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

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.collection.immutable.ArraySeq

final case class TestParameter(parameterName: String, escapedValue: String)

@RunWith(classOf[JUnitRunner])
class AmpersandTemplateTest extends Specification {

  private def fill(template: String, vars: (String, Any)*): String = {
    AmpersandTemplate.parse(template) match {
      case Right(parsed) => AmpersandTemplate.render(parsed.parts, vars.toMap)
      case Left(err)     => throw new RuntimeException(err.fullMsg)
    }
  }

  "variable expansion" should {
    "replace a simple variable" in {
      fill("Hello, &name&", "name" -> "World") === "Hello, World"
    }
    "render an absent variable as empty" in {
      fill("Hello, &name&!") === "Hello, !"
    }
    "concatenate the values of a list variable" in {
      fill("&list&", "list" -> Seq("a", "b", "c")) === "abc"
    }
    "join the values of a list variable with a separator when given" in {
      // seen in the wild: clientlist.st '&CLIENTSFOLDERS;separator=":"&'
      (fill("""&list;separator=":"&""", "list" -> Seq("a", "b", "c")) === "a:b:c") and
      (fill("""&v;separator=":"&""", "v" -> "a") === "a")
    }
    "access a property of an object" in {
      fill("&parameter.escapedValue&", "parameter" -> TestParameter("p", "v")) === "v"
    }
    "tolerate whitespace inside expressions like the ST3 action parser" in {
      // seen in the wild: rpmPackageInstallationData.st (2011) starts its loop with '& RPM_PACKAGE_REDACTION, ...'
      (fill("& name &", "name" -> "World") === "World") and
      (fill("& parameter . escapedValue &", "parameter" -> TestParameter("p", "v")) === "v") and
      (fill("& l1, l2 :{ a, b |&a&:&b&;}&", "l1" -> Seq("x"), "l2" -> Seq("y")) === "x:y;")
    }
    "access a property of a map" in {
      val m = new java.util.HashMap[String, String]()
      m.put("escapedValue", "v")
      fill("&parameter.escapedValue&", "parameter" -> m) === "v"
    }
  }

  "escapes and comments" should {
    "unescape ampersand and keep other backslashes" in {
      fill("""cf-agent -f failsafe.cf \&\& cf-agent 2>\&1 a\b c\\d""") === """cf-agent -f failsafe.cf && cf-agent 2>&1 a\b c\d"""
    }
    "skip comments" in {
      fill("a&! some comment !&b") === "ab"
    }
    "swallow the newline after a comment starting its line, keep it otherwise" in {
      (fill("a\n&! full line comment !&\nb") === "a\nb") and
      (fill("a &! inline comment !&\nb") === "a \nb")
    }
  }

  "conditionals" should {
    "use the value of a boolean variable" in {
      fill("&if(X)&yes&endif&no", "X" -> true) === "yesno"
      fill("&if(X)&yes&endif&no", "X" -> false) === "no"
    }
    "support negation and else" in {
      fill("&if(!X)&no&else&yes&endif&", "X" -> true) === "yes"
      fill("&if(!X)&no&else&yes&endif&") === "no"
    }
    "be false on absent variable, true on any string" in {
      fill("&if(X)&yes&endif&", "X" -> "anything") === "yes"
      fill("&if(X)&yes&endif&") === ""
    }
    "test emptiness of list variables" in {
      fill("&if(X)&yes&endif&", "X" -> Seq("a")) === "yes"
      fill("&if(X)&yes&endif&", "X" -> Seq()) === ""
    }
    "swallow the newlines around the condition markers like ST3" in {
      fill("&if(X)&\nyes\n&else&\nno\n&endif&\nend", "X" -> true) === "yesend"
      fill("&if(X)&\nyes\n&else&\nno\n&endif&\nend", "X" -> false) === "noend"
    }
  }

