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

import com.normation.cfclerk.domain.InputVariableSpec
import com.normation.cfclerk.domain.Variable
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import net.liftweb.common.Box
import net.liftweb.common.Empty
import net.liftweb.common.Failure
import net.liftweb.common.Full
import org.specs2.matcher.Expectable
import net.liftweb.json.JsonAST.JString
import net.liftweb.json.JsonAST.JValue
import com.normation.rudder.domain.nodes.NodeProperty

import scala.util.matching.Regex
import org.specs2.matcher.Matcher
import net.liftweb.json._
import com.normation.errors._
import cats.implicits._


/**
 * Test how parametrized variables are replaced for
 * parametrization with ${rudder.param.XXX} and
 * ${rudder.node.YYYY}
 */

@RunWith(classOf[JUnitRunner])
class TestNodeAndGlobalParameterLookup extends Specification {

  //matcher for failure
  def beFailure[T](r: Regex) = new Matcher[Box[T]] {
    def apply[S <: Box[T]](v: Expectable[S]) = {

      val res = v.value match {
        case Full(x)           => false
        case Empty             => false
        case Failure(m, _, _) => r.pattern.matcher(m).matches()
      }

      result(res, "ok", "Didn't get failure matching regex " + r.toString, v, Failure(r.toString()).toString, v.value.toString)
    }
  }


  def getError[A](e: PureResult[A]) = e.swap.getOrElse(throw new RuntimeException("ERROR")).fullMsg


  import NodeConfigData._
  //null is for RuleValService, only used in
  //rule lookup, node tested here.
  val compiler = new InterpolatedValueCompilerImpl()
  val lookupService = new RuleValServiceImpl(compiler)

  val context = ParamInterpolationContext(
      parameters      = Map()
    , globalPolicyMode= defaultModesConfig.globalPolicyMode
    , nodeInfo        = node1
    , policyServerInfo= root
  )

  def toNodeContext(c: ParamInterpolationContext, params: Map[String, String]) = InterpolationContext(
      parameters      = params
    , nodeInfo        = c.nodeInfo
    , globalPolicyMode= c.globalPolicyMode
    , policyServerInfo= c.policyServerInfo
      //environment variable for that server
    , nodeContext     = Map()
  )


  def lookup(
      variables: Seq[Variable]
    , pContext : ParamInterpolationContext
  )(test:Seq[Seq[String]] => org.specs2.execute.Result) : org.specs2.execute.Result  = {
    lookupParam(variables, pContext) match {
      case Left(err)  => failure("Error in test: " + err.fullMsg)
      case Right(res) => test(res.values.map( _.values ).toSeq)
    }
  }

  def lookupParam(
      variables  : Seq[Variable]
    , lookupParam: ParamInterpolationContext
  ) = {
    (for {
      params <- lookupParam.parameters.toList.traverse { case (k, c) => c(lookupParam).map((k, _)) }
      res    <- lookupService.lookupNodeParameterization(variables)(toNodeContext(lookupParam, params.toMap))
    } yield res)
  }

  def jparse(s: String): JValue = try { parse(s) } catch { case ex: Exception => JString(s) }

  //two variables
  val var1 = InputVariableSpec("var1", "").toVariable(Seq("== ${rudder.param.foo} =="))
  val var1_double = InputVariableSpec("var1_double", "").toVariable(Seq("== ${rudder.param.foo}${rudder.param.bar} =="))
  val var1_double_space = InputVariableSpec("var1_double_space", "").toVariable(Seq("== ${rudder.param.foo} contains ${rudder.param.bar} =="))

  val pathCaseInsensitive = InputVariableSpec("pathCaseInsensitive", "").toVariable(Seq("== ${RudDer.paRam.foo} =="))

  val paramNameCaseSensitive = InputVariableSpec("paramNameCaseSensitive", "").toVariable(Seq("== ${rudder.param.Foo} =="))

  val recurVariable = InputVariableSpec("recurParam", "").toVariable(Seq("== ${rudder.param.recurToFoo} =="))

  val dangerVariable = InputVariableSpec("danger", "").toVariable(Seq("${rudder.param.danger}"))

