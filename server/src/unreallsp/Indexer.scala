package unreallsp

import scala.meta.*
import scala.meta.tokens.Token
import scala.collection.mutable
import java.util.concurrent.{Callable, Executors}
import com.github.javaparser.{JavaParser as JP, ParserConfiguration}
import com.github.javaparser.ast.body.*

/** Two-tier indexer for Scala and Java.
  * Tier 1 (scanFile/scanJavaFile): Fast batch indexing.
  * Tier 2 (updateScalaFile/updateJavaFile): Full parse for open files — accurate positions.
  */
class Indexer:
  private val definitions = mutable.Map.empty[String, mutable.ListBuffer[SymbolLocation]]
  private val fileSymbols = mutable.Map.empty[String, Set[String]]
  private val fileContents = mutable.Map.empty[String, String]
  private val openFiles = mutable.Set.empty[String]

  def markOpen(uri: String): Unit = openFiles += uri
  def markClosed(uri: String): Unit = openFiles -= uri
  def isOpen(uri: String): Boolean = openFiles.contains(uri)

  case class SymbolLocation(uri: String, line: Int, col: Int, endLine: Int, endCol: Int)

  def uniqueSymbolNames: Int = definitions.size
  def indexedFiles: Int = fileSymbols.size

  private val skipDirs = Set("out", ".git", ".metals", ".bsp", ".idea", "node_modules", "target")

  private case class FileResult(
    uri: String,
    text: String,
    symbols: List[(String, SymbolLocation)]
  )

  private def isJavaFile(name: String): Boolean = name.endsWith(".java")
  private def isScalaFile(name: String): Boolean = name.endsWith(".scala")

  private def collectFiles(dir: java.io.File): List[java.io.File] =
    if !dir.isDirectory then return Nil
    val files = dir.listFiles()
    if files == null then return Nil
    files.toList.flatMap: f =>
      if f.isDirectory && !skipDirs.contains(f.getName) then collectFiles(f)
      else if f.isFile && (isScalaFile(f.getName) || isJavaFile(f.getName)) then List(f)
      else Nil

  // ── Scala: Tier 1 (token-only scan) ──────────────────────────────────────

  private def isDefKeyword(tok: Token): Boolean = tok match
    case _: Token.KwClass  => true
    case _: Token.KwTrait  => true
    case _: Token.KwObject => true
    case _: Token.KwEnum   => true
    case _: Token.KwDef    => true
    case _: Token.KwVal    => true
    case _: Token.KwType   => true
    case _: Token.KwGiven  => true
    case _                 => false

  private def readText(file: java.io.File): Option[String] =
    try Some(java.nio.file.Files.readString(file.toPath))
    catch case _: Exception => None

  private def scanScalaFile(file: java.io.File, uriOverride: Option[String] = None): FileResult =
    val uri = uriOverride.getOrElse(file.toURI.toString)
    readText(file) match
      case None => FileResult(uri, "", Nil)
      case Some(text) =>
        val symbols = List.newBuilder[(String, SymbolLocation)]

        try
          implicit val dialect: Dialect = dialects.Scala3
          val input = Input.VirtualFile(uri, text)
          val tokens = input.tokenize.get.tokens
          var depth = 0
          var i = 0

          while i < tokens.length do
            val tok = tokens(i)
            tok match
              case _: Token.LeftBrace  => depth += 1
              case _: Token.RightBrace => depth = math.max(0, depth - 1)
              case _ if isDefKeyword(tok) && depth <= 1 =>
                var j = i + 1
                while j < tokens.length && !tokens(j).isInstanceOf[Token.Ident] do
                  tokens(j) match
                    case _: Token.LeftBrace | _: Token.LeftParen | _: Token.Semicolon | _: Token.EOF =>
                      j = tokens.length
                    case _ => j += 1
                if j < tokens.length then
                  val ident = tokens(j).asInstanceOf[Token.Ident]
                  val pos = ident.pos
                  symbols += ((ident.value, SymbolLocation(uri, pos.startLine, pos.startColumn, pos.endLine, pos.endColumn)))
              case _ => ()
            i += 1
        catch
          case _: Exception => ()

        FileResult(uri, text, symbols.result())

  // ── Scala: Tier 2 (full AST parse) ───────────────────────────────────────

  private def updateScalaFile(uri: String, text: String): Unit =
    removeFile(uri)
    fileContents(uri) = text

    val symbolNames = mutable.Set.empty[String]

    try
      implicit val dialect: Dialect = dialects.Scala3
      val input = Input.VirtualFile(uri, text)
      val tree = input.parse[Source].get

      def addSymbol(name: String, pos: scala.meta.Position): Unit =
        val loc = SymbolLocation(uri, pos.startLine, pos.startColumn, pos.endLine, pos.endColumn)
        definitions.getOrElseUpdate(name, mutable.ListBuffer.empty) += loc
        symbolNames += name

      def traverse(tree: Tree): Unit = tree match
        case d: Defn.Class  => addSymbol(d.name.value, d.name.pos); d.children.foreach(traverse)
        case d: Defn.Trait  => addSymbol(d.name.value, d.name.pos); d.children.foreach(traverse)
        case d: Defn.Object => addSymbol(d.name.value, d.name.pos); d.children.foreach(traverse)
        case d: Defn.Def    => addSymbol(d.name.value, d.name.pos); d.children.foreach(traverse)
        case d: Defn.Val    =>
          d.pats.foreach:
            case Pat.Var(name) => addSymbol(name.value, name.pos)
            case _             =>
          d.children.foreach(traverse)
        case d: Defn.Type   => addSymbol(d.name.value, d.name.pos); d.children.foreach(traverse)
        case d: Defn.Enum   => addSymbol(d.name.value, d.name.pos); d.children.foreach(traverse)
        case d: Defn.Given if d.name.value.nonEmpty =>
          addSymbol(d.name.value, d.name.pos); d.children.foreach(traverse)
        case d: Pkg.Object  => addSymbol(d.name.value, d.name.pos); d.children.foreach(traverse)
        case other           => other.children.foreach(traverse)

      traverse(tree)
    catch
      case _: Exception => ()

    fileSymbols(uri) = symbolNames.toSet

  // ── Java: Tier 1 (JavaParser scan — used for both batch and single-file) ─

  private val javaParserConfig =
    val config = new ParserConfiguration()
    config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
    config

  private def scanJavaFile(file: java.io.File, uriOverride: Option[String] = None): FileResult =
    val uri = uriOverride.getOrElse(file.toURI.toString)
    readText(file) match
      case None       => FileResult(uri, "", Nil)
      case Some(text) => parseJavaText(uri, text)

  private def parseJavaText(uri: String, text: String): FileResult =
    val symbols = List.newBuilder[(String, SymbolLocation)]

    try
      val parser = new JP(javaParserConfig)
      val result = parser.parse(text)
      if result.isSuccessful then
        val cu = result.getResult.get()

        def addSym(name: String, node: com.github.javaparser.ast.Node): Unit =
          if node.getBegin.isPresent && node.getEnd.isPresent then
            val begin = node.getBegin.get()
            val end = node.getEnd.get()
            // JavaParser lines are 1-based; LSP is 0-based
            symbols += ((name, SymbolLocation(uri, begin.line - 1, begin.column - 1, end.line - 1, end.column)))

        def visitType(td: TypeDeclaration[?]): Unit =
          addSym(td.getNameAsString, td.getName)

          // Methods
          td.getMethods.forEach: m =>
            addSym(m.getNameAsString, m.getName)

          // Fields
          td.getFields.forEach: f =>
            f.getVariables.forEach: v =>
              addSym(v.getNameAsString, v.getName)

          // Constructors
          td.getConstructors.forEach: c =>
            addSym(td.getNameAsString, c.getName)

          // Enum constants
          td match
            case ed: EnumDeclaration =>
              ed.getEntries.forEach: entry =>
                addSym(entry.getNameAsString, entry.getName)
            case _ =>

          // Nested types
          td.getMembers.forEach:
            case nested: TypeDeclaration[?] => visitType(nested)
            case _ =>

        cu.getTypes.forEach(td => visitType(td))
    catch
      case _: Exception | _: Error => ()

    FileResult(uri, text, symbols.result())

  // ── Java: Tier 2 (full parse for open files) ─────────────────────────────

  private def updateJavaFile(uri: String, text: String): Unit =
    removeFile(uri)
    fileContents(uri) = text
    val result = parseJavaText(uri, text)
    val names = mutable.Set.empty[String]
    for (name, loc) <- result.symbols do
      definitions.getOrElseUpdate(name, mutable.ListBuffer.empty) += loc
      names += name
    fileSymbols(uri) = names.toSet

  // ── Routing ───────────────────────────────────────────────────────────────

  private def mergeResult(result: FileResult): Unit =
    fileContents(result.uri) = result.text
    val names = mutable.Set.empty[String]
    for (name, loc) <- result.symbols do
      definitions.getOrElseUpdate(name, mutable.ListBuffer.empty) += loc
      names += name
    fileSymbols(result.uri) = names.toSet

  /** Re-scan a single file from disk. */
  def reindexFile(uri: String, file: java.io.File): Unit =
    removeFile(uri)
    val result =
      if isJavaFile(file.getName) then scanJavaFile(file, Some(uri))
      else scanScalaFile(file, Some(uri))
    mergeResult(result)

  /** Index all .scala and .java files under a directory using virtual threads. */
  def indexDirectory(dir: java.io.File): Unit =
    val files = collectFiles(dir)
    if files.isEmpty then return

    val executor = Executors.newVirtualThreadPerTaskExecutor()
    try
      val futures = files.map: f =>
        executor.submit(new Callable[FileResult] { def call() =
          if isJavaFile(f.getName) then scanJavaFile(f)
          else scanScalaFile(f)
        })
      for future <- futures do
        mergeResult(future.get())
    finally
      executor.shutdown()

  /** Update index for an open file (Tier 2 — full parse). */
  def updateFile(uri: String, text: String): Unit =
    if isJavaFile(uri) then updateJavaFile(uri, text)
    else updateScalaFile(uri, text)

  // ── Shared ────────────────────────────────────────────────────────────────

  def removeFile(uri: String): Unit =
    fileSymbols.remove(uri).foreach: names =>
      for name <- names do
        definitions.get(name).foreach: locs =>
          locs.filterInPlace(_.uri != uri)
          if locs.isEmpty then definitions.remove(name)
    fileContents.remove(uri)

  def findDefinition(uri: String, line: Int, col: Int): List[SymbolLocation] =
    wordAtPosition(uri, line, col) match
      case Some(w) => definitions.get(w).map(_.toList).getOrElse(Nil)
      case None => Nil

  /** Find all occurrences of a word across all indexed files. */
  def findReferences(uri: String, line: Int, col: Int, includeDeclaration: Boolean): List[SymbolLocation] =
    wordAtPosition(uri, line, col) match
      case None => Nil
      case Some(word) =>
        val results = mutable.ListBuffer.empty[SymbolLocation]

        for (fileUri, text) <- fileContents do
          val lines = text.split("\n", -1)
          var lineNum = 0
          while lineNum < lines.length do
            val l = lines(lineNum)
            var pos = 0
            while pos < l.length do
              val idx = l.indexOf(word, pos)
              if idx == -1 then pos = l.length
              else
                val before = idx == 0 || !isIdentChar(l(idx - 1))
                val after = idx + word.length >= l.length || !isIdentChar(l(idx + word.length))
                if before && after then
                  results += SymbolLocation(fileUri, lineNum, idx, lineNum, idx + word.length)
                pos = idx + 1
            lineNum += 1

        val refs = results.toList
        if includeDeclaration then refs
        else
          val defs = definitions.get(word).map(_.toSet).getOrElse(Set.empty)
          refs.filterNot(defs.contains)

  def wordAtPosition(uri: String, line: Int, col: Int): Option[String] =
    fileContents.get(uri).flatMap: text =>
      val lines = text.split("\n", -1)
      if line < lines.length then
        val l = lines(line)
        if col <= l.length then
          var start = col
          while start > 0 && isIdentChar(l(start - 1)) do start -= 1
          var end = col
          while end < l.length && isIdentChar(l(end)) do end += 1
          if start < end then Some(l.substring(start, end))
          else None
        else None
      else None

  private def isIdentChar(c: Char): Boolean =
    c.isLetterOrDigit || c == '_'
