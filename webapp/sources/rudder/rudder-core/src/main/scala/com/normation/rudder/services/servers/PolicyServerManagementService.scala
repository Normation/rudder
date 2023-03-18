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

package com.normation.rudder.services.servers

import ca.mrvisser.sealerate
import cats.data.NonEmptyList
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.errors._
import com.normation.eventlog.EventActor
import com.normation.eventlog.EventLogDetails
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.NodeId
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants
import com.normation.rudder.domain.appconfig.RudderWebPropertyName
import com.normation.rudder.domain.eventlog.AuthorizedNetworkModification
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.domain.eventlog.UpdatePolicyServer
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.policies.PolicyServerTarget
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.policies.Tags
import com.normation.rudder.domain.queries.AgentComparator
import com.normation.rudder.domain.queries.And
import com.normation.rudder.domain.queries.Criterion
import com.normation.rudder.domain.queries.CriterionLine
import com.normation.rudder.domain.queries.Equals
import com.normation.rudder.domain.queries.NodeAndRootServerReturnType
import com.normation.rudder.domain.queries.NodeCriterionMatcherString
import com.normation.rudder.domain.queries.ObjectCriterion
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.domain.queries.ResultTransformation
import com.normation.rudder.domain.queries.StringComparator
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.services.servers.json._
import com.normation.zio._
import com.softwaremill.quicklens._
import com.unboundid.ldap.sdk.DN
import net.liftweb.common.Box
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import zio._
import zio.json._
import zio.syntax._

/**
 * This service allows to manage properties linked to the root policy server,
 * like authorized network, policy server hostname, etc.
 *
 * For now, the hypothesis that there is one and only one policy server is made.
 */
trait PolicyServerManagementService {

  /**
   * Get policy servers
   */
  def getPolicyServers(): IOResult[PolicyServers]

  /**
   * Save the policy servers , used by the plugin to create the entry
   * for the new policy server
   */
  def savePolicyServers(policyServers: PolicyServers): IOResult[PolicyServers]

  /**
   * Get the list of allowed networks, i.e the list of networks such that
   * a node with an IP in it can ask for updated policies..
   *
   * If the node is not present in the list of policy server, and empty list is returned,
   * not an error.
   *
   * As of rudder 7.0, the list of authorized network is stored in a settings entry in json,
   * no more in relay's directives
   */
  def getAllowedNetworks(policyServerId: NodeId): IOResult[List[AllowedNetwork]] = {
    getAllAllowedNetworks().map(_.getOrElse(policyServerId, Nil))
  }

  /**
   * Get the list of allowed network for all nodes.
   */
  def getAllAllowedNetworks(): IOResult[Map[NodeId, List[AllowedNetwork]]] = {
    for {
      servers <- getPolicyServers()
    } yield {
      (servers.root :: servers.relays).map(s => (s.id, s.allowedNetworks)).toMap
    }
  }

  /**
   * Update the list of policy server with the given add, delete or update command.
   * The list of all commands is executed atomically, so several updates will
   */
  def updatePolicyServers(
      commands: List[PolicyServersUpdateCommand],
      modId:    ModificationId,
      actor:    EventActor
  ): IOResult[PolicyServers]

  /**
   * Update the list of authorized networks with the given list.
   * Return the new list of authorized network for the node.
   */
  def setAllowedNetworks(
      policyServerId: NodeId,
      networks:       Seq[AllowedNetwork],
      modId:          ModificationId,
      actor:          EventActor
  ): IOResult[List[AllowedNetwork]] = {
    val command = PolicyServersUpdateCommand.Update((s: PolicyServer) => {
      if (s.id == policyServerId) {
        PolicyServerUpdateAction.SetNetworks(networks.toList) :: Nil
      } else {
        Nil
      }
    })
    updatePolicyServers(command :: Nil, modId, actor).map(zoomAllowedNetworks(policyServerId))
  }

  /**
   * Update the list of authorized networks with the given diff.
   * Return the new list of authorized network for the node.
   */
  def updateAllowedNetworks(
      policyServerId: NodeId,
      addNetworks:    Seq[AllowedNetwork],
      deleteNetwork:  Seq[String],
      modId:          ModificationId,
      actor:          EventActor
  ): IOResult[List[AllowedNetwork]] = {
    val command = PolicyServersUpdateCommand.Update((s: PolicyServer) => {
      if (s.id == policyServerId) {
        (addNetworks.map(PolicyServerUpdateAction.AddNetwork(_)) ++ deleteNetwork.map(
          PolicyServerUpdateAction.DeleteNetwork(_)
        )).toList
      } else {
        Nil
      }
    })
    updatePolicyServers(command :: Nil, modId, actor).map(zoomAllowedNetworks(policyServerId))
  }

