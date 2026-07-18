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

import com.normation.errors.*
import org.apache.commons.lang3.Strings
import scala.util.control.NonFatal

/*
 * A minimal template engine implementing the subset of the ampersand-delimited
 * StringTemplate language actually used by Rudder policy templates (".st" files):
 *
 *   - variable expansion:  &VAR& and &VAR.property&
 *   - conditionals:        &if(X)& ... &else& ... &endif&  and  &if(!X)&
 *                          (presence / boolean / non-empty-list test only)
 *   - parallel iteration:  &LIST1, LIST2 : { p1, p2 | body }; separator=", "&
 *                          with implicit iterator &it& when no parameter is
 *                          declared, and counters &i& (1-based) / &i0& (0-based)
 *   - escapes in text:     \& for a literal '&', \\ for '\'
 *   - comments:            &! ... !&
 *
 * A template is parsed once into an immutable AST (thread safe, cacheable and
 * shareable without locks), then rendering is a pure function of the AST and
 * the variable values. This is the replacement for the mutable, synchronized
 * StringTemplate instances.
 *
 * All layout rules of the ST3 ampersand lexer/writer are reproduced (whitespace
 * skip after the subtemplate '|', newline handling around conditional markers,
 * auto-indent, empty-expression line elision): the output is byte-identical to
 * StringTemplate 3 on all Rudder templates (see WriteSystemTechniquesFastparseTest).
 */

sealed trait TemplateAst
object TemplateAst {
  final case class Txt(text: String) extends TemplateAst

  // parse-time marker for '&!...!&', resolved by post-processing (a comment starting
  // its line swallows the following newline in ST3); it never survives parsing
  case object Comment extends TemplateAst

  /*
   * On expressions, two ST3 layout behaviors are pre-computed at parse time:
   * - `indent` ("auto-indent"): when an expression starts a line after only whitespace, that
   *   whitespace is removed from the text and re-applied to each line of a non-empty expansion;
   * - `trailingNewline` (empty-line elision): when an expression is alone on its line, the
   *   following newline is moved onto it and only emitted if the expansion is not empty,
   *   so that an empty expansion removes its whole line.
   */
  final case class Ref(
      name:            String,
      prop:            Option[String],
      separator:       Option[String] = None,
      indent:          Option[String] = None,
      trailingNewline: Option[String] = None
  ) extends TemplateAst
  final case class Cond(
      name:            String,
      negated:         Boolean,
      thenBody:        Seq[TemplateAst],
      elseBody:        Seq[TemplateAst],
      trailingNewline: Option[String] = None
  ) extends TemplateAst
  final case class Loop(
      attrs:           Seq[String],
      params:          Seq[String],
      body:            Seq[TemplateAst],
      separator:       Option[String],
      indent:          Option[String] = None,
      trailingNewline: Option[String] = None
  ) extends TemplateAst
}

final case class ParsedTemplate(parts: Seq[TemplateAst])

object AmpersandTemplate {
  import TemplateAst.*

  def parse(content: String): PureResult[ParsedTemplate] = Parser.parse(content)

  /*
   * Fill a parsed template with the same variable semantics as
   * FillTemplateThreadUnsafe.fill: system variables that may be empty and are
   * empty are absent (conditionals on them are false, their expansion is empty),
   * a mandatory empty variable is an error, a single value is a scalar and
   * multiple values are a list.
   * Returns (filled content, file name): when `replaceId` is defined (multi-policy
   * techniques), its (from, to) pair is available as a template variable and
   * substituted in the returned file name.
   */
  def fill(
      template:     ParsedTemplate,
      templateName: String,
      variables:    Seq[STVariable],
      replaceId:    Option[(String, String)]
  ): PureResult[(String, String)] = {
    val mandatoryEmpty = variables.collect { case v if !v.mayBeEmpty && v.values.isEmpty => v.name }
    if (mandatoryEmpty.nonEmpty) {
      Left(
        Unexpected(s"Mandatory variables ${mandatoryEmpty.mkString("'", "', '", "'")} are empty, can not write ${templateName}")
      )
    } else {
      val scope = variables.foldLeft(Map.empty[String, Any]) {
        case (m, v) =>
          if (v.isSystem && v.mayBeEmpty && (v.values.isEmpty || (v.values.sizeIs == 1 && v.values.head == ""))) m
          else if (v.values.sizeIs == 1) m + (v.name -> v.values.head)
          else m + (v.name                           -> v.values)
      }
      replaceId match {
        case None             => Right((render(template.parts, scope), templateName))
        case Some((from, to)) =>
          Right((render(template.parts, scope + (from -> to)), Strings.CS.replace(templateName, from, to)))
      }
    }
  }

