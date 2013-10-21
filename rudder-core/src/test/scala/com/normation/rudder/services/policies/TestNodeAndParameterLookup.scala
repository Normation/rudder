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

import org.junit.runner._
import org.specs2.runner._
import org.specs2.mutable._
import net.liftweb.common._
import com.normation.cfclerk.domain.InputVariable
import com.normation.cfclerk.domain.InputVariableSpec
import com.normation.rudder.domain.parameters.ParameterName
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.inventory.domain.NodeId
import org.joda.time.DateTime
import com.normation.inventory.domain.COMMUNITY_AGENT
import com.normation.cfclerk.domain.Variable
import org.specs2.specification.Example
import org.specs2.execute.Pending


/**
 * Test how parametrized variables are replaced for
 * parametrization with ${rudder.param.XXX} and
 * ${rudder.node.YYYY}
 */


@RunWith(classOf[JUnitRunner])
class TestNodeAndParameterLookup extends Specification {

  //null is for RuleValService, only used in
  //rule lookup, node tested here.
  val lookupService = new ParameterizedValueLookupServiceImpl(null)


  def lookup(
      nodeId: NodeId
    , variables: Seq[Variable]
    , parameters: Set[ParameterForConfiguration]
    , allNodes:Set[NodeInfo]
  )(test:Seq[Seq[String]] => Example) : Example  = {
    lookupService.lookupNodeParameterization(nodeId, variables, parameters, allNodes) match {
      case eb:EmptyBox =>
        val e = eb ?~! "Error in test"
        val ex = e ?~! e.rootExceptionCause.map( _.getMessage ).openOr("(not caused by an other exception)")
        failure(ex.messageChain)
      case Full(res) => test(res.map( _.values ))
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

  val id1 = NodeId("node1")
  val hostname1 = "node1.localhost"
  val admin1 = "root"
  val rootId = NodeId("root")
  val rootHostname = "root.localhost"
  val rootAdmin = "root"

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
  )

  "A single parameter" should {
    "be replaced by its value" in {
      lookup(id1, Seq(var1), Set(fooParam), Set(node1))( values =>
        values must containTheSameElementsAs(Seq(Seq("== fooValue ==")))
      )
    }

    "understand if its value is a parameter" in {
      lookup(id1, Seq(recurVariable), Set(fooParam, recurParam), Set(node1))( values =>
        values must containTheSameElementsAs(Seq(Seq("== fooValue ==")))
      )
    }

    "correctly escape regex special chars" in {
      lookup(id1, Seq(dangerVariable), Set(dangerousChars), Set(node1))( values =>
        values must containTheSameElementsAs(Seq(Seq(badChars)))
      )
    }

    "match when inputs are on multiline" in {
      lookup(id1, Seq(multilineInputVariable), Set(fooParam), Set(node1))( values =>
        values must containTheSameElementsAs(Seq(Seq("=\r= \nfooValue =\n=")))
      )
    }

    "throws an error when the curly brace after ${rudder. is not closed" in {
      lookupService.lookupNodeParameterization(id1, Seq(badUnclosed), Set(), Set(node1)) must beEqualTo(
        Failure("Can not replace parameters in value '== ${rudder.param.foo ==' because a curly brace is not closed")
      )
    }

    "throws an error when the part after ${rudder.} is empty" in {
      lookupService.lookupNodeParameterization(id1, Seq(badEmptyRudder), Set(), Set(node1)) must beEqualTo(
        Failure("Can not replace parameters in value '== ${rudder.} ==' because accessor '.' is not recognized")
      )
    }

    "throws an error when the part after ${rudder.} is not recognised" in {
      lookupService.lookupNodeParameterization(id1, Seq(badUnknown), Set(fooParam), Set(node1)) must beEqualTo(
        Failure("Can not replace parameters in value '== ${rudder.foo} ==' because accessor '.foo' is not recognized")
      )
    }

    "not match when parameter names are splited on multiple lines" in {
      lookupService.lookupNodeParameterization(id1, Seq(multilineParamVariable), Set(fooParam), Set(node1)) must beEqualTo(
        Failure("Can not replace parameters in value '== ${rudder.\rparam.\nfoo} ==' because accessors contains spaces")
      )
    }

  }

  "A double parameter" should {
    "be replaced by its value" in {
      lookup(id1, Seq(var1_double), Set(fooParam, barParam), Set(node1))( values =>
        values must containTheSameElementsAs(Seq(Seq("== fooValuebarValue ==")))
      )
    }
    "accept space between values" in {
      lookup(id1, Seq(var1_double_space), Set(fooParam, barParam), Set(node1))( values =>
        values must containTheSameElementsAs(Seq(Seq("== fooValue contains barValue ==")))
      )
    }

  }

  "Node parameters" should {
    "be correclty replaced by their values" in  {
      lookup(id1, Seq(var2), Set(), Set(node1, root))( values =>
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
      lookup(id1, Seq(pathCaseInsensitive), Set(fooParam), Set(node1))( values =>
        values must containTheSameElementsAs(Seq(Seq("== fooValue ==")))
      )
    }

    "matter for parameter names" in {
      lookupService.lookupNodeParameterization(id1, Seq(paramNameCaseSensitive), Set(), Set(node1)) must beEqualTo(
        Failure("Unknow parametrized value : == ${rudder.param.Foo} ==")
      )
    }
  }

}