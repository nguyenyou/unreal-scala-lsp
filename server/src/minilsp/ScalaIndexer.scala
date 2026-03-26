package minilsp

import scala.meta.*
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position as LspPosition
import org.eclipse.lsp4j.Range as LspRange
import scala.collection.mutable

/** Simple Scala indexer using Scalameta.
  * Indexes top-level definitions (class, trait, object, def, val, type, enum)
  * and resolves go-to-definition by matching the word under cursor.
  */
class ScalaIndexer:
  // symbol name -> list of (uri, line, col, endLine, endCol)
  private val definitions = mutable.Map.empty[String, mutable.ListBuffer[SymbolLocation]]
  // uri -> set of symbol names defined in that file
  private val fileSymbols = mutable.Map.empty[String, Set[String]]
  // uri -> source text (for word-under-cursor extraction)
  private val fileContents = mutable.Map.empty[String, String]

  case class SymbolLocation(uri: String, line: Int, col: Int, endLine: Int, endCol: Int)

  def symbolCount: Int = definitions.size
  def fileCount: Int = fileSymbols.size

  private val skipDirs = Set("out", ".git", ".metals", ".bsp", ".idea", "node_modules", "target")

  def indexDirectory(dir: java.io.File): Unit =
    if dir.isDirectory then
      val files = dir.listFiles()
      if files != null then files.foreach: f =>
        if f.isDirectory && !skipDirs.contains(f.getName) then indexDirectory(f)
        else if f.getName.endsWith(".scala") then
          val uri = f.toURI.toString
          val text = java.nio.file.Files.readString(f.toPath)
          updateFile(uri, text)

  def updateFile(uri: String, text: String): Unit =
    // Remove old symbols from this file
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

  def removeFile(uri: String): Unit =
    fileSymbols.remove(uri).foreach: names =>
      for name <- names do
        definitions.get(name).foreach: locs =>
          locs.filterInPlace(_.uri != uri)
          if locs.isEmpty then definitions.remove(name)
    fileContents.remove(uri)

  def findDefinition(uri: String, line: Int, col: Int): List[Location] =
    val word = wordAtPosition(uri, line, col)
    word match
      case Some(w) =>
        definitions.get(w) match
          case Some(locs) =>
            locs.toList.map: loc =>
              Location(
                loc.uri,
                LspRange(LspPosition(loc.line, loc.col), LspPosition(loc.endLine, loc.endCol))
              )
          case None => Nil
      case None => Nil

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
