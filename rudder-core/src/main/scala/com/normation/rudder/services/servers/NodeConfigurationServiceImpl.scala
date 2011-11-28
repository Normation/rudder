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
import com.normation.rudder.services.policies.TargetNodeConfiguration
import com.normation.inventory.domain.AgentType
import com.normation.rudder.domain.policies.ConfigurationRuleId
import com.normation.rudder.domain.policies.ConfigurationRule
import com.normation.utils.User
import com.normation.eventlog.{EventLogNode,EventActor}
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
import com.normation.rudder.domain.log._
import com.normation.rudder.services.log._
import com.normation.eventlog._
import com.normation.inventory.domain._
import com.normation.rudder.domain.policies.IdentifiableCFCPI
import com.normation.exceptions.TechnicalException

/**
 * Implementation of the server service, deals with adding, removing, updating policies or servers
 * @author Nicolas CHARLES
 *
 */
class NodeConfigurationServiceImpl(policyTranslator : TemplateWriter, 
    repository : NodeConfigurationRepository,
    policyService : PolicyPackageService,
    eventLogService : EventLogService,
    lockFolder : String) extends NodeConfigurationService {

  val LOGGER = LoggerFactory.getLogger(classOf[NodeConfigurationServiceImpl])

  LOGGER.info("Creating lock folder at {}", lockFolder)
  createLockFolder()
  val fileLock = new File(lockFolder , "updateServers.lock");
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
  
  def findServer(serverUUID: NodeId) : Option[NodeConfiguration] = {
    inLock {
      repository.findNodeConfiguration(serverUUID) match {
        case Full(server) => Some(server)
        case Empty => None
        case f:Failure => throw new TechnicalException("Error when trying to retrieve server %s. Message was %s".format(serverUUID,f.messageChain))
      }
    }
  }

  def getMultipleNodeConfigurations(ids : Seq[NodeId]) : Set[NodeConfiguration] = {
    inLock {
      repository.getMultipleNodeConfigurations(ids) match {
        case Full(servers) => servers
        case Empty => Set()
        case Failure(m,_,_) => throw new TechnicalException("Error when trying to retrieve servers %s. Message was %s".format(ids,m))
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
        case Failure(m,_,_) => throw new TechnicalException("Error when trying to retrieve all servers. Message was %s".format(m))
      }
    }
  }

  
  /**
   * Update a node configuration using a TargetNodeConfiguration :
   * update the policy instances and the node context, as well as the agentsName 
   * (well, every fields actually)
   * @param target
   * @return
   */
  def updateNodeConfiguration(target : TargetNodeConfiguration) : Box[NodeConfiguration] = {
    //create a new node configuration based on "target" datas
    def createNodeConfiguration() : NodeConfiguration = {
      //we need to decide if it's a root node config or a simple one
      target.nodeInfo match {
        case info: PolicyServerNodeInfo =>
          new RootNodeConfiguration(
            info.id.value,
            Seq(),
            Seq(),
            false,
            // when creating a nodeconfiguration, there is no current
            currentMinimalNodeConfig = new MinimalNodeConfig( 
                "", "", Seq(), "", ""
            ),
            targetMinimalNodeConfig = new MinimalNodeConfig(
                info.name ,
                info.hostname,
                info.agentsName,
                info.policyServerId.value,
                info.localAdministratorAccountName                   
            ),
            None,
            Map(),
            target.nodeContext
          )
          
        case info: NodeInfo =>
          new SimpleNodeConfiguration(
            info.id.value,
            Seq(),
            Seq(),
            false,
            // when creating a nodeconfiguration, there is no current
            currentMinimalNodeConfig = new MinimalNodeConfig( 
                "", "", Seq(), "", ""
            ),
            targetMinimalNodeConfig = new MinimalNodeConfig(
                info.name ,
                info.hostname,
                info.agentsName,
                info.policyServerId.value,
                info.localAdministratorAccountName                   
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
        return ParamFailure[Seq[IdentifiableCFCPI]]("Cannot create a server without policies", Full(new PolicyException("Cannot create a server without any policies")), Empty, target.identifiableCFCPIs)
      }

      deduplicateUniquePolicyInstances(target.identifiableCFCPIs) match {
      	case f : EmptyBox => 
      	  LOGGER.debug( (f ?~! "An error happened when finding unique policy instances").messageChain )
      	  LOGGER.error("Could not convert policy instance beans")
      	  f
      	  
      	case Full(policiesInstances) =>

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
  	      	case Full(server : SimpleNodeConfiguration) => 									      		
  	      		//update nodeconfiguration
              //server.id  = target.nodeInfo.id.value //not mutable TODO: use it in the constructor
  	      		
  	      		server.copy(
  	      		    __targetPoliciesInstances = Seq[IdentifiableCFCPI](),
  	      		    targetMinimalNodeConfig = new MinimalNodeConfig(
  	      		    		target.nodeInfo.name ,
  	      		    		target.nodeInfo.hostname ,
  	      		    		target.nodeInfo.agentsName ,
  	      		    		target.nodeInfo.policyServerId.value ,
  	      		    		target.nodeInfo.localAdministratorAccountName
  	      		    		),
  	      		    targetSystemVariables = target.nodeContext
  	      	  )

  	      		
  	      	case Full(server : RootNodeConfiguration) => 								
  	      		server.copy(
  	      		    __targetPoliciesInstances = Seq[IdentifiableCFCPI](),
  	      		    targetMinimalNodeConfig = new MinimalNodeConfig(
  	      		    		target.nodeInfo.name ,
  	      		    		target.nodeInfo.hostname ,
  	      		    		target.nodeInfo.agentsName ,
  	      		    		target.nodeInfo.policyServerId.value ,
  	      		    		target.nodeInfo.localAdministratorAccountName
  	      		    		),
  	      		    targetSystemVariables = target.nodeContext
  	      		 )
             
        	}
        	
        	for {
        	  addPoliciesOK          <- addPolicies(nodeConfiguration, policiesInstances) ?~! "Error when adding policies for node configuration %s".format(target.nodeInfo.id.value)
        	  nodeConfigurationSaved <- repository.saveNodeConfiguration(addPoliciesOK) ?~! "Error when saving node configuration %s".format(target.nodeInfo.id.value)
        	} yield {
        	  nodeConfigurationSaved
        	}
      }
    }
  }
  
  /**
   * From the list of updated crs (is it what we need) ? and th elist of ALL NODES (caution, we must have 'em all)
   * update the serials, and save them
   */
  def incrementSerials(crs: Seq[(ConfigurationRuleId,Int)], nodes : Seq[NodeConfiguration]) : Box[Seq[NodeConfiguration]] = {
  		repository.saveMultipleNodeConfigurations(nodes.map(x => x.setSerial(crs)))    
  }
  
  
  /**
   * Create a node configuration from a target.
   * Hence, it will have empty current configuration
   * @param target
   * @return
   */
  def addNodeConfiguration(target : TargetNodeConfiguration) : Box[NodeConfiguration] = {
  	inLock {
      if (target.identifiableCFCPIs.size == 0) {
        LOGGER.warn("Cannot create server {} without policy", target.nodeInfo.id.value)
        return ParamFailure[Seq[IdentifiableCFCPI]]("Cannot create a server without policies", Full(new PolicyException("Cannot create a server without any policies")), Empty, target.identifiableCFCPIs)
      }
      
      val isPolicyServer = target.nodeInfo match {
      	case t: PolicyServerNodeInfo => true
      	case t : NodeInfo => false
      }
      
      var server = target.nodeInfo match {
      	case t: PolicyServerNodeInfo => 
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
      	case t : NodeInfo =>
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
        
        
      
      deduplicateUniquePolicyInstances(target.identifiableCFCPIs) match {
      	case f : EmptyBox => f
      	case Full(policiesInstances) =>
      			
	      		addPolicies(server, policiesInstances) match {
	      			case f: EmptyBox => return f
	      			case Full(server) => 
	      			for {
	      				saved <- repository.saveNodeConfiguration(server)
	      				findBack <- repository.findNodeConfiguration(NodeId(server.id))
	      			} yield findBack
      			}
      }
    }
  }
 

  /**
   * Delete a server by its id
   */
  def deleteNodeConfiguration(serverid:String) :  Box[Unit] = {
    inLock {
      val server = repository.findNodeConfiguration(NodeId(serverid))
      if (server == None) {
        LOGGER.debug("Could not delete not found server {}", serverid)
        return Full(Unit)
      }

      //TODO: nothing is done with update batch ?
      val updateBatch = new UpdateBatch
      updateBatch.addNodeConfiguration(server.get)
      
      repository.deleteNodeConfiguration(serverid)
      Full(Unit)
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
      uncommitedMachines()
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
      val updatedNodeConfigurations = uncommitedMachines
      
      val updateBatch = new UpdateBatch
      
      
      for (server <- updatedNodeConfigurations) {
        if (!ids.contains(NodeId(server.id))) {
          LOGGER.warn("Must also write the modification of server {}", server.id)
          return ParamFailure[Seq[NodeId]]("NodeConfiguration " + server.id + " needs to be written too", Full(new VariableException("NodeConfiguration " + server + " needs to be written too")), Empty, ids)
        } else {
          if (server.getPolicyInstances.size == 0) {
            LOGGER.warn("Can't write a server without policy {}", server.id)
            return Failure("Can't write a server without policy " + server, Full(throw new PolicyException("Can't write a server without policy ")), Empty)
            
          }
        }
        updateBatch.addNodeConfiguration(server)
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
      val updatedNodeConfigurations = uncommitedMachines
      val updateBatch = new UpdateBatch
      
      for (server <- updatedNodeConfigurations) {
        if (!ids.contains(NodeId(server.id))) {
          LOGGER.warn("Must also write the modification of server {}", server.id)
          return ParamFailure[Seq[NodeId]]("NodeConfiguration " + server.id + " needs to be roolbacked too", Full(new VariableException("NodeConfiguration " + server + " needs to be written too")), Empty, ids)
        }
      }

      for (server <- updatedNodeConfigurations) {
        
        updateBatch.addNodeConfiguration(server.rollbackModification())
      }
      
      
      repository.saveMultipleNodeConfigurations(updateBatch.updatedNodeConfigurations.valuesIterator.toSeq)
      // save this rootCause
      Full(Unit)
    }
  }
  
  /**
   * Returns all the servers having all the policies names (it is policy name, and not policy instance)
   */
  def getNodeConfigurationsMatchingPolicy(policyName : PolicyPackageId) : Seq[NodeConfiguration] = {
    repository.findNodeConfigurationByTargetPolicyName(policyName) match {
      case Full(seq) => seq
      case Empty =>
        throw new TechnicalException("Error when trying to find server with policy name %s. No error message left".format(policyName))
      case Failure(m,_,_)=>
        throw new TechnicalException("Error when trying to find server with policy name %s. Error message was: ".format(policyName,m))
    }
  }

  def getNodeConfigurationsMatchingPolicyInstance(policyInstanceId : CFCPolicyInstanceId) : Seq[NodeConfiguration] = {
    repository.findNodeConfigurationByCurrentConfigurationRuleId(ConfigurationRuleId(policyInstanceId.value)) match {
      case Full(seq) => seq
      case Empty =>
        throw new TechnicalException("Error when trying to find server with policy instance id %s. No error message left".format(policyInstanceId))
      case Failure(m,_,_)=>
        throw new TechnicalException("Error when trying to find server with policy instance id %s. Error message was: ".format(policyInstanceId,m))
    }
  }


/*********************** Privates methods, utilitary methods ****************************************/

  /**
   * Deduplicate policy instance, in an ordered seq by priority
   * Unique policy are kept by priority order, (a 0 has more priority than 50)
   * @param pIs
   * @return
   */  
  private def deduplicateUniquePolicyInstances(pIs: Seq[IdentifiableCFCPI]) : Box[Seq[IdentifiableCFCPI]] = { 	
    val policiesInstances = ArrayBuffer[IdentifiableCFCPI]();
    
    for (policyToAdd <- pIs.sortBy(x => x.policyInstance.priority)) {
      //Prior to add it, must check that it is not unique and not already present
      val policy = policyService.getPolicy(policyToAdd.policyInstance.policyId).getOrElse(return Failure("Error: can not find policy with name '%s' with policy service".format(policyToAdd.policyInstance.policyId)))
      if (policy.isMultiInstance)
         policiesInstances += policyToAdd
      else {
        // if it is unique, add it only a same one is not already there
        if (policiesInstances.filter(x => x.policyInstance.policyId == policyToAdd.policyInstance.policyId).size == 0)
           policiesInstances += policyToAdd
        else
           LOGGER.warn("Ignoring less prioritized unique policy instance %s ".format(policyToAdd))
      }
    }
    Full(policiesInstances)
  }
  
  /**
   * Return all the server that need to be commited
   * Meaning, all servers that have a difference between the current and target policy instances */
  private def uncommitedMachines() : Seq[NodeConfiguration] = {
    repository.findUncommitedNodeConfigurations match {
      case Full(seq) => seq
      case Empty =>
        throw new TechnicalException("Error when trying to find server with uncommited policy instance. No error message left")
      case Failure(m,_,_)=>
        throw new TechnicalException("Error when trying to find server with uncommited policy instance. Error message was: %s".format(m))
    }    
  }
  

  def uuidGenerator : StringUuidGenerator = new StringUuidGenerator() {
    override def newUuid() =  java.util.UUID.randomUUID.toString
  }

  

  /**
   * Adding a policy to a server, without saving anything
   * (this is not hyper sexy)
   */
  private def addPolicies(server:NodeConfiguration, policyInstances :  Seq[IdentifiableCFCPI]) : Box[NodeConfiguration] = {
    
    var modifiedServer = server
    
    for (policyInstance <- policyInstances) {
  			// check the legit character of the policy
		    if (modifiedServer.getPolicyInstance(policyInstance.policyInstance.id) != None) {
		      LOGGER.warn("Cannot add a policy instance with the same id than an already existing one {} ", policyInstance.policyInstance.id)
		      return ParamFailure[IdentifiableCFCPI]("Duplicate policy instance", Full(new PolicyException("Duplicate policy instance " +policyInstance.policyInstance.id)), Empty, policyInstance)
		    }
		    

		    val policy = policyService.getPolicy(policyInstance.policyInstance.policyId).getOrElse(return Failure("Error: can not find policy with name '%s' with policy service".format(policyInstance.policyInstance.policyId)))

        // Check that the policy can be multiinstances
		    if (modifiedServer.findPolicyInstanceByPolicy(policyInstance.policyInstance.policyId).filter(x => policy.isMultiInstance==false).size>0) {
		      LOGGER.warn("Cannot add a policy instance from the same non duplicable policy %s than an already existing one %s ".format(policyInstance.policyInstance.policyId), modifiedServer.findPolicyInstanceByPolicy(policyInstance.policyInstance.policyId))
		      return ParamFailure[IdentifiableCFCPI]("Duplicate unique policy", Full(new PolicyException("Duplicate unique policy " +policyInstance.policyInstance.policyId)), Empty, policyInstance)    
		    }
		   
		    modifiedServer.addPolicyInstance(policyInstance) match {
		      case Full(server : NodeConfiguration) => modifiedServer = server
		      case f:EmptyBox => return f
		    }
		    
  	}
        
    return Full(modifiedServer)
  	
  }  
}