  /*
   * Pure rendering of a template given variable values. A value can be a Seq or
   * an Array (list variable), a Boolean (conditions), or anything else rendered
   * with its toString. An absent variable renders as empty and is false in
   * conditions, like a null attribute in StringTemplate.
   */
  def render(parts: Seq[TemplateAst], scope: Map[String, Any]): String = {
    val sb = new java.lang.StringBuilder(1024)
    append(sb, parts, scope)
    sb.toString
  }

  private val builtins = List("i", "i0")

  private def append(sb: java.lang.StringBuilder, parts: Seq[TemplateAst], scope: Map[String, Any]): Unit = {
    parts.foreach {
      case Comment => ()

      case Txt(t) =>
        sb.append(t): Unit

      case Ref(name, prop, sep, indent, trailingNl) =>
        exprOut(sb, indent, trailingNl) { out =>
          resolve(scope, name).foreach { v =>
            prop match {
              case None    => appendValue(out, v, sep)
              case Some(p) => Option(property(v, p)).foreach(appendValue(out, _, sep))
            }
          }
        }

      case Cond(name, negated, thenBody, elseBody, trailingNl) =>
        exprOut(sb, None, trailingNl) { out =>
          append(out, if (truthy(resolve(scope, name)) != negated) thenBody else elseBody, scope)
        }

      case Loop(attrs, params, body, separator, indent, trailingNl) =>
        exprOut(sb, indent, trailingNl) { out =>
          val lists = attrs.map(a => asList(resolve(scope, a)))
          val names = if (params.isEmpty) List("it") else params
          val len   = lists.map(_.size).max
          var i     = 0
          while (i < len) {
            if (i > 0) separator.foreach(s => out.append(s): Unit)
            val bound =
              names.iterator.zip(lists.iterator).flatMap { case (n, l) => l.lift(i).filter(_ != null).map(n -> _) }.toMap
            append(out, body, scope -- names -- builtins ++ bound + ("i" -> (i + 1)) + ("i0" -> i))
            i += 1
          }
        }
    }
  }

  /*
   * Write an expression expansion with ST3 layout semantics: an empty expansion emits
   * nothing at all (not even its line's indentation or newline); a non-empty one is
   * lazily indented (the indentation is only written before a non-newline character,
   * like ST3's AutoIndentWriter does) then followed by the moved trailing newline.
   */
  private def exprOut(sb: java.lang.StringBuilder, indent: Option[String], trailingNl: Option[String])(
      write: java.lang.StringBuilder => Unit
  ): Unit = {
    if (indent.isEmpty && trailingNl.isEmpty) write(sb)
    else {
      val out = new java.lang.StringBuilder(64)
      write(out)
      if (out.length > 0) {
        indent match {
          case None      => sb.append(out): Unit
          case Some(ind) =>
            var pending = true
            var i       = 0
            while (i < out.length) {
              val c = out.charAt(i)
              if (c == '\n' || c == '\r') {
                sb.append(c): Unit
                pending = true
              } else {
                if (pending) { sb.append(ind): Unit; pending = false }
                sb.append(c): Unit
              }
              i += 1
            }
        }
        trailingNl.foreach(s => sb.append(s): Unit)
      }
    }
  }

  private def resolve(scope: Map[String, Any], name: String): Option[Any] = scope.get(name).filter(_ != null)

  // a multi-valued reference concatenates its non-null values, with the separator between them if any
  private def appendValue(sb: java.lang.StringBuilder, v: Any, sep: Option[String]): Unit = {
    def list(l: Seq[Any]): Unit = {
      var first = true
      l.foreach { e =>
        if (e != null) {
          if (!first) sep.foreach(s => sb.append(s): Unit)
          appendValue(sb, e, sep)
          first = false
        }
      }
    }
    v match {
      case s: Seq[?]   => list(s.asInstanceOf[Seq[Any]])
      case a: Array[?] => list(scala.collection.immutable.ArraySeq.unsafeWrapArray(a).asInstanceOf[Seq[Any]])
      case other => sb.append(other.toString): Unit
    }
  }

