package minilsp

import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages
import scala.jdk.CollectionConverters.*

private def log(msg: String): Unit =
  System.err.println(s"[mini-scala-lsp] $msg")

class MiniScalaServer extends MiniServer:
  import scala.compiletime.uninitialized
  private var client: MiniClient = uninitialized
  private val indexer = ScalaIndexer()

  def connect(client: MiniClient): Unit =
    this.client = client

  override def initialize(params: InitializeParams): CompletableFuture[InitializeResult] =
    val capabilities = ServerCapabilities()
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Full)
    capabilities.setDefinitionProvider(true)

    val folders = Option(params.getWorkspaceFolders)
      .map(_.asScala.toList)
      .getOrElse(Nil)

    log(s"initialize: ${folders.size} workspace folders")

    val startTime = System.nanoTime()

    for folder <- folders do
      val uri = folder.getUri
      val path = java.net.URI(uri).getPath
      log(s"  indexing folder: $path")
      indexer.indexDirectory(java.io.File(path))

    if folders.isEmpty then
      val rootUri = Option(params.getRootUri)
      log(s"  no folders, rootUri=$rootUri")
      rootUri.foreach: uri =>
        val path = java.net.URI(uri).getPath
        log(s"  indexing rootUri: $path")
        indexer.indexDirectory(java.io.File(path))

    val elapsed = (System.nanoTime() - startTime) / 1_000_000
    log(s"  indexed ${indexer.symbolCount} symbols in ${indexer.fileCount} files in ${elapsed}ms")
    CompletableFuture.completedFuture(InitializeResult(capabilities))

  override def initialized(params: InitializedParams): Unit =
    log("initialized notification received")

  override def shutdown(): CompletableFuture[AnyRef] =
    log("shutdown")
    CompletableFuture.completedFuture(null)

  override def exit(): Unit =
    log("exit")
    System.exit(0)

  override def didOpen(params: DidOpenTextDocumentParams): Unit =
    val uri = params.getTextDocument.getUri
    val text = params.getTextDocument.getText
    log(s"didOpen: $uri (${text.length} chars)")
    indexer.updateFile(uri, text)
    log(s"  now ${indexer.symbolCount} symbols in ${indexer.fileCount} files")

  override def didChange(params: DidChangeTextDocumentParams): Unit =
    val uri = params.getTextDocument.getUri
    val text = params.getContentChanges.asScala.lastOption.map(_.getText).getOrElse("")
    log(s"didChange: $uri (${text.length} chars)")
    indexer.updateFile(uri, text)

  override def didClose(params: DidCloseTextDocumentParams): Unit =
    log(s"didClose: ${params.getTextDocument.getUri}")

  override def didSave(params: DidSaveTextDocumentParams): Unit =
    log(s"didSave: ${params.getTextDocument.getUri}")

  override def definition(params: DefinitionParams): CompletableFuture[messages.Either[java.util.List[? <: Location], java.util.List[? <: LocationLink]]] =
    val uri = params.getTextDocument.getUri
    val line = params.getPosition.getLine
    val col = params.getPosition.getCharacter

    val word = indexer.wordAtPosition(uri, line, col)
    log(s"definition: $uri:$line:$col word=$word")

    val locations = indexer.findDefinition(uri, line, col)
    log(s"  found ${locations.size} locations: ${locations.map(l => s"${l.getUri}:${l.getRange.getStart.getLine}")}")

    val result = messages.Either.forLeft[java.util.List[? <: Location], java.util.List[? <: LocationLink]](
      locations.asJava
    )
    CompletableFuture.completedFuture(result)
