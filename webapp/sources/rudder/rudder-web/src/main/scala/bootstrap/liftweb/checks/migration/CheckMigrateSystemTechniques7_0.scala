/*
*************************************************************************************
* Copyright 2021 Normation SAS
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

package bootstrap.liftweb.checks.migration

import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.cfclerk.services.UpdateTechniqueLibrary
import com.normation.eventlog.ModificationId

import bootstrap.liftweb.BootstrapChecks

import zio._
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.Constants
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.rudder.domain.RudderLDAPConstants
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.domain.logger.MigrationLoggerPure
import com.normation.rudder.domain.policies.ActiveTechniqueId
import com.normation.rudder.git.GitRepositoryProvider
import com.normation.rudder.repository.WoDirectiveRepository
import com.normation.rudder.repository.WoRuleRepository
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.servers.AllowedNetwork
import com.normation.rudder.services.servers.PolicyServer
import com.normation.rudder.services.servers.PolicyServerConfigurationObjects
import com.normation.rudder.services.servers.PolicyServerManagementService
import com.normation.rudder.services.servers.PolicyServers
import com.normation.utils.StringUuidGenerator

import com.unboundid.ldap.sdk.DN

import zio.syntax._
import com.normation.errors._
import com.normation.zio._
import com.softwaremill.quicklens._
import org.eclipse.jgit.lib.PersonIdent

/*
 * This migration check looks if we need to migrate a 6.x Rudder to a 7.x set of
 * techniques/directives/group/rules.
 * See https://issues.rudder.io/issues/19650 for details of migration
 */

class CheckMigratedSystemTechniques(
    policyServerService  : PolicyServerManagementService
  , gitRepo              : GitRepositoryProvider
  , nodeInfoService      : NodeInfoService
  , ldap                 : LDAPConnectionProvider[RwLDAPConnection]
  , techniqueRepo        : TechniqueRepository
  , techniqueLibUpdater  : UpdateTechniqueLibrary
  , uuidGen              : StringUuidGenerator
  , directiveRepo        : WoDirectiveRepository
  , ruleRepo             : WoRuleRepository
  ) extends BootstrapChecks {

  val allowedNetworks6_x_7_0 = new MigrateAllowedNetworks6_x_7_0(policyServerService, ldap)
  val migrateConfigs6_x_7_0 = new MigrateTechniques6_x_7_0(ldap, gitRepo, techniqueRepo, techniqueLibUpdater, uuidGen, directiveRepo, ruleRepo)

  def DN(child: String, parentDN: DN) = new DN(child+","+parentDN.toString)


  override def description: String = "Check if migration of system configuration object and allowed networks to Rudder 7.0 format is needed"


  override def checks() : Unit = {
    ZIO.whenM(checkMigrationNeeded())(
      for {
        servers <- getPolicyServerIds
        _       <- allowedNetworks6_x_7_0.createNewAllowedNetworks(servers)
        _       <- migrateConfigs6_x_7_0.createNewConfigsAll(servers)
        _       <- migrateConfigs6_x_7_0.finalMoveAndCleanAll(servers)
      } yield ()
    ).catchAll(err =>
      MigrationLoggerPure.error(s"Error during migration of policy server system configuration to Rudder V7.0 data. It is likely that Rudder won't " +
                                s"work as expected. Please check the log above. Error was: ${err.fullMsg}")
    ).runNow
  }


  /*
   * We rename in last position rule 'root-DP' (distribute policy for root server). So if that rule exists,
   * migration is needed.
   */
  def checkMigrationNeeded(): IOResult[Boolean] = {
    for {
      con <- ldap
      res <- con.exists(SystemConfig.v6_2.techniqueDistributePolicy.dn)
    } yield res
  }

  /*
   * Get policy servers that need to be migrated. We migrate policy servers even if the scale-out-relay plugin is
   * not present.
   * We only migrate date for currently present policy servers, ie for accepted nodes that have the "rudderPolicyServer"
   * object class and are not root (which is migrated separatly because of its special needs).
   */
  def getPolicyServerIds: IOResult[List[NodeId]] = nodeInfoService.getAll().map(_.collect {
    case (id, n) if n.isPolicyServer => id
  }.toList)



}

