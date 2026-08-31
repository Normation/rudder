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

package com.normation.utils

import com.normation.errors.*
import scala.annotation.tailrec

/*
 * A minimal reader/writer for the INI files shared with Rust, in particular
 * `/opt/rudder/etc/rudder-pkg/rudder-pkg.conf`.
 * That file is written by the webapp (Rudder Setup page) and read back by both the webapp and
 * `rudder package`.
 *
 * The reference implementation is the Rust one in `relay/sources/rudder-package/src/config.rs`.
 *
 * We didn't use a dependency because the obvious ones are unmaintained (ini4j), the other big and with
 * significantly different escaping rules than serde/Rust side (commonconfig2).
 *
 * The following rules apply:
 *
 * - the file is UTF-8 as everything in Rudder
 * - there is no escaping mechanism whatsoever, especially of backslash (see https://issues.rudder.io/issues/29519)
 * - `#` and `;` start a comment only at the beginning of a line, never inside a value
 * - a value is everything after the first `=`; only keys and values are trimmed, with
 *   Rust's Unicode definition of whitespace (see `rustTrim`);
 * - `[section]` headers are significant and a section name is
 *   taken verbatim between the brackets (`[ Rudder ]` isn't the `Rudder` section).
 * - indented section or comment is an error (no trim on line)
 *
 * In addition, we fail on following cases:
 *
 * - a duplicated key inside a section or a duplicated section;
 * - on `render` if a read-render-read cycles are not idempotent by `
 */
final case class Ini(sections: Map[String, Map[String, String]]) {

  def section(name: String): Map[String, String] = sections.getOrElse(name, Map.empty)

  def get(section: String, key: String): Option[String] = sections.get(section).flatMap(_.get(key))

  /*
   * `key =` and a missing key are the same thing for us: the default `rudder-pkg.conf`
   * ships with empty `proxy_url`/`proxy_user`/`proxy_password` lines meaning "unset",
   * and the Rust side maps them to `None` too.
   */
  def getNonEmpty(section: String, key: String): Option[String] = get(section, key).filter(_.nonEmpty)
}

object Ini {

  /*
   * Keys appearing before any `[section]` header. The Rust side ignores them (they
   * deserialize to unknown fields of the top-level struct), we just keep them apart.
   */
  val GlobalSection: String = ""

  private val CommentMarkers = Set('#', ';')

  /*
   * Rust's `char::is_whitespace` follows the Unicode `White_Space` property, whereas
   * Java's `Character.isWhitespace` excludes three non-breaking spaces, so we must align.
   */
  private val NonBreakingSpaces = Set(0x00a0, 0x2007, 0x202f) // NBSP, FIGURE SPACE, NARROW NBSP

  private def isRustWhitespace(c: Char): Boolean = {
    Character.isWhitespace(c) || NonBreakingSpaces.contains(c.toInt)
  }

  private def rustTrim(s: String): String = {
    val from = s.indexWhere(c => !isRustWhitespace(c))
    if (from < 0) "" // only whitespace
    else s.substring(from, s.lastIndexWhere(c => !isRustWhitespace(c)) + 1)
  }

