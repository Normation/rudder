package com.normation.rudder.services.nodes

import com.normation.errors.IOResult
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.AcceptedInventory
import com.normation.inventory.domain.FullInventory
import com.normation.inventory.domain.InventoryStatus
import com.normation.inventory.domain.PendingInventory
import com.normation.inventory.domain.RemovedInventory
import com.normation.inventory.domain.{MachineUuid, NodeId}
import com.normation.inventory.ldap.core.FullInventoryRepositoryImpl
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.ldap.core.InventoryDitService
import com.normation.inventory.ldap.core.InventoryDitServiceImpl
import com.normation.inventory.ldap.core.InventoryMapper
import com.normation.inventory.ldap.core.LDAPConstants.OC_MACHINE
import com.normation.inventory.ldap.core.LDAPConstants.OC_NODE
import com.normation.inventory.ldap.core.LDAPConstants.{A_CONTAINER_DN, A_DESCRIPTION, A_HOSTNAME, A_NAME, A_NODE_UUID, A_POLICY_SERVER_UUID}
import com.normation.ldap.ldif.DefaultLDIFFileLogger
import com.normation.ldap.listener.InMemoryDsConnectionProvider
import com.normation.ldap.sdk.BuildFilter.AND
import com.normation.ldap.sdk.BuildFilter.GTEQ
import com.normation.ldap.sdk.BuildFilter.IS
import com.normation.ldap.sdk.GeneralizedTime
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.LDAPEntry
import com.normation.ldap.sdk.LDAPIOResult.LDAPIOResult
import com.normation.ldap.sdk.RoLDAPConnection
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.ldap.sdk.One
import com.normation.rudder.domain.RudderLDAPConstants.A_POLICY_MODE
import com.normation.rudder.domain.RudderLDAPConstants.OC_RUDDER_NODE
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.{NodeDit, RudderDit}
import com.normation.rudder.domain.nodes.{MachineInfo, Node, NodeInfo}
import com.normation.rudder.domain.nodes.NodeState.Enabled
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.reports.ReportingConfiguration
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.rudder.services.nodes.NodeInfoService.A_MOD_TIMESTAMP
import com.normation.rudder.services.policies.NodeConfigData
import com.normation.rudder.services.servers.AcceptFullInventoryInNodeOu
import com.normation.rudder.services.servers.AcceptInventory
import com.normation.rudder.services.servers.UnitAcceptInventory
import com.normation.rudder.services.servers.UnitRefuseInventory
import com.unboundid.ldap.sdk.Filter
import com.unboundid.ldap.sdk.{DN, RDN}
import net.liftweb.common.Full
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import com.normation.zio._

