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

package com.normation.rudder.domain

import com.normation.utils.Utils
import com.normation.inventory.ldap.core.LDAPConstants._
import net.liftweb.common.Loggable
import com.normation.cfclerk.domain.Variable

object RudderLDAPConstants extends Loggable {

  /* UUIDs */
  val A_NODE_POLICY_SERVER = "policyServerId"
  val A_TARGET_NODE_POLICY_SERVER = "targetPolicyServerId"
  val A_DIRECTIVE_UUID = "directiveId"
  val A_TARGET_DIRECTIVE_UUID = "targetDirectiveId"
  val A_GROUP_CATEGORY_UUID = "groupCategoryId"
  val A_TECHNIQUE_CATEGORY_UUID = "techniqueCategoryId"
  val A_TECHNIQUE_UUID = "techniqueId"
  val A_ACTIVE_TECHNIQUE_UUID = "activeTechniqueId"
  val A_NODE_GROUP_UUID = "nodeGroupId"
  val A_RULE_UUID = "ruleId"
    
  /* other things */
  val A_TECHNIQUE_LIB_VERSION = "techniqueLibraryVersion"
  val A_TECHNIQUE_VERSION = "techniqueVersion"
  val A_INIT_DATETIME = "initTimestamp"
  val A_QUERY_NODE_GROUP = "jsonNodeGroupQuery"
  val A_RULE_TARGET = "ruleTarget"
  val A_LAST_UPDATE_DATE = "lastUpdateTimestamp"
  val A_DIRECTIVE_VARIABLES = "directiveVariable"
  val A_WRITTEN_DATE = "writtenTimestamp"
  /*
   * Last time a User Policy Template was 
   * accepted in the user library (either the
   * time it was created (initialization) or the
   * last time the reference policy template library
   * was updated and then modification were accepted. 
   */
  val A_ACCEPTATION_DATETIME = "acceptationTimestamp"

  val A_SERVER_IS_MODIFIED = "isModified"
  /* does the given object is activated ? Boolean expected */
  val A_IS_ENABLED = "isEnabled"
  val A_IS_DYNAMIC = "isDynamic"
  /* is it a system object ? boolean */
  val A_IS_SYSTEM = "isSystem"
  val A_IS_BROKEN = "isBroken"
  val A_IS_POLICY_SERVER = "isPolicyServer"


  val A_PRIORITY = "directivePriority"
  val A_LONG_DESCRIPTION = "longDescription"
  val A_SERIAL = "serial"
    
  val A_NODE_CONFIGURATION_SYSTEM_VARIABLE = "systemVariable"
  val A_NODE_CONFIGURATION_TARGET_SYSTEM_VARIABLE = "targetSystemVariable"


  // Details about a NodeConfiguration
  val A_TARGET_NAME = "targetName"
  val A_TARGET_HOSTNAME = "targetNodeHostname"
  val A_TARGET_AGENTS_NAME = "targetAgentName"
  val A_TARGET_ROOT_USER = "targetLocalAdministratorAccountName"
 

  // Creation date of an object
  // it's an operational attribute of OpenLDAP
  val A_OBJECT_CREATION_DATE = "createTimestamp"

   
  //
  // Object Classe names
  //
  val OC_RUDDER_NODE = "rudderNode"
  val OC_POLICY_SERVER_NODE = "rudderPolicyServer"
  val OC_TECHNIQUE_CATEGORY = "techniqueCategory"
  val OC_GROUP_CATEGORY = "groupCategory"
  val OC_RUDDER_NODE_GROUP = "nodeGroup"
  val OC_SPECIAL_TARGET = "specialRuleTarget"
  val OC_ROOT_POLICY_SERVER = "rootPolicyServerNodeConfiguration"
  val OC_ACTIVE_TECHNIQUE = "activeTechnique"
  val OC_DIRECTIVE = "directive" 
  val OC_RULE = "rule"
  val OC_ACTIVE_TECHNIQUE_LIB_VERSION = "activeTechniqueLibraryVersion"
  val OC_ABSTRACT_RULE_WITH_CF3POLICYDRAFT = "abstractDirectiveNodeConfiguration"
  val OC_RULE_WITH_CF3POLICYDRAFT = "directiveNodeConfiguration"
  val OC_TARGET_RULE_WITH_CF3POLICYDRAFT = "targetDirectiveNodeConfiguration"
  val OC_NODE_CONFIGURATION = "nodeConfiguration" //actually a node configuration, not a "rudder server"

