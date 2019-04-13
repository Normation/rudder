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

package com.normation.rudder.services.servers

import java.lang.IllegalArgumentException

import org.joda.time.DateTime
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import net.liftweb.common.Box
import net.liftweb.common.Failure
import net.liftweb.common.Empty
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.RoLDAPConnection
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.ldap.sdk.BuildFilter.ALL
import com.normation.inventory.domain.InventoryStatus
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.PendingInventory
import com.normation.inventory.domain.FullInventory
import com.normation.inventory.domain.AcceptedInventory
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.ldap.core.InventoryHistoryLogRepository
import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.inventory.ldap.core.LDAPFullInventoryRepository
import com.normation.inventory.services.core.ReadOnlyFullInventoryRepository
import com.normation.rudder.batch.UpdateDynamicGroups
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.domain.NodeDit
import com.normation.rudder.domain.servers.Srv
import com.normation.rudder.domain.queries.NodeReturnType
import com.normation.rudder.domain.queries.CriterionLine
import com.normation.rudder.domain.queries.DitQueryData
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.domain.queries.Or
import com.normation.rudder.domain.queries.Equals
import com.normation.rudder.domain.eventlog.RefuseNodeEventLog
import com.normation.rudder.domain.eventlog.AcceptNodeEventLog
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.CachedRepository
import com.normation.rudder.repository.WoNodeGroupRepository
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.rudder.reports.ReportingConfiguration
import com.normation.rudder.services.queries.QueryProcessor
import com.normation.utils.Control.sequence
import com.normation.utils.Control.bestEffort
import com.normation.rudder.hooks.RunHooks
import com.normation.rudder.hooks.HookEnvPairs
import com.normation.rudder.domain.eventlog.InventoryLogDetails
import com.normation.rudder.domain.logger.NodeLogger
import com.normation.rudder.hooks.HooksLogger
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.PolicyMode
import scalaz.zio._
import scalaz.zio.syntax._
import com.normation.box._
import com.normation.errors.IOResult

/**
 * A newNodeManager hook is a class that accept callbacks.
 */
trait NewNodeManagerHooks {

  /*
   * Hooks to call after the node is accepted.
   * These hooks are async and we don't wait for
   * their result. They are responsible to log
   * their errors.
   */
  def afterNodeAcceptedAsync(nodeId: NodeId): Unit

}

/**
 * A trait to manage the acceptation of new node in Rudder
 */
trait NewNodeManager extends NewNodeManagerHooks {

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

  def appendPostAcceptCodeHook(hook: NewNodeManagerHooks): Unit
}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

/*
 * Logic to execute scripts in hooks.d/node-post-acceptance
 */
class PostNodeAcceptanceHookScripts(
    HOOKS_D              : String
  , HOOKS_IGNORE_SUFFIXES: List[String]
  , nodeInfoService      : NodeInfoService
) extends NewNodeManagerHooks {

  override def afterNodeAcceptedAsync(nodeId: NodeId): Unit = {
    val systemEnv = {
      import scala.collection.JavaConverters._
      HookEnvPairs.build(System.getenv.asScala.toSeq:_*)
    }

    val postHooksTime =  System.currentTimeMillis
    HooksLogger.info(s"Executing post-node-acceptance hooks for node with id '${nodeId.value}'")
    for {
      optNodeInfo   <- nodeInfoService.getNodeInfo(nodeId)
      nodeInfo      <- optNodeInfo match {
                         case None    => Failure(s"Just accepted node with id '${nodeId.value}' was not found - perhaps a bug?"+
                                                  " Please report with /var/log/rudder/webapp/DATE_OF_DAY.stdout.log file attached")
                         case Some(x) => Full(x)
                       }
      hookEnv       =  HookEnvPairs.build(
                           ("RUDDER_NODE_ID"              , nodeInfo.id.value)
                         , ("RUDDER_NODE_HOSTNAME"        , nodeInfo.hostname)
                         , ("RUDDER_NODE_POLICY_SERVER_ID",  nodeInfo.policyServerId.value)
                         , ("RUDDER_AGENT_TYPE"           ,  nodeInfo.agentsName.headOption.map(_.agentType.id).getOrElse(""))
                       )
      postHooks     <- RunHooks.getHooks(HOOKS_D + "/node-post-acceptance", HOOKS_IGNORE_SUFFIXES)
      runPostHook   =  RunHooks.syncRun(postHooks, hookEnv, systemEnv)
      timePostHooks =  (System.currentTimeMillis - postHooksTime)
      _             =  NodeLogger.PendingNode.debug(s"Node-post-acceptance scripts hooks ran in ${timePostHooks} ms")
    } yield {
      ()
    }
  }
}


