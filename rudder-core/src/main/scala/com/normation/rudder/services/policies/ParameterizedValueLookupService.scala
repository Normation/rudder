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

import java.util.regex.Matcher
import java.util.regex.Pattern

import com.normation.cfclerk.domain.Variable
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.parameters.ParameterName
import com.normation.rudder.services.policies.nodeconfig.ParameterForConfiguration
import com.normation.utils.Control._
import com.normation.utils.HashcodeCaching

import net.liftweb.common._

/**
 * A service that handle parameterized value of
 * directive variables.
 *
 * The parameterization is to be taken in the context of
 * a rule (i.e, a directive applied to
 * a target), and in the scope of one node of the target
 * (as if you were processing one node at a time).
 *
 * The general parameterized value are of the form:
 * ${...}
 * were "..." is the parameter to lookup.
 *
 * We handle 2 kinds of parameterizations:
 * 1/ ${rudder.CONFIG_RULE_ID.ACCESSOR}
 *    where:
 *    - CONFIG_RULE_ID is a valid id of a rule in the system
 *    - ACCESSOR is an accessor for that rule, explained below.
 * 2/ ${rudder.node.ACCESSOR}
 *    where:
 *    - "node" is a keyword ;
 *    - ACCESSOR is an accessor for that node, explained below.
 *
 * Accessor are keywords which allows to reach value in a context, exactly like
 * properties in object oriented programming.
 *
 * Accessors for parameters
 *    ${rudder.param.ACCESSOR} : replace by the value for the parameter with the name ACCESSOR
 *
 * Accessors for node
 * ------------------
 *   ${rudder.node.id} : internal ID of the node (generally an UUID)
 *   ${rudder.node.hostname} : hostname of the node
 *   ${rudder.node.admin} : login (or username) of the node administrator, or root, or at least
 *                   the login to use to run the agent
 *   ${rudder.node.policyserver.ACCESSOR} : information about the policyserver of the node.
 *                                    ACCESSORs are the same than for ${rudder.node}
 *
 * Accessors for rule
 * --------------------------------
 * Accessor for rule are of two forms:
 * - ${rudder.CONFIG_RULE_ID.VAR_NAME}
 *   where VAR_NAME is the name of a variable of the technique implemented
 *   by the directive referenced by the given rule ;
 *   The following constraint are applied on a VAR_NAME accessor:
 *   - if the variable is monovalued, the targeted variable must have only one value
 *     (the target variable may be multivalued with exaclty one value) ;
 *   - if the variable is multivalued and is not part of a group,
 *     there is no constraints on the target ;
 *   - if the variable is mutlivalued and is part of group, there is no constraints
 *     on the target **BUT** if other members of the group don't have the same
 *     cardinality than the result of the lookup, you will have problems.
 *
 * - ${rudder.CONFIG_RULE_ID.target.ACCESSOR}
 *   - where "target" is an accessor which change the context of following
 *     accessors to the target of the rule (group, etc)
 *     Target Accessor are the same as node accessors, but are multivalued.
 *
 * Important notice:
 * -----------------
 * For a given rule, ALL parameterized value are processed in the
 * same order, and order is kept.
 *
 * That means  (for example) that if a parameterized value references two target accessors,
 * information regarding the same node will be put at the same index.
 *
 */
trait ParameterizedValueLookupService {

  /**
   * Replace all parameterization of the form ${rudder.node.XXX}
   * and ${rudder.param.XXX} by their values
   *
   * Here, we expect to always have only ONE value for each parametrization,
   * so variable cardinality are NOT changed by that lookup.
   *
   * A variable's value can have several parametrization.
   *
   * Return the new set of variables with value in place of parameters, for example:
   * "/tmp/${rudder.param.root_of_nodes}/${rudder.node.id}"
   * will become:
   * "/tmp/something/3720814d-7d2c-41eb-b730-804701c2f398
   *
   * nodeId: obviously, the node id
   * variables: the variables on which parameter will be replaced
   * parameters: parameters from NodeConfiguration for that node
   *
   */
  def lookupNodeParameterization(nodeId: NodeId, variables:Seq[Variable], parameters:Set[ParameterForConfiguration], allNodes: Map[NodeId, NodeInfo]) : Box[Seq[Variable]]


  sealed abstract class AccessorName(val value:String)
  case object ID extends AccessorName("id")
  case object HOSTNAME extends AccessorName("hostname")
  case object ADMIN extends AccessorName("admin")