  OC += (OC_SPECIAL_TARGET,
    must = Set(A_RULE_TARGET, A_NAME),
    may = Set(A_DESCRIPTION, A_IS_ENABLED, A_IS_SYSTEM))

  OC += (OC_RULE,
    must = Set(A_RULE_UUID),
    may = Set(A_NAME, A_DESCRIPTION, A_LONG_DESCRIPTION, A_IS_ENABLED, A_IS_SYSTEM, A_RULE_TARGET, A_DIRECTIVE_UUID, A_SERIAL))

  OC += (OC_RUDDER_NODE,
    must = Set(A_NODE_UUID, A_NAME, A_IS_BROKEN, A_IS_SYSTEM),
    may = Set(A_DESCRIPTION))

  OC += (OC_POLICY_SERVER_NODE, sup = OC(OC_RUDDER_NODE),
    must = Set(A_HOSTNAME, A_PKEYS, A_LIST_OF_IP, A_INVENTORY_DATE,
      A_ROOT_USER, A_AGENTS_NAME, A_NODE_POLICY_SERVER),

    may = Set(A_DESCRIPTION))

  OC += (OC_DIRECTIVE,
    must = Set(A_DIRECTIVE_UUID),
    may = Set(A_NAME, A_DESCRIPTION, A_LONG_DESCRIPTION,
      A_PRIORITY, A_IS_ENABLED, A_DIRECTIVE_VARIABLES, A_IS_SYSTEM))

  OC += (OC_ACTIVE_TECHNIQUE,
    must = Set(A_ACTIVE_TECHNIQUE_UUID, A_TECHNIQUE_UUID),
    may = Set(A_IS_ENABLED, A_IS_SYSTEM))

  OC += (OC_TECHNIQUE_CATEGORY,
    must = Set(A_TECHNIQUE_CATEGORY_UUID, A_NAME, A_IS_SYSTEM),
    may = Set(A_DESCRIPTION))

  OC += (OC_GROUP_CATEGORY,
    must = Set(A_GROUP_CATEGORY_UUID, A_NAME, A_IS_SYSTEM),
    may = Set(A_DESCRIPTION))

  OC += (OC_RUDDER_NODE_GROUP,
    must = Set(A_NODE_GROUP_UUID, A_NAME, A_IS_DYNAMIC),
    may = Set(A_NODE_UUID, A_DESCRIPTION, A_QUERY_NODE_GROUP, A_IS_SYSTEM, A_IS_ENABLED))

  OC += (OC_NODE_CONFIGURATION,
    must = Set(A_NODE_UUID, A_IS_POLICY_SERVER),
    may = Set(A_DESCRIPTION, A_SERVER_IS_MODIFIED,
      A_NAME, A_HOSTNAME, A_NODE_POLICY_SERVER, A_ROOT_USER, A_AGENTS_NAME,
      A_TARGET_NAME, A_TARGET_HOSTNAME, A_TARGET_NODE_POLICY_SERVER, A_TARGET_ROOT_USER, A_TARGET_AGENTS_NAME,
      A_NODE_CONFIGURATION_SYSTEM_VARIABLE, A_NODE_CONFIGURATION_TARGET_SYSTEM_VARIABLE, A_WRITTEN_DATE))

  OC += (OC_ROOT_POLICY_SERVER, sup = OC(OC_NODE_CONFIGURATION))

  OC += (OC_ABSTRACT_RULE_WITH_CF3POLICYDRAFT,
    must = Set(A_NAME, A_LAST_UPDATE_DATE),
    may = Set(A_DESCRIPTION, A_RULE_TARGET, A_DIRECTIVE_VARIABLES,
              A_IS_SYSTEM, A_IS_ENABLED))

  OC += (OC_RULE_WITH_CF3POLICYDRAFT, sup = OC(OC_ABSTRACT_RULE_WITH_CF3POLICYDRAFT),
    must = Set(A_DIRECTIVE_UUID, A_RULE_UUID, A_PRIORITY, A_SERIAL))

  OC += (OC_TARGET_RULE_WITH_CF3POLICYDRAFT, sup = OC(OC_ABSTRACT_RULE_WITH_CF3POLICYDRAFT),
    must = Set(A_TARGET_DIRECTIVE_UUID, A_RULE_UUID, A_PRIORITY, A_SERIAL))

  OC += (OC_ACTIVE_TECHNIQUE_LIB_VERSION,
    may = Set(A_INIT_DATETIME))

