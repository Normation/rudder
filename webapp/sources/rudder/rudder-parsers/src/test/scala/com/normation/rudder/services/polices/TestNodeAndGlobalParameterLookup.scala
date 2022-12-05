/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
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

package com.normation.rudder.services.policies

import cats.implicits._
import com.normation.errors._
import com.normation.rudder.services.nodes.EngineOption
import org.junit.runner.RunWith
import org.specs2.matcher.Expectable
import org.specs2.matcher.Matcher
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.util.matching.Regex

/**
 * Test how parametrized variables are replaced for
 * parametrization with ${rudder.param.XXX} and
 * ${rudder.node.YYYY}
 */

@RunWith(classOf[JUnitRunner])
class TestNodeAndGlobalParameterLookup extends Specification {
  import PropertyParser._
  import PropertyParserTokens._
  import fastparse._

  // in case of success, test for the result
  def test[T](p: P[_] => P[T], value: String, result: Any) = {
    fastparse.parse(value, p(_)) match {
      case Parsed.Success(x, index) => x === result
      case f: Parsed.Failure => ko(f.trace().longAggregateMsg)
    }
  }

  /**
   * Test that the parser correctly parse strings
   * to the expected AST
   */
  "Parsing values" should {

    "parse empty text" in {
      test(all(_), "", List(CharSeq("")))
    }

    "parse (multiline) plain text" in {
      val s = """some vars chars with \z \n plop foo"""
      test(noVariableStart(_), s, CharSeq(s))
    }

    "parse a rudder param variable with old syntax" in {
      test(variable(_), """${rudder.param.foo}""", Param("foo" :: Nil))
    }

    "parse a rudder param variable with spaces with old syntax" in {
      test(variable(_), """${rudder . param . foo}""", Param("foo" :: Nil))
    }

    "parse a rudder node variable" in {
      test(variable(_), """${rudder.node.foo.bar.baz}""", NodeAccessor(List("foo", "bar", "baz")))
    }

    "parse a rudder node variable with spaces" in {
      test(variable(_), """${rudder . node . foo . bar . baz}""", NodeAccessor(List("foo", "bar", "baz")))
    }

    "parse a rudder param variable with all parser with old syntax" in {
      test(all(_), """${rudder.param.foo}""", List(Param("foo" :: Nil)))
    }

    "parse a rudder param variable with new syntax" in {
      test(all(_), """${rudder.parameters[foo][bar]}""", List(Param("foo" :: "bar" :: Nil)))
    }

    "parse a rudder param variable with all parser with spaces" in {
      test(all(_), """${rudder . param . foo}""", List(Param("foo" :: Nil)))
    }

    "parse a non rudder param variable with all parser" in {
      test(all(_), """${something.cfengine}""", List(NonRudderVar("something.cfengine")))
    }

    "parse a non valid cfengine variable as a string" in {
      test(all(_), """${something . cfengine}""", List(CharSeq("${something . cfengine"), CharSeq("}")))
    }

    "parse a valid engine" in {
      val s   = """${data.test[foo]}"""
      val s2  = """${rudder-data.test[foo]}"""
      val res = RudderEngine("test", List("foo"), None)
      test(all(_), s, List(res)) and
      test(all(_), s2, List(res))
    }

    "parse a valid engine with methods" in {
      val s   = """${data.test[foo][bar]}"""
      val s2  = """${rudder-data.test[foo][bar]}"""
      val res = RudderEngine("test", List("foo", "bar"), None)
      test(all(_), s, List(res)) and
      test(all(_), s2, List(res))
    }

    "parse a valid engine with options" in {
      val s   = """${data.test[foo] | option1 = boo | option2 = baz}"""
      val s2  = """${rudder-data.test[foo] | option1 = boo | option2 = baz}"""
      val res = RudderEngine("test", List("foo"), Some(List(EngineOption("option1", "boo"), EngineOption("option2", "baz"))))
      test(all(_), s, List(res)) and
      test(all(_), s2, List(res))
    }

    "parse an engine with space" in {
      val s   = """${data . test [ foo ] [ bar]    |option1 =  tac |  option2   = toc  }"""
      val s2  = """${rudder-data . test [ foo ] [ bar]    |option1 =  tac |  option2   = toc  }"""
      val res =
        RudderEngine("test", List("foo", "bar"), Some(List(EngineOption("option1", "tac"), EngineOption("option2", "toc"))))
      test(all(_), s, List(res)) and
      test(all(_), s2, List(res))
    }

    "parse an engine with multiple method" in {
      val s   = """${data.test[foo][bar][baz]}"""
      val s2  = """${rudder-data.test[foo][bar][baz]}"""
      val res = RudderEngine("test", List("foo", "bar", "baz"), None)
      test(all(_), s, List(res)) and
      test(all(_), s2, List(res))
    }

    "parse engine with UTF-8" in {
      val s   = """${data.😈tëst😍[emo😄ji-parameter] | 1optionÃ¶ = emo😄jiOpt1 | 2optionÃ¼ = emo😄jiOpt2}"""
      val s2  = """${rudder-data.😈tëst😍[emo😄ji-parameter] | 1optionÃ¶ = emo😄jiOpt1 | 2optionÃ¼ = emo😄jiOpt2}"""
      val res = RudderEngine(
        "😈tëst😍",
        List("emo😄ji-parameter"),
        Some(
          List(
            EngineOption("1optionÃ¶", "emo😄jiOpt1"),
            EngineOption("2optionÃ¼", "emo😄jiOpt2")
          )
        )
      )
      test(all(_), s, List(res)) and
      test(all(_), s2, List(res))
    }

    "fails when an engine with empty method between" in {
      val s  = """${data.test[foo][][bar]("test")}"""
      val s2 = """${rudder-data.test[foo][][bar]("test")}"""
      (PropertyParser.parse(s) must beLeft) and
      (PropertyParser.parse(s2) must beLeft)
    }

    "fails when an engine with empty method at the end" in {
      val s  = """${data.test[foo][bar][]("test")}"""
      val s2 = """${rudder-data.test[foo][bar][]("test")}"""
      (PropertyParser.parse(s) must beLeft) and
      (PropertyParser.parse(s2) must beLeft)
    }

    "fails when an engine with empty method at the beginning" in {
      val s  = """${data.test[][foo][bar]("test")}"""
      val s2 = """${rudder-data.test[][foo][bar]("test")}"""
      (PropertyParser.parse(s) must beLeft) and
      (PropertyParser.parse(s2) must beLeft)
    }

    "parse blank test" in {
      test(all(_), "      ", List(CharSeq("      ")))
    }

    "parse text and variable and text" in {
      val s1 = "plj jmoji h imj "
      val s2 = " alkjf fm ^{i àié" // will be split at '$'
      val s3 = "${rudde ut ùt "
      test(
        all(_),
        s1 + "${rudder.node.policyserver.id}" + s2 + s3,
        List(CharSeq(s1), NodeAccessor(List("policyserver", "id")), CharSeq(s2), CharSeq(s3))
      )
    }

    "parse (multiline) text and variable and text" in {
      val s1 = "plj jmoji \n h \timj "
      val s2 = " alkjf \n\rfm ^{i àié"
      val s3 = "${rudde ut ùt "
      test(
        all(_),
        s1 + "${rudder.node.policyserver.id}" + s2 + s3,
        List(CharSeq(s1), NodeAccessor(List("policyserver", "id")), CharSeq(s2), CharSeq(s3))
      )
    }

    "parse a standard cfengine variable" in {
      val s = """${bla.foo}"""
      test(all(_), s, List(NonRudderVar("bla.foo")))
    }

    "accept rudder_parameters variable as a plain variable" in {
      val s = """${rudder_parameters.foo}"""
      test(all(_), s, List(NonRudderVar("rudder_parameters.foo")))
    }

    "accept rudderthing variable as a plain variable" in {
      val s = """${rudderthings.foo}"""
      test(all(_), s, List(NonRudderVar("rudderthings.foo")))
    }

    "accept node.things variable as a plain variable" in {
      val s = """${node.thing.foo}"""
      test(all(_), s, List(NonRudderVar("node.thing.foo")))
    }

    "accept nodethings variable as a plain variable" in {
      val s = """${nodething.foo}"""
      test(all(_), s, List(NonRudderVar("nodething.foo")))
    }

    "fails to find a rudder var on unknown rudder subpath" in {
      val s = """${rudder.foo.bar}"""
      PropertyParser.parse(s) must beLeft
    }

    "found a non rudder var on parse node properties with path=0" in {
      val s = """${node.properties}"""
      PropertyParser.parse(s) must beLeft
    }

    "found a non rudder var on parse node properties with path=0" in {
      val s = """${node.properties[]}"""
      PropertyParser.parse(s) must beLeft
    }

    "parse node properties with path=1" in {
      val s = """${node.properties[datacenter]}"""
      test(all(_), s, List(Property("datacenter" :: Nil, None)))
    }

    "parse node properties with path=1 with spaces" in {
      val s = """${node . properties [ datacenter ] }"""
      test(all(_), s, List(Property("datacenter" :: Nil, None)))
    }

    "parse node properties with path=2" in {
      val s = """${node.properties[datacenter][Europe]}"""
      test(all(_), s, List(Property("datacenter" :: "Europe" :: Nil, None)))
    }

    "parse node properties with UTF-8 name" in {
      val s = """${node.properties[emo😄ji]}"""
      test(all(_), s, List(Property("emo😄ji" :: Nil, None)))
    }

    "fails on invalid property chars" in {
      (PropertyParser.parse("""${node.properties[bad"bad]}""") must beLeft) and
      (PropertyParser.parse("""${node.properties[bad$bad]}""") must beLeft) and
      (PropertyParser.parse("""${node.properties[bad[bad]}""") must beLeft) and
      (PropertyParser.parse("""${node.properties[bad]bad]}""") must beLeft) and
      (PropertyParser.parse("""${node.properties[bad{bad]}""") must beLeft) and
      (PropertyParser.parse("""${node.properties[bad}bad]}""") must beLeft) and
      (PropertyParser.parse("""${node.properties[bad bad]}""") must beLeft) and
      (PropertyParser.parse("${node.properties[bad\nbad]}") must beLeft) and
      (PropertyParser.parse("${node.properties[bad\u0010bad]}") must beLeft)
    }

    "parse node properties with path=N>2" in {
      val s = """${node.properties[datacenter][Europe][France][Paris][3]}"""
      test(all(_), s, List(Property("datacenter" :: "Europe" :: "France" :: "Paris" :: "3" :: Nil, None)))
    }

    "parse node properties in the middle of a string" in {
      val s = """some text and ${node.properties[datacenter][Europe]}  and some more text"""
      test(
        all(_),
        s,
        List(CharSeq("some text and "), Property("datacenter" :: "Europe" :: Nil, None), CharSeq("  and some more text"))
      )
    }

    "parse node properties 'node' option" in {
      val s = """some text and ${node.properties[datacenter][Europe]|node}  and some more text"""
      test(
        all(_),
        s,
        List(
          CharSeq("some text and "),
          Property("datacenter" :: "Europe" :: Nil, Some(InterpreteOnNode)),
          CharSeq("  and some more text")
        )
      )
    }

    "parse node properties 'default:''' option" in {
      val s = """some text and ${node.properties[datacenter][Europe]|default= ""}  and some more text"""
      test(
        all(_),
        s,
        List(
          CharSeq("some text and "),
          Property("datacenter" :: "Europe" :: Nil, Some(DefaultValue(CharSeq("") :: Nil))),
          CharSeq("  and some more text")
        )
      )
    }

    "parse node properties 'default:string' option" in {
      val s = """some text and ${node.properties[datacenter][Europe]|default= "default value"}  and some more text"""
      test(
        all(_),
        s,
        List(
          CharSeq("some text and "),
          Property("datacenter" :: "Europe" :: Nil, Some(DefaultValue(CharSeq("default value") :: Nil))),
          CharSeq("  and some more text")
        )
      )
    }

    "parse node properties 'default:string' with unterminated quote lead to understandable message" in {
      val s = """some text and ${node.properties[datacenter]|default= "missing end quote}  and some more text"""
      val err: RudderError = {
        Unexpected(
          """Error when parsing value (without ''): 'some text and ${node.properties[datacenter]|default= "missing end quote}  and some more text'. Error message is: Expected (string | emptyString | variable):1:54, found "\"missing e""""
        )
      }
      PropertyParser.parse(s) must beLeft(
        beEqualTo(err)
      )
    }

    "parse node properties 'default:string with {}' option" in {
      val s = """some text and ${node.properties[datacenter][Europe]|default= "default {} value" }  and some more text"""
      test(
        all(_),
        s,
        List(
          CharSeq("some text and "),
          Property("datacenter" :: "Europe" :: Nil, Some(DefaultValue(CharSeq("default {} value") :: Nil))),
          CharSeq("  and some more text")
        )
      )
    }

    "parse node properties 'default:tq'' option" in {
      val s = """some text and ${node.properties[datacenter][Europe]|default= """ + "\"\"\"\"\"\"" + """}  and some more text"""
      test(
        all(_),
        s,
        List(
          CharSeq("some text and "),
          Property("datacenter" :: "Europe" :: Nil, Some(DefaultValue(CharSeq("") :: Nil))),
          CharSeq("  and some more text")
        )
      )
    }

    "parse node properties 'default:string' option" in {
      val s =
        """some text and ${node.properties[datacenter][Europe]|default= """ + "\"\"\"" + "default {} value" + "\"\"\"" + """}  and some more text"""
      test(
        all(_),
        s,
        List(
          CharSeq("some text and "),
          Property("datacenter" :: "Europe" :: Nil, Some(DefaultValue(CharSeq("default {} value") :: Nil))),
          CharSeq("  and some more text")
        )
      )
    }

    "parse node properties 'default:string with {}' option" in {
      val s =
        """some text and ${node.properties[datacenter][Europe]|default= """ + "\"\"\"" + "default {} value" + "\"\"\"" + """ }  and some more text"""
      test(
        all(_),
        s,
        List(
          CharSeq("some text and "),
          Property("datacenter" :: "Europe" :: Nil, Some(DefaultValue(CharSeq("default {} value") :: Nil))),
          CharSeq("  and some more text")
        )
      )
    }

    "parse node properties 'default:param' option" in {
      val s = """some text and ${node.properties[datacenter][Europe]|default=${rudder.param.foo}}  and some more text"""
      test(
        all(_),
        s,
        List(
          CharSeq("some text and "),
          Property("datacenter" :: "Europe" :: Nil, Some(DefaultValue(Param("foo" :: Nil) :: Nil))),
          CharSeq("  and some more text")
        )
      )
    }

    "parse node properties 'default:node.hostname' option" in {
      val s = """some text and ${node.properties[datacenter][Europe]|default=${rudder.node.hostname}}  and some more text"""
      test(
        all(_),
        s,
        List(
          CharSeq("some text and "),
          Property("datacenter" :: "Europe" :: Nil, Some(DefaultValue(NodeAccessor(List("hostname")) :: Nil))),
          CharSeq("  and some more text")
        )
      )
    }

    "parse node properties 'default:node.properties' option" in {
      val s =
        """some text and ${node.properties[datacenter][Europe]|default=${node.properties[defaultDatacenter]}}  and some more text"""
      test(
        all(_),
        s,
        List(
          CharSeq("some text and "),
          Property("datacenter" :: "Europe" :: Nil, Some(DefaultValue(Property("defaultDatacenter" :: Nil, None) :: Nil))),
          CharSeq("  and some more text")
        )
      )
    }
    "parse node properties 'default:node.properties with quote" in {
      val s = """some text and ${node.properties[datacenter][Europe]|default="${node.properties[defaultDatacenter]}"}"""
      test(
        all(_),
        s,
        List(
          CharSeq("some text and "),
          Property("datacenter" :: "Europe" :: Nil, Some(DefaultValue(Property("defaultDatacenter" :: Nil, None) :: Nil)))
        )
      )
    }
    "parse node properties 'default:node.properties without quote" in {
      val s = """some text and ${node.properties[datacenter][Europe]|default=${node.properties[defaultDatacenter]}}"""
      test(
        all(_),
        s,
        List(
          CharSeq("some text and "),
          Property("datacenter" :: "Europe" :: Nil, Some(DefaultValue(Property("defaultDatacenter" :: Nil, None) :: Nil)))
        )
      )
    }
    "parse node properties 'default:node.properties + string' option" in {
      val s =
        """some text and ${node.properties[datacenter][Europe]|default=" default ${node.properties[defaultDatacenter]}"}  and some more text"""
      test(
        all(_),
        s,
        List(
          CharSeq("some text and "),
          Property(
            "datacenter" :: "Europe" :: Nil,
            Some(DefaultValue(CharSeq(" default ") :: Property("defaultDatacenter" :: Nil, None) :: Nil))
          ),
          CharSeq("  and some more text")
        )
      )
    }
    "parse node properties 'default:node.properties + string' option" in {
      val s = """some text and ${node.properties[datacenter][Europe]|default=" default ${nod"}  and some more text"""
      test(
        all(_),
        s,
        List(
          CharSeq("some text and "),
          Property("datacenter" :: "Europe" :: Nil, Some(DefaultValue(CharSeq(" default ") :: CharSeq("${nod") :: Nil))),
          CharSeq("  and some more text")
        )
      )
    }
    "parse node properties 'default:node.properties+default' option" in {
      val s =
        """some text and ${node.properties[datacenter][Europe]|default=${node.properties[defaultDatacenter]|default="some default value"}}  and some more text"""
      test(
        all(_),
        s,
        List(
          CharSeq("some text and "),
          Property(
            "datacenter" :: "Europe" :: Nil,
            Some(
              DefaultValue(Property("defaultDatacenter" :: Nil, Some(DefaultValue(CharSeq("some default value") :: Nil))) :: Nil)
            )
          ),
          CharSeq("  and some more text")
        )
      )
    }
    "parse node properties 'default:node.properties+variable' option" in {
      val s =
        """some text and ${node.properties[datacenter][Europe]|default=${node.properties[defaultDatacenter]|default="Default vars : ${vars.default} value"}}  and some more text"""
      test(
        all(_),
        s,
        List(
          CharSeq("some text and "),
          Property(
            "datacenter" :: "Europe" :: Nil,
            Some(
              DefaultValue(
                Property(
                  "defaultDatacenter" :: Nil,
                  Some(
                    DefaultValue(CharSeq("Default vars : ") :: NonRudderVar("vars.default") :: CharSeq(" value") :: Nil)
                  )
                ) :: Nil
              )
            )
          ),
          CharSeq("  and some more text")
        )
      )
    }
  }

  "Parsing values with spaces" should {

    "parse n.either.runNowode properties 'default:node.hostname' option" in {
      val s = """some text and ${node . properties [
        datacenter] [ Europe] | default= ${rudder . node
        . hostname }  }  and some more text"""

      test(
        all(_),
        s,
        List(
          CharSeq("some text and "),
          Property("datacenter" :: "Europe" :: Nil, Some(DefaultValue(NodeAccessor(List("hostname")) :: Nil))),
          CharSeq("  and some more text")
        )
      )
    }
  }
}
