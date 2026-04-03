package unreallsp.server

import unreallsp.core.log
import unreallsp.rpc.JsonRpc

object Main {
  def main(args: Array[String]): Unit = {
    log("Starting server v1.3.0")
    val rpc = JsonRpc(System.in, System.out)
    val server = LspServer(rpc)
    server.loop()
  }
}