/**
 * Default implementation: a new server manager composed with a sequence of
 * "unit" accept, one by main goals of what it means to accept a server;
 * Each unit accept provides its main logic of accepting a server, optionally
 * a global post accept task, and a rollback mechanism.
 * Rollback is always a "best effort" task.
 */
class NewNodeManagerImpl(
    override val ldap                 : LDAPConnectionProvider[RoLDAPConnection]
  , override val pendingNodesDit      : InventoryDit
  , override val acceptedNodesDit     : InventoryDit
  , override val serverSummaryService : NodeSummaryServiceImpl
  , override val smRepo               : LDAPFullInventoryRepository
  , override val unitAcceptors        : Seq[UnitAcceptInventory]
  , override val unitRefusors         : Seq[UnitRefuseInventory]
  ,          val historyLogRepository : InventoryHistoryLogRepository
  ,          val eventLogRepository   : EventLogRepository
  , override val updateDynamicGroups  : UpdateDynamicGroups
  ,          val cacheToClear         : List[CachedRepository]
  ,              nodeInfoService      : NodeInfoService
  ,              HOOKS_D              : String
  ,              HOOKS_IGNORE_SUFFIXES: List[String]
) extends NewNodeManager with ListNewNode with ComposedNewNodeManager with NewNodeManagerHooks {

  private[this] val codeHooks = collection.mutable.Buffer[NewNodeManagerHooks]()

  //by default, add the script hooks
  appendPostAcceptCodeHook(new PostNodeAcceptanceHookScripts(HOOKS_D, HOOKS_IGNORE_SUFFIXES, nodeInfoService))

  override def afterNodeAcceptedAsync(nodeId: NodeId): Unit = {
    codeHooks.foreach( _.afterNodeAcceptedAsync(nodeId) )
  }

  def appendPostAcceptCodeHook(hook: NewNodeManagerHooks): Unit = {
    this.codeHooks.append(hook)
  }

}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

trait ListNewNode extends NewNodeManager {
  def ldap:LDAPConnectionProvider[RoLDAPConnection]
  def serverSummaryService:NodeSummaryServiceImpl
  def pendingNodesDit:InventoryDit

  override def listNewNodes : Box[Seq[Srv]] = {
    for {
      con  <- ldap
      seq  <- con.searchOne(pendingNodesDit.NODES.dn,ALL,Srv.ldapAttributes:_*)
      srvs <- ZIO.foreach(seq) { e => serverSummaryService.makeSrv(e).foldM(
                err =>
                  IOResult.effect(NodeLogger.PendingNode.debug(s"Error when mapping a pending node entry '${e.dn}' to a node object. Error was: ${err.fullMsg}")) *>
                  None.succeed
              , srv => Some(srv).succeed
              ) }
    } yield {
      srvs.flatten
    }
  }.toBox
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

trait ComposedNewNodeManager extends NewNodeManager with NewNodeManagerHooks {

  def ldap:LDAPConnectionProvider[RoLDAPConnection]
  def pendingNodesDit:InventoryDit
  def acceptedNodesDit:InventoryDit
  def smRepo:LDAPFullInventoryRepository
  def serverSummaryService:NodeSummaryService
  def unitAcceptors:Seq[UnitAcceptInventory]
  def unitRefusors:Seq[UnitRefuseInventory]