  /**
   * Serialize and unserialize variables in A_DIRECTIVE_VARIABLES
   */
  val VSEP = ":"
  // '(?m)' => multiligne
  // '(?s)' => . matches \n
  val policyVariableRegex = """(?m)(?s)([^\[]+)\[(\d+)\]:(.+)""".r

  val mayBeEmptyPolicyVariableRegex = """(?m)(?s)([^\[]+)\[(\d+)\]:(.*)""".r

  /**
   * Parsing/printing variables
   * When parsing, we can't check the consistency of the fact that the variable may be empty, or not
   */
  def parsePolicyVariable(variable: String): Option[(String, Int, String)] = Utils.??!(variable) match {
    case None =>
      logger.error("Can not process a null or empty variable, skip")
      None
    case Some(v) => v match {
      case mayBeEmptyPolicyVariableRegex(name, i, value) => try {
        Some((name, i.toInt, value))
      } catch {
        case e: NumberFormatException =>
          logger.error("Error when trying to parse variable '%s': '%s' is not convertible to an integer. That variable will be ignored".format(v, i))
          None
      }
      case _ =>
        logger.error("Can not parse variable '%s', bad pattern. That variable will be ignored".format(v))
        None
    }
  }

  /**
   * Parse a list of string -typically all values of an attribute
   * into a Map[String,Seq[String]] of Policy Variables
   */
  def parsePolicyVariables(variables: Seq[String]): Map[String, Seq[String]] = {
    variables.
      collect { Utils.toPartial(parsePolicyVariable _) }.
      groupBy { case (x, i, y) => x }.
      map {
        case (k, seq) =>
          //Here, seq is sequence of (varName,index,value)
          /*
         * Normally, all varName in the seq are the same (and the same as k), given the groupBy.
         * Now, we want to create a seq the size of the higher index+1, and put values to the 
         * good index.
         * 
         * We initialize the seq with empty Strings. 
         */
          val size = seq.map { case (_, i, _) => i }.sortWith(_ > _).head + 1
          val values = Array.fill[String](size)("")

          seq.foreach {
            case (_, i, v) =>
              values(i) = v
          }

          (k, values.toSeq)
      }.toMap
  }

  /**
   * Serialize a variable into the expected format
   * for an LDAP attribute value.
   */
  def policyVariableToString(name: String, index: Int, value: String, mayBeEmpty: Boolean = false) = {
    require(Utils.nonEmpty(name), "Policy variable name can not be null")
    if (!mayBeEmpty) {
      require(Utils.nonEmpty(value), "Policy variable value can not be empty (it is for variable %s)".format(name))
    }
    require(index >= 0, "Index of policy variable must be positive (index=%s for variable %s, value %)".format(index, name, value))
    val v = "%s[%s]%s%s".format(name.trim, index, VSEP, value.trim)
    if (mayBeEmpty) {
      require(mayBeEmptyPolicyVariableRegex.findFirstIn(v).isDefined,
        "Serialization of variable leads to uncompatible pattern. Serialized value: %s does not matches %s . Perhaps some forbiden chars were used in the variable".format(v, mayBeEmptyPolicyVariableRegex.toString))
    } else {
      require(policyVariableRegex.findFirstIn(v).isDefined,
        "Serialization of variable leads to uncompatible pattern. Serialized value: %s does not matches %s . Perhaps some forbiden chars were used in the variable".format(v, policyVariableRegex.toString))
    }
    v
  }

  /**
   * Serialize a map of variables into a sequence of string in
   * the expected format for an LDAP attribute values.
   * This method should really not be used anymore, however as long as the Directive doesn't hold Variables,
   * we have to keep it.
   * consistency is partially checked.
   */
  def policyVariableToSeq(targets: Map[String, Seq[String]]): Seq[String] = {
    targets.flatMap {
      case (k, seq) =>
        seq.zipWithIndex.map {
          case (s, i) =>
            policyVariableToString(k, i, s, true)
        }
    }.toSeq
  }

  /**
   * Serialize a seq of variables into a sequence of string in
   * the expected format for an LDAP attribute values, an check the consistency of
   * the optionnable variable
   */
  def variableToSeq(targets: Seq[Variable]): Seq[String] = {
    targets.flatMap {
      case v: Variable => v.values.zipWithIndex.map {
        case (s, i) =>
          if (!v.spec.isSystem) v.spec.constraint.check(s, v.spec.description)
          policyVariableToString(v.spec.name, i, s, v.spec.constraint.mayBeEmpty)
      }
    }.toSeq
  }
}