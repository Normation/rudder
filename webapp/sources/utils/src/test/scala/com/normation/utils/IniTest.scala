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

import better.files.File
import com.normation.errors.*
import java.nio.charset.StandardCharsets
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/*
 * The first two examples are the Scala half of a cross-language conformance suite: they
 * assert, value for value, the same things as `it_reads_values_verbatim_without_unescaping`
 * and `it_trims_whitespace_around_keys_and_values` in
 * `relay/sources/rudder-package/src/config.rs`, over the same fixture file.
 *
 * They exist because `/opt/rudder/etc/rudder-pkg/rudder-pkg.conf` is written by the webapp
 * and read by both the webapp and `rudder package` (Rust). When the two readers disagree -
 * which they did, the webapp used `java.util.Properties`, i.e. ISO-8859-1 plus backslash
 * escape processing - credentials are corrupted on one side only and the failure surfaces
 * as an authentication error far away from its cause.
 *
 * If you change one side, change the other.
 */
@RunWith(classOf[JUnitRunner])
class IniTest extends Specification {

  // surefire runs with the module directory as CWD; `basedir` is the fallback for IDEs
  private val repositoryRoot = File(System.getProperty("basedir", ".")) / ".." / ".." / ".."
  private val fixtures       = repositoryRoot / "relay" / "sources" / "rudder-package" / "tests" / "config"

  private def readFixture(name: String): String = {
    val f = fixtures / name
    if (!f.exists) {
      failure(s"Missing shared fixture '${f.pathAsString}'. It is the one shared with the Rust reader's tests.")
    }
    f.contentAsString(using StandardCharsets.UTF_8)
  }

  private def parsed(content: String): Ini = {
    Ini.parse(content) match {
      case Right(ini) => ini
      case Left(err)  => throw new AssertionError(s"Could not parse: ${err.fullMsg}")
    }
  }

  "The INI reader" should {

    "read values verbatim, without unescaping" in {
      val ini = parsed(readFixture("rudder-pkg.tricky.conf"))

      ini.get("Rudder", "username") must beSome("user-é-ü")
      // a backslash is a backslash: no escape sequence, no line continuation, no \uXXXX
      // decoding, and `#`, `;` and `=` are ordinary characters inside a value
      ini.get("Rudder", "password") must beSome("""p@ss\w0rd\\next#hash;semi=equals "quoted" spaced""")
      ini.get("Rudder", "proxy_user") must beSome("mario")
      ini.get("Rudder", "proxy_password") must beSome("mot-de-passe-é")
    }

    "trim whitespace around keys and values" in {
      val ini = parsed("[Rudder]\n  username\t=\tuser  \n\tpassword =  s3cret \n")

      ini.get("Rudder", "username") must beSome("user")
      // consequence: a password with leading or trailing whitespace can not be
      // represented in this format, which is why `render` refuses to write one
      ini.get("Rudder", "password") must beSome("s3cret")
    }

    "read the default shipped configuration, empty values meaning 'unset'" in {
      val ini = parsed(readFixture("rudder-pkg.conf"))

      ini.get("Rudder", "username") must beSome("user")
      // `proxy_url =`: present but empty. `split('=')` would have dropped it entirely.
      ini.get("Rudder", "proxy_url") must beSome("")
      ini.getNonEmpty("Rudder", "proxy_url") must beNone
    }

    "keep sections apart" in {
      val ini = parsed("[Rudder]\npassword = good\n[Other]\npassword = bad\n")

      ini.get("Rudder", "password") must beSome("good")
      ini.get("Other", "password") must beSome("bad")
    }

    "only treat '#' and ';' as comments at the beginning of a line" in {
      val ini = parsed("# a comment\n; another one\n[Rudder]\npassword = a#b;c\n")

      ini.get("Rudder", "password") must beSome("a#b;c")
    }

    /*
     * The cases below are where a reader written "the obvious way" silently disagrees
     * with `serde_ini`: it looks at the very first character of the line, and it never
     * trims a section name. Each one is mirrored in `config.rs`.
     */
    "not trim section names" in {
      val ini = parsed("[ Rudder ]\nusername = user\n")

      // for `rudder package` this means the settings are ignored, not read
      ini.get("Rudder", "username") must beNone
      ini.get(" Rudder ", "username") must beSome("user")
    }

    "refuse an indented section header" in {
      Ini.parse("  [Rudder]\nusername = user\n") must beLeft
    }

    "refuse an indented comment" in {
      Ini.parse("[Rudder]\n  # a comment\n") must beLeft
    }

    "refuse a blank but not empty line, and accept an empty one" in {
      Ini.parse("[Rudder]\n \nusername = user\n") must beLeft
      Ini.parse("[Rudder]\n\nusername = user\n") must beRight
    }

    "refuse a section name holding a closing bracket" in {
      Ini.parse("[Rud]der]\n") must beLeft
    }

    "trim the Unicode definition of whitespace, not Java's" in {
      // Java's `strip` keeps the non-breaking spaces that Rust's `trim` removes, so a
      // plain `strip` here would read a different password than `rudder package` does
      val ini = parsed("[Rudder]\npassword =" + 0x00a0.toChar + "s3cret" + 0x202f.toChar + "\n")

      ini.get("Rudder", "password") must beSome("s3cret")
    }

    "refuse a line that is neither a section, a comment nor a key/value pair" in {
      Ini.parse("[Rudder]\nnot a pair\n") must beLeft[RudderError].like { case err => err.fullMsg must contain("line 2") }
    }

    "refuse a key defined twice in the same section, rather than pick a winner" in {
      Ini.parse("[Rudder]\npassword = one\npassword = two\n") must beLeft[RudderError].like {
        case err => err.fullMsg must contain("defined twice")
      }
    }
  }