  def historyLogRepository : InventoryHistoryLogRepository
  def eventLogRepository : EventLogRepository

  def updateDynamicGroups : UpdateDynamicGroups

  def cacheToClear: List[CachedRepository]

  ////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////////////// Refuse //////////////////////////////////////
  ////////////////////////////////////////////////////////////////////////////////////

  /**
   * Retrieve the last inventory for the selected server
   */
  def retrieveLastVersions(nodeId : NodeId) : Option[DateTime] = {
      historyLogRepository.versions(nodeId).toBox.flatMap(_.headOption)
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
            NodeLogger.PendingNode.trace("Refuse %s: step %s ok".format(srv.id, refusor.name))
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

              eventLogRepository.saveEventLog(modId, entry).toBox match {
                case Full(_) =>
                  NodeLogger.PendingNode.debug(s"Successfully refused node '${id.value}'")
                case _ =>
                  NodeLogger.PendingNode.warn(s"Node '${id.value}' refused, but the action couldn't be logged")
              }
            case None =>
              NodeLogger.PendingNode.warn(s"Node '${id}' refused, but couldn't find it's inventory")
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
    NodeLogger.PendingNode.debug("\n*****************************************************\nRollbacking\n*****************************************************")

    for{
      toRollback <- unitAcceptors.reverse
    } {
      NodeLogger.PendingNode.debug("Rollbacking %s for %s".format(toRollback.name, rollbackOn.map( _.node.main.id.value).mkString("; ")))
      try {
        toRollback.rollback(rollbackOn, modId, actor)
      } catch {
        case e:Exception => NodeLogger.PendingNode.error("Error when rollbacking acceptor process '%s'".format(toRollback.name))
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
          NodeLogger.PendingNode.debug("Exception in unit acceptor %s".format(unitAcceptor.name),e)
          Failure(e.getMessage, Full(e), Empty)
        }
      }
    }) match {
      case Full(seq) => Full(sm)
      case e:EmptyBox => //rollback that one
        NodeLogger.PendingNode.error((e ?~! "Error when trying to accept node %s. Rollbacking.".format(sm.node.main.id.value)).messageChain)
        rollback(unitAcceptors, Seq(sm), modId, actor)
        e
    }
  }

  override def accept(id: NodeId, modId: ModificationId, actor:EventActor) : Box[FullInventory] = {
    //
    // start by retrieving all sms
    //
    val sm = smRepo.get(id, PendingInventory).toBox match {
      case Full(Some(x)) => x
      case Full(None) =>
        return Failure(s"Can not accept not found inventory with id: '${id.value}'")
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
          NodeLogger.PendingNode.debug("Pre accepted phase: %s".format(unitAcceptor.name))
        case eb:EmptyBox => //on an error here, stop
          val id = sm.node.main.id.value
          val msg = s"Error when trying to add node with id '${id}'"
          val e = eb ?~! msg
          NodeLogger.PendingNode.error(e.messageChain)
          NodeLogger.PendingNode.debug(s"Node with id '${id}' was refused during 'pre-accepting' step of phase '${unitAcceptor.name}'")
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
      case Full(sm) => NodeLogger.PendingNode.debug("Unit acceptors ok for %s".format(id))
      case eb:EmptyBox =>
        val e = eb ?~! "Unit acceptor error for node %s".format(id)
        NodeLogger.PendingNode.error(e.messageChain)
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
        // Update hooks for the node
        afterNodeAcceptedAsync(id)

        NodeLogger.PendingNode.info(s"New node accepted and managed by Rudder: ${id.value}")
        cacheToClear.foreach { _.clearCache }

        // Update hooks for the node - need to be done after cache cleaning
        afterNodeAcceptedAsync(id)

        updateDynamicGroups.startManualUpdate
        acceptationResults
      case e:EmptyBox => //on an error here, rollback all accpeted
        NodeLogger.PendingNode.error((e ?~! "Error when trying to execute accepting new server post-processing. Rollback.").messageChain)
        rollback(unitAcceptors, Seq(sm), modId, actor)
        //only update results that where not already in error
        e
    }
  }

  override def accept(ids: Seq[NodeId], modId: ModificationId, actor:EventActor, actorIp : String) : Box[Seq[FullInventory]] = {

    // Get inventory from a nodeId
    def getInventory(nodeId: NodeId) = {
      smRepo.get(nodeId, PendingInventory).toBox match {
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
              NodeLogger.PendingNode.debug(s"Pre acceptance phase: '${unitAcceptor.name}' OK")
              Full(seq)
            case eb:EmptyBox => //on an error here, stop
              val id = inventory.node.main.id.value
              val msg = s"Error when trying to add node with id '${id}'"
              val e = eb ?~! msg
              NodeLogger.PendingNode.error(e.messageChain)
              NodeLogger.PendingNode.debug(s"Node with id '${id}' was refused during 'pre-accepting' step of phase '${unitAcceptor.name}'")
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
              NodeLogger.PendingNode.debug(s"Post acceptance phase: '${unitAcceptor.name}' OK")
              Full(seq)
            case eb:EmptyBox => //on an error here, rollback
              val msg = s"Error when trying to execute post-accepting for phase '${unitAcceptor.name}. Rollback."
              val e = eb ?~! msg
              NodeLogger.PendingNode.error(e.messageChain)
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
         inventory <- getInventory(id).flatMap {
                        case None    => Failure(s"Missing inventory for node with ID: '${id.value}'")
                        case Some(i) => Full(i)
                      }
         // Pre accept it
         preAccept <- passPreAccept(inventory)
         // Accept it
         acceptationResults <- acceptOne(inventory, modId, actor) ?~! s"Error when trying to accept node ${id.value}"
         log = NodeLogger.PendingNode.debug(s"Unit acceptors ok for '${id.value}'")
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

             eventLogRepository.saveEventLog(modId, entry).toBox match {
               case Full(_) =>
                 NodeLogger.PendingNode.debug(s"Successfully accepted node '${id.value}'")
               case _ =>
                 NodeLogger.PendingNode.warn(s"Node '${id.value}' accepted, but the action couldn't be logged")
             }

           case None =>
             NodeLogger.PendingNode.warn(s"Node '${id.value}' accepted, but couldn't find it's inventory")
         }

         // Update hooks for the node
         afterNodeAcceptedAsync(id)

         acceptationResults
       }
    )

    // If one node succeed, then update policies
    if ( acceptanceResults.exists{ case Full(_) => true; case _ => false } ) {
      updateDynamicGroups.startManualUpdate
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
) extends UnitAcceptInventory with UnitRefuseInventory {

  override def preAccept(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Box[Seq[FullInventory]] = Full(sms) //nothing to do

  override def postAccept(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Box[Seq[FullInventory]] = Full(sms) //nothing to do

  override val fromInventoryStatus = PendingInventory

  override val toInventoryStatus = AcceptedInventory

  def acceptOne(sm:FullInventory, modId: ModificationId, actor:EventActor) : Box[FullInventory] = {
    smRepo.move(sm.node.main.id, fromInventoryStatus, toInventoryStatus).toBox.map { _ => sm }
  }

  def rollback(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Unit  = {
    sms.foreach { sm =>
      //rollback from accepted
      (for {
        result <- smRepo.move(sm.node.main.id, toInventoryStatus, fromInventoryStatus).toBox
      } yield {
        result
      }) match {
        case e:EmptyBox => NodeLogger.PendingNode.error(rollbackErrorMsg(e, sm.node.main.id.value))
        case Full(f) => NodeLogger.PendingNode.debug("Succesfully rollbacked %s for process '%s'".format(sm.node.main.id, this.name))
      }
    }
  }

  //////////// refuse ////////////
  override def refuseOne(srv:Srv, modId: ModificationId, actor:EventActor) : Box[Srv] = {
    //refuse an inventory: delete it
    smRepo.delete(srv.id, fromInventoryStatus).toBox.map( _ => srv)
  }

}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

/**
 * Accept FullInventory at ou=node level: just add it
 */
class AcceptFullInventoryInNodeOu(
    override val name             : String
  ,              nodeDit          : NodeDit
  ,              ldap             : LDAPConnectionProvider[RwLDAPConnection]
  ,              ldapEntityMapper : LDAPEntityMapper
  ,              inventoryStatus  : InventoryStatus //expected inventory status of nodes for that processor
  ,              defaultPolicyMode: () => Box[Option[PolicyMode]]
  ,              defaultNodeState : () => Box[NodeState]
) extends UnitAcceptInventory with UnitRefuseInventory {

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

    //naive test to find if the node is the master policy server.
    //TODO: that can not handle relay server
    val isPolicyServer = sm.node.main.id == sm.node.main.policyServerId

    val node = Node(
        sm.node.main.id
      , name
      , description
      , defaultNodeState().openOr(NodeState.Enabled)
      , false
      , isPolicyServer
      , DateTime.now // won't be used on save - dummy value
      , ReportingConfiguration(None,None) // use global schedule
      , Seq() //no user properties for now
      , defaultPolicyMode().openOr(None)
    )

    val entry = ldapEntityMapper.nodeToEntry(node)
    for {
      con <- ldap
      res <- con.save(entry).chainError(s"Error when trying to save node '${entry.dn}' in process '${this.name}'")
    } yield {
      sm
    }
  }.toBox

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
      }).toBox match {
        case e:EmptyBox => NodeLogger.PendingNode.error(rollbackErrorMsg(e, sm.node.main.id.value))
        case Full(f) => NodeLogger.PendingNode.debug("Succesfully rollbacked %s for process '%s'".format(sm.node.main.id, this.name))
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
  }.toBox

}

