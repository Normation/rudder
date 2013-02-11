package com.normation.rudder.services.servers
import net.liftweb.common._
import com.normation.inventory.domain.NodeId
import com.unboundid.ldif.LDIFChangeRecord
import com.normation.rudder.domain.NodeDit
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import net.liftweb.common.Loggable
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.repository.NodeGroupRepository
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.nodes.ModifyNodeGroupDiff
import com.normation.inventory.ldap.core.LDAPFullInventoryRepository
import com.normation.inventory.domain.InventoryStatus
import com.normation.inventory.domain.{AcceptedInventory,RemovedInventory}
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.domain.eventlog._
import com.normation.utils.ScalaReadWriteLock
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.inventory.services.core.MachineRepository
import com.normation.inventory.services.core.WriteOnlyMachineRepository
import com.normation.inventory.services.core.ReadOnlyMachineRepository
import com.normation.eventlog.ModificationId
import com.normation.inventory.ldap.core.InventoryHistoryLogRepository
import com.normation.inventory.ldap.core.InventoryDit


trait RemoveNodeService {

  /**
   * Remove a node from the Rudder
   * For the moment, it really deletes it, later it would be useful to actually move it
   * What it does :
   * - clean the ou=Nodes
   * - clean the ou=Nodesconfiguration
   * - clean the groups
   * - clean the AcceptedNodeConfiguration
   */
  def removeNode(nodeId : NodeId, modId: ModificationId, actor:EventActor) : Box[Seq[LDIFChangeRecord]]
}


class RemoveNodeServiceImpl(
      nodeDit                   : NodeDit
    , rudderDit                 : RudderDit
    , ldap                      : LDAPConnectionProvider
    , ldapEntityMapper          : LDAPEntityMapper
    , nodeGroupRepository       : NodeGroupRepository
    , nodeConfigurationService  : NodeConfigurationService
    , nodeInfoService           : NodeInfoService
    , fullNodeRepo              : LDAPFullInventoryRepository
    , actionLogger              : EventLogRepository
    , groupLibMutex             : ScalaReadWriteLock //that's a scala-level mutex to have some kind of consistency with LDAP
) extends RemoveNodeService with Loggable {


  /**
   * the removal of a node is a multi-step system
   * First, fetch the node, then remove it from groups, and clear all node configurations
   * Move the node to the removed inventory (and don't forget to change its container dn)
   * Then find its container, to see if it has others nodes on it
   *        if so, copy the container to the removed inventory
   *        if not, move the container to the removed inventory
   *
   */
  def removeNode(nodeId : NodeId, modId: ModificationId, actor:EventActor) : Box[Seq[LDIFChangeRecord]] = {
    logger.debug("Trying to remove node %s from the LDAP".format(nodeId.value))
    nodeId.value match {
      case "root" => Failure("The root node cannot be deleted from the nodes list.")
      case _ => {
        for {
          nodeInfo <- nodeInfoService.getNodeInfo(nodeId)

          moved <- groupLibMutex.writeLock {atomicDelete(nodeId, modId, actor) } ?~!
                   "Error when archiving a node"

          eventLogged <- {
            val invLogDetails = InventoryLogDetails(
                            nodeId           = nodeInfo.id
                          , inventoryVersion = nodeInfo.inventoryDate
                          , hostname         = nodeInfo.hostname
                          , fullOsName       = nodeInfo.os
                          , actorIp          = actor.name
                        )
            val eventlog = DeleteNodeEventLog.fromInventoryLogDetails(
                principal = actor
              , inventoryDetails = invLogDetails )
            actionLogger.saveEventLog(modId, eventlog)
          }
        } yield {
          moved
        }
      }
    }
  }


  private[this] def atomicDelete(nodeId : NodeId, modId: ModificationId, actor:EventActor) : Box[Seq[LDIFChangeRecord]] = {
    for {
      cleanGroup            <- deleteFromGroups(nodeId, modId, actor) ?~! "Could not remove the node '%s' from the groups".format(nodeId.value)
      cleanNode             <- deleteFromNodes(nodeId) ?~! "Could not remove the node '%s' from the nodes list".format(nodeId.value)
      cleanNodeConfiguration<- deleteAllNodesConfiguration ?~! "Could not clear all cache"
      moveNodeInventory     <- fullNodeRepo.move(nodeId, AcceptedInventory, RemovedInventory)
    } yield {
      cleanNode ++ moveNodeInventory
    }
  }

  /**
   * Deletes from ou=Node
   */
  private def deleteFromNodes(nodeId:NodeId) : Box[Seq[LDIFChangeRecord]]= {
    logger.debug("Trying to remove node %s from ou=Nodes".format(nodeId.value))
    for {
      con    <- ldap
      dn     =  nodeDit.NODES.NODE.dn(nodeId.value)
      result <- con.delete(dn)
    } yield {
      result
    }
  }

   /**
   * Delete all node cnfiguration
   */
  private def deleteAllNodesConfiguration() : Box[Unit]= {
    logger.debug("Trying to clear all Nodes Configuration")
    for {
      result  <-  nodeConfigurationService.deleteAllNodeConfigurations()
    } yield {
      () // unit is expected
    }
  }

  /**
   * Look for the groups containing this node in their nodes list, and remove the node
   * from the list
   */
  private def deleteFromGroups(nodeId: NodeId, modId: ModificationId, actor:EventActor): Box[Seq[ModifyNodeGroupDiff]]= {
    logger.debug("Trying to remove node %s from all the groups were it is referenced".format(nodeId.value))
    for {
      nodeGroupIds <- nodeGroupRepository.findGroupWithAnyMember(Seq(nodeId))
    } yield {
      (for {
        nodeGroups   <- nodeGroupIds.map(nodeGroupId => nodeGroupRepository.getNodeGroup(nodeGroupId))
        nodeGroup    <- nodeGroups
        updatedGroup =  nodeGroup.copy(serverList = nodeGroup.serverList - nodeId)
        msg          =  Some("Automatic update of group due to deletion of node " + nodeId.value)
        diff         <- nodeGroupRepository.update(updatedGroup, modId, actor, msg)  ?~! "Could not update group %s to remove node '%s'".format(nodeGroup.id.value, nodeId.value)
      } yield {
        diff
      }).flatten
    }
  }


}
