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

package com.normation.rudder.services.nodes

import com.normation.NamedZioLogger
import com.normation.errors._
import com.normation.errors.IOResult
import com.normation.inventory.domain._
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.ldap.core.InventoryMapper
import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.ldap.sdk._
import com.normation.ldap.sdk.BuildFilter._
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.LDAPIOResult._
import com.normation.ldap.sdk.syntax._
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.NodeDit
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.rudder.domain.logger.NodeLoggerPure
import com.normation.rudder.domain.logger.TimingDebugLogger
import com.normation.rudder.domain.logger.TimingDebugLoggerPure
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.repository.CachedRepository
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.rudder.services.nodes.NodeInfoService.A_MOD_TIMESTAMP
import com.normation.rudder.services.nodes.NodeInfoServiceCached.UpdatedNodeEntries
import com.normation.rudder.services.nodes.NodeInfoServiceCached.buildInfoMaps
import com.normation.zio._
import com.unboundid.ldap.sdk._
import org.joda.time.DateTime
import scala.collection.mutable.{Map => MutMap}
import scala.collection.mutable.Buffer
import scala.concurrent.duration.FiniteDuration
import zio.{System => _, _}
import zio.syntax._

/*
 * General logic for the cache implementation of NodeInfo.
 * The general idea is to limit at maximum:
 * - the number of request to LDAP server (because I/O, slow)
 * - the quantity of data on the wires.
 *
 * The general tactic is to have a local cache (a map of nodeid -> nodeinfo),
 * and before any access, we are checking if an update is necessary with
 * a very quick oracle.
 * The oracle only look for at least one entry modified since the last oracle
 * check, and if one is found, says the cache is dirty.
 *
 * We use an OpenLDAP specific query syntax to be able to perform one query
 * on 3 branches (nodes, inventory node, inventory machine) in one go. As
 * this is OpenLDAP specific, we had to to something a little different
 * for tests, and so we don't exactly test what we are doing for real.
 *
 *
 * Way to enhance current code:
 * - make the oracle be called only every X seconds (because we can tolerate such a
 *   a latency in datas, and frequently, call to nodeinfoservice are done in batch
 *   to fill several kind of informations (it should not, but the code is like that)
 */

/**
 * A case class used to represent the minimal
 * information needed to get a NodeInfo
 */
final case class LDAPNodeInfo(
    nodeEntry:          LDAPEntry,
    nodeInventoryEntry: LDAPEntry,
    machineEntry:       Option[LDAPEntry]
)

trait NodeInfoService {

  /**
   * Return a NodeInfo from a NodeId. First check the ou=Node, then fetch the other data
   */
  def getNodeInfo(nodeId: NodeId): IOResult[Option[NodeInfo]]

  def getNodeInfos(nodeIds: Set[NodeId]): IOResult[Set[NodeInfo]]

  def getNodeInfosSeq(nodeIds: Seq[NodeId]): IOResult[Seq[NodeInfo]]

  /**
   * Return the number of managed (ie non policy server, no rudder role nodes.
   * Implementation of that method must as efficient as possible.
   */
  def getNumberOfManagedNodes: IOResult[Int]

  /**
   * Get all node infos.
   * That method try to return the maximum
   * of information, and will not totally fail if some information are
   * missing (but the corresponding nodeInfos won't be present)
   * So it is possible that getAllIds.size > getAll.size
   */
  def getAll(): IOResult[Map[NodeId, NodeInfo]]

  /**
   * Get all nodes id
   */

  def getAllNodesIds(): IOResult[Set[NodeId]]

  /**
   * Get all nodes.
   * That method try to return the maximum
   * of information, and will not totally fail if some information are
   * missing (but the corresponding nodeInfos won't be present)
   * So it is possible that getAllIds.size > getAll.size
   */
  def getAllNodes(): IOResult[Map[NodeId, Node]]

  /**
   * Get all nodes
   * This returns a Seq for performance reasons - it is much faster
   * to return a Seq than a Set, and for subsequent use it is also
   * faster
   */
  def getAllNodeInfos(): IOResult[Seq[NodeInfo]]

  /**
   * Get all systen node ids, for example
   * policy server node ids.
   * @return
   */
  def getAllSystemNodeIds(): IOResult[Seq[NodeId]]

  /**
   * Getting something like a nodeinfo for pending / deleted nodes
   */
  def getPendingNodeInfos(): IOResult[Map[NodeId, NodeInfo]]
  def getPendingNodeInfo(nodeId: NodeId): IOResult[Option[NodeInfo]]
  def getDeletedNodeInfos(): IOResult[Map[NodeId, NodeInfo]]
  def getDeletedNodeInfo(nodeId: NodeId): IOResult[Option[NodeInfo]]
}

object NodeInfoService {

  /*
   * We need to list actual attributes, because we don't want software ids.
   * Really bad idea, these software ids, in node...
   */
  val nodeInfoAttributes: Seq[String] = (
    Set(
      // for all: objectClass and ldap object creation datatime
      A_OC,
      A_OBJECT_CREATION_DATE, // node
      //  id, name, description, isBroken, isSystem
      // , isPolicyServer <- this one is special and decided based on objectClasss rudderPolicyServer
      // , creationDate, nodeReportingConfiguration, properties

      A_NODE_UUID,
      A_NAME,
      A_DESCRIPTION,
      A_STATE,
      A_IS_SYSTEM,
      A_SERIALIZED_AGENT_RUN_INTERVAL,
      A_SERIALIZED_HEARTBEAT_RUN_CONFIGURATION,
      A_NODE_PROPERTY,
      A_POLICY_MODE, // machine inventory
      // MachineUuid
      // , machineType <- special: from objectClass
      // , systemSerial, manufacturer

      A_MACHINE_UUID,
      A_SERIAL_NUMBER,
      A_MANUFACTURER, // node inventory, safe machine and node (above)
      //  hostname, ips, inventoryDate, publicKey
      // , osDetails
      // , agentsName, policyServerId, localAdministratorAccountName
      // , serverRoles, archDescription, ram, timezone
      // , customProperties

      A_NODE_UUID,
      A_HOSTNAME,
      A_LIST_OF_IP,
      A_INVENTORY_DATE,
      A_PKEYS,
      A_OS_NAME,
      A_OS_FULL_NAME,
      A_OS_VERSION,
      A_OS_KERNEL_VERSION,
      A_OS_SERVICE_PACK,
      A_WIN_USER_DOMAIN,
      A_WIN_COMPANY,
      A_WIN_KEY,
      A_WIN_ID,
      A_AGENTS_NAME,
      A_POLICY_SERVER_UUID,
      A_ROOT_USER,
      A_ARCH,
      A_CONTAINER_DN,
      A_OS_RAM,
      A_KEY_STATUS,
      A_TIMEZONE_NAME,
      A_TIMEZONE_OFFSET,
      A_CUSTOM_PROPERTY
    )
  ).toSeq