  "iteration" should {
    "iterate in parallel up to the longest list, missing values empty" in {
      // same case as TemplateTest.arrayTest on ST3
      fill("&list1,list2:{ n,p |&n&:&p&}&", "list1" -> Seq("chi", "fou", "mi"), "list2" -> Seq("bar", "bazz")) ===
      "chi:barfou:bazzmi:"
    }
    "apply separator between iterations" in {
      fill("""&l:{x |<&x&>}; separator=", "&""", "l" -> Seq("a", "b")) === "<a>, <b>"
    }
    "bind the implicit iterator when no parameter is declared" in {
      fill("""&l: { [&it&] }&""", "l" -> Seq("a", "b")) === " [a]  [b] "
    }
    "provide 1-based &i& and 0-based &i0&" in {
      fill("""&l:{x |&i&/&i0&:&x& }&""", "l" -> Seq("a", "b")) === "1/0:a 2/1:b "
    }
    "iterate once on a scalar value" in {
      fill("""&l:{x |[&x&]}&""", "l" -> "a") === "[a]"
    }
    "not iterate on an absent variable" in {
      fill("""&l:{x |[&x&]}&""") === ""
    }
    "see outer variables from the loop body and restore builtins" in {
      fill("""&l:{x |&x&=&out&;}&""", "l" -> Seq("a", "b"), "out" -> "o") === "a=o;b=o;"
    }
    "keep balanced braces in the body as text, skipping the single space after the pipe" in {
      fill("""&l:{x | or => { "c_&x&" };}&""", "l" -> Seq("a")) === """or => { "c_a" };"""
    }
  }

  "auto-indent" should {
    "re-apply the line indentation to each line of a multi-line expansion" in {
      fill("  vars:\n      &V&\nend", "V" -> "a\nb") === "  vars:\n      a\n      b\nend"
    }
    "elide the whole line, indentation and newline included, for an empty expansion" in {
      fill("  vars:\n      &V&\nend") === "  vars:\nend"
    }
    "re-indent each line produced by an indented loop" in {
      fill("  vars:\n      &l:{x |\"&x&\",\n}&\nend", "l" -> Seq("a", "b")) === "  vars:\n      \"a\",\n      \"b\",\n\nend"
    }
    "not treat mid-line whitespace as indentation" in {
      fill("key: &V&", "V" -> "a\nb") === "key: a\nb"
    }
  }

  "STVariable fill" should {
    val parsed = AmpersandTemplate.parse("&if(SYS)&sys=&SYS&&endif&-&V&").toOption.get

    "treat an empty may-be-empty system variable as absent" in {
      val vars = List(
        STVariable("SYS", mayBeEmpty = true, values = ArraySeq(""), isSystem = true),
        STVariable("V", mayBeEmpty = false, values = ArraySeq("v"), isSystem = false)
      )
      AmpersandTemplate.fill(parsed, "test", vars, None) must beRight(("-v", "test"))
    }
    "error on a mandatory empty variable" in {
      val vars = List(STVariable("V", mayBeEmpty = false, values = ArraySeq.empty[Any], isSystem = false))
      AmpersandTemplate.fill(parsed, "test", vars, None) must beLeft
    }
    "substitute the multi-policy tag in content and file name with replaceId" in {
      val t = AmpersandTemplate.parse("id=&RudderUniqueID&").toOption.get
      AmpersandTemplate.fill(t, "technique_RudderUniqueID.cf", Nil, Some(("RudderUniqueID", "d1"))) must
      beRight(("id=d1", "technique_d1.cf"))
    }
  }

  "PolicyTemplateEngine" should {
    "parse valid names case-insensitively and reject unknown ones" in {
      (PolicyTemplateEngine.parse("string-template") must beRight(PolicyTemplateEngine.StringTemplate)) and
      (PolicyTemplateEngine.parse("Rudder-Fastparse") must beRight(PolicyTemplateEngine.Fastparse)) and
      (PolicyTemplateEngine.parse("jinja") must beLeft)
    }
  }
}
