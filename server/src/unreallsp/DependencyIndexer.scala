package unreallsp

import java.io.File
import java.util.zip.ZipFile
import scala.collection.mutable

/** Discovers dependency source JARs from Mill output and extracts .scala files. */
class DependencyIndexer:

  private val skipJarPatterns = Set("scala3-library")

  case class ExtractedSource(file: File, uri: String)

  /** Find source JARs from Mill's compile classpath, extract .scala files, return them. */
  def resolveSourceFiles(workspaceRoot: File): List[ExtractedSource] =
    val jarPaths = discoverClasspathJars(workspaceRoot)
    val sourceJars = jarPaths.flatMap(toSourceJar).distinct
    sourceJars.flatMap(extractScalaFiles)

  /** Scan out/ for compileClasspath.json files, collect JAR paths. */
  private def discoverClasspathJars(root: File): List[String] =
    val outDir = File(root, "out")
    if !outDir.isDirectory then return Nil
    val results = mutable.ListBuffer.empty[String]
    collectClasspathJsons(outDir, results)
    results.toList.distinct

  /** Recursively find compileClasspath.json, skip mill-build/. */
  private def collectClasspathJsons(dir: File, results: mutable.ListBuffer[String]): Unit =
    if dir.getName == "mill-build" then return
    val files = dir.listFiles()
    if files == null then return
    for f <- files do
      if f.isFile && f.getName == "compileClasspath.json" then
        parseClasspathJson(f, results)
      else if f.isDirectory then
        collectClasspathJsons(f, results)

  /** Parse a compileClasspath.json, extract JAR paths from qref entries. */
  private def parseClasspathJson(file: File, results: mutable.ListBuffer[String]): Unit =
    try
      val text = java.nio.file.Files.readString(file.toPath)
      val json = ujson.read(text)
      for entry <- json("value").arr do
        val s = entry.str
        val prefix = "qref:v1:"
        if s.startsWith(prefix) then
          val afterPrefix = s.substring(prefix.length)
          val colonIdx = afterPrefix.indexOf(':')
          if colonIdx > 0 then
            val path = afterPrefix.substring(colonIdx + 1)
            if path.endsWith(".jar") then results += path
    catch
      case _: Exception => ()

  /** Convert a classpath JAR path to its source JAR sibling, if it exists. */
  private def toSourceJar(jarPath: String): Option[File] =
    if skipJarPatterns.exists(jarPath.contains(_)) then return None
    val sourceJarPath = jarPath.stripSuffix(".jar") + "-sources.jar"
    val f = File(sourceJarPath)
    if f.isFile then Some(f) else None

  /** Extract .scala files from a source JAR to a temp directory. */
  private def extractScalaFiles(sourceJar: File): List[ExtractedSource] =
    val jarName = sourceJar.getName.stripSuffix("-sources.jar")
    val destDir = File(System.getProperty("java.io.tmpdir"), s"unreal-scala-lsp/$jarName")
    val results = mutable.ListBuffer.empty[ExtractedSource]
    try
      val zip = ZipFile(sourceJar)
      try
        val entries = zip.entries()
        while entries.hasMoreElements do
          val entry = entries.nextElement()
          if !entry.isDirectory && entry.getName.endsWith(".scala") then
            val outFile = File(destDir, entry.getName)
            if !outFile.exists() then
              outFile.getParentFile.mkdirs()
              val in = zip.getInputStream(entry)
              try java.nio.file.Files.copy(in, outFile.toPath)
              finally in.close()
            results += ExtractedSource(outFile, outFile.toURI.toString)
      finally zip.close()
    catch
      case _: Exception => ()
    results.toList
