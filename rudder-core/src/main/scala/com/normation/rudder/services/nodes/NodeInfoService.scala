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
import com.normation.rudder.domain.nodes.NodeInfo
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

trait NodeInfoService {

  /**
   * Return a NodeInfo from a NodeId. First check the ou=Node, then fetch the other data
   * @param nodeId
   * @return
   */
  def getNodeInfo(nodeId: NodeId) : Box[NodeInfo]

  /**
   * Return a seq of NodeInfo from a seq of NodeId.
   * If any of them fails, then we return Failure
   * @param nodeId
   * @return
   */
//  def find(nodeIds: Seq[NodeId]) : Box[Seq[NodeInfo]]


  /**
   * Get all node ids
   */
//  def getAllIds() : Box[Seq[NodeId]]

  /**
   * Get all node infos.
   * That method try to return the maximum
   * of information, and will not totally fail if some information are
   * missing (but the corresponding nodeInfos won't be present)
   * So it is possible that getAllIds.size > getAll.size
   */
  def getAll() : Box[Set[NodeInfo]]

  /**
   * Get all "simple" node ids (i.e, all user nodes,
   * for example, NOT policy servers)
   */
//  def getAllUserNodeIds() : Box[Seq[NodeId]]

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

class NodeInfoServiceImpl(
    nodeDit : NodeDit,
    rudderDit:RudderDit,
    inventoryDit:InventoryDit,
    ldap:LDAPConnectionProvider[RoLDAPConnection],
    ldapMapper:LDAPEntityMapper,
    inventoryMapper:InventoryMapper,
    inventoryDitService:InventoryDitService
) extends NodeInfoService with Loggable {
  import NodeInfoServiceImpl._

  def getNodeInfo(nodeId: NodeId) : Box[NodeInfo] = {
    logger.trace("Fetching node info for node id %s".format(nodeId.value))
    for {
      con <- ldap
      node <- con.get(nodeDit.NODES.NODE.dn(nodeId.value), nodeInfoAttributes:_*) ?~! "Node with ID '%s' was not found".format(nodeId.value)
      nodeInfo <- for {
                    server <- con.get(inventoryDit.NODES.NODE.dn(nodeId.value), nodeInfoAttributes:_*)  ?~! "Node info with ID '%s' was not found".format(nodeId.value)
                    machine <- {
                      val machines = inventoryMapper.mapSeqStringToMachineIdAndStatus(server.valuesFor(A_CONTAINER_DN))
                      if(machines.size == 0) Full(None)
                      else { //here, we only process the first machine
                        if(logger.isDebugEnabled && machines.size > 1) {
                          logger.debug("Node with id %s is attached to several container. Taking %s as the valid one, ignoring other (%s)".format(
                              nodeId.value, machines(0)._1.value, machines.tail.map( _._1).mkString(", "))
                          )
                        }
                        val (machineId, status) = machines(0)
                        val dit = inventoryDitService.getDit(status)

                        Full(con.get(dit.MACHINES.MACHINE.dn(machineId),Seq("*"):_*).toOption)
                      }
                    }
                    nodeInfo <- ldapMapper.convertEntriesToNodeInfos(node, server,machine)
                  } yield {
                    nodeInfo
                  }
    } yield {
      nodeInfo
    }
  }

  /**
   * Get all node infos.
   * That method try to return the maximum
   * of information, and will not totally fail if some information are
   * missing (but the corresponding nodeInfos won't be present)
   * So it is possible that getAllIds.size > getAll.size
   */
  def getAll() : Box[Set[NodeInfo]] = {
    ldap.map { con =>
      val allNodes = con.searchOne(nodeDit.NODES.dn, ALL, nodeInfoAttributes:_*).flatMap { node =>
        nodeDit.NODES.NODE.idFromDn(node.dn).map { (_, node) }
      }
      val allNodeInventories = con.searchOne(inventoryDit.NODES.dn, ALL, nodeInfoAttributes:_*).flatMap { nodeInv =>
        inventoryDit.NODES.NODE.idFromDN(nodeInv.dn).map{ (_, nodeInv) }
      }.toMap
      val allMachineInventories = con.searchOne(inventoryDit.MACHINES.dn, ALL).map( machine => (machine.dn, machine) ).toMap

      allNodes.flatMap { case (id, nodeEntry) =>
        for {
          nodeInv <- allNodeInventories.get(id)
          machineInv = for {
                         containerDn  <- nodeInv(A_CONTAINER_DN)
                         machineEntry <- allMachineInventories.get(containerDn)
                       } yield {
                         machineEntry
                       }
          nodeInfo <- ldapMapper.convertEntriesToNodeInfos(nodeEntry, nodeInv, machineInv)
        } yield {
          nodeInfo
        }
      }.toSet
    }
  }

  def find(nodeIds: Seq[NodeId]) : Box[Seq[NodeInfo]] = {
    sequence(nodeIds) { nodeId =>
      getNodeInfo(nodeId)
    }
  }

  /**
   * Get all node ids
   */
  def getAllIds() : Box[Seq[NodeId]] = {
    for {
      con <- ldap
      nodeIds <- sequence(con.searchOne(nodeDit.NODES.dn, IS(OC_RUDDER_NODE),  "1.1")) { entry =>
        nodeDit.NODES.NODE.idFromDn(entry.dn)
      }
    } yield {
      nodeIds
    }
  }

  /**
   * Get all "simple" node ids (i.e, all user nodes,
   * for example, NOT policy servers)
   */
  def getAllUserNodeIds() : Box[Seq[NodeId]] = {
    for {
      con <- ldap
      nodeIds <- sequence(con.searchOne(nodeDit.NODES.dn, AND(IS(OC_RUDDER_NODE), NOT(IS(OC_POLICY_SERVER_NODE))),  "1.1")) { entry =>
        nodeDit.NODES.NODE.idFromDn(entry.dn)
      }
    } yield {
      nodeIds
    }
  }

  /**
   * Get all systen node ids, for example
   * policy server node ids.
   * @return
   */
  def getAllSystemNodeIds() : Box[Seq[NodeId]] = {
    for {
      con <- ldap
      nodeIds <- sequence(con.searchOne(nodeDit.NODES.dn, IS(OC_POLICY_SERVER_NODE),  "1.1")) { entry =>
        nodeDit.NODES.NODE.idFromDn(entry.dn)
      }
    } yield {
      nodeIds
    }
  }
}
