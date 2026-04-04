package unreallsp.core

import java.io.File
import java.nio.file.{Files, Path}
import scala.util.matching.Regex

/** A Maven repository with optional credentials, extracted from mill-build/build.mill. */
case class MillRepo(
  url: String,
  user: Option[String],
  password: Option[String],
  realm: Option[String],
)

/** A discovered Mill module with its compilation metadata. */
case class MillModule(
  name: String,
  sourceRoots: List[String],
  classpath: List[String],
  scalaVersion: String,
  scalacOptions: List[String],
)

/** Reads Mill's `out/` cache to discover modules and their classpaths. */
object MillWorkspace {

  /** Discover all Mill modules under a workspace root. */
  def discover(workspaceRoot: File): List[MillModule] = {
    val outDir = File(workspaceRoot, "out")
    if (!outDir.isDirectory) {
      return Nil
    }
    val modules = List.newBuilder[MillModule]
    collectModules(outDir, outDir, modules)
    modules.result()
  }

  /** Find which module a file URI belongs to, based on source roots. */
  def findModule(modules: List[MillModule], filePath: String): Option[MillModule] = {
    modules.find { m =>
      m.sourceRoots.exists(root => filePath.startsWith(root))
    }
  }

  private def collectModules(outDir: File, dir: File, acc: collection.mutable.Builder[MillModule, List[MillModule]]): Unit = {
    val cpFile = File(dir, "compileClasspath.json")
    if (cpFile.isFile) {
      val name = moduleName(outDir, dir)
      if (name != "mill-build") {
        readModule(name, dir).foreach(acc += _)
      }
    }
    val children = dir.listFiles()
    if (children != null) {
      for (child <- children) {
        if (child.isDirectory && !child.getName.endsWith(".dest")) {
          collectModules(outDir, child, acc)
        }
      }
    }
  }

  private def moduleName(outDir: File, dir: File): String = {
    outDir.toPath.relativize(dir.toPath).toString.replace(File.separatorChar, '.')
  }

  private def readModule(name: String, dir: File): Option[MillModule] = {
    val classpath = readPathList(File(dir, "compileClasspath.json"))
    val sourceRoots = readPathList(File(dir, "allSources.json"))
    val scalaVersion = readStringValue(File(dir, "scalaVersion.json"))
    val scalacOptions = readStringList(File(dir, "scalacOptions.json"))

    // Add the module's own compiled classes to the classpath so the
    // presentation compiler can resolve symbols within the same module.
    val compileOutput = File(dir, "compile.dest/classes")
    val fullClasspath = if (compileOutput.isDirectory) {
      compileOutput.getAbsolutePath :: classpath
    } else {
      classpath
    }

    if (fullClasspath.isEmpty && sourceRoots.isEmpty) {
      None
    } else {
      Some(MillModule(
        name = name,
        sourceRoots = sourceRoots,
        classpath = fullClasspath,
        scalaVersion = scalaVersion.getOrElse(""),
        scalacOptions = scalacOptions,
      ))
    }
  }

  /** Parse a Mill JSON cache file containing a list of path refs.
    * Format: `{"value": ["qref:v1:hash:/path", "ref:v0:hash:/path", ...]}` */
  private def readPathList(file: File): List[String] = {
    readJsonValue(file) match {
      case Some(arr) if arr.isInstanceOf[ujson.Arr] => {
        arr.arr.toList.flatMap { v =>
          extractPath(v.str)
        }
      }
      case _ => Nil
    }
  }

  /** Parse a Mill JSON cache file containing a single string value.
    * Format: `{"value": "3.8.2"}` */
  private def readStringValue(file: File): Option[String] = {
    readJsonValue(file).collect {
      case s: ujson.Str => s.str
    }
  }

  /** Parse a Mill JSON cache file containing a list of strings.
    * Format: `{"value": ["-flag1", "-flag2"]}` */
  private def readStringList(file: File): List[String] = {
    readJsonValue(file) match {
      case Some(arr) if arr.isInstanceOf[ujson.Arr] => {
        arr.arr.toList.map(_.str)
      }
      case _ => Nil
    }
  }

  private def readJsonValue(file: File): Option[ujson.Value] = {
    if (!file.isFile) {
      None
    } else {
      try {
        val text = Files.readString(file.toPath)
        val json = ujson.read(text)
        json.obj.get("value")
      } catch {
        case _: Exception => None
      }
    }
  }