  val A_MOD_TIMESTAMP = "modifyTimestamp"

}

/*
 * For test, we need a way to split the cache part from its retrieval.
 */

// our cache is modelized with a Map of entries, their last modification timestamp, and the corresponding entryCSN
final case class LocalNodeInfoCache(
    nodeInfos:       Map[NodeId, (LDAPNodeInfo, NodeInfo)],
    lastModTime:     DateTime,
    lastModEntryCSN: Seq[String],
    managedNodes:    Int
)

/*
 * Companion object of NodeInfoServiceCached where pure computing function are
 * put. Here, you will find:
 *
 * - the logic to build a `LDAPNodeInfo` from existing cache and partial updates,
 * -
 */
object NodeInfoServiceCached {
  val logEffect = NodeLoggerPure.Cache.logEffect

  // utility class to move node updates around
  final case class NodeUpdates(
      updated:    Buffer[LDAPNodeInfo] = Buffer(),
      nodeErrors: Buffer[String] =
        Buffer(), // that's actually nodeId, but we are in perf/mem sensitive part and avoid object instanciation

      containerErrors: Buffer[String] = Buffer() // that's actually a container DN
  )
  // utility class to move around entries (node, inventories, CSN) from LDAP
  final case class InfoMaps(
      // some map of things - mutable, yes
      nodes: MutMap[String, LDAPEntry] = MutMap(), // node_uuid -> entry

      nodeInventories: MutMap[String, LDAPEntry] = MutMap(), // node_uuid -> entry

      machineInventories: MutMap[String, LDAPEntry] = MutMap(), // machine_dn -> entry

      entriesCSN: Buffer[String] = Buffer()
  )

  /*
   * That method constructs new LDAPInfo entries from partial update on nodes and inventories (nodes and machines).
   * To keep consistency, each map is processed in turn, and we mark errors (an update on a node when node inventory is missing,
   * or an update on an inventory (node and machine) where the node is missing) appart.
   * These errors can happen when a node is in the middle of acceptation and only half of data are created in LDAP when
   * the cache is updated.
   */
  def constructNodesFromPartialUpdate(
      currentCache: LocalNodeInfoCache,
      infoMaps:     InfoMaps
  ): NodeUpdates = {

    // get machine inventory from inventory map. it will lazely use cacheEntry if not found in machineInventories
    def getNonOptionnalMachineInventory(
        containerDn:        String,
        machineInventories: MutMap[String, LDAPEntry], // only read

        managedMachineInventories: scala.collection.mutable.Buffer[String],
        cacheEntry:                () => Option[LDAPEntry]
    ): Option[LDAPEntry] = {
      for {
        machineEntry <- machineInventories.get(containerDn) match {
                          case Some(value) =>
                            // machine inventory is handled
                            managedMachineInventories += containerDn
                            Some(value)
                          case None        => // look in cache
                            cacheEntry()
                        }
      } yield {
        machineEntry
      }
    }

    val managedNodeInventories    = scala.collection.mutable.Buffer[String]()
    val managedMachineInventories = scala.collection.mutable.Buffer[String]()
    val result                    = NodeUpdates()

    // we have three loops with map/filter on entries

    /* loop1: for nodes
     * For each entry updated in ou=Nodes, we look if the inventories are
     * also updated. If so, we mark them as "done" to avoid work in following loops.
     * If not, we look in cache. If no inventory is in cache for that node, we may
     * be in the middle of an inventory addition, and mark the nodeId as "try to compensate with full lookup".
     */
    infoMaps.nodes.foreach {
      case (id, nodeEntry) =>
        infoMaps.nodeInventories.get(id) match {
          case Some(nodeInv) =>
            // Node inventory with this is handled, so we should not look for it again
            managedNodeInventories += id
            // if a containerDn is defined, we must find a node
            nodeInv(A_CONTAINER_DN) match {
              case Some(containerDn) =>
                val machineInv = getNonOptionnalMachineInventory(
                  containerDn,
                  infoMaps.machineInventories,
                  managedMachineInventories,
                  () => currentCache.nodeInfos.get(NodeId(id)).flatMap(_._1.machineEntry)
                )
                machineInv match {
                  case Some(_) =>
                    result.updated.addOne(LDAPNodeInfo(nodeEntry, nodeInv, machineInv))
                  case None    =>
                    // container is defined, but not found. This is an error
                    result.nodeErrors.addOne(id)
                }
              case None              => // no container expected
                result.updated.addOne(LDAPNodeInfo(nodeEntry, nodeInv, None))
            }
          case None          => // look in cache
            currentCache.nodeInfos.get(NodeId(id)) match {
              case None                       => // oups, mark as problem
                result.nodeErrors.addOne(id)
              case Some((ldapInfo, nodeInfo)) => // use that
                val nodeInv = ldapInfo.nodeInventoryEntry
                // if a containerDn is defined, we must find a node
                nodeInv(A_CONTAINER_DN) match {
                  case Some(containerDn) =>
                    val machineInv = getNonOptionnalMachineInventory(
                      containerDn,
                      infoMaps.machineInventories,
                      managedMachineInventories,
                      () => ldapInfo.machineEntry
                    )
                    machineInv match {
                      case Some(_) =>
                        result.updated.addOne(LDAPNodeInfo(nodeEntry, nodeInv, machineInv))
                      case None    =>
                        // container is defined, but not found. This is an error
                        result.nodeErrors.addOne(id)
                    }
                  case None              => // no container expected
                    result.updated.addOne(LDAPNodeInfo(nodeEntry, nodeInv, None))
                }
            }

        }
    }
    val nbNodeEntries = result.updated.size
    logEffect.trace(s"Constructing nodes from partial update")
    logEffect.trace(s"  -- nodeEntries: ${result.updated.mkString(",")}")

    /*
     * Loop 2: for node inventories.
     * Only process node inventories that are not yet marked as done.
     * Again, we can have error: here, we know the node info for these inventories must
     * be in cache, since the one form updates were done in loop 1. If not in cache, it's
     * an error.
     */
    infoMaps.nodeInventories.foreach {
      case (id, nodeInv) =>
        if (!managedNodeInventories.contains(id)) {
          currentCache.nodeInfos.get(NodeId(id)) match {
            case None                       => // oups
              result.nodeErrors.addOne(id)
            case Some((ldapInfo, nodeInfo)) =>
              val nodeEntry = ldapInfo.nodeEntry
              nodeInv(A_CONTAINER_DN) match {
                case Some(containerDn) =>
                  val machineInv = getNonOptionnalMachineInventory(
                    containerDn,
                    infoMaps.machineInventories,
                    managedMachineInventories,
                    () => ldapInfo.machineEntry
                  )
                  machineInv match {
                    case Some(_) =>
                      result.updated.addOne(LDAPNodeInfo(nodeEntry, nodeInv, machineInv))
                    case None    =>
                      // container is defined, but not found. This is an error
                      result.nodeErrors.addOne(id)
                  }
                case None              =>
                  result.updated.addOne(LDAPNodeInfo(nodeEntry, nodeInv, None))
              }
          }
        }
    }

    val nbNodeInvs = result.updated.size - nbNodeEntries
    logEffect.trace(s"  -- inventoryEntries: ${result.updated.iterator.drop(nbNodeEntries).mkString(",")}")
    logEffect.debug(s"  -- following nodes were not complete for cache: ${result.nodeErrors.mkString(",")}")

    /* loop3: for machine inventories
     * Very similar than node inventories, for same reasons, but one machine can be mapped to several nodes!
     */
    infoMaps.machineInventories.foreach {
      case (containerDn, machineInv) =>
        if (!managedMachineInventories.contains(containerDn)) {
          // the tricky part: there may be several nodes with the same containerDn
          // now, we must have AT LEAST one value in res, else it's the same kind of error than a node inventory without a node.
          var atLeastOne = false
          currentCache.nodeInfos.collect {
            case (nodeId, (ldapInfo, _)) if (ldapInfo.nodeInventoryEntry(A_CONTAINER_DN).equals(Some(containerDn))) =>
              atLeastOne = true
              result.updated.addOne(LDAPNodeInfo(ldapInfo.nodeEntry, ldapInfo.nodeInventoryEntry, Some(machineInv)))
          }
          if (!atLeastOne) {
            result.containerErrors.addOne(containerDn)
          }
        }
    }

    logEffect.trace(s"  -- machineInventoriesEntries: ${result.updated.iterator.drop(nbNodeEntries + nbNodeInvs).mkString(",")}")
    logEffect.debug(s"  -- following machineInventories were not complete for cache: ${result.containerErrors.mkString(",")}")

    result
  }