object SystemConfig {

  object Constants6_2 {
    val ALLOWED_NETWORKS_PROPERTY = "ALLOWEDNETWORK"
  }

  val systemTechniqueBaseDn = new DN("techniqueCategoryId=Rudder Internal,techniqueCategoryId=Active Techniques,ou=Rudder,cn=rudder-configuration")
  val ruleBaseDn = new DN("ou=Rules,ou=Rudder,cn=rudder-configuration")

  final case class AT(id: String) {
    val dn = new DN(s"activeTechniqueId=${id},${systemTechniqueBaseDn.toString}")
  }
  sealed trait Dir { def id: String ; def at: AT ; def dn: DN }
  final case class D(id: String, at: AT)

  final case class T(id: String) {
    val dn = new DN(s"ruleTarget=${id},groupCategoryId=SystemGroups,groupCategoryId=GroupRoot,ou=Rudder,cn=rudder-configuration")
  }
  final case class R(id: String) {
    val dn = new DN(s"ruleId=${id},${ruleBaseDn.toString}")
    def dnWithPrefix(s: String) = new DN(s"ruleId=${s}${id},${ruleBaseDn.toString}")
  }

  object v6_2 {
    final case class DirectiveCommon(policyServerId: NodeId, at: AT) {
      val id = s"common-${policyServerId.value}"
      val dn = new DN(s"directiveId=${id},${at.dn.toString}")
    }
    final case class DirectiveDistributePolicy(policyServerId: NodeId, at: AT) {
      val id = s"common-${policyServerId.value}"
      val dn = new DN(s"directiveId=${id},${at.dn.toString}")
    }

    val serverRoleNoRoleTarget = T("special:all_nodes_without_role")
    val serverRoleAllRoleTarget = T(s"special:all_servers_with_role")

    val techniqueCommon = AT("common")
    val techniqueDistributePolicy = AT("distributePolicy")
    val techniqueInventory = AT("inventory")
    val techniqueServerRoles = AT("server-roles")

    val ruleServerRole = R("server-roles")

    def dirCommon(policyServerId: NodeId) = DirectiveCommon(policyServerId, techniqueCommon)


    def ruleDistributePolicy(policyServerId: NodeId) = R(s"${policyServerId.value}-DP")
    def ruleHasPolicyServer(policyServerId: NodeId) = R(s"hasPolicyServer-${policyServerId.value}")

    // for root
    val rootDirCommon = dirCommon(Constants.ROOT_POLICY_SERVER_ID)
    val rootRuleDistributPolicy = ruleDistributePolicy(Constants.ROOT_POLICY_SERVER_ID)
    val rootHasPolicyServer = ruleHasPolicyServer(Constants.ROOT_POLICY_SERVER_ID)
  }


  object v7_x {
    def rulePolicyServer(policyServerId: NodeId) = R(s"policy-server-${policyServerId.value}")
    def ruleHasPolicyServer(policyServerId: NodeId) = R(s"hasPolicyServer-${policyServerId.value}")
  }
}


/*
 * This class includes a copy of 6.x "policyServerService" which knew how to get allowed networks from
 * corresponding directives.
 * It is a bit tweaked to work alone and with 7.0 data.
 */
