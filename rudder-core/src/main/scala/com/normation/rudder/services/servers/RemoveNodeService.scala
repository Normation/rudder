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
import com.normation.inventory.domain.AcceptedInventory
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.domain.log._

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
  def removeNode(nodeId : NodeId, actor:EventActor) : Box[Seq[LDIFChangeRecord]]
}


class RemoveNodeServiceImpl(
      nodeDit              : NodeDit
    , rudderDit            : RudderDit
    , ldap                 : LDAPConnectionProvider
    , ldapEntityMapper     : LDAPEntityMapper
    , nodeGroupRepository  : NodeGroupRepository
    , smRepo               : LDAPFullInventoryRepository 
    , actionLogger         : EventLogRepository
    , nodeInfoService      : NodeInfoService
) extends RemoveNodeService with Loggable {
  
  
  def removeNode(nodeId : NodeId, actor:EventActor) : Box[Seq[LDIFChangeRecord]] = {
    logger.debug("Trying to remove node %s from the LDAP".format(nodeId.value))
    for {
      nodeToDelete          <- nodeInfoService.getNodeInfo(nodeId)
      cleanGroup            <- deleteFromGroups(nodeId, actor) ?~! "Could not remove the node '%s' from the groups".format(nodeId.value)
      cleanNodeConfiguration<- deleteFromNodesConfiguration(nodeId) ?~! "Could not remove the node configuration of node '%s'".format(nodeId.value)
      cleanNode             <- deleteFromNodes(nodeId) ?~! "Could not remove the node '%s' from the nodes list".format(nodeId.value)
      cleanInventory        <- deleteNodeFromInventory(nodeId)?~! "Could not remove the node '%s' from the nodes list".format(nodeId.value)
      loggedAction          <- actionLogger.saveEventLog(DeleteNodeEventLog.fromNodeLogDetails(principal = actor, node = nodeToDelete)) ?~! "Error when logging deletion of a node"
    } yield {
      cleanNodeConfiguration
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
   * Deletes from ou=Nodes Configuration
   */
  private def deleteFromNodesConfiguration(nodeId:NodeId) : Box[Seq[LDIFChangeRecord]]= {
    logger.debug("Trying to remove node %s from ou=Nodes Configuration".format(nodeId.value))
    for {
      con    <- ldap
      dn     =  rudderDit.RUDDER_NODES.SERVER.dn(nodeId.value)
      result <- con.delete(dn)
    } yield {
      result
    }
  }
  
  /**
   * Look for the groups containing this node in their nodes list, and remove the node
   * from the list
   */
  private def deleteFromGroups(nodeId: NodeId, actor:EventActor): Box[Seq[ModifyNodeGroupDiff]]= {
    logger.debug("Trying to remove node %s from al lthe groups were it is references".format(nodeId.value))
    for {
      nodeGroupIds <- nodeGroupRepository.findGroupWithAnyMember(Seq(nodeId))
    } yield {
      (for {
        nodeGroups   <- nodeGroupIds.map(nodeGroupId => nodeGroupRepository.getNodeGroup(nodeGroupId))
        nodeGroup    <- nodeGroups
        updatedGroup =  nodeGroup.copy(serverList = nodeGroup.serverList - nodeId)
        diff         <- nodeGroupRepository.update(updatedGroup, actor)  ?~! "Could not update group %s to remove node '%s'".format(nodeGroup.id.value, nodeId.value)
      } yield {
        diff
      }).flatten
    }
  }
  
  /**
   * For the moment, eagerly delete the associated machine to a node
   */
  private def deleteNodeFromInventory(nodeId : NodeId) : Box[Seq[LDIFChangeRecord]] = {
    logger.debug("Trying to remove node %s from the inventory".format(nodeId.value))

    smRepo.delete(nodeId, AcceptedInventory)
  }
}