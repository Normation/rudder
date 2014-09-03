/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.services.policies

import org.joda.time.DateTime
import org.junit.runner._
import org.specs2.runner._
import org.specs2.mutable._
import org.specs2.specification._
import com.normation.cfclerk.domain.InputVariableSpec
import com.normation.cfclerk.domain.Variable
import com.normation.inventory.domain._
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.parameters.ParameterName
import com.normation.rudder.services.policies.nodeconfig.ParameterForConfiguration
import net.liftweb.common._
import com.normation.utils.Control.sequence
import scala.collection.immutable.TreeMap
import com.normation.rudder.domain.policies.InterpolationContext
import InterpolationContext._
import com.normation.rudder.reports.FullCompliance
import com.normation.rudder.reports.ReportingConfiguration



/**
 * Test how parametrized variables are replaced for
 * parametrization with ${rudder.param.XXX} and
 * ${rudder.node.YYYY}
 */


@RunWith(classOf[JUnitRunner])
class TestNodeAndParameterLookup extends Specification {

  //null is for RuleValService, only used in
  //rule lookup, node tested here.
  val compiler = new InterpolatedValueCompilerImpl()
  val lookupService = new RuleValServiceImpl(compiler)

  val id1 = NodeId("node1")
  val hostname1 = "node1.localhost"
  val admin1 = "root"
  val rootId = NodeId("root")
  val rootHostname = "root.localhost"
  val rootAdmin = "root"

  private val emptyNodeReportingConfiguration = ReportingConfiguration(
    None
  )


  val node1 = NodeInfo(
      id            = id1
    , name          = "node1"
    , description   = ""
    , hostname      = hostname1
    , machineType   = "vm"
    , osName        = "Debian"
    , osVersion     = "7.0"
    , servicePack   = None
    , ips           = List("192.168.0.10")
    , inventoryDate = DateTime.now
    , publicKey     = ""
    , agentsName    = Seq(COMMUNITY_AGENT)
    , policyServerId= rootId
    , localAdministratorAccountName= admin1
    , creationDate  = DateTime.now
    , isBroken      = false
    , isSystem      = false
    , isPolicyServer= true
    , serverRoles   = Set()
    , emptyNodeReportingConfiguration
  )

  val root = NodeInfo(
      id            = rootId
    , name          = "root"
    , description   = ""
    , hostname      = rootHostname
    , machineType   = "vm"
    , osName        = "Debian"
    , osVersion     = "7.0"
    , servicePack   = None
    , ips           = List("192.168.0.100")
    , inventoryDate = DateTime.now
    , publicKey     = ""
    , agentsName    = Seq(COMMUNITY_AGENT)
    , policyServerId= rootId
    , localAdministratorAccountName= rootAdmin
    , creationDate  = DateTime.now
    , isBroken      = false
    , isSystem      = false
    , isPolicyServer= true
    , serverRoles   = Set()
    , emptyNodeReportingConfiguration
  )

  val nodeInventory1: NodeInventory = NodeInventory(
      NodeSummary(
          node1.id
        , AcceptedInventory
        , node1.localAdministratorAccountName
        , node1.hostname
        , Linux(Debian, "test machine", new Version("1.0"), None, new Version("3.42"))
        , root.id
      )
      , name                 = None
      , description          = None
      , ram                  = None
      , swap                 = None
      , inventoryDate        = None
      , receiveDate          = None
      , archDescription      = None
      , lastLoggedUser       = None
      , lastLoggedUserTime   = None
      , agentNames           = Seq()
      , publicKeys           = Seq()
      , serverIps            = Seq()
      , machineId            = None //if we want several ids, we would have to ass an "alternate machine" field
      , softwareIds          = Seq()
      , accounts             = Seq()
      , environmentVariables = Seq(EnvironmentVariable("THE_VAR", Some("THE_VAR value!")))
      , processes            = Seq()
      , vms                  = Seq()
      , networks             = Seq()
      , fileSystems          = Seq()
      , serverRoles          = Set()
  )

  val context = InterpolationContext(
        parameters      = Map()
      , nodeInfo        = node1
      , inventory       = nodeInventory1
      , policyServerInfo= root
        //environment variable for that server
      , nodeContext     = Map()
  )


