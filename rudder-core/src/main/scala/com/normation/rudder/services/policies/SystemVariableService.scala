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
import com.normation.rudder.domain.Constants

trait SystemVariableService {
  def getGlobalSystemVariables():  Box[Map[String, Variable]]

  def getSystemVariables(nodeInfo: NodeInfo, allNodeInfos: Set[NodeInfo], groupLib: FullNodeGroupCategory, directiveLib: FullActiveTechniqueCategory, allRules: Map[RuleId, Rule], globalSystemVariables: Map[String, Variable]): Box[Map[String, Variable]]
}

class SystemVariableServiceImpl(
    licenseRepository: LicenseRepository
  , parameterizedValueLookupService: ParameterizedValueLookupService
  , systemVariableSpecService: SystemVariableSpecService
  , toolsFolder: String
  , cmdbEndPoint: String
  , communityPort: Int
  , sharedFilesFolder: String
  , webdavUser: String
  , webdavPassword: String
  , syslogPort: Int
  //denybadclocks and skipIdentify are runtime properties
  , getDenyBadClocks: () => Box[Boolean]
  , getSkipIdentify: () => Box[Boolean]
  // schedules are runtime dependencies also
  , getAgentRunInterval: () => Int
  , getAgentRunSplaytime: () => Box[Int]
  , getAgentRunStartHour: () => Box[Int]
  , getAgentRunStartMinute: () => Box[Int]
  // TTLs are runtime properties too
  , getModifiedFilesTtl:   () => Box[Int]
  , getCfengineOutputsTtl: () => Box[Int]
) extends SystemVariableService with Loggable {

  val varToolsFolder = systemVariableSpecService.get("TOOLS_FOLDER").toVariable().copyWithSavedValue(toolsFolder)
  val varCmdbEndpoint = systemVariableSpecService.get("CMDBENDPOINT").toVariable().copyWithSavedValue(cmdbEndPoint)
  val varWebdavUser = systemVariableSpecService.get("DAVUSER").toVariable().copyWithSavedValue(webdavUser)
  val varWebdavPassword = systemVariableSpecService.get("DAVPASSWORD").toVariable().copyWithSavedValue(webdavPassword)
  val varSharedFilesFolder = systemVariableSpecService.get("SHARED_FILES_FOLDER").toVariable().copyWithSavedValue(sharedFilesFolder)
  val varCommunityPort = systemVariableSpecService.get("COMMUNITYPORT").toVariable().copyWithSavedValue(communityPort.toString)
  val syslogPortConfig = systemVariableSpecService.get("SYSLOGPORT").toVariable().copyWithSavedValue(syslogPort.toString)

  // compute all the global system variable (so that need to be computed only once in a deployment)

  def getGlobalSystemVariables():  Box[Map[String, Variable]] = {
    logger.trace("Preparing the global system variables")
    val denyBadClocks = getProp("DENYBADCLOCKS", getDenyBadClocks)
    val skipIdentify = getProp("SKIPIDENTIFY", getSkipIdentify)
    val modifiedFilesTtl = getProp("MODIFIED_FILES_TTL", getModifiedFilesTtl)
    val cfengineOutputsTtl = getProp("CFENGINE_OUTPUTS_TTL", getCfengineOutputsTtl)

    val interval = getAgentRunInterval()
    val varAgentRunInterval = systemVariableSpecService.get("AGENT_RUN_INTERVAL").toVariable().copyWithSavedValue(interval.toString)

    val varAgentRunSplayTime = getProp("AGENT_RUN_SPLAYTIME", getAgentRunSplaytime)

    for {
      agentRunStartHour <- getAgentRunStartHour() ?~! "Could not retrieve the configure value for the run start hour"
      agentRunStartMinute <- getAgentRunStartMinute() ?~! "Could not retrieve the configure value for the run start minute"
      agentRunSplaytime <- getAgentRunSplaytime() ?~! "Could not retrieve the configure value for the run splay time"
      schedule <- ComputeSchedule.computeSchedule(agentRunStartHour, agentRunStartMinute, interval) ?~! "Could not compute the run schedule"
    } yield {

      val varAgentRunSchedule = systemVariableSpecService.get("AGENT_RUN_SCHEDULE").toVariable().copyWithSavedValue(schedule)
      logger.trace("Global system variables done")
      Map(
        (varToolsFolder.spec.name, varToolsFolder)
      , (varCmdbEndpoint.spec.name, varCmdbEndpoint)
      , (varSharedFilesFolder.spec.name, varSharedFilesFolder)
      , (varCommunityPort.spec.name, varCommunityPort)
      , (varWebdavUser.spec.name, varWebdavUser)
      , (varWebdavPassword.spec.name, varWebdavPassword)
      , (syslogPortConfig.spec.name, syslogPortConfig)
      , (denyBadClocks.spec.name, denyBadClocks)
      , (skipIdentify.spec.name, skipIdentify)
      , (varAgentRunInterval.spec.name, varAgentRunInterval)
      , (varAgentRunSchedule.spec.name, varAgentRunSchedule)
      , (varAgentRunSplayTime.spec.name, varAgentRunSplayTime)
      , (modifiedFilesTtl.spec.name, modifiedFilesTtl)
      , (cfengineOutputsTtl.spec.name, cfengineOutputsTtl)
      )
    }
  }

  // allNodeInfos has to contain ALL the node info (those of every node within Rudder)
  // for this method to work properly
  // The global system variables are computed before (in the method up there), and
  // can be overriden by some node specific parameters (especially, the schedule for
  // policy servers)
  def getSystemVariables(
        nodeInfo             : NodeInfo
      , allNodeInfos         : Set[NodeInfo]
      , groupLib             : FullNodeGroupCategory
      , directiveLib         : FullActiveTechniqueCategory
      , allRules             : Map[RuleId, Rule]
      , globalSystemVariables: Map[String, Variable]): Box[Map[String, Variable]] = {
    logger.trace("Preparing the system variables for node %s".format(nodeInfo.id.value))

    // variables that will override the global system variables
    val overridGlobalVariable = collection.mutable.Map[String, Variable]()

    // First, fetch the
    // Set the roles of the nodes
    val nodeConfigurationRoles = collection.mutable.Set[String]()

    if (nodeInfo.isPolicyServer) {
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

    var varManagedNodes = systemVariableSpecService.get("MANAGED_NODES_NAME").toVariable()
    var varManagedNodesId = systemVariableSpecService.get("MANAGED_NODES_ID").toVariable()
    var varAllowedNetworks = systemVariableSpecService.get("AUTHORIZED_NETWORKS").toVariable()
    var varManagedNodesAdmin = systemVariableSpecService.get("MANAGED_NODES_ADMIN").toVariable()

    val clientList = collection.mutable.Set[String]()

    // If we are facing a policy server, we have to allow each child to connect, plus the policy parent,
    // else it's only the policy server
    if (nodeInfo.isPolicyServer) {
      val allowedNodeVarSpec = SystemVariableSpec(name = "${rudder.hasPolicyServer-" + nodeInfo.id.value + ".target.hostname}", description = "", multivalued = true)
      val allowedNodeVar = SystemVariable(allowedNodeVarSpec, Seq()).copyWithSavedValues(Seq("${rudder.hasPolicyServer-" + nodeInfo.id.value + ".target.hostname}"))

      val varNameForAllowedNetwork = "${rudder.hasPolicyServer-" + nodeInfo.id.value + "." + Constants.V_ALLOWED_NETWORK + "}"
      val allowedNetworkVarSpec = SystemVariableSpec(name = varNameForAllowedNetwork, description = "", multivalued = true)
      val allowedNetworkVar = SystemVariable(allowedNetworkVarSpec, Seq()).copyWithSavedValues(Seq(varNameForAllowedNetwork))

      parameterizedValueLookupService.lookupRuleParameterization(Seq(allowedNetworkVar), allNodeInfos, groupLib, directiveLib, allRules) match {
        case Full(variable) =>
          varAllowedNetworks = varAllowedNetworks.copyWithSavedValues(variable.flatMap(x => x.values))
        case Empty => logger.warn(s"No variable parametrized found for ${varNameForAllowedNetwork}")
        case f: Failure =>
          val e = f ?~! s"Failure when fetching the allowed network for policy server ${nodeInfo.hostname} with id ${nodeInfo.id}"
          logger.error(e.messageChain)
          return e
      }

      // Find the "policy children" of this policy server
      // thanks to the allNodeInfos, this is super easy
      val children = allNodeInfos.filter(_.policyServerId == nodeInfo.id).toSeq
      varManagedNodes = varManagedNodes.copyWithSavedValues(children.map(_.hostname))
      varManagedNodesId = varManagedNodesId.copyWithSavedValues(children.map(_.id.value))

      val varNameForAdminUsers = "${rudder.hasPolicyServer-" + nodeInfo.id.value + ".target.admin}"
      val allowedUserVarSpec = SystemVariableSpec(name = varNameForAdminUsers, description = "", multivalued = true)
      val allowedUserVar = SystemVariable(allowedUserVarSpec, Seq()).copyWithSavedValues(Seq(varNameForAdminUsers))

      parameterizedValueLookupService.lookupRuleParameterization(Seq(allowedUserVar), allNodeInfos, groupLib, directiveLib, allRules) match {
        case Full(variable) =>
          varManagedNodesAdmin = varManagedNodesAdmin.copyWithSavedValues(variable.flatMap(x => x.values).distinct)
        case Empty =>
          logger.warn(s"There were no variable parametrized found for ${varNameForAdminUsers}, which means that the configuration of Policy Server with Rudder ID ${nodeInfo.id.value} is probably incorrect. Please run again the rudder-init.sh script, or the script used to add a Relay Server")
        case f: Failure =>
          val e = f ?~! "Failure when fetching the list of administrators of managed nodes"
          logger.error(e.messageChain)
          return e
      }

      // the schedule must be the default one for policy server
      val DEFAULT_SCHEDULE =
      """
        "Min00", "Min05", "Min10", "Min15", "Min20", "Min25", "Min30", "Min35", "Min40", "Min45", "Min50", "Min55"
      """

      val varAgentRunInterval = systemVariableSpecService.get("AGENT_RUN_INTERVAL").toVariable().copyWithSavedValue("5")

      val varAgentRunSplayTime = systemVariableSpecService.get("AGENT_RUN_SPLAYTIME").toVariable().copyWithSavedValue("5")
      val varAgentRunSchedule = systemVariableSpecService.get("AGENT_RUN_SCHEDULE").toVariable().copyWithSavedValue(DEFAULT_SCHEDULE)
      overridGlobalVariable.put(varAgentRunInterval.spec.name, varAgentRunInterval)
      overridGlobalVariable.put(varAgentRunSchedule.spec.name, varAgentRunSchedule)
      overridGlobalVariable.put(varAgentRunSplayTime.spec.name, varAgentRunSplayTime)

    }

    logger.trace("System variables for node %s done".format(nodeInfo.id.value))

    Full(
        globalSystemVariables ++ Map(
        (varNodeRole.spec.name, varNodeRole)
      , (varLicensesPaid.spec.name, varLicensesPaid)
      , (varManagedNodesId.spec.name, varManagedNodesId)
      , (varManagedNodes.spec.name, varManagedNodes)
      , (varAllowedNetworks.spec.name, varAllowedNetworks)
      , (varManagedNodesAdmin.spec.name, varManagedNodesAdmin)

      ) ++ overridGlobalVariable
    )

  }

  //obtaining variable values from (failable) properties
  private[this] def getProp[T](specName: String, getter: () => Box[T]): SystemVariable = {
      //try to get the user configured value, else log an error and use the default value.
      val variable = systemVariableSpecService.get(specName).toVariable()

      getter() match {
        case Full(value) =>
          variable.copyWithSavedValue(value.toString)
        case eb: EmptyBox =>
          val e = eb ?~! s"Error when trying to get the value configured by the user for system variable '${specName}'"
          logger.error(e.messageChain)
          e.rootExceptionCause.foreach { ex =>
            logger.error("Root exception cause was:", ex)
          }
          variable
      }
    }
}

object ComputeSchedule {
  def computeSchedule(
      startHour        : Int
    , startMinute      : Int
    , executionInterval: Int
  ): Box[String] = {

    val minutesFreq = executionInterval % 60
    val hoursFreq: Int = executionInterval / 60

    (minutesFreq, hoursFreq) match {
      case (m, h) if m > 0 && h > 0 => Failure(s"Agent execution interval can only be defined as minutes (less than 60) or complete hours, (${h} hours ${m} minutes is not supported)")
      case (m, h) if h == 0 =>
        // two cases, hour is 0, then only minutes

        // let's modulate startMinutes by minutes
        val actualStartMinute = startMinute % minutesFreq
        val mins = Range(actualStartMinute, 60, minutesFreq) // range doesn't return the end range
        //val mins = for ( min <- 0 to 59; if ((min%minutesFreq) == actualStartMinute) ) yield { min }
        Full(mins.map("\"Min" + "%02d".format(_) + "\"").mkString(", "))

      case _ =>
        // hour is not 0, then we don't have minutes
        val actualStartHour = startHour % hoursFreq
        val hours = Range(actualStartHour, 24, hoursFreq)
        val minutesFormat = "Min" + "%02d".format(startMinute)
        Full(hours.map("\"Hr" + "%02d".format(_) + "." + minutesFormat + "\"").mkString(", "))
    }

  }

}