  /*
   * Simpler version of `constructNodesFromPartialUpdate` where we assume that we have all datas,
   * so we can only loop one time for nodes and be done.
   */
  def constructNodesFromAllEntries(infoMaps: InfoMaps, checkRoot: Boolean = true): IOResult[NodeUpdates] = {
    for {
      results     <- Ref.make(NodeUpdates())
      rootMissing <- Ref.make(checkRoot)
      // foreach cannot work on mutableMap
      _           <- ZIO.foreach(infoMaps.nodes: Iterable[(String, LDAPEntry)]) {
                       case (id, nodeEntry) =>
                         infoMaps.nodeInventories.get(id) match {
                           case None          =>
                             // We can safely skip it - when the inventory will appear, it will be caught up in the partial update
                             // For the case where we have an inventory but no node, it's the same
                             // If partial update get only part of the object (inv, or node), then the nodeErrors will fetch it
                             NodeLoggerPure.Cache.debug(
                               s"Node with id '${id}' is in ou=Nodes,cn=rudder-configuration but doesn't have an inventory: skipping it"
                             ) *>
                             results.update { x => x.nodeErrors.addOne(id); x }
                           case Some(nodeInv) =>
                             val machineInv = for {
                               containerDn  <- nodeInv(A_CONTAINER_DN)
                               machineEntry <- infoMaps.machineInventories.get(containerDn)
                             } yield {
                               machineEntry
                             }
                             results.update { x => x.updated.addOne(LDAPNodeInfo(nodeEntry, nodeInv, machineInv)); x } *>
                             ZIO.when(id == Constants.ROOT_POLICY_SERVER_ID.value)(rootMissing.set(false))
                         }
                     }
      // here, we must ensure that root ID is on the list, else chaos ensue.
      // If root is missing, invalidate the case
      _           <- ZIO.whenZIO(rootMissing.get) {
                       val msg = {
                         "'root' node is missing from the list of nodes. Rudder can not work in that state. We clear the cache now to try" +
                         "to auto-correct the problem. If it persists, try to run 'rudder agent inventory && rudder agent run' " +
                         "from the root server and check /var/log/rudder/webapp/ logs for additionnal information."
                       }
                       NodeLoggerPure.Cache.error(msg) *> msg.fail
                     }
      ldapNodes   <- results.get
    } yield {
      ldapNodes
    }
  }

  // utility class to move around deleted and existing/updated node entries
  final case class UpdatedNodeEntries(
      deleted: Seq[LDAPEntry],
      updated: Seq[LDAPEntry]
  )
  // logic to sort out different kind of node entries from generic LDAP entries
  def buildInfoMaps(updatedNodeEntries: UpdatedNodeEntries, currentLastModif: DateTime): (InfoMaps, DateTime) = {
    val infoMaps = InfoMaps(MutMap.empty, MutMap.empty, MutMap.empty, Buffer())
    val t0       = System.currentTimeMillis

    // two vars to keep track of the new last modification time and entries csn
    var lastModif = currentLastModif

    // look for the maxed timestamp
    (updatedNodeEntries.deleted ++ updatedNodeEntries.updated).foreach { e =>
      e.getAsGTime(A_MOD_TIMESTAMP) match {
        case None    => // nothing
        case Some(x) =>
          if (x.dateTime.isAfter(lastModif)) {
            lastModif = x.dateTime
            infoMaps.entriesCSN.clear()
          }
          if (x.dateTime == lastModif) {
            e("entryCSN").map(csn => infoMaps.entriesCSN.append(csn))
          }
      }
    }

    // now, create the nodeInfo
    updatedNodeEntries.updated.foreach { e =>
      if (e.isA(OC_MACHINE)) {
        infoMaps.machineInventories += (e.dn.toString -> e)
      } else if (e.isA(OC_NODE)) {
        infoMaps.nodeInventories += (e.value_!(A_NODE_UUID) -> e)
      } else if (e.isA(OC_RUDDER_NODE)) {
        infoMaps.nodes += (e.value_!(A_NODE_UUID) -> e)
      } else {
        // it's an error, don't use
      }
    }

    val t1 = System.currentTimeMillis
    NodeLoggerPure.Cache.trace(
      s"Updated entries are machineInventories: ${infoMaps.machineInventories.mkString(",")} \n" +
      s"nodeInventories: ${infoMaps.nodeInventories.mkString(",")}  \n" +
      s"nodes: ${infoMaps.nodes.mkString(",")} "
    )
    TimingDebugLogger.debug(s"Getting node info entries: ${t1 - t0}ms")
    (infoMaps, lastModif)
  }
}

