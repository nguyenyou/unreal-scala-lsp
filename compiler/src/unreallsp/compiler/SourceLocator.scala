package unreallsp.compiler

import unreallsp.core.debug
import java.net.URI
import java.nio.file.{Files, Path}
import javax.tools.{SimpleJavaFileObject, JavaFileObject, ToolProvider}
import com.sun.source.tree.{CompilationUnitTree, ClassTree, MethodTree, VariableTree, LineMap}
import com.sun.source.util.{JavacTask, TreePathScanner, Trees, SourcePositions}
import org.eclipse.lsp4j.{Location, Position, Range}
import scala.jdk.CollectionConverters.*
import scala.meta.{Defn, Decl, Dialect, Input, Parsed, Pat, Pkg, Source, Tree, XtensionParseInputLike}
import scala.meta.dialects

/** Finds precise source locations for symbols using real parsers.
  * Scalameta for .scala files, javac for .java files. */
object SourceLocator {

  def locate(path: Path, target: SymbolTarget): Location = {
    try {
      if (path.toString.endsWith(".java")) {
        locateInJava(path, target)
      } else {
        locateInScala(path, target)
      }
    } catch {
      case e: Exception => {
        debug(s"  SourceLocator error: ${e.getMessage}")
        defaultLocation(path)
      }
    }
  }

  /** Locate a symbol in source content already read into memory.
    * Returns a Location with the given URI (e.g. a jar: URI). */
  def locateInSource(uri: String, source: String, target: SymbolTarget): Location = {
    try {
      if (uri.endsWith(".java")) {
        locateJavaInSource(uri, source, target)
      } else {
        locateScalaInSource(uri, source, target)
      }
    } catch {
      case e: Exception => {
        debug(s"  SourceLocator error: ${e.getMessage}")
        new Location(uri, new Range(new Position(0, 0), new Position(0, 0)))
      }
    }
  }

  // ── Scala (scalameta) ───────────────────────────────────────────────────

  private def locateInScala(path: Path, target: SymbolTarget): Location = {
    val source = new String(Files.readAllBytes(path))
    locateScalaInSource(path.toUri.toString, source, target)
  }

  private def locateScalaInSource(uri: String, source: String, target: SymbolTarget): Location = {
    implicit val dialect: Dialect = dialects.Scala3
    val input = Input.VirtualFile(uri, source)
    val parsed = input.parse[Source]
    val defaultLoc = new Location(uri, new Range(new Position(0, 0), new Position(0, 0)))
    parsed match {
      case Parsed.Success(ast) => {
        val pos = target.member match {
          case Some(memberName) => {
            findScalaMember(ast, target.className, memberName)
              .orElse(findScalaDecl(ast, target.className))
          }
          case None => findScalaDecl(ast, target.className)
        }
        pos match {
          case Some(metaPos) => {
            val start = new Position(metaPos.startLine, metaPos.startColumn)
            val end = new Position(metaPos.endLine, metaPos.endColumn)
            new Location(uri, new Range(start, end))
          }
          case None => defaultLoc
        }
      }
      case _ => {
        debug(s"  SourceLocator: scalameta parse failed, falling back to text search")
        locateByTextInSource(uri, source, target)
      }
    }
  }

  /** Find a class/trait/object/enum declaration by name. */
  private def findScalaDecl(tree: Tree, name: String): Option[scala.meta.Position] = {
    var result: Option[scala.meta.Position] = None
    def walk(t: Tree): Unit = {
      if (result.isEmpty) {
        t match {
          case d: Defn.Class if d.name.value == name => result = Some(d.name.pos)
          case d: Defn.Trait if d.name.value == name => result = Some(d.name.pos)
          case d: Defn.Object if d.name.value == name => result = Some(d.name.pos)
          case d: Defn.Enum if d.name.value == name => result = Some(d.name.pos)
          case d: Pkg.Object if d.name.value == name => result = Some(d.name.pos)
          case _ => ()
        }
        if (result.isEmpty) { t.children.foreach(walk) }
      }
    }
    walk(tree)
    result
  }

