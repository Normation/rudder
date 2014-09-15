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
import com.normation.rudder.domain.servers.Srv
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
import java.net.InetAddress
import org.apache.commons.net.util.SubnetUtils
import com.normation.rudder.domain.eventlog._

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
  def accept(id: NodeId, modId: ModificationId, actor:EventActor) : Box[FullInventory]

  /**
   * refuse node
   * @param ids : the node id
   * @return : the srv representations of the refused node
   */
  def refuse(id: NodeId, modId: ModificationId, actor:EventActor) : Box[Srv]

  /**
   * Accept a list of pending nodes in Rudder
   */
  def accept(ids: Seq[NodeId], modId: ModificationId, actor:EventActor, actorIp : String) : Box[Seq[FullInventory]]

  /**
   * refuse a list of pending nodes
   * @param ids : node ids
   * @return : the srv representations of the refused nodes
   */
  def refuse(id: Seq[NodeId], modId: ModificationId, actor:EventActor, actorIp : String) : Box[Seq[Srv]]

}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////


/**
 * Default implementation: a new server manager composed with a sequence of
 * "unit" accept, one by main goals of what it means to accept a server;
 * Each unit accept provides its main logic of accepting a server, optionally
 * a global post accept task, and a rollback mechanism.
 * Rollback is always a "best effort" task.
 */
class NewNodeManagerImpl(
    override val ldap:LDAPConnectionProvider[RoLDAPConnection],
    override val pendingNodesDit:InventoryDit,
    override val acceptedNodesDit:InventoryDit,
    override val serverSummaryService:NodeSummaryServiceImpl,
    override val smRepo:LDAPFullInventoryRepository,
    override val unitAcceptors:Seq[UnitAcceptInventory],
    override val unitRefusors:Seq[UnitRefuseInventory]
  , val inventoryHistoryLogRepository : InventoryHistoryLogRepository
  , val eventLogRepository : EventLogRepository
  , val asyncDeploymentAgent : AsyncDeploymentAgent
) extends NewNodeManager with ListNewNode with ComposedNewNodeManager


///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////


trait ListNewNode extends NewNodeManager with Loggable {
  def ldap:LDAPConnectionProvider[RoLDAPConnection]
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

  def ldap:LDAPConnectionProvider[RoLDAPConnection]
  def pendingNodesDit:InventoryDit
  def acceptedNodesDit:InventoryDit
  def smRepo:LDAPFullInventoryRepository
  def serverSummaryService:NodeSummaryService
  def unitAcceptors:Seq[UnitAcceptInventory]
  def unitRefusors:Seq[UnitRefuseInventory]


  def inventoryHistoryLogRepository : InventoryHistoryLogRepository
  def eventLogRepository : EventLogRepository

  def asyncDeploymentAgent : AsyncDeploymentAgent

  ////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////////////// Refuse //////////////////////////////////////
  ////////////////////////////////////////////////////////////////////////////////////

  /**
   * Retrieve the last inventory for the selected server
   */
  def retrieveLastVersions(nodeId : NodeId) : Option[DateTime] = {
      inventoryHistoryLogRepository.versions(nodeId).flatMap(_.headOption)
  }

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

  override def refuse(id: NodeId, modId: ModificationId, actor:EventActor) : Box[Srv] = {
      for {
        srvs   <- serverSummaryService.find(pendingNodesDit, id)
        srv    <- if(srvs.size == 1) Full(srvs(0)) else Failure("Found several pending nodes matchin id %s: %s".format(id.value, srvs))
        refuse <- refuseOne(srv, modId, actor)
      } yield {
        refuse
    }
  }

