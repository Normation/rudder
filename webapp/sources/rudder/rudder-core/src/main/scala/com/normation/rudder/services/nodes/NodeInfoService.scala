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
import com.normation.rudder.domain.NodeDit
import net.liftweb.common._
import com.normation.rudder.domain.nodes.{Node, NodeInfo}
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.inventory.ldap.core.LDAPConstants._
import com.unboundid.ldap.sdk._
import com.normation.ldap.sdk._
import BuildFilter._
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.rudder.domain.logger.TimingDebugLogger
import com.normation.rudder.repository.CachedRepository
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.ldap.core.InventoryMapper
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.queries.And
import com.normation.rudder.domain.queries.CriterionComposition
import com.normation.rudder.domain.queries.NodeInfoMatcher
import com.normation.rudder.domain.queries.Or
import com.normation.ldap.sdk.LdapResult._

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
   * Retrieve minimal information needed for the node info, used (only) by the
   * LDAP QueryProcessor.
   */
  def getLDAPNodeInfo(nodeIds: Set[NodeId], predicates: Seq[NodeInfoMatcher], composition: CriterionComposition) : Box[Set[LDAPNodeInfo]]

  /**
   * Return a NodeInfo from a NodeId. First check the ou=Node, then fetch the other data
   * @param nodeId
   * @return
   */
  def getNodeInfo(nodeId: NodeId) : Box[Option[NodeInfo]]

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
    , A_NODE_UUID, A_NAME, A_DESCRIPTION, A_STATE, A_IS_SYSTEM
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
    //, serverRoles, archDescription, ram, timezone
    //, customProperties
    , A_NODE_UUID, A_HOSTNAME, A_LIST_OF_IP, A_INVENTORY_DATE, A_PKEYS
    , A_OS_NAME, A_OS_FULL_NAME, A_OS_VERSION, A_OS_KERNEL_VERSION, A_OS_SERVICE_PACK, A_WIN_USER_DOMAIN, A_WIN_COMPANY, A_WIN_KEY, A_WIN_ID
    , A_AGENTS_NAME, A_POLICY_SERVER_UUID, A_ROOT_USER
    , A_SERVER_ROLE, A_ARCH
    , A_CONTAINER_DN, A_OS_RAM, A_KEY_STATUS, A_TIMEZONE_NAME, A_TIMEZONE_OFFSET
    , A_CUSTOM_PROPERTY
  )).toSeq

  val A_MOD_TIMESTAMP = "modifyTimestamp"

}

/*
 * For test, we need a way to split the cache part from its retrieval.
 */

// our cache is modelized with a Map of entries, their last modification timestamp, and the corresponding entryCSN
final case class LocalNodeInfoCache(
    nodeInfos       : Map[NodeId, (LDAPNodeInfo, NodeInfo)]
  , lastModTime     : DateTime
  , lastModEntryCSN : Seq[String]
)


trait NodeInfoServiceCached extends NodeInfoService with Loggable with CachedRepository {
  import NodeInfoService._

  def ldap           : LDAPConnectionProvider[RoLDAPConnection]
  def nodeDit        : NodeDit
  def inventoryDit   : InventoryDit
  def removedDit     : InventoryDit
  def pendingDit     : InventoryDit
  def ldapMapper     : LDAPEntityMapper
  def inventoryMapper: InventoryMapper


  /*
   * Compare if cache is up to date (based on internal state of the cache)
   */
  def isUpToDate(): Boolean = {
    nodeCache match {
      case Some(cache) => checkUpToDate(cache.lastModTime, cache.lastModEntryCSN)
      case None        => checkUpToDate(new DateTime(0), Seq())
    }

  }

  /**
   * Check is LDAP directory contains updated entries compare
   * to the date we pass in arguments.
   * Entries may be any entry relevant for our cache, in particular,
   * some attention must be provided to deleted entries.
   */
  protected[this] def checkUpToDate(lastKnowModification: DateTime, lastModEntryCSN: Seq[String]): Boolean

  /**
   * This method must return only and all entries under:
   * - ou=Nodes,
   * - ou=[Node, Machine], ou=Accepted Inventories, etc
   *
   * attributes is the list of attributes needed in returned entries
   */
  def getNodeInfoEntries(con: RoLDAPConnection, attributes: Seq[String], status: InventoryStatus): LdapResult[Seq[LDAPEntry]]

