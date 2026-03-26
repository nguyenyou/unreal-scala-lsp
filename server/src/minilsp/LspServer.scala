package minilsp

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
          case "initialized"             => log("initialized")
          case "shutdown"                => id.foreach(i => rpc.respond(i, ujson.Null)); running = false
          case "exit"                    => running = false
          case "textDocument/didOpen"    => handleDidOpen(params)
          case "textDocument/didChange"  => handleDidChange(params)
          case "textDocument/didClose"   => () // no-op
          case "textDocument/didSave"    => () // no-op
          case "textDocument/definition" => id.foreach(i => handleDefinition(i, params))
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

    log(s"initialize: ${folders.size} workspace folders")

    if folders.nonEmpty then
      for folder <- folders do
        val uri = folder("uri").str
        val path = java.net.URI(uri).getPath
        log(s"indexing $path ...")
        val startTime = System.nanoTime()
        indexer.indexDirectory(java.io.File(path))
        val elapsed = (System.nanoTime() - startTime) / 1_000_000
        log(s"indexed ${indexer.symbolCount} symbols in ${indexer.fileCount} files in ${elapsed}ms")
    else
      params.obj.get("rootUri").foreach: v =>
        if !v.isNull then
          val path = java.net.URI(v.str).getPath
          log(s"indexing $path ...")
          val startTime = System.nanoTime()
          indexer.indexDirectory(java.io.File(path))
          val elapsed = (System.nanoTime() - startTime) / 1_000_000
          log(s"indexed ${indexer.symbolCount} symbols in ${indexer.fileCount} files in ${elapsed}ms")

    rpc.respond(id, ujson.Obj(
      "capabilities" -> ujson.Obj(
        "textDocumentSync" -> 1, // Full
        "definitionProvider" -> true,
      )
    ))

  private def handleDidOpen(params: ujson.Value): Unit =
    val td = params("textDocument")
    val uri = td("uri").str
    val text = td("text").str
    log(s"didOpen: $uri (${text.length} chars)")
    indexer.updateFile(uri, text)
    log(s"  now ${indexer.symbolCount} symbols in ${indexer.fileCount} files")

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