  /** Discover Maven repositories with credentials from mill-build/build.mill.
    * Parses the Scala source to extract MavenRepository URLs and Authentication params. */
  def discoverRepos(workspaceRoot: File): List[MillRepo] = {
    val buildFile = File(workspaceRoot, "mill-build/build.mill")
    if (!buildFile.isFile) {
      Nil
    } else {
      try {
        val content = Files.readString(buildFile.toPath)
        parseMavenRepos(content)
      } catch {
        case _: Exception => Nil
      }
    }
  }

  // Regex patterns for extracting MavenRepository blocks from Scala source.
  // Matches: MavenRepository("url", authentication = Some(Authentication(...)))
  // and plain: MavenRepository("url")
  private val mavenRepoBlock: Regex =
    """(?s)MavenRepository\(\s*"([^"]+)"(.*?)\)(?:\s*[,\)])""".r

  private val userPattern: Regex = """user\s*=\s*"([^"]+)"""".r
  private val passwordPattern: Regex = """password\s*=\s*"([^"]+)"""".r
  private val realmPattern: Regex = """realmOpt\s*=\s*(?:Some|Option)\(\s*"([^"]+)"\s*\)""".r

  private def parseMavenRepos(content: String): List[MillRepo] = {
    // Find all MavenRepository(...) blocks
    val repos = List.newBuilder[MillRepo]
    // Use a more robust approach: find each MavenRepository( and match its balanced parens
    var idx = 0
    while (idx < content.length) {
      val start = content.indexOf("MavenRepository(", idx)
      if (start < 0) {
        idx = content.length
      } else {
        val parenStart = start + "MavenRepository".length
        extractBalancedParens(content, parenStart) match {
          case Some(block) => {
            // block is the content inside MavenRepository(...)
            // Extract the URL (first string literal)
            val urlPattern = """"([^"]+)"""".r
            val url = urlPattern.findFirstMatchIn(block).map(_.group(1))
            url.foreach { u =>
              val user = userPattern.findFirstMatchIn(block).map(_.group(1))
              val password = passwordPattern.findFirstMatchIn(block).map(_.group(1))
              val realm = realmPattern.findFirstMatchIn(block).map(_.group(1))
              repos += MillRepo(u, user, password, realm)
            }
            idx = parenStart + block.length + 2 // skip past closing paren
          }
          case None => {
            idx = parenStart + 1
          }
        }
      }
    }
    repos.result()
  }

  /** Extract content inside balanced parentheses starting at the given index.
    * Returns the content between ( and ) exclusive. */
  private def extractBalancedParens(s: String, openIdx: Int): Option[String] = {
    if (openIdx >= s.length || s.charAt(openIdx) != '(') {
      None
    } else {
      var depth = 1
      var i = openIdx + 1
      while (i < s.length && depth > 0) {
        val c = s.charAt(i)
        if (c == '(') { depth += 1 }
        else if (c == ')') { depth -= 1 }
        else if (c == '"') {
          // Skip string literals to avoid counting parens inside strings
          i += 1
          while (i < s.length && s.charAt(i) != '"') {
            if (s.charAt(i) == '\\') { i += 1 } // skip escaped chars
            i += 1
          }
        }
        i += 1
      }
      if (depth == 0) {
        Some(s.substring(openIdx + 1, i - 1))
      } else {
        None
      }
    }
  }

  /** Extract the actual filesystem path from a Mill path reference.
    * Strips the `qref:v1:hash:` or `ref:v0:hash:` prefix. */
  private def extractPath(ref: String): Option[String] = {
    // Format: "qref:v1:hex:/actual/path" or "ref:v0:hex:/actual/path"
    val idx1 = ref.indexOf(':')
    if (idx1 < 0) {
      Some(ref) // plain path, no prefix
    } else {
      val idx2 = ref.indexOf(':', idx1 + 1)
      if (idx2 < 0) {
        Some(ref)
      } else {
        val idx3 = ref.indexOf(':', idx2 + 1)
        if (idx3 < 0) {
          Some(ref)
        } else {
          Some(ref.substring(idx3 + 1))
        }
      }
    }
  }
}
