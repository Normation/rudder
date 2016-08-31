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

import com.normation.inventory.domain._
import org.joda.time.DateTime
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.NodeDit
import net.liftweb.common._
import net.liftweb.util.Helpers._
import com.normation.rudder.domain.nodes.{NodeInfo, Node}
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.rudder.domain.Constants._
import com.unboundid.ldap.sdk._
import com.normation.ldap.sdk._
import BuildFilter._
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.utils.Control._
import com.normation.inventory.ldap.core.InventoryMapper
import com.normation.inventory.ldap.core.InventoryDitService
import com.normation.rudder.domain.logger.TimingDebugLogger
import com.normation.rudder.repository.CachedRepository
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.ldap.core.InventoryMapper
import com.normation.inventory.ldap.core.LDAPConstants
import com.normation.rudder.domain.nodes.MachineInfo

/*
 * General logic for the cache implementation of NodeInfo.
 * The general idea is to limit at maximum:
 * - the number of request to LDAP server (because I/O, slow)
 * - the quantity of data on the wires.
 *
 * The general tactic is to have a local cache (a map of nodeid -> nodeinfo),
 * and before any access, we are checking of an update is necessary with
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
 * - today, when the cache is dirty, we discard it completely and rebuilt it from
 *   scratch. It's clearly simpler than having to diff/merge it with only dirty
 *   entries, but it means that the little change on one node lead to 10 000 nodeinfo
 *   being rebuilt (so, much data on the wires, jvm gb pression, etc).
 */

/**
 * A case class used to represent the minimal
 * information needed to get a NodeInfo
 */
final case class LDAPNodeInfo(
    nodeEntry         : LDAPEntry
  , nodeInventoryEntry: LDAPEntry
  , machineEntry      : Option[LDAPEntry]
)

trait NodeInfoService {

  /**
   * Retrieve minimal information needed for the node info
   */
  def getLDAPNodeInfo(nodeIds: Set[NodeId]) : Box[Set[LDAPNodeInfo]]

  /**
   * Return a NodeInfo from a NodeId. First check the ou=Node, then fetch the other data
   * @param nodeId
   * @return
   */
  def getNodeInfo(nodeId: NodeId) : Box[Option[NodeInfo]]

  /**
   * Get the node (not inventory).
   * Most of the info are also in node info,
   * but for some specific case (nodeProperties for ex),
   * we need them.
   */
  def getNode(nodeId: NodeId): Box[Node]

  /**
   * Get all node infos.
   * That method try to return the maximum
   * of information, and will not totally fail if some information are
   * missing (but the corresponding nodeInfos won't be present)
   * So it is possible that getAllIds.size > getAll.size
   */
  def getAll() : Box[Map[NodeId, NodeInfo]]

  /**
   * Get all nodes.
   * That method try to return the maximum
   * of information, and will not totally fail if some information are
   * missing (but the corresponding nodeInfos won't be present)
   * So it is possible that getAllIds.size > getAll.size
   */
  def getAllNodes() : Box[Map[NodeId, Node]]

  /**
   * Get all systen node ids, for example
   * policy server node ids.
   * @return
   */
  def getAllSystemNodeIds() : Box[Seq[NodeId]]

  /**
   * Getting something like a nodeinfo for pending / deleted nodes
   */
  def getPendingNodeInfos(): Box[Map[NodeId, NodeInfo]]
  def getPendingNodeInfo(nodeId: NodeId): Box[Option[NodeInfo]]

  def getDeletedNodeInfos(): Box[Map[NodeId, NodeInfo]]
  def getDeletedNodeInfo(nodeId: NodeId): Box[Option[NodeInfo]]

}

object NodeInfoService {

