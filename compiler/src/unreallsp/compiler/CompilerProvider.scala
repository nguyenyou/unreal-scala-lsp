package unreallsp.compiler

import unreallsp.core.{LanguageProvider, MillModule, MillWorkspace, SymbolLocation, log, debug}
import java.net.URI
import java.nio.file.{Files, Path}
import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*
import scala.collection.mutable
import dotty.tools.pc.ScalaPresentationCompiler
import org.eclipse.lsp4j.Location

class CompilerProvider extends LanguageProvider {
  private var allModules: List[MillModule] = Nil
  private var workspaceRoot: java.io.File = uninitialized
  private val compilers = mutable.Map.empty[String, ScalaPresentationCompiler]
  private val fileContents = mutable.Map.empty[String, String]
  private val openFiles = mutable.Set.empty[String]

  def indexWorkspace(root: java.io.File): Unit = {
    workspaceRoot = root
    allModules = MillWorkspace.discover(root)
    log(s"compiler-precise: discovered ${allModules.size} Mill modules")
    for (m <- allModules) {
      log(s"  ${m.name}: ${m.classpath.size} classpath entries, scala ${m.scalaVersion}")
    }
  }

  def didOpen(uri: String, text: String): Unit = {
    openFiles += uri
    fileContents(uri) = text
    debug(s"compiler-precise: didOpen $uri, module=${moduleNameForUri(uri)}")
  }

  def didChange(uri: String, text: String): Unit = {
    fileContents(uri) = text
  }

  def didClose(uri: String): Unit = {
    openFiles -= uri
    fileContents.remove(uri)
    debug(s"compiler-precise: didClose $uri")
    compilerForUri(uri).foreach(_.didClose(URI(uri)))
  }

  def reindexFile(uri: String, file: java.io.File): Unit = () // PC compiles on demand

  def removeFile(uri: String): Unit = {
    fileContents.remove(uri)
  }

  def isOpen(uri: String): Boolean = openFiles.contains(uri)

  def definition(uri: String, line: Int, col: Int): List[SymbolLocation] = {
    fileContents.get(uri) match {
      case None => Nil
      case Some(text) => {
        val offset = lineColToOffset(text, line, col)
        val params = SimpleOffsetParams(URI(uri), text, offset)
        compilerForUri(uri) match {
          case None => {
            debug(s"compiler-precise: no module found for $uri")
            Nil
          }
          case Some(pc) => {
            try {
              debug(s"compiler-precise: calling pc.definition(offset=$offset)")
              val result = pc.definition(params).get()
              debug(s"compiler-precise: definition returned symbol=${result.symbol()}, ${result.locations().size()} locations")
              result.locations().asScala.toList.map(toSymbolLocation)
            } catch {
              case e: Exception => {
                log(s"compiler-precise: definition error: ${e.getMessage}")
                debug(s"  ${e.getStackTrace.take(5).mkString("\n  ")}")
                Nil
              }
            }
          }
        }
      }
    }
  }

  def references(uri: String, line: Int, col: Int, includeDeclaration: Boolean): List[SymbolLocation] = {
    fileContents.get(uri) match {
      case None => Nil
      case Some(text) => {
        val offset = lineColToOffset(text, line, col)
        val fileParams = SimpleVirtualFileParams(URI(uri), text)
        val request = SimpleReferencesRequest(fileParams, includeDeclaration, offset)
        compilerForUri(uri) match {
          case None => {
            debug(s"compiler-precise: no module found for $uri")
            Nil
          }
          case Some(pc) => {
            try {
              debug(s"compiler-precise: calling pc.references(offset=$offset, includeDecl=$includeDeclaration)")
              val results = pc.references(request).get()
              debug(s"compiler-precise: references returned ${results.size()} result groups")
              results.asScala.toList.flatMap { r =>
                r.locations().asScala.map(toSymbolLocation)
              }
            } catch {
              case e: Exception => {
                log(s"compiler-precise: references error: ${e.getMessage}")
                debug(s"  ${e.getStackTrace.take(5).mkString("\n  ")}")
                Nil
              }
            }
          }
        }
      }
    }
  }

  def wordAtPosition(uri: String, line: Int, col: Int): Option[String] = {
    fileContents.get(uri).flatMap { text =>
      val lines = text.split("\n", -1)
      if (line < lines.length) {
        val l = lines(line)
        if (col <= l.length) {
          var start = col
          while (start > 0 && isIdentChar(l(start - 1))) { start -= 1 }
          var end = col
          while (end < l.length && isIdentChar(l(end))) { end += 1 }
          if (start < end) { Some(l.substring(start, end)) } else { None }
        } else { None }
      } else { None }
    }
  }

  def uniqueSymbolNames: Int = 0

  def indexedFiles: Int = fileContents.size

  // ── Internal ──────────────────────────────────────────────────────────────

  private def moduleNameForUri(uri: String): String = {
    val path = URI(uri).getPath
    MillWorkspace.findModule(allModules, path).map(_.name).getOrElse("<unknown>")
  }

  private def compilerForUri(uri: String): Option[ScalaPresentationCompiler] = {
    val path = URI(uri).getPath
    MillWorkspace.findModule(allModules, path).map { mod =>
      compilers.getOrElseUpdate(mod.name, createCompiler(mod))
    }
  }

  private def createCompiler(mod: MillModule): ScalaPresentationCompiler = {
    log(s"compiler-precise: initializing PC for module '${mod.name}'")
    debug(s"  classpath (${mod.classpath.size} entries):")
    for (p <- mod.classpath) {
      debug(s"    $p")
    }
    debug(s"  scalacOptions: ${mod.scalacOptions}")
    val cp = mod.classpath.map(p => Path.of(p))
    val sourceCacheDir = workspaceRoot.toPath.resolve(".scalex").resolve("sources-cache")
    val search = WorkspaceSymbolSearch(allModules, sourceCacheDir)
    ScalaPresentationCompiler(
      buildTargetIdentifier = mod.name,
      classpath = cp,
      options = mod.scalacOptions,
      search = search,
    )
  }

  private def lineColToOffset(text: String, line: Int, col: Int): Int = {
    var offset = 0
    var currentLine = 0
    while (currentLine < line && offset < text.length) {
      if (text.charAt(offset) == '\n') { currentLine += 1 }
      offset += 1
    }
    math.min(offset + col, text.length)
  }

  private def toSymbolLocation(loc: Location): SymbolLocation = {
    val range = loc.getRange
    SymbolLocation(
      uri = loc.getUri,
      line = range.getStart.getLine,
      col = range.getStart.getCharacter,
      endLine = range.getEnd.getLine,
      endCol = range.getEnd.getCharacter,
    )
  }

  private def isIdentChar(c: Char): Boolean = c.isLetterOrDigit || c == '_'
}
