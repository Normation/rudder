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
import com.normation.stringtemplate.language.NormationAmpersandTemplateLexer
import com.normation.stringtemplate.language.formatter.DateRenderer
import com.normation.stringtemplate.language.formatter.LocalDateRenderer
import com.normation.stringtemplate.language.formatter.LocalTimeRenderer
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
import com.normation.rudder.domain.nodes.NodeProperty
import net.liftweb.json.JsonAST.JValue
import scala.util.Try
import scala.util.Success
import scala.util.{Failure => FailTry}
import com.normation.rudder.domain.Constants
import net.liftweb.json.JsonAST
import net.liftweb.json.Printer

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
  ) : Box[Seq[NodeConfiguration]]
}

class Cf3PromisesFileWriterServiceImpl(
    techniqueRepository      : TechniqueRepository
  , pathComputer             : PathComputer
  , logNodeConfig            : NodeConfigurationLogger
  , prepareTemplate          : PrepareTemplateVariables
  , communityCheckPromises   : String
  , novaCheckPromises        : String
  , cfengineReloadPromises   : String
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
    val propertyContent = Printer.pretty(JsonAST.render(jsonProperties))
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

  /**
   * Write templates for node configuration that changed since the last write.
   *
   */
  override def writeTemplate(
      rootNodeId    : NodeId
    , nodesToWrite  : Set[NodeId]
    , allNodeConfigs: Map[NodeId, NodeConfiguration]
    , versions      : Map[NodeId, NodeConfigId]
    , allLicenses   : Map[NodeId, NovaLicense]
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
     *   	   - the corresponding promises with templates filled
     *       - other resources files
     *   - the expected reports file
     *   - the bundle file
     * - then, copy the license, if applicable
     * - then, check generated promises and changes file permissions
     * - and finally, move everything to each node rules directory
     */

    for {
      configAndPaths   <- calculatePathsForNodeConfigurations(interestingNodeConfigs, rootNodeId, allNodeConfigs, newPostfix, backupPostfix)
      pathsInfo        =  configAndPaths.map { _.paths }
      templates        <- readTemplateFromFileSystem(techniqueIds)
      preparedPromises <- sequencePar(configAndPaths) { case agentNodeConfig =>
                           val nodeConfigId = versions(agentNodeConfig.config.nodeInfo.id)
                           Full(prepareTemplate.prepareTemplateForAgentNodeConfiguration(agentNodeConfig, nodeConfigId, rootNodeId, templates, allNodeConfigs, TAG_OF_RUDDER_ID))
                         }
      promiseWritten   <- sequencePar(preparedPromises) { prepared =>
                            for {
                              _ <- writePromises(prepared.paths, prepared.preparedTechniques)
                              _ <- writeExpectedReportsCsv(prepared.paths, prepared.expectedReportsCsv, GENEREATED_CSV_FILENAME)
                            } yield {
                              "OK"
                            }
                          }
     propertiesWritten <- sequencePar(configAndPaths) { case  agentNodeConfig =>
                            writeNodePropertiesFile(agentNodeConfig) ?~!
                              s"An error occured while writing property file for Node ${agentNodeConfig.config.nodeInfo.hostname} (id: ${agentNodeConfig.config.nodeInfo.id.value}"
                          }
      licensesCopied   <- copyLicenses(configAndPaths, allLicenses)
      checked          <- checkGeneratedPromises(configAndPaths.map { x => (x.agentType, x.paths) })
      permChanges      <- sequencePar(pathsInfo) { nodePaths =>
                            setCFEnginePermission(nodePaths.newFolder) ?~! "cannot change permission on destination folder"
                          }
      movedPromises    <- tryo { movePromisesToFinalPosition(pathsInfo) }
    } yield {

      //the reload is just a cool simplification, it's not mandatory -
      // - at least it does not failed the whole generation process
      reloadCFEnginePromises()

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

    sequence( agentConfig )  { case (agentType, config) =>
      for {
        paths <- if(rootNodeConfigId == config.nodeInfo.id) {
                    Full(NodePromisesPaths(
                        config.nodeInfo.id
                      , pathComputer.getRootPath(agentType)
                      , pathComputer.getRootPath(agentType) + newsFileExtension
                      , pathComputer.getRootPath(agentType) + backupFileExtension
                    ))
                  } else {
                    pathComputer.computeBaseNodePath(config.nodeInfo.id, rootNodeConfigId, allNodeConfigs).map { case NodePromisesPaths(id, base, news, backup) =>
                        val postfix = agentType.toRulesPath
                        NodePromisesPaths(id, base + postfix, news + postfix, backup + postfix)
                    }
                  }
      } yield {
        AgentNodeConfiguration(config, agentType, paths)
      }
    }
  }

  private[this] def readTemplateFromFileSystem(techniqueIds: Set[TechniqueId]) : Box[Map[TechniqueResourceId, TechniqueTemplateCopyInfo]] = {

    //list of (template id, template out path)
    val templatesToRead = for {
      technique <- techniqueRepository.getByIds(techniqueIds.toSeq)
      template  <- technique.templates
    } yield {
      (template.id, template.outPath)
    }

    val now = System.currentTimeMillis()

    val res = (sequencePar(templatesToRead) { case (templateId, templateOutPath) =>
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
   * For each path of generated promises, for a given agent type, check if the promises are
   * OK. At least execute cf-promises, but could also check sum and the like.
   */
  private[this] def checkGeneratedPromises(toCheck: Seq[(AgentType, NodePromisesPaths)]): Box[Seq[NodePromisesPaths]] = {

    sequence(toCheck) { case (agentType, paths) =>
      // Check the promises
      val (errorCode, errors, checkCommand) =  {
        if(logger.isDebugEnabled) {
          //current time millis for debuging info
          val now = System.currentTimeMillis
          val res = executeCfPromise(agentType, paths.newFolder)
          val spent = System.currentTimeMillis - now
          logger.debug(s"` Execute cf-promises for '${paths.newFolder}': ${spent} ms (${spent/1000} s)")
          res
        } else {
          executeCfPromise(agentType, paths.newFolder)
        }
      }

      if (errorCode != 0) {
        /*
         * we want to put any cfengine error or output as a technical detail, because:
         * - between version of cf-agent, it does not seems consistant what goes to sterr and what goes to stdout
         * - even stdout messages can be quite cryptic, like:
         *   ''' Fatal cfengine error: Validation: Scalar item in built-in FnCall
         *       fileexists-arg => { Download a file } in rvalue is out of bounds
         *       (value should match pattern "?(/.*)) '''
         *
         * More over, the !errormessage! tag is used on the client side to split among "information"
         * and "error/technical details"
         */
        val completeErrorMsg = ( s"The generated promises are invalid!errormessage!cf-promise check fails for promises generated at '${paths.newFolder}'"
                               + "<-Command to check generated promises is: '" + checkCommand + "'"
                               + (if(errors.isEmpty) "" else errors.mkString("<-", "<-", ""))
                               )
        val failure = Failure(completeErrorMsg)
        logger.error(failure.messageChain.replace("!errormessage!", ": "))
        failure
      } else {
        Full(paths)
      }
    }
  }

  // CFEngine will not run automatically if someone else than user can write the promise
  private[this] def setCFEnginePermission (baseFolder:String ) : Box[String] = {

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

  /**
   * Force cf-serverd to reload its promises
   * It always succeed, even if it fails it simply delays the reloading (automatic) by the server
   */
  private[this] def reloadCFEnginePromises() : Box[Unit] = {
    import scala.sys.process.{ProcessBuilder, ProcessLogger}
    var out = List[String]()
    var err = List[String]()
    val processLogger = ProcessLogger((s) => out ::= s, (s) => err ::= s)

    val process: ProcessBuilder = (cfengineReloadPromises)

    val errorCode =  process.!(processLogger)

    if (errorCode != 0) {
      val errorMessage = s"""Failed to reload CFEngine server promises with command "${cfengineReloadPromises}" - cause is ${out.mkString(",")} ${err.mkString(",")}"""
      logger.warn(errorMessage)
      Failure(errorMessage)
    } else {
      Full( () )
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

    try {

      //string template does not allows "." in path name, so we are force to use a templateGroup by policy template (versions have . in them)
      val template = new StringTemplate(templateInfo.content, classOf[NormationAmpersandTemplateLexer])
      template.registerRenderer(classOf[DateTime], new DateRenderer())
      template.registerRenderer(classOf[LocalDate], new LocalDateRenderer())
      template.registerRenderer(classOf[LocalTime], new LocalTimeRenderer())

      for (variable <- variableSet) {
        // Only System Variables have nullable entries
        if ( variable.isSystem && variable.mayBeEmpty &&
            ( (variable.values.size == 0) || (variable.values.size ==1 && variable.values.head == "") ) ) {
          template.setAttribute(variable.name, null)
        } else if (!variable.mayBeEmpty && variable.values.size == 0) {
          throw new VariableException(s"Mandatory variable ${variable.name} is empty, can not write ${templateInfo.destination}")
        } else {
          logger.trace(s"Adding variable ${outPath + "/" + templateInfo.destination} : ${variable.name} values ${variable.values.mkString("[",",","]")}")
          variable.values.foreach { value => template.setAttribute(variable.name, value)
          }
        }
      }

      // write the files to the new promise folder
      logger.trace("Create promises file %s %s".format(outPath, templateInfo.destination))

      for {
        _ <- tryo { FileUtils.writeStringToFile(new File(outPath, templateInfo.destination), template.toString, Codec.UTF8.charSet) } ?~!
               s"Bad format in Technique ${templateInfo.id.toString} (file: ${templateInfo.destination})"
      } yield {
        outPath
      }

    } catch {
      case ex: Exception =>
        val m = "Writing promises error in file template " + templateInfo
        logger.error(m, ex)
        Failure(m)
    }

  }

  private[this] def executeCfPromise(agentType: AgentType, pathOfPromises: String) : (Int, List[String], String) = {
    import scala.sys.process.{Process, ProcessLogger}

    var out = List[String]()
    var err = List[String]()
    val processLogger = ProcessLogger((s) => out ::= s, (s) => err ::= s)

    val checkPromises =  agentType match {
      case NOVA_AGENT => novaCheckPromises
      case COMMUNITY_AGENT => communityCheckPromises
    }

    val checkCommand = checkPromises + " -f " + pathOfPromises + "/promises.cf"

    val errorCode = if (checkPromises != "/bin/true")  {
      val process = Process(checkCommand, None, ("RES_OPTIONS","attempts:0"))
      process.!(processLogger)
    } else {
      //command is /bin/true => just return with no error (0)
      0
    }

    (errorCode, out.reverse ++ err.reverse, checkCommand)
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
