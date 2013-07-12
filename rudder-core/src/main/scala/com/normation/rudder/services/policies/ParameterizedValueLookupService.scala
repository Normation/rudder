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

import com.normation.rudder.repository.RuleRepository
import com.normation.rudder.services.nodes.NodeInfoService
import net.liftweb.common._
import com.normation.rudder.domain.policies.RuleId
import com.normation.inventory.domain.NodeId
import com.normation.cfclerk.domain.{VariableSpec, Variable}
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.utils.Control.sequence
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.utils.HashcodeCaching

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
   * We are in the context of a node, given by the id.
   * We can provide a NodeInfo as cache for the node.
   *
   */
  def lookupNodeParameterization(nodeId:NodeId, variables:Seq[Variable]) : Box[Seq[Variable]]

  /**
   * Replace all parameterization of the form
   * ${rudder.CONFIGURATION_RULE_ID.XXX} by their values
   *
   * TODO: handle cache !!
   *
   */
  def lookupRuleParameterization(variables:Seq[Variable]) : Box[Seq[Variable]]



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
    protected lazy val internalRegex = regex.r

    def r = (".*" + regex + ".*").r

    /**
     * Replace all occurence of regex in "target" by the value "replacer"
     */
    def replace(target: String, replacer:String) : String = internalRegex.replaceAllIn(target, replacer)
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

  abstract class CrParametrization extends Parametrization

  case class CrVarParametrization(crName:String, accessor:String) extends CrParametrization with HashcodeCaching
  case class CrTargetParametrization(crName:String, accessor:String) extends CrParametrization with HashcodeCaching
  object CrTargetParametrization extends RegexParameterTest {
    override val regex = """\$\{rudder\.([\-_a-zA-Z0-9]+)\.target\.([\-_a-zA-Z0-9]+)\}"""
  }



  object Parametrization extends RegexParameterTest {
    override val regex = """\$\{rudder\.(.*)\}"""
  }

  object CrParametrization extends RegexParameterTest {
    override val regex = """\$\{rudder\.([\-_a-zA-Z0-9]+)\.([\-_a-zA-Z0-9]+)\}"""

    def unapply(value:String) : Option[Parametrization] = {
        //start by the most specific and go up
        value.toLowerCase match {
          case NodeParametrization.r() => None
          case CrTargetParametrization.r(crName,accessor) => Some(CrTargetParametrization(crName,accessor))
          case CrParametrization.r(crName,accessor) => Some(CrVarParametrization(crName,accessor))
          case Parametrization.r(value) => Some(BadParametrization(value))
          case _ => None
        }
    }
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
}


class ParameterizedValueLookupServiceImpl(
    override val nodeInfoService : NodeInfoService,
    override val directiveTargetService : RuleTargetService,
    override val ruleRepo : RuleRepository,
    override val ruleValService : RuleValService
) extends ParameterizedValueLookupService with
  ParameterizedValueLookupService_lookupNodeParameterization with
  ParameterizedValueLookupService_lookupRuleParameterization

////////////////////////////////////////////////////////////////////////////////////////////////////


trait ParameterizedValueLookupService_lookupNodeParameterization extends ParameterizedValueLookupService {

  def nodeInfoService : NodeInfoService

  private[this] def lookupNodeVariable(nodeInfo : NodeInfo, policyServerInfo: => Box[NodeInfo], value:String) : Box[String] = {
    value match {
      case NodeParametrization(ParamNodeId) => Full(ParamNodeId.replace(value, nodeInfo.id.value))
      case NodeParametrization(ParamNodeHostname) => Full(ParamNodeHostname.replace(value, nodeInfo.hostname))
      case NodeParametrization(ParamNodeAdmin) => Full(ParamNodeAdmin.replace(value,nodeInfo.localAdministratorAccountName))
      case NodeParametrization(ParamNodePsId) => Full(ParamNodePsId.replace(value,nodeInfo.policyServerId.value))
      case NodeParametrization(ParamNodePsHostname) => policyServerInfo.map(x => ParamNodePsHostname.replace(value, x.hostname))
      case NodeParametrization(ParamNodePsAdmin) => policyServerInfo.map(x => ParamNodePsAdmin.replace(value, x.localAdministratorAccountName))
      case NodeParametrization(BadParametrization(value)) => Failure("Unknow parameterized value: ${%s}".format(value))
      case _ => Full(value) //nothing to replace
    }
  }


  override def lookupNodeParameterization(nodeId:NodeId, variables:Seq[Variable]) : Box[Seq[Variable]] = {
    for {
      nodeInfo <- nodeInfoService.getNodeInfo(nodeId)
      variables <- sequence(variables) { v =>
        for {
          values <- sequence(v.values) { value =>
            lookupNodeVariable(nodeInfo, nodeInfoService.getNodeInfo(nodeInfo.policyServerId), value)
          }
        } yield Variable.matchCopy(v, values)
      }
    } yield variables
  }
}




trait ParameterizedValueLookupService_lookupRuleParameterization extends ParameterizedValueLookupService with Loggable {


  def directiveTargetService : RuleTargetService
  def nodeInfoService : NodeInfoService
  def ruleRepo : RuleRepository
  def ruleValService : RuleValService