class MigrateAllowedNetworks6_x_7_0(
    policyServerService: PolicyServerManagementService
  , ldap               : LDAPConnectionProvider[RwLDAPConnection]
) {


  /**
   * The list of authorized network is not directly stored in an
   * entry. We have to look for the DistributePolicy directive for
   * that server and the rudderPolicyVariables: ALLOWEDNETWORK
   */
  def getAllowedNetworks6_2(policyServerId: NodeId) : IOResult[Seq[String]] = {
    for {
      con    <- ldap
      dir    =  SystemConfig.v6_2.dirCommon(policyServerId)
      entry  <- con.get(dir.dn, RudderLDAPConstants.A_DIRECTIVE_VARIABLES).notOptional(s"Entry for directive '${dir.id}' was not found, can not migrate allowed network for '${dir.policyServerId.value}'.")
      params =  RudderLDAPConstants.parsePolicyVariables(entry.valuesFor(RudderLDAPConstants.A_DIRECTIVE_VARIABLES).toSeq)
    } yield {
      val allowedNetworks = params.getOrElse(SystemConfig.Constants6_2.ALLOWED_NETWORKS_PROPERTY, List())
      allowedNetworks.toList
    }
  }

  /*
   * Create allowedNetworks with new format for root and possible additionnal relay server given
   * in parameter
   */
  def createNewAllowedNetworks(relayServers: List[NodeId]): IOResult[Unit] = {
    def getOldAllowedNetFor(nodeId: NodeId) = {
      getAllowedNetworks6_2(nodeId).map(ips =>
        PolicyServer(nodeId, ips.map(ip => AllowedNetwork(ip, ip)).toList)
      )
    }

    for {
      root   <- getOldAllowedNetFor(Constants.ROOT_POLICY_SERVER_ID)
      relays <- ZIO.foreach(relayServers)(getOldAllowedNetFor)
      all    =  PolicyServers(root, relays)
      _      <- policyServerService.savePolicyServers(all)
    } yield ()
  }


}

