package unreallsp.server

import unreallsp.core.{log, setDebug}
import unreallsp.rpc.JsonRpc

object Main {
  def main(args: Array[String]): Unit = {
    if (args.contains("--debug")) {
      setDebug(true)
    }
    log("Starting server v1.3.0")
    val rpc = JsonRpc(System.in, System.out)
    val server = LspServer(rpc)
    server.loop()
  }
}