  /**
   * Replace all parameterization of the form
   * ${CONFGIGURATION_RULE_ID.XXX} by their values
   */
  override def lookupRuleParameterization(variables:Seq[Variable]) : Box[Seq[Variable]] = {
     sequence(variables) { variable =>
     logger.debug("Processing variable : %s".format(variable))
       (sequence(variable.values) { value =>
         value match {
           case CrParametrization(CrTargetParametrization(targetConfiguRuleId, targetAccessorName)) =>
             logger.debug("Processing rule's parameterized value on target: %s".format(value))
             lookupTargetParameter(variable.spec, RuleId(targetConfiguRuleId), targetAccessorName)
           case CrParametrization(CrVarParametrization(targetConfiguRuleId, varAccessorName)) =>
             logger.debug("Processing rule's parameterized value on variable: %s".format(value))
             lookupVariableParameter(variable.spec, RuleId(targetConfiguRuleId), varAccessorName)
           case CrParametrization(BadParametrization(name)) =>
             logger.debug("Ignoring parameterized value (can not handle such parameter): %s".format(value))
             Full(Seq(value))
           case _ =>  //nothing to do
             Full(Seq(value))
         }
       }
       //we had value as simple strings, now we habe seq of strings (most of the time of one string) : flatten the results
       //note: the resulting Seq[values] may be longer after replacement that before
       ).map {seq => logger.debug("setted variable values are %s %s".format(variable.spec.name, seq.flatten));Variable.matchCopy(variable, seq.flatten) }
     }
  }




   /**
    * Lookup the variable with name varName in RuleVal.id in crv.
    * Try to first lookup the values in cache, and if it is not yet present,
    * update it with the value from RuleVal.
    * If RuleVal does not have such a variable name, fails.
    * If the looked-up variable's values contain a parameterized value, fails.
    * @param crv
    * @param varName
    * @param cache
    * @return
    */
  private[this] def lookupTargetParameter(
    sourceVariableSpec:VariableSpec,
    targetConfiguRuleId:RuleId,
    targetAccessorName:String) : Box[Seq[String]] = {

    if(isValidAccessorName(targetAccessorName)) {
      for {
        rule <- ruleRepo.get(targetConfiguRuleId)
        cf = logger.trace("Fetched rule : %s".format(rule))
        targets <- rule.targets match {
          case list if !list.isEmpty => Full(list)
          case list if list.isEmpty => Failure(
              "Missing target for rule with ID %s. Can not lookup parameters for a not fully defined rule"
                .format(targetConfiguRuleId))
        }
        nodeIds <- sequence(targets.toSeq){target => directiveTargetService.getNodeIds(target)}
        cf1 = logger.trace("Fetched nodes ids : %s".format(nodeIds))
        nodeInfos <- sequence(nodeIds.flatten.distinct) { nodeId =>
          nodeInfoService.getNodeInfo(nodeId)
        }
        cf2 = logger.trace("Fetched nodes infos : %s".format(nodeInfos))
        cardinalityOk <- {
          if(!sourceVariableSpec.multivalued && nodeInfos.size != 1)
            Failure("The parameterized value returned a list value not compatible with variable spec for %s (monovalued)"
                .format(sourceVariableSpec.name))
          else Full("OK")
        }
      } yield {
        targetAccessorName.toLowerCase match {
          case ID.value => nodeInfos.map( _.id.value)
          case HOSTNAME.value => nodeInfos.map( _.hostname)
          case ADMIN.value => nodeInfos.map( _.localAdministratorAccountName )
        }
      }
    } else {
      logger.error("Wrong accessor : %s".format(targetAccessorName))
      Failure("Unknown accessor name: %s".format(targetAccessorName))
    }
  }



   /**
    * Lookup the variable with name varName in RuleVal.id in crv.
    * Try to first lookup the values in cache, and if it is not yet present,
    * update it with the value from RuleVal.
    * If RuleVal does not have such a variable name, fails.
    * If the looked-up variable's values contain a parameterized value, fails.
    * @param sourceVariableSpec : the spec of the variable
    * @param targetConfiguRuleId : the configuration rule id
    * @param varAccessorName : the name of the searched variable, lowercase !
    * @return
    */
  private[this] def lookupVariableParameter(sourceVariableSpec:VariableSpec, targetConfiguRuleId:RuleId, varAccessorName:String) : Box[Seq[String]] = {
     for {
       crv <- ruleValService.findRuleVal(targetConfiguRuleId)
       variables = crv.directiveVals.map(x => x.variables.map { case (name, variable) =>
                       ( name.toLowerCase, variable) // need to lower the variable i'm looking for
                     }.get(varAccessorName)).filter(x => x != None).flatten
       exists <- {
         if(variables.size == 0){
           logger.error("Can not lookup variable %s for configuration rule %s.".format(varAccessorName, crv.ruleId))
           Failure("Can not lookup variable %s for rule %s.".format(varAccessorName, crv.ruleId))
         } else Full("OK")
       }
       values <- Full(variables.flatten(x => x.values))

       okMonovalued <- {
         if(!sourceVariableSpec.multivalued && values.size != 1) Failure("More than one value looked-up for parameterized variable %s".format(sourceVariableSpec.name))
         else Full("OK")
       }
       okNoParameterizedVariables <- {
         if(containsParameterizedValue(values)) Failure("A parameterized value for variable %s in rule %s is parameterized and is used as a target of another rule. That is not supported".format(sourceVariableSpec.name, crv.ruleId))
         else Full("OK")
       }
     } yield {
       values
     }
  }

  private[this] def containsParameterizedValue(values:Seq[String]) : Boolean = {
    val regex = """\$\{rudder\.*\}""".r
    values.foreach {
      case regex() => return true
      case _ => //continue
    }
    false
  }

}



