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

/**
 * A case class used to represent the minimal
 * information needed to get a NodeInfo
 */
case class LDAPNodeInfo(
    nodeEntry            : LDAPEntry
  , nodeInventoryEntry   : LDAPEntry
  , machineObjectClasses : Option[Seq[String]]
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
  def getNodeInfo(nodeId: NodeId) : Box[NodeInfo]


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
   * Get all systen node ids, for example
   * policy server node ids.
   * @return
   */
  def getAllSystemNodeIds() : Box[Seq[NodeId]]
}

object NodeInfoServiceImpl {
  val nodeInfoAttributes = Seq(SearchRequest.ALL_USER_ATTRIBUTES, A_OBJECT_CREATION_DATE)
}


/**
 * A cache on top of node info service.
 *
 */

class NodeInfoServiceCachedImpl(
    ldap           : LDAPConnectionProvider[RoLDAPConnection]
  , nodeDit        : NodeDit
  , inventoryDit   : InventoryDit
  , removedDit     : InventoryDit
  , ldapMapper     : LDAPEntityMapper
) extends NodeInfoService with Loggable with CachedRepository {


  /*
   * Our cache
   */
  private[this] var nodeCache = Option.empty[Map[NodeId, (LDAPNodeInfo, NodeInfo, Node)]]
  private[this] var lastModificationTime = new DateTime(0)

  private[this] val A_MOD_TIMESTAMP = "modifyTimestamp"
  private[this] val searchAttributes = (NodeInfoServiceImpl.nodeInfoAttributes :+ A_MOD_TIMESTAMP).toSeq

  /**
   * That's the method that do all the logic
   */
  private[this] def withUpToDateCache[T](label: String)(useCache: Map[NodeId, (LDAPNodeInfo, NodeInfo, Node)] => Box[T]): Box[T] = this.synchronized {
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
    def isUpToDate(lastKnowModification: DateTime): Boolean = {
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

    /*
     * Get all relevant info from backend along with the
     * date of the last modification
     */
    def getDataFromBackend(lastKnowModification: DateTime): Box[(Map[NodeId, (LDAPNodeInfo, NodeInfo, Node)], DateTime)] = {
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

        val allEntries = con.search(
            nodeDit.BASE_DN
          , Sub
          , OR(
                AND(IS(OC_NODE), Filter.create(s"entryDN:dnOneLevelMatch:=${inventoryDit.NODES.dn.toString}"))
              , AND(IS(OC_MACHINE), Filter.create(s"entryDN:dnOneLevelMatch:=${inventoryDit.MACHINES.dn.toString}"))
              , AND(IS(OC_RUDDER_NODE), Filter.create(s"entryDN:dnOneLevelMatch:=${nodeDit.NODES.dn.toString}"))
            )
          , searchAttributes:_*
        )

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
            nodeInv <- nodeInventories.get(id)
            machineInv = for {
                           containerDn  <- nodeInv(A_CONTAINER_DN)
                           machineEntry <- machineInventories.get(containerDn)
                         } yield {
                           machineEntry
                         }
            ldapNode =  LDAPNodeInfo(nodeEntry, nodeInv, machineInv.map( _.valuesFor("objectClass").toSeq))
            nodeInfo <- ldapMapper.convertEntriesToNodeInfos(ldapNode.nodeEntry, ldapNode.nodeInventoryEntry, ldapNode.machineObjectClasses)
            node     <- ldapMapper.entryToNode(nodeEntry)
          } yield {
            (nodeInfo.id, (ldapNode,nodeInfo,node))
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
          logger.debug(s"NodeInfo cache is not up to date, last modification time: '${lastModif}', last cache update: '${lastModificationTime}'")
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
   * Clear cache. Try a reload asynchronously, disregarding
   * the result
   */
  override def clearCache(): Unit = this.synchronized {
    this.lastModificationTime = new DateTime(0)
    this.nodeCache = None
  }

  def getAll(): Box[Map[NodeId, NodeInfo]] = withUpToDateCache("all node") { cache =>
    Full(cache.mapValues(_._2))
  }
  def getAllSystemNodeIds(): Box[Seq[NodeId]] = withUpToDateCache("all system node") { cache =>
    Full(cache.collect { case(k, (_,x,_)) if(x.isPolicyServer) => k }.toSeq)
  }
  def getLDAPNodeInfo(nodeIds: Set[NodeId]): Box[Set[LDAPNodeInfo]] = {
    if(nodeIds.size > 0) {
      withUpToDateCache(s"${nodeIds.size} ldap node info") { cache =>
        Full(cache.collect { case(k, (x,_,_)) if(nodeIds.contains(k)) => x }.toSet)
      }
    } else {
      Full(Set())
    }
  }
  def getNode(nodeId: NodeId): Box[Node] = withUpToDateCache(s"${nodeId.value} node") { cache =>
    Box(cache.get(nodeId).map( _._3)) ?~! s"Node with ID '${nodeId.value}' was not found"
  }
  def getNodeInfo(nodeId: NodeId): Box[NodeInfo] = withUpToDateCache(s"${nodeId.value} node info") { cache =>
    Box(cache.get(nodeId).map( _._2)) ?~! s"Node with ID '${nodeId.value}' was not found"
  }

}
