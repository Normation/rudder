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

package com.normation.rudder.services.policies

import com.normation.box._
import com.normation.cfclerk.domain.SystemVariable
import com.normation.cfclerk.domain.SystemVariableSpec
import com.normation.cfclerk.domain.Variable
import com.normation.cfclerk.services.MissingSystemVariable
import com.normation.cfclerk.services.SystemVariableSpecService
import com.normation.errors.Inconsistency
import com.normation.errors.PureResult
import com.normation.inventory.domain.AgentInfo
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.AgentVersion
import com.normation.inventory.domain.Certificate
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.policies.FullRuleTargetInfo
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.rudder.reports._
import com.normation.rudder.services.servers.PolicyServerManagementService
import com.normation.rudder.services.servers.RelaySynchronizationMethod
import com.normation.zio._
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.common.Loggable

trait SystemVariableService {
  def getGlobalSystemVariables(globalAgentRun: AgentRunInterval):  Box[Map[String, Variable]]

  def getSystemVariables(
      nodeInfo              : NodeInfo
    , allNodeInfos          : Map[NodeId, NodeInfo]
    , nodeTargets           : List[FullRuleTargetInfo]
    , globalSystemVariables : Map[String, Variable]
    , globalAgentRun        : AgentRunInterval
    , globalComplianceMode  : ComplianceMode  ) : Box[Map[String, Variable]]
}

final case class ResolvedRudderServerRole(
    val name       : String
  , val configValue: Option[Iterable[String]]
)

sealed  trait SendMetrics
object  SendMetrics {
  case object NoMetrics extends  SendMetrics
  case object MinimalMetrics extends  SendMetrics
  case object CompleteMetrics extends  SendMetrics
}
object SystemVariableService {

  // we use that variable to take care of an unexpected missing variable.
  implicit class MissingSystemVariableCatch(optVar: Either[MissingSystemVariable, SystemVariableSpec]) {
    def toVariable(initValues: Seq[String] = Seq()): SystemVariable = (optVar match {
      case Left(MissingSystemVariable(name)) =>
        ApplicationLogger.error(s"System variable '${name}' is missing. This is most likely denote a desynchronisation between your system variable and " +
                                s"your Rudder version. Please check that both are well synchronized. If it's the case, please report that problem.")
        SystemVariableSpec(name, "THIS IS DEFAULT GENERATED VARIABLE SPEC. THE CORRECT ONE WAS NOT FOUND. PLEASE SEE YOUR RUDDER LOG.", multivalued = false)

      case Right(spec) => spec
    }).toVariable(initValues)
  }
}