trait NodeInfoServiceCached extends NodeInfoService with NamedZioLogger with CachedRepository {
  import NodeInfoService._

  def ldap:            LDAPConnectionProvider[RoLDAPConnection]
  def nodeDit:         NodeDit
  def inventoryDit:    InventoryDit
  def removedDit:      InventoryDit
  def pendingDit:      InventoryDit
  def ldapMapper:      LDAPEntityMapper
  def inventoryMapper: InventoryMapper

  override def loggerName: String = this.getClass.getName

  val semaphore = Semaphore.make(1).runNow
  /*
   * Compare if cache is up to date (based on internal state of the cache)
   */
  def isUpToDate(): IOResult[Boolean] = {
    IOResult.attempt(nodeCache).flatMap {
      case Some(cache) => checkUpToDate(cache.lastModTime, cache.lastModEntryCSN)
      case None        => false.succeed // an empty cache is never up to date
    }
  }

  /**
   * Check is LDAP directory contains updated entries compare
   * to the date we pass in arguments.
   * Entries may be any entry relevant for our cache, in particular,
   * some attention must be provided to deleted entries.
   */
  protected[this] def checkUpToDate(lastKnowModification: DateTime, lastModEntryCSN: Seq[String]): IOResult[Boolean]

  /**
   * This method must return only and all entries under:
   * - ou=Nodes,
   * - ou=[Node, Machine], ou=Accepted Inventories, etc
   *
   * attributes is the list of attributes needed in returned entries
   */
  def getNodeInfoEntries(
      con:              RoLDAPConnection,
      attributes:       Seq[String],
      status:           InventoryStatus,
      lastModification: Option[DateTime]
  ): LDAPIOResult[Seq[LDAPEntry]]

  /*
   * Retrieve from backend LDANodeInfo for the entries
   */
  def getBackendLdapNodeInfo(nodeIds: Seq[String]): IOResult[Seq[LDAPNodeInfo]]

  // containerDn look like  machineId=000f6268-e825-d13c-fa14-f9e55d05038c,ou=Machines,ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration
  def getBackendLdapContainerinfo(containersDn: Seq[String]): IOResult[Seq[LDAPNodeInfo]]

  override def getNumberOfManagedNodes = nodeCache.map(_.managedNodes).getOrElse(0).succeed

  /*
   * Our cache
   */
  protected[this] var nodeCache = Option.empty[LocalNodeInfoCache]

  // we need modifyTimestamp to search for update and entryCSN to remove already processed entries
  private[this] val searchAttributes = nodeInfoAttributes :+ A_MOD_TIMESTAMP :+ "entryCSN"

  /**
   * Remove a node from cache. It cannot fail - if node is not in cache, it is a success
   */
  def removeNodeFromCache(nodeId: NodeId): IOResult[Unit] = {
    semaphore.withPermit(
      IOResult.attempt({ nodeCache = nodeCache.map(x => x.copy(nodeInfos = x.nodeInfos.removed(nodeId))) })
    )
  }

  /**
   * Update cache, without doing anything with the data
   */
  def updateCache(): IOResult[Unit] = {
    withUpToDateCache("update cache")(_ => ZIO.unit)
  }

