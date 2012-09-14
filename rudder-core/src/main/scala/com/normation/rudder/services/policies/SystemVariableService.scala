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
import scala.collection._
import com.normation.rudder.exceptions.LicenseException
import com.normation.cfclerk.services.SystemVariableSpecService

trait SystemVariableService {
  def getSystemVariables(nodeInfo: NodeInfo): Box[Map[String, Variable]]
}

class SystemVariableServiceImpl(
    licenseRepository               : LicenseRepository
  , parameterizedValueLookupService : ParameterizedValueLookupService
  , systemVariableSpecService       : SystemVariableSpecService
  , nodeInfoService                 : NodeInfoService
  , toolsFolder                     : String
  , cmdbEndPoint                    : String
  , communityPort                   : String
  , sharedFilesFolder               : String
  , webdavUser                      : String
  , webdavPassword                  : String
  , syslogPort                      : Int
) extends SystemVariableService with Loggable {

  val varToolsFolder = SystemVariable(systemVariableSpecService.get("TOOLS_FOLDER"))
  varToolsFolder.saveValue(toolsFolder);

  val varCmdbEndpoint = SystemVariable(systemVariableSpecService.get("CMDBENDPOINT"))
  varCmdbEndpoint.saveValue(cmdbEndPoint)

  val varWebdavUser = SystemVariable(systemVariableSpecService.get("DAVUSER"))
  varWebdavUser.saveValue(webdavUser)

  val varWebdavPassword = SystemVariable(systemVariableSpecService.get("DAVPASSWORD"))
  varWebdavPassword.saveValue(webdavPassword)
  
  val varSharedFilesFolder = SystemVariable(systemVariableSpecService.get("SHARED_FILES_FOLDER"))
  varSharedFilesFolder.saveValue(sharedFilesFolder)

  val varCommunityPort = SystemVariable(systemVariableSpecService.get("COMMUNITYPORT"))
  varCommunityPort.saveValue(communityPort)

  val syslogPortConfig = SystemVariable(systemVariableSpecService.get("SYSLOGPORT"))
  syslogPortConfig.saveValue(syslogPort.toString)

  def getSystemVariables(nodeInfo: NodeInfo): Box[Map[String, Variable]] = {
    logger.debug("Preparing the system variables for server %s".format(nodeInfo.id.value))

    // Set the roles of the nodes
    val nodeConfigurationRoles = mutable.Set[String]()
    
    if(nodeInfo.isPolicyServer) {
      nodeConfigurationRoles.add("policy_server")
      if (nodeInfo.id == nodeInfo.policyServerId) {
        nodeConfigurationRoles.add("root_server")
      }
    }

    val varNodeRole = new SystemVariable(systemVariableSpecService.get("NODEROLE"))

    if (nodeConfigurationRoles.size > 0) {
      varNodeRole.saveValue("  classes: \n" + nodeConfigurationRoles.map(x => "    \"" + x + "\" expression => \"any\";").mkString("\n"))
    } else {
      varNodeRole.saveValue("# This node doesn't have any specific role")
    }
    
    // Set the licences for the Nova
    val varLicensesPaid = SystemVariable(systemVariableSpecService.get("LICENSESPAID"))
    if (nodeInfo.agentsName.contains(NOVA_AGENT)) {
      licenseRepository.findLicense(nodeInfo.policyServerId.value) match {
        case None =>
          logger.warn("Caution, the policy server %s does not have a registered Nova license".format(nodeInfo.policyServerId.value))
          throw new LicenseException("No license found for the policy server " + nodeInfo.policyServerId.value)
        case Some(x) => varLicensesPaid.saveValue(x.licenseNumber.toString)
      }
    } else {
      varLicensesPaid.saveValue("1")
    }

    val varAllowConnect = SystemVariable(systemVariableSpecService.get("ALLOWCONNECT"))

    val varClientList = SystemVariable(systemVariableSpecService.get("CLIENTSLIST"))

    val allowConnect = mutable.Set[String]()

    val clientList = mutable.Set[String]()

    // If we are facing a policy server, we have to allow each children to connect, plus the policy parent,
    // else it's only the policy server
    if(nodeInfo.isPolicyServer) {
      val allowedNodeVar = new SystemVariable(SystemVariableSpec(name = "${hasPolicyServer-" + nodeInfo.id.value + ".target.hostname}", description = "", multivalued = true))
      allowedNodeVar.values = Seq("${hasPolicyServer-" + nodeInfo.id.value + ".target.hostname}")

      parameterizedValueLookupService.lookupRuleParameterization(Seq(allowedNodeVar)) match {
        case Full(variable) =>
          allowConnect ++= variable.flatMap(x => x.values)
          clientList ++= variable.flatMap(x => x.values)
          varClientList.saveValues(clientList.toSeq)
        case Empty => logger.warn("No variable parametrized found for ${hasPolicyServer-" + nodeInfo.id.value + ".target.hostname}")
        case f: Failure => logger.error("Failure when fetching the policy children : %s ".format(f.msg))
      }
    }

    nodeInfoService.getNodeInfo(nodeInfo.policyServerId) match {
      case Full(policyServer) => allowConnect += policyServer.hostname
      case f: EmptyBox => logger.error("Couldn't find the policy server of node %s".format(nodeInfo.id.value))
    }

    varAllowConnect.saveValues(allowConnect.toSeq)
    
    logger.debug("System variables for server %s done".format(nodeInfo.id.value))

    Full(Map(
      (varNodeRole.spec.name, varNodeRole),
      (varLicensesPaid.spec.name, varLicensesPaid),
      (varToolsFolder.spec.name, varToolsFolder),
      (varCmdbEndpoint.spec.name, varCmdbEndpoint),
      (varSharedFilesFolder.spec.name, varSharedFilesFolder),
      (varCommunityPort.spec.name, varCommunityPort),
      (varAllowConnect.spec.name, varAllowConnect),
      (varClientList.spec.name, varClientList),
      (varWebdavUser.spec.name, varWebdavUser),
      (varWebdavPassword.spec.name, varWebdavPassword),
      (syslogPortConfig.spec.name, syslogPortConfig)
    ))

  }

}