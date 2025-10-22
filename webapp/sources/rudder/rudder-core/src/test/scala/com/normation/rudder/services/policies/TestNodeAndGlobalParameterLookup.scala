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

import com.normation.cfclerk.domain.InputVariable
import com.normation.cfclerk.domain.InputVariableSpec
import com.normation.cfclerk.domain.Variable
import com.normation.errors.*
import com.normation.rudder.domain.properties.GenericProperty
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.services.nodes.EngineOption
import com.normation.rudder.services.nodes.PropertyEngineServiceImpl
import com.normation.rudder.services.nodes.RudderPropertyEngine
import com.normation.zio.*
import com.softwaremill.quicklens.*
import com.typesafe.config.ConfigValue
import net.liftweb.common.Box
import net.liftweb.common.Empty
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.json.*
import net.liftweb.json.JsonAST.JString
import net.liftweb.json.JsonAST.JValue
import org.junit.runner.RunWith
import org.specs2.matcher.Expectable
import org.specs2.matcher.Matcher
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.util.matching.Regex
import zio.*
import zio.syntax.*

/**
 * Test how parametrized variables are replaced for
 * parametrization with ${rudder.param.XXX} and
 * ${rudder.node.YYYY}
 */

@RunWith(classOf[JUnitRunner])
class TestNodeAndGlobalParameterLookup extends Specification {

  class FakeEncryptedPasswordEngine extends RudderPropertyEngine {
    override def name: String = "fakeEncryptorEngineTesting"

    override def process(namespace: List[String], opt: Option[List[EngineOption]]): IOResult[String] =
      "encrypted-string-test".succeed

  }

  // matcher for failure
  def beFailure[T](r: Regex): Matcher[Box[T]] = new Matcher[Box[T]] {
    def apply[S <: Box[T]](v: Expectable[S]) = {

      val res = v.value match {
        case Full(x)          => false
        case Empty            => false
        case Failure(m, _, _) => r.pattern.matcher(m).matches()
      }

      result(
        res,
        "ok",
        "Didn't get failure matching regex " + r.toString,
        v,
        Failure(r.toString()).messageChain,
        v.value.toString
      )
    }
  }

  def getError[A](e: PureResult[A]): String = e.swap.getOrElse(throw new RuntimeException("ERROR")).fullMsg

  import NodeConfigData.*
  // null is for RuleValService, only used in
  // rule lookup, node tested here.
  val propertyEngineService = new PropertyEngineServiceImpl(
    List(
      new FakeEncryptedPasswordEngine
    )
  )

  val compiler      = new InterpolatedValueCompilerImpl(propertyEngineService)
  val lookupService = new RuleValServiceImpl(compiler)
  val data          = new TestNodeConfiguration()
  val buildContext: PromiseGeneration_BuildNodeContext = new PromiseGeneration_BuildNodeContext {
    override def interpolatedValueCompiler: InterpolatedValueCompiler = compiler
    override def systemVarService:          SystemVariableService     = data.systemVariableService
  }

  val context: ParamInterpolationContext = ParamInterpolationContext(
    parameters = Map(),
    globalPolicyMode = defaultModesConfig.globalPolicyMode,
    nodeInfo = fact1,
    policyServerInfo = factRoot
  )

  def toNodeContext(c: ParamInterpolationContext, params: Map[String, ConfigValue]): InterpolationContext = InterpolationContext(
    parameters = params,
    nodeInfo = c.nodeInfo,
    globalPolicyMode = c.globalPolicyMode,
    policyServerInfo = c.policyServerInfo, // environment variable for that server

    nodeContext = Map()
  )

  def lookup(
      variables: Seq[Variable],
      pContext:  ParamInterpolationContext
  )(test: Seq[Seq[String]] => org.specs2.execute.Result): org.specs2.execute.Result = {
    lookupParam(variables, pContext).either.runNow match {
      case Left(err)  => failure("Error in test: " + err.fullMsg)
      case Right(res) => test(res.values.map(_.values).toSeq)
    }
  }

  def lookupParam(
      variables:   Seq[Variable],
      lookupParam: ParamInterpolationContext
  ): ZIO[Any, RudderError, Map[ComponentId, Variable]] = {
    (for {
      params <- ZIO.foreach(lookupParam.parameters.toList) { case (k, c) => c(lookupParam).map((k, _)) }
      p      <- ZIO.foreach(params.toList) { case (k, value) => GenericProperty.parseValue(value).map(v => (k, v)).toIO }
      res    <- lookupService.lookupNodeParameterization(variables.map(v => (ComponentId(v.spec.name, Nil, v.spec.id), v)).toMap)(
                  toNodeContext(lookupParam, p.toMap)
                )
    } yield res)
  }

  def jparse(s: String): JValue = try { parse(s) }
  catch { case ex: Exception => JString(s) }

