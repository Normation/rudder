package com.normation.rudder.services.nodes

import com.normation.errors.IOResult
import com.normation.inventory.domain.{InventoryStatus, MachineUuid, NodeId}
import com.normation.inventory.ldap.core.{InventoryDit, InventoryMapper}
import com.normation.inventory.ldap.core.LDAPConstants.{A_CONTAINER_DN, A_DESCRIPTION, A_HOSTNAME, A_NAME, A_NODE_UUID, A_POLICY_SERVER_UUID}
import com.normation.ldap.sdk.LDAPIOResult.LDAPIOResult
import com.normation.ldap.sdk.{LDAPConnectionProvider, LDAPEntry, RoLDAPConnection}
import com.normation.rudder.domain.RudderLDAPConstants.A_POLICY_MODE
import com.normation.rudder.domain.{NodeDit, RudderDit}
import com.normation.rudder.domain.nodes.{MachineInfo, Node, NodeInfo}
import com.normation.rudder.domain.nodes.NodeState.Enabled
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.unboundid.ldap.sdk.{DN, RDN}
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.collection.mutable.{Map => MutMap}

/*
 * Test the cache behaviour
 */
@RunWith(classOf[JUnitRunner])
class NodeInfoServiceCachedTest extends Specification {

  sequential

  def DN(rdn: String, parent: DN) = new DN(new RDN(rdn),  parent)
  val LDAP_BASEDN = new DN("cn=rudder-configuration")
  val LDAP_INVENTORIES_BASEDN = DN("ou=Inventories", LDAP_BASEDN)
  val LDAP_INVENTORIES_SOFTWARE_BASEDN = LDAP_INVENTORIES_BASEDN

  val rudderDit = new RudderDit(DN("ou=Rudder", LDAP_BASEDN))
  val nodeDit = new NodeDit(new DN("cn=rudder-configuration"))
  val inventoryDit = new InventoryDit(DN("ou=Accepted Inventories", DN("ou=Inventories", LDAP_BASEDN)), LDAP_INVENTORIES_SOFTWARE_BASEDN, "Accepted inventories")

  val nodeInfoService = new NodeInfoServiceCached() {
    override def ldap: LDAPConnectionProvider[RoLDAPConnection] = ???
    override def nodeDit: NodeDit = ???
    override def inventoryDit: InventoryDit = ???
    override def removedDit: InventoryDit = ???
    override def pendingDit: InventoryDit = ???
    override def ldapMapper: LDAPEntityMapper = ???
    override def inventoryMapper: InventoryMapper = ???
    override protected[this] def checkUpToDate(lastKnowModification: DateTime, lastModEntryCSN: Seq[String]): IOResult[Boolean] = ???
    override def getNodeInfoEntries(con: RoLDAPConnection, attributes: Seq[String], status: InventoryStatus): LDAPIOResult[Seq[LDAPEntry]] = ???
    override def getNewNodeInfoEntries(con: RoLDAPConnection, lastKnowModification: DateTime, searchAttributes: Seq[String]): LDAPIOResult[Seq[LDAPEntry]] = ???
    def setNodeCache(newCache : Option[LocalNodeInfoCache]) = {
      nodeCache = newCache
    }
  }
  def createNodeInfo(
      id: NodeId
    , machineUuid: Option[MachineUuid]) : NodeInfo = {
    NodeInfo(
        Node(id, id.value, id.value, Enabled, false, false, new DateTime(), null, null, None)
      , id.value
      , machineUuid.map(x => MachineInfo(x, null, None, None)), null, List(), new DateTime(0), null, Seq(), NodeId("root"), "root", Set(), None, None, None
    )
  }

  // create the ldap node ifo, with an option for the machine entry (which is not mandatory)
  def createLdapNodeInfo(
     node: NodeInfo
  ) : LDAPNodeInfo = {
    val nodeEntry = nodeDit.NODES.NODE.nodeModel(node.id)
    nodeEntry +=! (A_NAME, node.name)
    nodeEntry +=! (A_DESCRIPTION, node.node.description)

    for {
      mode <- node.node.policyMode
    } nodeEntry += (A_POLICY_MODE, mode.name)

    val machineEntry = node.machine.map(x => inventoryDit.MACHINES.MACHINE.model(x.id))

    val nodeInvEntry = inventoryDit.NODES.NODE.genericModel(node.id)

    nodeInvEntry +=! (A_HOSTNAME, node.name)
    nodeInvEntry +=! (A_POLICY_SERVER_UUID, node.policyServerId.value)
    machineEntry.map( mac => nodeInvEntry +=! (A_CONTAINER_DN, mac.dn.toString))

    LDAPNodeInfo(nodeEntry, nodeInvEntry, machineEntry)
  }

  def ldapNodeInfosToMaps(ldapNodeInfos: Seq[LDAPNodeInfo]) = {
    val seqs = ldapNodeInfos.map(x => (x.nodeEntry, x.nodeInventoryEntry, x.machineEntry))

    val nodeEntries = MutMap() ++ (for {
      entry <- seqs
      nodeEntry = entry._1
    } yield {
      (nodeEntry.value_!(A_NODE_UUID), nodeEntry)
    }).toMap
    val nodeInventoriesEntries = MutMap() ++ (for {
      entry <- seqs
      nodeInventoryEntry = entry._2
    } yield {
      (nodeInventoryEntry.value_!(A_NODE_UUID), nodeInventoryEntry)
    }).toMap
    val machineEntries = MutMap() ++ (for {
      entry <- seqs
      machineEntry <- entry._3
      machineDn = machineEntry.dn.toString
    } yield {
      (machineDn, machineEntry)
    }).toMap

    (nodeEntries, nodeInventoriesEntries, machineEntries)
  }