  /*
   * We need to list actual attributes, because we don't want software ids.
   * Really bad idea, these software ids, in node...
   */
  val nodeInfoAttributes: Seq[String] = (Set(
    // for all: objectClass and ldap object creation datatime
      A_OC, A_OBJECT_CREATION_DATE

    // node
    //  id, name, description, isBroken, isSystem
    //, isPolicyServer <- this one is special and decided based on objectClasss rudderPolicyServer
    //, creationDate, nodeReportingConfiguration, properties
    , A_NODE_UUID, A_NAME, A_DESCRIPTION, A_IS_BROKEN, A_IS_SYSTEM
    , A_SERIALIZED_AGENT_RUN_INTERVAL, A_SERIALIZED_HEARTBEAT_RUN_CONFIGURATION, A_NODE_PROPERTY, A_POLICY_MODE

    // machine inventory
    // MachineUuid
    //, machineType <- special: from objectClass
    //, systemSerial, manufacturer
    , A_MACHINE_UUID, A_SERIAL_NUMBER, A_MANUFACTURER

    //node inventory, safe machine and node (above)
    //  hostname, ips, inventoryDate, publicKey
    //, osDetails
    //, agentsName, policyServerId, localAdministratorAccountName
    //, serverRoles, archDescription, ram
    , A_NODE_UUID, A_HOSTNAME, A_LIST_OF_IP, A_INVENTORY_DATE, A_PKEYS
    , A_OS_NAME, A_OS_FULL_NAME, A_OS_VERSION, A_OS_KERNEL_VERSION, A_OS_SERVICE_PACK, A_WIN_USER_DOMAIN, A_WIN_COMPANY, A_WIN_KEY, A_WIN_ID
    , A_AGENTS_NAME, A_POLICY_SERVER_UUID, A_ROOT_USER
    , A_SERVER_ROLE, A_ARCH
    , A_CONTAINER_DN, A_OS_RAM, A_KEY_STATUS
  )).toSeq

  val A_MOD_TIMESTAMP = "modifyTimestamp"

}

/*
 * For test, we need a way to split the cache part from its retrieval.
 */

trait NodeInfoServiceCached extends NodeInfoService  with Loggable with CachedRepository {
  import NodeInfoService._

  def ldap           : LDAPConnectionProvider[RoLDAPConnection]
  def nodeDit        : NodeDit
  def inventoryDit   : InventoryDit
  def removedDit     : InventoryDit
  def pendingDit     : InventoryDit
  def ldapMapper     : LDAPEntityMapper
  def inventoryMapper: InventoryMapper

  /**
   * Check is LDAP directory contains updated entries compare
   * to the date we pass in arguments.
   * Entries may be any entry relevant for our cache, in particular,
   * some attention must be provided to deleted entries.
   */
  def isUpToDate(lastKnowModification: DateTime): Boolean

  /**
   * This method must return only and all entries under:
   * - ou=Nodes,
   * - ou=[Node, Machine], ou=Accepted Inventories, etc
   *
   * attributes is the list of attributes needed in returned entries
   */
  def getNodeInfoEntries(con: RoLDAPConnection, attributes: Seq[String], status: InventoryStatus): Seq[LDAPEntry]

  /*
   * Our cache
   */
  private[this] var nodeCache = Option.empty[Map[NodeId, (LDAPNodeInfo, NodeInfo)]]
  private[this] var lastModificationTime = new DateTime(0)

  private[this] val searchAttributes = nodeInfoAttributes :+ A_MOD_TIMESTAMP

