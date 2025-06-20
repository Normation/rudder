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

package com.normation.rudder.domain

import com.normation.cfclerk.domain.Variable
import com.normation.inventory.ldap.core.LDAPConstants.*
import com.normation.rudder.services.policies.ParameterForConfiguration
import com.normation.utils.Utils
import net.liftweb.common.Loggable
import scala.util.matching.Regex

object RudderLDAPConstants extends Loggable {

  /* UUIDs */
  val A_NODE_POLICY_SERVER      = "policyServerId"
  val A_DIRECTIVE_UUID          = "directiveId"
  val A_TARGET_DIRECTIVE_UUID   = "targetDirectiveId"
  val A_GROUP_CATEGORY_UUID     = "groupCategoryId"
  val A_RULE_CATEGORY_UUID      = "ruleCategoryId"
  val A_TECHNIQUE_CATEGORY_UUID = "techniqueCategoryId"
  val A_TECHNIQUE_UUID          = "techniqueId"
  val A_ACTIVE_TECHNIQUE_UUID   = "activeTechniqueId"
  val A_NODE_GROUP_UUID         = "nodeGroupId"
  val A_RULE_UUID               = "ruleId"

  /* other things */
  val A_TECHNIQUE_LIB_VERSION = "techniqueLibraryVersion"
  val A_TECHNIQUE_VERSION     = "techniqueVersion"
  val A_INIT_DATETIME         = "initTimestamp"
  val A_QUERY_NODE_GROUP      = "jsonNodeGroupQuery"
  val A_RULE_TARGET           = "ruleTarget"
  val A_LAST_UPDATE_DATE      = "lastUpdateTimestamp"
  val A_DIRECTIVE_VARIABLES   = "directiveVariable"
  val A_RULE_CATEGORY         = "tag"

  /*
   * Last time an active technique was
   * accepted in the active technique library (either the
   * time it was created (initialization) or the
   * last time the reference Technique library
   * was updated and then modification were accepted.
   */
  val A_ACCEPTATION_DATETIME = "acceptationTimestamp"

  val A_SERVER_IS_MODIFIED = "isModified"
  /* does the given object is activated ? Boolean expected */
  val A_IS_ENABLED         = "isEnabled"
  val A_IS_DYNAMIC         = "isDynamic"
  /* is it a system object ? boolean */
  val A_IS_SYSTEM          = "isSystem"
  val A_POLICY_TYPES       = "policyTypes"
  val A_STATE              = "state"
  val A_IS_POLICY_SERVER   = "isPolicyServer"

  val A_SERIALIZED_AGENT_RUN_INTERVAL          = "serializedAgentRunInterval"
  val A_SERIALIZED_HEARTBEAT_RUN_CONFIGURATION = "serializedHeartbeatRunConfiguration"
  val A_AGENT_REPORTING_PROTOCOL               = "agentReportingProtocol"

  val A_SECURITY_TAG = "securityTag"
  val A_POLICY_MODE  = "policyMode"

  val A_NODE_PROPERTY = "serializedNodeProperty"
  val A_JSON_PROPERTY = "serializedProperty"

  val A_PRIORITY         = "directivePriority"
  val A_LONG_DESCRIPTION = "longDescription"
  // val A_SERIAL = "serial" - removed in 4.3 but kept in schema for compatibility reason

  // Creation date of an object
  // it's an operational attribute of OpenLDAP
  // see A_CREATION_DATETIME for API token
  val A_OBJECT_CREATION_DATE = "createTimestamp"

  // API Token related
  val A_API_UUID                    = "apiAccountId"
  val A_CREATION_DATETIME           = "creationTimestamp" // not operationnal, can be different from ldap entry value
  val A_API_KIND                    = "apiAccountKind"
  val A_API_TOKEN                   = "apiToken"
  val A_API_TOKEN_CREATION_DATETIME = "apiTokenCreationTimestamp"
  val A_API_EXPIRATION_DATETIME     = "expirationTimestamp"
  val A_API_AUTHZ_KIND              = "apiAuthorizationKind"
  val A_API_ACL                     = "apiAcl"
  val A_API_TENANT                  = "apiTenant"

  // Parameters
  val A_PARAMETER_NAME  = "parameterName"
  val A_PARAMETER_VALUE = "parameterValue"

  // Web properties
  val A_PROPERTY_NAME  = "propertyName"
  val A_PROPERTY_VALUE = "propertyValue"

  // property provider
  val A_PROPERTY_PROVIDER = "propertyProvider"
  val A_INHERIT_MODE      = "inheritMode"
  val A_VISIBILITY        = "visibility"

  // node configuration hashes
  val A_NODE_CONFIG = "nodeConfig"

  // key=value tags, in JSON
  val A_SERIALIZED_TAGS = "serializedTags"

