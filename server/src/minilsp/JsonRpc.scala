package minilsp

import java.io.{InputStream, OutputStream}
import ujson.*

/** Minimal JSON-RPC 2.0 transport over stdin/stdout with Content-Length framing. */
class JsonRpc(in: InputStream, out: OutputStream):
  private val lock = new Object

  /** Read one JSON-RPC message. Blocks until a full message is available. */
  def read(): ujson.Value =
    val contentLength = readHeaders()
    val body = new Array[Byte](contentLength)
    var read = 0
    while read < contentLength do
      val n = in.read(body, read, contentLength - read)
      if n == -1 then throw new RuntimeException("EOF")
      read += n
    ujson.read(new String(body, "UTF-8"))

  /** Write a JSON-RPC message. */
  def write(msg: ujson.Value): Unit = lock.synchronized:
    val body = ujson.write(msg).getBytes("UTF-8")
    val header = s"Content-Length: ${body.length}\r\n\r\n"
    out.write(header.getBytes("UTF-8"))
    out.write(body)
    out.flush()

  /** Send a response to a request. */
  def respond(id: ujson.Value, result: ujson.Value): Unit =
    write(ujson.Obj("jsonrpc" -> "2.0", "id" -> id, "result" -> result))

  /** Send an error response. */
  def respondError(id: ujson.Value, code: Int, message: String): Unit =
    write(ujson.Obj(
      "jsonrpc" -> "2.0",
      "id" -> id,
      "error" -> ujson.Obj("code" -> code, "message" -> message)
    ))

  private def readHeaders(): Int =
    val sb = new StringBuilder
    var prev = 0
    var curr = 0
    // Read until \r\n\r\n
    while
      prev = curr
      curr = in.read()
      if curr == -1 then throw new RuntimeException("EOF")
      sb.append(curr.toChar)
      !(prev == '\n' && curr == '\n') && !(sb.length >= 4 && sb.substring(sb.length - 4) == "\r\n\r\n")
    do ()
    val headers = sb.toString
    val m = "(?i)Content-Length:\\s*(\\d+)".r.findFirstMatchIn(headers)
    m.map(_.group(1).toInt).getOrElse(throw new RuntimeException(s"Missing Content-Length in: $headers"))