  // two variables
  val var1:              InputVariable = InputVariableSpec("var1", "", None, id = None).toVariable(Seq("== ${rudder.param.foo} =="))
  val var1_double:       InputVariable =
    InputVariableSpec("var1_double", "", None, id = None).toVariable(Seq("== ${rudder.param.foo}${rudder.param.bar} =="))
  val var1_double_space: InputVariable = InputVariableSpec("var1_double_space", "", None, id = None).toVariable(
    Seq("== ${rudder.param.foo} contains ${rudder.param.bar} ==")
  )

  val pathCaseInsensitive: InputVariable =
    InputVariableSpec("pathCaseInsensitive", "", None, id = None).toVariable(Seq("== ${RudDer.paRam.foo} =="))

  val paramNameCaseSensitive: InputVariable =
    InputVariableSpec("paramNameCaseSensitive", "", None, id = None).toVariable(Seq("== ${rudder.param.Foo} =="))

  val recurVariable: InputVariable =
    InputVariableSpec("recurParam", "", None, id = None).toVariable(Seq("== ${rudder.param.recurToFoo} =="))

  val dangerVariable: InputVariable = InputVariableSpec("danger", "", None, id = None).toVariable(Seq("${rudder.param.danger}"))

  val multilineInputVariable:    InputVariable =
    InputVariableSpec("multiInput", "", None, id = None).toVariable(Seq("=\r= \n${rudder.param.foo} =\n="))
  val multilineNodePropVariable: InputVariable =
    InputVariableSpec("multiNodeProp", "", None, id = None).toVariable(Seq("=\r= \n${node.properties[datacenter][Europe]} =\n="))

  val var2: InputVariable = InputVariableSpec("var1", "", None, multivalued = true, id = None).toVariable(
    Seq(
      "a${rudder.node.id})",
      "=${rudder.node.hostname}/",
      ".${rudder.node.admin}]",
      "$${rudder.node.policyserver.id}|",
      "{${rudder.node.policyserver.hostname}&",
      "!${rudder.node.policyserver.admin}^"
    )
  )

  val badEmptyRudder: InputVariable = InputVariableSpec("empty", "", None, id = None).toVariable(Seq("== ${rudder.} =="))
  val badUnclosed:    InputVariable = InputVariableSpec("empty", "", None, id = None).toVariable(Seq("== ${rudder.param.foo =="))
  val badUnknown:     InputVariable = InputVariableSpec("empty", "", None, id = None).toVariable(Seq("== ${rudder.foo} =="))

  val fooParam:   ParameterForConfiguration = ParameterForConfiguration("foo", "fooValue")
  val barParam:   ParameterForConfiguration = ParameterForConfiguration("bar", "barValue")
  val recurParam: ParameterForConfiguration = ParameterForConfiguration("recurToFoo", """${rudder.param.foo}""")

  val badChars = """$¹ ${plop[]} (foo) \$ @ %plop & \\ | $[xas]^"""
  val dangerousChars: ParameterForConfiguration = ParameterForConfiguration("danger", badChars)

  def p(params: ParameterForConfiguration*): Map[String, ParamInterpolationContext => IOResult[String]] = {
    import cats.implicits.*
    params.toList.traverse { param =>
      for {
        p <- compiler
               .compileParam(param.value)
               .chainError(s"Error when looking for interpolation variable in global parameter '${param.name}'")
      } yield {
        (param.name, p)
      }
    }.map(seq => Map(seq*)).chainError("Error when parsing parameters for interpolated variables") match {
      case Left(err)  => throw new RuntimeException(err.fullMsg)
      case Right(res) => res
    }
  }

  import com.normation.rudder.services.policies.PropertyParser.*
  import com.normation.rudder.services.policies.PropertyParserTokens.*
  import fastparse.*

  // in case of success, test for the result
  def test[T](p: P[?] => P[T], value: String, result: Any): MatchResult[Any] = {
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
      test({ case given P[?] => all }, "", List(CharSeq("")))
    }

    "parse (multiline) plain text" in {
      val s = """some vars chars with \z \n plop foo"""
      test({ case given P[?] => noVariableStart }, s, CharSeq(s))
    }

    "parse a rudder param variable with old syntax" in {
      test({ case given P[?] => variable }, """${rudder.param.foo}""", Param("foo" :: Nil))
    }

    "parse a rudder param variable with spaces with old syntax" in {
      test({ case given P[?] => variable }, """${rudder . param . foo}""", Param("foo" :: Nil))
    }

    "parse a rudder node variable" in {
      test({ case given P[?] => variable }, """${rudder.node.foo.bar.baz}""", NodeAccessor(List("foo", "bar", "baz")))
    }

    "parse a rudder node variable with spaces" in {
      test({ case given P[?] => variable }, """${rudder . node . foo . bar . baz}""", NodeAccessor(List("foo", "bar", "baz")))
    }

    "parse a rudder param variable with all parser with old syntax" in {
      test({ case given P[?] => all }, """${rudder.param.foo}""", List(Param("foo" :: Nil)))
    }

    "parse a rudder param variable with new syntax" in {
      test({ case given P[?] => all }, """${rudder.parameters[foo][bar]}""", List(Param("foo" :: "bar" :: Nil)))
    }

