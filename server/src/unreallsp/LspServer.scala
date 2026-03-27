package unreallsp

import ujson.*

/** LSP server that dispatches JSON-RPC methods to handlers. */
class LspServer(rpc: JsonRpc):
  private val indexer = ScalaIndexer()

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
          case "textDocument/definition"  => id.foreach(i => handleDefinition(i, params))
          case "textDocument/references" => id.foreach(i => handleReferences(i, params))
          case "workspace/didChangeWatchedFiles" => handleDidChangeWatchedFiles(params)
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

    log("unreal-scala-lsp — source-only Scala language server")
    log("no compiler, no build server — just tokens and text")
    log("")
    log("features:")
    log("  go-to-definition    name-based lookup from a two-tier index")
    log("                      tier 1: fast token scan (batch, parallel via virtual threads)")
    log("                      tier 2: full Scalameta AST parse (open files, accurate positions)")
    log("  find-references     text search with word-boundary matching across all indexed files")
    log("  file watching       auto-reindex on create/change/delete via workspace/didChangeWatchedFiles")
    log("")
    log(s"initialize: ${folders.size} workspace folders")

    if folders.nonEmpty then
      for folder <- folders do
        val uri = folder("uri").str
        val path = java.net.URI(uri).getPath
        log(s"indexing $path ...")
        val startTime = System.nanoTime()
        indexer.indexDirectory(java.io.File(path))
        val elapsed = (System.nanoTime() - startTime) / 1_000_000
        log(s"done in ${elapsed}ms — now tracking ${indexer.uniqueSymbolNames} unique symbol names, collected across ${indexer.indexedFiles} files")
    else
      params.obj.get("rootUri").foreach: v =>
        if !v.isNull then
          val path = java.net.URI(v.str).getPath
          log(s"indexing $path ...")
          val startTime = System.nanoTime()
          indexer.indexDirectory(java.io.File(path))
          val elapsed = (System.nanoTime() - startTime) / 1_000_000
          log(s"done in ${elapsed}ms — now tracking ${indexer.uniqueSymbolNames} unique symbol names, collected across ${indexer.indexedFiles} files")

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
    indexer.markOpen(uri)
    indexer.updateFile(uri, text)
    log(s"  now tracking ${indexer.uniqueSymbolNames} unique symbol names, collected across ${indexer.indexedFiles} files")

  private def handleDidChange(params: ujson.Value): Unit =
    val uri = params("textDocument")("uri").str
    val changes = params("contentChanges").arr
    val text = changes.last("text").str
    log(s"didChange: $uri (${text.length} chars)")
    indexer.updateFile(uri, text)

  private def handleDefinition(id: ujson.Value, params: ujson.Value): Unit =
    val uri = params("textDocument")("uri").str
    val line = params("position")("line").num.toInt
    val col = params("position")("character").num.toInt

    val word = indexer.wordAtPosition(uri, line, col)
    log(s"definition: $uri:$line:$col word=$word")

    val locations = indexer.findDefinition(uri, line, col)
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

    val word = indexer.wordAtPosition(uri, line, col)
    log(s"references: $uri:$line:$col word=$word includeDeclaration=$includeDeclaration")

    val locations = indexer.findReferences(uri, line, col, includeDeclaration)
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
              )
            )
          )
        )
      )
    ))

  private def handleDidClose(params: ujson.Value): Unit =
    val uri = params("textDocument")("uri").str
    log(s"didClose: $uri")
    indexer.markClosed(uri)

  private def handleDidChangeWatchedFiles(params: ujson.Value): Unit =
    val changes = params("changes").arr
    var reindexed = 0
    var removed = 0
    var skipped = 0
    for change <- changes do
      val uri = change("uri").str
      val changeType = change("type").num.toInt
      if indexer.isOpen(uri) then
        skipped += 1
      else
        changeType match
          case 1 | 2 => // Created or Changed
            val path = java.net.URI(uri).getPath
            val file = java.io.File(path)
            if file.exists() then
              indexer.reindexFile(uri, file)
              reindexed += 1
          case 3 => // Deleted
            indexer.removeFile(uri)
            removed += 1
          case _ => ()
    log(s"didChangeWatchedFiles: ${changes.size} events — reindexed $reindexed, removed $removed, skipped $skipped open — now tracking ${indexer.uniqueSymbolNames} unique symbol names, collected across ${indexer.indexedFiles} files")