  /** Find a member (def/val/var/type) inside a class/object matching the given name. */
  private def findScalaMember(tree: Tree, className: String, memberName: String): Option[scala.meta.Position] = {
    var result: Option[scala.meta.Position] = None
    var inTarget = false

    def walk(t: Tree): Unit = {
      if (result.isEmpty) {
        t match {
          case d: Defn.Class if d.name.value == className => {
            inTarget = true; d.children.foreach(walk); inTarget = false
          }
          case d: Defn.Trait if d.name.value == className => {
            inTarget = true; d.children.foreach(walk); inTarget = false
          }
          case d: Defn.Object if d.name.value == className => {
            inTarget = true; d.children.foreach(walk); inTarget = false
          }
          case d: Defn.Enum if d.name.value == className => {
            inTarget = true; d.children.foreach(walk); inTarget = false
          }
          case d: Defn.Class if inTarget && d.name.value == memberName => {
            result = Some(d.name.pos)
          }
          case d: Defn.Trait if inTarget && d.name.value == memberName => {
            result = Some(d.name.pos)
          }
          case d: Defn.Object if inTarget && d.name.value == memberName => {
            result = Some(d.name.pos)
          }
          case d: Defn.Enum if inTarget && d.name.value == memberName => {
            result = Some(d.name.pos)
          }
          case d: Defn.Def if inTarget && d.name.value == memberName => {
            result = Some(d.name.pos)
          }
          case d: Defn.Val if inTarget => {
            d.pats.foreach {
              case p: Pat.Var if p.name.value == memberName => result = Some(p.name.pos)
              case _ => ()
            }
          }
          case d: Defn.Var if inTarget => {
            d.pats.foreach {
              case p: Pat.Var if p.name.value == memberName => result = Some(p.name.pos)
              case _ => ()
            }
          }
          case d: Defn.Type if inTarget && d.name.value == memberName => {
            result = Some(d.name.pos)
          }
          case d: Defn.GivenAlias if inTarget && d.name.value == memberName => {
            result = Some(d.name.pos)
          }
          case d: Decl.Def if inTarget && d.name.value == memberName => {
            result = Some(d.name.pos)
          }
          case d: Decl.Val if inTarget => {
            d.pats.foreach {
              case p: Pat.Var if p.name.value == memberName => result = Some(p.name.pos)
              case _ => ()
            }
          }
          case d: Decl.Var if inTarget => {
            d.pats.foreach {
              case p: Pat.Var if p.name.value == memberName => result = Some(p.name.pos)
              case _ => ()
            }
          }
          case d: Decl.Type if inTarget && d.name.value == memberName => {
            result = Some(d.name.pos)
          }
          case _ => t.children.foreach(walk)
        }
      }
    }
    walk(tree)
    // If not found inside the target class, search everywhere (companion, top-level)
    if (result.isEmpty) {
      findScalaMemberAnywhere(tree, memberName)
    } else {
      result
    }
  }

  /** Find a member anywhere in the file (top-level defs, package objects, etc.). */
  private def findScalaMemberAnywhere(tree: Tree, memberName: String): Option[scala.meta.Position] = {
    var result: Option[scala.meta.Position] = None
    def walk(t: Tree): Unit = {
      if (result.isEmpty) {
        t match {
          case d: Defn.Def if d.name.value == memberName => result = Some(d.name.pos)
          case d: Defn.Val => {
            d.pats.foreach {
              case p: Pat.Var if p.name.value == memberName => result = Some(p.name.pos)
              case _ => ()
            }
            if (result.isEmpty) { t.children.foreach(walk) }
          }
          case _ => t.children.foreach(walk)
        }
      }
    }
    walk(tree)
    result
  }

  // ── Java (javac) ───────────────────────────────────────────────────────

  private def locateInJava(path: Path, target: SymbolTarget): Location = {
    val source = new String(Files.readAllBytes(path))
    locateJavaInSource(path.toUri.toString, source, target)
  }

  private def locateJavaInSource(uri: String, source: String, target: SymbolTarget): Location = {
    val defaultLoc = new Location(uri, new Range(new Position(0, 0), new Position(0, 0)))
    val compiler = ToolProvider.getSystemJavaCompiler()
    if (compiler == null) {
      debug(s"  no system Java compiler available")
      defaultLoc
    } else {
      val fileObject = new SourceString(URI(uri), source)
      val task = compiler.getTask(
        null, // writer
        null, // fileManager
        null, // diagnosticListener (ignore parse errors)
        java.util.List.of("--enable-preview", "--source", Runtime.version().feature().toString),
        null, // classes
        java.util.List.of(fileObject),
      ).asInstanceOf[JavacTask]

      val compilationUnits = task.parse().asScala.toList
      compilationUnits.headOption match {
        case None => defaultLoc
        case Some(cu) => {
          val trees = Trees.instance(task)
          val sourcePositions = trees.getSourcePositions()
          val lineMap = cu.getLineMap()

          val pos = target.member match {
            case Some(memberName) => {
              findJavaMember(cu, sourcePositions, lineMap, target.className, memberName)
                .orElse(findJavaDecl(cu, sourcePositions, lineMap, target.className))
            }
            case None => findJavaDecl(cu, sourcePositions, lineMap, target.className)
          }
          pos match {
            case Some((line, col, endCol)) => {
              val start = new Position(line, col)
              val end = new Position(line, endCol)
              new Location(uri, new Range(start, end))
            }
            case None => defaultLoc
          }
        }
      }
    }
  }