  /**
   * That's the method that do all the logic
   */
  private[this] def withUpToDateCache[T](label: String)(useCache: Map[NodeId, (LDAPNodeInfo, NodeInfo)] => Box[T]): Box[T] = this.synchronized {
    /*
     * Get all relevant info from backend along with the
     * date of the last modification
     */
    def getDataFromBackend(lastKnowModification: DateTime): Box[(Map[NodeId, (LDAPNodeInfo, NodeInfo)], DateTime)] = {
      import scala.collection.mutable.{Map => MutMap}

      //some map of things - mutable, yes
      val nodes = MutMap[String, LDAPEntry]() //node_uuid -> entry
      val nodeInventories = MutMap[String, LDAPEntry]() // node_uuid -> entry
      val machineInventories = MutMap[String, LDAPEntry]() // machine_dn -> entry

      val t0 = System.currentTimeMillis

      var lastModif = lastKnowModification

      ldap.map { con =>

        val deletedNodes = con.search(
            removedDit.NODES.dn
          , One
          , AND(IS(OC_NODE), GTEQ(A_MOD_TIMESTAMP, GeneralizedTime(lastKnowModification.plusMillis(1)).toString))
          , A_MOD_TIMESTAMP
        )

        val allEntries = getNodeInfoEntries(con, searchAttributes, AcceptedInventory)

        //look for the maxed timestamp
        (deletedNodes ++ allEntries).foreach { e =>
          e.getAsGTime(A_MOD_TIMESTAMP) match {
            case None    => //nothing
            case Some(x) =>
              if(x.dateTime.isAfter(lastModif)) {
                lastModif = x.dateTime
              }
          }
        }

        // now, create the nodeInfo
        allEntries.foreach { e =>
          if(e.isA(OC_MACHINE)) {
            machineInventories += (e.dn.toString -> e)
          } else if(e.isA(OC_NODE)) {
            nodeInventories += (e.value_!(A_NODE_UUID) -> e)
          } else if(e.isA(OC_RUDDER_NODE)) {
            nodes += (e.value_!(A_NODE_UUID) -> e)
          } else {
            // it's an error, don't use
          }
        }

        val t1 = System.currentTimeMillis
        TimingDebugLogger.debug(s"Getting node info entries: ${t1-t0}ms")

        val res = nodes.flatMap { case (id, nodeEntry) =>
          for {
            nodeInv   <- nodeInventories.get(id)
            machineInv = for {
                           containerDn  <- nodeInv(A_CONTAINER_DN)
                           machineEntry <- machineInventories.get(containerDn)
                         } yield {
                           machineEntry
                         }
            ldapNode  = LDAPNodeInfo(nodeEntry, nodeInv, machineInv)
            nodeInfo <- ldapMapper.convertEntriesToNodeInfos(ldapNode.nodeEntry, ldapNode.nodeInventoryEntry, ldapNode.machineEntry)
          } yield {
            (nodeInfo.id, (ldapNode,nodeInfo))
          }
        }.toMap
        (res, lastModif)
      }
    }

    //actual logic that check what to do (invalidate cache or not)

    val t0 = System.currentTimeMillis

    val boxInfo = (if(nodeCache.isEmpty || !isUpToDate(lastModificationTime)) {

      getDataFromBackend(lastModificationTime) match {
        case Full((info, lastModif)) =>
          logger.debug(s"NodeInfo cache is not up to date, last modification time: '${lastModif}', last cache update: '${lastModificationTime}' => reseting cache with ${info.size} entries")
          logger.trace(s"NodeInfo cache updated entries: [${info.keySet.map{ _.value }.mkString(", ")}]")
          nodeCache = Some(info)
          lastModificationTime = lastModif
          Full(info)
        case eb: EmptyBox =>
          nodeCache = None
          lastModificationTime = new DateTime(0)
          eb ?~! "Could not get node information from database"
      }
    } else {
      logger.debug(s"NodeInfo cache is up to date, last modification time: '${lastModificationTime}'")
      Full(nodeCache.get) //get is ok because in a synchronized block with a test on isEmpty
    })

    val res = for {
      info <- boxInfo
      x    <- useCache(info)
    } yield {
      x
    }
    val t1 = System.currentTimeMillis
    TimingDebugLogger.debug(s"Get node info (${label}): ${t1-t0}ms")
    res
  }

  /**
   * An utility method that gets data from backend for things that are
   * node really nodes (pending or deleted).
   */
  private[this] def getNotAcceptedNodeDataFromBackend(status: InventoryStatus): Box[Map[NodeId, NodeInfo]] = {
    import scala.collection.mutable.{Map => MutMap}

    //some map of things - mutable, yes
    val nodeInventories = MutMap[String, LDAPEntry]() // node_uuid -> entry
    val machineInventories = MutMap[String, LDAPEntry]() // machine_dn -> entry

    ldap.map { con =>

      val allEntries = getNodeInfoEntries(con, searchAttributes, status)
      // now, create the nodeInfo
      allEntries.foreach { e =>
        if(e.isA(OC_MACHINE)) {
          machineInventories += (e.dn.toString -> e)
        } else if(e.isA(OC_NODE)) {
          nodeInventories += (e.value_!(A_NODE_UUID) -> e)
        } else {
          // it's an error, don't use
        }
      }

      nodeInventories.flatMap { case (id, nodeEntry) =>
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
      }.toMap
    }
  }

  override final def getPendingNodeInfos(): Box[Map[NodeId, NodeInfo]] = getNotAcceptedNodeDataFromBackend(PendingInventory)

  override final def getDeletedNodeInfos(): Box[Map[NodeId, NodeInfo]] = getNotAcceptedNodeDataFromBackend(RemovedInventory)