  def lookup(
      variables: Seq[Variable]
    , context: InterpolationContext
  )(test:Seq[Seq[String]] => Example) : Example  = {
    lookupService.lookupNodeParameterization(variables)(context) match {
      case eb:EmptyBox =>
        val e = eb ?~! "Error in test"
        val ex = e ?~! e.rootExceptionCause.map( _.getMessage ).openOr("(not caused by an other exception)")
        failure(ex.messageChain)
      case Full(res) => test(res.values.map( _.values ).toSeq)
    }
  }



  //two variables
  val var1 = InputVariableSpec("var1", "").toVariable(Seq("== ${rudder.param.foo} =="))
  val var1_double = InputVariableSpec("var1_double", "").toVariable(Seq("== ${rudder.param.foo}${rudder.param.bar} =="))
  val var1_double_space = InputVariableSpec("var1_double_space", "").toVariable(Seq("== ${rudder.param.foo} contains ${rudder.param.bar} =="))

  val pathCaseInsensitive = InputVariableSpec("pathCaseInsensitive", "").toVariable(Seq("== ${RudDer.paRam.foo} =="))

  val paramNameCaseSensitive = InputVariableSpec("paramNameCaseSensitive", "").toVariable(Seq("== ${rudder.param.Foo} =="))

  val recurVariable = InputVariableSpec("recurParam", "").toVariable(Seq("== ${rudder.param.recurToFoo} =="))

  val dangerVariable = InputVariableSpec("danger", "").toVariable(Seq("${rudder.param.danger}"))

  val multilineParamVariable = InputVariableSpec("multiParam", "").toVariable(Seq("== ${rudder.\rparam.\nfoo} =="))
  val multilineInputVariable = InputVariableSpec("multiInput", "").toVariable(Seq("=\r= \n${rudder.param.foo} =\n="))

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

  val fooParam = ParameterForConfiguration(ParameterName("foo"), "fooValue")
  val barParam = ParameterForConfiguration(ParameterName("bar"), "barValue")
  val recurParam = ParameterForConfiguration(ParameterName("recurToFoo"), """${rudder.param.foo}""")

  val badChars = """$¹ ${plop} (foo) \$ @ %plop & \\ | $[xas]^"""
  val dangerousChars = ParameterForConfiguration(ParameterName("danger"), badChars)

  def p(params: ParameterForConfiguration*): Map[ParameterName, InterpolationContext => Box[String]] = {
    sequence(params.toSeq) { param =>
      for {
        p <- compiler.compile(param.value) ?~! s"Error when looking for interpolation variable in global parameter '${param.name}'"
      } yield {
        (param.name, p)
      }
    }.map{seq =>
      Map(seq:_*)
   } match {
      case Full(m) => m
      case eb: EmptyBox =>
        throw new RuntimeException((eb ?~! "Error when parsing parameters for interpolated variables").messageChain)
    }
  }

  /**
   * Test that the parser correctly parse strings
   * to the expected AST
   */
  "Parsing values" should {
    import compiler.{failure => fff, _}

    //in case of success, test for the result
    def test[T](r: ParseResult[T], result: Any) = r match {
      case NoSuccess(msg, x) => ko(msg)
      case Success(x, remaining) =>  x === result
    }

    "parse (multiline) plain text" in {
      val s = """some vars chars with \z \n plop foo"""
      test(parseAll(plainString, s), CharSeq(s))
    }

    "parse a rudder param variable" in {
      test(parseAll(interpol, """${rudder.param.foo}"""), Param("foo"))
    }

    "parse a rudder node variable" in {
      test(parseAll(interpol, """${rudder.node.foo.bar.baz}"""), NodeAccessor(List("foo", "bar", "baz")))
    }

    "parse text and variable and text" in {
      val s1 = "plj jmoji h imj "
      val s2 = " alkjf fm ^{i àié${rudde ut ùt "
      test(parseAll(all, s1+"${rudder.node.policyserver.id}"+s2), List(CharSeq(s1), NodeAccessor(List("policyserver", "id")), CharSeq(s2)))
    }

    "parse (multiline) text and variable and text" in {
      val s1 = "plj jmoji \n h \timj "
      val s2 = " alkjf \n\rfm ^{i àié${rudde ut ùt "
      test(parseAll(all, s1+"${rudder.node.policyserver.id}"+s2), List(CharSeq(s1), NodeAccessor(List("policyserver", "id")), CharSeq(s2)))
    }
  }

