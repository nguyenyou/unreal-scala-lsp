package unreallsp.core

trait LanguageProvider {
  def indexWorkspace(root: java.io.File): Unit
  def didOpen(uri: String, text: String): Unit
  def didChange(uri: String, text: String): Unit
  def didClose(uri: String): Unit
  def reindexFile(uri: String, file: java.io.File): Unit
  def removeFile(uri: String): Unit
  def isOpen(uri: String): Boolean
  def definition(uri: String, line: Int, col: Int): List[SymbolLocation]
  def references(uri: String, line: Int, col: Int, includeDeclaration: Boolean): List[SymbolLocation]
  def wordAtPosition(uri: String, line: Int, col: Int): Option[String]
  def uniqueSymbolNames: Int
  def indexedFiles: Int
}
