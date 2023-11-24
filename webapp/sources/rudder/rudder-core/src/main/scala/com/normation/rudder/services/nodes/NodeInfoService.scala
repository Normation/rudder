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

import com.normation.errors.IOResult
import com.normation.inventory.domain._
import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.ldap.sdk._
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.domain.nodes.NodeInfo
import org.joda.time.DateTime

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
      A_CUSTOM_PROPERTY,
      A_SECURITY_TAG
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