class MigrateTechniques6_x_7_0(
    ldap               : LDAPConnectionProvider[RwLDAPConnection]
  , gitRepo            : GitRepositoryProvider
  , techniqueRepo      : TechniqueRepository
  , techniqueLibUpdater: UpdateTechniqueLibrary
  , uuidGen            : StringUuidGenerator
  , directiveRepo      : WoDirectiveRepository
  , ruleRepo           : WoRuleRepository
) {

  val NEW_RULE_PREFIX = "new_"
  val OLD_RULE_PREFIX = "old_"

  /*
   * We need to have new techniques ready and loaded in technique library to be able to do the migration.
   * If it's not the case, abort.
   * We force a change on technique (placeholder file) + commit + a reload of technique library to avoid
   * to have technique in git but not in ldap because we hit bug https://issues.rudder.io/issues/20003
   */
  def testNewTechniquesLoaded(): IOResult[Unit] = {

    def touchTechniques() = {
      val base = gitRepo.rootDirectory / "techniques" / "system"

      val touchAll = ZIO.foreach(PolicyServerConfigurationObjects.rootTechniques) { t =>
        val f = base / t / "1.0/placeholder"
        IOResult.effect {
          f.createFileIfNotExists()
          f.append("force reload\n")
        }
      }

      gitRepo.semaphore.withPermit(for {
        _ <- touchAll
        _ <- IOResult.effect(gitRepo.git.add().addFilepattern("techniques/system").call())
        _ <- IOResult.effect(gitRepo.git.commit().setMessage(s"Force reloac of system technique v7.0").setCommitter(new PersonIdent("Rudder", "email not set")).call())
      } yield ())
    }

    val reloadTechLib = techniqueLibUpdater.update(
        ModificationId(uuidGen.newUuid)
      , RudderEventActor
      , Some(s"Reload Technique library to allows migration to 7.x system techniques")
    ).toIO

    val checkActiveTechnique = PolicyServerConfigurationObjects.rootTechniques.accumulate(t =>
      if(techniqueRepo.getByName(TechniqueName(t)).isEmpty) {
        Unexpected(s"New system technique '${t}' is not available in technique repository. " +
                   s"It is necessary to be able to migrate old system configurations to 7.0.").fail
      } else {
        MigrationLoggerPure.debug(s"System technique v7.0 present: '${t}'") *>
        UIO.unit
      }
    ).unit



    MigrationLoggerPure.info(s"Check if all Rudder 7.0 system technique are correctly loaded in technique library") *>
    touchTechniques() *>
    reloadTechLib *>
    checkActiveTechnique.chainError(s"Some system techniques needed to migration" +
                                    s" to Rudder 7.x are missing, trying to reload technique library."
    )
  }


  /*
   * This function assume that all new system techniques are available and loaded in technique library.
   * We also assume that if any target directives/rules are already present, they are coming from a
   * previous failed migration and need to be replaced.
   * Return the list of rule ID that will need to be renamed.
   */
  def createNewConfigsOne(nodeId: NodeId) = {

    val configs = PolicyServerConfigurationObjects.getConfigurationObject(nodeId)
    val modId = new ModificationId(uuidGen.newUuid)
    for {
    // create new directive for the server
    // all directives have new names appart from "inventory", but that directive has no parameter, so not a problem
    // even if we need to abort
      _   <- MigrationLoggerPure.info(s"Creating new system directives for policy server '${nodeId.value}'")
      _   <- ZIO.foreach(configs.directives.toList) { case (t,d) =>
               directiveRepo.saveSystemDirective(ActiveTechniqueId(t.value), d, modId, RudderEventActor, Some(s"Create system directive for rudder v7.0 for policy server '${nodeId.value}'"))
             }
    // groups and targets: nothing to do
    // for rule, we need to use placeholder to not overwrite things for now: we use "new_"
      _   <- MigrationLoggerPure.info(s"Creating new system rules for policy server '${nodeId.value}'")
    rules = configs.rules.map(r => r.modify(_.id.uid.value).using(NEW_RULE_PREFIX + _))
      _   <- ZIO.foreach(rules)(r => ruleRepo.create(r, modId, RudderEventActor, Some(s"Update system rules for rudder v7.0 for policy server '${nodeId.value}'")))
    } yield (rules.map(_.id))
  }

  def createNewConfigsAll(nodeIds: List[NodeId]) = {
    for {
      _ <- testNewTechniquesLoaded()
      _ <- ZIO.foreach(nodeIds)(createNewConfigsOne)
    } yield ()
  }

  /*
   * We need to clean old server roles config objects, which are:
   * - remove active technique and directives and rule "server-roles"
   * - remove target ruleTarget=special:all_nodes_without_role
   * - remove target ruleTarget=special:all_servers_with_role
   * Go in best effort
   */
  def cleanServerRolesConfig(): IOResult[Unit] = {
    val toDelete = List(
        SystemConfig.v6_2.techniqueServerRoles.dn
      , SystemConfig.v6_2.ruleServerRole.dn
      , SystemConfig.v6_2.serverRoleNoRoleTarget.dn
      , SystemConfig.v6_2.serverRoleAllRoleTarget.dn
    )

    ldap.flatMap(con =>
      toDelete.accumulate { dn =>
        MigrationLoggerPure.info(s"Deleting old system object for deprecated server-roles: ${dn.toString()}") *>
        con.delete(dn)
      }
    ).unit
  }

  // only one active technique need to be deleted: distributePolicy
  def cleanDistributePolicy(): IOResult[Unit] = {
    for {
      con <- ldap
      _   <- MigrationLoggerPure.info(s"Deleting old technique '${SystemConfig.v6_2.techniqueDistributePolicy.id}' information")
      _   <- con.delete(SystemConfig.v6_2.techniqueDistributePolicy.dn)
    } yield ()
  }

  /*
   * Do final move of rules for one policy server:
   * - move old rules to old_ID
   * - move new_rules to rules
   * If that work, we can safely delete old things, migration is ok that that policy server
   * Else, we rollback
   */
  def finalMoveAndCleanOne(nodeId: NodeId): IOResult[Unit] = {

    // rollaback: delete already migrated, move back old ones
    def rollback(con: RwLDAPConnection, oldSaved: Ref[List[SystemConfig.R]], migrated:Ref[List[SystemConfig.R]], err: RudderError): IOResult[Nothing] = {
      MigrationLoggerPure.error(s"Error when trying to migrate system technique, rollbacking to 6.2 configuration: ${err.fullMsg}") *>
      (for {
        toDelete   <- migrated.get
        _          <- ZIO.foreach(toDelete) { r => con.delete(r.dnWithPrefix(NEW_RULE_PREFIX)) }
        toMoveBack <- oldSaved.get
        _          <- ZIO.foreach(toMoveBack){ r =>
                        con.move(r.dnWithPrefix(OLD_RULE_PREFIX), r.dn.getParent, Some(r.dn.getRDN))
                      }
      } yield ()) *>
      Inconsistency(s"An error happened while migrating system technique to rudder 7.0. You " +
                    s"will likely need to restart Rudder and report that error: ${err.fullMsg}").fail
    }


    val migrateOne = for {
      con  <- ldap

      // store old rule id with the old_ prefix once moved
      oldSaved <- Ref.make[List[SystemConfig.R]](Nil)
      // store new rule id (without prefix) once moved
      migrated <- Ref.make[List[SystemConfig.R]](Nil)

      // move 6.2 rules from $ruleId to old_$ruleId
      _        <- MigrationLoggerPure.info(s"Starting migration for system rules for policy server '${nodeId.value}''")
      _        <- (ZIO.foreach(List(SystemConfig.v6_2.ruleHasPolicyServer(nodeId), SystemConfig.v6_2.ruleDistributePolicy(nodeId))) { r =>
                    MigrationLoggerPure.debug(s"[${nodeId.value}] move old 6.2 rule '${r.id}' to '${OLD_RULE_PREFIX}${r.id}'") *>
                    con.move(r.dn, r.dn.getParent, Some(r.dnWithPrefix(OLD_RULE_PREFIX).getRDN)) *>
                    oldSaved.update(r :: _)
                  }).catchAll(err => rollback(con, oldSaved, migrated, err))

      // move 7.0 rules from new_$ruleId to $ruleId
      _        <- (ZIO.foreach(List(SystemConfig.v7_x.ruleHasPolicyServer(nodeId), SystemConfig.v7_x.rulePolicyServer(nodeId))) { r =>
                    MigrationLoggerPure.debug(s"[${nodeId.value}] move new 7.0 rule '${NEW_RULE_PREFIX}${r.id}' to '${r.id}'") *>
                    con.move(r.dnWithPrefix(NEW_RULE_PREFIX), r.dn.getParent, Some(r.dn.getRDN)) *>
                    migrated.update(r :: _)
                  }).catchAll(err => rollback(con, oldSaved, migrated, err))

      // now we can clean everything, migration worked! It's not grave if there is error here
      _        <- ZIO.foreach(List(SystemConfig.v6_2.ruleHasPolicyServer(nodeId), SystemConfig.v6_2.ruleDistributePolicy(nodeId))) { r =>
                    MigrationLoggerPure.info(s"[${nodeId.value} techniques] delete old rule '${r.id}'") *>
                    con.delete(r.dnWithPrefix(OLD_RULE_PREFIX))
                  }
      // also clean old directive common for that server
      _        <- MigrationLoggerPure.info(s"[${nodeId.value} techniques] delete old directive 'common-${nodeId.value}'")
      _        <- con.delete(SystemConfig.v6_2.dirCommon(nodeId).dn)
      _        <- MigrationLoggerPure.info(s"Migration of system techniques, directives and rules done for '${nodeId.value}'")
    } yield ()

    // sometime, a policy server can have been migrated without other, in that case: skip it.
    // We know if 7.0 rules are alredy here. We should not have a case with only one given rollback.
    for {
      con         <- ldap
      alreadyDone <- con.exists(SystemConfig.v7_x.rulePolicyServer(nodeId).dn)
      _           <- ZIO.when(!alreadyDone)(migrateOne)
    } yield ()

  }

  def finalMoveAndCleanAll(nodeIds: List[NodeId]) = {
    for {
      // we try to migrate each server
      _ <- ZIO.foreach(nodeIds)(finalMoveAndCleanOne(_))
      // then we clean distribute policy, so that migration is "DONE"
      _ <- cleanDistributePolicy()
      // and finally we clean server-roles
      _ <- cleanServerRolesConfig()
      _ <- MigrationLoggerPure.info(s"Migration of all system configuration to Rudder 7.0: done")
    } yield ()
  }
}