  /**
   * Delete things related to a relay (groups, rules, directives)
   */
  def deleteRelaySystemObjects(policyServerId: NodeId): IOResult[Unit]

  // utility to "zoom" on the allowed network of one node
  protected[servers] def zoomAllowedNetworks(policyServerId: NodeId)(policyServers: PolicyServers): List[AllowedNetwork] = {
    if (policyServerId == Constants.ROOT_POLICY_SERVER_ID) policyServers.root.allowedNetworks
    else policyServers.relays.collectFirst { case s if s.id == policyServerId => s.allowedNetworks }.getOrElse(Nil)
  }

}

object PolicyServerManagementService {

  /*
   * Apply all commands to policy servers. Return an updated structure.
   */
  def applyCommands(servers: PolicyServers, commands: List[PolicyServersUpdateCommand]): IOResult[PolicyServers] = {
    ZIO.foldLeft(commands)(servers) {
      case (old, command) =>
        applyOneCommand(old, command)
    }
  }

  // apply the given command to the list of server and return the new list of server
  def applyOneCommand(servers: PolicyServers, command: PolicyServersUpdateCommand): IOResult[PolicyServers] = {
    command match {
      case PolicyServersUpdateCommand.Delete(policyServerId) =>
        if (policyServerId == Constants.ROOT_POLICY_SERVER_ID) {
          Inconsistency(s"Root policy server can not be deleted").fail
        } else {
          servers.modify(_.relays).using(_.filterNot(_.id == policyServerId).toList).succeed
        }
      case PolicyServersUpdateCommand.Add(policyServer)      =>
        if (policyServer.id == Constants.ROOT_POLICY_SERVER_ID) {
          Inconsistency(s"Root policy server can not be added again").fail
        } else {
          servers.modify(_.relays).using(s => (policyServer :: s).sortBy(_.id.value)).succeed
        }
      case PolicyServersUpdateCommand.Update(actions)        =>
        for {
          root   <- applyAllActions(actions, servers.root)
          relays <- ZIO.foreach(servers.relays)(applyAllActions(actions, _))
        } yield {
          PolicyServers(root, relays)
        }
    }
  }

  def debugStringActions(actions: List[PolicyServerUpdateAction]): String = {
    val setTo   = actions.collect { case PolicyServerUpdateAction.SetNetworks(inets) => inets.map(_.inet) } match {
      case Nil => ""
      case l   => s"set allowed networks to: ${l.mkString(", ")}"
    }
    val adds    = actions.collect { case PolicyServerUpdateAction.AddNetwork(inet) => inet.inet } match {
      case Nil => ""
      case l   => s"add allowed network(s): ${l.mkString(", ")}"
    }
    val deletes = actions.collect { case PolicyServerUpdateAction.DeleteNetwork(inet) => inet } match {
      case Nil => ""
      case l   => s"delete allowed network(s): ${l.mkString(", ")}"
    }
    val updates = actions.collect { case PolicyServerUpdateAction.UpdateNetworks(mod) => "" } match {
      case Nil => ""
      case l   => s"free modification(s) on allowed networks: ${l.size}"
    }
    val mods    = setTo :: adds :: deletes :: updates :: Nil
    s"[${mods.mkString("][")}]"
  }

  // we want to try all action for each server so that if there is multiple error, they are all
  // reported, but still have a "foldLeft" semantic, ie the policy server have all change in turn.
  def applyAllActions(actions: PolicyServer => List[PolicyServerUpdateAction], s: PolicyServer): IOResult[PolicyServer] = {
    val a = actions(s)
    if (a.isEmpty) {
      s.succeed
    } else {
      ApplicationLoggerPure.debug(s"Update action for policy server '${s.id.value}': ${debugStringActions(a)}")
      ZIO
        .foldLeft(a)((List.empty[RudderError], s)) {
          case ((err, s), a) =>
            applyOneAction(s, a).either.map {
              case Left(e)        => (e :: err, s)
              case Right(updated) => (err, updated)
            }
        }
        .flatMap {
          case (Nil, s)    => s.succeed
          case (h :: e, _) => Accumulated(NonEmptyList(h, e)).fail
        }
    }
  }