  //
  // Object Classe names
  //
  val OC_RUDDER_NODE                  = "rudderNode"
  val OC_POLICY_SERVER_NODE           = "rudderPolicyServer"
  val OC_TECHNIQUE_CATEGORY           = "techniqueCategory"
  val OC_GROUP_CATEGORY               = "groupCategory"
  val OC_RULE_CATEGORY                = "ruleCategory"
  val OC_RUDDER_NODE_GROUP            = "nodeGroup"
  val OC_SPECIAL_TARGET               = "specialRuleTarget"
  val OC_ACTIVE_TECHNIQUE             = "activeTechnique"
  val OC_DIRECTIVE                    = "directive"
  val OC_RULE                         = "rule"
  val OC_ACTIVE_TECHNIQUE_LIB_VERSION = "activeTechniqueLibraryVersion"
  val OC_PARAMETER                    = "parameter"
  val OC_PROPERTY                     = "property"
  val OC_NODES_CONFIG                 = "nodeConfigurations"

  val OC_API_ACCOUNT = "apiAccount"

  OC.createObjectClass(OC_SPECIAL_TARGET, must = Set(A_RULE_TARGET, A_NAME), may = Set(A_DESCRIPTION, A_IS_ENABLED, A_IS_SYSTEM))

  OC.createObjectClass(
    OC_RULE,
    must = Set(A_RULE_UUID),
    may = Set(
      A_NAME,
      A_DESCRIPTION,
      A_LONG_DESCRIPTION,
      A_IS_ENABLED,
      A_IS_SYSTEM,
      A_RULE_TARGET,
      A_DIRECTIVE_UUID,
      "serial",
      A_RULE_CATEGORY,
      A_SERIALIZED_TAGS
    )
  )

  OC.createObjectClass(
    OC_RUDDER_NODE,
    must = Set(A_NODE_UUID, A_NAME, A_STATE, A_IS_SYSTEM),
    may = Set(A_DESCRIPTION, A_SERIALIZED_AGENT_RUN_INTERVAL, A_SERIALIZED_HEARTBEAT_RUN_CONFIGURATION, A_NODE_PROPERTY)
  )

  OC.createObjectClass(
    OC_POLICY_SERVER_NODE,
    sup = OC(OC_RUDDER_NODE),
    must = Set(A_HOSTNAME, A_LIST_OF_IP, A_INVENTORY_DATE, A_ROOT_USER, A_AGENT_NAME, A_NODE_POLICY_SERVER),
    may = Set(A_DESCRIPTION)
  )

  OC.createObjectClass(
    OC_DIRECTIVE,
    must = Set(A_DIRECTIVE_UUID),
    may = Set(
      A_NAME,
      A_DESCRIPTION,
      A_LONG_DESCRIPTION,
      A_PRIORITY,
      A_IS_ENABLED,
      A_DIRECTIVE_VARIABLES,
      A_IS_SYSTEM,
      A_SERIALIZED_TAGS
    )
  )

  OC.createObjectClass(
    OC_ACTIVE_TECHNIQUE,
    must = Set(A_ACTIVE_TECHNIQUE_UUID, A_TECHNIQUE_UUID),
    may = Set(A_IS_ENABLED, A_IS_SYSTEM)
  )

  OC.createObjectClass(
    OC_TECHNIQUE_CATEGORY,
    must = Set(A_TECHNIQUE_CATEGORY_UUID, A_NAME, A_IS_SYSTEM),
    may = Set(A_DESCRIPTION)
  )

  OC.createObjectClass(OC_GROUP_CATEGORY, must = Set(A_GROUP_CATEGORY_UUID, A_NAME, A_IS_SYSTEM), may = Set(A_DESCRIPTION))

  OC.createObjectClass(OC_RULE_CATEGORY, must = Set(A_RULE_CATEGORY_UUID, A_NAME), may = Set(A_DESCRIPTION, A_IS_SYSTEM))

  OC.createObjectClass(
    OC_RUDDER_NODE_GROUP,
    must = Set(A_NODE_GROUP_UUID, A_NAME, A_IS_DYNAMIC),
    may = Set(A_NODE_UUID, A_DESCRIPTION, A_QUERY_NODE_GROUP, A_IS_SYSTEM, A_IS_ENABLED, A_JSON_PROPERTY)
  )

  OC.createObjectClass(OC_ACTIVE_TECHNIQUE_LIB_VERSION, may = Set(A_INIT_DATETIME))

  OC.createObjectClass(
    OC_API_ACCOUNT,
    must = Set(A_API_UUID, A_NAME, A_CREATION_DATETIME, A_API_TOKEN, A_API_TOKEN_CREATION_DATETIME),
    may = Set(A_DESCRIPTION, A_API_EXPIRATION_DATETIME, A_API_ACL, A_IS_ENABLED, A_API_AUTHZ_KIND)
  )

  OC.createObjectClass(
    OC_PARAMETER,
    must = Set(A_PARAMETER_NAME),
    may = Set(A_PARAMETER_VALUE, A_DESCRIPTION, A_PROPERTY_PROVIDER)
  )

