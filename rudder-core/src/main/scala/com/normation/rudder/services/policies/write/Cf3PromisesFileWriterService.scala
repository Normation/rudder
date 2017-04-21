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

package com.normation.rudder.services.policies.write

import java.io.File
import java.io.IOException
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueFile
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueResourceId
import com.normation.cfclerk.domain.TechniqueTemplate
import com.normation.cfclerk.exceptions.VariableException
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.COMMUNITY_AGENT
import com.normation.inventory.domain.NOVA_AGENT
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.repository.LicenseRepository
import com.normation.rudder.services.policies.nodeconfig.NodeConfiguration
import com.normation.rudder.services.policies.nodeconfig.NodeConfiguration
import com.normation.rudder.services.policies.nodeconfig.NodeConfigurationLogger
import com.normation.utils.Control._
import org.antlr.stringtemplate.StringTemplate
import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.IOUtils
import org.joda.time.DateTime
import org.joda.time.LocalDate
import org.joda.time.LocalTime
import net.liftweb.common._
import net.liftweb.util.Helpers.tryo
import com.normation.rudder.domain.licenses.NovaLicense
import scala.io.Codec
import com.normation.templates.FillTemplatesService
import com.normation.templates.STVariable
import com.normation.rudder.domain.nodes.NodeProperty
import net.liftweb.json.JsonAST.JValue
import scala.util.Try
import scala.util.Success
import scala.util.{Failure => FailTry}
import com.normation.rudder.domain.Constants
import net.liftweb.json.JsonAST
import net.liftweb.json.Printer
import com.normation.rudder.domain.policies.GlobalPolicyMode
import scala.language.postfixOps
import com.normation.rudder.hooks.RunHooks
import com.normation.rudder.hooks.HookEnvPairs
import com.normation.rudder.hooks.HooksLogger
import monix.execution.Scheduler
import monix.eval.TaskSemaphore
import monix.eval.Task
import scala.concurrent.Await
import monix.execution.ExecutionModel

/**
 * Write promises for the set of nodes, with the given configs.
 * Requires access to external templates files.
 */
trait Cf3PromisesFileWriterService {

  /**
   * Write templates for node configuration that changed since the last write.
   *
   */
  def writeTemplate(
      rootNodeId    : NodeId
    , nodesToWrite  : Set[NodeId]
    , allNodeConfigs: Map[NodeId, NodeConfiguration]
    , versions      : Map[NodeId, NodeConfigId]
    , allLicenses   : Map[NodeId, NovaLicense]
    , globalPolicyMode: GlobalPolicyMode
    , generationTime  : DateTime
  ) : Box[Seq[NodeConfiguration]]
}

