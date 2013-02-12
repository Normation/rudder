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

import org.joda.time.DateTime
import com.normation.utils.Control.sequence
import com.normation.rudder.domain.nodes._
import scala.collection.mutable.ArrayBuffer
import net.liftweb.common.EmptyBox
import net.liftweb.common.EmptyBox
import com.normation.inventory.domain.NodeId
import com.normation.rudder.services.policies.targetNodeConfiguration
import com.normation.inventory.domain.AgentType
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.Rule
import com.normation.utils.User
import com.normation.eventlog.EventActor
import scala.collection._
import com.normation.rudder.domain.servers._
import com.normation.rudder.domain._
import com.normation.rudder.services.servers._
import org.slf4j.{Logger,LoggerFactory}
import com.normation.rudder._
import com.normation.rudder.exceptions._
import com.normation.rudder.services.policies._
import com.normation.rudder.repository._
import com.normation.utils.StringUuidGenerator
import java.nio.channels._
import java.io.File
import java.io.RandomAccessFile
import org.apache.commons.io.FileUtils
import scala.reflect.BeanProperty
import org.slf4j.{Logger,LoggerFactory}
import com.normation.cfclerk.services._
import net.liftweb.common._
import com.normation.cfclerk.domain._
import com.normation.rudder.domain.transporter._
import com.normation.rudder.domain.eventlog._
import com.normation.rudder.services.eventlog._
import com.normation.eventlog._
import com.normation.inventory.domain._
import com.normation.rudder.domain.policies.RuleWithCf3PolicyDraft
import com.normation.exceptions.TechnicalException

/**
 * Implementation of the Node Configuration service
 * It manages the NodeConfiguration content (the cache of the deployed conf)
 * @author Nicolas CHARLES
 *
 */