    "parse a rudder param variable with all parser with spaces" in {
      test({ case given P[?] => all }, """${rudder . param . foo}""", List(Param("foo" :: Nil)))
    }

    "parse a non rudder param variable with all parser" in {
      test({ case given P[?] => all }, """${something.cfengine}""", List(NonRudderVar("something.cfengine")))
    }

    "parse a non rudder param variable with accessor" in {
      test({ case given P[?] => all }, """${something.cfengine[foo]}""", List(NonRudderVar("something.cfengine", List("foo"))))
    }

    "parse a non rudder param variable with space in accessor" in {
      test(
        { case given P[?] => all },
        """${something.cfengine[foo bar]}""",
        List(NonRudderVar("something.cfengine", List("foo bar")))
      )
    }

    "parse a non rudder param variable amid other chars" in {
      test(
        { case given P[?] => all },
        """foo ${something.cfengine[foo]} bar""",
        List(CharSeq("foo "), NonRudderVar("something.cfengine", List("foo")), CharSeq(" bar"))
      )
    }

    // this case is considered unsafe because nested interpolation is not yet supported : ${...${...}}. When it is, it should be
    "parse a non rudder param variable with interpolation accessor" in {
      test(
        { case given P[?] => all },
        """${something.cfengine[foo][${node.properties[bar]}]}""",
        List(UnsafeRudderVar("something.cfengine[foo][${node.properties[bar]"), CharSeq("]}"))
      )
    }

    "parse a non valid cfengine variable as unsafe" in {
      test({ case given P[?] => all }, """${something . cfengine}""", List(UnsafeRudderVar("something . cfengine")))
    }

    "parse a valid engine" in {
      val s   = """${data.test[foo]}"""
      val s2  = """${rudder-data.test[foo]}"""
      val res = RudderEngine("test", List("foo"), None)
      test({ case given P[?] => all }, s, List(res)) and
      test({ case given P[?] => all }, s2, List(res))
    }

    "parse a valid engine with methods" in {
      val s   = """${data.test[foo][bar]}"""
      val s2  = """${rudder-data.test[foo][bar]}"""
      val res = RudderEngine("test", List("foo", "bar"), None)
      test({ case given P[?] => all }, s, List(res)) and
      test({ case given P[?] => all }, s2, List(res))
    }

    "parse a valid engine with options" in {
      val s   = """${data.test[foo] | option1 = boo | option2 = baz}"""
      val s2  = """${rudder-data.test[foo] | option1 = boo | option2 = baz}"""
      val res = RudderEngine("test", List("foo"), Some(List(EngineOption("option1", "boo"), EngineOption("option2", "baz"))))
      test({ case given P[?] => all }, s, List(res)) and
      test({ case given P[?] => all }, s2, List(res))
    }

    "parse an engine with space" in {
      val s   = """${data . test [ foo ] [ bar]    |option1 =  tac |  option2   = toc  }"""
      val s2  = """${rudder-data . test [ foo ] [ bar]    |option1 =  tac |  option2   = toc  }"""
      val res =
        RudderEngine("test", List("foo", "bar"), Some(List(EngineOption("option1", "tac"), EngineOption("option2", "toc"))))
      test({ case given P[?] => all }, s, List(res)) and
      test({ case given P[?] => all }, s2, List(res))
    }

    "parse an engine with multiple method" in {
      val s   = """${data.test[foo][bar][baz]}"""
      val s2  = """${rudder-data.test[foo][bar][baz]}"""
      val res = RudderEngine("test", List("foo", "bar", "baz"), None)
      test({ case given P[?] => all }, s, List(res)) and
      test({ case given P[?] => all }, s2, List(res))
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
      test({ case given P[?] => all }, s, List(res)) and
      test({ case given P[?] => all }, s2, List(res))
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
      test({ case given P[?] => all }, "      ", List(CharSeq("      ")))
    }

    "parse text and variable and text" in {
      val s1 = "plj jmoji h imj "
      val s2 = " alkjf fm ^{i àié$rudde ut ùt ending"
      test(
        { case given P[?] => all },
        s1 + "${rudder.node.policyserver.id}" + s2,
        List(CharSeq(s1), NodeAccessor(List("policyserver", "id")), CharSeq(s2))
      )
    }

    "parse (multiline) text and variable and text" in {
      val s1 = "plj jmoji \n h \timj "
      val s2 = " alkjf \n\rfm ^{i àié$rudde ut ùt "
      test(
        { case given P[?] => all },
        s1 + "${rudder.node.policyserver.id}" + s2,
        List(CharSeq(s1), NodeAccessor(List("policyserver", "id")), CharSeq(s2))
      )
    }

    "parse a standard cfengine variable" in {
      val s = """${bla.foo}"""
      test({ case given P[?] => all }, s, List(NonRudderVar("bla.foo")))
    }

    "accept rudder_parameters variable as a plain variable" in {
      val s = """${rudder_parameters.foo}"""
      test({ case given P[?] => all }, s, List(NonRudderVar("rudder_parameters.foo")))
    }