  "The INI writer" should {

    val settings = List(
      "url"            -> "https://download.rudder.io/plugins",
      "username"       -> "user",
      "password"       -> """p@ss\w0rd""",
      "proxy_url"      -> "",
      "proxy_user"     -> "",
      "proxy_password" -> ""
    )

    "keep the layout of the file we ship" in {
      Ini.render("Rudder", settings) must beRight(
        """|[Rudder]
           |url = https://download.rudder.io/plugins
           |username = user
           |password = p@ss\w0rd
           |proxy_url =
           |proxy_user =
           |proxy_password =
           |""".stripMargin
      )
    }

    "round-trip every value it accepts" in {
      val rendered = Ini.render("Rudder", settings) match {
        case Right(s)  => s
        case Left(err) => throw new AssertionError(err.fullMsg)
      }
      parsed(rendered).section("Rudder") must beEqualTo(settings.toMap)
    }

    /*
     * The format has no escaping, so a newline in a value would not be lost, it would
     * inject arbitrary keys: this is what used to let a password silently repoint the
     * plugin repository URL.
     */
    "refuse a value containing a line break" in {
      val injection = List("password" -> "x\nurl = http://evil.example")

      Ini.render("Rudder", injection) must beLeft[RudderError].like { case err => err.fullMsg must contain("line break") }
    }

    "refuse a value that would be trimmed when read back" in {
      Ini.render("Rudder", List("password" -> " leading")) must beLeft
      Ini.render("Rudder", List("password" -> "trailing ")) must beLeft
    }

    /*
     * Java's `Character.isWhitespace` excludes the non-breaking spaces, Rust's
     * `char::is_whitespace` does not. `rudder package` would therefore trim a password
     * bounded by one of them while we would keep it, and the two would disagree about
     * the very same file - so we refuse to write it.
     */
    "refuse a value bounded by a non-breaking space, which Rust trims and Java does not" in {
      val nbsp = "secret" + 0x00a0.toChar

      nbsp.strip must beEqualTo(nbsp) // Java keeps it...
      Ini.render("Rudder", List("password" -> nbsp)) must beLeft // ...so we must refuse it
    }
  }
}
