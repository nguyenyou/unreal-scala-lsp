package unreallsp.indexer

import unreallsp.core.{LanguageProvider, SymbolLocation, log}

class CompilerProvider extends LanguageProvider {

  def indexWorkspace(root: java.io.File): Unit = log("compiler-precise: indexWorkspace not yet implemented")

  def didOpen(uri: String, text: String): Unit = log(s"compiler-precise: didOpen $uri — not yet implemented")

  def didChange(uri: String, text: String): Unit = log(s"compiler-precise: didChange $uri — not yet implemented")

  def didClose(uri: String): Unit = log(s"compiler-precise: didClose $uri — not yet implemented")

  def reindexFile(uri: String, file: java.io.File): Unit = log(s"compiler-precise: reindexFile $uri — not yet implemented")

  def removeFile(uri: String): Unit = log(s"compiler-precise: removeFile $uri — not yet implemented")

  def isOpen(uri: String): Boolean = false

  def definition(uri: String, line: Int, col: Int): List[SymbolLocation] = {
    log(s"compiler-precise: definition $uri:$line:$col — presentation compiler path not yet implemented")
    Nil
  }

  def references(uri: String, line: Int, col: Int, includeDeclaration: Boolean): List[SymbolLocation] = {
    log(s"compiler-precise: references $uri:$line:$col — presentation compiler path not yet implemented")
    Nil
  }

  def wordAtPosition(uri: String, line: Int, col: Int): Option[String] = None

  def uniqueSymbolNames: Int = 0

  def indexedFiles: Int = 0
}