  /**
   * That's the method that do all the logic
   */
  private[this] def withUpToDateCache[T](
      label:  String
  )(useCache: Map[NodeId, (LDAPNodeInfo, NodeInfo)] => IOResult[T]): IOResult[T] = {
    /*
     * Get all relevant info from backend along with the
     * date of the last modification.
     * If `existingCache` is None, all data will be fetched to fully build a fresh new cache.
     * If `existingCache` is Some(cache), then only a partial update will be computed based on
     * modified entries since cache last update datetime.
     *
     * That method is not threadsafe and must be enclosed in a semaphore or other guarding mean.
     */
    def getDataFromBackend(existingCache: Option[LocalNodeInfoCache]): IOResult[LocalNodeInfoCache] = {

      // Find entries modified since `lastKnowModification` (or all entries if None)
      def getUpdatedDataIO(lastKnowModification: Option[DateTime]): IOResult[UpdatedNodeEntries] = {
        val filter = lastKnowModification match {
          case Some(date) => AND(IS(OC_NODE), GTEQ(A_MOD_TIMESTAMP, GeneralizedTime(date).toString))
          case None       => AND(IS(OC_NODE))
        }

        for {
          con     <- ldap
          t0      <- currentTimeMillis
          deleted <- con.search(removedDit.NODES.dn, One, filter, A_MOD_TIMESTAMP, "entryCSN")
          t1      <- currentTimeMillis
          updated <- getNodeInfoEntries(con, searchAttributes, AcceptedInventory, lastKnowModification)
          t2      <- currentTimeMillis
        } yield {
          TimingDebugLogger.debug(
            s"Getting updated node info data from LDAP: ${t2 - t0}ms total (${t1 - t0}ms for removed nodes, ${t2 - t1} for accepted inventories)"
          )
          UpdatedNodeEntries(deleted, updated)
        }
      }

      // create a fresh new cache with `updatedEntries`.  If `existingCache` is None, we assume these entries are all
      // available entries, else we assume these are only the ones updated since cache last update.
      def getUpdatedCache(
          existingCache:  Option[LocalNodeInfoCache],
          updatedEntries: UpdatedNodeEntries
      ): IOResult[(LocalNodeInfoCache, Int)] = {
        // if we don't have an existing cache, then we are (re)initializing cache, so we get all entries since epoch
        val (infoMaps, lastModif) = buildInfoMaps(updatedEntries, existingCache.map(_.lastModTime).getOrElse(new DateTime(0)))

        for {
          updates             <- existingCache match {
                                   case None        => // full build
                                     NodeInfoServiceCached.constructNodesFromAllEntries(infoMaps)
                                   case Some(cache) => // only update what need to be updated
                                     NodeInfoServiceCached.constructNodesFromPartialUpdate(cache, infoMaps).succeed
                                 }
          // try to compasente for errors: for node id, get full info from backend, for containers, dedicated search (todo)
          _                   <- NodeLoggerPure.Cache.debug(s"Found ${updates.updated.size} new entries to update cache")
          compensate          <- if (updates.nodeErrors.nonEmpty) {
                                   getBackendLdapNodeInfo(updates.nodeErrors.toSeq).catchAll(
                                     err => { // we don't want to fail because we tried to compensate
                                       NodeLoggerPure.Cache.warn(
                                         s"Error when trying to find in LDAP node entries: ${updates.nodeErrors.mkString(", ")}: ${err.fullMsg}"
                                       ) *> Seq().succeed
                                     }
                                   )
                                 } else {
                                   Seq().succeed
                                 }
          _                   <- ZIO.when(updates.nodeErrors.size > 0) {
                                   NodeLoggerPure.Cache.debug(s"${updates.nodeErrors.size} were in errors, compensated ${compensate.size}")
                                 }
          compensateContainer <- if (updates.containerErrors.nonEmpty) {
                                   getBackendLdapContainerinfo(updates.containerErrors.toSeq).catchAll(
                                     err => { // we don't want to fail because we tried to compensate
                                       NodeLoggerPure.Cache.warn(
                                         s"Error when trying to find in LDAP containers entries: ${updates.containerErrors
                                             .mkString(", ")}: ${err.fullMsg}"
                                       ) *> Seq().succeed
                                     }
                                   )
                                 } else {
                                   Seq().succeed
                                 }
          _                   <- ZIO.when(updates.containerErrors.size > 0) {
                                   NodeLoggerPure.Cache.debug(
                                     s"${updates.containerErrors.size} were in errors, compensated ${compensateContainer.size}"
                                   )
                                 }
          // now construct the nodeInfo
          updated             <- ZIO.foreach((updates.updated ++ compensate ++ compensateContainer): Iterable[LDAPNodeInfo]) { ldapNode =>
                                   val id = ldapNode.nodeEntry.value_!(A_NODE_UUID) // id is mandatory
                                   ldapMapper
                                     .convertEntriesToNodeInfos(
                                       ldapNode.nodeEntry,
                                       ldapNode.nodeInventoryEntry,
                                       ldapNode.machineEntry
                                     )
                                     .foldZIO(
                                       err =>
                                         NodeLoggerPure.Cache.error(
                                           s"An error occured while updating node cache: can not unserialize node with id '${id}', it will be ignored: ${err.fullMsg}"
                                         ) *> None.succeed,
                                       nodeInfo => Some((nodeInfo.id, (ldapNode, nodeInfo))).succeed
                                     )
                                 }
          _                   <-
            NodeLoggerPure.Cache.trace(s"Updated entries are updatedNodeInfos: ${updated.flatten.map(_._1.value).mkString(", ")}")
        } yield {
          val allEntries = existingCache.map(_.nodeInfos).getOrElse(Map()) ++ updated.flatten.toMap
          val cache      = LocalNodeInfoCache(
            allEntries,
            lastModif,
            infoMaps.entriesCSN.toSeq,
            allEntries.filter { case (_, (_, n)) => !n.isPolicyServer }.size
          )
          (cache, infoMaps.nodes.size)
        }
      }

      for {
        t0         <- currentTimeMillis
        data       <- getUpdatedDataIO(existingCache.map(_.lastModTime))
        res        <- getUpdatedCache(existingCache, data)
        (cache, nb) = res
        t1         <- currentTimeMillis
        _          <- TimingDebugLoggerPure.debug(s"Converting ${nb} node info entries to node info: ${t1 - t0}ms")
      } yield {
        cache
      }
    }