  def isValidAccessorName(accessor:String) : Boolean = accessor.toLowerCase match {
    case ID.value | HOSTNAME.value | ADMIN.value => true
    case _ => false
  }

  sealed trait RegexParameterTest {
    def regex: String
    protected lazy val internalRegex = ("(?is)" + regex).r

    def r = ("(?is).*" + regex + ".*").r

    /**
     * Replace all occurence of regex in "target" by the value "replacer"
     */
    def replace(target: String, replacer:String) : Box[String] = {
      try {
        Full(internalRegex.replaceAllIn(target, replacer))
      } catch {
        case e: Exception => Failure(s"Error when replacing parameter named '${target}' by value '${replacer}': ${e.getMessage}")
      }
    }
  }

  sealed trait Parametrization

  case class BadParametrization(value:String) extends Parametrization with HashcodeCaching

  abstract class NodeParametrization extends Parametrization

  abstract class NodePsParametrization extends Parametrization
  object NodePsParametrization extends RegexParameterTest {
    override val regex = """\$\{rudder\.node\.policyserver\..*\}"""
  }
  case object ParamNodeId extends NodeParametrization with RegexParameterTest {
    override val regex = """\$\{rudder\.node\.id\}"""
  }
  case object ParamNodeHostname extends NodeParametrization with RegexParameterTest {
    override val regex = """\$\{rudder\.node\.hostname\}"""
  }
  case object ParamNodeAdmin extends NodeParametrization with RegexParameterTest {
    override val regex = """\$\{rudder\.node\.admin\}"""
  }
  case object ParamNodePsId extends NodeParametrization with RegexParameterTest {
    override val regex = """\$\{rudder\.node\.policyserver\.id\}"""
  }
  case object ParamNodePsHostname extends NodeParametrization with RegexParameterTest {
    override val regex = """\$\{rudder\.node\.policyserver\.hostname\}"""
  }
  case object ParamNodePsAdmin extends NodeParametrization with RegexParameterTest {
    override val regex = """\$\{rudder\.node\.policyserver\.admin\}"""
  }

  case object NodeParam extends NodeParametrization
  case class BadNodeParam(value:String) extends NodeParametrization with HashcodeCaching


  abstract class ParameterParametrization extends Parametrization

  case class ParameterParam(parameterName: String) extends ParameterParametrization with HashcodeCaching

  object Parametrization extends RegexParameterTest {
    override val regex = """\$\{rudder\.(.*)\}"""
  }


  object NodeParametrization extends RegexParameterTest {
    override val regex = """\$\{rudder\.node\..*\}"""

    def unapply(value:String) : Option[Parametrization] = {
        //start by the most specific and go up
        value.toLowerCase match {
          case ParamNodeId.r() => Some(ParamNodeId)
          case ParamNodeHostname.r() => Some(ParamNodeHostname)
          case ParamNodeAdmin.r() => Some(ParamNodeAdmin)
          case ParamNodePsId.r() => Some(ParamNodePsId)
          case ParamNodePsHostname.r() => Some(ParamNodePsHostname)
          case ParamNodePsAdmin.r() => Some(ParamNodePsAdmin)
          case NodeParametrization.r() => Some(BadNodeParam(value))
          case _ => None
        }
    }
  }

  /**
   * This one is different from nodes, because we do have to remember the param name and replace only these one
   * after
   */
  object ParameterParametrization extends Loggable {

    val regex = """(?is)\$\{rudder\.param\.([\-_a-zA-Z0-9]+)}"""

    private[this] def internalRegex(param: ParameterName) = ("""(?is)\$\{rudder\.param\.""" + param.value + """}""").r

    def r = ("(?is).*" + regex + ".*").r

    /**
     * Replace all occurence of regex in "target" by the value "replacer"
     */
    def replace(target: String, replacer: ParameterForConfiguration) : Box[String] = {
      try {
        Full(internalRegex(replacer.name).replaceAllIn(target, Matcher.quoteReplacement(replacer.value)))
      } catch {
        case e:Exception => Failure(s"Exception with parameter name: '${replacer.name.value}' for value: '${replacer.value}': ${e.getMessage}")
      }
    }


    def unapply(value:String) : Option[Parametrization] = {
        //start by the most specific and go up
        value match {
          case ParameterParametrization.r(parameter) => Some(ParameterParam(parameter))
          case _ => None
        }
    }
  }
}


class ParameterizedValueLookupServiceImpl extends ParameterizedValueLookupService {