  // same truth table as ST3: null is false, a Boolean is itself, a list is its non-emptiness, anything else is true
  private def truthy(v: Option[Any]): Boolean = v match {
    case None              => false
    case Some(b: Boolean)  => b
    case Some(s: Seq[?])   => s.nonEmpty
    case Some(a: Array[?]) => a.nonEmpty
    case Some(_)           => true
  }

  private def asList(v: Option[Any]): Seq[Any] = v match {
    case None              => Nil
    case Some(s: Seq[?])   => s.asInstanceOf[Seq[Any]]
    case Some(a: Array[?]) => scala.collection.immutable.ArraySeq.unsafeWrapArray(a).asInstanceOf[Seq[Any]]
    case Some(other)       => other :: Nil
  }

  // property access on a map or an object accessor (only used as &parameter.escapedValue& & co)
  private def property(v: Any, prop: String): Any = {
    try {
      v match {
        case m: java.util.Map[?, ?]        => m.get(prop)
        case m: scala.collection.Map[?, ?] => m.asInstanceOf[scala.collection.Map[String, Any]].getOrElse(prop, null)
        case other => other.getClass.getMethod(prop).invoke(other)
      }
    } catch { case NonFatal(_) => null }
  }

  private object Parser {
    import fastparse.*
    import fastparse.NoWhitespace.*

    def parse(content: String): PureResult[ParsedTemplate] = {
      fastparse.parse(content, { case given P[?] => template }) match {
        case Parsed.Success(parts, _) => Right(ParsedTemplate(parts))
        case f: Parsed.Failure => Left(Inconsistency(s"Error when parsing template: ${f.trace().longMsg}"))
      }
    }

    private val keywords = Set("if", "elseif", "else", "endif")

    /*
     * ST3 auto-indent, lexer side: when an expression is only preceded by whitespace on its
     * line, that whitespace is moved from the literal text to the expression (see `indented`
     * in the renderer), except for conditional markers where ST3 simply discards it.
     * `atLineStart` tells if the first part of the list starts on a fresh line.
     */
    private def extractIndents(parts: Seq[TemplateAst], atLineStart: Boolean): Seq[TemplateAst] = {
      def lineStartIndent(t: String, isFirst: Boolean): Option[(String, String)] = {
        val idx = t.lastIndexOf('\n')
        val ind = t.substring(idx + 1)
        if (ind.nonEmpty && ind.forall(c => c == ' ' || c == '\t') && (idx >= 0 || (isFirst && atLineStart))) {
          Some((t.substring(0, idx + 1), ind))
        } else None
      }

      val res = scala.collection.mutable.ArrayBuffer[TemplateAst]()
      parts.foreach { p =>
        (res.lastOption, p) match {
          case (Some(Txt(t)), (_: Ref | _: Loop | _: Cond)) =>
            lineStartIndent(t, res.sizeIs == 1) match {
              case Some((remaining, ind)) =>
                res.dropRightInPlace(1)
                if (remaining.nonEmpty) res += Txt(remaining)
                res += (p match {
                  case r: Ref  => r.copy(indent = Some(ind))
                  case l: Loop => l.copy(indent = Some(ind))
                  case other => other // conditional: the indentation is discarded
                })
              case None                   => res += p
            }
          case _                                            => res += p
        }
      }
      res.toSeq
    }

