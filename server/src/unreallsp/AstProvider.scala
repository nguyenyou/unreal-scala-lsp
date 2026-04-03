package unreallsp

class AstProvider extends LanguageProvider {
  private val indexer = Indexer()

  def indexWorkspace(root: java.io.File): Unit = indexer.indexDirectory(root)

  def didOpen(uri: String, text: String): Unit = {
    indexer.markOpen(uri)
    indexer.updateFile(uri, text)
  }

  def didChange(uri: String, text: String): Unit = indexer.updateFile(uri, text)

  def didClose(uri: String): Unit = indexer.markClosed(uri)

  def reindexFile(uri: String, file: java.io.File): Unit = indexer.reindexFile(uri, file)

  def removeFile(uri: String): Unit = indexer.removeFile(uri)

  def isOpen(uri: String): Boolean = indexer.isOpen(uri)

  def definition(uri: String, line: Int, col: Int): List[SymbolLocation] =
    indexer.findDefinition(uri, line, col)

  def references(uri: String, line: Int, col: Int, includeDeclaration: Boolean): List[SymbolLocation] =
    indexer.findReferences(uri, line, col, includeDeclaration)

  def wordAtPosition(uri: String, line: Int, col: Int): Option[String] =
    indexer.wordAtPosition(uri, line, col)

  def uniqueSymbolNames: Int = indexer.uniqueSymbolNames
  def indexedFiles: Int = indexer.indexedFiles
}