import scala.collection.mutable.{Map => MutMap}
import scala.collection.mutable.Buffer
import scala.concurrent.duration.FiniteDuration
import com.softwaremill.quicklens._
import net.liftweb.common.Box

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
  val inventoryDit = InventoryDit(DN("ou=Accepted Inventories", DN("ou=Inventories", LDAP_BASEDN)), LDAP_INVENTORIES_SOFTWARE_BASEDN, "Accepted inventories")

  def createNodeInfo(
      id: NodeId
    , machineUuid: Option[MachineUuid]) : NodeInfo = {
    NodeInfo(
        Node(id, id.value, id.value, Enabled, false, false, new DateTime(), null, null, None)
      , id.value
      , machineUuid.map(x => MachineInfo(x, null, None, None)), null, List(), new DateTime(0), null, Seq(), NodeId("root"), "root", None, None, None
    )
  }

  // create the ldap node ifo, with an option for the machine entry (which is not mandatory)
  def createLdapNodeInfo(
     node: NodeInfo
  ) : LDAPNodeInfo = {
    val nodeEntry = nodeDit.NODES.NODE.nodeModel(node.id)
    nodeEntry.resetValuesTo(A_NAME, node.name)
    nodeEntry.resetValuesTo(A_DESCRIPTION, node.node.description)

    for {
      mode <- node.node.policyMode
    } nodeEntry.resetValuesTo(A_POLICY_MODE, mode.name)

    val machineEntry = node.machine.map(x => inventoryDit.MACHINES.MACHINE.model(x.id))

    val nodeInvEntry = inventoryDit.NODES.NODE.genericModel(node.id)

    nodeInvEntry.resetValuesTo(A_HOSTNAME, node.name)
    nodeInvEntry.resetValuesTo(A_POLICY_SERVER_UUID, node.policyServerId.value)
    machineEntry.map( mac => nodeInvEntry.resetValuesTo(A_CONTAINER_DN, mac.dn.toString))

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

      ldapNodesInfos.values.map(_._1).toSeq.sortBy(e => e.nodeEntry.dn.toString) must containTheSameElementsAs(ldap.updated.toSeq)
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

      newLdapNodesInfos.values.map(_._1).toSeq must containTheSameElementsAs(ldap.updated.toSeq)
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
      val ldap = NodeInfoServiceCached.constructNodesFromPartialUpdate(cache, infoMaps).updated.toSeq

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

      val ldap = NodeInfoServiceCached.constructNodesFromPartialUpdate(cache, infoMaps).updated.toSeq

      ldap.size == 1 and
        ldap.head.nodeEntry          === ldapNodeEntry and
        ldap.head.nodeEntry           != oldLdapNodeInfo.nodeEntry and
        ldap.head.nodeInventoryEntry === oldLdapNodeInfo.nodeInventoryEntry and
        ldap.head.machineEntry       === oldLdapNodeInfo.machineEntry

    }

  }


  "with a real ldap server" should {

    val ldifLogger = new DefaultLDIFFileLogger("TestQueryProcessor","/tmp/normation/rudder/ldif")

    //init of in memory LDAP directory
    val schemaLDIFs = (
        "00-core" ::
        "01-pwpolicy" ::
        "04-rfc2307bis" ::
        "05-rfc4876" ::
        "099-0-inventory" ::
        "099-1-rudder"  ::
        Nil
    ) map { name =>
      // toURI is needed for https://issues.rudder.io/issues/19186
      this.getClass.getClassLoader.getResource("ldap-data/schema/" + name + ".ldif").toURI.getPath
    }
    val bootstrapLDIFs = ("ldap/bootstrap.ldif" :: "ldap-data/inventory-sample-data.ldif" :: Nil) map { name =>
       // toURI is needed for https://issues.rudder.io/issues/19186
       this.getClass.getClassLoader.getResource(name).toURI.getPath
    }
    val ldap = InMemoryDsConnectionProvider[RwLDAPConnection](
        baseDNs = "cn=rudder-configuration" :: Nil
      , schemaLDIFPaths = schemaLDIFs
      , bootstrapLDIFPaths = bootstrapLDIFs
      , ldifLogger
    )
    // close your eyes for next line
    val ldapRo = ldap.asInstanceOf[LDAPConnectionProvider[RoLDAPConnection]]

    val DIT = new InventoryDit(new DN("ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration"),new DN("ou=Inventories,cn=rudder-configuration"),"test")

    val removedDIT = new InventoryDit(new DN("ou=Removed Inventories,ou=Inventories,cn=rudder-configuration"),new DN("ou=Inventories,cn=rudder-configuration"),"test")
    val pendingDIT = new InventoryDit(new DN("ou=Pending Inventories,ou=Inventories,cn=rudder-configuration"),new DN("ou=Inventories,cn=rudder-configuration"),"test")
    val ditService = new InventoryDitServiceImpl(pendingDIT, DIT, removedDIT)
    val nodeDit = new NodeDit(new DN("cn=rudder-configuration"))
    val rudderDit = new RudderDit(new DN("ou=Rudder, cn=rudder-configuration"))
    val inventoryMapper = new InventoryMapper(ditService, pendingDIT, DIT, removedDIT)
    val ldapMapper = new LDAPEntityMapper(rudderDit, nodeDit, DIT, null, inventoryMapper)
    val inventoryDitService: InventoryDitService = new InventoryDitServiceImpl(pendingDIT, DIT, removedDIT)
    val ldapFullInventoryRepository = new FullInventoryRepositoryImpl(inventoryDitService, inventoryMapper, ldap)
    val acceptInventory: UnitAcceptInventory with UnitRefuseInventory = new AcceptInventory(
      "accept_new_server:inventory",
      pendingDIT,
      DIT,
      ldapFullInventoryRepository)
    val acceptNodeAndMachineInNodeOu: UnitAcceptInventory with UnitRefuseInventory = new AcceptFullInventoryInNodeOu(
        "accept_new_server:ou=node"
      , nodeDit
      , ldap
      , ldapMapper
      , PendingInventory
      , () => Full(None)
      , () => Full(NodeState.Enabled)
    )
    /*
     * Our cached node info service. For test, we need to override the search for entries, because the in memory
     * LDAP does not support searching only under some branch AND does not have an entryCSN.
     * So for search, we do 3 search and we always search for "lastMost + 1ms".
     *
     * WARNING: that means that we assume there is no change in the directory between each search, else
     * timestamp of last mod won't be consistant.
     */
    val nodeInfoService = new NodeInfoServiceCachedImpl(ldapRo, nodeDit, DIT, removedDIT, pendingDIT, ldapMapper, inventoryMapper, FiniteDuration(5, "millis")) {
      override def getNodeInfoEntries(con: RoLDAPConnection, searchAttributes: Seq[String], status: InventoryStatus, lastModification: Option[DateTime]): LDAPIOResult[Seq[LDAPEntry]] = {
        // for test, force to not look pending
        val dit = status match {
          case AcceptedInventory|PendingInventory => inventoryDit
          case RemovedInventory  => removedDit
        }

        def filter(filterNodes: Filter) = lastModification match {
          case None    => filterNodes
          case Some(d) => AND(filterNodes, GTEQ(A_MOD_TIMESTAMP, GeneralizedTime(d.plus(1)).toString))
        }

        for {
          res1 <- con.search(dit.NODES.dn    , One, filter(IS(OC_NODE))       , searchAttributes:_*)
          res2 <- con.search(dit.MACHINES.dn , One, filter(IS(OC_MACHINE))    , searchAttributes:_*)
          res3 <- con.search(nodeDit.NODES.dn, One, filter(IS(OC_RUDDER_NODE)), searchAttributes:_*)
        } yield res1 ++ res2 ++ res3
      }
    }

    implicit class ForceGetBox[A](b: Box[A]) {
      def forceGet = b match {
        case Full(a) => a
        case eb      => throw new IllegalArgumentException(s"Error during test, box is an erro: ${eb}")
      }
    }
    implicit class ForceGetIO[A](b: IOResult[A]) {
      def forceGet = b.either.runNow match {
        case Right(a)  => a
        case Left(err) => throw new IllegalArgumentException(s"Error during test, box is an erro: ${err.fullMsg}")
      }
    }

    "a new entry, with only node and then on next cache update inventory is ignored then found" in {
      // we have a new node. In NewNodeManager impl in RudderConfig, we start by acceptNodeAndMachineInNodeOu then acceptInventory


      // our new node
      val nodeInv = new FullInventory(
        NodeConfigData.nodeInventory1.modify(_.main.status).setTo(PendingInventory)
                                     .modify(_.main.id.value).setTo("testCacheNode")
                                     .modify(_.machineId).setTo(Some((MachineUuid("testCacheMachine"), PendingInventory)))
        , Some(NodeConfigData.machine2Pending.modify(_.id.value).setTo("testCacheMachine")
                                             .modify(_.name).setTo(Some("testCacheMachine"))
        )
      )
      val modid = ModificationId("test")
      val actor = EventActor("test")
      val nodeId = nodeInv.node.main.id


      // *************** start ****************
      // add inventory to pending
      ldapFullInventoryRepository.save(nodeInv).runNow
      //wait a bit for cache
      Thread.sleep(50)
      // load cache
      nodeInfoService.getAll().forceGet


      // org.slf4j.LoggerFactory.getLogger("nodes.cache").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.TRACE)

      // *************** step1 ****************
      // cache does not know about node1 yet
      acceptNodeAndMachineInNodeOu.acceptOne(nodeInv, modid, actor).forceGet
      val step1res = nodeInfoService.getNodeInfo(nodeId).forceGet

      // *************** step2 ****************
      // second new node step: cache converge
      acceptInventory.acceptOne(nodeInv, modid, actor).forceGet
      val step2res = nodeInfoService.getNodeInfo(nodeId).forceGet

      (step1res === None) and
      (step2res must beSome) and
        (step2res.get.machine must beSome)

    }

    "a new entry, with only the container first, and then on next cache update inventory is ignored then found" in {
      // our new node
      val nodeInv = new FullInventory(
        NodeConfigData.nodeInventory1.modify(_.main.status).setTo(PendingInventory)
          .modify(_.main.id.value).setTo("testCacheNode2")
          .modify(_.machineId).setTo(Some((MachineUuid("testCacheMachine2"), AcceptedInventory)))
        , Some(NodeConfigData.machine1Accepted.modify(_.id.value).setTo("testCacheMachine2")
          .modify(_.name).setTo(Some("testCacheMachine2"))
        )
      )
      val nodeId = nodeInv.node.main.id

      /** Create the node here, to "cheat" to simulate acceptation */
      val name = nodeInv.node.name.getOrElse(nodeInv.node.main.id.value)
      val description = nodeInv.node.description.getOrElse("")

      val node = Node(
          nodeId
        , name
        , description
        , NodeState.Enabled
        , false
        , false
        , DateTime.now // won't be used on save - dummy value
        , ReportingConfiguration(None,None, None) // use global schedule, and default configuration for reporting
        , Nil //no user properties for now
        , None
      )

      // *************** start ****************
      // Force init of cache
      nodeInfoService.getAll().forceGet

      // add node inventory to pending, but machine to accepted
      ldapFullInventoryRepository.save(nodeInv).runNow
      ldap.server.exportToLDIF("/tmp/ldif-before", false, false)

      //wait a bit for cache
      Thread.sleep(500)
      // load cache
      nodeInfoService.getAll().forceGet

      // *************** step1 ****************
      // cache does not know about testCacheNode2 yet
      val step1res = nodeInfoService.getNodeInfo(nodeId).forceGet


      // move the node to the accepted
      // *************** step2 ****************
      ldapFullInventoryRepository.moveNode(nodeId, PendingInventory, AcceptedInventory).runNow

      // create the entry in ou=Nodes
      val nodeEntry = ldapMapper.nodeToEntry(node)
      (for {
        con <- ldap
        saved <- con.save(nodeEntry)
      } yield {
        saved
      }).runNow

      ldapFullInventoryRepository.move(MachineUuid("testCacheMachine2"), PendingInventory)

      Thread.sleep(500)
      val step2res = nodeInfoService.getNodeInfo(nodeId).forceGet
      // It should find the container via compensation

      (step1res === None) and
        (step2res must beSome) and
        (step2res.get.machine must beSome)

    }
  }

}