class NodeConfigurationServiceImpl(
    policyTranslator    : TemplateWriter,
    repository          : NodeConfigurationRepository,
    techniqueRepository : TechniqueRepository,
    lockFolder          : String
    ) extends NodeConfigurationService {

  val LOGGER = LoggerFactory.getLogger(classOf[NodeConfigurationServiceImpl])

  LOGGER.info("Creating lock folder at {}", lockFolder)
  createLockFolder()
  val fileLock = new File(lockFolder , "updateNodes.lock");
  val channel = new RandomAccessFile(fileLock, "rw").getChannel();
  var lock = channel.tryLock(); // we want an early failure
  lock.release();

  private def createLockFolder() {
    FileUtils.forceMkdir(new File(lockFolder))
  }

  private[this] def inLock[T]( block: => T ) : T = {
    try {
      lock = channel.tryLock();
      block
    } catch {
      // File is already locked in this thread or virtual server
      case ex : OverlappingFileLockException => LOGGER.debug("Concurrent acces for method %s".format(Thread.currentThread.getStackTrace()(0).getMethodName))
      throw ex
    } finally {
      // Release the lock
      lock.release();
    }
  }

  def findNode(nodeId: NodeId) : Option[NodeConfiguration] = {
    inLock {
      repository.findNodeConfiguration(nodeId) match {
        case Full(node) => Some(node)
        case Empty => None
        case f:Failure => throw new TechnicalException("Error when trying to retrieve node %s. Message was %s".format(nodeId,f.messageChain))
      }
    }
  }

  def getMultipleNodeConfigurations(ids : Seq[NodeId]) : Set[NodeConfiguration] = {
    inLock {
      repository.getMultipleNodeConfigurations(ids) match {
        case Full(nodes) => nodes
        case Empty => Set()
        case Failure(m,_,_) => throw new TechnicalException("Error when trying to retrieve node %s. Message was %s".format(ids,m))
      }
    }
  }

  /**
   * Find all servers
   * @return
   */
  def getAllNodeConfigurations() : Map[String, NodeConfiguration] = {
    inLock {
      repository.getAll() match {
        case Full(map) => map
        case Empty => Map()
        case Failure(m,_,_) => throw new TechnicalException("Error when trying to retrieve all nodes. Message was %s".format(m))
      }
    }
  }


  /**
   * Update a node configuration using a targetNodeConfiguration :
   * update the directives and the node context, as well as the agentsName
   * (well, every fields actually)
   * @param target
   * @return
   */
  def updateNodeConfiguration(target : targetNodeConfiguration) : Box[NodeConfiguration] = {
    //create a new node configuration based on "target" datas
    def createNodeConfiguration() : NodeConfiguration = {
      //we need to decide if it's a root node config or a simple one
      if(target.nodeInfo.isPolicyServer) {
          new RootNodeConfiguration(
            target.nodeInfo.id.value,
            Seq(),
            Seq(),
            true,
            // when creating a nodeconfiguration, there is no current
            currentMinimalNodeConfig = new MinimalNodeConfig(
                "", "", Seq(), "", ""
            ),
            targetMinimalNodeConfig = new MinimalNodeConfig(
                target.nodeInfo.name ,
                target.nodeInfo.hostname,
                target.nodeInfo.agentsName,
                target.nodeInfo.policyServerId.value,
                target.nodeInfo.localAdministratorAccountName
            ),
            None,
            Map(),
            target.nodeContext
          )
      } else {
          new SimpleNodeConfiguration(
            target.nodeInfo.id.value,
            Seq(),
            Seq(),
            false,
            // when creating a nodeconfiguration, there is no current
            currentMinimalNodeConfig = new MinimalNodeConfig(
                "", "", Seq(), "", ""
            ),
            targetMinimalNodeConfig = new MinimalNodeConfig(
                target.nodeInfo.name ,
                target.nodeInfo.hostname,
                target.nodeInfo.agentsName,
                target.nodeInfo.policyServerId.value,
                target.nodeInfo.localAdministratorAccountName
            ),
            None,
            Map(),
            target.nodeContext
          )
      }
    }

    inLock {
      LOGGER.debug("Updating node configuration %s".format(target.nodeInfo.id.value) )
      if (target.identifiableCFCPIs.size == 0) {
        LOGGER.warn("Cannot create server {} without policy", target.nodeInfo.id.value)
        return ParamFailure[Seq[RuleWithCf3PolicyDraft]]("Cannot create a server without policies", Full(new TechniqueException("Cannot create a server without any policies")), Empty, target.identifiableCFCPIs)
      }

      deduplicateUniqueDirectives(target.identifiableCFCPIs) match {
        case f : EmptyBox =>
          LOGGER.debug( (f ?~! "An error happened when finding unique directives").messageChain )
          LOGGER.error("Could not convert directive beans")
          f

        case Full(directives) =>

          /*
           * Try to find the node configuration. If none are found (or one is found but
           * mapping lead to an error, create a new one.
           */
          val nodeConfiguration = repository.findNodeConfiguration(target.nodeInfo.id) match {
            case f : Failure =>
              LOGGER.debug("An error occured when trying to fetch the node with id %s : %s".format(target.nodeInfo.id, f.messageChain))

              //try to delete the bad, bad node configuration
              LOGGER.debug("We are trying to erase the node configuration and create a new one")
              repository.deleteNodeConfiguration(target.nodeInfo.id.value) match {
                case f:Failure =>
                  LOGGER.error("Can not delete the inconsistent node configuration with id: {}. The error does not seems to be corrected automatically, please contact your administrator.",target.nodeInfo.id.value)
                  return f
                case _ => //ok
                  createNodeConfiguration()
              }

            case Empty => //create server
              createNodeConfiguration()
            case Full(node : SimpleNodeConfiguration) =>
              //update nodeconfiguration
              //server.id  = target.nodeInfo.id.value //not mutable TODO: use it in the constructor

              node.copy(
                  __targetRulePolicyDrafts = Seq[RuleWithCf3PolicyDraft](),
                  targetMinimalNodeConfig = new MinimalNodeConfig(
                      target.nodeInfo.name ,
                      target.nodeInfo.hostname ,
                      target.nodeInfo.agentsName ,
                      target.nodeInfo.policyServerId.value ,
                      target.nodeInfo.localAdministratorAccountName
                      ),
                  targetSystemVariables = target.nodeContext
              )


            case Full(rootServer : RootNodeConfiguration) =>
              rootServer.copy(
                  __targetRulePolicyDrafts = Seq[RuleWithCf3PolicyDraft](),
                  targetMinimalNodeConfig = new MinimalNodeConfig(
                      target.nodeInfo.name ,
                      target.nodeInfo.hostname ,
                      target.nodeInfo.agentsName ,
                      target.nodeInfo.policyServerId.value ,
                      target.nodeInfo.localAdministratorAccountName
                      ),
                  targetSystemVariables = target.nodeContext
               )

            case Full(otherNodeType:NodeConfiguration) =>
              LOGGER.error("Found a not supported nodeConfiguration type, neither simple nor root one: ", otherNodeType)
              otherNodeType

          }

          for {
            addDirectivesOK          <- addDirectives(nodeConfiguration, directives) ?~! "Error when adding policies for node configuration %s".format(target.nodeInfo.id.value)
            nodeConfigurationSaved <- repository.saveNodeConfiguration(addDirectivesOK) ?~! "Error when saving node configuration %s".format(target.nodeInfo.id.value)
          } yield {
            nodeConfigurationSaved
          }
      }
    }
  }

  /**
   * From the list of updated rules (is it what we need) ? and the list of ALL NODES (caution, we must have 'em all)
   * update the serials, and save them
   */
  def incrementSerials(rules: Seq[(RuleId,Int)], nodes : Seq[NodeConfiguration]) : Box[Seq[NodeConfiguration]] = {
      repository.saveMultipleNodeConfigurations(nodes.map(x => x.setSerial(rules)))
  }


  /**
   * Create a node configuration from a target.
   * Hence, it will have empty current configuration
   * @param target
   * @return
   */
  def addNodeConfiguration(target : targetNodeConfiguration) : Box[NodeConfiguration] = {
    inLock {
      if (target.identifiableCFCPIs.size == 0) {
        LOGGER.warn("Cannot create server {} without policy", target.nodeInfo.id.value)
        return ParamFailure[Seq[RuleWithCf3PolicyDraft]]("Cannot create a server without policies", Full(new TechniqueException("Cannot create a server without any policies")), Empty, target.identifiableCFCPIs)
      }

      val node = if(target.nodeInfo.isPolicyServer) {
          new RootNodeConfiguration(
            target.nodeInfo.id.value,
            Seq(),
            Seq(),
            isPolicyServer = true,
            new MinimalNodeConfig("", "", Seq(), "", ""),
            new MinimalNodeConfig(
                target.nodeInfo.name,
                target.nodeInfo.hostname,
                target.nodeInfo.agentsName,
                target.nodeInfo.policyServerId.value,
                target.nodeInfo.localAdministratorAccountName
                ),
            None,
            Map(),
            target.nodeContext)
      } else {
          new SimpleNodeConfiguration(
            target.nodeInfo.id.value,
            Seq(),
            Seq(),
            isPolicyServer = false,
            new MinimalNodeConfig("", "", Seq(), "", ""),
            new MinimalNodeConfig(
                target.nodeInfo.name,
                target.nodeInfo.hostname,
                target.nodeInfo.agentsName,
                target.nodeInfo.policyServerId.value,
                target.nodeInfo.localAdministratorAccountName
                ),
            None,
            Map(),
            target.nodeContext)
      }



      deduplicateUniqueDirectives(target.identifiableCFCPIs) match {
        case f : EmptyBox => f
        case Full(directives) =>
            addDirectives(node, directives) match {
              case f: EmptyBox => return f
              case Full(updatedNode) =>
              for {
                saved <- repository.saveNodeConfiguration(updatedNode)
                findBack <- repository.findNodeConfiguration(NodeId(updatedNode.id))
              } yield findBack
            }
      }
    }
  }


  /**
   * Delete a node by its id
   */
  def deleteNodeConfiguration(nodeId:String) :  Box[Unit] = {
    inLock {
      val node = repository.findNodeConfiguration(NodeId(nodeId))
      if (node == None) {
        LOGGER.debug("Could not delete not found node {}", nodeId)
        return Full(())
      }

      //TODO: nothing is done with update batch ?
      val updateBatch = new UpdateBatch
      updateBatch.addNodeConfiguration(node.get)

      repository.deleteNodeConfiguration(nodeId)
      Full(())
    }
  }

  /**
   * delete all node configuration
   */
  def deleteAllNodeConfigurations() : Box[Set[NodeId]] = {
    inLock { repository.deleteAllNodeConfigurations }
  }

  /**
   * Return the server that need to be commited (that have been updated)
   */
  def getUpdatedNodeConfigurations() : Seq[NodeConfiguration] = {
    inLock {
      uncommitedNodes()
    }
  }

  /**
   * Write the templates of the updated servers
   * All the updated servers must be written
   * If the input parameters contains server that does not need to be updated, they won't be
   * @param ids : the id of the updated server
   */
  def writeTemplateForUpdatedNodeConfigurations(ids : Seq[NodeId]) : Box[Seq[NodeConfiguration]] = {
    inLock {
      val updatedNodeConfigurations = uncommitedNodes

      val updateBatch = new UpdateBatch


      for (node <- updatedNodeConfigurations) {
        if (!ids.contains(NodeId(node.id))) {
          LOGGER.warn("Must also write the modification of node {}", node.id)
          return ParamFailure[Seq[NodeId]]("NodeConfiguration " + node.id + " needs to be written too", Full(new VariableException("NodeConfiguration " + node + " needs to be written too")), Empty, ids)
        } else {
          if (node.getDirectives.size == 0) {
            LOGGER.warn("Can't write a server without policy {}", node.id)
            return Failure("Can't write a server without policy " + node, Full(throw new TechniqueException("Can't write a server without policy ")), Empty)

          }
        }
        updateBatch.addNodeConfiguration(node)
      }



      policyTranslator.writePromisesForMachines(updateBatch) match {
        case e: EmptyBox => return e
        case Full(f) => f;
      }

      val writeTime = DateTime.now().getMillis
      val savedNodes = repository.saveMultipleNodeConfigurations(updateBatch.updatedNodeConfigurations.valuesIterator.map(x => x.commitModification).toSeq)
      LOGGER.debug("Written in ldap the node configuration caches in %d millisec".format((DateTime.now().getMillis - writeTime)))

      // save this rootCause
      savedNodes
    }
  }

  /**
   * Rollback the configuration of the updated servers
   * All the updated servers must be rollbacked in one shot
   * If the input parameters contains server that does not need to be roolbacked, they won't be
   * @param ids : the id of the updated server
   */
  def rollbackNodeConfigurations(ids : Seq[NodeId]) : Box[Unit] = {
    inLock {
      val updatedNodeConfigurations = uncommitedNodes
      val updateBatch = new UpdateBatch

      for (node <- updatedNodeConfigurations) {
        if (!ids.contains(NodeId(node.id))) {
          LOGGER.warn("Must also write the modification of server {}", node.id)
          return ParamFailure[Seq[NodeId]]("NodeConfiguration " + node.id + " needs to be roolbacked too", Full(new VariableException("NodeConfiguration " + node + " needs to be written too")), Empty, ids)
        }
      }

      for (node <- updatedNodeConfigurations) {

        updateBatch.addNodeConfiguration(node.rollbackModification())
      }


      repository.saveMultipleNodeConfigurations(updateBatch.updatedNodeConfigurations.valuesIterator.toSeq)
      // save this rootCause
      Full(())
    }
  }

  /**
   * Returns all the servers having all the technique names (it is technique name, and not directive)
   */
  def getNodeConfigurationsMatchingPolicy(techniqueId : TechniqueId) : Seq[NodeConfiguration] = {
    repository.findNodeConfigurationByTargetPolicyName(techniqueId) match {
      case Full(seq) => seq
      case Empty =>
        throw new TechnicalException("Error when trying to find node with technique name %s. No error message left".format(techniqueId))
      case Failure(m,_,_)=>
        throw new TechnicalException("Error when trying to find node with technique name %s. Error message was: ".format(techniqueId,m))
    }
  }

  def getNodeConfigurationsMatchingDirective(directiveId : Cf3PolicyDraftId) : Seq[NodeConfiguration] = {
    repository.findNodeConfigurationByCurrentRuleId(RuleId(directiveId.value)) match {
      case Full(seq) => seq
      case Empty =>
        throw new TechnicalException("Error when trying to find node with directive id %s. No error message left".format(directiveId))
      case Failure(m,_,_)=>
        throw new TechnicalException("Error when trying to find node with directive id %s. Error message was: ".format(directiveId,m))
    }
  }


/*********************** Privates methods, utilitary methods ****************************************/

  /**
   * Deduplicate directive, in an ordered seq by priority
   * Unique technique are kept by priority order, (a 0 has more priority than 50)
   * @param directives
   * @return
   */
  private def deduplicateUniqueDirectives(directives: Seq[RuleWithCf3PolicyDraft]) : Box[Seq[RuleWithCf3PolicyDraft]] = {
    val resultingDirectives = ArrayBuffer[RuleWithCf3PolicyDraft]();

    for (directiveToAdd <- directives.sortBy(x => x.cf3PolicyDraft.priority)) {
      //Prior to add it, must check that it is not unique and not already present
      val policy = techniqueRepository.get(directiveToAdd.cf3PolicyDraft.techniqueId).getOrElse(return Failure("Error: can not find technique with name '%s'".format(directiveToAdd.cf3PolicyDraft.techniqueId)))
      if (policy.isMultiInstance)
         resultingDirectives += directiveToAdd
      else {
        // if it is unique, add it only a same one is not already there
        if (resultingDirectives.filter(x => x.cf3PolicyDraft.techniqueId == directiveToAdd.cf3PolicyDraft.techniqueId).size == 0)
           resultingDirectives += directiveToAdd
        else
           LOGGER.warn("Ignoring less prioritized unique directive %s ".format(directiveToAdd))
      }
    }
    Full(resultingDirectives)
  }

  /**
   * Return all the server that need to be commited
   * Meaning, all servers that have a difference between the current and target directives */
  private def uncommitedNodes() : Seq[NodeConfiguration] = {
    repository.findUncommitedNodeConfigurations match {
      case Full(seq) => seq
      case Empty =>
        throw new TechnicalException("Error when trying to find server with uncommited directive. No error message left")
      case Failure(m,_,_)=>
        throw new TechnicalException("Error when trying to find server with uncommited directive. Error message was: %s".format(m))
    }
  }


  def uuidGenerator : StringUuidGenerator = new StringUuidGenerator() {
    override def newUuid() =  java.util.UUID.randomUUID.toString
  }



  /**
   * Adding a directive to a node, without saving anything
   * (this is not hyper sexy)
   */
  private def addDirectives(node:NodeConfiguration, directives :  Seq[RuleWithCf3PolicyDraft]) : Box[NodeConfiguration] = {

    var modifiedNode = node

    for (directive <- directives) {
        // check the legit character of the policy
        if (modifiedNode.getDirective(directive.cf3PolicyDraft.id) != None) {
          LOGGER.warn("Cannot add a directive with the same id than an already existing one {} ",
              directive.cf3PolicyDraft.id)
          return ParamFailure[RuleWithCf3PolicyDraft](
              "Duplicate directive",
              Full(new TechniqueException("Duplicate directive " + directive.cf3PolicyDraft.id)),
              Empty,
              directive)
        }


        val technique = techniqueRepository.get(directive.cf3PolicyDraft.techniqueId).getOrElse(return Failure("Error: can not find policy with name '%s' with policy service".format(directive.cf3PolicyDraft.techniqueId)))

        // Check that the directive can be multiinstances
        // to check that, either make sure that it is multiinstance, or that it is not
        // multiinstance and that there are no existing directives based on it
        if (modifiedNode.findDirectiveByTechnique(directive.cf3PolicyDraft.techniqueId).filter(x => technique.isMultiInstance==false).size>0) {
          LOGGER.warn("Cannot add a directive from the same non duplicable technique %s than an already existing one %s ".format(directive.cf3PolicyDraft.techniqueId), modifiedNode.findDirectiveByTechnique(directive.cf3PolicyDraft.techniqueId))
          return ParamFailure[RuleWithCf3PolicyDraft]("Duplicate unique technique", Full(new TechniqueException("Duplicate unique policy " +directive.cf3PolicyDraft.techniqueId)), Empty, directive)
        }
        modifiedNode.addDirective(directive) match {
          case Full(updatedNode : NodeConfiguration) =>
            modifiedNode = updatedNode
          case f:EmptyBox => return f
        }
    }

    return Full(modifiedNode)

  }
}