    "accept rudderthing variable as a plain variable" in {
      val s = """${rudderthings.foo}"""
      test({ case given P[?] => all }, s, List(NonRudderVar("rudderthings.foo")))
    }

    "accept node.things variable as a plain variable" in {
      val s = """${node.thing.foo}"""
      test({ case given P[?] => all }, s, List(NonRudderVar("node.thing.foo")))
    }

    "accept nodethings variable as a plain variable" in {
      val s = """${nodething.foo}"""
      test({ case given P[?] => all }, s, List(NonRudderVar("nodething.foo")))
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
      test({ case given P[?] => all }, s, List(Property("datacenter" :: Nil, None)))
    }

    "parse node properties with path=1 with spaces" in {
      val s = """${node . properties [ datacenter ] }"""
      test({ case given P[?] => all }, s, List(Property("datacenter" :: Nil, None)))
    }

    "parse node properties with path=2" in {
      val s = """${node.properties[datacenter][Europe]}"""
      test({ case given P[?] => all }, s, List(Property("datacenter" :: "Europe" :: Nil, None)))
    }

    "parse node properties with UTF-8 name" in {
      val s = """${node.properties[emo😄ji]}"""
      test({ case given P[?] => all }, s, List(Property("emo😄ji" :: Nil, None)))
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
      test({ case given P[?] => all }, s, List(Property("datacenter" :: "Europe" :: "France" :: "Paris" :: "3" :: Nil, None)))
    }

    "parse node properties in the middle of a string" in {
      val s = """some text and ${node.properties[datacenter][Europe]}  and some more text"""
      test(
        { case given P[?] => all },
        s,
        List(CharSeq("some text and "), Property("datacenter" :: "Europe" :: Nil, None), CharSeq("  and some more text"))
      )
    }

