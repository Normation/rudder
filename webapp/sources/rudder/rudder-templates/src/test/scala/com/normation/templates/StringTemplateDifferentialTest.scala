/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
 *************************************************************************************
 *
 * This file is part of Rudder.
 *
 * Rudder is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * In accordance with the terms of section 7 (7. Additional Terms.) of
 * the GNU General Public License version 3, the copyright holders add
 * the following Additional permissions:
 * Notwithstanding to the terms of section 5 (5. Conveying Modified Source
 * Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
 * Public License version 3, when you create a Related Module, this
 * Related Module is not considered as a part of the work and may be
 * distributed under the license agreement of your choice.
 * A "Related Module" means a set of sources files including their
 * documentation that, without modification of the Source Code, enables
 * supplementary functions or services in addition to those offered by
 * the Software.
 *
 * Rudder is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

 *
 *************************************************************************************
 */

package com.normation.templates

import com.normation.stringtemplate.language.NormationAmpersandTemplateLexer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.antlr.stringtemplate.StringTemplate
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.jdk.CollectionConverters.*

/**
 * Differential test between StringTemplate 3 and AmpersandTemplate: every .st
 * file of the rudder-techniques repository is filled by both engines with the
 * same synthetic variable values (derived from the variables each template
 * references), in two passes (conditions all satisfied / all unsatisfied), and
 * the outputs must be identical up to whitespace (each line trimmed, blank
 * lines ignored - ST3 auto-indentation and newline-swallowing rules are pure
 * layout).
 *
 * The rudder-techniques repository is located by the RUDDER_TECHNIQUES_DIR env
 * variable, defaulting to a checkout next to the rudder repository; the test is
 * skipped when not found.
 */
@RunWith(classOf[JUnitRunner])
class StringTemplateDifferentialTest extends Specification {

  // all corpora of real templates: the rudder-techniques repository (located by env
  // variable, with a default for a checkout next to the rudder one) and the test
  // techniques of rudder-core (which contain older syntax, e.g. '& VAR ...&')
  val templateRoots: List[Path] = List(
    Paths.get(sys.env.getOrElse("RUDDER_TECHNIQUES_DIR", "../../../../../rudder-techniques")),
    Paths.get("../rudder-core/src/test/resources/configuration-repository/techniques")
  ).map(_.toAbsolutePath.normalize).filter(Files.isDirectory(_))

  args(skipAll = templateRoots.isEmpty)

  def stFiles(root: Path): List[Path] = {
    Files
      .walk(root)
      .iterator()
      .asScala
      .filter(f => f.toString.endsWith(".st") && Files.isRegularFile(f))
      .toList
      .sortBy(_.toString)
  }

  // what a template references, to generate matching synthetic values
  final case class Usage(refs: Set[String], props: Map[String, Set[String]], conds: Set[String], loopAttrs: Set[String])

  def usageOf(parts: Seq[TemplateAst]): Usage = {
    import scala.collection.mutable
    val refs      = mutable.Set[String]()
    val props     = mutable.Map[String, mutable.Set[String]]()
    val conds     = mutable.Set[String]()
    val loopAttrs = mutable.Set[String]()

    // bound maps a loop parameter to the list variable it iterates (empty for builtins),
    // so that property access on a parameter is attributed to the iterated variable
    def go(parts: Seq[TemplateAst], bound: Map[String, String]): Unit = parts.foreach {
      case _: TemplateAst.Txt => ()
      case TemplateAst.Comment => ()
      case r: TemplateAst.Ref  =>
        bound.get(r.name) match {
          case Some(owner) => r.prop.foreach(pp => if (owner.nonEmpty) props.getOrElseUpdate(owner, mutable.Set()) += pp)
          case None        =>
            refs += r.name
            r.prop.foreach(pp => props.getOrElseUpdate(r.name, mutable.Set()) += pp)
        }
      case c: TemplateAst.Cond =>
        if (!bound.contains(c.name)) conds += c.name
        go(c.thenBody, bound)
        go(c.elseBody, bound)
      case l: TemplateAst.Loop =>
        l.attrs.foreach(a => if (!bound.contains(a)) loopAttrs += a)
        val names = if (l.params.isEmpty) List("it") else l.params.toList
        go(l.body, bound ++ names.zip(l.attrs))
    }

    go(parts, Map("i" -> "", "i0" -> ""))
    Usage(refs.toSet, props.view.mapValues(_.toSet).toMap, conds.toSet, loopAttrs.toSet)
  }

  def valuesFor(u: Usage, condsSatisfied: Boolean): Map[String, Any] = {
    (u.refs ++ u.conds ++ u.loopAttrs).flatMap { n =>
      def obj(i: Int): java.util.HashMap[String, String] = {
        val m = new java.util.HashMap[String, String]()
        u.props(n).foreach(p => m.put(p, s"${n}_${p}_${i}"))
        m
      }
      if (u.conds(n) && !condsSatisfied) None
      else if (u.props.contains(n) && u.loopAttrs(n)) Some(n -> Seq(obj(1), obj(2), obj(3)))
      else if (u.props.contains(n)) Some(n -> obj(1))
      else if (u.loopAttrs(n)) Some(n -> Seq(s"${n}_1", s"${n}_2", s"${n}_3"))
      else if (u.conds(n) && !u.refs(n)) Some(n -> java.lang.Boolean.TRUE)
      else Some(n                               -> s"${n}_value")
    }.toMap
  }

  var stNanos:  Long = 0L
  var newNanos: Long = 0L

  def fillSt3(content: String, values: Map[String, Any]): String = {
    val t0  = System.nanoTime()
    val st  = new StringTemplate(content, classOf[NormationAmpersandTemplateLexer])
    values.foreach {
      case (n, v) =>
        v match {
          // production code passes multi-values as a raw array, see FillTemplateThreadUnsafe
          case s: Seq[?] => st.setAttribute(n, s.asInstanceOf[Seq[Any]].toArray)
          case other => st.setAttribute(n, other)
        }
    }
    val res = st.toString
    stNanos += System.nanoTime() - t0
    res
  }

  def fillNew(content: String, values: Map[String, Any]): Either[String, String] = {
    val t0  = System.nanoTime()
    val res = AmpersandTemplate.parse(content).map(p => AmpersandTemplate.render(p.parts, values))
    newNanos += System.nanoTime() - t0
    res.left.map(_.fullMsg)
  }

  def normalize(s: String): String = s.linesIterator.map(_.trim).filterNot(_.isEmpty).mkString("\n")

  def firstDiff(a: String, b: String): String = {
    val la = a.linesIterator.toVector
    val lb = b.linesIterator.toVector
    val i  = la.zipAll(lb, "<missing line>", "<missing line>").indexWhere(p => p._1 != p._2)
    s"line ${i + 1}:\n  st3: ${la.applyOrElse(i, (_: Int) => "<missing line>")}\n  new: ${lb.applyOrElse(i, (_: Int) => "<missing line>")}"
  }

  def check(root: Path, file: Path): List[String] = {
    val name    = root.relativize(file).toString
    val content = Files.readString(file, StandardCharsets.UTF_8)
    AmpersandTemplate.parse(content) match {
      case Left(err)     => List(s"$name: parse error: ${err.fullMsg}")
      case Right(parsed) =>
        val u = usageOf(parsed.parts)
        List(true, false).flatMap { condsSatisfied =>
          val values = valuesFor(u, condsSatisfied)
          val st3    = normalize(fillSt3(content, values))
          fillNew(content, values) match {
            case Left(err)  => List(s"$name: render error: $err")
            case Right(out) =>
              val newOut = normalize(out)
              if (st3 == newOut) Nil
              else List(s"$name (conditions ${if (condsSatisfied) "satisfied" else "unsatisfied"}): ${firstDiff(st3, newOut)}")
          }
        }
    }
  }

  "all known .st templates" should {
    "render identically (up to whitespace) with ST3 and AmpersandTemplate" in {
      val files    = templateRoots.map(r => (r, stFiles(r)))
      val failures = files.flatMap { case (root, fs) => fs.flatMap(check(root, _)) }
      println(
        s"[differential] ${files.map(_._2.size).sum} templates x 2 passes; ST3 fill: ${stNanos / 1000000} ms, new engine parse+render: ${newNanos / 1000000} ms"
      )
      failures aka s"${failures.size} mismatch(es):\n${failures.mkString("\n\n")}" must beEmpty
    }

    // indicative hot-path timing: in production the template is parsed once and cached,
    // then filled for every (node, directive), so only the fill part matters
    "print an indicative fill-time comparison on the biggest template" in {
      val file    = templateRoots.flatMap(stFiles).maxBy(Files.size)
      val content = Files.readString(file, StandardCharsets.UTF_8)
      val values  = valuesFor(usageOf(AmpersandTemplate.parse(content).toOption.get.parts), condsSatisfied = true)
      val n       = 1000

      def time(warmupAndRun: () => Unit): Long = {
        (0 until n).foreach(_ => warmupAndRun())
        val t0 = System.nanoTime()
        (0 until n).foreach(_ => warmupAndRun())
        System.nanoTime() - t0
      }

      val source  = new StringTemplate(content, classOf[NormationAmpersandTemplateLexer])
      val st3Time = time { () =>
        val t = source.getInstanceOf()
        values.foreach {
          case (k, v) =>
            v match {
              case s: Seq[?] => t.setAttribute(k, s.asInstanceOf[Seq[Any]].toArray)
              case other => t.setAttribute(k, other)
            }
        }
        t.toString: Unit
      }

      val parsed  = AmpersandTemplate.parse(content).toOption.get
      val newTime = time(() => AmpersandTemplate.render(parsed.parts, values): Unit)

      println(
        s"[bench] ${file.getFileName} (${Files.size(file)} bytes), $n fills: " +
        s"ST3 ${st3Time / 1000000} ms, new engine ${newTime / 1000000} ms (x${"%.1f".format(st3Time.toDouble / newTime)})"
      )
      ok
    }
  }
}