  // try to apply one action. Can fail.
  def applyOneAction(server: PolicyServer, action: PolicyServerUpdateAction): IOResult[PolicyServer] = {
    // filter out bad networks
    def validNets(networks: List[AllowedNetwork]): IOResult[Unit] = {
      networks
        .accumulate(net => {
          if (AllowedNetwork.isValid(net.inet)) ZIO.unit
          else Inconsistency(s"Allowed network '${net}' is not a valid network syntax").fail
        })
        .unit
    }

    action match {
      case PolicyServerUpdateAction.SetNetworks(allowedNetworks) =>
        ApplicationLoggerPure.trace(
          s"Update allowed networks for policy server '${server.id.value}', set to: ${allowedNetworks.map(_.inet).mkString(", ")}"
        ) *>
        validNets(allowedNetworks) *>
        server.modify(_.allowedNetworks).setTo(allowedNetworks).succeed
      case PolicyServerUpdateAction.DeleteNetwork(inet)          =>
        ApplicationLoggerPure.trace(s"Update allowed networks for policy server '${server.id.value}', delete: ${inet}") *>
        server.modify(_.allowedNetworks).using(_.filterNot(_.inet == inet)).succeed
      case PolicyServerUpdateAction.AddNetwork(allowedNetwork)   =>
        ApplicationLoggerPure.trace(
          s"Update allowed networks for policy server '${server.id.value}', add: ${allowedNetwork.inet}"
        ) *>
        validNets(allowedNetwork :: Nil) *> server.modify(_.allowedNetworks).using(allowedNetwork :: _).succeed
      case PolicyServerUpdateAction.UpdateNetworks(update)       =>
        val updated = server.allowedNetworks.map(update)
        ApplicationLoggerPure.trace(s"Update allowed networks for policy server '${server.id.value}' with a free modification") *>
        validNets(updated) *> server.modify(_.allowedNetworks).setTo(updated).succeed
    }
  }

}

/*
 * Implementation of policy server management backed in LDAP.
 * We store the json serialisation of policy server in a settings,
 * but we can't use config service since it's not in rudder-core.
 * Setting name: rudder_policy_servers
 */
