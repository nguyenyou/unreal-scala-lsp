package minilsp

private def log(msg: String): Unit =
  System.err.println(s"[mini-scala-lsp] $msg")

object Main:
  def main(args: Array[String]): Unit =
    log("Starting server v0.8.0")
    val rpc = JsonRpc(System.in, System.out)
    val server = LspServer(rpc)
    server.loop()
