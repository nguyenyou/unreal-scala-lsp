package minilsp

import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.services.{JsonNotification, JsonRequest}
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages
import java.util.concurrent.CompletableFuture

/** Flat server interface — no delegates, no LanguageServer, no TextDocumentService.
  * This avoids all lsp4j duplicate RPC method bugs.
  */
trait MiniServer:
  @JsonRequest("initialize")
  def initialize(params: InitializeParams): CompletableFuture[InitializeResult]

  @JsonNotification("initialized")
  def initialized(params: InitializedParams): Unit

  @JsonRequest("shutdown")
  def shutdown(): CompletableFuture[AnyRef]

  @JsonNotification("exit")
  def exit(): Unit

  @JsonNotification("textDocument/didOpen")
  def didOpen(params: DidOpenTextDocumentParams): Unit

  @JsonNotification("textDocument/didChange")
  def didChange(params: DidChangeTextDocumentParams): Unit

  @JsonNotification("textDocument/didClose")
  def didClose(params: DidCloseTextDocumentParams): Unit

  @JsonNotification("textDocument/didSave")
  def didSave(params: DidSaveTextDocumentParams): Unit

  @JsonRequest("textDocument/definition")
  def definition(params: DefinitionParams): CompletableFuture[messages.Either[java.util.List[? <: Location], java.util.List[? <: LocationLink]]]

/** Minimal client interface. */
trait MiniClient:
  @JsonNotification("window/logMessage")
  def logMessage(params: MessageParams): Unit

object Main:
  def main(args: Array[String]): Unit =
    System.err.println("[mini-scala-lsp] Starting server v0.4.0")
    val server = MiniScalaServer()
    val launcher = new Launcher.Builder[MiniClient]()
      .setLocalService(server)
      .setRemoteInterface(classOf[MiniClient])
      .setInput(System.in)
      .setOutput(System.out)
      .create()
    server.connect(launcher.getRemoteProxy)
    System.err.println("[mini-scala-lsp] Listening...")
    launcher.startListening().get()