  def parse(content: String): PureResult[Ini] = {
    // `-1` keeps trailing empty strings so that line numbers in errors match the file
    val lines = content.split("\\R", -1).toList.zipWithIndex.map { case (l, i) => (l, i + 1) }

    @tailrec
    def loop(remaining: List[(String, Int)], current: String, acc: Map[String, Map[String, String]]): PureResult[Ini] = {
      remaining match {
        case Nil                        => Right(Ini(acc))
        case (line, lineNumber) :: tail =>
          // we don't trim line to match `serde_ini` behavior
          if (line.startsWith("[")) {
            if (!line.endsWith("]")) {
              Left(Inconsistency(s"Error at line ${lineNumber}: section missing ']'"))
            } else {
              val name = line.substring(1, line.length - 1)
              if (name.contains("]")) {
                Left(Inconsistency(s"Error at line ${lineNumber}: section name contains ']'"))
              } else if (acc.contains(name)) {
                Left(Inconsistency(s"Error at line ${lineNumber}: section '${name}' is declared twice"))
              } else {
                loop(tail, name, acc + (name -> Map.empty))
              }
            }
          } else if (CommentMarkers.exists(c => line.startsWith(c.toString))) {
            loop(tail, current, acc)
          } else {
            // `indexOf` and not `split('=')` because it drops trailing empty string like
            // `"proxy_url ="` in the default `rudder-pkg.conf`
            line.indexOf('=') match {
              case -1  =>
                if (line.isEmpty) loop(tail, current, acc)
                // a non-key-value non-empty line is an error in `serde_ini`
                else Left(Inconsistency(s"Error at line ${lineNumber}: variable assignment missing '=', got '${line}'"))
              case pos =>
                val key   = rustTrim(line.substring(0, pos))
                val value = rustTrim(line.substring(pos + 1))
                if (acc.get(current).exists(_.contains(key))) {
                  Left(
                    Inconsistency(
                      s"Error at line ${lineNumber}: key '${key}' is defined twice in section '${current}', " +
                      s"which implementation wins is not specified for that file format"
                    )
                  )
                } else {
                  loop(tail, current, acc.updatedWith(current)(s => Some(s.getOrElse(Map.empty) + (key -> value))))
                }
            }
          }
      }
    }

    loop(lines, GlobalSection, Map.empty)
  }

  /*
   * Check that every entry can be written and read back identically.
   * All the entries are checked, so that the user gets every problem at once.
   */
  def checkEntries(values: List[(String, String)]): PureResult[Unit] = {
    values.accumulatePure { case (k, v) => checkKey(k).flatMap(_ => checkValue(k, v)) }.map(_ => ())
  }

  /*
   * Serialize one section. Values are kept ordered as they were provided.
   */
  def render(section: String, values: List[(String, String)]): PureResult[String] = {
    for {
      _ <- checkSection(section)
      _ <- checkEntries(values)
    } yield {
      values.map {
        case (k, v) =>
          if (v.isEmpty) s"${k} ="
          else s"${k} = ${v}"
      }.mkString(s"[${section}]\n", "\n", "\n")
    }
  }

  private def checkSection(section: String): PureResult[Unit] = {
    if (section.isEmpty) Left(Inconsistency("An INI section name can not be empty"))
    else if (section.exists(c => c == '[' || c == ']' || c == '\n' || c == '\r')) {
      Left(Inconsistency(s"Invalid INI section name '${section}': it can not contain '[', ']' or a line break"))
    } else Right(())
  }

  private def checkKey(key: String): PureResult[Unit] = {
    if (key.isEmpty) Left(Inconsistency("An INI key can not be empty"))
    else if (key.exists(c => c == '=' || c == '\n' || c == '\r')) {
      Left(Inconsistency(s"Invalid INI key '${key}': it can not contain '=' or a line break"))
    } else if (CommentMarkers.contains(key.charAt(0))) {
      Left(Inconsistency(s"Invalid INI key '${key}': it can not start with '#' or ';', it would be read back as a comment"))
    } else if (key.exists(isRustWhitespace)) {
      Left(Inconsistency(s"Invalid INI key '${key}': it can not contain whitespace"))
    } else Right(())
  }

  /*
   * The format has no escaping, so anything we can not write verbatim must be refused.
   *
   * Two cases:
   * - a line break could inject arbitrary keys into the file, for ex `x\nurl = http://evil.example`
   * - leading/trailing whitespace is trimmed by every reader of that format
   */
  private def checkValue(key: String, value: String): PureResult[Unit] = {
    if (value.exists(c => c == '\n' || c == '\r')) {
      Left(Inconsistency(s"Value for '${key}' can not contain a line break: that file format has no escaping mechanism"))
    } else if (value.nonEmpty && (isRustWhitespace(value.charAt(0)) || isRustWhitespace(value.charAt(value.length - 1)))) {
      Left(
        Inconsistency(
          s"Value for '${key}' can not start or end with a whitespace character: " +
          s"it would be trimmed when the file is read back"
        )
      )
    } else Right(())
  }
}
