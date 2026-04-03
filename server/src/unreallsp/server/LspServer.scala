package unreallsp.server

import unreallsp.core.{LanguageProvider, log, debug, setDebug, isDebugEnabled}
import unreallsp.rpc.JsonRpc
import unreallsp.indexer.AstProvider
import unreallsp.compiler.HybridProvider
import ujson.*
import scala.compiletime.uninitialized

/** LSP server that dispatches JSON-RPC methods to handlers. */
class LspServer(rpc: JsonRpc) {
  private var provider: LanguageProvider = uninitialized

  def loop(): Unit = {
    var running = true
    while (running) {
      try {
        val msg = rpc.read()
        val method = msg.obj.get("method").map(_.str).getOrElse("")
        val id = msg.obj.get("id")
        val params = msg.obj.getOrElse("params", ujson.Obj())

        debug(s"← $method${id.map(i => s" id=$i").getOrElse("")}")

        method match {
          case "initialize"              => handleInitialize(id.get, params)
          case "initialized"             => handleInitialized()
          case "shutdown"                => {
            log("shutdown")
            id.foreach(i => rpc.respond(i, ujson.Null))
            running = false
          }
          case "exit"                    => {
            log("exit")
            running = false
          }
          case "textDocument/didOpen"    => handleDidOpen(params)
          case "textDocument/didChange"  => handleDidChange(params)
          case "textDocument/didClose"   => handleDidClose(params)
          case "textDocument/didSave"    => debug("didSave (no-op)")
          case "$/setTrace"              => () // no-op
          case "textDocument/definition"  => id.foreach(i => handleDefinition(i, params))
          case "textDocument/references" => id.foreach(i => handleReferences(i, params))
          case "workspace/didChangeWatchedFiles" => handleDidChangeWatchedFiles(params)
          case ""                        => debug("response message (no method)")
          case other                     => log(s"unhandled: $other")
        }
      } catch {
        case e: RuntimeException if e.getMessage == "EOF" =>
          log("EOF — stopping")
          running = false
        case e: Exception =>
          log(s"error: ${e.getMessage}")
          debug(s"  stacktrace: ${e.getStackTrace.take(10).mkString("\n    ")}")
      }
    }
  }

  private def handleInitialize(id: ujson.Value, params: ujson.Value): Unit = {
    val folders = params.obj.get("workspaceFolders")
      .flatMap(v => if (v.isNull) { None } else { Some(v.arr.toList) })
      .getOrElse(Nil)

    val initOpts = params.obj.get("initializationOptions")
      .flatMap(v => if (v.isNull) { None } else { Some(v) })

    val usePresentationCompiler = initOpts
      .flatMap(v => v.obj.get("usePresentationCompiler"))
      .exists(_.bool)

    // Allow debug to be enabled via initializationOptions too
    val debugFromClient = initOpts
      .flatMap(v => v.obj.get("debug"))
      .exists(_.bool)
    if (debugFromClient && !isDebugEnabled) {
      setDebug(true)
      log("debug logging enabled via initializationOptions")
    }

    provider = if (usePresentationCompiler) { HybridProvider() } else { AstProvider() }

    log("unreal-scala-lsp")
    log("AI writes the code. You read it, navigate it, review it.")
    if (usePresentationCompiler) {
      log("Mode: hybrid (PC definition + AST references/fallback)")
    } else {
      log("Mode: AST-based (fast, no compiler)")
    }
    log("")
    log("  go-to-definition  · find-references  · file watching")
    log("")

    debug(s"initializationOptions: ${initOpts.map(ujson.write(_)).getOrElse("null")}")
    debug(s"usePresentationCompiler=$usePresentationCompiler debugEnabled=${isDebugEnabled}")

    val workspaceRoots = if (folders.nonEmpty) {
      folders.map(f => java.net.URI(f("uri").str).getPath)
    } else {
      params.obj.get("rootUri").flatMap(v => if (v.isNull) { None } else { Some(java.net.URI(v.str).getPath) }).toList
    }

    debug(s"workspace roots: ${workspaceRoots.mkString(", ")}")

    for (path <- workspaceRoots) {
      log(s"indexing $path ...")
      val startTime = System.nanoTime()
      provider.indexWorkspace(java.io.File(path))
      val elapsed = (System.nanoTime() - startTime) / 1_000_000
      log(s"done in ${elapsed}ms — now tracking ${provider.uniqueSymbolNames} unique symbol names, collected across ${provider.indexedFiles} files")
    }

    rpc.respond(id, ujson.Obj(
      "capabilities" -> ujson.Obj(
        "textDocumentSync" -> 1, // Full
        "definitionProvider" -> true,
        "referencesProvider" -> true,
      )
    ))
  }

