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

import com.normation.rudder.repository.ConfigurationRuleRepository
import com.normation.rudder.domain.nodes.PolicyServerNodeInfo
import com.normation.rudder.services.nodes.NodeInfoService
import net.liftweb.common._
import com.normation.rudder.domain.policies.ConfigurationRuleId
import com.normation.inventory.domain.NodeId
import com.normation.cfclerk.domain.{VariableSpec, Variable}
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.utils.Control.sequence
import com.normation.rudder.domain.policies.PolicyInstanceTarget

/**
 * A service that handle parameterized value of
 * policy instance variables. 
 * 
 * The parameterization is to be taken in the context of
 * a configuration rule (i.e, a policy instance applied to
 * a target), and in the scope of one node of the target
 * (as if you were processing one node at a time).
 * 
 * The general parameterized value are of the form:
 * ${...}
 * were "..." is the parameter to lookup.
 * 
 * We handle 2 kinds of parameterizations:
 * 1/ ${CONFIG_RULE_ID.ACCESSOR}
 *    where:
 *    - CONFIG_RULE_ID is a valid id of a configuration rule in the system
 *    - ACCESSOR is an accessor for that configuration rule, explained below.
 * 2/ ${node.ACCESSOR}
 *    where: 
 *    - "node" is a keyword ;
 *    - ACCESSOR is an accessor for that node, explained below.
 *
 * Accessor are keywords which allows to reach value in a context, exactly like 
 * properties in object oriented programming. 
 *
 * Accessors for node
 * ------------------
 *   ${node.id} : internal ID of the node (generally an UUID)
 *   ${node.hostname} : hostname of the node
 *   ${node.admin} : login (or username) of the node administrator, or root, or at least 
 *                   the login to use to run the agent
 *   ${node.policyserver.ACCESSOR} : information about the policyserver of the node.
 *                                    ACCESSORs are the same than for ${node}
 * 
 * Accessors for configuration rule
 * --------------------------------
 * Accessor for configuration rule are of two forms:
 * - ${CONFIG_RULE_ID.VAR_NAME}
 *   where VAR_NAME is the name of a variable of the policy template implemented
 *   by the policy instance referenced by the given configuration rule ;
 *   The following constraint are applied on a VAR_NAME accessor:
 *   - if the variable is monovalued, the targeted variable must have only one value 
 *     (the target variable may be multivalued with exaclty one value) ;
 *   - if the variable is multivalued and is not part of a group, 
 *     there is no constraints on the target ;
 *   - if the variable is mutlivalued and is part of group, there is no constraints
 *     on the target **BUT** if other members of the group don't have the same
 *     cardinality than the result of the lookup, you will have problems. 
 *   
 * - ${CONFIG_RULE_ID.target.ACCESSOR}
 *   - where "target" is an accessor which change the context of following
 *     accessors to the target of the configuration rule (group, etc)
 *     Target Accessor are the same as node accessors, but are multivalued.
 * 
 * Important notice:
 * -----------------
 * For a given configuration rule, ALL parameterized value are processed in the 
 * same order, and order is kept.
 * 
 * That means  (for example) that if a parameterized value references two target accessors,
 * information regarding the same node will be put at the same index.
 * 
 */
trait ParameterizedValueLookupService {

  /**
   * Replace all parameterization of the form ${node.XXX} 
   * by their values
   * 
   * We are in the context of a node, given by the id.
   * We can provide a NodeInfo as cache for the node.
   * 
   */
  def lookupNodeParameterization(nodeId:NodeId, variables:Seq[Variable]) : Box[Seq[Variable]]

  /**
   * Replace all parameterization of the form
   * ${CONFGIGURATION_RULE_ID.XXX} by their values
   * 
   * TODO: handle cache !!
   * 
   */
  def lookupConfigurationRuleParameterization(variables:Seq[Variable]) : Box[Seq[Variable]]
  
  
  
  sealed abstract class AccessorName(val value:String)
  case object ID extends AccessorName("id")
  case object HOSTNAME extends AccessorName("hostname")
  case object ADMIN extends AccessorName("admin")
  
  def isValidAccessorName(accessor:String) : Boolean = accessor.toLowerCase match {
    case ID.value | HOSTNAME.value | ADMIN.value => true
    case _ => false
  }

  
  sealed trait Parametrization 

  case class BadParametrization(value:String) extends Parametrization
  
