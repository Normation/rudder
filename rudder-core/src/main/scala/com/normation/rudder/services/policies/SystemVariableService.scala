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

import net.liftweb.common.EmptyBox
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.repository.LicenseRepository
import com.normation.cfclerk.domain._
import com.normation.rudder.domain.nodes.NodeInfo
import net.liftweb.common.Box
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain._
import net.liftweb.common._
import org.slf4j.{ Logger, LoggerFactory }
import com.normation.rudder.exceptions.LicenseException
import com.normation.cfclerk.services.SystemVariableSpecService
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.Rule

trait SystemVariableService {
  def getSystemVariables(nodeInfo: NodeInfo, allNodeInfos: Set[NodeInfo], groupLib: FullNodeGroupCategory, directiveLib: FullActiveTechniqueCategory, allRules: Map[RuleId, Rule]): Box[Map[String, Variable]]
}

class SystemVariableServiceImpl(
    licenseRepository               : LicenseRepository
  , parameterizedValueLookupService : ParameterizedValueLookupService
  , systemVariableSpecService       : SystemVariableSpecService
  , toolsFolder                     : String
  , cmdbEndPoint                    : String
  , communityPort                   : Int
  , sharedFilesFolder               : String
  , webdavUser                      : String
  , webdavPassword                  : String
  , syslogPort                      : Int
    //denybadclocks and skipIdentify are runtime properties
  , getDenyBadClocks                : () => Box[Boolean]
  , getSkipIdentify                 : () => Box[Boolean]
) extends SystemVariableService with Loggable {

  val varToolsFolder = systemVariableSpecService.get("TOOLS_FOLDER").toVariable().copyWithSavedValue(toolsFolder)
  val varCmdbEndpoint = systemVariableSpecService.get("CMDBENDPOINT").toVariable().copyWithSavedValue(cmdbEndPoint)
  val varWebdavUser = systemVariableSpecService.get("DAVUSER").toVariable().copyWithSavedValue(webdavUser)
  val varWebdavPassword = systemVariableSpecService.get("DAVPASSWORD").toVariable().copyWithSavedValue(webdavPassword)
  val varSharedFilesFolder = systemVariableSpecService.get("SHARED_FILES_FOLDER").toVariable().copyWithSavedValue(sharedFilesFolder)
  val varCommunityPort = systemVariableSpecService.get("COMMUNITYPORT").toVariable().copyWithSavedValue(communityPort.toString)
  val syslogPortConfig = systemVariableSpecService.get("SYSLOGPORT").toVariable().copyWithSavedValue(syslogPort.toString)

  // allNodeInfos has to contain ALL the node info (those of every node within Rudder)
  // for this method to work properly
  def getSystemVariables(nodeInfo: NodeInfo, allNodeInfos: Set[NodeInfo], groupLib: FullNodeGroupCategory, directiveLib: FullActiveTechniqueCategory, allRules: Map[RuleId, Rule]): Box[Map[String, Variable]] = {
    logger.debug("Preparing the system variables for server %s".format(nodeInfo.id.value))

    // Set the roles of the nodes
    val nodeConfigurationRoles = collection.mutable.Set[String]()

    if(nodeInfo.isPolicyServer) {
      nodeConfigurationRoles.add("policy_server")
      if (nodeInfo.id == nodeInfo.policyServerId) {
        nodeConfigurationRoles.add("root_server")
      }
    }

    val varNodeRoleValue = if (nodeConfigurationRoles.size > 0) {
      "  classes: \n" + nodeConfigurationRoles.map(x => "    \"" + x + "\" expression => \"any\";").mkString("\n")
    } else {
      "# This node doesn't have any specific role"
    }
    val varNodeRole = systemVariableSpecService.get("NODEROLE").toVariable().copyWithSavedValue(varNodeRoleValue)

    // Set the licences for the Nova
    val varLicensesPaidValue = if (nodeInfo.agentsName.contains(NOVA_AGENT)) {
      licenseRepository.findLicense(nodeInfo.policyServerId) match {
        case None =>
          logger.warn("Caution, the policy server %s does not have a registered Nova license".format(nodeInfo.policyServerId.value))
          throw new LicenseException("No license found for the policy server " + nodeInfo.policyServerId.value)
        case Some(x) => x.licenseNumber.toString
      }
    } else {
      "1"
    }
    val varLicensesPaid = systemVariableSpecService.get("LICENSESPAID").toVariable().copyWithSavedValue(varLicensesPaidValue)


    var varClientList = systemVariableSpecService.get("CLIENTSLIST").toVariable()

    var varManagedNodes = systemVariableSpecService.get("MANAGED_NODES_NAME").toVariable()
    var varManagedNodesId = systemVariableSpecService.get("MANAGED_NODES_ID").toVariable()

    val allowConnect = collection.mutable.Set[String]()

    val clientList = collection.mutable.Set[String]()

    // If we are facing a policy server, we have to allow each children to connect, plus the policy parent,
    // else it's only the policy server
    if(nodeInfo.isPolicyServer) {
      val allowedNodeVarSpec = SystemVariableSpec(name = "${rudder.hasPolicyServer-" + nodeInfo.id.value + ".target.hostname}", description = "", multivalued = true)
      val allowedNodeVar = SystemVariable(allowedNodeVarSpec, Seq()).copyWithSavedValues(Seq("${rudder.hasPolicyServer-" + nodeInfo.id.value + ".target.hostname}"))

      parameterizedValueLookupService.lookupRuleParameterization(Seq(allowedNodeVar), allNodeInfos, groupLib, directiveLib, allRules) match {
        case Full(variable) =>
          allowConnect ++= variable.flatMap(x => x.values)
          clientList ++= variable.flatMap(x => x.values)
          varClientList = varClientList.copyWithSavedValues(clientList.toSeq)
        case Empty => logger.warn("No variable parametrized found for ${rudder.hasPolicyServer-" + nodeInfo.id.value + ".target.hostname}")
        case f: Failure =>
          val e = f ?~! "Failure when fetching the policy children"
          logger.error(e.messageChain)
          return e
      }

      // Find the "policy children" of this policy server
      // thanks to the allNodeInfos, this is super easy
      val children = allNodeInfos.filter(_.policyServerId == nodeInfo.id).toSeq
      varManagedNodes = varManagedNodes.copyWithSavedValues(children.map(_.hostname))
      varManagedNodesId = varManagedNodesId.copyWithSavedValues(children.map(_.id.value))
    }

    allNodeInfos.find( _.id == nodeInfo.policyServerId) match {
      case Some(policyServer) => allowConnect += policyServer.hostname
      case None =>
        val m = s"Couldn't find the policy server of node %s".format(nodeInfo.id.value)
        logger.error(m)
        return Failure(m)
    }

    val varAllowConnect = systemVariableSpecService.get("ALLOWCONNECT").toVariable().copyWithSavedValues(allowConnect.toSeq)

    //obtaining variable values from (failable) properties
    def getProp[T](specName: String, getter: () => Box[T]) : SystemVariable = {
      //try to get the user configured value, else log an error and use the default value.
      val variable = systemVariableSpecService.get(specName).toVariable()

      getter() match {
        case Full(value) =>
          variable.copyWithSavedValue(value.toString)
        case eb:EmptyBox =>
          val e = eb ?~! s"Error when trying to get the value configured by the user for system variable '${specName}'"
          logger.error(e.messageChain)
          e.rootExceptionCause.foreach { ex =>
            logger.error("Root exception cause was:" , ex)
          }
          variable
      }
    }

    val denyBadClocks = getProp("DENYBADCLOCKS", getDenyBadClocks)
    val skipIdentify = getProp("SKIPIDENTIFY", getSkipIdentify)

    logger.debug("System variables for server %s done".format(nodeInfo.id.value))

    Full(Map(
        (varNodeRole.spec.name, varNodeRole)
      , (varLicensesPaid.spec.name, varLicensesPaid)
      , (varToolsFolder.spec.name, varToolsFolder)
      , (varCmdbEndpoint.spec.name, varCmdbEndpoint)
      , (varSharedFilesFolder.spec.name, varSharedFilesFolder)
      , (varCommunityPort.spec.name, varCommunityPort)
      , (varAllowConnect.spec.name, varAllowConnect)
      , (varClientList.spec.name, varClientList)
      , (varManagedNodesId.spec.name, varManagedNodesId)
      , (varManagedNodes.spec.name, varManagedNodes)
      , (varWebdavUser.spec.name, varWebdavUser)
      , (varWebdavPassword.spec.name, varWebdavPassword)
      , (syslogPortConfig.spec.name, syslogPortConfig)
      , (denyBadClocks.spec.name, denyBadClocks)
      , (skipIdentify.spec.name, skipIdentify)
    ))

  }

}