  private def handleDidOpen(params: ujson.Value): Unit = {
    val td = params("textDocument")
    val uri = td("uri").str
    val text = td("text").str
    log(s"didOpen: $uri (${text.length} chars)")
    val startTime = System.nanoTime()
    provider.didOpen(uri, text)
    val elapsed = (System.nanoTime() - startTime) / 1_000_000
    debug(s"  didOpen took ${elapsed}ms")
    log(s"  now tracking ${provider.uniqueSymbolNames} unique symbol names, collected across ${provider.indexedFiles} files")
  }

  private def handleDidChange(params: ujson.Value): Unit = {
    val uri = params("textDocument")("uri").str
    val changes = params("contentChanges").arr
    val text = changes.last("text").str
    debug(s"didChange: $uri (${text.length} chars)")
    provider.didChange(uri, text)
  }

  private def handleDefinition(id: ujson.Value, params: ujson.Value): Unit = {
    val uri = params("textDocument")("uri").str
    val line = params("position")("line").num.toInt
    val col = params("position")("character").num.toInt

    val word = provider.wordAtPosition(uri, line, col)
    log(s"definition: $uri:$line:$col word=$word")

    val startTime = System.nanoTime()
    val locations = provider.definition(uri, line, col)
    val elapsed = (System.nanoTime() - startTime) / 1_000_000

    log(s"  found ${locations.size} locations in ${elapsed}ms")
    for (loc <- locations) {
      debug(s"  → ${loc.uri}:${loc.line}:${loc.col}")
    }

    val result = ujson.Arr.from(locations.map { loc =>
      ujson.Obj(
        "uri" -> loc.uri,
        "range" -> ujson.Obj(
          "start" -> ujson.Obj("line" -> loc.line, "character" -> loc.col),
          "end" -> ujson.Obj("line" -> loc.endLine, "character" -> loc.endCol),
        )
      )
    })
    rpc.respond(id, result)
  }

  private def handleReferences(id: ujson.Value, params: ujson.Value): Unit = {
    val uri = params("textDocument")("uri").str
    val line = params("position")("line").num.toInt
    val col = params("position")("character").num.toInt
    val includeDeclaration = params("context")("includeDeclaration").bool

    val word = provider.wordAtPosition(uri, line, col)
    log(s"references: $uri:$line:$col word=$word includeDeclaration=$includeDeclaration")

    val startTime = System.nanoTime()
    val locations = provider.references(uri, line, col, includeDeclaration)
    val elapsed = (System.nanoTime() - startTime) / 1_000_000

    log(s"  found ${locations.size} references in ${elapsed}ms")

    val result = ujson.Arr.from(locations.map { loc =>
      ujson.Obj(
        "uri" -> loc.uri,
        "range" -> ujson.Obj(
          "start" -> ujson.Obj("line" -> loc.line, "character" -> loc.col),
          "end" -> ujson.Obj("line" -> loc.endLine, "character" -> loc.endCol),
        )
      )
    })
    rpc.respond(id, result)
  }

  private def handleInitialized(): Unit = {
    log("initialized — registering file watchers")
    rpc.sendRequest("client/registerCapability", ujson.Obj(
      "registrations" -> ujson.Arr(
        ujson.Obj(
          "id" -> "scala-file-watcher",
          "method" -> "workspace/didChangeWatchedFiles",
          "registerOptions" -> ujson.Obj(
            "watchers" -> ujson.Arr(
              ujson.Obj(
                "globPattern" -> "**/*.scala",
                "kind" -> 7
              ),
              ujson.Obj(
                "globPattern" -> "**/*.java",
                "kind" -> 7
              )
            )
          )
        )
      )
    ))
  }

  private def handleDidClose(params: ujson.Value): Unit = {
    val uri = params("textDocument")("uri").str
    log(s"didClose: $uri")
    provider.didClose(uri)
  }

  private def handleDidChangeWatchedFiles(params: ujson.Value): Unit = {
    val changes = params("changes").arr
    var reindexed = 0
    var removed = 0
    var skipped = 0
    for (change <- changes) {
      val uri = change("uri").str
      val changeType = change("type").num.toInt
      if (provider.isOpen(uri)) {
        skipped += 1
        debug(s"  skip open file: $uri")
      } else {
        changeType match {
          case 1 | 2 => // Created or Changed
            val path = java.net.URI(uri).getPath
            val file = java.io.File(path)
            if (file.exists()) {
              debug(s"  reindex: $uri")
              provider.reindexFile(uri, file)
              reindexed += 1
            }
          case 3 => // Deleted
            debug(s"  remove: $uri")
            provider.removeFile(uri)
            removed += 1
          case _ => ()
        }
      }
    }
    log(s"didChangeWatchedFiles: ${changes.size} events — reindexed $reindexed, removed $removed, skipped $skipped open — now tracking ${provider.uniqueSymbolNames} unique symbol names, collected across ${provider.indexedFiles} files")
  }
}