  abstract class NodeParametrization extends Parametrization

  abstract class NodePsParametrization extends Parametrization 
  object NodePsParametrization {
    def r = """\$\{node\.policyserver\..*\}""".r
  }
  case object ParamNodeId extends NodeParametrization {
    def r = """\$\{node\.id\}""".r
  }
  case object ParamNodeHostname extends NodeParametrization {
    def r = """\$\{node\.hostname\}""".r
  }
  case object ParamNodeAdmin extends NodeParametrization {
    def r = """\$\{node\.admin\}""".r
  }
  case object ParamNodePsId extends NodeParametrization {
    def r = """\$\{node\.policyserver\.id\}""".r
  }
  case object ParamNodePsHostname extends NodeParametrization {
    def r = """\$\{node\.policyserver\.hostname\}""".r
  }
  case object ParamNodePsAdmin extends NodeParametrization {
    def r = """\$\{node\.policyserver\.admin\}""".r
  }
  
  case object NodeParam extends NodeParametrization
  case class BadNodeParam(value:String) extends NodeParametrization

  abstract class CrParametrization extends Parametrization 

  case class CrVarParametrization(crName:String, accessor:String) extends CrParametrization
  case class CrTargetParametrization(crName:String, accessor:String) extends CrParametrization 
  object CrTargetParametrization {
    def r = """\$\{([\-_a-zA-Z0-9]+)\.target\.([\-_a-zA-Z0-9]+)\}""".r
  }

  

  object Parametrization {
    def r = """\$\{(.*)\}""".r
  }  
  
  object CrParametrization {
    def r = """\$\{([\-_a-zA-Z0-9]+)\.([\-_a-zA-Z0-9]+)\}""".r
    
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
  
  object NodeParametrization {
    def r = """\$\{node\..*\}""".r
    
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
    override val policyInstanceTargetService : PolicyInstanceTargetService,
    override val configurationRuleRepo : ConfigurationRuleRepository,
    override val configurationRuleValService : ConfigurationRuleValService
) extends ParameterizedValueLookupService with 
  ParameterizedValueLookupService_lookupNodeParameterization with 
  ParameterizedValueLookupService_lookupConfigurationRuleParameterization 

////////////////////////////////////////////////////////////////////////////////////////////////////


trait ParameterizedValueLookupService_lookupNodeParameterization extends ParameterizedValueLookupService {

  def nodeInfoService : NodeInfoService
  
  private[this] def lookupNodeVariable(nodeInfo : NodeInfo, policyServerInfo: => Box[PolicyServerNodeInfo], value:String) : Box[String] = {
    value match {
      case NodeParametrization(ParamNodeId) => Full(nodeInfo.id.value)
      case NodeParametrization(ParamNodeHostname) => Full(nodeInfo.hostname)
      case NodeParametrization(ParamNodeAdmin) => Full(nodeInfo.localAdministratorAccountName)
      case NodeParametrization(ParamNodePsId) => Full(nodeInfo.policyServerId.value)
      case NodeParametrization(ParamNodePsHostname) => policyServerInfo.map( _.hostname)
      case NodeParametrization(ParamNodePsAdmin) => policyServerInfo.map( _.localAdministratorAccountName)
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
            lookupNodeVariable(nodeInfo, nodeInfoService.getPolicyServerNodeInfo(nodeInfo.policyServerId), value)
          }
        } yield Variable.matchCopy(v, values)
      }
    } yield variables
  }
}




trait ParameterizedValueLookupService_lookupConfigurationRuleParameterization extends ParameterizedValueLookupService with Loggable {
  
   
  def policyInstanceTargetService : PolicyInstanceTargetService
  def nodeInfoService : NodeInfoService
  def configurationRuleRepo : ConfigurationRuleRepository
  def configurationRuleValService : ConfigurationRuleValService
  