class Cf3PromisesFileWriterServiceImpl(
    techniqueRepository  : TechniqueRepository
  , pathComputer         : PathComputer
  , logNodeConfig        : NodeConfigurationLogger
  , prepareTemplate      : PrepareTemplateVariables
  , fillTemplates        : FillTemplatesService
  , HOOKS_D              : String
  , HOOKS_IGNORE_SUFFIXES: List[String]
) extends Cf3PromisesFileWriterService with Loggable {

  val TAG_OF_RUDDER_ID = "@@RUDDER_ID@@"
  val GENEREATED_CSV_FILENAME = "rudder_expected_reports.csv"

  val newPostfix = ".new"
  val backupPostfix = ".bkp"

  private[this] def writeNodePropertiesFile (agentNodeConfig: AgentNodeConfiguration) = {

    def generateNodePropertiesJson(properties : Seq[NodeProperty]): JValue = {
      import net.liftweb.json.JsonDSL._
      import com.normation.rudder.domain.nodes.JsonSerialisation._
      ( "properties" -> properties.toDataJson())
    }

    val fileName = Constants.GENEREATED_PROPERTY_FILE
    val path = Constants.GENERATED_PROPERTY_DIR
    val jsonProperties = generateNodePropertiesJson(agentNodeConfig.config.nodeInfo.properties)
    val propertyContent = JsonAST.prettyRender(jsonProperties)
    logger.trace(s"Create node properties file '${agentNodeConfig.paths.newFolder}/${path}/${fileName}'")
    Try {
      val propertyFile = new File ( new File (agentNodeConfig.paths.newFolder, path), fileName)
      FileUtils.writeStringToFile(propertyFile,propertyContent)
    } match {
      case FailTry(e) =>
        val message = s"could not write ${fileName} file, cause is: ${e.getMessage}"
        Failure(message)
      case Success(_) =>
        Full(agentNodeConfig)
    }
  }

  /*
   * For all the writing part, we want to limit the number of concurrent workers on two aspects:
   * - we want an I/O threadpool, which knows what to do when a thread is blocked for a long time,
   *   and risks thread exaustion
   *   (it can happens, since we have a number of files to write, some maybe big)
   * - we want to limit the total number of concurrent execution to avoid blowming up the number
   *   of open files, concurrent hooks, etc. This is not directly linked to the number of CPU,
   *   but clearly there is no point having a pool of 4 threads for 1000 nodes which will
   *   fork 1000 times for hooks - even if there is 4 threads used for that.
   *
   * That means that we want something looking like a sequencePar interface, but with:
   * - a common thread pool, i/o oriented
   * - a common max numbers of concurrent tasks.
   *   I believe it should be common to all steps because it's mostly on the same machine - but
   *   for now, that doesn't really matter, since each step is bloking (ie wait before next).
   *   A parallelism around the thread pool sizing (by default, number of CPU) seems ok.
   *
   * here, f must not throws execption.
   *
   */
  def parrallelSequence[U,T](seq: Seq[U])(f:U => Box[T])(implicit scheduler: Scheduler, semaphore: TaskSemaphore): Box[Seq[T]] = {
    import scala.concurrent.duration._
    // compact format a Failure(msg1, Failure(msg2, ...)) in to a Failure("msg1; msg2")
    def compactFailure[A](b: Box[A]): Box[A] = {
      b match {
        case f:Failure =>
          Failure(f.messageChain.replaceAll("<-", ";"), f.rootExceptionCause, Empty)
        case x => x
      }
    }

    //transform the sequence in tasks, gather results unordered
    val tasks = Task.gather(seq.map { action =>
      semaphore.greenLight(Task(f(action)))
    })

   // give a timeout for the whole tasks sufficiently large.
   // Hint: CF-promise taking 2s by node, for 10 000 nodes, on
   // 4 cores => ~85 minutes...
   // It is here mostly as a safeguard for generation which went wrong -
   // we will already have timeout at the thread level for stalling threads.
   val timeout = 2.hours

   //exec
   for {
     updated       <- tryo(Await.result(tasks.runAsync, timeout))
     gatherErrors  <- compactFailure(bestEffort(updated)(identity))
   } yield {
     gatherErrors
   }
  }

  /**
   * Write templates for node configuration that changed since the last write.
   *
   */
  override def writeTemplate(
      rootNodeId      : NodeId
    , nodesToWrite    : Set[NodeId]
    , allNodeConfigs  : Map[NodeId, NodeConfiguration]
    , versions        : Map[NodeId, NodeConfigId]
    , allLicenses     : Map[NodeId, NovaLicense]
    , globalPolicyMode: GlobalPolicyMode
    , generationTime  : DateTime
  ) : Box[Seq[NodeConfiguration]] = {

    val nodeConfigsToWrite = allNodeConfigs.filterKeys(nodesToWrite.contains(_))
    val interestingNodeConfigs = allNodeConfigs.filterKeys(k => nodeConfigsToWrite.exists{ case(x, _) => x == k }).values.toSeq
    val techniqueIds = interestingNodeConfigs.flatMap( _.getTechniqueIds ).toSet

    //debug - but don't fails for debugging !
    logNodeConfig.log(nodeConfigsToWrite.values.toSeq) match {
      case eb:EmptyBox =>
        val e = eb ?~! "Error when trying to write node configurations for debugging"
        logger.error(e)
        e.rootExceptionCause.foreach { ex =>
          logger.error("Root exception cause was:", ex)
        }
      case _ => //nothing to do
    }

    /*
     * Here come the general writing process
     *
     * The general algorithm flow as following:
     * - for all nodes, for each of its agent, build path and configuration (both node&agent related)
     * - then, for each node/agent, prepare for each techniques the context variable to use, and the expected reports file to construct
     * - then, actually write things. For a node/agent, write into the ".new" directory:
     *   - for each technique:
     *        - the corresponding promises with templates filled
     *       - other resources files
     *   - the expected reports file
     *   - the bundle file
     * - then, copy the license, if applicable
     * - then, check generated promises and changes file permissions
     * - and finally, move everything to each node rules directory
     */

    //we need to add the current environment variables to the script context
    //plus the script environment variables used as script parameters
    import scala.collection.JavaConverters._
    val systemEnv = HookEnvPairs.build(System.getenv.asScala.toSeq:_*)

    implicit val semaphore = TaskSemaphore(maxParallelism = {
      //the same logic than the one used for scala pool size is used to keep some consistency
      //by default, use the number of core, or the configured number.
      //see https://github.com/scala/scala/blob/2.12.x/src/library/scala/concurrent/impl/ExecutionContextImpl.scala#L92
      (try System.getProperty("scala.concurrent.context.maxThreads", "x1") catch {
        case e: SecurityException => "x1"
      }) match {
        case s if s.charAt(0) == 'x' => (Runtime.getRuntime.availableProcessors * s.substring(1).toDouble).ceil.toInt
        case other => other.toInt
      }})
    implicit val scheduler = Scheduler.io(executionModel = ExecutionModel.AlwaysAsyncExecution)

    for {
      configAndPaths   <- calculatePathsForNodeConfigurations(interestingNodeConfigs, rootNodeId, allNodeConfigs, newPostfix, backupPostfix)
      pathsInfo        =  configAndPaths.map { _.paths }
      templates        <- readTemplateFromFileSystem(techniqueIds)
      preparedPromises <- parrallelSequence(configAndPaths) { case agentNodeConfig =>
                           val nodeConfigId = versions(agentNodeConfig.config.nodeInfo.id)
                           prepareTemplate.prepareTemplateForAgentNodeConfiguration(agentNodeConfig, nodeConfigId, rootNodeId, templates, allNodeConfigs, TAG_OF_RUDDER_ID, globalPolicyMode)
                         }
      promiseWritten   <- parrallelSequence(preparedPromises) { prepared =>
                            for {
                              _ <- writePromises(prepared.paths, prepared.preparedTechniques)
                              _ <- writeExpectedReportsCsv(prepared.paths, prepared.expectedReportsCsv, GENEREATED_CSV_FILENAME)
                            } yield {
                              "OK"
                            }
                          }
     propertiesWritten <- parrallelSequence(configAndPaths) { case agentNodeConfig =>
                            writeNodePropertiesFile(agentNodeConfig) ?~!
                              s"An error occured while writing property file for Node ${agentNodeConfig.config.nodeInfo.hostname} (id: ${agentNodeConfig.config.nodeInfo.id.value}"
                          }
      licensesCopied   <- copyLicenses(configAndPaths, allLicenses)
      nodePreMvHooks   <- RunHooks.getHooks(HOOKS_D + "/policy-generation-node-ready", HOOKS_IGNORE_SUFFIXES)
      preMvHooks       <- parrallelSequence(configAndPaths) { agentNodeConfig =>
                            val timeHooks = System.currentTimeMillis
                            val nodeId = agentNodeConfig.config.nodeInfo.node.id.value
                            val res = RunHooks.syncRun(
                                          nodePreMvHooks
                                        , HookEnvPairs.build(
                                                                 ("RUDDER_GENERATION_DATETIME", generationTime.toString)
                                                               , ("RUDDER_NODEID", nodeId)
                                                               , ("RUDDER_NEXT_POLICIES_DIRECTORY", agentNodeConfig.paths.newFolder)
                                                               , ("RUDDER_AGENT_TYPE", agentNodeConfig.agentType.tagValue)
                                                             )
                                        , systemEnv
                            )
                            HooksLogger.trace(s"Run post-generation pre-move hooks for node '${nodeId}' in ${System.currentTimeMillis - timeHooks} ms")
                            Full(res)
                          }
      movedPromises    <- tryo { movePromisesToFinalPosition(pathsInfo) }
      nodePostMvHooks  <- RunHooks.getHooks(HOOKS_D + "/policy-generation-node-finished", HOOKS_IGNORE_SUFFIXES)
      postMvHooks      <- parrallelSequence(configAndPaths) { agentNodeConfig =>
                            val timeHooks = System.currentTimeMillis
                            val nodeId = agentNodeConfig.config.nodeInfo.node.id.value
                            val res = RunHooks.syncRun(
                                          nodePostMvHooks
                                        , HookEnvPairs.build(
                                                               ("RUDDER_GENERATION_DATETIME", generationTime.toString)
                                                             , ("RUDDER_NODEID", nodeId)
                                                             , ("RUDDER_POLICIES_DIRECTORY", agentNodeConfig.paths.baseFolder)
                                                             , ("RUDDER_AGENT_TYPE", agentNodeConfig.agentType.tagValue)
                                                           )
                                        , systemEnv
                            )
                            HooksLogger.trace(s"Run post-generation post-move hooks for node '${nodeId}' in ${System.currentTimeMillis - timeHooks} ms")
                            Full(res)
                          }
    } yield {
      val ids = movedPromises.map { _.nodeId }.toSet
      allNodeConfigs.filterKeys { id => ids.contains(id) }.values.toSeq
    }

  }

  ///////////// implementation of each step /////////////

  /**
   * Calculate path for node configuration.
   * Path are agent dependant, so from that point, all
   * node configuration are also identified by agent.
   * Note that a node without any agent configured won't
   * have any promise written.
   */
  private[this] def calculatePathsForNodeConfigurations(
      configs            : Seq[NodeConfiguration]
    , rootNodeConfigId   : NodeId
    , allNodeConfigs     : Map[NodeId, NodeConfiguration]
    , newsFileExtension  : String
    , backupFileExtension: String
  ): Box[Seq[AgentNodeConfiguration]] = {

    val agentConfig = configs.flatMap { config =>
      if(config.nodeInfo.agentsName.size == 0) {
        logger.info(s"Node '${config.nodeInfo.hostname}' (${config.nodeInfo.id.value}) has no agent type configured and so no promises will be generated")
      }
      config.nodeInfo.agentsName.map {agentType => (agentType, config) }
    }

    sequence( agentConfig )  { case (agentInfo, config) =>
      val agentType = agentInfo.name
      for {
        paths <- if(rootNodeConfigId == config.nodeInfo.id) {
                    Full(NodePromisesPaths(
                        config.nodeInfo.id
                      , pathComputer.getRootPath(agentType)
                      , pathComputer.getRootPath(agentType) + newsFileExtension
                      , pathComputer.getRootPath(agentType) + backupFileExtension
                    ))
                  } else {
                    pathComputer.computeBaseNodePath(config.nodeInfo.id, rootNodeConfigId, allNodeConfigs.mapValues(_.nodeInfo)).map { case NodePromisesPaths(id, base, news, backup) =>
                        val postfix = agentType.toRulesPath
                        NodePromisesPaths(id, base + postfix, news + postfix, backup + postfix)
                    }
                  }
      } yield {
        AgentNodeConfiguration(config, agentType, paths)
      }
    }
  }

  private[this] def readTemplateFromFileSystem(
      techniqueIds: Set[TechniqueId]
  )(implicit scheduler: Scheduler, semaphore: TaskSemaphore): Box[Map[TechniqueResourceId, TechniqueTemplateCopyInfo]] = {

    //list of (template id, template out path)
    val templatesToRead = for {
      technique <- techniqueRepository.getByIds(techniqueIds.toSeq)
      template  <- technique.templates
    } yield {
      (template.id, template.outPath)
    }

    val now = System.currentTimeMillis()

    val res = (parrallelSequence(templatesToRead) { case (templateId, templateOutPath) =>
      for {
        copyInfo <- techniqueRepository.getTemplateContent(templateId) { optInputStream =>
          optInputStream match {
            case None =>
              Failure(s"Error when trying to open template '${templateId.toString}'. Check that the file exists with a ${TechniqueTemplate.templateExtension} extension and is correctly commited in Git, or that the metadata for the technique are corrects.")
            case Some(inputStream) =>
              logger.trace(s"Loading template: ${templateId}")
              //string template does not allows "." in path name, so we are force to use a templateGroup by polity template (versions have . in them)
              val content = IOUtils.toString(inputStream, Codec.UTF8.charSet)
              Full(TechniqueTemplateCopyInfo(templateId, templateOutPath, content))
          }
        }
      } yield {
        (copyInfo.id, copyInfo)
      }
    }).map( _.toMap)

    logger.debug(s"${templatesToRead.size} promises templates read in ${System.currentTimeMillis-now} ms")
    res
  }

  private[this] def writeExpectedReportsCsv(paths: NodePromisesPaths, csv: ExpectedReportsCsv, csvFilename: String): Box[String] = {
    val path = new File(paths.newFolder, csvFilename)
    for {
        _ <- tryo { FileUtils.writeStringToFile(path, csv.lines.mkString("\n"), Codec.UTF8.charSet) } ?~!
               s"Can not write the expected reports CSV file at path '${path.getAbsolutePath}'"
    } yield {
      path.getAbsolutePath
    }
  }

  private[this] def writePromises(
      paths            : NodePromisesPaths
    , preparedTechniques: Seq[PreparedTechnique]
  ) : Box[NodePromisesPaths] = {
    // write the promises of the current machine and set correct permission
    for {
      _ <- sequence(preparedTechniques) { preparedTechnique =>
             for {
               templates <-  sequence(preparedTechnique.templatesToProcess.toSeq) { template =>
                               writePromisesFiles(template, preparedTechnique.environmentVariables , paths.newFolder)
                             }
               files     <- sequence(preparedTechnique.filesToCopy.toSeq) { file =>
                              copyResourceFile(file, paths.newFolder)
                            }
             } yield {
               "OK"
             }
           }
    } yield {
      paths
    }
  }

  /**
   * For agent needing it, copy licences to the correct path
   */
  private[this] def copyLicenses(agentNodeConfigurations: Seq[AgentNodeConfiguration], licenses: Map[NodeId, NovaLicense]): Box[Seq[AgentNodeConfiguration]] = {

    sequence(agentNodeConfigurations) { case x @ AgentNodeConfiguration(config, agentType, paths) =>

      agentType match {
        case NOVA_AGENT =>
          logger.debug("Writing licence for nodeConfiguration  " + config.nodeInfo.id);
          val sourceLicenceNodeId = if(config.nodeInfo.isPolicyServer) {
            config.nodeInfo.id
          } else {
            config.nodeInfo.policyServerId
          }

          licenses.get(sourceLicenceNodeId) match {
            case None =>
              // we are in the "free case", just log-debug it (as we already informed the user that there is no license)
              logger.info(s"Not copying missing license file into '${paths.newFolder}' for node '${config.nodeInfo.hostname}' (${config.nodeInfo.id.value}).")
              Full(x)

            case Some(license) =>
              val licenseFile = new File(license.file)
              if (licenseFile.exists) {
                val destFile = FilenameUtils.normalize(paths.newFolder + "/license.dat")
                  tryo { FileUtils.copyFile(licenseFile, new File(destFile) ) }.map( _ => x)
              } else {
                logger.error(s"Could not find the license file ${licenseFile.getAbsolutePath} for server ${sourceLicenceNodeId.value}")
                throw new Exception("Could not find license file " +license.file)
              }
          }

        case _ => Full(x)
      }
    }
  }

  /**
   * Move the generated promises from the new folder to their final folder, backuping previous promises in the way
   * @param folder : (Container identifier, (base folder, new folder of the policies, backup folder of the policies) )
   */
  private[this] def movePromisesToFinalPosition(folders: Seq[NodePromisesPaths]): Seq[NodePromisesPaths] = {
    // We need to sort the folders by "depth", so that we backup and move the deepest one first
    val sortedFolder = folders.sortBy(x => x.baseFolder.count(_ =='/')).reverse

    val newFolders = scala.collection.mutable.Buffer[NodePromisesPaths]()
    try {
      // Folders is a map of machine.uuid -> (base_machine_folder, backup_machine_folder, machine)
      for (folder @ NodePromisesPaths(_, baseFolder, newFolder, backupFolder) <- sortedFolder) {
        // backup old promises
        logger.trace("Backuping old promises from %s to %s ".format(baseFolder, backupFolder))
        backupNodeFolder(baseFolder, backupFolder)
        try {
          newFolders += folder

          logger.trace("Copying new promises into %s ".format(baseFolder))
          // move new promises
          moveNewNodeFolder(newFolder, baseFolder)

        } catch {
          case ex: Exception =>
            logger.error("Could not write promises into %s, reason : ".format(baseFolder), ex)
            throw ex
        }
      }
      folders
    } catch {
      case ex: Exception =>

        for (folder <- newFolders) {
          logger.info("Restoring old promises on folder %s".format(folder.baseFolder))
          try {
            restoreBackupNodeFolder(folder.baseFolder, folder.backupFolder);
          } catch {
            case ex: Exception =>
              logger.error("could not restore old promises into %s ".format(folder.baseFolder))
              throw ex
          }
        }
        throw ex
    }

  }

  ///////////// utilities /////////////

  /**
   * Copy a resource file from a technique to the node promises directory
   */
  private[this] def copyResourceFile(file: TechniqueFile, rulePath: String): Box[String] = {
    val destination = new File(rulePath+"/"+file.outPath)

    techniqueRepository.getFileContent(file.id) { optStream =>
      optStream match {
        case None => Failure(s"Can not open the technique reource file ${file.id} for reading")
        case Some(s) =>
          try {
            FileUtils.copyInputStreamToFile(s, destination)
            Full(destination.getAbsolutePath)
          } catch {
            case ex: Exception => Failure(s"Error when copying technique resoure file '${file.id}' to '${destination.getAbsolutePath}')", Full(ex), Empty)
          }
      }

    }
  }

  /**
   * Write the current seq of template file a the path location, replacing the variables found in variableSet
   * @param fileSet : the set of template to be written
   * @param variableSet : the set of variable
   * @param path : where to write the files
   */
  private[this] def writePromisesFiles(
      templateInfo          : TechniqueTemplateCopyInfo
    , variableSet           : Seq[STVariable]
    , outPath               : String
  ): Box[String] = {

    //here, we need a big try/catch, because almost anything in string template can
    //throw errors
    // write the files to the new promise folder
    logger.trace(s"Create promises file ${outPath} ${templateInfo.destination}")

    for {
      replaced <- fillTemplates.fill(templateInfo.destination, templateInfo.content, variableSet)
      _        <- tryo { FileUtils.writeStringToFile(new File(outPath, templateInfo.destination), replaced, Codec.UTF8.charSet) } ?~!
                    s"Bad format in Technique ${templateInfo.id.toString} (file: ${templateInfo.destination})"
    } yield {
      outPath
    }
  }

  /**
   * Move the machine promises folder  to the backup folder
   * @param machineFolder
   * @param backupFolder
   */
  private[this] def backupNodeFolder(nodeFolder: String, backupFolder: String): Unit = {
    val src = new File(nodeFolder)
    if (src.isDirectory()) {
      val dest = new File(backupFolder)
      if (dest.isDirectory) {
        // force deletion of previous backup
        FileUtils.forceDelete(dest)
      }
      FileUtils.moveDirectory(src, dest)
    }
  }

  /**
   * Move the newly created folder to the final location
   * @param newFolder : where the promises have been written
   * @param nodeFolder : where the promises will be
   */
  private[this] def moveNewNodeFolder(sourceFolder: String, destinationFolder: String): Unit = {
    val src = new File(sourceFolder)

    logger.trace("Moving folders from %s to %s".format(src, destinationFolder))

    if (src.isDirectory()) {
      val dest = new File(destinationFolder)

      if (dest.isDirectory) {
        // force deletion of previous promises
        FileUtils.forceDelete(dest)
      }
      FileUtils.moveDirectory(src, dest)

      // force deletion of dandling new promise folder
      if ( (src.getParentFile().isDirectory) && (src.getParent().endsWith("rules.new"))) {
        FileUtils.forceDelete(src.getParentFile())
      }
    } else {
      logger.error("Could not find freshly created promises at %s".format(sourceFolder))
      throw new IOException("Created promises not found !!!!")
    }
  }

  /**
   * Restore (by moving) backup folder to its original location
   * @param machineFolder
   * @param backupFolder
   */
  private[this] def restoreBackupNodeFolder(nodeFolder: String, backupFolder: String): Unit = {
    val src = new File(backupFolder)
    if (src.isDirectory()) {
      val dest = new File(nodeFolder)
      // force deletion of invalid promises
      FileUtils.forceDelete(dest)

      FileUtils.moveDirectory(src, dest)
    } else {
      logger.error("Could not find freshly backup promises at %s".format(backupFolder))
      throw new IOException("Backup promises could not be found, and valid promises couldn't be restored !!!!")
    }
  }
}