  val multilineInputVariable = InputVariableSpec("multiInput", "").toVariable(Seq("=\r= \n${rudder.param.foo} =\n="))
  val multilineNodePropVariable = InputVariableSpec("multiNodeProp", "").toVariable(Seq("=\r= \n${node.properties[datacenter][Europe]} =\n="))

  val var2 = InputVariableSpec("var1", "", multivalued = true).toVariable(Seq(
      "a${rudder.node.id})"
    , "=${rudder.node.hostname}/"
    , ".${rudder.node.admin}]"
    , "$${rudder.node.policyserver.id}|"
    , "{${rudder.node.policyserver.hostname}&"
    , "!${rudder.node.policyserver.admin}^"
  ))

  val badEmptyRudder = InputVariableSpec("empty", "").toVariable(Seq("== ${rudder.} =="))
  val badUnclosed = InputVariableSpec("empty", "").toVariable(Seq("== ${rudder.param.foo =="))
  val badUnknown = InputVariableSpec("empty", "").toVariable(Seq("== ${rudder.foo} =="))

  val fooParam = ParameterForConfiguration("foo", "fooValue")
  val barParam = ParameterForConfiguration("bar", "barValue")
  val recurParam = ParameterForConfiguration("recurToFoo", """${rudder.param.foo}""")

  val badChars = """$¹ ${plop} (foo) \$ @ %plop & \\ | $[xas]^"""
  val dangerousChars = ParameterForConfiguration("danger", badChars)

  def p(params: ParameterForConfiguration*): Map[String, ParamInterpolationContext => PureResult[String]] = {
    import cats.implicits._
    params.toList.traverse { param =>
      for {
        p <- compiler.compileParam(param.value).chainError(s"Error when looking for interpolation variable in global parameter '${param.name}'")
      } yield {
        (param.name, p)
      }
    }.map{seq =>
      Map(seq:_*)
   }.chainError("Error when parsing parameters for interpolated variables") match {
      case Left(err)  => throw new RuntimeException(err.fullMsg)
      case Right(res) => res
    }
  }


  import PropertyParserTokens._
  import PropertyParser._
  import fastparse._