  /**
   * Replace all parameterization of the form
   * ${CONFGIGURATION_RULE_ID.XXX} by their values
   */
  override def lookupConfigurationRuleParameterization(variables:Seq[Variable]) : Box[Seq[Variable]] = {
     sequence(variables) { variable =>
     logger.debug("Processing variable : %s".format(variable))
       (sequence(variable.values) { value =>
         value match {
           case CrParametrization(CrTargetParametrization(targetConfiguRuleId, targetAccessorName)) =>
             logger.debug("Processing configuration rule's parameterized value on target: %s".format(value))
             lookupTargetParameter(variable.spec, ConfigurationRuleId(targetConfiguRuleId), targetAccessorName)
           case CrParametrization(CrVarParametrization(targetConfiguRuleId, varAccessorName)) =>
             logger.debug("Processing configuration rule's parameterized value on variable: %s".format(value))
             lookupVariableParameter(variable.spec, ConfigurationRuleId(targetConfiguRuleId), varAccessorName)
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
    * Lookup the variable with name varName in ConfigurationRuleVal.id in crv.
    * Try to first lookup the values in cache, and if it is not yet present,
    * update it with the value from ConfigurationRuleVal.
    * If ConfigurationRuleVal does not have such a variable name, fails.
    * If the looked-up variable's values contain a parameterized value, fails.
    * @param crv
    * @param varName
    * @param cache
    * @return
    */
  private[this] def lookupTargetParameter(sourceVariableSpec:VariableSpec, targetConfiguRuleId:ConfigurationRuleId, targetAccessorName:String) : Box[Seq[String]] = {
    if(isValidAccessorName(targetAccessorName)) {
      for {
        configurationRule <- configurationRuleRepo.get(targetConfiguRuleId)
        cf = logger.trace("Fetched configuration rule : %s".format(configurationRule))
        target <- Box(configurationRule.target) ?~! "Missing target for configuration rule with ID %s. Can not lookup parameters for a not fully defined configuration rule". format(targetConfiguRuleId)
        nodeIds <- policyInstanceTargetService.getNodeIds(target)
        cf1 = logger.trace("Fetched nodes ids : %s".format(nodeIds))
        nodeInfos <- sequence(nodeIds) { nodeId =>
          nodeInfoService.getNodeInfo(nodeId)
        }
        cf2 = logger.trace("Fetched nodes infos : %s".format(nodeInfos))
        cardinalityOk <- { 
          if(!sourceVariableSpec.multivalued && nodeInfos.size != 1) Failure("The parameterized value returned a list value not compatible with variable spec for %s (monovalued)".format(sourceVariableSpec.name))
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
    * Lookup the variable with name varName in ConfigurationRuleVal.id in crv.
    * Try to first lookup the values in cache, and if it is not yet present,
    * update it with the value from ConfigurationRuleVal.
    * If ConfigurationRuleVal does not have such a variable name, fails.
    * If the looked-up variable's values contain a parameterized value, fails.
    * @param sourceVariableSpec : the spec of the variable
    * @param targetConfiguRuleId : the configuration rule id
    * @param varAccessorName : the name of the searched variable, lowercase !
    * @return
    */
  private[this] def lookupVariableParameter(sourceVariableSpec:VariableSpec, targetConfiguRuleId:ConfigurationRuleId, varAccessorName:String) : Box[Seq[String]] = {
     for {
       crv <- configurationRuleValService.findConfigurationRuleVal(targetConfiguRuleId)
       variables = crv.policies.map{ x =>
                     x.variables.map { case (name, variable) =>
                       ( name.toLowerCase, variable) // need to lower the variable i'm looking for
                     }.get(varAccessorName)
                  }.filter(x => x != None).flatten
       exists <- {
         if(variables.size == 0) {
           logger.error("Can not lookup variable %s for configuration rule %s.".format(
                                                      varAccessorName, crv.configurationRuleId))
           Failure("Can not lookup variable %s for configuration rule %s.".format(
                                                      varAccessorName, crv.configurationRuleId))
         } else {
           Full("OK") 
         }  
       }
       values <- Full(variables.flatten(x => x.values)) 
       
       okMonovalued <- {
         if(!sourceVariableSpec.multivalued && values.size != 1) Failure("More than one value looked-up for parameterized variable %s".format(sourceVariableSpec.name))
         else Full("OK")
       }
       okNoParameterizedVariables <- {
         if(containsParameterizedValue(values)) Failure("A parameterized value for variable %s in configuration rule %s is parameterized and is used as a target of another configuration rule. That is not supported".format(sourceVariableSpec.name, crv.configurationRuleId))
         else Full("OK") 
       }
     } yield {
       values
     }
  }
  
  private[this] def containsParameterizedValue(values:Seq[String]) : Boolean = {
    val regex = """\$\{*\}""".r
    values.foreach { 
      case regex() => return true
      case _ => //continue
    }
    false
  }

}