  private[this] def getNotAcceptedNodeInfo(nodeId: NodeId, status: InventoryStatus): Box[Option[NodeInfo]] = {
    val dit = status match {
      case AcceptedInventory => inventoryDit
      case PendingInventory  => pendingDit
      case RemovedInventory  => removedDit
    }

    def sureGet(con: RoLDAPConnection, dn: DN): Box[Option[LDAPEntry]] = {
      con.get(dn, searchAttributes:_*) match {
        case f: Failure => f
        case Empty      => Full(None)
        case Full(e)    => Full(Some(e))
      }
    }

    for {
      con          <- ldap
      optNodeEntry <- sureGet(con, dit.NODES.NODE.dn(nodeId))
      nodeInfo     <- (optNodeEntry match {
                        case None            => Full(None)
                        case Some(nodeEntry) =>
                          nodeEntry.getAsDn(A_CONTAINER_DN) match {
                            case None     => Full(None)
                            case Some(dn) =>
                              for {
                                machineEntry <- sureGet(con, dn)
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

  override final def getPendingNodeInfo(nodeId: NodeId): Box[Option[NodeInfo]] = getNotAcceptedNodeInfo(nodeId, PendingInventory)
  override final def getDeletedNodeInfo(nodeId: NodeId): Box[Option[NodeInfo]] = getNotAcceptedNodeInfo(nodeId, RemovedInventory)

  /**
   * Clear cache. Try a reload asynchronously, disregarding
   * the result
   */
  override def clearCache(): Unit = this.synchronized {
    this.lastModificationTime = new DateTime(0)
    this.nodeCache = None
  }

  def getAll(): Box[Map[NodeId, NodeInfo]] = withUpToDateCache("all nodes info") { cache =>
    Full(cache.mapValues(_._2))
  }
  def getAllSystemNodeIds(): Box[Seq[NodeId]] = withUpToDateCache("all system nodes") { cache =>
    Full(cache.collect { case(k, (_,x)) if(x.isPolicyServer) => k }.toSeq)
  }

  def getAllNodes(): Box[Map[NodeId, Node]] = withUpToDateCache("all nodes") { cache =>
    Full(cache.mapValues(_._2.node))
  }

  def getLDAPNodeInfo(nodeIds: Set[NodeId]): Box[Set[LDAPNodeInfo]] = {
    if(nodeIds.size > 0) {
      withUpToDateCache(s"${nodeIds.size} ldap node info") { cache =>
        Full(cache.collect { case(k, (x,_)) if(nodeIds.contains(k)) => x }.toSet)
      }
    } else {
      Full(Set())
    }
  }
  def getNode(nodeId: NodeId): Box[Node] = withUpToDateCache(s"${nodeId.value} node") { cache =>
    Box(cache.get(nodeId).map( _._2.node)) ?~! s"Node with ID '${nodeId.value}' was not found"
  }
  def getNodeInfo(nodeId: NodeId): Box[Option[NodeInfo]] = withUpToDateCache(s"${nodeId.value} node info") { cache =>
    Full(cache.get(nodeId).map( _._2))
  }

}

/**
 * A testing implementation, that just retrieve node info each time. Not very efficient.
 */
class NaiveNodeInfoServiceCachedImpl(
    override val ldap           : LDAPConnectionProvider[RoLDAPConnection]
  , override val nodeDit        : NodeDit
  , override val inventoryDit   : InventoryDit
  , override val removedDit     : InventoryDit
  , override val pendingDit     : InventoryDit
  , override val ldapMapper     : LDAPEntityMapper
  , override val inventoryMapper: InventoryMapper
) extends NodeInfoServiceCached with Loggable  {

  override def isUpToDate(lastKnowModification: DateTime): Boolean = {
    false //yes naive
  }

  /**
   * This method must return only and all entries under:
   * - ou=Nodes,
   * - ou=[Node, Machine], ou=Accepted Inventories, etc
   */
  override def getNodeInfoEntries(con: RoLDAPConnection, searchAttributes: Seq[String], status: InventoryStatus ): Seq[LDAPEntry] = {
    val nodeInvs = con.search(inventoryDit.NODES.dn, One, BuildFilter.ALL, searchAttributes:_*)
    val machineInvs = con.search(inventoryDit.MACHINES.dn, One, BuildFilter.ALL, searchAttributes:_*)

    nodeInvs ++ machineInvs ++ (if(status == AcceptedInventory) {
        con.search(nodeDit.NODES.dn, One, BuildFilter.ALL, searchAttributes:_*)
      } else {
        Seq()
      })
  }

}

/**
 * A cache on top of node info service.
 *
 */

class NodeInfoServiceCachedImpl(
    override val ldap           : LDAPConnectionProvider[RoLDAPConnection]
  , override val nodeDit        : NodeDit
  , override val inventoryDit   : InventoryDit
  , override val removedDit     : InventoryDit
  , override val pendingDit     : InventoryDit
  , override val ldapMapper     : LDAPEntityMapper
  , override val inventoryMapper: InventoryMapper
) extends NodeInfoServiceCached {
  import NodeInfoService._

 /*
   * Check if node related infos are up to date.
   *
   * Here, we need to only check for attributeModifyTimestamp
   * under ou=AcceptedInventories and under ou=Nodes (onelevel)
   * and only for machines, inventory nodes, and node,
   * and inventory nodes under ou=RemovedInventories
   *  Reason:
   * - only these three entries are used for node info (and none of their
   *   subentries)
   * - if a node is deleted, it must go to RemovedInventories (and so the
   *   machine, but we don't care as soon as we know a node went there)
   * - for ou=Node, all modifications happen in the entry, so the modifyTimestamp
   *   is changed accordingly.
   *
   * Moreover, it is less costly to only do one search and post-filter result
   * than to do 2.
   *
   * Finally, we limit the result to one, because here, we just need to know if
   * some update exists, not WHAT they are, nor the MOST RECENT one. Just that
   * at least one exists.
   *
   * A cleaner implementation could use a two persistent search which would notify
   * when a cache becomes invalide and reset it, but the rationnal for that implementation is:
   * - it's extremelly simple to understand the logic (if(cache is uptodate) use it else update cache)
   * - most of the time (99.99% of it), the search will return 0 result and will be cache on OpenLDAP,
   *   whatever the number of entries. So we talking of a request taking a couple of ms on the server
   *   (with a vagrant VM on the same host (so, almost no network), it takes from client to server and
   *   back ~10ms on a dev machine.
   */
  override def isUpToDate(lastKnowModification: DateTime): Boolean = {
    val n0 = System.currentTimeMillis
    val searchRequest = new SearchRequest(nodeDit.BASE_DN.toString, Sub, DereferencePolicy.NEVER, 1, 0, false
        , AND(
             OR(
                  // ou=Removed Inventories,ou=Inventories,cn=rudder-configuration
                  AND(IS(OC_NODE), Filter.create(s"entryDN:dnOneLevelMatch:=${removedDit.NODES.dn.toString}"))
                  // ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration
                , AND(IS(OC_NODE), Filter.create(s"entryDN:dnOneLevelMatch:=${inventoryDit.NODES.dn.toString}"))
                , AND(IS(OC_MACHINE), Filter.create(s"entryDN:dnOneLevelMatch:=${inventoryDit.MACHINES.dn.toString}"))
                  // ou=Nodes,cn=rudder-configuration - the objectClass is used only here
                , AND(IS(OC_RUDDER_NODE), Filter.create(s"entryDN:dnOneLevelMatch:=${nodeDit.NODES.dn.toString}"))
              )
            , GTEQ(A_MOD_TIMESTAMP, GeneralizedTime(lastKnowModification.plusMillis(1)).toString)
          )
        , "1.1"
      )

    val res = ldap.map { con =>
      //here, I have to rely on low-level LDAP connection, because I need to proceed size-limit exceeded as OK
      try {
        con.backed.search(searchRequest).getSearchEntries
      } catch {
        case e:LDAPSearchException if(e.getResultCode == ResultCode.SIZE_LIMIT_EXCEEDED) =>
          e.getSearchEntries()
      }
    } match {
      case Full(seq) =>
        //we only have interesting entries in the result, so it's up to date if we have exactly 0 entries
        val res = seq.size <= 0
        logger.trace(s"Cache check for node info gave '${res}' (${seq.size} entry returned)")
        res
      case eb: EmptyBox =>
        val e = eb ?~! "Error when checking for cache expiration: invalidating it"
        logger.debug(e.messageChain)
        false
    }
    val n1 = System.currentTimeMillis
    TimingDebugLogger.debug(s"Cache for nodes info expire ?: ${n1-n0}ms")
    res
  }

  /**
   * This method must return only and all entries under:
   * - ou=Nodes,
   * - ou=[Node, Machine], ou=Accepted Inventories, etc
   */
  override def getNodeInfoEntries(con: RoLDAPConnection, searchAttributes: Seq[String], status: InventoryStatus): Seq[LDAPEntry] = {
    val dit = status match {
      case AcceptedInventory => inventoryDit
      case PendingInventory  => pendingDit
      case RemovedInventory  => removedDit
    }

    val filter = Seq(
          AND(IS(OC_NODE), Filter.create(s"entryDN:dnOneLevelMatch:=${dit.NODES.dn.toString}"))
        , AND(IS(OC_MACHINE), Filter.create(s"entryDN:dnOneLevelMatch:=${dit.MACHINES.dn.toString}"))
      ) ++ (if(status == AcceptedInventory) {
          Seq(AND(IS(OC_RUDDER_NODE), Filter.create(s"entryDN:dnOneLevelMatch:=${nodeDit.NODES.dn.toString}")))
        } else {
          Seq()
        }
      )

    con.search(nodeDit.BASE_DN, Sub, OR(filter:_*), searchAttributes:_*)
  }
}
