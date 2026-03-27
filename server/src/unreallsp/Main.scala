package unreallsp

private def log(msg: String): Unit =
  System.err.println(s"[unreal-scala-lsp] $msg")

object Main:
  def main(args: Array[String]): Unit =
    log("Starting server v1.3.0")
    val rpc = JsonRpc(System.in, System.out)
    val server = LspServer(rpc)
    server.loop()