    // Here finaly comes the whole logic that
    for {
      // only checking the cache validity should be in a semaphore - logic to read it does not need to
      // we cannot get (cache, t0), because it fails with Cannot prove that NoSuchElementException <:< com.normation.errors.RudderError
      result            <-
        semaphore.withPermit(
          for {
            t0           <- currentTimeMillis
            updatedCache <- nodeCache match {
                              case None =>
                                for {
                                  updated <- getDataFromBackend(None).foldZIO(
                                               err =>
                                                 IOResult.attempt({ nodeCache = None; () }) *> Chained(
                                                   "Could not get node information from database",
                                                   err
                                                 ).fail,
                                               newCache => {
                                                 logPure.debug(
                                                   s"NodeInfo cache is now initialized, last modification time: '${newCache.lastModTime}', last cache update:" +
                                                   s" with ${newCache.nodeInfos.size} entries"
                                                 ) *>
                                                 logPure
                                                   .trace(s"NodeInfo cache initialized entries: [${newCache.nodeInfos.keySet
                                                       .map(_.value)
                                                       .mkString(", ")}]") *>
                                                 IOResult.attempt({ nodeCache = Some(newCache); () }) *>
                                                 newCache.succeed
                                               }
                                             )
                                } yield {
                                  updated
                                }

                              case Some(currentCache) =>
                                isUpToDate().flatMap(isClean => {
                                  if (!isClean) {
                                    for {
                                      updated <- getDataFromBackend(Some(currentCache)).foldZIO(
                                                   err =>
                                                     IOResult.attempt({
                                                       nodeCache = None; ()
                                                     }) *> Chained(
                                                       "Could not get updated node information from database",
                                                       err
                                                     ).fail,
                                                   newCache => {
                                                     logPure.debug(
                                                       s"NodeInfo cache is not up to date, last modification time: '${newCache.lastModTime}', last cache update:" +
                                                       s" '${currentCache.lastModTime}' => updating cache with ${newCache.nodeInfos.size} entries"
                                                     ) *>
                                                     logPure.trace(
                                                       s"NodeInfo cache updated entries: [${newCache.nodeInfos.keySet.map {
                                                           _.value
                                                         }.mkString(", ")}]"
                                                     ) *>
                                                     IOResult.attempt({
                                                       nodeCache = Some(newCache); ()
                                                     }) *>
                                                     newCache.succeed
                                                   }
                                                 )
                                    } yield {
                                      updated
                                    }
                                  } else {
                                    logPure.debug(s"NodeInfo cache is up to date, ${nodeCache
                                        .map(c => s"last modification time: '${c.lastModTime}' for: '${c.lastModEntryCSN.mkString("','")}'")
                                        .getOrElse("")}") *>
                                    currentCache.succeed
                                  }
                                })
                            }

            t1 <- currentTimeMillis
            _  <- IOResult.attempt(TimingDebugLogger.debug(s"Get cache for node info (${label}): ${t1 - t0}ms"))
          } yield {
            (t0, updatedCache)
          }
        )
      (t0, updatedCache) = result
      t1                <- currentTimeMillis
      res               <- useCache(updatedCache.nodeInfos) // this does not need to be in a semaphore
      t2                <- currentTimeMillis
      _                 <- IOResult.attempt(
                             TimingDebugLogger.debug(s"Get node info (${label}): ${t2 - t0}ms - exploring the cache took ${t2 - t1}ms ")
                           )
    } yield {
      res
    }
  }

  /**
   * An utility method that gets data from backend for things that are
   * node really nodes (pending or deleted).
   */
  private[this] def getNotAcceptedNodeDataFromBackend(status: InventoryStatus): IOResult[Map[NodeId, NodeInfo]] = {
    import scala.collection.mutable.{Map => MutMap}
    for {
      con        <- ldap
      allEntries <- getNodeInfoEntries(con, searchAttributes, status, None)
      res        <- {
        // some map of things - mutable, yes
        val nodeInventories    = MutMap[String, LDAPEntry]() // node_uuid -> entry
        val machineInventories = MutMap[String, LDAPEntry]() // machine_dn -> entry

        // now, create the nodeInfo
        allEntries.foreach { e =>
          if (e.isA(OC_MACHINE)) {
            machineInventories += (e.dn.toString -> e)
          } else if (e.isA(OC_NODE)) {
            nodeInventories += (e.value_!(A_NODE_UUID) -> e)
          } else {
            // it's an error, don't use
          }
        }

        ZIO.foreach(nodeInventories.toMap) {
          case (id, nodeEntry) =>
            val machineInfo = for {
              containerDn  <- nodeEntry(A_CONTAINER_DN)
              machineEntry <- machineInventories.get(containerDn)
            } yield {
              machineEntry
            }
            for {
              nodeInfo <- ldapMapper.convertEntriesToSpecialNodeInfos(nodeEntry, machineInfo)
            } yield {
              (nodeInfo.id, nodeInfo)
            }
        }
      }
    } yield {
      res
    }
  }

  private[this] def getNotAcceptedNodeInfo(nodeId: NodeId, status: InventoryStatus): IOResult[Option[NodeInfo]] = {
    val dit = status match {
      case AcceptedInventory => inventoryDit
      case PendingInventory  => pendingDit
      case RemovedInventory  => removedDit
    }

    for {
      con          <- ldap
      optNodeEntry <- con.get(dit.NODES.NODE.dn(nodeId), searchAttributes: _*)
      nodeInfo     <- (optNodeEntry match {
                        case None            => None.succeed
                        case Some(nodeEntry) =>
                          nodeEntry.getAsDn(A_CONTAINER_DN) match {
                            case None     => None.succeed
                            case Some(dn) =>
                              for {
                                machineEntry <- con.get(dn, searchAttributes: _*)
                                nodeInfo     <- ldapMapper.convertEntriesToSpecialNodeInfos(nodeEntry, machineEntry)
                              } yield {
                                Some(nodeInfo)
                              }
                          }
                      })
    } yield {
      nodeInfo
    }
  }

  final override def getPendingNodeInfos():              IOResult[Map[NodeId, NodeInfo]] = getNotAcceptedNodeDataFromBackend(PendingInventory)
  final override def getDeletedNodeInfos():              IOResult[Map[NodeId, NodeInfo]] = getNotAcceptedNodeDataFromBackend(RemovedInventory)
  final override def getPendingNodeInfo(nodeId: NodeId): IOResult[Option[NodeInfo]]      =
    getNotAcceptedNodeInfo(nodeId, PendingInventory)
  final override def getDeletedNodeInfo(nodeId: NodeId): IOResult[Option[NodeInfo]]      =
    getNotAcceptedNodeInfo(nodeId, RemovedInventory)

  /**
   * Clear cache.
   */
  override def clearCache(): Unit = {
    semaphore
      .withPermit(
        (this.nodeCache = None).succeed
      )
      .runNow
  }

  // return the cache last update time, or epoch if cache is not init
  def getCacheLastUpdate: UIO[DateTime] = {
    semaphore.withPermit(ZIO.succeed(this.nodeCache.map(_.lastModTime).getOrElse(new DateTime(0))))
  }

  def getAll():              IOResult[Map[NodeId, NodeInfo]] = withUpToDateCache("all nodes info") { cache =>
    cache.view.mapValues(_._2).toMap.succeed
  }
  def getAllNodesIds():      IOResult[Set[NodeId]]           = withUpToDateCache("all nodes id")(cache => cache.keySet.succeed)
  def getAllSystemNodeIds(): IOResult[Seq[NodeId]]           = withUpToDateCache("all system nodes") { cache =>
    cache.collect { case (k, (_, x)) if (x.isPolicyServer) => k }.toSeq.succeed
  }

  def getAllNodes(): IOResult[Map[NodeId, Node]] = withUpToDateCache("all nodes") { cache =>
    cache.view.mapValues(_._2.node).toMap.succeed
  }

  def getAllNodeInfos(): IOResult[Seq[NodeInfo]] = withUpToDateCache("all nodeinfos") { cache =>
    cache.view.values.map(_._2).toSeq.succeed
  }

  def getNodeInfo(nodeId: NodeId): IOResult[Option[NodeInfo]] = withUpToDateCache(s"${nodeId.value} node info") { cache =>
    cache.get(nodeId).map(_._2).succeed
  }

  def getNodeInfos(nodeIds: Set[NodeId]): IOResult[Set[NodeInfo]] = withUpToDateCache(s"${nodeIds.size} nodes infos") { cache =>
    cache.filter(x => nodeIds.contains(x._1)).values.map(_._2).toSet.succeed
  }

  def getNodeInfosSeq(nodeIds: Seq[NodeId]): IOResult[Seq[NodeInfo]] = withUpToDateCache(s"${nodeIds.size} nodes infos") {
    cache => nodeIds.map(id => cache.get(id).map(_._2)).flatten.succeed
  }
}