class SystemVariableServiceImpl(
    systemVariableSpecService    : SystemVariableSpecService
  , policyServerManagementService: PolicyServerManagementService
  // Variables definitions
  , toolsFolder              : String
  , policyDistribCfenginePort: Int
  , policyDistribHttpsPort   : Int
  , sharedFilesFolder        : String
  , webdavUser               : String
  , webdavPassword           : String
  , reportsDbUri             : String
  , reportsDbUser            : String
  , reportsDbPassword        : String
  , configurationRepository  : String
  , serverVersion            : String
  //denybadclocks is runtime property
  , getDenyBadClocks: () => Box[Boolean]
  // relay synchronisation method
  , getSyncMethod            : () => Box[RelaySynchronizationMethod]
  , getSyncPromises          : () => Box[Boolean]
  , getSyncSharedFiles       : () => Box[Boolean]
  // TTLs are runtime properties too
  , getModifiedFilesTtl             : () => Box[Int]
  , getCfengineOutputsTtl           : () => Box[Int]
  , getSendMetrics                  : () => Box[Option[SendMetrics]]
  , getReportProtocolDefault        : () => Box[AgentReportingProtocol]
  , getRudderVerifyCertificates     : () => Box[Boolean]
) extends SystemVariableService with Loggable {

  import SystemVariableService._

  //get the Rudder reports DB (postgres) database name from URI
  val reportsDbName = {
    reportsDbUri.split("""/""").toSeq.lastOption.getOrElse(throw new IllegalArgumentException(
      s"The JDBC URI configure for property 'rudder.jdbc.url' is malformed and should ends by /BASENAME: ${reportsDbUri}")
    )
  }
  val reportsDbUrl = reportsDbUri.replace(s"""jdbc:postgresql://""", s"""postgresql://${reportsDbUser}@""")

  val varToolsFolder                = systemVariableSpecService.get("TOOLS_FOLDER"                   ).toVariable(Seq(toolsFolder))
  val varWebdavUser                 = systemVariableSpecService.get("DAVUSER"                        ).toVariable(Seq(webdavUser))
  val varWebdavPassword             = systemVariableSpecService.get("DAVPASSWORD"                    ).toVariable(Seq(webdavPassword))
  val varSharedFilesFolder          = systemVariableSpecService.get("SHARED_FILES_FOLDER"            ).toVariable(Seq(sharedFilesFolder))
  val varPolicyDistribCfenginePort  = systemVariableSpecService.get("COMMUNITYPORT"                  ).toVariable(Seq(policyDistribCfenginePort.toString))
  val varPolicyDistribHttpsPort     = systemVariableSpecService.get("HTTPS_POLICY_DISTRIBUTION_PORT" ).toVariable(Seq(policyDistribHttpsPort.toString))
  val configurationRepositoryFolder = systemVariableSpecService.get("CONFIGURATION_REPOSITORY_FOLDER").toVariable(Seq(configurationRepository))

  // compute all the global system variable (so that need to be computed only once in a deployment)

  def getGlobalSystemVariables(globalAgentRun: AgentRunInterval):  Box[Map[String, Variable]] = {
    logger.trace("Preparing the global system variables")
    val denyBadClocks        = getProp("DENYBADCLOCKS"         , getDenyBadClocks)

    val modifiedFilesTtl     = getProp("MODIFIED_FILES_TTL"    , getModifiedFilesTtl)
    val cfengineOutputsTtl   = getProp("CFENGINE_OUTPUTS_TTL"  , getCfengineOutputsTtl)

    val relaySyncMethod      = getProp("RELAY_SYNC_METHOD"     , () => getSyncMethod().map(_.value))
    val relaySyncPromises    = getProp("RELAY_SYNC_PROMISES"   , getSyncPromises)
    val relaySyncSharedFiles = getProp("RELAY_SYNC_SHAREDFILES", getSyncSharedFiles)

    val rudderVerifyCertificates = getProp("RUDDER_VERIFY_CERTIFICATES", getRudderVerifyCertificates)

    val varServerVersion = systemVariableSpecService.get("SERVER_VERSION").toVariable(Seq(serverVersion))

    import SendMetrics._
    val sendMetricsValue = getSendMetrics().getOrElse(None) match {
      case None => "no"
      case Some(NoMetrics) => "no"
      case Some(MinimalMetrics) => "minimal"
      case Some(CompleteMetrics) => "complete"
    }

    val varSendMetrics = systemVariableSpecService.get("SEND_METRICS").toVariable(Seq(sendMetricsValue))

    logger.trace("Global system variables done")
    val vars =
      varToolsFolder ::
      varSharedFilesFolder ::
      varPolicyDistribCfenginePort ::
      varPolicyDistribHttpsPort ::
      varWebdavUser ::
      varWebdavPassword ::
      configurationRepositoryFolder ::
      denyBadClocks ::
      relaySyncMethod ::
      relaySyncPromises ::
      relaySyncSharedFiles ::
      modifiedFilesTtl ::
      cfengineOutputsTtl ::
      varSendMetrics ::
      rudderVerifyCertificates ::
      varServerVersion ::
      Nil

    Full(vars.map(v => (v.spec.name,v)).toMap)
  }

  // allNodeInfos has to contain ALL the node info (those of every node within Rudder)
  // for this method to work properly

  // The global system variables are computed before (in the method up there), and
  // can be overriden by some node specific parameters (especially, the schedule for
  // policy servers)
  def getSystemVariables(
        nodeInfo              : NodeInfo
      , allNodeInfos          : Map[NodeId, NodeInfo]
      , nodeTargets           : List[FullRuleTargetInfo]
      , globalSystemVariables : Map[String, Variable]
      , globalAgentRun        : AgentRunInterval
      , globalComplianceMode  : ComplianceMode
  ): Box[Map[String, Variable]] = {

    logger.trace("Preparing the system variables for node %s".format(nodeInfo.id.value))

    val varRudderNodeKind = systemVariableSpecService.get("RUDDER_NODE_KIND").toVariable(Seq(nodeInfo.nodeKind.name))

    val allowedNetworks = policyServerManagementService.getAllowedNetworks(nodeInfo.id).toBox match {
      case eb:EmptyBox =>
        if(nodeInfo.isPolicyServer) {
          logger.warn(s"No allowed networks found for policy server '${nodeInfo.id.value}'; Nodes won't be able to connect to it to get their policies.")
        } // in other case, it's a simple node and it's expected that allowed networks are emtpy. We used to add it in all cases, not sure it's useful.
        Seq()
      case Full(nets) =>
        nets.map(_.inet)
    }

    val varAllowedNetworks = systemVariableSpecService.get("ALLOWED_NETWORKS").toVariable(allowedNetworks)

    val agentRunParams = {
      if (nodeInfo.isPolicyServer) {
        val policyServerSchedule = """ "Min00", "Min05", "Min10", "Min15", "Min20", "Min25", "Min30", "Min35", "Min40", "Min45", "Min50", "Min55" """
        Right((AgentRunInterval(Some(false), 5, 0, 0, 0), policyServerSchedule))
      } else {
        val runInterval = nodeInfo.nodeReportingConfiguration.agentRunInterval match {
          case Some(nodeRunInterval)  if nodeRunInterval.overrides.getOrElse(false) =>
            nodeRunInterval
          case _ =>
            globalAgentRun
        }
        for {
          schedule <- ComputeSchedule.computeSchedule(
                              runInterval.startHour
                            , runInterval.startMinute
                            , runInterval.interval
                          ).chainError(s"Could not compute the run schedule for node ${nodeInfo.id.value}")
        } yield {
          ( runInterval, schedule )
        }
      }
    }

    val heartBeatFrequency = {
      if (nodeInfo.isPolicyServer) {
        // A policy server is always sending heartbeat
        1
      } else {
        globalComplianceMode.mode match {
          case ChangesOnly =>
            nodeInfo.nodeReportingConfiguration.heartbeatConfiguration match {
              // It overrides! use it to compute the new heartbeatInterval
              case Some(heartbeatConf) if heartbeatConf.overrides =>
                heartbeatConf.heartbeatPeriod
              case _ =>
                globalComplianceMode.heartbeatPeriod
            }
          case _ =>
            1
        }
      }
    }

    val agentRunVariables = ( agentRunParams.map {
      case (runInterval,schedule) =>

        // The heartbeat should be strictly shorter than the run execution, otherwise they may be skipped
        val heartbeat = runInterval.interval * heartBeatFrequency - 1
        val vars = {
          systemVariableSpecService.get("AGENT_RUN_INTERVAL").toVariable(Seq(runInterval.interval.toString)) ::
          systemVariableSpecService.get("AGENT_RUN_SPLAYTIME").toVariable(Seq(runInterval.splaytime.toString))  ::
          systemVariableSpecService.get("AGENT_RUN_SCHEDULE").toVariable(Seq(schedule)) ::
          systemVariableSpecService.get("RUDDER_HEARTBEAT_INTERVAL").toVariable(Seq(heartbeat.toString)) ::
          systemVariableSpecService.get("RUDDER_REPORT_MODE").toVariable(Seq(globalComplianceMode.name)) ::
          Nil
        }
        vars.map(v => v.spec.name -> v ).toMap
    } )

    // If we are facing a policy server, we have to allow each child to connect, plus the policy parent,
    // else it's only the policy server
    val policyServerVars = if (nodeInfo.isPolicyServer) {

      // we need to know the mapping between policy servers and their children
      val childrenByPolicyServer = allNodeInfos.values.toList.groupBy( _.policyServerId )

      // Find the "policy children" of this policy server
      // thanks to the allNodeInfos, this is super easy
      //IT IS VERY IMPORTANT TO SORT SYSTEM VARIABLE HERE: see ticket #4859
      val children = childrenByPolicyServer.getOrElse(nodeInfo.id, Nil).sortBy( _.id.value )


      // Sort these children by agent
      // Each node may have several agent type, so we need to "unfold" agents per children
      val childerNodesList = children.map(node => (node.agentsName.map(agent => agent -> node))).flatten

      // we need to split nodes based on the way they get their policies. If they use cf-serverd,
      // we need to set some system variable to managed authentication.
      // The distribution is chosen based on agent type.
      val (nodesAgentWithCfserverDistrib, nodesAgentWithHttpDistrib) = childerNodesList.partition(x => x._1.agentType match {
          case AgentType.CfeCommunity | AgentType.CfeEnterprise => true
          case _                                                => false
        }
      )

      //IT IS VERY IMPORTANT TO SORT SYSTEM VARIABLE HERE: see ticket #4859
      val nodesWithCFEKey = nodesAgentWithCfserverDistrib.map(_._2).sortBy( _.id.value )

      val varManagedNodes      = systemVariableSpecService.get("MANAGED_NODES_NAME" ).toVariable(nodesWithCFEKey.map(_.hostname))
      val varManagedNodesId    = systemVariableSpecService.get("MANAGED_NODES_ID"   ).toVariable(nodesWithCFEKey.map(_.id.value))
      val varManagedNodesKey   = systemVariableSpecService.get("MANAGED_NODES_KEY"  ).toVariable(nodesWithCFEKey.map(_.keyHashCfengine))
      //IT IS VERY IMPORTANT TO SORT SYSTEM VARIABLE HERE: see ticket #4859
      val varManagedNodesAdmin = systemVariableSpecService.get("MANAGED_NODES_ADMIN").toVariable(nodesWithCFEKey.map(_.localAdministratorAccountName).distinct.sorted)

      //IT IS VERY IMPORTANT TO SORT SYSTEM VARIABLE HERE: see ticket #4859
      val varManagedNodesIp = systemVariableSpecService.get("MANAGED_NODES_IP"      ).toVariable(nodesWithCFEKey.flatMap(_.ips).distinct.sorted)

      // same kind of variable but for ALL childrens, not only direct one:
      val allChildren = {
         //utility to add children of a list of nodes
        def addWithSubChildren(nodes: List[NodeInfo]): List[NodeInfo] = {
          nodes.flatMap(n =>
            n :: {
              childrenByPolicyServer.get(n.id) match {
                case None           => Nil
                case Some(children) =>
                  // If the node 'n' is the same node of the policy server we are generating variables, do not go to childs level, they will be treated by the upper level call
                  if (n.id == nodeInfo.id) {
                    Nil
                  } else {
                    addWithSubChildren(children)
                  }
              }
            }
          )
        }
        addWithSubChildren(children)
      }

      // Each node may have several agent type, so we need to "unfold" agents per children
      val subNodesList = allChildren.map(node => (node.agentsName.map(agent => agent -> node))).flatten

      val varSubNodesName    = systemVariableSpecService.get("SUB_NODES_NAME"   ).toVariable(subNodesList.map(_._2.hostname))
      val varSubNodesId      = systemVariableSpecService.get("SUB_NODES_ID"     ).toVariable(subNodesList.map(_._2.id.value))
      val varSubNodesServer  = systemVariableSpecService.get("SUB_NODES_SERVER" ).toVariable(subNodesList.map(_._2.policyServerId.value))
      val varSubNodesKeyhash = systemVariableSpecService.get("SUB_NODES_KEYHASH").toVariable(subNodesList.map(n => s"sha256//${n._2.keyHashBase64Sha256}"))


      // Construct the system variables for nodes with certificates
      val nodesWithCertificate = nodesAgentWithHttpDistrib.flatMap {case (agent, node) =>
        agent.securityToken match {
          // A certificat, we return it
          case cert:Certificate =>
            // for the certificate part, we exec the ZIO. If we have failure, log it and return "None"
            val parsedCert = cert.cert.either.runNow match {
              case Left(err) =>
                logger.error(s"Error when parsing certificate for node '${node.hostname}' [${node.id.value}]: ${err.fullMsg}")
                None
              case Right(x) =>
                Some(x)
            }
            Some((node, cert, parsedCert))
          case _                =>
            None
        }
      }
      val varManagedNodesCertificate = systemVariableSpecService.get("MANAGED_NODES_CERT_PEM").toVariable(nodesWithCertificate.map(_._2.value))

      val varManagedNodesCertDN = systemVariableSpecService.get("MANAGED_NODES_CERT_DN").toVariable(nodesWithCertificate.map(_._3.map(_.getSubject.toString).getOrElse("")))

      // get the CN, based on https://stackoverflow.com/questions/2914521/how-to-extract-cn-from-x509certificate-in-java
      val varManagedNodesCertCN = systemVariableSpecService.get("MANAGED_NODES_CERT_CN").toVariable(nodesWithCertificate.map(_._3.map(_.getSubject.getRDNs().apply(0).getFirst().getValue().toString).getOrElse("")))

      val varManagedNodesCertUUID = systemVariableSpecService.get("MANAGED_NODES_CERT_UUID").toVariable(nodesWithCertificate.map(_._1.id.value))

      // Root server specific
      val rootServerSpecific = if (nodeInfo.id.value == "root") {
        //Reports DB name (postgres), URL and DB user
        val varReportsDBname = systemVariableSpecService.get("RUDDER_REPORTS_DB_NAME").toVariable(Seq(reportsDbName))
        val varReportsDBUrl = systemVariableSpecService.get("RUDDER_REPORTS_DB_URL").toVariable(Seq(reportsDbUrl))
        val varReportsDBPassword = systemVariableSpecService.get("RUDDER_REPORTS_DB_PASSWORD").toVariable(Seq(reportsDbPassword))
        Seq(
            varReportsDBname
          , varReportsDBUrl
          , varReportsDBPassword)
      } else {
        val onlyForRootMessage = "This variable is only defined on root server"
        val varReportsDBname = systemVariableSpecService.get("RUDDER_REPORTS_DB_NAME").toVariable(Seq(onlyForRootMessage))
        val varReportsDBUrl = systemVariableSpecService.get("RUDDER_REPORTS_DB_URL").toVariable(Seq(onlyForRootMessage))
        val varReportsDBPassword = systemVariableSpecService.get("RUDDER_REPORTS_DB_PASSWORD").toVariable(Seq(onlyForRootMessage))
        Seq(
          varReportsDBname
          , varReportsDBUrl
          , varReportsDBPassword)
      }


      // the schedule must be the default one for policy server

      (Seq(
          varManagedNodes
        , varManagedNodesId
        , varManagedNodesAdmin
        , varManagedNodesIp
        , varManagedNodesKey
        , varSubNodesName
        , varSubNodesId
        , varSubNodesServer
        , varSubNodesKeyhash
        , varManagedNodesCertificate
        , varManagedNodesCertDN
        , varManagedNodesCertCN
        , varManagedNodesCertUUID
      ) ++ rootServerSpecific ).map(x => (x.spec.name, x))
    } else {
      Map()
    }

    /* Get the policy server security token
     * We are pretty certin to find a policy server, as it is checked earlier
     * and there is by construct a securityToken
     */
    // cfengine version
    val varPolicyServerKeyHashCfengine = systemVariableSpecService.get("POLICY_SERVER_KEY" ).toVariable(Seq(allNodeInfos(nodeInfo.policyServerId).keyHashCfengine))
    // base64(sha256(der encoded pub key))) version
    val varPolicyServerKeyHashB64Sha256 = systemVariableSpecService.get("POLICY_SERVER_KEY_HASH" ).toVariable(Seq("sha256//"+allNodeInfos(nodeInfo.policyServerId).keyHashBase64Sha256))

    logger.trace("System variables for node %s done".format(nodeInfo.id.value))

    /*
     * RUDDER_NODE_CONFIG_ID is a very special system variable:
     * it must not be used to assess node config stability from
     * run to run.
     * So we set it to a default value and handle it specialy in
     * PolicyWriterServiceImpl#prepareRulesForAgents
     */
    val varNodeConfigVersion = systemVariableSpecService.get("RUDDER_NODE_CONFIG_ID").toVariable(Seq("DUMMY NODE CONFIG VERSION"))

    /*
     * RUDDER_NODE_GROUPS_VAR is an array of group_uuid -> group_name for the node
     * RUDDER_NODE_GROUPS_CLASSE are pairs of group_UUID, group_NORMALIZED_NAME,
     * for ex if node belongs to group:
     * (id: 64f85ba8-39c7-418a-a099-24c2c2909dfd ; name: "Serveurs pre-prod")
     * we will have the following classes:
     *   - group_64f85ba8_39c7_418a_a099_24c2c2909dfd
     *   - group_serveurs_pre_prod
     * and vars:
     *   - "by_uuid[64f85ba8-39c7-418a-a099-24c2c2909dfd]" string => "Serveurs pre-prod"
     *     with a meta: { "inventory", "attribute_name=rudder_groups" }
     */
    //build the list of nodeId -> names, taking care of special nodeIds for special target
    val nodeGroups = nodeTargets.map { info =>
      val id = info.target.target match {
        case GroupTarget(id) => id.serialize
        case t => t.target
      }
      (id, info.name)
    }
    val nodeMaxString = if(nodeGroups.isEmpty) 0 else nodeGroups.flatMap { case (a,b) => a.size :: b.size :: Nil }.max
    val stringNodeGroupsVars = if(nodeGroups.isEmpty) {
      ""
    } else {
      nodeGroups.map { case (id, name) =>
        s""""by_uuid[${id}]" ${" "*(nodeMaxString-id.size)} string => "${name.replace("\"", "\\\"")}",\n""" +
        s"""            ${" "*(nodeMaxString)        }   meta => { "inventory", "attribute_name=rudder_groups" };"""
      }.mkString("\n")
    }
    val stringNodeGroupsClasses = if(nodeGroups.isEmpty) {
      ""
    } else {
      nodeGroups.flatMap { case (id, name) =>
        (  s""""${RuleTarget.toCFEngineClassName(id  )}" ${" "*(nodeMaxString-  id.size)} expression => "any",\n""" +
           s"""             ${" "*(nodeMaxString)}   meta => { "inventory", "attribute_name=rudder_groups" };"""
        :: s""""${RuleTarget.toCFEngineClassName(name)}" ${" "*(nodeMaxString-name.size)} expression => "any",\n""" +
           s"""             ${" "*(nodeMaxString)}   meta => { "inventory", "attribute_name=rudder_groups" };"""
        :: Nil
        )
      }.mkString("\n")
    }
    val varNodeGroups = systemVariableSpecService.get("RUDDER_NODE_GROUPS_VARS").toVariable(Seq(stringNodeGroupsVars))
    val varNodeGroupsClasses = systemVariableSpecService.get("RUDDER_NODE_GROUPS_CLASSES").toVariable(Seq(stringNodeGroupsClasses))

    // If Syslog is disabled, we force HTTPS.
    val varNodeReportingProtocol = {
      // By default, we are tolerant: it's far worse to fail a generation while support is ok than to
      // succeed and have a node that doesn't rerport.
      // => version must exists and and starts by 2.x, 3.x, 4.x, 5.x
      def versionHasSyslogOnly(maybeVersion: Option[AgentVersion], agentType: AgentType): Boolean = {
        maybeVersion match {
          case None    =>
            false // we don't know, so say HTTPS is supported
          case Some(v) =>
            val forbidenUnix = List("2.", "3.", "4.", "5.")
            val forbidenDSC = List("2.", "3.", "4.", "5.", "6.0.")
            val forbiden = agentType match {
              case AgentType.Dsc => forbidenDSC
              case _             => forbidenUnix
            }
            forbiden.exists(x => v.value.startsWith(x))
        }
      }

      def failure(nodeInfo: NodeInfo, badVersion: AgentInfo) = {
        Failure(s"Node ${nodeInfo.hostname} (${nodeInfo.id.value}) has an agent which doesn't support sending compliance reports in HTTPS: ${
                  badVersion.agentType match {
                    case AgentType.Dsc => s"Rudder agent on Windows only support HTTPS reporting for version >= 6.1"
                    case _             => s"Rudder agent only support HTTPS reporting for version >= 6.0"
                  }
                }. You need to either disable that node (in node details > settings > Node State) or update agent version " +
                s"on that node.")
      }
      // as we don't have capalities and version is not reliable, we only fail when we are sure:
      // - version provided and
      //   - starts by 2.x, 3.x, 4.x, 5.x for Unix
      //   - agent is DSC < 6.1
      val onlySyslogSupported = nodeInfo.agentsName.find { agent => versionHasSyslogOnly(agent.version, agent.agentType) }

      (onlySyslogSupported match {
        case Some(agentInfo)  =>
          // If HTTPS is used on a node that does support it, we fails.
          // Also, special case root, because not having root cause strange things.
          if(nodeInfo.id == Constants.ROOT_POLICY_SERVER_ID) Full(AgentReportingHTTPS)
          else failure(nodeInfo, agentInfo)
        case None             => Full(AgentReportingHTTPS)
      }).map { reportingProtocol =>
        val v = systemVariableSpecService.get("REPORTING_PROTOCOL").toVariable(Seq(reportingProtocol.value))
        (v.spec.name, v)
      }
    }

    import  net.liftweb.json.{prettyRender,JObject,JString,JField}
    // Utilitaty method to convert NodeInfo to JSON
    def nodeInfoToJson(nodeInfo: NodeInfo) : List[JField] = {
      JField("hostname", JString(nodeInfo.hostname)) ::
      JField("policyServerId", JString(nodeInfo.policyServerId.value)) ::
      JField("localAdministratorAccountName", JString(nodeInfo.localAdministratorAccountName)) ::
      JField("archDescription", JString(nodeInfo.archDescription.getOrElse(""))) ::
      JField("ram", JString(nodeInfo.ram.map(_.size).getOrElse(0L).toString)) ::
      JField("timezone", JString(nodeInfo.timezone.map(_.name).getOrElse(""))) ::
      JField("os", JObject(
                     JField("name", JString(nodeInfo.osDetails.os.name)) ::
                     JField("fullName", JString(nodeInfo.osDetails.fullName)) ::
                     JField("version", JString(nodeInfo.osDetails.version.value)) ::
                     JField("kernelVersion", JString(nodeInfo.osDetails.kernelVersion.value)) ::
                     JField("servicePack", JString(nodeInfo.osDetails.servicePack.getOrElse(""))) ::
                     Nil))  ::
      JField("machine", JObject(
                     JField("machineType", JString(nodeInfo.machine.map(_.machineType.toString).getOrElse(""))) ::
                     JField("manufacturer", JString(nodeInfo.machine.flatMap(_.manufacturer.map(_.name)).getOrElse(""))) ::
                     Nil))  :: Nil
    }
    val varRudderInventoryVariables = systemVariableSpecService.get("RUDDER_INVENTORY_VARS").toVariable(Seq(prettyRender(JObject(nodeInfoToJson(nodeInfo)))))

    val baseVariables = {
      Seq(
          varRudderNodeKind
        , varAllowedNetworks
        , varNodeConfigVersion
        , varNodeGroups
        , varNodeGroupsClasses
        , varRudderInventoryVariables
        , varPolicyServerKeyHashCfengine
        , varPolicyServerKeyHashB64Sha256
      ) map (x => (x.spec.name, x))
    }

    val variables = globalSystemVariables ++ baseVariables ++ policyServerVars

    (agentRunVariables, varNodeReportingProtocol) match {
      case (Right(runValues), Full(reporting))  =>
        Full(variables ++ runValues + reporting)
      case (f1, f2:Failure) =>
        // prefer message on reporting mode
        f2
      case (fail, _) =>
        fail.toBox
    }
  }

  // obtaining variable values from (failable) properties.
  // If property fails, variable will be empty.
  private[this] def getProp[T](specName: String, getter: () => Box[T]): SystemVariable = {
      //try to get the user configured value, else log an error and use the default value.
      val variable = systemVariableSpecService.get(specName).toVariable()

      getter().flatMap(value => variable.copyWithSavedValue(value.toString).toBox) match {
        case Full(v) =>
          v
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
  ): PureResult[String] = {

    val minutesFreq = executionInterval % 60
    val hoursFreq: Int = executionInterval / 60

    (minutesFreq, hoursFreq) match {
      case (m, h) if m > 0 && h > 0 => Left(Inconsistency(s"Agent execution interval can only be defined as minutes (less than 60) or complete hours, (${h} hours ${m} minutes is not supported)"))
      case (m, h) if h == 0 =>
        // two cases, hour is 0, then only minutes

        // let's modulate startMinutes by minutes
        val actualStartMinute = startMinute % minutesFreq
        val mins = Range(actualStartMinute, 60, minutesFreq) // range doesn't return the end range
        //val mins = for ( min <- 0 to 59; if ((min%minutesFreq) == actualStartMinute) ) yield { min }
        Right(mins.map("\"Min" + "%02d".format(_) + "\"").mkString(", "))

      case _ =>
        // hour is not 0, then we don't have minutes
        val actualStartHour = startHour % hoursFreq
        val hours = Range(actualStartHour, 24, hoursFreq)
        val minutesFormat = "Min" + "%02d".format(startMinute)
        Right(hours.map("\"Hr" + "%02d".format(_) + "." + minutesFormat + "\"").mkString(", "))
    }

  }

}