class RefuseGroups(
    override val name:String
  , roGroupRepo: RoNodeGroupRepository
  , woGroupRepo: WoNodeGroupRepository
) extends UnitRefuseInventory {

  //////////// refuse ////////////
  override def refuseOne(srv:Srv, modId: ModificationId, actor:EventActor): Box[Srv] = {
    //remove server id in all groups
    for {
      groupIds       <- roGroupRepo.findGroupWithAnyMember(Seq(srv.id))
      modifiedGroups <- ZIO.foreach(groupIds) { groupId =>
                          for {
                            groupPair  <- roGroupRepo.getNodeGroup(groupId)
                            modGroup   =  groupPair._1.copy( serverList = groupPair._1.serverList - srv.id)
                            msg        =  Some("Automatic update of groups due to refusal of node "+ srv.id.value)
                            saved      <- {
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
  }.toBox
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

    val hostnameCriterion = hostnames.toList.map { h =>
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
      authorizedNetworks   <- policyServerNet.getAuthorizedNetworks(Constants.ROOT_POLICY_SERVER_ID) ?~! "Can not get authorized networks: check their configuration, and that rudder-init was done"
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
) extends UnitAcceptInventory with UnitRefuseInventory {

  override def preAccept(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Box[Seq[FullInventory]] = Full(sms) //nothing to do

  override def postAccept(sms:Seq[FullInventory], modId: ModificationId, actor:EventActor) : Box[Seq[FullInventory]] = Full(sms) //nothing to do

  override val fromInventoryStatus = inventoryStatus

  override val toInventoryStatus = inventoryStatus

  /**
   * Add a node entry in ou=Nodes
   */
  def acceptOne(sm:FullInventory, modId: ModificationId, actor:EventActor) : Box[FullInventory] = {
    historyRepos.save(sm.node.main.id, sm).toBox.map( _ => sm)
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
      _    <- full match {
                case None      => "ok".succeed
                case Some(inv) => historyRepos.save(srv.id, inv)
              }
    } yield srv
  }.toBox
}
