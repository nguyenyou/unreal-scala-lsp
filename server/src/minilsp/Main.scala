package minilsp

import java.util.concurrent.Executors
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient

object Main:
  def main(args: Array[String]): Unit =
    val server = MiniScalaServer()
    val launcher = LSPLauncher.createServerLauncher(server, System.in, System.out)
    val client = launcher.getRemoteProxy
    server.connect(client)
    launcher.startListening().get()