    /*
     * ST3 empty-line elision (from StringTemplate.write): when an expression chunk alone on
     * its line writes nothing, the following newline is skipped. The newline is moved onto
     * the expression at parse time and the renderer emits it only for a non-empty expansion.
     * Line starts are determined on the original parts (matching ST3's chunk list).
     */
    private def elideEmptyExprLines(parts: Seq[TemplateAst], atLineStart: Boolean): Seq[TemplateAst] = {
      val res  = scala.collection.mutable.ArrayBuffer[TemplateAst]()
      val v    = parts.toVector
      var drop = 0
      var i    = 0
      while (i < v.length) {
        val p         = v(i) match {
          case Txt(t) => Txt(t.substring(drop))
          case other  => other
        }
        drop = 0
        val lineStart = {
          if (i == 0) atLineStart
          else {
            v(i - 1) match {
              case Txt(t) => t.endsWith("\n")
              case _      => false
            }
          }
        }
        val nextNl    = v.lift(i + 1) match {
          case Some(Txt(t)) if t.startsWith("\r\n") => Some("\r\n")
          case Some(Txt(t)) if t.startsWith("\n")   => Some("\n")
          case _                                    => None
        }
        p match {
          case r: Ref if lineStart && nextNl.isDefined  =>
            res += r.copy(trailingNewline = nextNl)
            drop = nextNl.get.length
          case l: Loop if lineStart && nextNl.isDefined =>
            res += l.copy(trailingNewline = nextNl)
            drop = nextNl.get.length
          case c: Cond if lineStart && nextNl.isDefined =>
            res += c.copy(trailingNewline = nextNl)
            drop = nextNl.get.length
          case Txt(t) if t.isEmpty => () // fully consumed by the previous expression
          case other               => res += other
        }
        i += 1
      }
      res.toSeq
    }

    /*
     * Remove the Comment markers: as in the ST3 lexer, a comment starting its line also
     * swallows the following newline (its whole line disappears). Adjacent texts are
     * merged so that the other layout passes see through removed comments.
     */
    private def resolveComments(parts: Seq[TemplateAst], atLineStart: Boolean): Seq[TemplateAst] = {
      val res    = scala.collection.mutable.ArrayBuffer[TemplateAst]()
      var dropNl = false
      parts.foreach {
        case Comment =>
          val lineStart = res.lastOption match {
            case Some(Txt(t)) => t.endsWith("\n")
            case Some(_)      => false
            case None         => atLineStart
          }
          if (lineStart) dropNl = true
        case Txt(t)  =>
          val t2 = if (dropNl) t.replaceFirst("^\\r?\\n", "") else t
          dropNl = false
          if (t2.nonEmpty) {
            res.lastOption match {
              case Some(Txt(prev)) => res(res.size - 1) = Txt(prev + t2)
              case _               => res += Txt(t2)
            }
          }
        case other   =>
          dropNl = false
          res += other
      }
      res.toSeq
    }

    private def postProcess(parts: Seq[TemplateAst], atLineStart: Boolean): Seq[TemplateAst] =
      elideEmptyExprLines(extractIndents(resolveComments(parts, atLineStart), atLineStart), atLineStart)

    private def template[A: P]: P[Seq[TemplateAst]] =
      P(parts(inSub = false) ~ End).map(postProcess(_, atLineStart = true))

    private def parts[A: P](inSub: Boolean): P[Seq[TemplateAst]] = P(part(inSub).rep).map(_.flatten)

    private def part[A: P](inSub: Boolean): P[Seq[TemplateAst]] = P(
      comment.map(_ => Seq(Comment))
      | cond(inSub)
      | expression.map(Seq(_))
      | escapedChar.map(c => Seq(Txt(c)))
      | (if (inSub) braced else Fail)
      | text(inSub).map(t => Seq(Txt(t)))
    )

    // a run of literal text; inside a subtemplate braces are matched separately to find its end
    private def text[A: P](inSub: Boolean): P[String] = P(
      CharsWhile(c => c != '&' && c != '\\' && !(inSub && (c == '{' || c == '}'))).!
    )

    // \& is a literal '&', \\ a literal '\', any other backslash is kept verbatim (as in the ST ampersand lexer)
    private def escapedChar[A: P]: P[String] = P("\\" ~ AnyChar.!).map {
      case "&"   => "&"
      case "\\"  => "\\"
      case other => "\\" + other
    }

    private def comment[A: P]: P[Unit] = P("&!" ~ (!"!&" ~ AnyChar).rep ~ "!&")

    // balanced braces inside a subtemplate body are plain text (e.g. cfengine lists)
    private def braced[A: P]: P[Seq[TemplateAst]] =
      P("{" ~ parts(inSub = true) ~ "}").map(inner => (Txt("{") +: inner) :+ Txt("}"))