/**
 * A testing implementation, that just retrieve node info each time. Not very efficient.
 */
class NaiveNodeInfoServiceCachedImpl(
    override val ldap:            LDAPConnectionProvider[RoLDAPConnection],
    override val nodeDit:         NodeDit,
    override val inventoryDit:    InventoryDit,
    override val removedDit:      InventoryDit,
    override val pendingDit:      InventoryDit,
    override val ldapMapper:      LDAPEntityMapper,
    override val inventoryMapper: InventoryMapper
) extends NodeInfoServiceCached {

  override def loggerName: String = this.getClass.getName

  override def checkUpToDate(lastKnowModification: DateTime, lastModEntryCSN: Seq[String]): IOResult[Boolean] = {
    false.succeed // yes naive
  }

  def getNewNodeInfoEntries(
      con:                  RoLDAPConnection,
      lastKnowModification: DateTime,
      searchAttributes:     Seq[String]
  ): LDAPIOResult[Seq[LDAPEntry]] = ???

  /**
   * This method must return only and all entries under:
   * - ou=Nodes,
   * - ou=[Node, Machine], ou=Accepted Inventories, etc
   */
  override def getNodeInfoEntries(
      con:              RoLDAPConnection,
      searchAttributes: Seq[String],
      status:           InventoryStatus,
      lastModification: Option[DateTime]
  ): LDAPIOResult[Seq[LDAPEntry]] = {
    for {
      nodeInvs    <- con.search(inventoryDit.NODES.dn, One, BuildFilter.ALL, searchAttributes: _*)
      machineInvs <- con.search(inventoryDit.MACHINES.dn, One, BuildFilter.ALL, searchAttributes: _*)
      nodes       <- if (status == AcceptedInventory) {
                       con.search(nodeDit.NODES.dn, One, BuildFilter.ALL, searchAttributes: _*)
                     } else {
                       Seq().succeed
                     }
    } yield {
      nodeInvs ++ machineInvs ++ nodes
    }
  }
  // necessary for tests
  override def getBackendLdapNodeInfo(nodeIds: Seq[String]): IOResult[Seq[LDAPNodeInfo]] = {
    Seq().succeed
  }

  override def getBackendLdapContainerinfo(containersDn: Seq[String]): IOResult[Seq[LDAPNodeInfo]] = ???
}

/**
 * A cache on top of node info service.
 *
 */