  def compileAndGet(s:String) = compiler.compile(s).openOrThrowException("Initialisation test error")

  /**
   * Test that the interpretation of an AST is
   * correctly done (with forged interpretation contexts)
   */
  "Interpretation of a parsed interpolated string" should {


    val nodeId = compileAndGet("${rudder.node.uuid}")
    val policyServerId = compileAndGet("${rudder.node.id}")
    val paramVar = compileAndGet("${rudder.node.uuid}")

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

      accessors must contain( (x:(String, InterpolationContext => Box[String], String)) => x._2(context) match {
        case eb:EmptyBox => ko((eb ?~! s"Error when evaluating context for accessor ${x._1} with expected result '${x._3}'").messageChain)
        case Full(result) => result === x._3
      }).forall
    }

    "raise an error for an unknow accessor" in {
      val badAccessor = "rudder.node.foo"
      compiler.compile("${"+badAccessor+"}") match {
        case eb:EmptyBox => ko((eb?~!"Error when parsing interpolated value").messageChain)
        case Full(i) => i(context) match {
          case Full(res) => ko(s"When interpreted, an unkown accessor '${badAccessor}' should yield an error")
          case Empty => ko("Unknown accessor should yield a real Failure, not an Empty")
          case f:Failure => 1 === 1 //here, ok(...) leads to a typing error
        }
      }
    }


    "correctly interpret simple param" in {
      val res = "p1 replaced"
      val i = compileAndGet("${rudder.param.p1}")
      val c = context.copy(parameters = Map(
          (ParameterName("p1"), (i:InterpolationContext) => Full(res))
      ))
      i(c) must beEqualTo(Full(res))
    }


    "fails on missing param in context" in {
      val res = "p1 replaced"
      val i = compileAndGet("${rudder.param.p1}")
      i(context) match {
        case Full(_) => ko("The parameter should not have been found")
        case Empty => ko("Real Failure are expected, not Empty")
        case f:Failure => 1 === 1 //ok(...) leads to type error
      }
    }

    "correcly replace parameter with interpolated values" in {
      val res = "p1 replaced with p2 value"
      val i = compileAndGet("${rudder.param.p1}")
      val p1value = compileAndGet("${rudder.param.p2}")
      val c = context.copy(parameters = Map(
          (ParameterName("p1"), p1value)
        , (ParameterName("p2"), (i:InterpolationContext) => Full(res))
      ))
      i(c) must beEqualTo(Full(res))
    }

    "correctly replace maxDepth-1 parameter with interpolated values" in {
      val res = "p1 replaced with p2 value"
      val i = compileAndGet("${rudder.param.p1}")
      val p1value = compileAndGet("${rudder.param.p2}")
      val p2value = compileAndGet("${rudder.param.p3}")
      val p3value = compileAndGet("${rudder.param.p4}")
      val c = context.copy(parameters = Map(
          (ParameterName("p1"), p1value)
        , (ParameterName("p2"), p2value)
        , (ParameterName("p3"), p3value)
        , (ParameterName("p4"), (i:InterpolationContext) => Full(res))
      ))

      (compiler.maxEvaluationDepth == 5) and
      (i(c) must beEqualTo(Full(res)))
    }

    "fails to replace maxDepth parameter with interpolated values" in {
      val res = "p1 replaced with p2 value"
      val i = compileAndGet("${rudder.param.p1}")
      val p1value = compileAndGet("${rudder.param.p2}")
      val p2value = compileAndGet("${rudder.param.p3}")
      val p3value = compileAndGet("${rudder.param.p4}")
      val p4value = compileAndGet("${rudder.param.p5}")
      val c = context.copy(parameters = Map(
          (ParameterName("p1"), p1value)
        , (ParameterName("p2"), p2value)
        , (ParameterName("p3"), p3value)
        , (ParameterName("p4"), p3value)
        , (ParameterName("p5"), (i:InterpolationContext) => Full(res))
      ))

      (compiler.maxEvaluationDepth == 5) and
      (i(c) match {
        case Full(_) => ko("Was expecting an error due to too deep evaluation")
        case Empty => ko("Was expecting an error due to too deep evaluation")
        case f:Failure => 1 === 1 //ok(...) does not type check
      })
    }