    private def ws[A:    P]: P[Unit]   = P(CharsWhileIn(" \t", 0))
    private def eol[A:   P]: P[Unit]   = P("\r".? ~ "\n")
    private def name[A:  P]: P[String] = P(CharsWhileIn("a-zA-Z0-9_").!)
    private def ident[A: P]: P[String] = name.filter(n => !keywords(n))

    private def quoted[A:    P]: P[String] = P("\"" ~ CharsWhile(_ != '"', 0).! ~ "\"")
    private def separator[A: P]: P[String] = P(ws ~ ";" ~ ws ~ "separator" ~ ws ~ "=" ~ ws ~ quoted)

    private def cond[A: P](inSub: Boolean): P[Seq[TemplateAst]] = P(
      "&if" ~ ws ~ "(" ~ ws ~ "!".!.? ~ ws ~ name ~ ws ~ ")&" ~ eol.!.? ~ parts(inSub) ~
      ("&else&" ~ eol.!.? ~ parts(inSub)).? ~ "&endif&" ~ eol.!.?
    ).map {
      case (neg, n, thenNl, thenBody, elseBranch, endifNl) =>
        val (elseNl, elseBody) = elseBranch.map { case (nl, b) => (nl, b) }.getOrElse((None, Nil))

        def body(parts: Seq[TemplateAst], nl: Option[String]): Seq[TemplateAst] = {
          val atLineStart = nl.isDefined
          elideEmptyExprLines(dropLastNewline(extractIndents(resolveComments(parts, atLineStart), atLineStart)), atLineStart)
        }

        // '&endif&' is at column 1 when directly preceded by a newline (or an empty body starting a line)
        val lastBranch  = if (elseBranch.isDefined) (elseBody, elseNl) else (thenBody, thenNl)
        val endifAtCol1 = lastBranch._1.lastOption match {
          case Some(Txt(t)) => t.endsWith("\n")
          case Some(_)      => false
          case None         => lastBranch._2.isDefined
        }
        val c           = Cond(n, neg.isDefined, body(thenBody, thenNl), body(elseBody, elseNl))
        endifNl match {
          // ST3 only swallows the newline after '&endif&' when the marker starts its line
          case Some(nl) if !endifAtCol1 => Seq(c, Txt(nl))
          case _                        => Seq(c)
        }
    }

    // ST3 drops the newline (and the indentation of the marker) just before '&else&' / '&endif&'
    private def dropLastNewline(parts: Seq[TemplateAst]): Seq[TemplateAst] = parts.lastOption match {
      case Some(Txt(t)) =>
        val stripped = t.replaceFirst("\\r?\\n[ \\t]*$", "")
        if (stripped.isEmpty) parts.init else parts.init :+ Txt(stripped)
      case _            => parts
    }

    // like the ST3 action parser, whitespace is tolerated between the tokens of an expression
    private def expression[A: P]: P[TemplateAst] = P(loop | ref)

    // '&LIST;separator=","&' (no subtemplate) joins the values with the separator
    private def ref[A: P]: P[TemplateAst] = P("&" ~ ws ~ ident ~ (ws ~ "." ~ ws ~ ident).? ~ separator.? ~ ws ~ "&").map {
      case (n, p, sep) => Ref(n, p, sep)
    }

    private def loop[A: P]: P[TemplateAst] = P(
      "&" ~ ws ~ ident.rep(1, sep = ws ~ "," ~ ws) ~ ws ~ ":" ~ ws ~ subtemplate ~ separator.? ~ ws ~ "&"
    ).map { case (attrs, (params, body), sep) => Loop(attrs, params, body, sep) }

    private def subtemplate[A: P]: P[(Seq[String], Seq[TemplateAst])] = P(
      // ST3 skips a single whitespace character right after the parameters' '|'
      "{" ~ (ws ~ ident.rep(1, sep = ws ~ "," ~ ws) ~ ws ~ "|" ~ ("\r\n" | CharIn(" \t\n")).!.?).? ~ parts(inSub = true) ~ "}"
    ).map {
      case (paramsAndNl, body) =>
        val (params, nl) = paramsAndNl.map { case (ps, nl) => (ps, nl) }.getOrElse((Nil, None))
        (params, postProcess(body, atLineStart = nl.exists(_.contains("\n"))))
    }
  }
}