  //in case of success, test for the result
  def test[T](p: P[_] => P[T], value: String, result: Any) = {
    fastparse.parse(value, p(_)) match {
      case Parsed.Success(x, index) => x === result
      case f:Parsed.Failure         => ko(f.trace().longAggregateMsg)
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

    "parse a rudder param variable" in {
      test(variableStart(_), """${rudder.param.foo}""", Param("foo"))
    }

    "parse a rudder param variable with spaces" in {
      test(variableStart(_), """${rudder . param . foo}""", Param("foo"))
    }

    "parse a rudder node variable" in {
      test(variableStart(_), """${rudder.node.foo.bar.baz}""", NodeAccessor(List("foo", "bar", "baz")))
    }

    "parse a rudder node variable with spaces" in {
      test(variableStart(_), """${rudder . node . foo . bar . baz}""", NodeAccessor(List("foo", "bar", "baz")))
    }

    "parse a rudder param variable with all parser" in {
      test(all(_), """${rudder.param.foo}""", List(Param("foo")))
    }

    "parse a rudder param variable with all parser with spaces" in {
      test(all(_), """${rudder . param . foo}""", List(Param("foo")))
    }

    "parse a non rudder param variable with all parser" in {
      test(all(_), """${something.cfengine}""", List(NonRudderVar("${something.cfengine}")))
    }

    "parse a non rudder param variable with all parser with space and keep them" in {
      test(all(_), """${something . cfengine}""", List(NonRudderVar("${something . cfengine}")))
    }

    "parse blank test" in {
      test(all(_), "      ",
        List(CharSeq("      ")))
    }

    "parse text and variable and text" in {
      val s1 = "plj jmoji h imj "
      val s2 = " alkjf fm ^{i àié" // will be split at '$'
      val s3 = "${rudde ut ùt "
      test(all(_), s1+"${rudder.node.policyserver.id}"+s2+s3,
        List(CharSeq(s1), NodeAccessor(List("policyserver", "id")), CharSeq(s2), CharSeq(s3)))
    }

    "parse (multiline) text and variable and text" in {
      val s1 = "plj jmoji \n h \timj "
      val s2 = " alkjf \n\rfm ^{i àié"
      val s3 = "${rudde ut ùt "
      test(all(_), s1+"${rudder.node.policyserver.id}"+s2+s3,
        List(CharSeq(s1), NodeAccessor(List("policyserver", "id")), CharSeq(s2), CharSeq(s3)))
    }

    "parse a standard cfengine variable" in {
      val s = """${bla.foo}"""
      test(all(_), s, List(NonRudderVar(s)))
    }

    "accept rudder_parameters variable as a plain variable" in {
      val s = """${rudder_parameters.foo}"""
      test(all(_), s, List(NonRudderVar(s)))
    }

    "accept rudderthing variable as a plain variable" in {
      val s = """${rudderthings.foo}"""
      test(all(_), s, List(NonRudderVar(s)))
    }

    "accept node.things variable as a plain variable" in {
      val s = """${node.thing.foo}"""
      test(all(_), s, List(NonRudderVar(s)))
    }

    "accept nodethings variable as a plain variable" in {
      val s = """${nodething.foo}"""
      test(all(_), s, List(NonRudderVar(s)))
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

    "parse node properties with path=N>2" in {
      val s = """${node.properties[datacenter][Europe][France][Paris][3]}"""
      test(all(_), s, List(Property("datacenter" :: "Europe" :: "France" :: "Paris" :: "3" :: Nil, None)))
    }

    "parse node properties in the middle of a string" in {
      val s = """some text and ${node.properties[datacenter][Europe]}  and some more text"""
      test(all(_), s, List(CharSeq("some text and "), Property("datacenter" :: "Europe" :: Nil, None), CharSeq("  and some more text")))
    }

    "parse node properties 'node' option" in {
      val s = """some text and ${node.properties[datacenter][Europe]|node}  and some more text"""
      test(all(_), s, List(CharSeq("some text and "), Property("datacenter" :: "Europe" :: Nil, Some(InterpreteOnNode)), CharSeq("  and some more text")))
    }

    "parse node properties 'default:''' option" in {
      val s = """some text and ${node.properties[datacenter][Europe]|default= ""}  and some more text"""
      test(all(_), s, List(
          CharSeq("some text and ")
        , Property("datacenter" :: "Europe" :: Nil, Some(DefaultValue(CharSeq("")::Nil)))
        , CharSeq("  and some more text")
      ))
    }

    "parse node properties 'default:string' option" in {
      val s = """some text and ${node.properties[datacenter][Europe]|default= "default value"}  and some more text"""
      test(all(_), s, List(
          CharSeq("some text and ")
        , Property("datacenter" :: "Europe" :: Nil, Some(DefaultValue(CharSeq("default value")::Nil)))
        , CharSeq("  and some more text")
      ))
    }

    "parse node properties 'default:string with {}' option" in {
      val s = """some text and ${node.properties[datacenter][Europe]|default= "default {} value" }  and some more text"""
      test(all(_), s, List(
          CharSeq("some text and ")
        , Property("datacenter" :: "Europe" :: Nil, Some(DefaultValue(CharSeq("default {} value")::Nil)))
        , CharSeq("  and some more text")
      ))
    }


    "parse node properties 'default:tq'' option" in {
      val s = """some text and ${node.properties[datacenter][Europe]|default= """ + "\"\"\"\"\"\"" + """}  and some more text"""
      test(all(_), s, List(
          CharSeq("some text and ")
        , Property("datacenter" :: "Europe" :: Nil, Some(DefaultValue(CharSeq("")::Nil)))
        , CharSeq("  and some more text")
      ))
    }

    "parse node properties 'default:string' option" in {
      val s = """some text and ${node.properties[datacenter][Europe]|default= """ + "\"\"\"" + "default {} value"+ "\"\"\"" + """}  and some more text"""
      test(all(_), s, List(
          CharSeq("some text and ")
        , Property("datacenter" :: "Europe" :: Nil, Some(DefaultValue(CharSeq("default {} value")::Nil)))
        , CharSeq("  and some more text")
      ))
    }

    "parse node properties 'default:string with {}' option" in {
      val s = """some text and ${node.properties[datacenter][Europe]|default= """ + "\"\"\"" + "default {} value"+ "\"\"\"" + """ }  and some more text"""
      test(all(_), s, List(
          CharSeq("some text and ")
        , Property("datacenter" :: "Europe" :: Nil, Some(DefaultValue(CharSeq("default {} value")::Nil)))
        , CharSeq("  and some more text")
      ))
    }



    "parse node properties 'default:param' option" in {
      val s = """some text and ${node.properties[datacenter][Europe]|default=${rudder.param.foo}}  and some more text"""
      test(all(_), s, List(CharSeq("some text and "), Property("datacenter" :: "Europe" :: Nil, Some(DefaultValue(Param("foo")::Nil))), CharSeq("  and some more text")))
    }

    "parse node properties 'default:node.hostname' option" in {
      val s = """some text and ${node.properties[datacenter][Europe]|default=${rudder.node.hostname}}  and some more text"""
      test(all(_), s, List(CharSeq("some text and "), Property("datacenter" :: "Europe" :: Nil, Some(DefaultValue(NodeAccessor(List("hostname"))::Nil))), CharSeq("  and some more text")))
    }

    "parse node properties 'default:node.properties' option" in {
      val s = """some text and ${node.properties[datacenter][Europe]|default=${node.properties[defaultDatacenter]}}  and some more text"""
      test(all(_), s, List(
          CharSeq("some text and ")
        , Property("datacenter" :: "Europe" :: Nil, Some(DefaultValue(Property("defaultDatacenter" :: Nil, None)::Nil)))
        , CharSeq("  and some more text")
      ))
    }
    "parse node properties 'default:node.properties + string' option" in {
      val s = """some text and ${node.properties[datacenter][Europe]|default=" default ${node.properties[defaultDatacenter]}"}  and some more text"""
      test(all(_), s, List(
        CharSeq("some text and ")
        , Property("datacenter" :: "Europe" :: Nil, Some(DefaultValue(CharSeq(" default ") :: Property("defaultDatacenter" :: Nil, None) :: Nil)))
        , CharSeq("  and some more text")
      ))
    }
    "parse node properties 'default:node.properties+default' option" in {
      val s = """some text and ${node.properties[datacenter][Europe]|default=${node.properties[defaultDatacenter]|default="some default value"}}  and some more text"""
      test(all(_), s, List(
          CharSeq("some text and ")
        , Property("datacenter" :: "Europe" :: Nil, Some(DefaultValue(Property("defaultDatacenter" :: Nil, Some(DefaultValue(CharSeq("some default value")::Nil)))::Nil)))
        , CharSeq("  and some more text")
      ))
    }
  }


  "Parsing values with spaces" should {

    "parse n.either.runNowode properties 'default:node.hostname' option" in {
      val s = """some text and ${node . properties [
        datacenter] [ Europe] | default= ${rudder . node
        . hostname }  }  and some more text"""

      test(all(_), s, List(CharSeq("some text and "), Property("datacenter" :: "Europe" :: Nil, Some(DefaultValue(NodeAccessor(List("hostname"))::Nil))), CharSeq("  and some more text")))
    }
  }
  def compileAndGet(s:String) = compiler.compileParam(s).getOrElse(throw new RuntimeException(s"compileAndGet(${s}): ERROR"))

  /**
   * Test that the interpretation of an AST is
   * correc.either.runNowtly done (with forged interpretation contexts)
   */
  "Interpretation of a parsed interpolated string" should {

    "know for the 6 node & policy server param" in {

      //build a triplet: accessor, interpolation function, expected
      def comp(accessor:String, expected:String) = (
          accessor
        , compileAndGet(s"$${rudder.node.${accessor}}")
        , expected
      )

      //map of server.param -> AST
      val accessors = List(
          comp("id", context.nodeInfo.id.value)
        , comp("hostname", context.nodeInfo.hostname)
        , comp("admin", context.nodeInfo.localAdministratorAccountName)
        , comp("policyserver.id", context.policyServerInfo.id.value)
        , comp("policyserver.hostname", context.policyServerInfo.hostname)
        , comp("policyserver.admin", context.policyServerInfo.localAdministratorAccountName)
      )

      accessors must contain( (x:(String, ParamInterpolationContext => PureResult[String], String)) => (x._2(context) match {
        case Right(result) => result must beEqualTo(x._3)
        case Left(err) => ko(s"Error when evaluating context for accessor ${x._1} with expected result '${x._3}': " + err.fullMsg)
      })).forall
    }

    "raise an error for an unknow accessor" in {
      val badAccessor = "rudder.node.foo"
      compiler.compileParam("${"+badAccessor+"}") match {
        case Left(err) => ko("Error when parsing interpolated value: " + err.fullMsg)
        case Right(i) => i(context) match {
          case Right(res) => ko(s"When interpreted, an unkown accessor '${badAccessor}' should yield an error")
          case Left(_) => ok
        }
      }
    }

    "correctly interpret simple param" in {
      val res = "p1 replaced"
      val i = compileAndGet("${rudder.param.p1}")
      val c = context.copy(parameters = Map(
          ("p1", (i:ParamInterpolationContext) => Right(res))
      ))
      i(c) must beEqualTo(Right(res))
    }

    "fails on missing param in context" in {
      val i = compileAndGet("${rudder.param.p1}")
      i(context) match {
        case Right(_) => ko("The parameter should not have been found")
        case Left(_) => ok
      }
    }

    "correcly replace parameter with interpolated values" in {
      val res = "p1 replaced with p2 value"
      val i = compileAndGet("${rudder.param.p1}")
      val p1value = compileAndGet("${rudder.param.p2}")
      val c = context.copy(parameters = Map(
          ("p1", p1value)
        , ("p2", (i:ParamInterpolationContext) => Right(res))
      ))
      i(c) must beEqualTo(Right(res))
    }

    "correctly replace maxDepth-1 parameter with interpolated values" in {
      val res = "p1 replaced with p2 value"
      val i = compileAndGet("${rudder.param.p1}")
      val p1value = compileAndGet("${rudder.param.p2}")
      val p2value = compileAndGet("${rudder.param.p3}")
      val p3value = compileAndGet("${rudder.param.p4}")
      val c = context.copy(parameters = Map(
          ("p1", p1value)
        , ("p2", p2value)
        , ("p3", p3value)
        , ("p4", (i:ParamInterpolationContext) => Right(res))
      ))

      (AnalyseParamInterpolation.maxEvaluationDepth == 5) and
      (i(c) must beEqualTo(Right(res)))
    }

    "fails to replace maxDepth parameter with interpolated values" in {
      val res = "p1 replaced with p2 value"
      val i = compileAndGet("${rudder.param.p1}")
      val p1value = compileAndGet("${rudder.param.p2}")
      val p2value = compileAndGet("${rudder.param.p3}")
      val p3value = compileAndGet("${rudder.param.p4}")
      val c = context.copy(parameters = Map(
          ("p1", p1value)
        , ("p2", p2value)
        , ("p3", p3value)
        , ("p4", p3value)
        , ("p5", (i:ParamInterpolationContext) => Right(res))
      ))

      (AnalyseParamInterpolation.maxEvaluationDepth == 5) and
      (i(c) match {
        case Right(_) => ko("Was expecting an error due to too deep evaluation")
        case Left(_) => ok
      })
    }

    "fails to replace recurring parameter value" in {
      val i = compileAndGet("${rudder.param.p1}")
      val c = context.copy(parameters = Map(
          ("p1", i)
      ))

      i(c) match {
        case Right(_) => ko("Was expecting an error due to too deep evaluation")
        case Left(_) => ok
      }
    }

  }

  "A single parameter" should {
    "be replaced by its value" in {
      lookup(Seq(var1), context.copy(parameters =  p(fooParam)))( values =>
        values must containTheSameElementsAs(Seq(Seq("== fooValue ==")))
      )
    }

    "understand if its value is a parameter" in {
      lookup(Seq(recurVariable), context.copy(parameters = p(fooParam, recurParam)))( values =>
        values must containTheSameElementsAs(Seq(Seq("== fooValue ==")))
      )
    }

    "correctly escape regex special chars" in {
      lookup(Seq(dangerVariable), context.copy(parameters = p(dangerousChars)))( values =>
        values must containTheSameElementsAs(Seq(Seq(badChars)))
      )
    }

    "match when inputs are on multiline" in {
      lookup(Seq(multilineInputVariable), context.copy(parameters = p(fooParam)))( values =>
        values must containTheSameElementsAs(Seq(Seq("=\r= \nfooValue =\n=")))
      )
    }

    "match when node properties are on multiline" in {
      val node = context.nodeInfo.node.copy(properties = List(NodeProperty("datacenter", jparse("""{"Europe": "Paris"}"""), None)))
      val c = context.copy(nodeInfo = context.nodeInfo.copy(node = node))
      lookup(Seq(multilineNodePropVariable), c.copy(parameters = p(fooParam)))( values =>
        values must containTheSameElementsAs(Seq(Seq("=\r= \nParis =\n=")))
      )
    }



    "fails when the curly brace after ${rudder. is not closed" in {
      getError(lookupParam(Seq(badUnclosed), context)) must beMatching(
        """.*== \$\{rudder.param.foo ==",.*""".r
      )
    }

    "fails when the part after ${rudder.} is empty" in {
      getError(lookupParam(Seq(badEmptyRudder), context)) must beMatching(
        """.* Error when parsing value "== \$\{rudder.} ==",.*""".r
      )
    }

    "fails when the part after ${rudder.} is not recognised" in {
      getError(lookupParam(Seq(badUnknown), context.copy(parameters = p(fooParam)))) must beMatching(
         """.* Error when parsing value "== \$\{rudder.foo} ==",.*""".r
      )
    }
  }

  "A double parameter" should {
    "be replaced by its value" in {
      lookup(Seq(var1_double), context.copy(parameters = p(fooParam, barParam)))( values =>
        values must containTheSameElementsAs(Seq(Seq("== fooValuebarValue ==")))
      )
    }
    "accept space between values" in {
      lookup(Seq(var1_double_space), context.copy(parameters = p(fooParam, barParam)))( values =>
        values must containTheSameElementsAs(Seq(Seq("== fooValue contains barValue ==")))
      )
    }

  }

  "Node parameters" should {
    "be correclty replaced by their values" in  {
      lookup(Seq(var2), context)( values =>
        values must containTheSameElementsAs(
            Seq(Seq(
                s"a${id1.value})"
              , s"=${hostname1}/"
              , s".${admin1}]"
              , "$"+rootId.value+"|"
              , s"{${rootHostname}&"
              , s"!${rootAdmin}^"
            ))
        )
      )
    }
  }

  "Node properties in directives" should {

    def compare(param: String, props: List[(String, String)])= {
      val i = compileAndGet(param)
      val p = props.map { case (k, v) => NodeProperty(k, jparse(v), None) }
      val node = context.nodeInfo.node.copy(properties = p)
      val c = context.copy(nodeInfo = context.nodeInfo.copy(node = node))

      i(c)
    }

    val json = """{  "Europe" : {  "France"  : "Paris" }   }"""

    "not be able to replace parameter when no properties" in {
      compare("${node.properties[plop]}", ("datacenter", "some data center") :: Nil) must haveClass[Left[_,_]]
    }

    "correctly get the value even if not in JSON for 1-deep-length path" in {
      compare("${node.properties[datacenter]}", ("datacenter", "some data center") :: Nil) must beEqualTo(Right("some data center"))
    }

    "not be able to replace a parameter with a 2-deep length path in non-json value" in {
      compare("${node.properties[datacenter][Europe]}", ("datacenter", "some data center") :: Nil) must haveClass[Left[_,_]]
    }

    "not be able to replace a parameter with a 2-deep length path in a json value without the asked path" in {
      compare("${node.properties[datacenter][Asia]}", ("datacenter", json) :: Nil) must haveClass[Left[_,_]]
    }

    "correclty return the compacted json string for 1-length" in {
      compare("${node.properties[datacenter]}", ("datacenter", json) :: Nil) must beEqualTo(Right(net.liftweb.json.compactRender(jparse(json))))
    }

    "correctly return the compacted json string for 2-or-more-lenght" in {
      compare("${node.properties[datacenter][Europe]}", ("datacenter", json) :: Nil) must beEqualTo(Right("""{"France":"Paris"}""") )// look, NO SPACES
    }

    "correctly return the same string if interpretation is done on node" in {
      compare("${node.properties[datacenter][Europe]|node}", ("datacenter", json) :: Nil) must beEqualTo(Right("""${node.properties[datacenter][Europe]}"""))
    }

    "correctly return the default string if value is missing on node" in {
      compare("""${node.properties[datacenter][Europe]|default="some default"}""", ("missing_datacenter", json) :: Nil) must beEqualTo(Right("""some default"""))
    }

    "correctly return the replaced value if key is present on node" in {
      compare("""${node.properties[datacenter][Europe]|default="some default"}""", ("datacenter", json) :: Nil) must beEqualTo(Right("""{"France":"Paris"}"""))
    }

    "correctly return the replaced value, two level deep, if first key is missing" in {
      compare("""${node.properties[missing][key]|default= ${node.properties[datacenter][Europe]|default="some default"}}"""
           , ("datacenter", json) :: Nil) must beEqualTo(Right("""{"France":"Paris"}"""))
    }
    "correctly return the default value, two level deep, if all keys are missing" in {
      compare("""${node.properties[missing][key]|default= ${node.properties[missing_datacenter][Europe]|default="some default"}}"""
           , ("datacenter", json) :: Nil) must beEqualTo(Right("""some default"""))
    }
    "correctly return the default value which is ${node.properties...}, if default has the 'node' optiopn" in {
      compare("""${node.properties[missing][key]|default= ${node.properties[datacenter][Europe]|node}}"""
           , ("datacenter", json) :: Nil) must beEqualTo(Right("""${node.properties[datacenter][Europe]}"""))
    }
  }


  "Case" should {
    "not matter in the path" in {
      lookup(Seq(pathCaseInsensitive), context.copy(parameters = p(fooParam)))( values =>
        values must containTheSameElementsAs(Seq(Seq("== fooValue ==")))
      )
    }

    "not matter in nodes path accessor" in {
      val i = compileAndGet("${rudder.node.HoStNaMe}")
      i(context) must beEqualTo(Right("node1.localhost"))
    }

    "matter for parameter names" in {
      getError(lookupParam(Seq(paramNameCaseSensitive), context)) must beMatching(
        ".*Rudder parameter not found: 'Foo'".r
      )
    }

    "matter in param names" in {
      val i = compileAndGet("${rudder.param.xX}")
      val c = context.copy(parameters = Map(
          //test all combination
          ("XX", (i:ParamInterpolationContext) => Right("bad"))
        , ("Xx", (i:ParamInterpolationContext) => Right("bad"))
        , ("xx", (i:ParamInterpolationContext) => Right("bad"))
      ))
      i(c) match {
        case Right(_) => ko("No, case must matter!")
        case Left(err) => err.msg must beEqualTo("Error when trying to interpolate a variable: Rudder parameter not found: 'xX'")
      }
    }

    "matter in node properties" in {
      val value = "some data center somewhere"

      def compare(s1: String, s2: String) = {
        val i = compileAndGet(s"$${node.properties[${s1}]}")
        val props = List(NodeProperty(s2, jparse(value), None))
        val node = context.nodeInfo.node.copy(properties = props)
        val c = context.copy(nodeInfo = context.nodeInfo.copy(node = node))

        i(c)
      }

      compare("DataCenter", "datacenter") must haveClass[Left[_,_]]
      compare("datacenter", "DataCenter") must haveClass[Left[_,_]]
      compare("datacenter", "datacenter") must beEqualTo(Right(value))
    }

  }

}