class PolicyServerManagementServiceImpl(
    ldap:         LDAPConnectionProvider[RwLDAPConnection],
    dit:          RudderDit,
    eventLogRepo: EventLogRepository
) extends PolicyServerManagementService with Loggable {
  val PROP_NAME = "rudder_policy_servers"

  /*
   * we ensure that write operation on relays are under a lock.
   * The lock is global to all policy server, so avoid changing
   * their allowed networks in parallel.
   */
  val lock = Semaphore.make(1).runNow

  private def getDefaultSettingEntryIfMissing = {
    val entry = dit.APPCONFIG.propertyModel(RudderWebPropertyName(PROP_NAME))
    entry.resetValuesTo(RudderLDAPConstants.A_PROPERTY_VALUE, """[{"id":"root","allowed-networks":[]}]""")
    entry
  }

  private def getLdap(con: RwLDAPConnection) = {
    for {
      entry   <- con.get(dit.APPCONFIG.propertyDN(RudderWebPropertyName(PROP_NAME))).map {
                   case Some(e) => e
                   case None    => getDefaultSettingEntryIfMissing
                 }
      json    <-
        entry(RudderLDAPConstants.A_PROPERTY_VALUE).notOptional(
          s"Value for policy servers is empty, while we should always have root server defined. It may be a bug, please report it."
        )
      servers <- json.fromJson[JPolicyServers].toIO
    } yield {
      (entry, servers.toPolicyServers())
    }
  }

  /*
   * Get policy servers. If the setting property is missing (for example in migration), returns just root with
   * no allowed networks.
   */
  override def getPolicyServers(): IOResult[PolicyServers] = {
    for {
      con     <- ldap
      servers <- getLdap(con)
    } yield {
      servers._2
    }
  }

  override def savePolicyServers(policyServers: PolicyServers): IOResult[PolicyServers] = {
    lock.withPermit(for {
      con      <- ldap
      pair     <- getLdap(con)
      (e, orig) = pair
      value     = JPolicyServers.from(policyServers).toJson
      _         = e.resetValuesTo(RudderLDAPConstants.A_PROPERTY_VALUE, value)
      _        <- con.save(e)
    } yield {
      policyServers
    })
  }

  /*
   * All update commads must be in the atomic bound, so the general logic is
   * - we enter lock,
   * - get the whole of servers,
   * - apply commands,
   * - store result if successful,
   * - out of lock.
   *
   * Command can fail, for ex user may want to update an network with an incorrect value. We accumulate these error for the
   * whole list of command.
   */
  def updatePolicyServers(
      commands: List[PolicyServersUpdateCommand],
      modId:    ModificationId,
      actor:    EventActor
  ): IOResult[PolicyServers] = {

    // Save modified network. Only inet is taken into account
    def saveAllowedNetworkEventLog(
        orig:    PolicyServers,
        updated: PolicyServers,
        modId:   ModificationId,
        actor:   EventActor
    ): IOResult[Unit] = {
      val origNet    = (orig.root :: orig.relays).map(s => (s.id, s.allowedNetworks.map(_.inet))).toMap
      val updatedNet = (updated.root :: updated.relays).map(s => (s.id, s.allowedNetworks.map(_.inet))).toMap

      val toLog = (origNet.keySet ++ updatedNet.keySet).toList.flatMap { id =>
        val old     = origNet.getOrElse(id, Nil)
        val current = updatedNet.getOrElse(id, Nil)
        if (old == current) {
          None
        } else {
          Some(UpdatePolicyServer.buildDetails(AuthorizedNetworkModification(old, current)))
        }
      }
      ZIO
        .foreach(toLog) { log =>
          eventLogRepo.saveEventLog(
            modId,
            UpdatePolicyServer(
              EventLogDetails(
                modificationId = Some(modId),
                principal = actor,
                details = log,
                reason = None
              )
            )
          )
        }
        .unit
    }

    lock.withPermit(for {
      con      <- ldap
      pair     <- getLdap(con)
      (e, orig) = pair
      updated  <- PolicyServerManagementService.applyCommands(orig, commands)
      value     = JPolicyServers.from(updated).toJson
      _         = e.resetValuesTo(RudderLDAPConstants.A_PROPERTY_VALUE, value)
      saved    <- con.save(e)
      logged   <- saveAllowedNetworkEventLog(orig, updated, modId, actor)
    } yield {
      updated
    })
  }

  /**
   * Delete things related to a relay:
   * - group: nodeGroupId=hasPolicyServer-${uuid},groupCategoryId=SystemGroups,groupCategoryId=GroupRoot,ou=Rudder,cn=rudder-configuration
   * - rule target:  ruleTarget=policyServer:${RELAY_UUID},groupCategoryId=SystemGroups,groupCategoryId=GroupRoot,ou=Rudder,cn=rudder-configuration
   * - directive:  directiveId=${RELAY_UUID}-distributePolicy,activeTechniqueId=distributePolicy,techniqueCategoryId=Rudder Internal,techniqueCategoryId=Active Techniques,ou=Rudder,cn=rudder-configuration
   * - directive: directiveId=common-${RELAY_UUID},activeTechniqueId=common,techniqueCategoryId=Rudder Internal,techniqueCategoryId=Active Techniques,ou=Rudder,cn=rudder-configuratio
   * - rule: ruleId=${RELAY_UUID}-DP,ou=Rules,ou=Rudder,cn=rudder-configuration
   * - rule: ruleId=hasPolicyServer-${RELAY_UUID},ou=Rules,ou=Rudder,cn=rudder-configuration
   * - allowed networks
   */
  override def deleteRelaySystemObjects(policyServerId: NodeId): IOResult[Unit] = {
    lock.withPermit {
      if (policyServerId == Constants.ROOT_POLICY_SERVER_ID) {
        Inconsistency("Root server configuration elements can't be deleted").fail
      } else { // we don't have specific validation to do: if the node is not a policy server, nothing will be done
        def DN(child: String, parentDN: DN) = new DN(child + "," + parentDN.toString)
        val id                              = policyServerId.value

        for {
          con <- ldap
          _   <- con.delete(DN(s"nodeGroupId=hasPolicyServer-${id}", dit.GROUP.SYSTEM.dn))
          _   <- con.delete(DN(s"ruleTarget=policyServer:${id}", dit.GROUP.SYSTEM.dn))
          _   <- con.delete(
                   DN(
                     s"directiveId=${id}-distributePolicy,activeTechniqueId=distributePolicy,techniqueCategoryId=Rudder Internal",
                     dit.ACTIVE_TECHNIQUES_LIB.dn
                   )
                 )
          _   <- con.delete(
                   DN(
                     s"directiveId=common-${id},activeTechniqueId=common,techniqueCategoryId=Rudder Internal",
                     dit.ACTIVE_TECHNIQUES_LIB.dn
                   )
                 )
          _   <- con.delete(DN(s"ruleId=${id}-DP", dit.RULES.dn))
          _   <- con.delete(DN(s"ruleId=hasPolicyServer-${id}", dit.RULES.dn))
          _    = ApplicationLogger.info(
                   s"System configuration object (rules, directives, groups) related to relay '${id}' were successfully deleted."
                 )
        } yield ()
      }
    } *> updatePolicyServers(
      PolicyServersUpdateCommand.Delete(policyServerId) :: Nil,
      ModificationId(s"clean-${policyServerId.value}"),
      RudderEventActor
    ).unit
  }
}