    "fails to replace recurring parameter value" in {
      val i = compileAndGet("${rudder.param.p1}")
      val c = context.copy(parameters = Map(
          (ParameterName("p1"), i)
      ))

      i(c) match {
        case Full(_) => ko("Was expecting an error due to too deep evaluation")
        case Empty => ko("Was expecting an error due to too deep evaluation")
        case f:Failure => 1 === 1 //ok(...) does not type check
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

    "fails when the curly brace after ${rudder. is not closed" in {
      lookupService.lookupNodeParameterization(Seq(badUnclosed))(context) must beEqualTo(
        Failure("""Error when parsing variable empty""",Empty,Full(Failure("""Error when parsing value "== ${rudder.param.foo ==", error message is: `}' expected but ` ' found""")))
      )
    }

    "fails when the part after ${rudder.} is empty" in {
      lookupService.lookupNodeParameterization(Seq(badEmptyRudder))(context) must beEqualTo(
        Failure("""Error when parsing variable empty""",Empty,Full(Failure("""Error when parsing value "== ${rudder.} ==", error message is: string matching regex `(?iu)\Qparam\E' expected but `}' found""")))
      )
    }

    "fails when the part after ${rudder.} is not recognised" in {
      lookupService.lookupNodeParameterization(Seq(badUnknown))(context.copy(parameters = p(fooParam))) must beEqualTo(
        Failure("""Error when parsing variable empty""",Empty,Full(Failure("""Error when parsing value "== ${rudder.foo} ==", error message is: string matching regex `(?iu)\Qparam\E' expected but `f' found""")))
      )
    }

    "not match when parameter names are splited on multiple lines" in {
      lookupService.lookupNodeParameterization(Seq(multilineParamVariable))(context.copy(parameters = p(fooParam))) must beEqualTo(
        Failure("""Error when parsing variable multiParam""",Empty,Full(Failure("Error when parsing value \"== ${rudder.\rparam.\nfoo} ==\", error message is: string matching regex `(?iu)\\Qparam\\E' expected but `\r' found",Empty,Empty)))
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


  "Case" should {
    "not matter in the path" in {
      lookup(Seq(pathCaseInsensitive), context.copy(parameters = p(fooParam)))( values =>
        values must containTheSameElementsAs(Seq(Seq("== fooValue ==")))
      )
    }

    "matter for parameter names" in {
      lookupService.lookupNodeParameterization(Seq(paramNameCaseSensitive))(context) must beEqualTo(
        Failure(
            "Error when resolving interpolated variable in directive variable paramNameCaseSensitive"
          , Empty
          , Full(Failure("Error when trying to interpolate a variable: Rudder parameter not found: 'Foo'",Empty,Empty))
        )
      )
    }

    "not matter in nodes path accessor" in {
      val i = compileAndGet("${rudder.node.HoStNaMe}")
      i(context) must beEqualTo(Full("node1.localhost"))
    }

    "matter in environement variable name" in {
      val i = compileAndGet("${rudder.node.env.THE_VAR}")
      val j = compileAndGet("${rudder.node.env.THE_var}")
      (i(context) must beEqualTo(Full("THE_VAR value!"))) and (j(context) must beEqualTo(Full("")))
    }

    "matter in param names" in {
      val i = compileAndGet("${rudder.param.xX}")
      val c = context.copy(parameters = Map(
          //test all combination
          (ParameterName("XX"), (i:InterpolationContext) => Full("bad"))
        , (ParameterName("Xx"), (i:InterpolationContext) => Full("bad"))
        , (ParameterName("xx"), (i:InterpolationContext) => Full("bad"))
      ))
      i(c) match {
        case Full(_) => ko("No, case must matter!")
        case Empty => ko("No, we should have a failure")
        case Failure(m,_,_) => m must beEqualTo("Error when trying to interpolate a variable: Rudder parameter not found: 'xX'")
      }
    }

  }

}