  /*
   * Our cache
   */
  private[this] var nodeCache = Option.empty[LocalNodeInfoCache]

  // we need modifyTimestamp to search for update and entryCSN to remove already processed entries
  private[this] val searchAttributes = nodeInfoAttributes :+ A_MOD_TIMESTAMP :+ "entryCSN"

  /**
   * That's the method that do all the logic
   */
  private[this] def withUpToDateCache[T](label: String)(useCache: Map[NodeId, (LDAPNodeInfo, NodeInfo)] => LdapResult[T]): LdapResult[T] = this.synchronized {
    /*
     * Get all relevant info from backend along with the
     * date of the last modification
     */
    def getDataFromBackend(lastKnowModification: DateTime): LdapResult[LocalNodeInfoCache] = {
      import scala.collection.mutable.{Map => MutMap}

      //some map of things - mutable, yes
      val nodes = MutMap[String, LDAPEntry]() //node_uuid -> entry
      val nodeInventories = MutMap[String, LDAPEntry]() // node_uuid -> entry
      val machineInventories = MutMap[String, LDAPEntry]() // machine_dn -> entry

      val t0 = System.currentTimeMillis

      // two vars to keep track of the new last modification time and entries csn
      var lastModif = lastKnowModification
      val entriesCSN = scala.collection.mutable.Buffer[String]()


      ldap.flatMap(con =>
        con.search(
           removedDit.NODES.dn
         , One
         , AND(IS(OC_NODE), GTEQ(A_MOD_TIMESTAMP, GeneralizedTime(lastKnowModification).toString))
         , A_MOD_TIMESTAMP , "entryCSN"
       ).flatMap( deletedNodes =>
         getNodeInfoEntries(con, searchAttributes, AcceptedInventory).flatMap { allActiveEntries =>

        //look for the maxed timestamp
        (deletedNodes ++ allActiveEntries).foreach { e =>
          e.getAsGTime(A_MOD_TIMESTAMP) match {
            case None    => //nothing
            case Some(x) =>
              if(x.dateTime.isAfter(lastModif)) {
                lastModif = x.dateTime
                entriesCSN.clear()
              }
              if(x.dateTime == lastModif) {
                e("entryCSN").map(csn => entriesCSN.append(csn))
              }
          }
        }

        // now, create the nodeInfo
        allActiveEntries.foreach { e =>
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
            nodeInv    <- nodeInventories.get(id).orElse { //log missing elements
                            logger.debug(s"Node with id '${id}' is in ou=Nodes,cn=rudder-configuration but doesn't have an inventory")
                            None
                          }
            machineInv =  for {
                            containerDn  <- nodeInv(A_CONTAINER_DN)
                            machineEntry <- machineInventories.get(containerDn)
                          } yield {
                            machineEntry
                          }
            ldapNode   =  LDAPNodeInfo(nodeEntry, nodeInv, machineInv)
            nodeInfo   <- ldapMapper.convertEntriesToNodeInfos(ldapNode.nodeEntry, ldapNode.nodeInventoryEntry, ldapNode.machineEntry) match {
                            case Full(nodeInfo) => Some(nodeInfo)
                            case eb : EmptyBox =>
                              val fail = eb ?~! s"An error occured while updating node cache: can not unserialize node with id '${id}', it will be ignored"
                              logger.error(fail.messageChain)
                              // for now we only log the error message, and keep a None so Node data are not updated
                              None
                          }
          } yield {
            (nodeInfo.id, (ldapNode,nodeInfo))
          }
        }.toMap

        // here, we must ensure that root ID is on the list, else chaos ensue.
        // If root is missing, invalidate the case
        if(res.get(Constants.ROOT_POLICY_SERVER_ID).isEmpty) {
          val msg = "'root' node is missing from the list of nodes. Rudder can not work in that state. We clear the cache now to try" +
                    "to auto-correct the problem. If it persists, try to run 'rudder agent inventory && rudder agent run' " +
                    "from the root server and check /var/log/rudder/webapp/ logs for additionnal information."
          logger.error(msg)
          msg.failure
        } else {
          LocalNodeInfoCache(res, lastModif, entriesCSN).success
        }
      }))
    }

    //actual logic that check what to do (invalidate cache or not)

    val t0 = System.currentTimeMillis

