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
import com.normation.inventory.domain.NOVA_AGENT
import com.normation.cfclerk.domain.Variable
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.cfclerk.services.impl.Cf3PromisesFileWriterServiceImpl
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
import com.normation.rudder.domain.eventlog._
import com.normation.rudder.services.reports._
import com.normation.cfclerk.domain._
import Process._
import com.normation.inventory.domain.AgentType
import net.liftweb.util.Helpers.tryo
import com.normation.cfclerk.services.SystemVariableSpecService
import scala.sys.process.ProcessLogger
import com.normation.inventory.domain.NodeId
import com.normation.rudder.services.policies.nodeconfig.NodeConfiguration
import com.normation.utils.Control.boxSequence
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.domain.reports.NodeAndConfigId
import scala.sys.process.Process

class RudderCf3PromisesFileWriterServiceImpl(
  techniqueRepository      : TechniqueRepository,
  pathComputer             : PathComputer,
  nodeInfoService          : NodeInfoService,
  val licenseRepository    : LicenseRepository,
  reportingService         : ReportingService,
  systemVariableSpecService: SystemVariableSpecService,
  systemVariableService    : SystemVariableService,
  private val toolsFolder  : String,
  private val sharesFolder : String,
  cmdbEnpoint              : String,
  communityPort            : Int,
  communityCheckPromises   : String,
  novaCheckPromises        : String,
  cfengineReloadPromises   : String
) extends Cf3PromisesFileWriterServiceImpl(
  techniqueRepository, systemVariableSpecService
) with TemplateWriter with Loggable {


  val newPostfix = ".new"
  val backupPostfix = ".bkp"

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
   * Write the promises for all the relevant nodes
   * For each node:
   * 1 - Promises are written to /var/rudder/share/node-uuid/rules.new/cfengine-agentType (except for root)
   * 2 - Promises are checked there
   * 3 - If they are all valid,
   *    Old promises are moved to /var/rudder/backup/node-uuid/rules/cfengine-agentType (except for root)
   *    New promises are moved to /var/rudder/share/node-uuid/rules/cfengine-agentType (except for root)
   * @param updateBatch : the container for the server to be updated
   */
  override def writePromisesForMachines(configToWrite: Set[NodeId], rootNodeId: NodeId, allNodeConfigs:Map[NodeId, NodeConfiguration], versions: Map[NodeId, NodeConfigId]): Box[Seq[PromisesFinalMoveInfo]] = {
    val interestingNodeConfig = allNodeConfigs.filterKeys(k => configToWrite.exists(x => x == k)).values.toSeq.par
    val techniqueIds = interestingNodeConfig.flatMap( _.getTechniqueIds ).toSet

    // CFEngine will not run automatically if someone else than user can write the promise
    def setCFEnginePermission (baseFolder:String ) : Box[String] = {

      // We need to remove executable perms, then add them back only on folders (X), so no file have executable perms
      // Then remove all permissions for group and other
      val changePermission: scala.sys.process.ProcessBuilder = s"/bin/chmod -R u-x,u+rwX,go-rwx ${baseFolder}"

      // Permissions change is inside tryo, not sure if it throws exceptions, but as it interacts with IO, we cannot be sure
      tryo { changePermission ! } match {
        case Full(result) =>
          if (result != 0) {
            Failure(s"Could not change permission for base folder ${baseFolder}")
          } else {
            Full("OK")
          }
        case eb:EmptyBox =>
          eb ?~! "cannot change permission on destination folder"
      }
    }

    for {
      templates     <- readTemplateFromFileSystem(techniqueIds)
      movedPromises <- {
                         // A buffer of node, promisefolder, newfolder, backupfolder
                         val folders = collection.mutable.Buffer[(NodeConfiguration, String, String, String)]()
                         // Writing the policy
                         for (node <- interestingNodeConfig) {
                           if (node.policyDrafts.size == 0) {
                             val msg = s"Can not write promises for node ${node.nodeInfo.hostname} (id: ${node.nodeInfo.id.value}): No policy found for node"
                             logger.error(msg)
                             throw new Exception(msg)
                           }
                           if (node.nodeInfo.agentsName.size < 1) {
                             val msg = s"Can not write promises for node ${node.nodeInfo.hostname} (id: ${node.nodeInfo.id.value}): no agent type specified."
                             logger.error(msg)
                             throw new RuntimeException(msg)
                           }
                           val (nodePromisePath, newNodePromisePath, backupNodePath) = pathComputer.computeBaseNodePath(node.nodeInfo.id, rootNodeId, allNodeConfigs) match {
                             case Full(x) => x
                             case e:EmptyBox => return (e ?~! s"Error when computing the path for node  ${node.nodeInfo.hostname} (id: ${node.nodeInfo.id.value})")
                           }

                           prepareRulesForAgents(nodePromisePath, newNodePromisePath, backupNodePath, node, versions(node.nodeInfo.id), rootNodeId, templates) match {
                             case Full(x) =>
                               folders ++= x
                             case e: EmptyBox => return (e ?~! "Error when preparing rules for agents")
                           }



                         }

                         val moveInfo = folders.map((x) => PromisesFinalMoveInfo(x._1.nodeInfo.id.value, x._2, x._3, x._4))
                         // Change permission on generated promise folder so CFEngine can run
                         val permissionChanged =
                           for {
                             // Find all distinct generated promise folder
                             promiseFolder <- moveInfo.map(_.newFolder).distinct
                           } yield {
                             setCFEnginePermission(promiseFolder)
                           }

                         boxSequence(permissionChanged) match {
                           case Full(_) =>
                             tryo { movePromisesToFinalPosition(moveInfo) }
                           case eb:EmptyBox =>
                             val fail = eb ?~! "cannot change permission on destination folder"
                             Failure(s"Cannot move new promises to their folder, cause is: ${fail.msg}")
                         }
                       }
      } yield {
        movedPromises
      }
  }

  /**
   * From a base path and a base backup path, plus a node configuration, write the rules
   * for its of the agent target in it, and then check it
   *
   * This is a several step process, from a nodePromisePath (end path), newNodePromisePath (path where promises are written before being checked)
   * and a backupNodePath (path where old promises will be moved), and a node, it will:
   * 1 - get the agents types, and complete the path with cfengine-community or cfengine-noca
   * 2 - if the node is root, it will use hardcoded paths (var/rudder/cfengine-community/inputs, var/rudder/cfengine-community/inputs.new,   /var/rudder/cfengine-community/inputs.bkp)
   * 3 - Write the file to /var/rudder/share/node-uuid/rules.new/cfengine-agentType
   * 4 - Check the promises there
   * Caution : a specific computation is done for the root server
   * @params
   *   nodePromisePath    : the path where the promises will be moved for the agent to fetch (finishing by /rules)
   *   newNodePromisesPath: the path where the promises will be written, before being checked (finishes by /rules.new)
   *   backupNodePath     : the path where the previous promises will be backuped

   * @return : a Set of node, final destination of promises, folder where promises are written (.new), backup folder (don't want to return duplicate)
   */
  private[this] def prepareRulesForAgents(
      nodePromisePath        : String
    , newNodePromisesPath    : String
    , backupNodePath         : String
    , node                   : NodeConfiguration
    , nodeConfigVersion      : NodeConfigId
    , rootNodeConfigId       : NodeId
    , templates              : Map[Cf3PromisesFileTemplateId, Cf3PromisesFileTemplateCopyInfo]
  ): Box[Set[(NodeConfiguration, String, String, String)]] = {

    logger.debug(s"Writting promises for node '${node.nodeInfo.hostname}' (${node.nodeInfo.id.value})")

    val folders = collection.mutable.Set[(NodeConfiguration, String, String, String)]()

    for (agentType <- node.nodeInfo.agentsName) {
      val varNova      = systemVariableSpecService.get("NOVA"     ).toVariable(if(agentType == NOVA_AGENT     ) Seq("true") else Seq())
      val varCommunity = systemVariableSpecService.get("COMMUNITY").toVariable(if(agentType == COMMUNITY_AGENT) Seq("true") else Seq())
      val varNodeConfigVersion = systemVariableSpecService.get("RUDDER_NODE_CONFIG_ID").toVariable(Seq(nodeConfigVersion.value))

      val systemVariables = (node.nodeContext
                            + (varNova.spec.name -> varNova)
                            + (varCommunity.spec.name -> varCommunity)
                            + (varNodeConfigVersion.spec.name -> varNodeConfigVersion)
                            )

      val (nodeRulePath, backupNodeRulePath, newNodeRulePath) = if(rootNodeConfigId == node.nodeInfo.id) {
        (pathComputer.getRootPath(agentType), pathComputer.getRootPath(agentType) + backupPostfix, pathComputer.getRootPath(agentType) + newPostfix)
      } else {
        (nodePromisePath + agentType.toRulesPath(), backupNodePath + agentType.toRulesPath(), newNodePromisesPath + agentType.toRulesPath()) // we'll want to move the root folders
      }

      val container = node.toContainer(newNodeRulePath)

      val tmls = prepareCf3PromisesFileTemplate(container, systemVariables.toMap, templates)

      logger.trace(s"Templates for node ${node.nodeInfo.id.value} prepared")

      logger.trace("Preparing reporting information from meta technique")
      val csv = prepareReportingDataForMetaTechnique(container)

      // write the promises of the current machine and set correct permission
      for { (activeTechniqueId, preparedTemplate) <- tmls } {
        writePromisesFiles(preparedTemplate.templatesToCopy , preparedTemplate.environmentVariables , newNodeRulePath, csv)
      }

      agentType match {
        case NOVA_AGENT => writeLicense(node, newNodeRulePath)
        case _ => ;
      }

      // Check the promises
      val (errorCode, errors) =  {
        if(logger.isDebugEnabled) {
          //current time millis for debuging info
          val now = System.currentTimeMillis
          val res = executeCfPromise(agentType, newNodeRulePath)
          val spent = System.currentTimeMillis - now
          logger.debug(s"` Execute cf-promise for '${newNodeRulePath}': ${spent}ms (${spent/1000}s)")
          res
        } else {
          executeCfPromise(agentType, newNodeRulePath)
        }
      }

      if (errorCode != 0) {
        /*
         * we want to put any cfengine error or output as a technical detail, because:
         * - between version of cf-agent, it does not seems consistant what goes to sterr and what goes to stdout
         * - even sdtout message can be quiet crytic, like:
         *   ''' Fatal cfengine error: Validation: Scalar item in built-in FnCall
         *       fileexists-arg => { Download a file } in rvalue is out of bounds
         *       (value should match pattern "?(/.*)) '''
         *
         * More over, the !errormessage! tag is used on the client side to split among "information"
         * and "error/technical details"
         */
        val completeErrorMsg = ( "The generated promises are invalid!errormessage!cf-promise check fails for promises generated at '%s'".format(newNodeRulePath)
                               + (if(errors.isEmpty) "" else errors.mkString("<-", "<-", ""))
                               )
        val failure = Failure(completeErrorMsg)
        logger.error(failure.messageChain.replace("!errormessage!", ": "))
        return failure
      }

      folders += ((node, nodeRulePath, newNodeRulePath, backupNodeRulePath))
    }

    Full(folders.toSet)

  }

  private[this] def executeCfPromise(agentType: AgentType, pathOfPromises: String) : (Int, List[String]) = {
    var out = List[String]()
    var err = List[String]()
    val processLogger = ProcessLogger((s) => out ::= s, (s) => err ::= s)

    val checkPromises =  agentType match {
      case NOVA_AGENT => novaCheckPromises
      case COMMUNITY_AGENT => communityCheckPromises
    }

    val errorCode = if (checkPromises != "/bin/true")  {
      val process = Process(checkPromises + " -f " + pathOfPromises + "/promises.cf", None, ("RES_OPTIONS","attempts:0"))
      process.!(processLogger)
    } else {
      //command is /bin/true => just return with no error (0)
      0
    }

    (errorCode, out.reverse ++ err.reverse)
  }

  /**
   * Force cf-serverd to reload its promises
   * It always succeeed, even if it fails it simply delays the reloading (automatic) by the server
   */
  def reloadCFEnginePromises() : Unit = {
    var out = List[String]()
    var err = List[String]()
    val processLogger = ProcessLogger((s) => out ::= s, (s) => err ::= s)

    val process: scala.sys.process.ProcessBuilder = (cfengineReloadPromises)

    val errorCode =  process.!(processLogger)

    if (errorCode != 0) {
      val errorMessage = s"""Failed to reload CFEngine server promises with command "${cfengineReloadPromises}" - cause is ${out.mkString(",")} ${err.mkString(",")}"""

      logger.warn(errorMessage)
    }
  }

}
