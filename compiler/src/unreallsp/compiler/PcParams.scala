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
import scala.meta.pc.{CancelToken, ContentType, OffsetParams, ParentSymbols, SymbolDocumentation, SymbolSearch, SymbolSearchVisitor, VirtualFileParams, ReferencesRequest, OutlineFiles}
import org.eclipse.lsp4j.{Location, Position, Range}
import org.eclipse.lsp4j.jsonrpc.messages.Either

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

private[compiler] class SimpleReferencesRequest(
  val _file: VirtualFileParams,
  val _includeDefinition: Boolean,
  val _offset: Int,
) extends ReferencesRequest {
  def file(): VirtualFileParams = _file
  def includeDefinition(): Boolean = _includeDefinition
  def offsetOrSymbol(): Either[Integer, String] = Either.forLeft(_offset)
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
  * to a cache directory. */
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
      val loc = findInWorkspace(target).orElse(findInSourceJars(symbol, target))
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
    val direct = allModules.iterator.flatMap(_.sourceRoots).collectFirst {
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
    val patterns = declPatterns(target.className)
    allModules.iterator
      .flatMap(m => m.sourceRoots.iterator.map(Path.of(_)))
      .filter(Files.isDirectory(_))
      .flatMap(findScalaFiles)
      .collectFirst {
        case file if {
          try {
            val content = Files.readString(file)
            val hasPackage = packageName.isEmpty || content.contains(s"package $packageName")
            hasPackage && patterns.exists(p => content.contains(p))
          } catch { case _: Exception => false }
        } => locateInFile(file, target)
      }
  }

  // ── Library source jars ─────────────────────────────────────────────────

  private def findInSourceJars(symbol: String, target: SymbolTarget): Option[Location] = {
    val classfilePath = toClassfilePath(symbol, target.topLevel)
    debug(s"  searching classpath for $classfilePath")

    val allCp = allModules.flatMap(_.classpath)
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
          None
        }
      } else {
        None
      }
    })
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

  // ── Symbol parsing ──────────────────────────────────────────────────────

  /** Extract the top-level type from a semanticdb symbol. */
  private def extractTopLevel(symbol: String): String = {
    val s = symbol.stripSuffix(".").stripSuffix("#")
    val hashIdx = s.indexOf('#')
    val base = if (hashIdx > 0) { s.substring(0, hashIdx) } else { s }
    val dotIdx = base.lastIndexOf('.')
    if (dotIdx > 0 && base.substring(dotIdx).contains("(")) {
      base.substring(0, dotIdx)
    } else {
      base
    }
  }

  /** Extract the member name from a semanticdb symbol, if any.
    * `scala/collection/immutable/List.newBuilder().` -> Some("newBuilder")
    * `scala/collection/immutable/List#head.`         -> Some("head")
    * `scala/collection/immutable/List.`              -> None
    * `scala/collection/immutable/List#`              -> None */
  private def extractMember(symbol: String): Option[String] = {
    // Find the boundary between type and member: first `#` or `.` after the last `/`
    val lastSlash = symbol.lastIndexOf('/')
    val afterType = symbol.substring(lastSlash + 1)
    // afterType e.g. "List.newBuilder()." or "List#head." or "List."
    val sepIdx = afterType.indexWhere(c => c == '#' || c == '.')
    if (sepIdx < 0) {
      None
    } else {
      val rest = afterType.substring(sepIdx + 1) // e.g. "newBuilder()." or "head." or ""
      if (rest.isEmpty || rest == "." || rest == "#") {
        None
      } else {
        // Extract just the name part (strip parens, trailing . or #)
        val nameEnd = rest.indexWhere(c => c == '(' || c == '.' || c == '#')
        if (nameEnd <= 0) {
          None
        } else {
          Some(rest.substring(0, nameEnd))
        }
      }
    }
  }

  /** Convert a semanticdb symbol to the classfile path to look up. */
  private def toClassfilePath(symbol: String, topLevel: String): String = {
    val isObject = symbol.indexOf('#') < 0 && !symbol.endsWith("#")
    if (isObject) { topLevel + "$.class" } else { topLevel + ".class" }
  }

  // ── Source location helpers ─────────────────────────────────────────────

  private def declPatterns(name: String): List[String] = List(
    s"class $name", s"object $name", s"trait $name", s"enum $name",
  )

  /** Find the precise location of a symbol target in a source file.
    * If the target has a member, search for `def/val/var/lazy val/type memberName`.
    * Otherwise, search for the class/object/trait/enum declaration. */
  private def locateInFile(path: Path, target: SymbolTarget): Location = {
    try {
      val lines = Files.readAllLines(path)
      target.member match {
        case Some(memberName) => {
          // Search for member declaration
          val memberPatterns = List(
            s"def $memberName",
            s"val $memberName",
            s"var $memberName",
            s"lazy val $memberName",
            s"type $memberName",
          )
          findPattern(path, lines, memberPatterns, memberName)
            .getOrElse(findPattern(path, lines, declPatterns(target.className), target.className)
              .getOrElse(defaultLocation(path)))
        }
        case None => {
          findPattern(path, lines, declPatterns(target.className), target.className)
            .getOrElse(defaultLocation(path))
        }
      }
    } catch {
      case _: Exception => defaultLocation(path)
    }
  }

  /** Search lines for any of the given patterns, returning a Location pointing to `name`. */
  private def findPattern(path: Path, lines: java.util.List[String], patterns: List[String], name: String): Option[Location] = {
    var i = 0
    var result: Option[Location] = None
    while (i < lines.size() && result.isEmpty) {
      val line = lines.get(i)
      var j = 0
      while (j < patterns.size && result.isEmpty) {
        val idx = line.indexOf(patterns(j))
        if (idx >= 0) {
          val col = idx + patterns(j).length - name.length
          val start = new Position(i, col)
          val end = new Position(i, col + name.length)
          result = Some(new Location(path.toUri.toString, new Range(start, end)))
        }
        j += 1
      }
      i += 1
    }
    result
  }

  private def defaultLocation(path: Path): Location = {
    new Location(path.toUri.toString, new Range(new Position(0, 0), new Position(0, 0)))
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