    "parse node properties 'node' option" in {
      val s = """some text and ${node.properties[datacenter][Europe]|node}  and some more text"""
      test(
        { case given P[?] => all },
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
        { case given P[?] => all },
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
        { case given P[?] => all },
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
        { case given P[?] => all },
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
        { case given P[?] => all },
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
        { case given P[?] => all },
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
        { case given P[?] => all },
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
        { case given P[?] => all },
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
        { case given P[?] => all },
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
        { case given P[?] => all },
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
        { case given P[?] => all },
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
        { case given P[?] => all },
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
        { case given P[?] => all },
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
        { case given P[?] => all },
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
        { case given P[?] => all },
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
        { case given P[?] => all },
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
        { case given P[?] => all },
        s,
        List(
          CharSeq("some text and "),
          Property("datacenter" :: "Europe" :: Nil, Some(DefaultValue(NodeAccessor(List("hostname")) :: Nil))),
          CharSeq("  and some more text")
        )
      )
    }
  }
  def compileAndGet(s: String): ParamInterpolationContext => IOResult[String] = compiler.compileParam(s) match {
    case Left(err) => throw new RuntimeException(s"compileAndGet(${s}): ERROR: ${err.fullMsg}")
    case Right(v)  => v
  }

  /**
   * Test that the interpretation of an AST is
   * correc.either.runNowtly done (with forged interpretation contexts)
   */
  "Interpretation of a parsed interpolated string" should {

    "know for the 6 node & policy server param" in {

      // build a triplet: accessor, interpolation function, expected
      def comp(accessor: String, expected: String) = (
        accessor,
        compileAndGet(s"$${rudder.node.${accessor}}"),
        expected
      )

      // map of server.param -> AST
      val accessors = List(
        comp("id", context.nodeInfo.id.value),
        comp("hostname", context.nodeInfo.fqdn),
        comp("admin", context.nodeInfo.rudderAgent.user),
        comp("policyserver.id", context.policyServerInfo.id.value),
        comp("policyserver.hostname", context.policyServerInfo.fqdn),
        comp("policyserver.admin", context.policyServerInfo.rudderAgent.user)
      )

      accessors must contain((x: (String, ParamInterpolationContext => IOResult[String], String)) => {
        (x._2(context).either.runNow match {
          case Right(result) => result must beEqualTo(x._3)
          case Left(err)     =>
            ko(s"Error when evaluating context for accessor ${x._1} with expected result '${x._3}': " + err.fullMsg)
        })
      }).forall
    }

    "raise an error for an unknow accessor" in {
      val badAccessor = "rudder.node.foo"
      compiler.compileParam("${" + badAccessor + "}") match {
        case Left(err) => ko("Error when parsing interpolated value: " + err.fullMsg)
        case Right(i)  =>
          i(context).either.runNow match {
            case Right(res) => ko(s"When interpreted, an unkown accessor '${badAccessor}' should yield an error")
            case Left(_)    => ok
          }
      }
    }

    "correctly interpret simple param with old syntax" in {
      val res = "p1 replaced"
      val i   = compileAndGet("${rudder.param.p1}")
      val c   = context.copy(parameters = {
        Map(
          ("p1", (i: ParamInterpolationContext) => res.succeed)
        )
      })
      i(c).either.runNow must beEqualTo(Right(res))
    }

    "correctly interpret simple param with new syntax on string" in {
      val res = "p1 replaced"
      val i   = compileAndGet("${rudder.parameters[p1]}")
      val c   = context.copy(parameters = {
        Map(
          ("p1", (i: ParamInterpolationContext) => res.succeed)
        )
      })
      i(c).either.runNow must beEqualTo(Right(res))
    }

    "correctly interpret simple param with new syntax on json" in {
      val res  = "p1 replaced"
      val json = s"""{"p2": "${res}"}"""
      val i    = compileAndGet("${rudder.parameters[p1][p2]}")
      val c    = context.copy(parameters = {
        Map(
          ("p1", (i: ParamInterpolationContext) => json.succeed)
        )
      })
      i(c).either.runNow must beEqualTo(Right(res))
    }

    "fails on missing param in context" in {
      val i = compileAndGet("${rudder.param.p1}")
      i(context).either.runNow match {
        case Right(_) => ko("The parameter should not have been found")
        case Left(_)  => ok
      }
    }

    "correcly replace parameter with interpolated values" in {
      val res     = "p1 replaced with p2 value"
      val i       = compileAndGet("${rudder.param.p1}")
      val p1value = compileAndGet("${rudder.param.p2}")
      val c       = context.copy(parameters = {
        Map(
          ("p1", p1value),
          ("p2", (i: ParamInterpolationContext) => res.succeed)
        )
      })
      i(c).either.runNow must beEqualTo(Right(res))
    }

    "correctly replace maxDepth-1 parameter with interpolated values" in {
      val res          = "p1 replaced with p2 value"
      val i            = compileAndGet("${rudder.param.p1}")
      val p1value      = compileAndGet("${rudder.param.p2}")
      val p2value      = compileAndGet("${rudder.param.p3}")
      val p3value      = compileAndGet("${rudder.param.p4}")
      val analyseParam = new AnalyseParamInterpolation(propertyEngineService)
      val c            = context.copy(parameters = {
        Map(
          ("p1", p1value),
          ("p2", p2value),
          ("p3", p3value),
          ("p4", (i: ParamInterpolationContext) => res.succeed)
        )
      })

      (analyseParam.maxEvaluationDepth == 5) and
      (i(c).either.runNow must beEqualTo(Right(res)))
    }

    "fails to replace maxDepth parameter with interpolated values" in {
      val res          = "p1 replaced with p2 value"
      val i            = compileAndGet("${rudder.param.p1}")
      val p1value      = compileAndGet("${rudder.param.p2}")
      val p2value      = compileAndGet("${rudder.param.p3}")
      val p3value      = compileAndGet("${rudder.param.p4}")
      val analyseParam = new AnalyseParamInterpolation(propertyEngineService)
      val c            = context.copy(parameters = {
        Map(
          ("p1", p1value),
          ("p2", p2value),
          ("p3", p3value),
          ("p4", p3value),
          ("p5", (i: ParamInterpolationContext) => res.succeed)
        )
      })

      (analyseParam.maxEvaluationDepth == 5) and
      (i(c).either.runNow match {
        case Right(_) => ko("Was expecting an error due to too deep evaluation")
        case Left(_)  => ok
      })
    }

    "fails to replace recurring parameter value" in {
      val i = compileAndGet("${rudder.param.p1}")
      val c = context.copy(parameters = {
        Map(
          ("p1", i)
        )
      })

      i(c).either.runNow match {
        case Right(_) => ko("Was expecting an error due to too deep evaluation")
        case Left(_)  => ok
      }
    }

    // utility class to help run the IOResult
    def runParseJValue(value: JValue, context: InterpolationContext): JValue = {
      buildContext.parseJValue(value, context).either.runNow match {
        case Left(err) => throw new RuntimeException(s"Error when interpolating '${value}': ${err.fullMsg}")
        case Right(v)  => v
      }
    }

    "interpolate engine in JSON" in {
      val before  =
        """{"login":"admin", "password":"${data.fakeEncryptorEngineTesting[password_test] | option1 = foo | option2 = bar}"}"""
      val before2 =
        """{"login":"admin", "password":"${rudder-data.fakeEncryptorEngineTesting[password_test] | option1 = foo | option2 = bar}"}"""
      val after   = """{"login":"admin", "password":"encrypted-string-test"}"""
      (runParseJValue(JsonParser.parse(before), toNodeContext(context, Map())) must beEqualTo(JsonParser.parse(after))) and
      (runParseJValue(JsonParser.parse(before2), toNodeContext(context, Map())) must beEqualTo(JsonParser.parse(after)))
    }

    "interpolate unknown engine in JSON must raise en error" in {
      val before  = {
        """{
          |  "login" : "admin",
          |  "password" : {
          |    "value" : "${data.UNKNOWN[test]}"
          |  }
          |}
          |""".stripMargin
      }
      val before2 = {
        """{
          |  "login" : "admin",
          |  "password" : {
          |    "value" : "${rudder-data.UNKNOWN[test]}"
          |  }
          |}
          |""".stripMargin
      }
      (buildContext.parseJValue(JsonParser.parse(before), toNodeContext(context, Map())).either.runNow must beLeft) and
      (buildContext.parseJValue(JsonParser.parse(before2), toNodeContext(context, Map())).either.runNow must beLeft)
    }

    "interpolate engine in nested JSON object" in {
      val before  = {
        """{
          |  "login" : "admin",
          |  "password" : {
          |    "value" : "${data.fakeEncryptorEngineTesting[password]}"
          |  }
          |}
          |""".stripMargin
      }
      val before2 = {
        """{
          |  "login" : "admin",
          |  "password" : {
          |    "value" : "${rudder-data.fakeEncryptorEngineTesting[password]}"
          |  }
          |}
          |""".stripMargin
      }
      val after   = {
        """{
          |  "login" : "admin",
          |  "password" : {
          |    "value" : "encrypted-string-test"
          |  }
          |}
          |""".stripMargin
      }

      (runParseJValue(JsonParser.parse(before), toNodeContext(context, Map())) must beEqualTo(JsonParser.parse(after))) and
      (runParseJValue(JsonParser.parse(before2), toNodeContext(context, Map())) must beEqualTo(JsonParser.parse(after)))
    }

    "interpolate engine in JSON array" in {
      val before  = {
        """{
          |  "login" : "admin",
          |  "data" : ["foo", "${data.fakeEncryptorEngineTesting[password]}", "bar"]
          |}
          |""".stripMargin
      }
      val before2 = {
        """{
          |  "login" : "admin",
          |  "data" : ["foo", "${rudder-data.fakeEncryptorEngineTesting[password]}", "bar"]
          |}
          |""".stripMargin
      }
      val after   = {
        """{
          |  "login" : "admin",
          |  "data" : ["foo", "encrypted-string-test", "bar"]
          |}
          |""".stripMargin
      }
      (runParseJValue(JsonParser.parse(before), toNodeContext(context, Map())) must beEqualTo(JsonParser.parse(after))) and
      (runParseJValue(JsonParser.parse(before2), toNodeContext(context, Map())) must beEqualTo(JsonParser.parse(after)))
    }

    "interpolate engine in nested JSON array" in {
      val before  = {
        """{
          |  "login" : "admin",
          |  "data" : [
          |      {
          |        "foo" : "bar"
          |      },
          |      "xzy",
          |      {
          |        "shouldBeInterpolated" : "${data.fakeEncryptorEngineTesting[password]}"
          |      }
          |  ]
          |}
          |""".stripMargin
      }
      val before2 = {
        """{
          |  "login" : "admin",
          |  "data" : [
          |      {
          |        "foo" : "bar"
          |      },
          |      "xzy",
          |      {
          |        "shouldBeInterpolated" : "${rudder-data.fakeEncryptorEngineTesting[password]}"
          |      }
          |  ]
          |}
          |""".stripMargin
      }
      val after   = {
        """{
          |  "login" : "admin",
          |  "data" : [
          |      {
          |        "foo" : "bar"
          |      },
          |      "xzy",
          |      {
          |        "shouldBeInterpolated" : "encrypted-string-test"
          |      }
          |  ]
          |}
          |""".stripMargin
      }
      (runParseJValue(JsonParser.parse(before), toNodeContext(context, Map())) must beEqualTo(JsonParser.parse(after))) and
      (runParseJValue(JsonParser.parse(before2), toNodeContext(context, Map())) must beEqualTo(JsonParser.parse(after)))
    }

    "interpolate engine multiple time" in {
      val before  = {
        """{
          |  "login" : "${data.fakeEncryptorEngineTesting[password]}",
          |  "data" : [
          |      {
          |        "foo" : "${data.fakeEncryptorEngineTesting[password]}"
          |      },
          |      "${data.fakeEncryptorEngineTesting[password]}",
          |      "${data.fakeEncryptorEngineTesting[password]}",
          |      {
          |        "shouldBeInterpolated" : "${data.fakeEncryptorEngineTesting[password]}"
          |      }
          |  ]
          |  "moreData" : {
          |    "nestedStruct" : {
          |       "array" : [{"value":"${data.fakeEncryptorEngineTesting[password]}"}]
          |    }
          |  }
          |
          |}
          |""".stripMargin
      }
      val before2 = {
        """{
          |  "login" : "${rudder-data.fakeEncryptorEngineTesting[password]}",
          |  "data" : [
          |      {
          |        "foo" : "${rudder-data.fakeEncryptorEngineTesting[password]}"
          |      },
          |      "${rudder-data.fakeEncryptorEngineTesting[password]}",
          |      "${rudder-data.fakeEncryptorEngineTesting[password]}",
          |      {
          |        "shouldBeInterpolated" : "${rudder-data.fakeEncryptorEngineTesting[password]}"
          |      }
          |  ]
          |  "moreData" : {
          |    "nestedStruct" : {
          |       "array" : [{"value":"${rudder-data.fakeEncryptorEngineTesting[password]}"}]
          |    }
          |  }
          |
          |}
          |""".stripMargin
      }
      val after   = {
        """{
          |  "login" : "encrypted-string-test",
          |  "data" : [
          |      {
          |        "foo" : "encrypted-string-test"
          |      },
          |      "encrypted-string-test",
          |      "encrypted-string-test",
          |      {
          |        "shouldBeInterpolated" : "encrypted-string-test"
          |      }
          |  ]
          |  "moreData" : {
          |    "nestedStruct" : {
          |       "array" : [{"value":"encrypted-string-test"}]
          |    }
          |  }
          |}
          |""".stripMargin
      }

      (runParseJValue(JsonParser.parse(before), toNodeContext(context, Map())) must beEqualTo(JsonParser.parse(after))) and
      (runParseJValue(JsonParser.parse(before2), toNodeContext(context, Map())) must beEqualTo(JsonParser.parse(after)))
    }
  }

  "A single parameter" should {
    "be replaced by its value" in {
      lookup(Seq(var1), context.copy(parameters = p(fooParam)))(values =>
        values must containTheSameElementsAs(Seq(Seq("== fooValue ==")))
      )
    }

    "understand if its value is a parameter" in {
      lookup(Seq(recurVariable), context.copy(parameters = p(fooParam, recurParam)))(values =>
        values must containTheSameElementsAs(Seq(Seq("== fooValue ==")))
      )
    }

    "correctly escape regex special chars" in {
      lookup(Seq(dangerVariable), context.copy(parameters = p(dangerousChars)))(values =>
        values must containTheSameElementsAs(Seq(Seq(badChars)))
      )
    }

    "match when inputs are on multiline" in {
      lookup(Seq(multilineInputVariable), context.copy(parameters = p(fooParam)))(values =>
        values must containTheSameElementsAs(Seq(Seq("=\r= \nfooValue =\n=")))
      )
    }

    "match when node properties are on multiline" in {
      val v = GenericProperty
        .parseValue("""{"Europe": "Paris"}""")
        .fold(
          err => throw new IllegalArgumentException("Error in test: " + err.fullMsg),
          identity
        )
      val c = context
        .modify(_.nodeInfo.properties)
        .setTo(Chunk(NodeProperty("datacenter", v, None, None)))
        .modify(_.parameters)
        .setTo(p(fooParam))

      lookup(Seq(multilineNodePropVariable), c)(values => values must containTheSameElementsAs(Seq(Seq("=\r= \nParis =\n="))))
    }

    "fails when the curly brace after ${rudder. is not closed" in {
      getError(lookupParam(Seq(badUnclosed), context).either.runNow) must beMatching(
        """.*\Q'== ${rudder.param.foo =='. Error message is: Expected "}":1:23, found "=="\E.*""".r
      )
    }

    "fails when the part after ${rudder.} is empty" in {
      getError(lookupParam(Seq(badEmptyRudder), context).either.runNow) must beMatching(
        """.*\Q'== ${rudder.} =='. Error message is: Expected (rudderNode | parameters | oldParameter):1:13, found "} =="\E.*""".r
      )
    }

    "fails when the part after ${rudder.} is not recognised" in {
      getError(lookupParam(Seq(badUnknown), context.copy(parameters = p(fooParam))).either.runNow) must beMatching(
        """.*\Q'== ${rudder.foo} =='. Error message is: Expected (rudderNode | parameters | oldParameter):1:13, found "foo} =="\E.*""".r
      )
    }
  }

  "A double parameter" should {
    "be replaced by its value" in {
      lookup(Seq(var1_double), context.copy(parameters = p(fooParam, barParam)))(values =>
        values must containTheSameElementsAs(Seq(Seq("== fooValuebarValue ==")))
      )
    }
    "accept space between values" in {
      lookup(Seq(var1_double_space), context.copy(parameters = p(fooParam, barParam)))(values =>
        values must containTheSameElementsAs(Seq(Seq("== fooValue contains barValue ==")))
      )
    }

  }

  "Node parameters" should {
    "be correclty replaced by their values" in {
      lookup(Seq(var2), context)(values => {
        values must containTheSameElementsAs(
          Seq(
            Seq(
              s"a${id1.value})",
              s"=${hostname1}/",
              s".${admin1}]",
              "$" + rootId.value + "|",
              s"{${rootHostname}&",
              s"!${rootAdmin}^"
            )
          )
        )
      })
    }
  }

  "Node properties in directives" should {

    def compare(param: String, props: List[(String, String)]) = {
      val i = compileAndGet(param)
      val p = props.map {
        case (k, value) =>
          val v = GenericProperty
            .parseValue(value)
            .fold(
              err => throw new IllegalArgumentException("Error in test: " + err.fullMsg),
              identity
            )
          NodeProperty(k, v, None, None)
      }
      val c = context.modify(_.nodeInfo.properties).setTo(Chunk.fromIterable(p))

      i(c).either.runNow
    }

    val json = """{  "Europe" : {  "France"  : "Paris" }   }"""

    "not be able to replace parameter when no properties" in {
      compare("${node.properties[plop]}", ("datacenter", "some data center") :: Nil) must haveClass[Left[?, ?]]
    }

    "correctly get the value even if not in JSON for 1-deep-length path" in {
      compare("${node.properties[datacenter]}", ("datacenter", "some data center") :: Nil) must beEqualTo(
        Right("some data center")
      )
    }

    "not be able to replace a parameter with a 2-deep length path in non-json value" in {
      compare("${node.properties[datacenter][Europe]}", ("datacenter", "some data center") :: Nil) must haveClass[Left[?, ?]]
    }

    "not be able to replace a parameter with a 2-deep length path in a json value without the asked path" in {
      compare("${node.properties[datacenter][Asia]}", ("datacenter", json) :: Nil) must haveClass[Left[?, ?]]
    }

    "correclty return the compacted json string for 1-length" in {
      compare("${node.properties[datacenter]}", ("datacenter", json) :: Nil) must beEqualTo(
        Right(net.liftweb.json.compactRender(jparse(json)))
      )
    }

    "correctly return the compacted json string for 2-or-more-lenght" in {
      compare("${node.properties[datacenter][Europe]}", ("datacenter", json) :: Nil) must beEqualTo(
        Right("""{"France":"Paris"}""")
      ) // look, NO SPACES
    }

    "correctly return the same string if interpretation is done on node" in {
      compare("${node.properties[datacenter][Europe]|node}", ("datacenter", json) :: Nil) must beEqualTo(
        Right("""${node.properties[datacenter][Europe]}""")
      )
    }

    "correctly return the default string if value is missing on node" in {
      compare(
        """${node.properties[datacenter][Europe]|default="some default"}""",
        ("missing_datacenter", json) :: Nil
      ) must beEqualTo(Right("""some default"""))
    }

    "correctly return the replaced value if key is present on node" in {
      compare("""${node.properties[datacenter][Europe]|default="some default"}""", ("datacenter", json) :: Nil) must beEqualTo(
        Right("""{"France":"Paris"}""")
      )
    }
    "correctly return the replaced value, two level deep, if first key is missing" in {
      compare(
        """${node.properties[missing][key]|default= ${node.properties[datacenter][Europe]|default="some default"}}""",
        ("datacenter", json) :: Nil
      ) must beEqualTo(Right("""{"France":"Paris"}"""))
    }
    "correctly return the default value, two level deep, if all keys are missing" in {
      compare(
        """${node.properties[missing][key]|default= ${node.properties[missing_datacenter][Europe]|default="some default"}}""",
        ("datacenter", json) :: Nil
      ) must beEqualTo(Right("""some default"""))
    }
    "correctly return the default value which is ${node.properties...}, if default has the 'node' optiopn" in {
      compare(
        """${node.properties[missing][key]|default= ${node.properties[datacenter][Europe]|node}}""",
        ("datacenter", json) :: Nil
      ) must beEqualTo(Right("""${node.properties[datacenter][Europe]}"""))
    }
  }

  "Case" should {
    "not matter in the path" in {
      lookup(Seq(pathCaseInsensitive), context.copy(parameters = p(fooParam)))(values =>
        values must containTheSameElementsAs(Seq(Seq("== fooValue ==")))
      )
    }

    "not matter in nodes path accessor" in {
      val i = compileAndGet("${rudder.node.HoStNaMe}")
      i(context).either.runNow must beEqualTo(Right("node1.localhost"))
    }

    "matter for parameter names" in {
      getError(lookupParam(Seq(paramNameCaseSensitive), context).either.runNow) must beMatching(
        """.*\QMissing parameter '${node.parameter[Foo]}\E.*""".r
      )
    }

    "matter in param names" in {
      val i = compileAndGet("${rudder.param.xX}")
      val c = context.copy(parameters = {
        Map(
          // test all combination
          ("XX", (i: ParamInterpolationContext) => "bad".succeed),
          ("Xx", (i: ParamInterpolationContext) => "bad".succeed),
          ("xx", (i: ParamInterpolationContext) => "bad".succeed)
        )
      })
      i(c).either.runNow match {
        case Right(_)  => ko("No, case must matter!")
        case Left(err) => err.msg must beEqualTo("Missing parameter '${node.parameter[xX]}'")
      }
    }

    "matter in node properties" in {
      val value = GenericProperty
        .parseValue("some data center somewhere")
        .fold(
          err => throw new IllegalArgumentException("Error in test: " + err.fullMsg),
          identity
        )

      def compare(s1: String, s2: String) = {
        val i = compileAndGet(s"$${node.properties[${s1}]}")
        val c = context.modify(_.nodeInfo.properties).setTo(Chunk(NodeProperty(s2, value, None, None)))

        i(c).either.runNow
      }

      compare("DataCenter", "datacenter") must haveClass[Left[?, ?]]
      compare("datacenter", "DataCenter") must haveClass[Left[?, ?]]
      compare("datacenter", "datacenter") must beEqualTo(Right(GenericProperty.serializeToJson(value)))
    }

  }

}
