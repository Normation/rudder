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

package com.normation.rudder.services.servers

import com.normation.rudder.batch.{AsyncDeploymentAgent,AutomaticStartDeployment}
import com.normation.rudder.domain._
import com.normation.rudder.domain.nodes._
import com.normation.rudder.domain.Constants._
import com.normation.rudder.domain.policies.RuleVal
import com.normation.rudder.domain.servers.{Srv,NodeConfiguration}
import com.normation.rudder.repository._
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.rudder.services.queries.DynGroupService
import com.normation.inventory.ldap.core.InventoryDitService
import com.normation.inventory.domain._
import com.normation.inventory.ldap.core._
import LDAPConstants._
import com.normation.inventory.ldap.core.LDAPFullInventoryRepository
import com.unboundid.ldap.sdk._
import com.unboundid.ldif.LDIFChangeRecord
import com.normation.ldap.sdk._
import BuildFilter.{ALL,EQ}
import com.normation.utils.Control._
import scala.collection.mutable.Buffer
import net.liftweb.common._
import Box._
import net.liftweb.util.Helpers._
import com.normation.cfclerk.domain.{Cf3PolicyDraftId,TechniqueId}
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import com.normation.eventlog.EventActor
import com.normation.inventory.services.core.ReadOnlyFullInventoryRepository
import com.normation.rudder.services.queries.QueryProcessor
import com.normation.rudder.domain.queries._
import java.lang.IllegalArgumentException
import com.normation.eventlog.ModificationId


/**
 * A trait to manage the acceptation of new node in Rudder
 */
trait NewNodeManager {

  /**
   * List all pending node
   */
  def listNewNodes : Box[Seq[Srv]]
  
  /**
   * Accept a pending node in Rudder
   */
  def accept(ids:Seq[NodeId], modId: ModificationId, actor:EventActor) : Seq[Box[NodeId]]

  /**
   * refuse node
   * @param ids : the node id
   * @return : the srv representations of the refused node
   */
  def refuse(ids:Seq[NodeId], modId: ModificationId, actor:EventActor) : Seq[Box[Srv]]

}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////


/**
 * Default implementation: a new server manager composed with a sequence of 
 * "unit" accept, one by main goals of what it means to accept a server; 
 * Each unit accept provides its main logic of accepting a server, optionnaly
 * a global post accept task, and a rollback mechanism.
 * Rollback is always a "best effort" task. 
 */
class NewNodeManagerImpl(
    override val ldap:LDAPConnectionProvider, 
    override val pendingNodesDit:InventoryDit,
    override val acceptedNodesDit:InventoryDit,
    override val serverSummaryService:NodeSummaryServiceImpl,
    override val smRepo:LDAPFullInventoryRepository, 
    override val unitAcceptors:Seq[UnitAcceptInventory],
    override val unitRefusors:Seq[UnitRefuseInventory]
) extends NewNodeManager with ListNewNode with ComposedNewNodeManager


///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////


trait ListNewNode extends NewNodeManager with Loggable {
  def ldap:LDAPConnectionProvider
  def serverSummaryService:NodeSummaryServiceImpl
  def pendingNodesDit:InventoryDit
  
  override def listNewNodes : Box[Seq[Srv]] = {
    ldap map { con => 
      con.searchOne(pendingNodesDit.NODES.dn,ALL,Srv.ldapAttributes:_*).map { e => 
        serverSummaryService.makeSrv(e) match {
          case Full(x) => Some(x)
          case b:EmptyBox => 
            val error = b ?~! "Error when mapping a pending node entry to a node object: %s".format(e.dn)
            logger.debug(error.messageChain)
            None
        }
      }.flatten
    }
  }
}


trait UnitRefuseInventory {
  
  def name : String
  
  def refuseOne(srv:Srv, modId: ModificationId, actor:EventActor) : Box[Srv]    
}

trait UnitAcceptInventory {
  
  /**
   * A name to describe the role of that acceptor
   */
  def name : String

  /**
   * The status of the inventory before that action
   */
  def fromInventoryStatus : InventoryStatus
  
  /**
   * The status of the inventory after that action
   */
  def toInventoryStatus : InventoryStatus
  
  /**
   * What to do ?
   */
  def acceptOne(sm:FullInventory, modId: ModificationId, actor:EventActor) : Box[FullInventory]
  
