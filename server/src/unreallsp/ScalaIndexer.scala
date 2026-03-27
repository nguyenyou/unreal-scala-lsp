package unreallsp

import scala.meta.*
import scala.meta.tokens.Token
import scala.collection.mutable
import java.util.concurrent.{Callable, Executors}

/** Two-tier Scala indexer.
  * Tier 1 (scanFile): Token-only scan for batch indexing — no AST, fast.
  * Tier 2 (updateFile): Full Scalameta parse for open files — accurate positions.
  */
class ScalaIndexer:
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

  private def collectFiles(dir: java.io.File): List[java.io.File] =
    if !dir.isDirectory then return Nil
    val files = dir.listFiles()
    if files == null then return Nil
    files.toList.flatMap: f =>
      if f.isDirectory && !skipDirs.contains(f.getName) then collectFiles(f)
      else if f.isFile && f.getName.endsWith(".scala") then List(f)
      else Nil

  // --- Tier 1: Token-only scan (batch indexing) ---

  /** Definition-introducing keywords. */
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

  /** Scan a file using tokenizer only — no AST. Thread-safe. */
  private def scanFile(file: java.io.File, uriOverride: Option[String] = None): FileResult =
    val uri = uriOverride.getOrElse(file.toURI.toString)
    val text = java.nio.file.Files.readString(file.toPath)
    val symbols = List.newBuilder[(String, SymbolLocation)]

    try
      implicit val dialect: Dialect = dialects.Scala3
      val input = Input.VirtualFile(uri, text)
      val tokens = input.tokenize.get.tokens
      var depth = 0 // brace depth
      var i = 0

      while i < tokens.length do
        val tok = tokens(i)
        tok match
          case _: Token.LeftBrace  => depth += 1
          case _: Token.RightBrace => depth = math.max(0, depth - 1)
          case _ if isDefKeyword(tok) && depth <= 1 =>
            // Find the next Ident token (skip whitespace, comments, modifiers)
            var j = i + 1
            while j < tokens.length && !tokens(j).isInstanceOf[Token.Ident] do
              tokens(j) match
                case _: Token.LeftBrace | _: Token.LeftParen | _: Token.Semicolon | _: Token.EOF =>
                  j = tokens.length // bail — no ident follows
                case _ => j += 1
            if j < tokens.length then
              val ident = tokens(j).asInstanceOf[Token.Ident]
              val pos = ident.pos
              symbols += ((ident.value, SymbolLocation(uri, pos.startLine, pos.startColumn, pos.endLine, pos.endColumn)))
          case _ => ()
        i += 1
    catch
      case _: Exception => // skip files that fail to tokenize

    FileResult(uri, text, symbols.result())

  private def mergeResult(result: FileResult): Unit =
    fileContents(result.uri) = result.text
    val names = mutable.Set.empty[String]
    for (name, loc) <- result.symbols do
      definitions.getOrElseUpdate(name, mutable.ListBuffer.empty) += loc
      names += name
    fileSymbols(result.uri) = names.toSet

  /** Re-scan a single file from disk (Tier 1 token scan). */
  def reindexFile(uri: String, file: java.io.File): Unit =
    removeFile(uri)
    val result = scanFile(file, Some(uri))
    mergeResult(result)

  /** Index all .scala files under a directory using token scan + virtual threads. */
  def indexDirectory(dir: java.io.File): Unit =
    val files = collectFiles(dir)
    if files.isEmpty then return

    val executor = Executors.newVirtualThreadPerTaskExecutor()
    try
      val futures = files.map: f =>
        executor.submit(new Callable[FileResult] { def call() = scanFile(f) })
      for future <- futures do
        mergeResult(future.get())
    finally
      executor.shutdown()

  // --- Tier 2: Full parse (single file, didOpen/didChange) ---

  def updateFile(uri: String, text: String): Unit =
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
      case _: Exception => // skip files that fail to parse

    fileSymbols(uri) = symbolNames.toSet

  // --- Shared ---

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

        // Search every indexed file's text for word-boundary matches
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
                // Check word boundaries
                val before = idx == 0 || !isIdentChar(l(idx - 1))
                val after = idx + word.length >= l.length || !isIdentChar(l(idx + word.length))
                if before && after then
                  results += SymbolLocation(fileUri, lineNum, idx, lineNum, idx + word.length)
                pos = idx + 1
            lineNum += 1

        val refs = results.toList
        if includeDeclaration then refs
        else
          // Exclude locations that are definitions
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