    val boxInfo = (if(nodeCache.isEmpty || !isUpToDate()) {

      val lastUpdate = nodeCache.map(_.lastModTime).getOrElse(new DateTime(0))
      getDataFromBackend(lastUpdate) match {
        case Right(newCache) =>
          logger.debug(s"NodeInfo cache is not up to date, last modification time: '${newCache.lastModTime}', last cache update:"+
                       s" '${lastUpdate}' => reseting cache with ${newCache.nodeInfos.size} entries")
          logger.trace(s"NodeInfo cache updated entries: [${newCache.nodeInfos.keySet.map{ _.value }.mkString(", ")}]")
          nodeCache = Some(newCache)
          newCache.nodeInfos.success
        case Left(e) =>
          nodeCache = None
          e ?~! "Could not get node information from database"
      }
    } else {
      logger.debug(s"NodeInfo cache is up to date, ${nodeCache.map(c => s"last modification time: '${c.lastModTime}' for: '${c.lastModEntryCSN.mkString("','")}'").getOrElse("")}")
      nodeCache.get.nodeInfos.success //get is ok because in a synchronized block with a test on isEmpty
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
  private[this] def getNotAcceptedNodeDataFromBackend(status: InventoryStatus): LdapResult[Map[NodeId, NodeInfo]] = {
    import scala.collection.mutable.{Map => MutMap}

    //some map of things - mutable, yes
    val nodeInventories = MutMap[String, LDAPEntry]() // node_uuid -> entry
    val machineInventories = MutMap[String, LDAPEntry]() // machine_dn -> entry

    for {
      con        <- ldap
      allEntries <- getNodeInfoEntries(con, searchAttributes, status)
    } yield {
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

  override final def getPendingNodeInfos(): Box[Map[NodeId, NodeInfo]] = getNotAcceptedNodeDataFromBackend(PendingInventory).toBox

  override final def getDeletedNodeInfos(): Box[Map[NodeId, NodeInfo]] = getNotAcceptedNodeDataFromBackend(RemovedInventory).toBox

  private[this] def getNotAcceptedNodeInfo(nodeId: NodeId, status: InventoryStatus): LdapResult[Option[NodeInfo]] = {
    val dit = status match {
      case AcceptedInventory => inventoryDit
      case PendingInventory  => pendingDit
      case RemovedInventory  => removedDit
    }


    for {
      con          <- ldap
      optNodeEntry <- con.get(dit.NODES.NODE.dn(nodeId), searchAttributes:_*)
      nodeInfo     <- (optNodeEntry match {
                        case None            => None.success
                        case Some(nodeEntry) =>
                          nodeEntry.getAsDn(A_CONTAINER_DN) match {
                            case None     => None.success
                            case Some(dn) =>
                              for {
                                machineEntry <- con.get(dn, searchAttributes:_*)
                                nodeInfo     <- ldapMapper.convertEntriesToSpecialNodeInfos(nodeEntry, machineEntry).toLdapResult
                              } yield {
                                Some(nodeInfo)
                              }
                          }
                      })
    } yield {
      nodeInfo
    }
  }

  override final def getPendingNodeInfo(nodeId: NodeId): Box[Option[NodeInfo]] = getNotAcceptedNodeInfo(nodeId, PendingInventory).toBox
  override final def getDeletedNodeInfo(nodeId: NodeId): Box[Option[NodeInfo]] = getNotAcceptedNodeInfo(nodeId, RemovedInventory).toBox

  /**
   * Clear cache. Try a reload asynchronously, disregarding
   * the result
   */
  override def clearCache(): Unit = this.synchronized {
    this.nodeCache = None
  }

  def getAll(): Box[Map[NodeId, NodeInfo]] = withUpToDateCache("all nodes info") { cache =>
    cache.mapValues(_._2).success
  }.toBox
  def getAllSystemNodeIds(): Box[Seq[NodeId]] = withUpToDateCache("all system nodes") { cache =>
    cache.collect { case(k, (_,x)) if(x.isPolicyServer) => k }.toSeq.success
  }.toBox

  def getAllNodes(): Box[Map[NodeId, Node]] = withUpToDateCache("all nodes") { cache =>
    cache.mapValues(_._2.node).success
  }.toBox

  override def getLDAPNodeInfo(nodeIds: Set[NodeId], predicats: Seq[NodeInfoMatcher], composition: CriterionComposition): Box[Set[LDAPNodeInfo]] = {
    def comp(a: Boolean, b: Boolean) = composition match {
        case And => a && b
        case Or  => a || b
    }
    // utliity to combine predicats according to comp
    def combine(a: NodeInfoMatcher, b: NodeInfoMatcher) = new NodeInfoMatcher {
      override def matches(node: NodeInfo): Boolean = comp(a.matches(node), b.matches(node))
    }

    // if there is no predicats (ie no specific filter on NodeInfo), we don't have to filter and and we can get all Nodes information for all ids we got
    val p =
      if(predicats.isEmpty) {
        new NodeInfoMatcher { override def matches(node: NodeInfo): Boolean = true  }
      } else {
        predicats.reduceLeft(combine)
      }

    withUpToDateCache(s"${nodeIds.size} ldap node info") { cache =>
      cache.collect { case(k, (x,y)) if(nodeIds.contains(k) && p.matches(y)) => x }.toSet.success
    }
  }.toBox

  def getNodeInfo(nodeId: NodeId): Box[Option[NodeInfo]] = withUpToDateCache(s"${nodeId.value} node info") { cache =>
    cache.get(nodeId).map( _._2).success
  }.toBox
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

  override def checkUpToDate(lastKnowModification: DateTime, lastModEntryCSN: Seq[String]): Boolean = {
    false //yes naive
  }

  /**
   * This method must return only and all entries under:
   * - ou=Nodes,
   * - ou=[Node, Machine], ou=Accepted Inventories, etc
   */
  override def getNodeInfoEntries(con: RoLDAPConnection, searchAttributes: Seq[String], status: InventoryStatus ): LdapResult[Seq[LDAPEntry]] = {
    for {
      nodeInvs    <- con.search(inventoryDit.NODES.dn, One, BuildFilter.ALL, searchAttributes:_*)
      machineInvs <- con.search(inventoryDit.MACHINES.dn, One, BuildFilter.ALL, searchAttributes:_*)
      nodes       <- if(status == AcceptedInventory) {
                       con.search(nodeDit.NODES.dn, One, BuildFilter.ALL, searchAttributes:_*)
                     } else {
                       Seq().success
                     }
    } yield {
      nodeInvs ++ machineInvs ++ nodes
    }
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
   * when a cache becomes invalide and reset it, but the rationnal for that implementation is:
   * - it's extremelly simple to understand the logic (if(cache is uptodate) use it else update cache)
   * - most of the time (99.99% of it), the search will return 0 result and will be cache on OpenLDAP,
   *   whatever the number of entries. So we talking of a request taking a couple of ms on the server
   *   (with a vagrant VM on the same host (so, almost no network), it takes from client to server and
   *   back ~10ms on a dev machine.
   */
  override def checkUpToDate(lastKnowModification: DateTime, lastModEntryCSN: Seq[String]): Boolean = {
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
            , GTEQ(A_MOD_TIMESTAMP, GeneralizedTime(lastKnowModification).toString)
            , NOT(OR(lastModEntryCSN.map(csn => EQ("entryCSN", csn)):_*))
          )
        , "1.1"
      )

    val res = (for {
      con     <- ldap
      entries <- //here, I have to rely on low-level LDAP connection, because I need to proceed size-limit exceeded as OK
                 try {
                   con.backed.search(searchRequest).getSearchEntries.success
                 } catch {
                   case e:LDAPSearchException if(e.getResultCode == ResultCode.SIZE_LIMIT_EXCEEDED) =>
                     e.getSearchEntries().success
                   case e:LDAPException =>
                     LdapResultError.BackendException("Error when searching node information", e).failure
                 }
    } yield {
      entries
    }) match {
      case Right(seq) =>
        //we only have interesting entries in the result, so it's up to date if we have exactly 0 entries
        val res = seq.size <= 0
        logger.trace(s"Cache check for node info gave '${res}' (${seq.size} entry returned)")
        res
      case Left(eb) =>
        val e = LdapResultError.Chained("Error when checking for cache expiration: invalidating it", eb)
        logger.debug(e.msg)
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
  override def getNodeInfoEntries(con: RoLDAPConnection, searchAttributes: Seq[String], status: InventoryStatus): LdapResult[Seq[LDAPEntry]] = {
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
