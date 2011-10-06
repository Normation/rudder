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

package com.normation.rudder.services.policies

import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.inventory.domain.COMMUNITY_AGENT
import com.normation.cfclerk.domain.{ InputVariable, InputVariableSpec}
import com.normation.inventory.domain.NOVA_AGENT
import com.normation.cfclerk.domain.Variable
import com.normation.cfclerk.services.PolicyPackageService
import com.normation.cfclerk.services.impl.PromiseWriterServiceImpl
import scala.collection._
import scala.io.Source
import net.liftweb.common._
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io._
import scala.xml._
import com.normation.rudder.domain._
import com.normation.rudder.services.path._
import com.normation.rudder.repository._
import org.apache.commons.io.FileUtils
import com.normation.rudder.exceptions._
import com.normation.rudder.domain.servers._
import org.joda.time._
import org.joda.time.format._
import com.normation.stringtemplate.language.formatter._
import org.antlr.stringtemplate._
import org.antlr.stringtemplate.language._
import com.normation.stringtemplate.language._
import com.normation.rudder.domain.transporter._
import com.normation.rudder.domain.log._
import com.normation.rudder.services.reports._
import com.normation.cfclerk.domain._
import Process._
import com.normation.inventory.domain.AgentType
import net.liftweb.util.Helpers.tryo
import com.normation.cfclerk.services.SystemVariableSpecService

class RudderPromiseWriterServiceImpl(
  policyService: PolicyPackageService,
  pathComputer: PathComputer,
  val nodeConfigurationRepository: NodeConfigurationRepository,
  nodeInfoService: NodeInfoService,
  val licenseRepository: LicenseRepository,
  reportingService: ReportingService,
  systemVariableSpecService: SystemVariableSpecService,
  systemVariableService: SystemVariableService,
  baseFolder: String,
  backupFolder: String,
  toolsFolder: String,
  sharesFolder: String,
  cmdbEnpoint: String,
  communityPort: String,
  communityCheckPromises: String,
  novaCheckPromises: String
) extends PromiseWriterServiceImpl(
  policyService, systemVariableSpecService, baseFolder, backupFolder
) with TemplateWriter with Loggable {

  logger.debug("baseFolder %s".format(baseFolder))

  licenseRepository.loadLicenses()
  logger.debug("Licenses loaded")

  def getSharesFolder(): String = {
    sharesFolder
  }

  def getToolsFolder(): String = {
    toolsFolder
  }
  def getCmdbEnpoint(): String = {
    cmdbEnpoint
  }

  def getCommunityPort = communityPort

  /**
   * Write the promises of all the machine
   * It no longer change the status of the nodeconfiguration
   * @param updateBatch : the container for the server to be updated
   */
  override def writePromisesForMachines(updateBatch: UpdateBatch): Box[Seq[PromisesFinalMoveInfo]] = {
    // A buffer of node, promisefolder, newfolder, backupfolder 
    val folders = mutable.Buffer[(NodeConfiguration, String, String, String)]()
    // Writing the policy 
    for (node <- updateBatch.updatedNodeConfigurations.valuesIterator) {
      if (node.getPolicyInstances.size == 0) {
        logger.error("Could not write the promises for server %s : No policy found on server".format(node.id))
        throw new Exception("Could not write the promises : no policy on machine " + node)
      }
      if (node.targetMinimalNodeConfig.agentsName.size < 1) {
        val msg = "Can not write promises for a node without any agent specified. Node id: %s".format(node.id)
        logger.error(msg)
        throw new RuntimeException(msg)
      }
      val (baseNodePath, backupNodePath) = pathComputer.computeBaseServerPath(node)

      prepareRulesForAgents(baseNodePath, backupNodePath, node) match {
        case Full(x) => 
          folders ++= x
        case e: EmptyBox => return (e ?~! "Error when preparing rules for agents")
      }

    }

    tryo(movePromisesToFinalPosition(folders.map((x) => PromisesFinalMoveInfo(x._1.id, x._2, x._3, x._4))))

  }

  /**
   * From a base path and a base backup path, plus a node configuration, write the rules
   * for its of the agent target in it, and then check it
   * Caution : a specific computation is done for the root server
   * @param baseMachinePath
   * @param node
   * @param cause
   * @return : a Set of node, base folder, new folder, backup folder (don't want to return duplicate)
   */
  private[this] def prepareRulesForAgents(baseNodePath: String, backupNodePath: String, node: NodeConfiguration): Box[Set[(NodeConfiguration, String, String, String)]] = {

    val folders = mutable.Set[(NodeConfiguration, String, String, String)]()

    for (agentType <- node.targetMinimalNodeConfig.agentsName) {
      val varNova = SystemVariable(systemVariableSpecService.get("NOVA"))
      val varCommunity = SystemVariable(systemVariableSpecService.get("COMMUNITY"))

      agentType match {
        case NOVA_AGENT => varNova.saveValue("true")
        case COMMUNITY_AGENT => varCommunity.saveValue("true")
        case x => return Failure("Unrecognized agent type: %s. Known values are: %s".format(x, AgentType.allValues))
      }

      val systemVariables = node.getTargetSystemVariables + (varNova.spec.name -> varNova) + (varCommunity.spec.name -> varCommunity)

      val (nodeRulePath, newNodePath, backupNodeRulePath, newNodeRulePath) = nodeConfigurationRepository.getRootNodeConfiguration match {
        case Full(root: NodeConfiguration) if root.id == node.id => (pathComputer.getRootPath(agentType), pathComputer.getRootPath(agentType) + newPostfix, pathComputer.getRootPath(agentType) + backupPostfix, pathComputer.getRootPath(agentType) + newPostfix)
        case _ => (baseNodePath, baseNodePath + newPostfix, backupNodePath, baseNodePath + newPostfix + "/rules" + agentType.toRulesPath()) // we'll want to move the root folders
      }

      val tmls = prepareTmls(NodeConfiguration.toContainer(newNodeRulePath, node), systemVariables)

      logger.debug("Prepared the tml for the node %s".format(node.id))

      // write the promises of the current machine
      for { (ptId, preparedTemplate) <- tmls } {
        writePromisesFiles(preparedTemplate.templatesToCopy , preparedTemplate.environmentVariables , newNodeRulePath)
      }
      
      writeSpecificsData(node, newNodeRulePath)

      agentType match {
        case NOVA_AGENT => writeLicense(node, newNodeRulePath)
        case _ => ;
      }

      // Check the promises
      val errorCode = agentType match {
        case NOVA_AGENT =>
          val process: scala.sys.process.ProcessBuilder = (novaCheckPromises + " -f " + newNodeRulePath + "/promises.cf")
          process.!

        case _ =>
          val process: scala.sys.process.ProcessBuilder = (communityCheckPromises + " -f " + newNodeRulePath + "/promises.cf")
          process.!
      }

      if (errorCode != 0) {
        logger.error("The generated promises at %s are invalid".format(newNodeRulePath))
        return Failure("The generated promises are invalid")
      }

      folders += ((node, nodeRulePath, newNodePath, backupNodeRulePath))
    }
    
    Full(folders)

  }

}