  " with a standard cache " should {
    val nodes = Map("1" -> Some("M1")
                  , "2" -> Some("M2")
                  , "3" -> Some("M3")
                  , "4" -> None
                  , "5" -> None )

    val nodeInfos = nodes.map { case (id, machineId) =>
      createNodeInfo(NodeId(id), machineId.map(MachineUuid(_)))
    }

    val ldapNodesInfos = nodeInfos.map { case nodeinfo =>
      (nodeinfo.id, (createLdapNodeInfo(nodeinfo), nodeinfo))
    }.toMap

    nodeInfoService.setNodeCache(Some(LocalNodeInfoCache(ldapNodesInfos, new DateTime(), Seq(), ldapNodesInfos.size)))

    " be idempotent" in {
      val (nodeEntries, nodeInventoriesEntries, machineEntries) = ldapNodeInfosToMaps(ldapNodesInfos.values.map(_._1).toSeq)

      val ldap = nodeInfoService.constructNodes(nodeEntries, nodeInventoriesEntries, machineEntries)

      ldapNodesInfos.values.map(_._1).toSeq.sortBy(e => e.nodeEntry.dn.toString) === ldap.sortBy(e => e.nodeEntry.dn.toString)
    }

    " find only the new entry if we make a new entry " in {
      val newNode = Map("12" -> Some("M12"))
      val newNodeInfos = newNode.map { case (id, machineId) =>
        createNodeInfo(NodeId(id), machineId.map(MachineUuid(_)))
      }

      val newLdapNodesInfos = newNodeInfos.map { case nodeinfo =>
        (nodeinfo.id, (createLdapNodeInfo(nodeinfo), nodeinfo))
      }.toMap

      val (nodeEntries, nodeInventoriesEntries, machineEntries) = ldapNodeInfosToMaps(newLdapNodesInfos.values.map(_._1).toSeq)

      val ldap = nodeInfoService.constructNodes(nodeEntries, nodeInventoriesEntries, machineEntries)

      newLdapNodesInfos.values.map(_._1).toSeq === ldap.toSeq
    }

    "update an existing entry if we update a nodeInventory (policy server) " in {

      nodeInfoService.setNodeCache(Some(LocalNodeInfoCache(ldapNodesInfos, new DateTime(), Seq(), ldapNodesInfos.size)))

      val nodes = Map("1" -> Some("M1"))
      val nodeInfo = nodes.map { case (id, machineId) =>
        createNodeInfo(NodeId(id), machineId.map(MachineUuid(_)))
      }.head
      // reference in cache
      val oldLdapNodeInfo = createLdapNodeInfo(nodeInfo)
      // change the policy server id
      val newNodeInfo = nodeInfo.copy(policyServerId = NodeId("test"))
      val ldapInventoryEntry = createLdapNodeInfo(newNodeInfo).nodeInventoryEntry

      val ldap = nodeInfoService.constructNodes(MutMap(), MutMap(ldapInventoryEntry.value_!(A_NODE_UUID) -> ldapInventoryEntry), MutMap())

      ldap.size == 1 and
        ldap.head.nodeInventoryEntry === ldapInventoryEntry and
        ldap.head.nodeInventoryEntry != oldLdapNodeInfo.nodeInventoryEntry and
        ldap.head.nodeEntry          === oldLdapNodeInfo.nodeEntry and
        ldap.head.machineEntry       === oldLdapNodeInfo.machineEntry

    }


    "update an existing entry if we update a node (policy mode) " in {
      nodeInfoService.setNodeCache(Some(LocalNodeInfoCache(ldapNodesInfos, new DateTime(), Seq(), ldapNodesInfos.size)))

      val nodes = Map("1" -> Some("M1"))
      val nodeInfo = nodes.map { case (id, machineId) =>
        createNodeInfo(NodeId(id), machineId.map(MachineUuid(_)))
      }.head
      // reference in cache
      val oldLdapNodeInfo = createLdapNodeInfo(nodeInfo)

      // change the policy mode
      val newNode = nodeInfo.node.copy(policyMode = Some(PolicyMode.Audit))

      val newNodeInfo = nodeInfo.copy(node = newNode )
      val ldapNodeEntry = createLdapNodeInfo(newNodeInfo).nodeEntry

      val ldap = nodeInfoService.constructNodes(
            MutMap(ldapNodeEntry.value_!(A_NODE_UUID) -> ldapNodeEntry)
          , MutMap()
          , MutMap()
      )

      ldap.size == 1 and
        ldap.head.nodeEntry          === ldapNodeEntry and
        ldap.head.nodeEntry           != oldLdapNodeInfo.nodeEntry and
        ldap.head.nodeInventoryEntry === oldLdapNodeInfo.nodeInventoryEntry and
        ldap.head.machineEntry       === oldLdapNodeInfo.machineEntry

    }


  }

}
