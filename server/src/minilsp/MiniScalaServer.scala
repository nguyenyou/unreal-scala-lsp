package minilsp

import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages
import org.eclipse.lsp4j.services.*
import scala.jdk.CollectionConverters.*

class MiniScalaServer extends LanguageServer with TextDocumentService with WorkspaceService:
  import scala.compiletime.uninitialized
  private var client: LanguageClient = uninitialized
  private val indexer = ScalaIndexer()

  def connect(client: LanguageClient): Unit =
    this.client = client

  override def initialize(params: InitializeParams): CompletableFuture[InitializeResult] =
    val capabilities = ServerCapabilities()
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Full)
    capabilities.setDefinitionProvider(true)

    // Index workspace on init
    val folders = Option(params.getWorkspaceFolders)
      .map(_.asScala.toList)
      .getOrElse(Nil)

    for folder <- folders do
      val uri = folder.getUri
      val path = java.net.URI(uri).getPath
      indexer.indexDirectory(java.io.File(path))

    // Fallback to rootUri
    if folders.isEmpty then
      Option(params.getRootUri).foreach: uri =>
        val path = java.net.URI(uri).getPath
        indexer.indexDirectory(java.io.File(path))

    CompletableFuture.completedFuture(InitializeResult(capabilities))

  override def shutdown(): CompletableFuture[AnyRef] =
    CompletableFuture.completedFuture(null)

  override def exit(): Unit =
    System.exit(0)

  override def getTextDocumentService: TextDocumentService = this

  override def getWorkspaceService: WorkspaceService = this

  // -- TextDocumentService --

  override def didOpen(params: DidOpenTextDocumentParams): Unit =
    val uri = params.getTextDocument.getUri
    val text = params.getTextDocument.getText
    indexer.updateFile(uri, text)

  override def didChange(params: DidChangeTextDocumentParams): Unit =
    val uri = params.getTextDocument.getUri
    val text = params.getContentChanges.asScala.lastOption.map(_.getText).getOrElse("")
    indexer.updateFile(uri, text)

  override def didClose(params: DidCloseTextDocumentParams): Unit = ()

  override def didSave(params: DidSaveTextDocumentParams): Unit = ()

  override def definition(params: DefinitionParams): CompletableFuture[messages.Either[java.util.List[? <: Location], java.util.List[? <: LocationLink]]] =
    val uri = params.getTextDocument.getUri
    val line = params.getPosition.getLine
    val col = params.getPosition.getCharacter

    val locations = indexer.findDefinition(uri, line, col)
    val result = messages.Either.forLeft[java.util.List[? <: Location], java.util.List[? <: LocationLink]](
      locations.asJava
    )
    CompletableFuture.completedFuture(result)

  // -- WorkspaceService --

  override def didChangeConfiguration(params: DidChangeConfigurationParams): Unit = ()

  override def didChangeWatchedFiles(params: DidChangeWatchedFilesParams): Unit =
    params.getChanges.asScala.foreach: event =>
      val uri = event.getUri
      if uri.endsWith(".scala") then
        event.getType match
          case FileChangeType.Deleted => indexer.removeFile(uri)
          case _ =>
            val path = java.nio.file.Paths.get(java.net.URI(uri))
            val text = java.nio.file.Files.readString(path)
            indexer.updateFile(uri, text)