  override def refuse(ids: Seq[NodeId], modId: ModificationId, actor:EventActor, actorIp : String) : Box[Seq[Srv]] = {

    // Best effort it, starting with an empty result
    val start : Box[Seq[Srv]] = Full(Seq())
    ( ids :\ start ) {
      case (id, result) =>

        // Refuse the node and get the result
        val refusal = for {
          srvs   <- serverSummaryService.find(pendingNodesDit, id)
          // I don't think this is possible, either we have one, either we don't have any
          srv    <- if (srvs.size == 1) {
                      Full(srvs.head)
                    } else {
                      Failure(s"Found ${srvs.size} pending nodes matching id ${id.value}: ${srvs.mkString(", ")}")
                    }
          refuse <- refuseOne(srv, modId, actor)
        } yield {

          // Make an event log of the refusale
          retrieveLastVersions(srv.id) match {
            case Some(x) =>
              val inventoryDetails = InventoryLogDetails (
                  nodeId           = srv.id
                , inventoryVersion = x
                , hostname         = srv.hostname
                , fullOsName       = srv.osFullName
                , actorIp          = actorIp
              )
              val entry = RefuseNodeEventLog.fromInventoryLogDetails(
                  principal        = actor
                , inventoryDetails = inventoryDetails
              )

              eventLogRepository.saveEventLog(modId, entry) match {
                case Full(_) =>
                  logger.debug(s"Successfully refused node '${id.value}'")
                case _ =>
                  logger.warn(s"Node '${id.value}' refused, but the action couldn't be logged")
              }
            case None =>
              logger.warn(s"Node '${id}' refused, but couldn't find it's inventory")
          }
          refuse
        }

        // accumulate result
        (refusal,result) match {
          // Node deleted, and result ok, accumulate success
          case (Full(srv), Full(seq)) =>
            Full(srv +: seq)
          // Node deleted, but there was an error before, keep error
          case (Full(_), error) =>
            error
          // An error while deleting, and there was none, create a new error
          case (eb:EmptyBox, Full(_)) =>
            eb
          // A new error while deleting, and there was already one, accumulate error
          case (eb:EmptyBox, result:EmptyBox) =>
            // That message will not be used, since we have 'Failure' and no 'Empty'
            val error = eb ?~ "An error occured while refusing a Node"
            result ?~! error.messageChain
        }
    }
  }

  ////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////////////// Accept //////////////////////////////////////
  ////////////////////////////////////////////////////////////////////////////////////

  private[this] def rollback(unitAcceptors:Seq[UnitAcceptInventory], rollbackOn:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Unit = {
    logger.debug("\n*****************************************************\nRollbacking\n*****************************************************")

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
        logger.error((e ?~! "Error when trying to accept node %s. Rollbacking.".format(sm.node.main.id.value)).messageChain)
        rollback(unitAcceptors, Seq(sm), modId, actor)
        e
    }
  }


  override def accept(id: NodeId, modId: ModificationId, actor:EventActor) : Box[FullInventory] = {
    //
    // start by retrieving all sms
    //
    val sm = smRepo.get(id, PendingInventory) match {
      case Full(x) => x
      case eb: EmptyBox =>
        return eb ?~! "Can not accept not found inventory with id %s".format(id)
      }

    //
    //execute pre-accept phase for all unit acceptor
    // stop here in case of any error
    //
    unitAcceptors.foreach { unitAcceptor =>
      unitAcceptor.preAccept(Seq(sm), modId, actor) match {
        case Full(seq) => //ok, cool
          logger.debug("Pre accepted phase: %s".format(unitAcceptor.name))
        case eb:EmptyBox => //on an error here, stop
          val e = eb ?~! "Error when trying to execute pre-accepting for phase %s. Stop.".format(unitAcceptor.name)
          logger.error(e.messageChain)
          //stop now
          return e
      }
    }


    //
    //now, execute unit acceptor
    //

    //build the map of results
    val acceptationResults = acceptOne(sm, modId, actor) ?~! "Error when trying to accept node %s".format(sm.node.main.id.value)

    //log
    acceptationResults match {
      case Full(sm) => logger.debug("Unit acceptors ok for %s".format(id))
      case eb:EmptyBox =>
        val e = eb ?~! "Unit acceptor error for node %s".format(id)
        logger.error(e.messageChain)
        return eb
    }


    //
    //now, execute global post process
    //
    (sequence(unitAcceptors) { unit =>
      try {
        unit.postAccept(Seq(sm), modId, actor)
      } catch {
        case e:Exception => Failure(e.getMessage, Full(e), Empty)
      }
    }) match {
      case Full(seq) => //ok, cool
        logger.debug("Accepted inventories: %s".format(sm.node.main.id.value))
        acceptationResults
      case e:EmptyBox => //on an error here, rollback all accpeted
        logger.error((e ?~! "Error when trying to execute accepting new server post-processing. Rollback.").messageChain)
        rollback(unitAcceptors, Seq(sm), modId, actor)
        //only update results that where not already in error
        e
    }
  }


