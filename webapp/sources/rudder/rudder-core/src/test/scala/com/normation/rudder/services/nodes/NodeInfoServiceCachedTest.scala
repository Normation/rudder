package com.normation.rudder.services.nodes

import com.normation.inventory.domain.{MachineUuid, NodeId}
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.ldap.core.LDAPConstants.{A_CONTAINER_DN, A_DESCRIPTION, A_HOSTNAME, A_NAME, A_NODE_UUID, A_POLICY_SERVER_UUID}
import com.normation.rudder.domain.RudderLDAPConstants.A_POLICY_MODE
import com.normation.rudder.domain.{NodeDit, RudderDit}
import com.normation.rudder.domain.nodes.{MachineInfo, Node, NodeInfo}
import com.normation.rudder.domain.nodes.NodeState.Enabled
import com.normation.rudder.domain.policies.PolicyMode
import com.unboundid.ldap.sdk.{DN, RDN}
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.collection.mutable.{Map => MutMap}
import scala.collection.mutable.Buffer

/*
 * Test the cache behaviour
 */
@RunWith(classOf[JUnitRunner])
class NodeInfoServiceCachedTest extends Specification {

  def DN(rdn: String, parent: DN) = new DN(new RDN(rdn),  parent)
  val LDAP_BASEDN = new DN("cn=rudder-configuration")
  val LDAP_INVENTORIES_BASEDN = DN("ou=Inventories", LDAP_BASEDN)
  val LDAP_INVENTORIES_SOFTWARE_BASEDN = LDAP_INVENTORIES_BASEDN

  val rudderDit = new RudderDit(DN("ou=Rudder", LDAP_BASEDN))
  val nodeDit = new NodeDit(new DN("cn=rudder-configuration"))
  val inventoryDit = new InventoryDit(DN("ou=Accepted Inventories", DN("ou=Inventories", LDAP_BASEDN)), LDAP_INVENTORIES_SOFTWARE_BASEDN, "Accepted inventories")

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

  def ldapNodeInfosToMaps(ldapNodeInfos: Seq[LDAPNodeInfo]): NodeInfoServiceCached.InfoMaps = {
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

    NodeInfoServiceCached.InfoMaps(nodeEntries, nodeInventoriesEntries, machineEntries, Buffer())
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
    val cache = LocalNodeInfoCache(ldapNodesInfos, new DateTime(), Seq(), ldapNodesInfos.size)

    " be idempotent" in {
      val infoMaps = ldapNodeInfosToMaps(ldapNodesInfos.values.map(_._1).toSeq)

      val ldap = NodeInfoServiceCached.constructNodesFromPartialUpdate(cache, infoMaps)

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

      val infoMaps = ldapNodeInfosToMaps(newLdapNodesInfos.values.map(_._1).toSeq)

      val ldap = NodeInfoServiceCached.constructNodesFromPartialUpdate(cache, infoMaps)

      newLdapNodesInfos.values.map(_._1).toSeq === ldap.toSeq
    }

    "update an existing entry if we update a nodeInventory (policy server) " in {

      val nodes = Map("1" -> Some("M1"))
      val nodeInfo = nodes.map { case (id, machineId) =>
        createNodeInfo(NodeId(id), machineId.map(MachineUuid(_)))
      }.head
      // reference in cache
      val oldLdapNodeInfo = createLdapNodeInfo(nodeInfo)
      // change the policy server id
      val newNodeInfo = nodeInfo.copy(policyServerId = NodeId("test"))
      val ldapInventoryEntry = createLdapNodeInfo(newNodeInfo).nodeInventoryEntry

      val infoMaps = NodeInfoServiceCached.InfoMaps(MutMap(), MutMap(ldapInventoryEntry.value_!(A_NODE_UUID) -> ldapInventoryEntry), MutMap(), Buffer())
      val ldap = NodeInfoServiceCached.constructNodesFromPartialUpdate(cache, infoMaps)

      ldap.size == 1 and
        ldap.head.nodeInventoryEntry === ldapInventoryEntry and
        ldap.head.nodeInventoryEntry != oldLdapNodeInfo.nodeInventoryEntry and
        ldap.head.nodeEntry          === oldLdapNodeInfo.nodeEntry and
        ldap.head.machineEntry       === oldLdapNodeInfo.machineEntry

    }


    "update an existing entry if we update a node (policy mode) " in {
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

      val infoMaps = NodeInfoServiceCached.InfoMaps(MutMap(ldapNodeEntry.value_!(A_NODE_UUID) -> ldapNodeEntry), MutMap(), MutMap(), Buffer())

      val ldap = NodeInfoServiceCached.constructNodesFromPartialUpdate(cache, infoMaps)

      ldap.size == 1 and
        ldap.head.nodeEntry          === ldapNodeEntry and
        ldap.head.nodeEntry           != oldLdapNodeInfo.nodeEntry and
        ldap.head.nodeInventoryEntry === oldLdapNodeInfo.nodeInventoryEntry and
        ldap.head.machineEntry       === oldLdapNodeInfo.machineEntry

    }


  }

}