class NodeInfoServiceCachedImpl(
    override val ldap:            LDAPConnectionProvider[RoLDAPConnection],
    override val nodeDit:         NodeDit,
    override val inventoryDit:    InventoryDit,
    override val removedDit:      InventoryDit,
    override val pendingDit:      InventoryDit,
    override val ldapMapper:      LDAPEntityMapper,
    override val inventoryMapper: InventoryMapper,
    minimumCacheValidity:         FiniteDuration
) extends NodeInfoServiceCached {
  import NodeInfoService._
  val minimumCacheValidityMillis = minimumCacheValidity.toMillis

  override def loggerName: String = this.getClass.getName

  /*
   * Check if node related infos are up to date.
   *
   * Here, we need to only check for attributeModifyTimestamp
   * under ou=AcceptedInventories and under ou=Nodes (onelevel)
   * and only for machines, inventory nodes, and node,
   * and inventory nodes under ou=RemovedInventories
   *  Reason:
   * - only these three entries are used for node info (and none of their
   *   sub-entries)
   * - if a node is deleted, it either go to RemovedInventories (and so the
   *   machine, but we don't care as soon as we know a node went there) and we
   *   will see its based on modify timestamps, or if "node full erase" is enabled,
   *   a special call to "remove node from cache" must be done
   * - for ou=Node, all modifications happen in the entry, so the modifyTimestamp
   *   is changed accordingly.
   *
   * Moreover, it is less costly to only do one search and post-filter result
   * than to do 2 or more.
   *
   * We also need to filter out entry in the previous last modify set to not
   * have them always matching. The reason is that modifyTimestamp is on
   * second. But we can have several modify in a second. If we get our
   * lastModificationTimestamp in just before the second modification, that
   * modification will be ignore forever (or at least until an other modification
   * happens - see ticket https://www.rudder-project.org/redmine/issues/12988)
   * The filter is based on entryCSN, which is sure to be unique (by def).
   *
   * Finally, we limit the result to one, because here, we just need to know if
   * some update exists, not WHAT they are, nor the MOST RECENT one. Just that
   * at least one exists.
   *
   * A cleaner implementation could use a two persistent search which would notify
   * when a cache becomes invalid and reset it, but the rational for that implementation is:
   * - it's extremely simple to understand the logic (if(cache is up-to-date) use it else update cache)
   * - most of the time (99.99% of it), the search will return 0 result and will be cache on OpenLDAP,
   *   whatever the number of entries. So we talking of a request taking a couple of ms on the server
   *   (with a vagrant VM on the same host (so, almost no network), it takes from client to server and
   *   back ~10ms on a dev machine.
   */
  override def checkUpToDate(lastKnowModification: DateTime, lastModEntryCSN: Seq[String]): IOResult[Boolean] = {
    ZIO.succeed(System.currentTimeMillis).flatMap { n0 =>
      // if last check is less than 100 ms ago, consider cache ok
      if (n0 - lastKnowModification.getMillis < minimumCacheValidityMillis) {
        true.succeed
      } else {
        val searchRequest = new SearchRequest(
          nodeDit.BASE_DN.toString,
          Sub.toUnboundid,
          DereferencePolicy.NEVER,
          1,
          0,
          false,
          AND(
            OR(
              // ou=Removed Inventories,ou=Inventories,cn=rudder-configuration
              AND(
                IS(OC_NODE),
                Filter.create(s"entryDN:dnOneLevelMatch:=${removedDit.NODES.dn.toString}")
              ), // ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration

              AND(IS(OC_NODE), Filter.create(s"entryDN:dnOneLevelMatch:=${inventoryDit.NODES.dn.toString}")),
              AND(
                IS(OC_MACHINE),
                Filter.create(s"entryDN:dnOneLevelMatch:=${inventoryDit.MACHINES.dn.toString}")
              ), // ou=Nodes,cn=rudder-configuration - the objectClass is used only here

              AND(IS(OC_RUDDER_NODE), Filter.create(s"entryDN:dnOneLevelMatch:=${nodeDit.NODES.dn.toString}"))
            ),
            GTEQ(A_MOD_TIMESTAMP, GeneralizedTime(lastKnowModification).toString),
            NOT(OR(lastModEntryCSN.map(csn => EQ("entryCSN", csn)): _*))
          ),
          "1.1"
        )

        for {
          con     <- ldap
          entries <- // here, I have to rely on low-level LDAP connection, because I need to proceed size-limit exceeded as OK
            (ZIO.attempt(con.backed.search(searchRequest).getSearchEntries) catchAll {
              case e: LDAPSearchException if (e.getResultCode == ResultCode.SIZE_LIMIT_EXCEEDED) =>
                e.getSearchEntries().succeed
              case e: Throwable                                                                  =>
                SystemError("Error when searching node information", e).fail
            }).foldZIO(
              err =>
                logPure.debug(
                  s"Error when checking for cache expiration: invalidating it. Error was: ${err.fullMsg}"
                ) *> false.succeed,
              seq => {
                // we only have interesting entries in the result, so it's up to date if we have exactly 0 entries
                val res = seq.isEmpty
                logPure.trace(s"Cache check for node info gave '${res}' (${seq.size} entry returned)") *> res.succeed
              }
            )
          n1      <- ZIO.succeed(System.currentTimeMillis)
          _       <- IOResult.attempt(TimingDebugLogger.debug(s"Cache for nodes info expire ?: ${n1 - n0}ms"))
        } yield {
          entries
        }
      }
    }
  }

  /**
   * This method must return only and all entries under:
   * - ou=Nodes,
   * - ou=[Node, Machine], ou=Accepted Inventories, etc
   */
  override def getNodeInfoEntries(
      con:              RoLDAPConnection,
      searchAttributes: Seq[String],
      status:           InventoryStatus,
      lastModification: Option[DateTime]
  ): LDAPIOResult[Seq[LDAPEntry]] = {
    val dit = status match {
      case AcceptedInventory => inventoryDit
      case PendingInventory  => pendingDit
      case RemovedInventory  => removedDit
    }

    val filterNodes = OR(
      Seq(
        AND(IS(OC_NODE), Filter.create(s"entryDN:dnOneLevelMatch:=${dit.NODES.dn.toString}")),
        AND(IS(OC_MACHINE), Filter.create(s"entryDN:dnOneLevelMatch:=${dit.MACHINES.dn.toString}"))
      ) ++ (if (status == AcceptedInventory) {
              Seq(AND(IS(OC_RUDDER_NODE), Filter.create(s"entryDN:dnOneLevelMatch:=${nodeDit.NODES.dn.toString}")))
            } else {
              Seq()
            }): _*
    )

    val filter = lastModification match {
      case None    => filterNodes
      case Some(d) => AND(filterNodes, GTEQ(A_MOD_TIMESTAMP, GeneralizedTime(d).toString))
    }

    con.search(nodeDit.BASE_DN, Sub, filter, searchAttributes: _*)
  }

  // Utility method to construct infomaps for getBackEnd methods
  private[this] def constructInfoMaps(nodeEntries: Seq[LDAPEntry], nodeInvs: Seq[LDAPEntry], machineInvs: Seq[LDAPEntry]) = {
    val res = NodeInfoServiceCached.InfoMaps()
    nodeEntries.foreach(e => res.nodes.addOne((e.value_!(A_NODE_UUID), e)))
    nodeInvs.foreach(e => res.nodeInventories.addOne((e.value_!(A_NODE_UUID), e)))
    machineInvs.foreach(e => res.machineInventories.addOne((e.dn.toString, e)))
    res
  }

  override def getBackendLdapNodeInfo(nodeIds: Seq[String]): IOResult[Seq[LDAPNodeInfo]] = {
    for {
      con         <- ldap
      nodeEntries <-
        con.search(nodeDit.NODES.dn, One, OR(nodeIds.map(id => EQ(A_NODE_UUID, id)): _*), NodeInfoService.nodeInfoAttributes: _*)
      nodeInvs    <- con.search(
                       inventoryDit.NODES.dn,
                       One,
                       OR(nodeIds.map(id => EQ(A_NODE_UUID, id)): _*),
                       NodeInfoService.nodeInfoAttributes: _*
                     )
      containers   = nodeInvs.flatMap(e => e(A_CONTAINER_DN).map(dn => new DN(dn).getRDN.getAttributeValues()(0)))
      machineInvs <- con.search(
                       inventoryDit.MACHINES.dn,
                       One,
                       OR(containers.map(id => EQ(A_MACHINE_UUID, id)): _*),
                       NodeInfoService.nodeInfoAttributes: _*
                     )
      infoMaps    <- IOResult.attempt(constructInfoMaps(nodeEntries, nodeInvs, machineInvs))
      res         <- NodeInfoServiceCached.constructNodesFromAllEntries(infoMaps, checkRoot = false)
    } yield {
      // here, we ignore error cases
      res.updated.toSeq
    }
  }

  // containerDn look like  machineId=000f6268-e825-d13c-fa14-f9e55d05038c,ou=Machines,ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration
  override def getBackendLdapContainerinfo(containersDn: Seq[String]): IOResult[Seq[LDAPNodeInfo]] = {
    for {
      con         <- ldap
      containers   = containersDn.map(dn =>
                       new DN(dn).getRDN.getAttributeValues()(0)
                     ) // I'm using the same logic as up so that I can get all machines in one query, rather than on get per dn
      machineInvs <- con.search(
                       inventoryDit.MACHINES.dn,
                       One,
                       OR(containers.map(id => EQ(A_MACHINE_UUID, id)): _*),
                       NodeInfoService.nodeInfoAttributes: _*
                     )
      nodeInvs    <- con.search(
                       inventoryDit.NODES.dn,
                       One,
                       OR(containersDn.map(container => EQ(A_CONTAINER_DN, container)): _*),
                       NodeInfoService.nodeInfoAttributes: _*
                     )
      nodeIds      = nodeInvs.flatMap(_(A_NODE_UUID))
      nodeEntries <-
        con.search(nodeDit.NODES.dn, One, OR(nodeIds.map(id => EQ(A_NODE_UUID, id)): _*), NodeInfoService.nodeInfoAttributes: _*)
      infoMaps    <- IOResult.attempt(constructInfoMaps(nodeEntries, nodeInvs, machineInvs))
      res         <- NodeInfoServiceCached.constructNodesFromAllEntries(infoMaps, checkRoot = false)
    } yield {
      // here, we ignore error cases
      res.updated.toSeq
    }
  }
}