  override def accept(ids: Seq[NodeId], modId: ModificationId, actor:EventActor, actorIp : String) : Box[Seq[FullInventory]] = {

    // Get inventory from a nodeId
    def getInventory(nodeId: NodeId) = {
      smRepo.get(nodeId, PendingInventory) match {
      case Full(x) => Full(x)
      case eb: EmptyBox =>
        val msg = s"Can not accept not found inventory with id '${nodeId.value}'"
        eb ?~! msg
      }
    }

    // validate pre acceptance for a Node, if an error occurs, stop everything on that node.
    def passPreAccept (inventory  : FullInventory) = {
      sequence(unitAcceptors) (
        unitAcceptor =>
          unitAcceptor.preAccept(Seq(inventory), modId, actor) match {
            case Full(seq) => //ok, cool
              logger.debug(s"Pre acceptance phase: '${unitAcceptor.name}' OK")
              Full(seq)
            case eb:EmptyBox => //on an error here, stop
              val msg = s"Error when trying to execute pre-accepting for phase '${unitAcceptor.name}. Stop."
              val e = eb ?~! msg
              logger.error(e.messageChain)
              e
          }
      )
    }

    // validate post acceptance for a Node, if an error occurs, Rollback the node acceptance
    def passPostAccept (inventory  : FullInventory) = {
      sequence(unitAcceptors) (
        unitAcceptor =>
          unitAcceptor.postAccept(Seq(inventory), modId, actor) match {
            case Full(seq) => //ok, cool
              logger.debug(s"Post acceptance phase: '${unitAcceptor.name}' OK")
              Full(seq)
            case eb:EmptyBox => //on an error here, rollback
              val msg = s"Error when trying to execute post-accepting for phase '${unitAcceptor.name}. Rollback."
              val e = eb ?~! msg
              logger.error(e.messageChain)
              rollback(unitAcceptors, Seq(inventory), modId, actor)
              e
          }
      )
    }

    // Get all acceptance, we will not accumulate errors in that pass, so we can start a promise generation
    val acceptanceResults = ids.map (
     id =>
       for {
         // Get inventory og the node
         inventory <- getInventory(id)
         // Pre accept it
         preAccept <- passPreAccept(inventory)
         // Accept it
         acceptationResults <- acceptOne(inventory, modId, actor) ?~! s"Error when trying to accept node ${id.value}"
         log = logger.debug(s"Unit acceptors ok for '${id.value}'")
         // Post accept it
         postAccept <- passPostAccept(inventory)
       } yield {

         // Make an event log for acceptance
         retrieveLastVersions(id) match {
           case Some(x) =>
             val inventoryDetails = InventoryLogDetails (
                 nodeId           = id
               , inventoryVersion = x
               , hostname         = inventory.node.main.hostname
               , fullOsName       = inventory.node.main.osDetails.fullName
               , actorIp          = actorIp
             )
             val entry = AcceptNodeEventLog.fromInventoryLogDetails(
                 principal        = actor
               , inventoryDetails = inventoryDetails
             )

             eventLogRepository.saveEventLog(modId, entry) match {
               case Full(_) =>
                 logger.debug(s"Successfully accepted node '${id.value}'")
               case _ =>
                 logger.warn(s"Node '${id.value}' accepted, but the action couldn't be logged")
             }

           case None =>
             logger.warn(s"Node '${id.value}' accepted, but couldn't find it's inventory")
         }
         acceptationResults
       }
    )

    // If one node succeed, then update policies
    if ( acceptanceResults.exists{ case Full(_) => true; case _ => false } ) {
      asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)
    }