  /**
   * An action to execute before the whole batch 
   */
  def preAccept(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Box[Seq[FullInventory]]

  /**
   * An action to execute after the whole batch 
   */
  def postAccept(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Box[Seq[FullInventory]]

  /**
   * Execute a rollback for the given inventory
   */
  def rollback(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Unit
  
  def rollbackErrorMsg(e:EmptyBox, id:String) : String = {
    val msg = "Error when rollbacking server node id %s in process '%s', you should delete it by hand. ".format(id, this.name)
    
    e match {
      case Empty => msg + "No error message was left."
      case f:Failure => msg + "Error messages: " + f.messageChain
    }
  }
  
}

trait ComposedNewNodeManager extends NewNodeManager with Loggable {

  def ldap:LDAPConnectionProvider
  def pendingNodesDit:InventoryDit
  def acceptedNodesDit:InventoryDit
  def smRepo:LDAPFullInventoryRepository
  def serverSummaryService:NodeSummaryService
  def unitAcceptors:Seq[UnitAcceptInventory]
  def unitRefusors:Seq[UnitRefuseInventory]
 
  ////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////////////// Refuse //////////////////////////////////////
  ////////////////////////////////////////////////////////////////////////////////////
  
  /**
   * Refuse one server
   */
  private[this] def refuseOne(srv:Srv, modId: ModificationId, actor:EventActor) : Box[Srv] = {
    var errors = Option.empty[Failure]
    unitRefusors.foreach { refusor =>
      try {
        refusor.refuseOne(srv, modId, actor) match {
          case e:EmptyBox =>
            val msg = "Error refusing %s: step %s".format(srv.id, refusor.name)
            errors match {
              case None => errors = Some(e ?~! msg)
              case Some(old) => errors = Some(Failure(msg, Empty, Full(old)))
            }
          case Full(x) =>
            logger.trace("Refuse %s: step %s ok".format(srv.id, refusor.name))
        }
      } catch {
        case e:Exception => 
          val msg = "Error when trying to executre the step %s, when refusing inventory %".format(refusor.name, srv.id)
          errors match {
            case None => errors = Some(Failure(msg, Full(e),Empty))
            case Some(old) => errors = Some(Failure(msg, Full(e), Full(old)))
          }
      }
    }
    errors match {
      case Some(f) => f
      case None => Full(srv)
    } 
  }
  
  override def refuse(ids:Seq[NodeId], modId: ModificationId, actor:EventActor) : Seq[Box[Srv]] = {
    ids.flatMap { id => serverSummaryService.find(pendingNodesDit, id).openOr(Seq()) }.map { srv => 
      refuseOne(srv, modId, actor)
    }
  }
  
  ////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////////////// Accept //////////////////////////////////////
  ////////////////////////////////////////////////////////////////////////////////////

  private[this] def rollback(unitAcceptors:Seq[UnitAcceptInventory], rollbackOn:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Unit = {
    logger.debug("\n*****************************************************\nRollbaking\n*****************************************************")
    
    for{
      toRollback <- unitAcceptors.reverse
    } {
      logger.debug("Rollbacking %s for %s".format(toRollback.name, rollbackOn.map( _.node.main.id.value).mkString("; ")))
      try {
        toRollback.rollback(rollbackOn, modId, actor)
      } catch {
        case e:Exception => logger.error("Error when rollbacking acceptor process '%s'".format(toRollback.name))
      }
    }
  }
  
  
  /**
   * Accept one server. 
   * Accepting mean that the server went to all unitAccept items and that all of them 
   * succeeded. 
   * If one fails, all already passed are to be rollbacked (and the reverse order the
   * were executed)

   * 
   */
  private[this] def acceptOne(sm:FullInventory, modId: ModificationId, actor:EventActor) : Box[FullInventory] = {
    (sequence(unitAcceptors) { unitAcceptor =>
      try {
        unitAcceptor.acceptOne(sm, modId, actor) ?~! "Error when executing accept node process named %s".format(unitAcceptor.name)
      } catch {
        case e:Exception => {
          logger.debug("Exception in unit acceptor %s".format(unitAcceptor.name),e)
          Failure(e.getMessage, Full(e), Empty)
        }
      }
    }) match {
      case Full(seq) => Full(sm)
      case e:EmptyBox => //rollback that one
        logger.error((e ?~! "Error when trying to accept node %s. Rollbaking.".format(sm.node.main.id.value)).messageChain)
        rollback(unitAcceptors, Seq(sm), modId, actor)
        e
    }
  }
  
  
  override def accept(ids:Seq[NodeId], modId: ModificationId, actor:EventActor) : Seq[Box[NodeId]] = {
    //
    // start by retrieving all sms
    //
    val sms = (for {
      id <- ids
    } yield {
      //we want a seq of box, so here, it's a box
      for {
        sm <- smRepo.get(id, PendingInventory) ?~! "Can not accept not found inventory with id %s".format(id)
      } yield {
        (id,sm)
      }
    }).flatten   
    
    //
    //execute pre-accept phase for all unit acceptor
    // stop here in case of any error
    //
    unitAcceptors.foreach { unitAcceptor =>
      unitAcceptor.preAccept(sms.map( _._2), modId, actor) match {
        case Full(seq) => //ok, cool
          logger.debug("Pre accepted phase: %s".format(unitAcceptor.name))
        case e:EmptyBox => //on an error here, stop
          logger.error((e ?~! "Error when trying to execute pre-accepting for phase %s. Stop.".format(unitAcceptor.name)).messageChain)
          //stop now
          return Seq(e)
      }
    }
    
    
    //
    //now, execute unit acceptor
    //  
    
    //only keep the actually accpeted nodes
    val accepted = (for {
      (id,sm) <- sms
    } yield {
      //we want a seq of box, so here, it's a box
      for {
        accepted <- acceptOne(sm, modId, actor) ?~! "Error when trying to accept node %s".format(sm.node.main.id.value)
      } yield {
        (accepted,id)
      }
    })
    
    //log
    accepted.foreach { 
      case Full((sm, nodeId)) => logger.debug("Unit acceptors ok for %s".format(nodeId))
      case e:EmptyBox => logger.error((e ?~! "Unit acceptor error for a node").messageChain)
    }
    
    
    val acceptedSMs = accepted.flatten.map( _._1)
    val accpetedDNs = accepted.map(b => b.map(_._2))
    
    //
    //now, execute global post process
    //  
    (sequence(unitAcceptors) { unit =>
      try {
        unit.postAccept(acceptedSMs, modId, actor)
      } catch {
        case e:Exception => Failure(e.getMessage, Full(e), Empty)
      }
    }) match {
      case Full(seq) => //ok, cool
        logger.debug("Accepted inventories: %s".format(acceptedSMs.map(sm => sm.node.main.id.value).mkString("; ")))
        accpetedDNs
      case e:EmptyBox => //on an error here, rollback all accpeted
        logger.error((e ?~! "Error when trying to execute accepting new server post-processing. Rollback.").messageChain)
        rollback(unitAcceptors, acceptedSMs, modId, actor)
        Seq(e)
    }
  }

}


///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

/**
 * Unit acceptor for the inventory part.
 * Essentially: move FullInventory information
 * where they belongs. 
 *
 */
class AcceptInventory(
    override val name:String,
    pendingNodesDit:InventoryDit,
    acceptedNodesDit:InventoryDit,
    smRepo:LDAPFullInventoryRepository
) extends UnitAcceptInventory with UnitRefuseInventory with Loggable {
 
  override def preAccept(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Box[Seq[FullInventory]] = Full(sms) //nothing to do

  override def postAccept(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Box[Seq[FullInventory]] = Full(sms) //nothing to do
 
  override val fromInventoryStatus = PendingInventory
  
  override val toInventoryStatus = AcceptedInventory
  
  def acceptOne(sm:FullInventory, modId: ModificationId, actor:EventActor) : Box[FullInventory] = {
    smRepo.move(sm.node.main.id, fromInventoryStatus, toInventoryStatus).map { _ => sm }
  }
  
  def rollback(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Unit  = {
    sms.foreach { sm => 
      //rollback from accepted
      (for {
        result <- smRepo.move(sm.node.main.id, toInventoryStatus, fromInventoryStatus)
      } yield {
        result
      }) match {
        case e:EmptyBox => logger.error(rollbackErrorMsg(e, sm.node.main.id.value))
        case Full(f) => logger.debug("Succesfully rollbacked %s for process '%s'".format(sm.node.main.id, this.name))
      }
    }
  }
  
  //////////// refuse //////////// 
  override def refuseOne(srv:Srv, modId: ModificationId, actor:EventActor) : Box[Srv] = {
    //refuse an inventory: delete it
    smRepo.delete(srv.id, fromInventoryStatus).map( _ => srv)
  }
  
}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////


/**
 * Accept FullInventory at ou=node level: just add it
 * TODO: use NodeRepository
 */
class AcceptFullInventoryInNodeOu(
  override val name:String,
  nodeDit:NodeDit,
  ldap:LDAPConnectionProvider,
  ldapEntityMapper: LDAPEntityMapper,
  inventoryStatus:InventoryStatus //expected inventory status of nodes for that processor
) extends UnitAcceptInventory with UnitRefuseInventory with Loggable {
  
  
  override def preAccept(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Box[Seq[FullInventory]] = Full(sms) //nothing to do

  override def postAccept(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Box[Seq[FullInventory]] = Full(sms) //nothing to do

  override val fromInventoryStatus = inventoryStatus
  
  override val toInventoryStatus = inventoryStatus
  
  /**
   * Add a node entry in ou=Nodes
   */
  def acceptOne(sm:FullInventory, modId: ModificationId, actor:EventActor) : Box[FullInventory] = {
    val name = sm.node.name.getOrElse(sm.node.main.id.value)
    val description = sm.node.description.getOrElse("")
    
    //naive test to find is the node is the master policy server.
    //TODO: that can not handle relay server
    val isPolicyServer = sm.node.main.id == sm.node.main.policyServerId
    
    val node = Node(sm.node.main.id, name, description, false, false, isPolicyServer)
    val entry = ldapEntityMapper.nodeToEntry(node)
    for {
      con <- ldap
      res <- con.save(entry) ?~! "Error when trying to save node %s in process '%s'".format(entry.dn, this.name)
    } yield {
      sm
    }
  }
  
  /**
   * Just remove the node entry
   */
  def rollback(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Unit = {
    sms.foreach { sm => 
      (for {
        con <- ldap
        dn = nodeDit.NODES.NODE.dn(sm.node.main.id.value)
        result <- con.delete(dn)
      } yield {
        result
      }) match {
        case e:EmptyBox => logger.error(rollbackErrorMsg(e, sm.node.main.id.value))
        case Full(f) => logger.debug("Succesfully rollbacked %s for process '%s'".format(sm.node.main.id, this.name))
      }
    }
  }
  
  
  //////////// refuse //////////// 
  override def refuseOne(srv:Srv, modId: ModificationId, actor:EventActor) : Box[Srv] = {
    //refuse ou=nodes: delete it
    for {
      con <- ldap
      dn = nodeDit.NODES.NODE.dn(srv.id.value)
      result <- con.delete(dn)
    } yield {
      srv
    }
  }
  
}


/**
 * A unit acceptor in charge to add nodes to dyn groups
 * Be carefull, that acceptor only looks for node in
 * ou=pending branch, so it MUST be executed before
 * than nodes are moved to accepted branch
 */
class AddNodeToDynGroup(
    override val name:String,
    groupRepo: NodeGroupRepository,
    dynGroupService: DynGroupService,
    inventoryStatus: InventoryStatus
) extends UnitAcceptInventory with UnitRefuseInventory with Loggable {

  var dynGroupIdByNode : Map[NodeId,Seq[NodeGroupId]] = Map()
  
  override val fromInventoryStatus = inventoryStatus
  
  override val toInventoryStatus = inventoryStatus
  
  /**
   * In the pre-accept phase, we list for all node the list of dyn group id it belongs to, and then
   * add that node in them
   */
  override def preAccept(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Box[Seq[FullInventory]] = {
    for {
      nodeIds <- sequence(sms) { sm => Box.legacyNullTest(sm.node.main.id) }
      map <- dynGroupService.findDynGroups(nodeIds) ?~! "Error when building the map of dynamic group to update by node"
    } yield {
      dynGroupIdByNode = map
      logger.debug("Dynamic group to update (by node): %s".format(dynGroupIdByNode))
      sms
    }
  }

  /**
   * Add the server node id to dyn groups it belongs to
   * if the map does not have the node id, it just mean 
   * that that node does not match any dyngroup
   */
  def acceptOne(sm:FullInventory, modId: ModificationId, actor:EventActor) : Box[FullInventory] = {
    for {
      updatetGroup <- sequence(dynGroupIdByNode.getOrElse(sm.node.main.id, Seq())) { groupId =>
        for {
          group <- groupRepo.getNodeGroup(groupId) ?~! "Can not find group with id: %s".format(groupId)
          updatedGroup = group.copy( serverList = group.serverList + sm.node.main.id )
          msg = Some("Automatic update of system group due to acceptation of node "+ sm.node.main.id.value)
          saved <- groupRepo.update(updatedGroup, modId, actor, msg) ?~! "Error when trying to update dynamic group %s with member %s".format(groupId,sm.node.main.id.value)
        } yield {
          saved 
        }
      }
    } yield {
      sm
    }
  }
  
  
  /**
   * Rollback server: remove it from the policy server group it belongs to.
   * TODO: remove its node configuration, redeploy policies, etc. 
   */
  def rollback(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Unit = {
    sms.foreach { sm => 
      (for {
        updatetGroup <- sequence(dynGroupIdByNode.getOrElse(sm.node.main.id, Seq())) { groupId =>
          for {
            group <- groupRepo.getNodeGroup(groupId) ?~! "Can not find group with id: %s".format(groupId)
            updatedGroup = group.copy( serverList = group.serverList.filter(x => x != sm.node.main.id ) )
            msg = Some("Automatic update of system group due to rollback of acceptation of node "+ sm.node.main.id.value)
            saved <- groupRepo.update(updatedGroup, modId, actor, msg) ?~! "Error when trying to update dynamic group %s with member %s".format(groupId,sm.node.main.id.value)
          } yield {
            saved 
          }
        }
      } yield {
        sm
      }) match {
        case Full(_) => //ok
        case e:EmptyBox => logger.error((e ?~! "Error when rollbacking server %s".format(sm.node.main.id.value)).messageChain)
      }
    }
  }
  
  override def postAccept(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Box[Seq[FullInventory]] = Full(sms)

    
  //////////// refuse //////////// 
  override def refuseOne(srv:Srv, modId: ModificationId, actor:EventActor) : Box[Srv] = {
    //nothing, special processing for all groups
    Full(srv)
  }
}

class RefuseGroups(
  override val name:String,
  groupRepo: NodeGroupRepository
) extends UnitRefuseInventory with Loggable {
  
  //////////// refuse //////////// 
  override def refuseOne(srv:Srv, modId: ModificationId, actor:EventActor) : Box[Srv] = {
    //remove server id in all groups
    for {
      groupIds <- groupRepo.findGroupWithAnyMember(Seq(srv.id))
      modifiedGroups <- bestEffort(groupIds) { groupId =>
        for {
          group <- groupRepo.getNodeGroup(groupId)
          modGroup = group.copy( serverList = group.serverList - srv.id)
          msg = Some("Automatic update of groups due to refusal of node "+ srv.id.value)
          saved <- groupRepo.update(modGroup, modId, actor, msg)
        } yield {
          saved
        }
      }
    } yield {
      srv
    }
  }  
}
  
/**
 * A unit acceptor in charge to update rules
 */
class AcceptNodeRule(
    override val name:String,
    asyncDeploymentAgent:AsyncDeploymentAgent,
    groupRepo: NodeGroupRepository,
    nodeConfigRepo:NodeConfigurationRepository,
    inventoryStatus: InventoryStatus
) extends UnitAcceptInventory with UnitRefuseInventory with Loggable {
  
  override def preAccept(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Box[Seq[FullInventory]] = Full(sms) //nothing to do

  override val fromInventoryStatus = inventoryStatus
  
  override val toInventoryStatus = inventoryStatus
  
  /**
   * Only add the server to the list of children of the policy server
   */
  def acceptOne(sm:FullInventory, modId: ModificationId, actor:EventActor) : Box[FullInventory] = {
    for {
      addedToPolicyServer <- addNodeToPolicyServerGroup(sm,sm.node.main.id, modId, actor)
    } yield {
      sm
    } 
  }
  
  /**
   * add the server to the list of children of the policy server
   */
  private[this] def addNodeToPolicyServerGroup(sm:FullInventory, nodeId:NodeId, modId: ModificationId, actor:EventActor) : Box[NodeId] = {
    val hasPolicyServerNodeGroup = buildHasPolicyServerGroupId(sm.node.main.policyServerId) 
    for {
      group <- groupRepo.getNodeGroup(hasPolicyServerNodeGroup) ?~! "Technical group with ID '%s' was not found".format(hasPolicyServerNodeGroup)
      updatedGroup = group.copy( serverList = group.serverList + nodeId )
      msg = Some("Automatic update of system group due to acceptation of node "+ sm.node.main.id.value)
      saved<- groupRepo.update(updatedGroup, modId, actor, msg) ?~! "Could not update the technical group with ID '%s'".format(updatedGroup.id )
    } yield {
      nodeId
    } 
  }
  
  /**
   * Rollback server: remove it from the policy server group it belongs to.
   * TODO: remove its node configuration, redeploy policies, etc. 
   */
  def rollback(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Unit = {
    sms.foreach { sm => 
      (for {
        group <- groupRepo.getNodeGroup(buildHasPolicyServerGroupId(sm.node.main.policyServerId)) ?~! "Can not find group with id: %s".format(sm.node.main.policyServerId)
        updatedGroup = group.copy( serverList = group.serverList.filter(x => x != sm.node.main.id ) )
        msg = Some("Automatic update of system group due to rollback of acceptation of node "+ sm.node.main.id.value)
        saved<- groupRepo.update(updatedGroup, modId, actor, msg)?~! "Error when trying to update dynamic group %s with member %s".format(updatedGroup.id,sm.node.main.id.value)
      } yield {
        sm
      }) match {
        case Full(_) => //ok
        case e:EmptyBox => logger.error((e ?~! "Error when rollbacking server %s".format(sm.node.main.id.value)).messageChain)
      }
    }
  }  
  
  override def postAccept(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Box[Seq[FullInventory]] = {
    asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)
    logger.debug("Successfully sent deployment request")
    Full(sms)
  }
 
  
  //////////// refuse //////////// 
  override def refuseOne(srv:Srv, modId: ModificationId, actor:EventActor) : Box[Srv] = {
    //remove node rule
    for {
      deleted <- nodeConfigRepo.deleteNodeConfiguration(srv.id.value)
    } yield {
      srv
    }
  }   
}

/**
 * We don't want to have node with the same hostname.
 * That means don't accept two nodes with the same hostname, and don't 
 * accept a node with a hostname already existing in database. 
 */
class AcceptHostnameAndIp(
    override val name: String
  , inventoryStatus  : InventoryStatus
  , queryProcessor   : QueryProcessor
  , ditQueryData     : DitQueryData
) extends UnitAcceptInventory {
  
  

  
  
  //return the list of ducplicated hostname from user input - we want that to be empty
  private[this] def checkDuplicateString(attributes:Seq[String], attributeName:String) : Box[Unit]= {
    val duplicates = attributes.groupBy( x => x ).collect { case (k,v) if v.size > 1 => v.head }.toSeq.sorted
    if(duplicates.isEmpty) Full({})
    else Failure("You can not accept two nodes with the same %s: %s".format(attributeName,duplicates.mkString("'", "', ", "'")))
  }
  
  //some constant data for the query about hostname on node
  private[this] val objectType = ditQueryData.criteriaMap(OC_NODE)
  private[this] val hostnameCriteria = objectType.criteria.find(c => c.name == A_HOSTNAME).
    getOrElse(throw new IllegalArgumentException("Data model inconsistency: missing '%s' criterion in object type '%s'".format(A_HOSTNAME, OC_NODE) ))
  private[this] val ipCriteria = objectType.criteria.find(c => c.name == A_LIST_OF_IP).
    getOrElse(throw new IllegalArgumentException("Data model inconsistency: missing '%s' criterion in object type '%s'".format(A_LIST_OF_IP, OC_NODE) ))
    
  /*
   * search in database nodes having the same hostname as one provided.
   * Only return existing hostname (and so again, we want that to be empty)
   */
  private[this] def queryForDuplicateHostnameAndIp(hostnames:Seq[String], ips: Seq[String]) : Box[Unit] = {
    def failure(duplicates:Seq[String], name:String) = {
      Failure("There is already a node with %s %s in database. You can not add it again.".format(name, duplicates.mkString("'", "' or '", "'")))
    }
      
    val hostnameCriterion = hostnames.map { h =>
      CriterionLine(
          objectType = objectType
        , attribute  = hostnameCriteria
        , comparator = Equals
        , value      = h
      )
    }
    


    val ipCriterion = ips.map(ip =>
      CriterionLine(
          objectType = objectType
        , attribute  = ipCriteria
        , comparator = Equals
        , value      = ip
      )      
    )
    
    for {
      duplicatesH    <- queryProcessor.process(Query(NodeReturnType,Or,hostnameCriterion)).map { nodesInfo => 
                          //here, all nodes found are duplicate-in-being. They should be unique, but
                          //if not, we will don't group them that the duplicate appears in the list
                          nodesInfo.map( ni => ni.hostname)
                        } 
      noDuplicatesH  <- if(duplicatesH.isEmpty) Full({})
                        else failure(duplicatesH, "Hostname")
      duplicatesIP   <- queryProcessor.process(Query(NodeReturnType,Or,ipCriterion)).map { nodesInfo => 
                          nodesInfo.map( ni => ni.ips.filter(ip1 => ips.exists(ip2 => ip2 == ip1))).flatten
                        } 
      noDuplicatesIP <- if(duplicatesIP.isEmpty) Full({})
                        else failure(duplicatesIP , "IP")
    } yield {
      {}
    }
  }
  
  
  override def preAccept(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Box[Seq[FullInventory]] = {
    
    val hostnames = sms.map( _.node.main.hostname)
    val ips = sms.map( _.node.serverIps).flatten.filter( InetAddressUtils.getAddressByName(_) match {
      case None => false
      //we don't want to check for duplicate loopback and the like - we expect them to be duplicated
      case Some(ip) => !(ip.isAnyLocalAddress || ip.isLoopbackAddress || ip.isMulticastAddress )
    })
    
    for {
      noDuplicateHostnames <- checkDuplicateString(hostnames, "hostname")
      noDuplicateIPs       <- checkDuplicateString(ips, "IP")
      noDuplicateInDB      <- queryForDuplicateHostnameAndIp(hostnames,ips)
    } yield {
      sms
    }    
  }
  
  override val fromInventoryStatus = inventoryStatus
  
  override val toInventoryStatus = inventoryStatus  
    
  /**
   * Only add the server to the list of children of the policy server
   */
  def acceptOne(sm:FullInventory, modId: ModificationId, actor:EventActor) : Box[FullInventory] = Full(sm)
  
  /**
   * An action to execute after the whole batch 
   */
  def postAccept(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Box[Seq[FullInventory]] = Full(sms)

  /**
   * Execute a rollback for the given inventory
   */
  def rollback(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Unit = {}
}

/**
 * A unit acceptor in charge to historize the 
 * state of the Inventory so that we can keep it
 * forever. 
 * That acceptor should be call before node
 * is actually deleted or accepted
 */
class HistorizeNodeStateOnChoice(
    override val name: String
  , repos            : ReadOnlyFullInventoryRepository
  , historyRepos     : InventoryHistoryLogRepository
  , inventoryStatus  : InventoryStatus //expected inventory status of nodes for that processor
) extends UnitAcceptInventory with UnitRefuseInventory with Loggable {
  
  override def preAccept(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Box[Seq[FullInventory]] = Full(sms) //nothing to do

  override def postAccept(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Box[Seq[FullInventory]] = Full(sms) //nothing to do

  override val fromInventoryStatus = inventoryStatus
  
  override val toInventoryStatus = inventoryStatus
  
  /**
   * Add a node entry in ou=Nodes
   */
  def acceptOne(sm:FullInventory, modId: ModificationId, actor:EventActor) : Box[FullInventory] = {
    historyRepos.save(sm.node.main.id, sm).map( _ => sm)
  }
  
  /**
   * Does nothing - we don't have the "id" of the historized
   * inventory to remove
   */
  def rollback(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Unit = {}
  
  //////////// refuse //////////// 
  override def refuseOne(srv:Srv, modId: ModificationId, actor:EventActor) : Box[Srv] = {
    //refuse ou=nodes: delete it
    for {
      full <- repos.get(srv.id, inventoryStatus)
      _    <- historyRepos.save(srv.id, full)
    } yield srv
  }
}
