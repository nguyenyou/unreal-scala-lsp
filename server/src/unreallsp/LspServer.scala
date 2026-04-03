package unreallsp

import ujson.*
import scala.compiletime.uninitialized

/** LSP server that dispatches JSON-RPC methods to handlers. */
class LspServer(rpc: JsonRpc):
  private var provider: LanguageProvider = uninitialized

  def loop(): Unit =
    var running = true
    while running do
      try
        val msg = rpc.read()
        val method = msg.obj.get("method").map(_.str).getOrElse("")
        val id = msg.obj.get("id")
        val params = msg.obj.getOrElse("params", ujson.Obj())

        method match
          case "initialize"              => handleInitialize(id.get, params)
          case "initialized"             => handleInitialized()
          case "shutdown"                => id.foreach(i => rpc.respond(i, ujson.Null)); running = false
          case "exit"                    => running = false
          case "textDocument/didOpen"    => handleDidOpen(params)
          case "textDocument/didChange"  => handleDidChange(params)
          case "textDocument/didClose"   => handleDidClose(params)
          case "textDocument/didSave"    => () // no-op
          case "$/setTrace"              => () // no-op
          case "textDocument/definition"  => id.foreach(i => handleDefinition(i, params))
          case "textDocument/references" => id.foreach(i => handleReferences(i, params))
          case "workspace/didChangeWatchedFiles" => handleDidChangeWatchedFiles(params)
          case ""                        => () // response message (no method) — ignore
          case other                     => log(s"unhandled: $other")
      catch
        case e: RuntimeException if e.getMessage == "EOF" =>
          running = false
        case e: Exception =>
          log(s"error: ${e.getMessage}")

  private def handleInitialize(id: ujson.Value, params: ujson.Value): Unit =
    val folders = params.obj.get("workspaceFolders")
      .flatMap(v => if v.isNull then None else Some(v.arr.toList))
      .getOrElse(Nil)

    val compilerPrecise = params.obj.get("initializationOptions")
      .flatMap(v => if v.isNull then None else v.obj.get("compilerPrecise"))
      .exists(_.bool)

    provider = if compilerPrecise then CompilerProvider() else AstProvider()

    log("unreal-scala-lsp")
    log("AI writes the code. You read it, navigate it, review it.")
    if compilerPrecise then
      log("Mode: compiler-precise (presentation compiler)")
    else
      log("Mode: AST-based (fast, no compiler)")
    log("")
    log("  go-to-definition  · find-references  · file watching")
    log("")

    val workspaceRoots = if folders.nonEmpty then
      folders.map(f => java.net.URI(f("uri").str).getPath)
    else
      params.obj.get("rootUri").flatMap(v => if v.isNull then None else Some(java.net.URI(v.str).getPath)).toList

    for path <- workspaceRoots do
      log(s"indexing $path ...")
      val startTime = System.nanoTime()
      provider.indexWorkspace(java.io.File(path))
      val elapsed = (System.nanoTime() - startTime) / 1_000_000
      log(s"done in ${elapsed}ms — now tracking ${provider.uniqueSymbolNames} unique symbol names, collected across ${provider.indexedFiles} files")

    rpc.respond(id, ujson.Obj(
      "capabilities" -> ujson.Obj(
        "textDocumentSync" -> 1, // Full
        "definitionProvider" -> true,
        "referencesProvider" -> true,
      )
    ))

  private def handleDidOpen(params: ujson.Value): Unit =
    val td = params("textDocument")
    val uri = td("uri").str
    val text = td("text").str
    log(s"didOpen: $uri (${text.length} chars)")
    provider.didOpen(uri, text)
    log(s"  now tracking ${provider.uniqueSymbolNames} unique symbol names, collected across ${provider.indexedFiles} files")

  private def handleDidChange(params: ujson.Value): Unit =
    val uri = params("textDocument")("uri").str
    val changes = params("contentChanges").arr
    val text = changes.last("text").str
    log(s"didChange: $uri (${text.length} chars)")
    provider.didChange(uri, text)

  private def handleDefinition(id: ujson.Value, params: ujson.Value): Unit =
    val uri = params("textDocument")("uri").str
    val line = params("position")("line").num.toInt
    val col = params("position")("character").num.toInt

    val word = provider.wordAtPosition(uri, line, col)
    log(s"definition: $uri:$line:$col word=$word")

    val locations = provider.definition(uri, line, col)
    log(s"  found ${locations.size} locations")

    val result = ujson.Arr.from(locations.map: loc =>
      ujson.Obj(
        "uri" -> loc.uri,
        "range" -> ujson.Obj(
          "start" -> ujson.Obj("line" -> loc.line, "character" -> loc.col),
          "end" -> ujson.Obj("line" -> loc.endLine, "character" -> loc.endCol),
        )
      )
    )
    rpc.respond(id, result)

  private def handleReferences(id: ujson.Value, params: ujson.Value): Unit =
    val uri = params("textDocument")("uri").str
    val line = params("position")("line").num.toInt
    val col = params("position")("character").num.toInt
    val includeDeclaration = params("context")("includeDeclaration").bool

    val word = provider.wordAtPosition(uri, line, col)
    log(s"references: $uri:$line:$col word=$word includeDeclaration=$includeDeclaration")

    val locations = provider.references(uri, line, col, includeDeclaration)
    log(s"  found ${locations.size} references")

    val result = ujson.Arr.from(locations.map: loc =>
      ujson.Obj(
        "uri" -> loc.uri,
        "range" -> ujson.Obj(
          "start" -> ujson.Obj("line" -> loc.line, "character" -> loc.col),
          "end" -> ujson.Obj("line" -> loc.endLine, "character" -> loc.endCol),
        )
      )
    )
    rpc.respond(id, result)

  private def handleInitialized(): Unit =
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

  private def handleDidClose(params: ujson.Value): Unit =
    val uri = params("textDocument")("uri").str
    log(s"didClose: $uri")
    provider.didClose(uri)

  private def handleDidChangeWatchedFiles(params: ujson.Value): Unit =
    val changes = params("changes").arr
    var reindexed = 0
    var removed = 0
    var skipped = 0
    for change <- changes do
      val uri = change("uri").str
      val changeType = change("type").num.toInt
      if provider.isOpen(uri) then
        skipped += 1
      else
        changeType match
          case 1 | 2 => // Created or Changed
            val path = java.net.URI(uri).getPath
            val file = java.io.File(path)
            if file.exists() then
              provider.reindexFile(uri, file)
              reindexed += 1
          case 3 => // Deleted
            provider.removeFile(uri)
            removed += 1
          case _ => ()
    log(s"didChangeWatchedFiles: ${changes.size} events — reindexed $reindexed, removed $removed, skipped $skipped open — now tracking ${provider.uniqueSymbolNames} unique symbol names, collected across ${provider.indexedFiles} files")