    // Transform the sequence of box into a boxed result, best effort it!
    val start : Box[Seq[FullInventory]] = Full(Seq())
    ( acceptanceResults :\ start ) {
      // Node accepted, and result ok, accumulate success
      case (Full(inv), Full(seq)) =>
        Full(inv +: seq)
      // Node accepted, but there was an error before, keep error
      case (Full(_), error) =>
        error
      // An error while accepting, and there was none, create a new error
      case (eb:EmptyBox, Full(_)) =>
        eb
      // A new error while accepting, and there was already one, accumulate error
      case (eb:EmptyBox, result:EmptyBox) =>
        // That message will not be used, since we have 'Failure' and no 'Empty'
        val error = eb ?~ "An error occured while refusing a Node"
        result ?~! error.messageChain
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
  ldap:LDAPConnectionProvider[RwLDAPConnection],
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
    roGroupRepo: RoNodeGroupRepository,
    woGroupRepo: WoNodeGroupRepository,
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
          (group, _) <- roGroupRepo.getNodeGroup(groupId) ?~! "Can not find group with id: %s".format(groupId)
          updatedGroup = group.copy( serverList = group.serverList + sm.node.main.id )
          msg = Some("Automatic update of system group due to acceptation of node "+ sm.node.main.id.value)
          saved <- {
                     val res = if(updatedGroup.isSystem) {
                       woGroupRepo.updateSystemGroup(updatedGroup, modId, actor, msg)
                     } else {
                       woGroupRepo.update(updatedGroup, modId, actor, msg)
                     }
                     res ?~! "Error when trying to update dynamic group %s with member %s".format(groupId,sm.node.main.id.value)
                   }
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
            (group, _) <- roGroupRepo.getNodeGroup(groupId) ?~! "Can not find group with id: %s".format(groupId)
            updatedGroup = group.copy( serverList = group.serverList.filter(x => x != sm.node.main.id ) )
            msg = Some("Automatic update of system group due to rollback of acceptation of node "+ sm.node.main.id.value)
            saved <- {
                       val res = if(updatedGroup.isSystem) {
                         woGroupRepo.updateSystemGroup(updatedGroup, modId, actor, msg)
                       } else {
                         woGroupRepo.update(updatedGroup, modId, actor, msg)
                       }
                       res ?~! "Error when trying to update dynamic group %s with member %s".format(groupId,sm.node.main.id.value)
                     }
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
    override val name:String
  , roGroupRepo: RoNodeGroupRepository
  , woGroupRepo: WoNodeGroupRepository
) extends UnitRefuseInventory with Loggable {

  //////////// refuse ////////////
  override def refuseOne(srv:Srv, modId: ModificationId, actor:EventActor) : Box[Srv] = {
    //remove server id in all groups
    for {
      groupIds <- roGroupRepo.findGroupWithAnyMember(Seq(srv.id))
      modifiedGroups <- bestEffort(groupIds) { groupId =>
        for {
          (group, _) <- roGroupRepo.getNodeGroup(groupId)
          modGroup = group.copy( serverList = group.serverList - srv.id)
          msg = Some("Automatic update of groups due to refusal of node "+ srv.id.value)
          saved <- {
                     val res = if(modGroup.isSystem) {
                       woGroupRepo.updateSystemGroup(modGroup, modId, actor, msg)
                     } else {
                       woGroupRepo.update(modGroup, modId, actor, msg)
                     }
                     res
                   }
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
    roGroupRepo: RoNodeGroupRepository,
    woGroupRepo: WoNodeGroupRepository,
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
      (group, _) <- roGroupRepo.getNodeGroup(hasPolicyServerNodeGroup) ?~! "Technical group with ID '%s' was not found".format(hasPolicyServerNodeGroup)
      updatedGroup = group.copy( serverList = group.serverList + nodeId )
      msg = Some("Automatic update of system group due to acceptation of node "+ sm.node.main.id.value)
      saved<- woGroupRepo.updateSystemGroup(updatedGroup, modId, actor, msg) ?~! "Could not update the technical group with ID '%s'".format(updatedGroup.id )
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
        (group, _) <- roGroupRepo.getNodeGroup(buildHasPolicyServerGroupId(sm.node.main.policyServerId)) ?~! "Can not find group with id: %s".format(sm.node.main.policyServerId)
        updatedGroup = group.copy( serverList = group.serverList.filter(x => x != sm.node.main.id ) )
        msg = Some("Automatic update of system group due to rollback of acceptation of node "+ sm.node.main.id.value)
        saved<- woGroupRepo.updateSystemGroup(updatedGroup, modId, actor, msg)?~! "Error when trying to update dynamic group %s with member %s".format(updatedGroup.id,sm.node.main.id.value)
      } yield {
        sm
      }) match {
        case Full(_) => //ok
        case e:EmptyBox => logger.error((e ?~! "Error when rollbacking server %s".format(sm.node.main.id.value)).messageChain)
      }
    }
  }

  override def postAccept(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Box[Seq[FullInventory]] = {
    //nothing, node configuration state will be handle by the deployment service
    Full(sms)
  }


  //////////// refuse ////////////
  override def refuseOne(srv:Srv, modId: ModificationId, actor:EventActor) : Box[Srv] = {
    //nothing, node configuration state will be handle by the deployment service
    Full(srv)
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
  , policyServerNet  : PolicyServerManagementService
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

  /*
   * search in database nodes having the same hostname as one provided.
   * Only return existing hostname (and so again, we want that to be empty)
   */
  private[this] def queryForDuplicateHostname(hostnames:Seq[String]) : Box[Unit] = {
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

    for {
      duplicatesH    <- queryProcessor.process(Query(NodeReturnType,Or,hostnameCriterion)).map { nodesInfo =>
                          //here, all nodes found are duplicate-in-being. They should be unique, but
                          //if not, we will don't group them that the duplicate appears in the list
                          nodesInfo.map( ni => ni.hostname)
                        }
      noDuplicatesH  <- if(duplicatesH.isEmpty) Full({})
                        else failure(duplicatesH, "Hostname")
    } yield {
      {}
    }
  }

  override def preAccept(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Box[Seq[FullInventory]] = {

    val hostnames = sms.map( _.node.main.hostname)

    for {
      authorizedNetworks   <- policyServerNet.getAuthorizedNetworks(Constants.ROOT_POLICY_SERVER_ID)
      noDuplicateHostnames <- checkDuplicateString(hostnames, "hostname")
      noDuplicateInDB      <- queryForDuplicateHostname(hostnames)
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
