package unreallsp.compiler

import unreallsp.core.{LanguageProvider, SymbolLocation, log, debug}
import unreallsp.indexer.AstProvider

/** Hybrid provider: PC for definition (exact), AST for references (project-wide).
  * Falls back to AST for definition when PC returns no results.
  */
class HybridProvider extends LanguageProvider {
  private val ast = AstProvider()
  private val pc = CompilerProvider()

  def indexWorkspace(root: java.io.File): Unit = {
    ast.indexWorkspace(root)
    pc.indexWorkspace(root)
  }

  def didOpen(uri: String, text: String): Unit = {
    ast.didOpen(uri, text)
    pc.didOpen(uri, text)
  }

  def didChange(uri: String, text: String): Unit = {
    ast.didChange(uri, text)
    pc.didChange(uri, text)
  }

  def didClose(uri: String): Unit = {
    ast.didClose(uri)
    pc.didClose(uri)
  }

  def reindexFile(uri: String, file: java.io.File): Unit = {
    ast.reindexFile(uri, file)
    pc.reindexFile(uri, file)
  }

  def removeFile(uri: String): Unit = {
    ast.removeFile(uri)
    pc.removeFile(uri)
  }

  def isOpen(uri: String): Boolean = ast.isOpen(uri)

  def definition(uri: String, line: Int, col: Int): List[SymbolLocation] = {
    val pcResult = pc.definition(uri, line, col)
    if (pcResult.nonEmpty) {
      pcResult
    } else {
      debug(s"hybrid: PC returned no results, falling back to AST")
      ast.definition(uri, line, col)
    }
  }

  // References: always AST (project-wide)
  def references(uri: String, line: Int, col: Int, includeDeclaration: Boolean): List[SymbolLocation] = {
    ast.references(uri, line, col, includeDeclaration)
  }

  def wordAtPosition(uri: String, line: Int, col: Int): Option[String] = {
    ast.wordAtPosition(uri, line, col)
  }

  def uniqueSymbolNames: Int = ast.uniqueSymbolNames

  def indexedFiles: Int = ast.indexedFiles

  override def shutdown(): Unit = {
    ast.shutdown()
    pc.shutdown()
  }
}
