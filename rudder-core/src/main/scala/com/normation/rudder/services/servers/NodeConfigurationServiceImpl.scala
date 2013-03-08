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

import scala.collection.mutable.ArrayBuffer

import org.joda.time.DateTime

import com.normation.cfclerk.services.TechniqueRepository
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.RuleWithCf3PolicyDraft
import com.normation.rudder.domain.servers.MinimalNodeConfig
import com.normation.rudder.domain.servers.NodeConfiguration
import com.normation.rudder.domain.servers.RootNodeConfiguration
import com.normation.rudder.domain.servers.SimpleNodeConfiguration
import com.normation.rudder.exceptions.TechniqueException
import com.normation.rudder.repository.NodeConfigurationRepository
import com.normation.rudder.services.policies.TargetNodeConfiguration
import com.normation.rudder.services.policies.TemplateWriter

import net.liftweb.common.Box
import net.liftweb.common.Empty
import net.liftweb.common.EmptyBox
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.common.ParamFailure

/**
 * Implementation of the Node Configuration service
 * It manages the NodeConfiguration content (the cache of the deployed conf)
 *
 * That implementation is not thread safe at all, and all call to its
 * methods should be made in the context of an actor
 * (deployment service and it's actor model is a good example)
 *
 */
class NodeConfigurationServiceImpl(
    policyTranslator    : TemplateWriter
  , repository          : NodeConfigurationRepository
  , techniqueRepository : TechniqueRepository
) extends NodeConfigurationService with Loggable {

  /**
   * Find all servers
   * @return
   */
  def getAllNodeConfigurations() : Box[Map[NodeId, NodeConfiguration]] = repository.getAll()

  /**
   * Update a node configuration using a targetNodeConfiguration :
   * update the directives and the node context, as well as the agentsName
   * (well, every fields actually)
   * @param target
   * @return
   */
  def updateNodeConfiguration(target: TargetNodeConfiguration, allNodeConfigs: Map[NodeId, NodeConfiguration]) : Box[Map[NodeId, NodeConfiguration]] = {
    //create a new node configuration based on "target" datas
    def createNodeConfiguration() : NodeConfiguration = {
      //we need to decide if it's a root node config or a simple one
      if(target.nodeInfo.isPolicyServer) {
          new RootNodeConfiguration(
            target.nodeInfo.id,
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
            target.nodeContext,
            Set(),
            target.parameters
          )
      } else {
          new SimpleNodeConfiguration(
            target.nodeInfo.id,
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
            target.nodeContext,
            Set(),
            target.parameters
          )
      }
    }

    logger.debug("Updating node configuration %s".format(target.nodeInfo.id.value) )
    if (target.identifiableCFCPIs.size == 0) {
      logger.warn(s"Cannot create server ${target.nodeInfo.id.value} without policy")
      return ParamFailure[Seq[RuleWithCf3PolicyDraft]]("Cannot create a server without policies", Full(new TechniqueException("Cannot create a server without any policies")), Empty, target.identifiableCFCPIs)
    }

    val directives = deduplicateUniqueDirectives(target.identifiableCFCPIs)

    /*
     * Try to find the node configuration. If none are found (or one is found but
     * mapping lead to an error, create a new one.
     */
    val nodeConfiguration = allNodeConfigs.get(target.nodeInfo.id) match {

      case None => //create configuration for that node
        createNodeConfiguration()

      case Some(node : SimpleNodeConfiguration) =>
        //update nodeconfiguration
        //server.id  = target.nodeInfo.id.value //not mutable TODO: use it in the constructor

        node.copy(
            targetRulePolicyDrafts = Seq[RuleWithCf3PolicyDraft](),
            targetMinimalNodeConfig = new MinimalNodeConfig(
                target.nodeInfo.name ,
                target.nodeInfo.hostname ,
                target.nodeInfo.agentsName ,
                target.nodeInfo.policyServerId.value,
                target.nodeInfo.localAdministratorAccountName
                ),
            targetSystemVariables = target.nodeContext,
            targetParameters = target.parameters

        )


      case Some(rootServer : RootNodeConfiguration) =>
        rootServer.copy(
            targetRulePolicyDrafts = Seq[RuleWithCf3PolicyDraft](),
            targetMinimalNodeConfig = new MinimalNodeConfig(
                target.nodeInfo.name ,
                target.nodeInfo.hostname ,
                target.nodeInfo.agentsName ,
                target.nodeInfo.policyServerId.value,
                target.nodeInfo.localAdministratorAccountName
                ),
            targetSystemVariables = target.nodeContext,
            targetParameters = target.parameters
         )
    }

    for {
      finalConfig <- addDirectives(nodeConfiguration, directives) ?~! "Error when adding policies for node configuration %s".format(target.nodeInfo.id.value)
    } yield {
      allNodeConfigs + (finalConfig.id -> finalConfig)
    }
  }

  /**
   * Delete a node by its id
   */
  def deleteNodeConfigurations(nodeIds:Set[NodeId]) :  Box[Set[NodeId]] = repository.deleteNodeConfigurations(nodeIds)

  /**
   * delete all node configuration
   */
  def deleteAllNodeConfigurations() : Box[Set[NodeId]] = repository.deleteAllNodeConfigurations

  /**
   * Write the templates of the updated servers
   * All the updated servers must be written
   * If the input parameters contains server that does not need to be updated, they won't be
   * @param ids : the id of the updated server
   */
  def writeTemplateForUpdatedNodeConfigurations(rootNodeId: NodeId, allNodeConfigs: Map[NodeId, NodeConfiguration]) : Box[Seq[NodeConfiguration]] = {
    val updatedNodeConfigurations = allNodeConfigs.filter( _._2.isModified )

    for ((_, node) <- updatedNodeConfigurations) {
      if (node.targetRulePolicyDrafts.size == 0) {
        logger.warn(s"Can't write a server without policy ${node.id.value}")
        return Failure("Can't write a server without policy " + node, Full(throw new TechniqueException("Can't write a server without policy ")), Empty)
      }
    }

    if(updatedNodeConfigurations.size == 0) {
      logger.info("No node configuration was updated, no promises to write")
    } else {
      logger.info("Configuration of following nodes were updated, their promises are going to be written: " + updatedNodeConfigurations.keySet.map(_.value).mkString(", "))
    }


    policyTranslator.writePromisesForMachines(updatedNodeConfigurations, rootNodeId, allNodeConfigs) match {
      case e: EmptyBox => return e
      case Full(f) => f;
    }

    val writeTime = DateTime.now().getMillis

    val savedNodes = repository.saveMultipleNodeConfigurations(updatedNodeConfigurations.values.map(x => x.commitModification).toSeq)
    logger.debug("Written in ldap the node configuration caches in %d millisec".format((DateTime.now().getMillis - writeTime)))
    savedNodes
  }


/*********************** Privates methods, utilitary methods ****************************************/

  /**
   * Deduplicate directive, in an ordered seq by priority
   * Unique technique are kept by priority order, (a 0 has more priority than 50)
   * @param directives
   * @return
   */
  private def deduplicateUniqueDirectives(directives: Seq[RuleWithCf3PolicyDraft]) : Seq[RuleWithCf3PolicyDraft] = {
    val resultingDirectives = ArrayBuffer[RuleWithCf3PolicyDraft]();

    for (directiveToAdd <- directives.sortBy(x => x.cf3PolicyDraft.priority)) {
      //Prior to add it, must check that it is not unique and not already present
      val technique = directiveToAdd.cf3PolicyDraft.technique
      if (technique.isMultiInstance)
         resultingDirectives += directiveToAdd
      else {
        // if it is unique, add it only a same one is not already there
        if (resultingDirectives.filter(x => x.cf3PolicyDraft.technique.id == directiveToAdd.cf3PolicyDraft.technique.id).size == 0)
           resultingDirectives += directiveToAdd
        else
           logger.warn("Ignoring less prioritized unique directive %s ".format(directiveToAdd))
      }
    }
    resultingDirectives
  }

  /**
   * Adding a directive to a node, without saving anything
   * (this is not hyper sexy)
   */
  private def addDirectives(node:NodeConfiguration, directives :  Seq[RuleWithCf3PolicyDraft]) : Box[NodeConfiguration] = {

    var modifiedNode = node

    for (directive <- directives) {
        // check the legit character of the policy
        if (modifiedNode.targetRulePolicyDrafts.find( _.draftId == directive.draftId) != None) {
          logger.warn(s"Cannot add a directive with the same id than an already existing one ${directive.draftId}")
          return ParamFailure[RuleWithCf3PolicyDraft](
              "Duplicate directive",
              Full(new TechniqueException("Duplicate directive " + directive.draftId)),
              Empty,
              directive)
        }


        val technique = directive.cf3PolicyDraft.technique

        // Check that the directive can be multiinstances
        // to check that, either make sure that it is multiinstance, or that it is not
        // multiinstance and that there are no existing directives based on it
        if (modifiedNode.findDirectiveByTechnique(directive.cf3PolicyDraft.technique.id).filter(x => technique.isMultiInstance==false).size>0) {
          logger.warn(s"Cannot add a directive from the same non duplicable technique ${directive.cf3PolicyDraft.technique.id} than an already existing one")
          return ParamFailure[RuleWithCf3PolicyDraft]("Duplicate unique technique", Full(new TechniqueException("Duplicate unique policy " +directive.cf3PolicyDraft.technique.id)), Empty, directive)
        }
        modifiedNode.addDirective(directive) match {
          case Full(updatedNode : NodeConfiguration) =>
            modifiedNode = updatedNode
          case f:EmptyBox => return f
        }
    }
    Full(modifiedNode)
  }
}
