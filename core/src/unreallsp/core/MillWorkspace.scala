package unreallsp.core

import java.io.File
import java.nio.file.{Files, Path}

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

    if (classpath.isEmpty && sourceRoots.isEmpty) {
      None
    } else {
      Some(MillModule(
        name = name,
        sourceRoots = sourceRoots,
        classpath = classpath,
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