sealed trait RelaySynchronizationMethod { def value: String }
object RelaySynchronizationMethod       {

  final case object Classic extends RelaySynchronizationMethod { val value = "classic" }

  final case object Rsync extends RelaySynchronizationMethod { val value = "rsync" }

  final case object Disabled extends RelaySynchronizationMethod { val value = "disabled" }

  final val all: Set[RelaySynchronizationMethod] = sealerate.values[RelaySynchronizationMethod]

  def parse(value: String): Box[RelaySynchronizationMethod] = {
    value match {
      case null | "" => Failure("An empty or null string can not be parsed as a relay synchronization method")
      case s         =>
        s.trim.toLowerCase match {
          case Classic.value  => Full(Classic)
          case Disabled.value => Full(Disabled)
          case Rsync.value    => Full(Rsync)
          case _              =>
            Failure(
              s"Cannot parse the given value as a valid relay synchronization method: '${value}'. Authorised values are: '${all.map(_.value).mkString(", ")}'"
            )
        }
    }
  }
}

/*
 * System directives / rules / etc for a policy server.
 */
final case class PolicyServerConfigurationObjects(
    directives: Map[TechniqueName, Directive],
    groups:     List[NodeGroup],
    targets:    List[PolicyServerTarget],
    rules:      List[Rule]
)

object PolicyServerConfigurationObjects {
  val v1_0: TechniqueVersion = TechniqueVersion
    .parse("1.0")
    .getOrElse(throw new RuntimeException(s"Error in default version data, this is likely a bug. Please report it."))

  implicit class ToDirective(id: String) {
    def toDirective = Directive(
      DirectiveId(DirectiveUid(id)),
      v1_0,
      Map(),
      id,
      s"",
      None,
      "",
      0,
      _isEnabled = true,
      isSystem = true,
      Tags(Set.empty)
    )
  }

  val relayTechniques = List("server-common", "rudder-service-apache", "rudder-service-relayd")
  val rootTechniques  = List("rudder-service-postgresql", "rudder-service-slapd", "rudder-service-webapp") ::: relayTechniques

  def directiveCommonHasPolicyServer(nodeId: NodeId) = {
    TechniqueName("common") ->
    s"common-hasPolicyServer-${nodeId.value}".toDirective
      .modify(_.parameters)
      .setTo(
        Map(
          "OWNER"              -> Seq("${rudder.node.admin}"),
          "UUID"               -> Seq("${rudder.node.id}"),
          "POLICYSERVER_ID"    -> Seq("${rudder.node.policyserver.id}"),
          "POLICYSERVER_ADMIN" -> Seq("${rudder.node.policyserver.admin}")
        )
      )
      .modify(_.name)
      .setTo(s"Common - ${nodeId.value}")
      .modify(_.shortDescription)
      .setTo(s"Common policy for nodes with '${nodeId.value}' for policy server")
  }

  def directiveServerCommon(nodeId: NodeId) = {
    TechniqueName("server-common") ->
    s"server-common-${nodeId.value}".toDirective
      .modify(_.name)
      .setTo(s"Server Common - ${nodeId.value}")
      .modify(_.shortDescription)
      .setTo(s"Common policy for policy server with '${nodeId.value}'")
  }

