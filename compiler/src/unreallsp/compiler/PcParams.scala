package unreallsp.compiler

import unreallsp.core.{MillModule, debug}
import java.io.File
import java.net.URI
import java.nio.file.{Files, Path}
import java.util.Optional
import java.util.concurrent.{CompletableFuture, CompletionStage}
import java.util.jar.JarFile
import scala.jdk.CollectionConverters.*
import scala.collection.mutable
import scala.meta.internal.semanticdb.Scala.*
import scala.meta.internal.semanticdb.Scala.{Descriptor => d}
import scala.meta.pc.{CancelToken, ContentType, OffsetParams, ParentSymbols, SymbolDocumentation, SymbolSearch, SymbolSearchVisitor, VirtualFileParams, OutlineFiles}
import org.eclipse.lsp4j.{Location, Position, Range}

private[compiler] class SimpleVirtualFileParams(
  val _uri: URI,
  val _text: String,
) extends VirtualFileParams {
  def uri(): URI = _uri
  def text(): String = _text
  def token(): CancelToken = NoCancelToken
}

private[compiler] class SimpleOffsetParams(
  val _uri: URI,
  val _text: String,
  val _offset: Int,
) extends OffsetParams {
  def uri(): URI = _uri
  def text(): String = _text
  def offset(): Int = _offset
  def token(): CancelToken = NoCancelToken
}

private[compiler] object NoCancelToken extends CancelToken {
  def checkCanceled(): Unit = ()
  def onCancel(): CompletionStage[java.lang.Boolean] = {
    new CompletableFuture[java.lang.Boolean]()
  }
}

/** Parsed semanticdb symbol target: the top-level type and optional member name.
  * E.g. symbol `scala/collection/immutable/List.newBuilder().`
  * -> topLevel = `scala/collection/immutable/List`, member = Some("newBuilder") */
private[compiler] case class SymbolTarget(
  topLevel: String,
  member: Option[String],
) {
  val parts: Array[String] = topLevel.split("/")
  val className: String = parts.last
  val packageDir: String = parts.init.mkString("/")
  val packageDirFs: String = parts.init.mkString(File.separator)
}

/** Resolves semanticdb symbols to source locations across workspace source roots
  * and library source jars.
  *
  * For workspace symbols: scans module source roots for matching files.
  * For library symbols: reads the classfile's SourceFile attribute via ASM to find
  * the exact source filename, then extracts it from the companion -sources.jar
  * to a readonly cache directory (same approach as Metals' .metals/readonly/). */