  OC.createObjectClass(OC_PROPERTY, must = Set(A_PROPERTY_NAME), may = Set(A_PROPERTY_VALUE, A_DESCRIPTION))

  OC.createObjectClass(OC_NODES_CONFIG, must = Set(A_NAME), may = Set(A_DESCRIPTION, A_NODE_CONFIG))

  /**
   * Serialize and unserialize variables in A_DIRECTIVE_VARIABLES
   */
  val VSEP = ":"
  // '(?m)' => multiligne
  // '(?s)' => . matches \n
  val policyVariableRegex: Regex = """(?m)(?s)([^\[]+)\[(\d+)\]:(.+)""".r

  val mayBeEmptyPolicyVariableRegex: Regex = """(?m)(?s)([^\[]+)\[(\d+)\]:(.*)""".r

  /**
   * Parsing/printing variables
   * When parsing, we can't check the consistency of the fact that the variable may be empty, or not
   */
  def parsePolicyVariable(variable: String): Option[(String, Int, String)] = variable match {
    case null | ""                                     =>
      logger.error("Can not process a null or empty variable, skip")
      None
    case mayBeEmptyPolicyVariableRegex(name, i, value) =>
      try {
        Some((name, i.toInt, value))
      } catch {
        case e: NumberFormatException =>
          logger.error(
            s"Error when trying to parse variable '${variable}': '${i}' is not convertible to an integer. That variable will be ignored"
          )
          None
      }
    case _                                             =>
      logger.error(s"Can not parse variable '${variable}', bad pattern. That variable will be ignored")
      None
  }

  /**
   * Parse a list of string -typically all values of an attribute
   * into a Map[String,Seq[String]] of Policy Variables
   */
  def parsePolicyVariables(variables: Seq[String]): Map[String, Seq[String]] = {

    /**
     * Change a function: f:A => Option[B] in a
     * partial function A => B
     */
    def toPartial[A, B](f: A => Option[B]) = new PartialFunction[A, B] {
      override def isDefinedAt(x: A) = f(x).isDefined
      override def apply(x:       A) = f(x).get
    }

    variables
      .collect(toPartial(parsePolicyVariable _))
      .groupBy { case (x, i, y) => x }
      .map {
        case (k, seq) =>
          // Here, seq is sequence of (varName,index,value)
          /*
           * Normally, all varName in the seq are the same (and the same as k), given the groupBy.
           * Now, we want to create a seq the size of the higher index+1, and put values to the
           * good index.
           *
           * We initialize the seq with empty Strings.
           */
          val size   = seq.map { case (_, i, _) => i }.sortWith(_ > _).head + 1
          val values = Array.fill[String](size)("")

          seq.foreach {
            case (_, i, v) =>
              values(i) = v
          }

          (k, values.toSeq)
      }
      .toMap
  }

  /**
   * Serialize a variable into the expected format
   * for an LDAP attribute value.
   */
  def policyVariableToString(name: String, index: Int, value: String, mayBeEmpty: Boolean = false): String = {
    require(!Utils.isEmpty(name), "Policy variable name can not be null")
    if (!mayBeEmpty) {
      require(!Utils.isEmpty(value), "Policy variable value can not be empty (it is for variable %s)".format(name))
    }
    require(
      index >= 0,
      "Index of policy variable must be positive (index=%s for variable %s, value %)".format(index, name, value)
    )
    val v = "%s[%s]%s%s".format(name.trim, index, VSEP, value.trim)
    if (mayBeEmpty) {
      require(
        mayBeEmptyPolicyVariableRegex.findFirstIn(v).isDefined,
        "Serialization of variable leads to uncompatible pattern. Serialized value: %s does not matches %s . Perhaps some forbiden chars were used in the variable"
          .format(v, mayBeEmptyPolicyVariableRegex.toString)
      )
    } else {
      require(
        policyVariableRegex.findFirstIn(v).isDefined,
        "Serialization of variable leads to uncompatible pattern. Serialized value: %s does not matches %s . Perhaps some forbiden chars were used in the variable"
          .format(v, policyVariableRegex.toString)
      )
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
            policyVariableToString(k, i, s, mayBeEmpty = true)
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
      case v: Variable =>
        v.values.zipWithIndex.map {
          case (s, i) =>
            if (!v.spec.isSystem) v.spec.constraint.check(s, v.spec.description)
            policyVariableToString(v.spec.name, i, s, v.spec.constraint.mayBeEmpty)
        }
    }.toSeq
  }

  /**
   * Serialize the parameters into a sequence of string in
   * the expected format for an LDAP attribute values
   */
  def parametersToSeq(targets: Seq[ParameterForConfiguration]): Seq[String] = {
    targets.map(p => policyVariableToString(p.name, 0, p.value, mayBeEmpty = true))
  }

}