  private[this] def lookupNodeVariable(nodeInfo : NodeInfo, policyServerInfo: => Box[NodeInfo], value:String) : Box[String] = {
    value match {
      case NodeParametrization(ParamNodeId) => ParamNodeId.replace(value, nodeInfo.id.value)
      case NodeParametrization(ParamNodeHostname) => ParamNodeHostname.replace(value, nodeInfo.hostname)
      case NodeParametrization(ParamNodeAdmin) => ParamNodeAdmin.replace(value,nodeInfo.localAdministratorAccountName)
      case NodeParametrization(ParamNodePsId) => ParamNodePsId.replace(value,nodeInfo.policyServerId.value)
      case NodeParametrization(ParamNodePsHostname) => policyServerInfo.flatMap(x => ParamNodePsHostname.replace(value, x.hostname))
      case NodeParametrization(ParamNodePsAdmin) => policyServerInfo.flatMap(x => ParamNodePsAdmin.replace(value, x.localAdministratorAccountName))
      case NodeParametrization(BadParametrization(value)) => Failure("Unknow parameterized value: ${%s}".format(value))
      case _ => Full(value) //nothing to replace
    }
  }

  private[this] def lookupParameterParametrization(value: String, parameters: Map[String, ParameterForConfiguration]) : Box[String] = {
    value match {
      case ParameterParametrization(ParameterParam(name)) =>
        parameters.get(name) match {
          case Some(parameter) => ParameterParametrization.replace(value, parameter)
          case _ => Failure("Unknow parametrized value : %s".format(value))
        }
      case _ => Full(value)
    }
  }


  private[this] def checkSanity(value: String) : Box[String] = {

    //To check if contains spaces
    val ok = """(?is).*\$\{rudder([^\}]+)}.*""".r

    val matchSpace = Pattern.compile("""^[\S]+$""")

    /*
     * Unclosed also if another line or contain spaces
     */
    val unclosed = """(?is).*\$\{rudder[^\}]*""".r

    /*
     *
     * that one is sooooo nice.
     * Can be check in http://www.regexplanet.com/advanced/java/index.html
     * with values:
     * [Doesn't match]  ${rudder.param.foo}
     * [Matches      ]  ${rudder.foo}
     * [Matches      ]  ${rudder.}
     * [Matches      ]  ${rudder}
     */
    val unknown  = """(?is).*\$\{rudder(\.?[^(param|node)]?[^\}\.]*)\}.*""".r

    value match {
      case ok(name) if(!matchSpace.matcher(name).matches) =>
        Failure(s"Can not replace parameters in value '${value}' because accessors contains spaces")
      case unclosed() => Failure(s"Can not replace parameters in value '${value}' because a curly brace is not closed")
      case unknown(name) => Failure(s"Can not replace parameters in value '${value}' because accessor '${name}' is not recognized")
      case _ => Full(value)
    }

  }

  /**
   * Change parameter for given value until a fixe point is reached.
   * Don't iterate more than 5 times.
   */
  private[this] def recurrencelLookupParameter(value:String, nodeInfo : NodeInfo, policyServerInfo: => Box[NodeInfo], parameters: Map[String, ParameterForConfiguration]) : Box[String] = {

    def recLookup(recValue:String, iteration: Int) : Box[String] = {

      (for {
        v1 <- lookupNodeVariable(nodeInfo, policyServerInfo, recValue)
        v2 <- lookupParameterParametrization(v1, parameters)
        v3 <- checkSanity(v2)
      } yield {
        v3
      }) match {
        case eb:EmptyBox => eb
        case Full(v) if(v == recValue) => Full(v)
        case Full(v) =>
          if(iteration > 0) recLookup(v, iteration - 1)
          else Failure(s"Can not replace parameters in value ${value} because of too many replacement attemped. Last value was: ${v}")
      }
    }
    recLookup(value, 5)
  }


  override def lookupNodeParameterization(nodeId: NodeId, variables:Seq[Variable], parameters:Set[ParameterForConfiguration], allNodes:Map[NodeId, NodeInfo]) : Box[Seq[Variable]] = {
    val params = parameters.map(p => (p.name.value, p)).toMap

    for {
      nodeInfo <- Box(allNodes.get(nodeId)) ?~! s"Can not find node with id ${nodeId.value}"
      polServer = Box(allNodes.get(nodeInfo.policyServerId))
      variables <- sequence(variables) { v =>
        for {
          // first, expand the node variables
          values <- sequence(v.values) { value =>
            recurrencelLookupParameter(value, nodeInfo, polServer, params)
          }
        } yield Variable.matchCopy(v, values)
      }
    } yield variables
  }
}


