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
import com.normation.rudder.services.servers.PolicyServerManagementService
import com.normation.rudder.reports.ComplianceMode

trait SystemVariableService {
  def getGlobalSystemVariables():  Box[Map[String, Variable]]



  def getSystemVariables(nodeInfo: NodeInfo, allNodeInfos: Map[NodeId, NodeInfo], globalSystemVariables: Map[String, Variable]): Box[Map[String, Variable]]
}

class SystemVariableServiceImpl(
    licenseRepository: LicenseRepository
  , systemVariableSpecService: SystemVariableSpecService
  , policyServerManagementService: PolicyServerManagementService
  // Variables definitions
  , toolsFolder              : String
  , cmdbEndPoint             : String
  , communityPort            : Int
  , sharedFilesFolder        : String
  , webdavUser               : String
  , webdavPassword           : String
  , syslogPort               : Int
  , rudderServerRoleLdap     : String
  , rudderServerRoleInventoryEndpoint: String
  , rudderServerRoleDb       : String
  , rudderServerRoleRelayTop : String
  , rudderServerRoleWeb      : String
  //denybadclocks and skipIdentify are runtime properties
  , getDenyBadClocks: () => Box[Boolean]
  , getSkipIdentify : () => Box[Boolean]
  // schedules are runtime dependencies also
  , getAgentRunInterval   : () => Int
  , getAgentRunSplaytime  : () => Box[Int]
  , getAgentRunStartHour  : () => Box[Int]
  , getAgentRunStartMinute: () => Box[Int]
  // TTLs are runtime properties too
  , getModifiedFilesTtl             : () => Box[Int]
  , getCfengineOutputsTtl           : () => Box[Int]
  , getStoreAllCentralizedLogsInFile: () => Box[Boolean]
  , getComplianceMode               : () => Box[ComplianceMode]
) extends SystemVariableService with Loggable {

  val varToolsFolder = systemVariableSpecService.get("TOOLS_FOLDER").toVariable().copyWithSavedValue(toolsFolder)
  val varCmdbEndpoint = systemVariableSpecService.get("CMDBENDPOINT").toVariable().copyWithSavedValue(cmdbEndPoint)
  val varWebdavUser = systemVariableSpecService.get("DAVUSER").toVariable().copyWithSavedValue(webdavUser)
  val varWebdavPassword = systemVariableSpecService.get("DAVPASSWORD").toVariable().copyWithSavedValue(webdavPassword)
  val varSharedFilesFolder = systemVariableSpecService.get("SHARED_FILES_FOLDER").toVariable().copyWithSavedValue(sharedFilesFolder)
  val varCommunityPort = systemVariableSpecService.get("COMMUNITYPORT").toVariable().copyWithSavedValue(communityPort.toString)
  val syslogPortConfig = systemVariableSpecService.get("SYSLOGPORT").toVariable().copyWithSavedValue(syslogPort.toString)

  // Compute the values for rudderServerRoleLdap, rudderServerRoleDb and rudderServerRoleRelayTop
  // if autodetect, then it is not defined, otherwise we parse it
  val AUTODETECT_KEYWORD="autodetect"
  def parseRoleContent(value: String) : Option[Iterable[String]] = {
    value match {
      case AUTODETECT_KEYWORD => None
      case _ => Some(value.split(","))
    }
  }

  lazy val definedRudderServerRoleLdap     = parseRoleContent(rudderServerRoleLdap)
  lazy val definedRudderServerRoleDb       = parseRoleContent(rudderServerRoleDb)
  lazy val definedRudderServerRoleRelayTop = parseRoleContent(rudderServerRoleRelayTop)
  lazy val definedRudderServerRoleWeb      = parseRoleContent(rudderServerRoleWeb)
  lazy val definedRudderServerRoleInventoryEnpoint = parseRoleContent(rudderServerRoleInventoryEndpoint)

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

    val storeAllCentralizedLogsInFile = getProp("STORE_ALL_CENTRALIZED_LOGS_IN_FILE", getStoreAllCentralizedLogsInFile)

    val varReportMode = {

      //we want an actual valide string, defined in the "name" of compliance mode object
      getProp("RUDDER_REPORT_MODE", () => getComplianceMode().map( _.name ))
    }


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
      , (storeAllCentralizedLogsInFile.spec.name, storeAllCentralizedLogsInFile)
      , (varReportMode.spec.name, varReportMode)
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
      , allNodeInfos         : Map[NodeId, NodeInfo]
      , globalSystemVariables: Map[String, Variable]
  ): Box[Map[String, Variable]] = {

    logger.trace("Preparing the system variables for node %s".format(nodeInfo.id.value))

    // Set the roles of the nodes
    val nodeConfigurationRoles = collection.mutable.Set[ServerRole]() ++ nodeInfo.serverRoles


    // Define the mapping of roles/hostnames, only if the node has a role
    val varRoleMappingValue = if (nodeConfigurationRoles.size > 0) {
      val allNodeInfosSet = allNodeInfos.values.toSet

      val nodesWithRoleLdap = definedRudderServerRoleLdap match {
        case Some(seq) => seq
        case None => getNodesWithRole(allNodeInfosSet, ServerRole("rudder-ldap"))
      }

      val nodesWithRoleInventoryEndpoint = definedRudderServerRoleInventoryEnpoint match {
        case Some(seq) => seq
        case None => getNodesWithRole(allNodeInfosSet, ServerRole("rudder-inventory-endpoint"))
      }

      val nodesWithRoleDb = definedRudderServerRoleDb match {
        case Some(seq) => seq
        case None => getNodesWithRole(allNodeInfosSet, ServerRole("rudder-db"))
      }

      val nodesWithRoleRelayTop = definedRudderServerRoleRelayTop match {
        case Some(seq) => seq
        case None => getNodesWithRole(allNodeInfosSet, ServerRole("rudder-relay-top"))
      }

      val nodesWithRoleWeb = definedRudderServerRoleWeb match {
        case Some(seq) => seq
        case None => getNodesWithRole(allNodeInfosSet, ServerRole("rudder-web"))
      }

      writeNodesWithRole(nodesWithRoleLdap, "rudder-ldap") +
      writeNodesWithRole(nodesWithRoleInventoryEndpoint, "rudder-inventory-endpoint") +
      writeNodesWithRole(nodesWithRoleDb, "rudder-db") +
      writeNodesWithRole(nodesWithRoleRelayTop, "rudder-relay-top") +
      writeNodesWithRole(nodesWithRoleWeb, "rudder-web")
    } else {
      ""
    }

    val varRudderServerRole = systemVariableSpecService.get("RUDDER_SERVER_ROLES").toVariable().copyWithSavedValue(varRoleMappingValue)

    if (nodeInfo.isPolicyServer) {
      nodeConfigurationRoles.add(ServerRole("policy_server"))
      if (nodeInfo.id == nodeInfo.policyServerId) {
        nodeConfigurationRoles.add(ServerRole("root_server"))
      }
    }

    val varNodeRoleValue = if (nodeConfigurationRoles.size > 0) {
      "  classes: \n" + nodeConfigurationRoles.map(x => "    \"" + x.value + "\" expression => \"any\";").mkString("\n")
    } else {
      "# This node doesn't have any specific role"
    }

    val varNodeRole = systemVariableSpecService.get("NODEROLE").toVariable().copyWithSavedValue(varNodeRoleValue)

    // Check that the policy server have Nova license if the host is a Nova
    if (nodeInfo.agentsName.contains(NOVA_AGENT) && licenseRepository.findLicense(nodeInfo.policyServerId).isEmpty) {
      logger.warn("Caution, the policy server %s does not have a registered Nova license".format(nodeInfo.policyServerId.value))
      throw new LicenseException("No license found for the policy server " + nodeInfo.policyServerId.value)
    }

    val authorizedNetworks = policyServerManagementService.getAuthorizedNetworks(nodeInfo.id) match {
      case eb:EmptyBox =>
        //log ?
        Seq()
      case Full(nets) => nets
    }

    val varAllowedNetworks = systemVariableSpecService.get("AUTHORIZED_NETWORKS").toVariable(authorizedNetworks)


    // If we are facing a policy server, we have to allow each child to connect, plus the policy parent,
    // else it's only the policy server
    val policyServerVars = if (nodeInfo.isPolicyServer) {

      // Find the "policy children" of this policy server
      // thanks to the allNodeInfos, this is super easy
      //IT IS VERY IMPORTANT TO SORT SYSTEM VARIABLE HERE: see ticket #4859
      val children = allNodeInfos.filter{ case(k,v) => v.policyServerId == nodeInfo.id }.values.toSeq.sortBy( _.id.value )

      val varManagedNodes = systemVariableSpecService.get("MANAGED_NODES_NAME").toVariable(children.map(_.hostname))
      val varManagedNodesId = systemVariableSpecService.get("MANAGED_NODES_ID").toVariable(children.map(_.id.value))
      //IT IS VERY IMPORTANT TO SORT SYSTEM VARIABLE HERE: see ticket #4859
      val varManagedNodesAdmin = systemVariableSpecService.get("MANAGED_NODES_ADMIN").toVariable(children.map(_.localAdministratorAccountName).distinct.sorted)


      // the schedule must be the default one for policy server
      val DEFAULT_SCHEDULE =
      """
        "Min00", "Min05", "Min10", "Min15", "Min20", "Min25", "Min30", "Min35", "Min40", "Min45", "Min50", "Min55"
      """

      val varAgentRunInterval = systemVariableSpecService.get("AGENT_RUN_INTERVAL").toVariable().copyWithSavedValue("5")
      val varAgentRunSplayTime = systemVariableSpecService.get("AGENT_RUN_SPLAYTIME").toVariable().copyWithSavedValue("5")
      val varAgentRunSchedule = systemVariableSpecService.get("AGENT_RUN_SCHEDULE").toVariable().copyWithSavedValue(DEFAULT_SCHEDULE)

      Map(
          varManagedNodes.spec.name -> varManagedNodes
        , varManagedNodesId.spec.name -> varManagedNodesId
        , varManagedNodesAdmin.spec.name -> varManagedNodesAdmin
        , varAgentRunInterval.spec.name -> varAgentRunInterval
        , varAgentRunSchedule.spec.name -> varAgentRunSchedule
        , varAgentRunSplayTime.spec.name -> varAgentRunSplayTime
      )
    } else {
      Map()
    }

    logger.trace("System variables for node %s done".format(nodeInfo.id.value))

    /*
     * RUDDER_NODE_CONFIG_ID is a very special system variable:
     * it must not be used to assess node config stability from
     * run to run.
     * So we set it to a default value and handle it specialy in
     * RudderCf3PromisesFileWriterServiceImpl#prepareRulesForAgents
     */
    val varNodeConfigVersion = systemVariableSpecService.get("RUDDER_NODE_CONFIG_ID").toVariable(Seq("DUMMY NODE CONFIG VERSION"))

    Full(
         globalSystemVariables
      ++ Seq(
             varNodeRole
           , varAllowedNetworks
           , varRudderServerRole
           , varNodeConfigVersion
         ).map(x => (x.spec.name, x))
      ++ policyServerVars
    )

  }

  // Fetch the Set of node hostnames having specific role
  private[this] def getNodesWithRole(
      allNodeInfos  : Set[NodeInfo]
    , role          : ServerRole
  ) : Set[String] = {
    allNodeInfos.filter(x => x.serverRoles.contains(role)).map(_.hostname)
  }

  // Formating of the roles
  private[this] def writeNodesWithRole(
      nodesWithRole: Iterable[String]
    , roleName     : String
  ) : String = {
    nodesWithRole.size match {
      case 0 => "" // no string, no role
      case _ => s"${roleName}:${nodesWithRole.mkString(",")}\n"
    }
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