private[compiler] class WorkspaceSymbolSearch(
  allModules: List[MillModule],
  cacheDir: Path,
) extends SymbolSearch {

  private val sourceJarCache = mutable.Map.empty[String, Option[Path]]

  override def definition(symbol: String, source: URI): java.util.List[Location] = {
    debug(s"WorkspaceSymbolSearch.definition($symbol)")
    val topLevel = extractTopLevel(symbol)
    if (topLevel.isEmpty) {
      java.util.Collections.emptyList()
    } else {
      val target = SymbolTarget(topLevel, extractMember(symbol))
      val loc = findInWorkspace(target)
        .orElse(findInSourceJars(symbol, target))
        .orElse(findInJdkSources(target))
      loc match {
        case Some(l) => java.util.Collections.singletonList(l)
        case None => {
          debug(s"  not found for $symbol")
          java.util.Collections.emptyList()
        }
      }
    }
  }

  override def definitionSourceToplevels(symbol: String, sourceUri: URI): java.util.List[String] = {
    java.util.Collections.emptyList()
  }

  override def search(query: String, buildTarget: String, visitor: SymbolSearchVisitor): SymbolSearch.Result = {
    SymbolSearch.Result.COMPLETE
  }

  override def searchMethods(query: String, buildTarget: String, visitor: SymbolSearchVisitor): SymbolSearch.Result = {
    SymbolSearch.Result.COMPLETE
  }

  override def documentation(symbol: String, parents: ParentSymbols): Optional[SymbolDocumentation] = {
    Optional.empty()
  }

  override def documentation(symbol: String, parents: ParentSymbols, contentType: ContentType): Optional[SymbolDocumentation] = {
    Optional.empty()
  }

  // ── Workspace sources ───────────────────────────────────────────────────

  private def findInWorkspace(target: SymbolTarget): Option[Location] = {
    debug(s"  workspace: looking for ${target.packageDirFs}/${target.className}.scala")
    val allRoots = allModules.flatMap(_.sourceRoots)
    // Prefer real source roots over generated/copied sources in out/
    val (realRoots, outRoots) = allRoots.partition(r => !r.contains("/out/"))
    val orderedRoots = realRoots ++ outRoots
    val direct = orderedRoots.iterator.collectFirst {
      case root if {
        val dir = Path.of(root, target.packageDirFs)
        Files.isDirectory(dir) && Files.isRegularFile(dir.resolve(target.className + ".scala"))
      } => {
        val candidate = Path.of(root, target.packageDirFs).resolve(target.className + ".scala")
        debug(s"  workspace: resolved to $candidate")
        locateInFile(candidate, target)
      }
    }
    direct.orElse(findByPackageDecl(target))
  }

  private def findByPackageDecl(target: SymbolTarget): Option[Location] = {
    val packageName = target.parts.init.mkString(".")
    val declKeywords = List("class ", "object ", "trait ", "enum ")
    allModules.iterator
      .flatMap(m => m.sourceRoots.iterator.map(Path.of(_)))
      .filter(Files.isDirectory(_))
      .flatMap(findScalaFiles)
      .collectFirst {
        case file if {
          try {
            val content = Files.readString(file)
            val hasPackage = packageName.isEmpty || content.contains(s"package $packageName")
            hasPackage && declKeywords.exists(kw => content.contains(kw + target.className))
          } catch { case _: Exception => false }
        } => locateInFile(file, target)
      }
  }

  // ── Library source jars ─────────────────────────────────────────────────

  private def findInSourceJars(symbol: String, target: SymbolTarget): Option[Location] = {
    val classfilePath = toClassfilePath(symbol, target.topLevel)
    debug(s"  searching classpath for $classfilePath")

    val allCp = allModules.flatMap(_.classpath).distinct
    allCp.iterator
      .filter(_.endsWith(".jar"))
      .flatMap { cpJar =>
        findSourceViaClassfile(cpJar, classfilePath, target)
      }
      .nextOption()
  }

  private def findSourceViaClassfile(
    cpJar: String,
    classfilePath: String,
    target: SymbolTarget,
  ): Option[Location] = {
    try {
      val jar = new JarFile(cpJar)
      try {
        val entry = jar.getEntry(classfilePath)
        if (entry == null) {
          None
        } else {
          val bytes = jar.getInputStream(entry).readAllBytes()
          val sourceFileName = readSourceFileAttribute(bytes)
          sourceFileName.flatMap { srcFile =>
            debug(s"  classfile $classfilePath -> SourceFile: $srcFile")
            findSourceJar(cpJar).flatMap { srcJarPath =>
              val entryPath = target.packageDir + "/" + srcFile
              extractSourceEntry(srcJarPath, entryPath, target)
            }
          }
        }
      } finally {
        jar.close()
      }
    } catch {
      case _: Exception => None
    }
  }

  private def readSourceFileAttribute(classBytes: Array[Byte]): Option[String] = {
    try {
      val reader = new scala.tools.asm.ClassReader(classBytes)
      var sourceFile: Option[String] = None
      reader.accept(
        new scala.tools.asm.ClassVisitor(scala.tools.asm.Opcodes.ASM9) {
          override def visitSource(source: String, debug: String): Unit = {
            if (source != null) { sourceFile = Some(source) }
          }
        },
        scala.tools.asm.ClassReader.SKIP_CODE | scala.tools.asm.ClassReader.SKIP_FRAMES,
      )
      sourceFile
    } catch {
      case _: Exception => None
    }
  }

  private def findSourceJar(cpJar: String): Option[Path] = {
    sourceJarCache.getOrElseUpdate(cpJar, {
      val p = Path.of(cpJar)
      val name = p.getFileName.toString
      if (name.endsWith(".jar")) {
        val sourceName = name.stripSuffix(".jar") + "-sources.jar"
        val candidate = p.getParent.resolve(sourceName)
        if (Files.isRegularFile(candidate)) {
          debug(s"  found source jar: $candidate")
          Some(candidate)
        } else {
          debug(s"  no local source jar at $candidate, trying coursier fetch")
          fetchSourceJar(p)
        }
      } else {
        None
      }
    })
  }

  /** Extract Maven coordinates from a Coursier cache path and fetch the sources JAR.
    * Path pattern: .../maven2/group/artifact/version/artifact-version.jar */
  private def fetchSourceJar(jarPath: Path): Option[Path] = {
    try {
      val abs = jarPath.toAbsolutePath.toString
      // Find the maven2/ prefix to extract coordinates
      val mavenIdx = abs.indexOf("/maven2/")
      if (mavenIdx < 0) {
        None
      } else {
        val afterMaven = abs.substring(mavenIdx + "/maven2/".length)
        val parts = afterMaven.split("/")
        // parts: [group, ..., group, artifact, version, filename.jar]
        if (parts.length < 3) {
          None
        } else {
          val version = parts(parts.length - 2)
          val artifact = parts(parts.length - 3)
          val group = parts.take(parts.length - 3).mkString(".")
          val coord = s"$group:$artifact:$version"
          debug(s"  fetching sources jar for $coord")
          val pb = new ProcessBuilder("coursier", "fetch", "--sources", coord)
          pb.redirectErrorStream(true)
          val proc = pb.start()
          val output = new String(proc.getInputStream.readAllBytes()).trim
          val exitCode = proc.waitFor()
          if (exitCode == 0) {
            val sourcePaths = output.split("\n").filter(_.endsWith("-sources.jar"))
            sourcePaths.headOption.map { sp =>
              val srcPath = Path.of(sp)
              debug(s"  fetched source jar: $srcPath")
              srcPath
            }
          } else {
            debug(s"  coursier fetch failed (exit $exitCode)")
            None
          }
        }
      }
    } catch {
      case e: Exception => {
        debug(s"  fetchSourceJar error: ${e.getMessage}")
        None
      }
    }
  }

  private def extractSourceEntry(srcJarPath: Path, entryPath: String, target: SymbolTarget): Option[Location] = {
    try {
      val jar = new JarFile(srcJarPath.toFile)
      try {
        val entry = jar.getEntry(entryPath)
        if (entry == null) {
          debug(s"  entry $entryPath not found in $srcJarPath")
          None
        } else {
          val jarName = srcJarPath.getFileName.toString.stripSuffix(".jar")
          val destFile = cacheDir.resolve(jarName).resolve(entryPath)
          if (!Files.isRegularFile(destFile)) {
            Files.createDirectories(destFile.getParent)
            val is = jar.getInputStream(entry)
            try {
              Files.copy(is, destFile)
            } finally {
              is.close()
            }
          }
          debug(s"  extracted to $destFile")
          Some(locateInFile(destFile, target))
        }
      } finally {
        jar.close()
      }
    } catch {
      case _: Exception => None
    }
  }

  // ── JDK sources ─────────────────────────────────────────────────────────

  /** Resolve JDK symbols (java/lang/String etc.) from $JAVA_HOME/lib/src.zip.
    * Entries are prefixed with the module name: `java.base/java/lang/String.java`. */
  private def findInJdkSources(target: SymbolTarget): Option[Location] = {
    val javaHome = System.getProperty("java.home")
    if (javaHome == null) {
      None
    } else {
      val srcZip = Path.of(javaHome, "lib", "src.zip")
      if (!Files.isRegularFile(srcZip)) {
        None
      } else {
        debug(s"  searching JDK src.zip for ${target.topLevel}")
        val sourcePath = target.topLevel + ".java"
        try {
          val jar = new JarFile(srcZip.toFile)
          try {
            val entry = findJdkEntry(jar, sourcePath)
            entry.flatMap { e =>
              val destFile = cacheDir.resolve("jdk-src").resolve(e.getName)
              if (!Files.isRegularFile(destFile)) {
                Files.createDirectories(destFile.getParent)
                val is = jar.getInputStream(e)
                try {
                  Files.copy(is, destFile)
                } finally {
                  is.close()
                }
              }
              debug(s"  extracted JDK source to $destFile")
              Some(locateInFile(destFile, target))
            }
          } finally {
            jar.close()
          }
        } catch {
          case _: Exception => None
        }
      }
    }
  }

  private def findJdkEntry(jar: JarFile, sourcePath: String): Option[java.util.jar.JarEntry] = {
    // Try well-known modules first for speed
    val modules = List("java.base", "java.desktop", "java.sql", "java.net.http",
      "java.logging", "java.xml", "java.naming", "java.compiler", "java.management")
    modules.iterator.map(m => jar.getEntry(s"$m/$sourcePath")).collectFirst {
      case e if e != null => e.asInstanceOf[java.util.jar.JarEntry]
    }.orElse {
      // Fallback: scan all entries
      val entries = jar.entries()
      var found: Option[java.util.jar.JarEntry] = None
      while (entries.hasMoreElements && found.isEmpty) {
        val e = entries.nextElement()
        if (e.getName.endsWith("/" + sourcePath) || e.getName == sourcePath) {
          found = Some(e)
        }
      }
      found
    }
  }

  // ── Symbol parsing (uses scalameta's semanticdb symbol parser) ──────────

  /** Walk up the owner chain to find the first non-package symbol.
    * That's our "top-level type" for classfile/source lookup.
    * E.g. `scala/Predef.String#` → owner chain: String# → Predef. → scala/ → _root_
    *   First non-package = `scala/Predef.` → top-level = `scala/Predef` */
  private def extractTopLevel(symbol: String): String = {
    if (!symbol.isGlobal) { "" }
    else {
      // Walk up: if the symbol's owner is a package, the symbol itself is top-level.
      // Otherwise the owner (or its owner, etc.) is the top-level.
      def findTopLevel(sym: String): String = {
        val ownerSym = sym.owner
        if (ownerSym.isPackage || ownerSym.isNone) {
          // sym is the top-level — strip trailing . or #
          sym.stripSuffix(".").stripSuffix("#")
        } else {
          findTopLevel(ownerSym)
        }
      }
      findTopLevel(symbol)
    }
  }

  /** Extract the member name if the symbol is nested inside a top-level type.
    * E.g. `scala/Predef.String#` → Some("String")
    *       `scala/collection/immutable/List.newBuilder().` → Some("newBuilder")
    *       `scala/collection/immutable/List#` → None */
  private def extractMember(symbol: String): Option[String] = {
    if (!symbol.isGlobal) { None }
    else {
      val ownerSym = symbol.owner
      if (ownerSym.isPackage || ownerSym.isNone) {
        // Symbol is itself the top-level — no member
        None
      } else {
        // Symbol is a member; extract its name from the descriptor
        val desc = symbol.desc
        desc match {
          case d.Term(value) => Some(value)
          case d.Type(value) => Some(value)
          case d.Method(value, _) => Some(value)
          case _ => None
        }
      }
    }
  }

  /** Convert a semanticdb symbol to the classfile path for the top-level type.
    * Objects get `$` appended. */
  private def toClassfilePath(symbol: String, topLevel: String): String = {
    // Find the top-level symbol itself (not the member) and check if it's a term (object)
    def findTopLevelSymbol(sym: String): String = {
      val ownerSym = sym.owner
      if (ownerSym.isPackage || ownerSym.isNone) { sym }
      else { findTopLevelSymbol(ownerSym) }
    }
    val tlSym = findTopLevelSymbol(symbol)
    if (tlSym.isTerm) { topLevel + "$.class" } else { topLevel + ".class" }
  }

  // ── Source location helpers ─────────────────────────────────────────────

  private def locateInFile(path: Path, target: SymbolTarget): Location = {
    SourceLocator.locate(path, target)
  }

  private def findScalaFiles(dir: Path): List[Path] = {
    val result = List.newBuilder[Path]
    val entries = Files.list(dir)
    try {
      entries.forEach { p =>
        if (Files.isDirectory(p)) {
          result ++= findScalaFiles(p)
        } else if (p.toString.endsWith(".scala")) {
          result += p
        }
      }
    } finally {
      entries.close()
    }
    result.result()
  }
}