  private def findJavaDecl(
    cu: CompilationUnitTree,
    sourcePositions: SourcePositions,
    lineMap: LineMap,
    name: String,
  ): Option[(Int, Int, Int)] = {
    var result: Option[(Int, Int, Int)] = None
    val scanner = new TreePathScanner[Void, Void] {
      override def visitClass(node: ClassTree, p: Void): Void = {
        if (result.isEmpty && node.getSimpleName.toString == name) {
          result = findNameInRange(cu, sourcePositions, lineMap, node, name)
        }
        super.visitClass(node, p)
      }
    }
    scanner.scan(cu, null)
    result
  }

  private def findJavaMember(
    cu: CompilationUnitTree,
    sourcePositions: SourcePositions,
    lineMap: LineMap,
    className: String,
    memberName: String,
  ): Option[(Int, Int, Int)] = {
    var result: Option[(Int, Int, Int)] = None
    var inTargetClass = false
    val scanner = new TreePathScanner[Void, Void] {
      override def visitClass(node: ClassTree, p: Void): Void = {
        val wasInTarget = inTargetClass
        if (node.getSimpleName.toString == className) {
          inTargetClass = true
        }
        val r = super.visitClass(node, p)
        inTargetClass = wasInTarget
        r
      }
      override def visitMethod(node: MethodTree, p: Void): Void = {
        if (result.isEmpty && inTargetClass && node.getName.toString == memberName) {
          result = findNameInRange(cu, sourcePositions, lineMap, node, memberName)
        }
        super.visitMethod(node, p)
      }
      override def visitVariable(node: VariableTree, p: Void): Void = {
        if (result.isEmpty && inTargetClass && node.getName.toString == memberName) {
          result = findNameInRange(cu, sourcePositions, lineMap, node, memberName)
        }
        super.visitVariable(node, p)
      }
    }
    scanner.scan(cu, null)
    result
  }

  /** Find the exact position of a name within a tree node's source range.
    * Same character-by-character identifier matching approach as Metals. */
  private def findNameInRange(
    cu: CompilationUnitTree,
    sp: SourcePositions,
    lineMap: LineMap,
    node: com.sun.source.tree.Tree,
    name: String,
  ): Option[(Int, Int, Int)] = {
    val startOffset = sp.getStartPosition(cu, node)
    val endOffset = sp.getEndPosition(cu, node)
    if (startOffset < 0) {
      None
    } else {
      val source = cu.getSourceFile.getCharContent(true)
      val len = source.length()
      val end = math.min(endOffset, len).toInt
      var i = startOffset.toInt
      var found: Option[(Int, Int, Int)] = None
      while (i < end && found.isEmpty) {
        val ch = source.charAt(i)
        if (Character.isJavaIdentifierStart(ch)) {
          var j = 1
          while (i + j < end && Character.isJavaIdentifierPart(source.charAt(i + j))) {
            j += 1
          }
          if (j == name.length) {
            var matches = true
            var k = 0
            while (k < j && matches) {
              if (source.charAt(i + k) != name.charAt(k)) { matches = false }
              k += 1
            }
            if (matches) {
              val line = lineMap.getLineNumber(i).toInt - 1
              val col = lineMap.getColumnNumber(i).toInt - 1
              found = Some((line, col, col + name.length))
            }
          }
          i += j
        } else {
          i += 1
        }
      }
      found
    }
  }

  // ── Text-based fallback (when scalameta can't parse) ─────────────────

  /** When scalameta can't parse the file (e.g. capture checking syntax),
    * fall back to finding the declaration by text pattern.
    * We already know the exact class and member name from the compiler. */
  private def locateByTextInSource(uri: String, source: String, target: SymbolTarget): Location = {
    val searchName = target.member.getOrElse(target.className)
    val declKeywords = List("def ", "val ", "var ", "type ", "class ", "trait ", "object ", "enum ", "given ")
    val lines = source.split("\n", -1)
    var found: Option[(Int, Int, Int)] = None
    var i = 0
    while (i < lines.length && found.isEmpty) {
      val line = lines(i)
      val trimmed = line.trim
      declKeywords.foreach { kw =>
        if (found.isEmpty && trimmed.contains(kw + searchName)) {
          val col = line.indexOf(kw + searchName) + kw.length
          found = Some((i, col, col + searchName.length))
          debug(s"  SourceLocator: text fallback found '$searchName' at $i:$col")
        }
      }
      i += 1
    }
    found match {
      case Some((line, col, endCol)) => {
        new Location(uri, new Range(new Position(line, col), new Position(line, endCol)))
      }
      case None => new Location(uri, new Range(new Position(0, 0), new Position(0, 0)))
    }
  }

  // ── Helpers ─────────────────────────────────────────────────────────────

  private def defaultLocation(path: Path): Location = {
    new Location(path.toUri.toString, new Range(new Position(0, 0), new Position(0, 0)))
  }

  private class SourceString(uri: URI, code: String)
    extends SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
    override def getCharContent(ignoreEncodingErrors: Boolean): CharSequence = code
  }
}
