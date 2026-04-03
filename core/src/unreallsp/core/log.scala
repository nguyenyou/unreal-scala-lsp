package unreallsp.core

@volatile private var _debugEnabled: Boolean = false

def setDebug(enabled: Boolean): Unit = {
  _debugEnabled = enabled
}

def isDebugEnabled: Boolean = _debugEnabled

/** Always logged — startup, errors, key lifecycle events. */
def log(msg: String): Unit = {
  System.err.println(s"[unreal-scala-lsp] $msg")
}

/** Only logged when debug is enabled — verbose/internal details. */
def debug(msg: String): Unit = {
  if (_debugEnabled) {
    System.err.println(s"[unreal-scala-lsp] [DEBUG] $msg")
  }
}