  def directiveServices(nodeId: NodeId) = {
    List("apache", "postgresql", "relayd", "slapd", "webapp").map(name => {
      TechniqueName(s"rudder-service-${name}") ->
      s"rudder-service-${name}-${nodeId.value}".toDirective
        .modify(_.name)
        .setTo(s"Rudder ${name.capitalize} - ${nodeId.value}")
        .modify(_.shortDescription)
        .setTo(s"Manage ${name} rudder service")
    })
  }

  def groupHasPolicyServer(nodeId: NodeId) = {
    val objectType = ObjectCriterion(
      "node",
      Seq(
        Criterion(
          "policyServerId",
          StringComparator,
          NodeCriterionMatcherString(n => Chunk(n.rudderSettings.policyServerId.value)),
          None
        ),
        Criterion("agentName", AgentComparator, NodeCriterionMatcherString(n => Chunk(n.rudderAgent.agentType.id)), None)
      )
    )
    NodeGroup(
      NodeGroupId(NodeGroupUid(s"hasPolicyServer-${nodeId.value}")),
      s"All nodes managed by '${nodeId.value}' policy server",
      s"All nodes known by Rudder directly connected to the '${nodeId.value}' server. This group exists only as internal purpose and should not be used to configure Nodes.",
      Nil,
      Some(
        Query(
          NodeAndRootServerReturnType,
          And,
          ResultTransformation.Identity,
          List(
            CriterionLine(
              objectType,
              Criterion("agentName", StringComparator, NodeCriterionMatcherString(n => Chunk(n.rudderAgent.agentType.id))),
              Equals,
              "cfengine"
            ),
            CriterionLine(
              objectType,
              Criterion(
                "policyServerId",
                StringComparator,
                NodeCriterionMatcherString(n => Chunk(n.rudderSettings.policyServerId.value))
              ),
              Equals,
              nodeId.value
            )
          )
        )
      ),
      isDynamic = true,
      Set(),
      _isEnabled = true,
      isSystem = true
    )
  }

  def ruleCommonHasPolicyServer(nodeId: NodeId) = {
    Rule(
      RuleId(RuleUid(s"hasPolicyServer-${nodeId.value}")),
      s"Rudder system policy: basic setup (common) - ${nodeId.value}",
      RuleCategoryId("rootRuleCategory"),
      Set(GroupTarget(NodeGroupId(NodeGroupUid(s"hasPolicyServer-${nodeId.value}")))),
      Set(DirectiveId(DirectiveUid(s"common-hasPolicyServer-${nodeId.value}"))),
      "Common - Technical",
      "This is the basic system rule which all nodes must have.",
      isEnabledStatus = true,
      isSystem = true
    )
  }

  def rulePolicyServer(nodeId: NodeId, techniques: List[String]) = {
    Rule(
      RuleId(RuleUid(s"policy-server-${nodeId.value}")),
      s"Rule for policy server ${nodeId.value}",
      RuleCategoryId("rootRuleCategory"),
      Set(PolicyServerTarget(nodeId)),
      techniques.map(t => DirectiveId(DirectiveUid(s"${t}-${nodeId.value}"))).toSet,
      "Server components configuration - Technical",
      s"This rule allows to configure the rudder ${nodeId.value} server",
      isEnabledStatus = true,
      isSystem = true
    )
  }

  /*
   * Get system configuration objects (directives, groups, rules) for policyServerId
   */
  def getConfigurationObject(policyServerId: NodeId): PolicyServerConfigurationObjects = {
    val techniques = if (policyServerId == Constants.ROOT_POLICY_SERVER_ID) {
      rootTechniques
    } else {
      relayTechniques
    }

    PolicyServerConfigurationObjects(
      Map(directiveServerCommon(policyServerId), directiveCommonHasPolicyServer(policyServerId))
      ++ directiveServices(policyServerId).filter { case (t, d) => techniques.contains(t.value) },
      groupHasPolicyServer(policyServerId) :: Nil,
      PolicyServerTarget(policyServerId) :: Nil,
      ruleCommonHasPolicyServer(policyServerId) :: rulePolicyServer(policyServerId, techniques) :: Nil
    )
  }

}
