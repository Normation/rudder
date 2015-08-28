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

package com.normation.rudder.services.policies.write

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.services.policies.nodeconfig.NodeConfiguration

import net.liftweb.common.Box
import java.io.IOException
import java.io.File
import com.normation.cfclerk.services.SystemVariableSpecService
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.cfclerk.exceptions.VariableException
import com.normation.cfclerk.domain.Bundle
import com.normation.cfclerk.domain.Cf3PromisesFileTemplateId
import com.normation.cfclerk.domain.TrackerVariable
import com.normation.cfclerk.domain.SystemVariable
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.PARAMETER_VARIABLE
import com.normation.cfclerk.domain.SectionVariableSpec
import com.normation.cfclerk.domain.Cf3PromisesFileTemplate
import com.normation.cfclerk.domain.SystemVariableSpec
import com.normation.cfclerk.domain.Variable
import com.normation.cfclerk.domain.TrackerVariableSpec
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.{COMMUNITY_AGENT, NOVA_AGENT}
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.repository.LicenseRepository
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.policies.SystemVariableService
import com.normation.rudder.services.policies.nodeconfig.NodeConfiguration
import com.normation.rudder.services.reports.ReportingService
import com.normation.stringtemplate.language.NormationAmpersandTemplateLexer
import com.normation.stringtemplate.language.formatter.LocalTimeRenderer
import com.normation.stringtemplate.language.formatter.DateRenderer
import com.normation.stringtemplate.language.formatter.LocalDateRenderer
import com.normation.utils.Control.sequence
import com.normation.utils.Control.boxSequence
import org.antlr.stringtemplate.StringTemplate
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.apache.commons.io.FilenameUtils
import org.joda.time.LocalTime
import org.joda.time.LocalDate
import org.joda.time.DateTime
import net.liftweb.util.Helpers.tryo
import net.liftweb.common.Full
import net.liftweb.common.Box
import net.liftweb.common.Failure
import net.liftweb.common.Loggable
import net.liftweb.common.EmptyBox
import com.normation.rudder.services.policies.BundleOrder
import com.normation.rudder.services.policies.nodeconfig.NodeConfigurationLogger


/**
 * Write promises for the set of nodes, with the given configs.
 * Requires access to external templates files.
 */
trait Cf3PromisesFileWriterService {

  /**
   * Write templates for node configuration that changed since the last write.
   *
   */
  def writeTemplate(rootNodeId: NodeId, nodesToWrite: Set[NodeId], allNodeConfigs: Map[NodeId, NodeConfiguration], versions: Map[NodeId, NodeConfigId]) : Box[Seq[NodeConfiguration]]
}



class Cf3PromisesFileWriterServiceImpl(
    techniqueRepository      : TechniqueRepository
  , pathComputer             : PathComputer
  , licenseRepository        : LicenseRepository
  , logNodeConfig            : NodeConfigurationLogger
  , prepareTemplate          : PrepareTemplateVariables
  , communityCheckPromises   : String
  , novaCheckPromises        : String
  , cfengineReloadPromises   : String
) extends Cf3PromisesFileWriterService with Loggable {


  /**
   * Write templates for node configuration that changed since the last write.
   *
   */
  override def writeTemplate(
      rootNodeId    : NodeId
    , nodesToWrite  : Set[NodeId]
    , allNodeConfigs: Map[NodeId, NodeConfiguration]
    , versions      : Map[NodeId, NodeConfigId]
  ) : Box[Seq[NodeConfiguration]] = {

    val TAG_OF_RUDDER_ID = "@@RUDDER_ID@@"
    val GENEREATED_CSV_FILENAME = "rudder_expected_reports.csv"

    val newPostfix = ".new"
    val backupPostfix = ".bkp"


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
     */

    //utility method to parallelized computation. Does not seem safe...
    def sequencePar[U,T](seq:Seq[U])(f:U => Box[T]) : Box[Seq[T]] = {
      val buf = scala.collection.mutable.Buffer[T]()
      seq.par.foreach { u => f(u) match {
        case e:EmptyBox => return e
        case Full(x) => buf += x
      } }
      Full(buf)
    }

    for {
      configAndPaths   <- calculatePathsForNodeConfigurations(interestingNodeConfigs, rootNodeId, allNodeConfigs, newPostfix, backupPostfix)
      pathsInfo        =  configAndPaths.map { _.paths }
      templates        <- readTemplateFromFileSystem(techniqueIds)
      preparedPromises <- sequencePar(configAndPaths) { case agentNodeConfig =>
                           val nodeConfigId = versions(agentNodeConfig.config.nodeInfo.id)
                           prepareTemplate.prepareTemplateForAgentNodeConfiguration(agentNodeConfig, nodeConfigId, rootNodeId, templates, allNodeConfigs, TAG_OF_RUDDER_ID) ?~! "Error when preparing rules for agents"
                         }
      promiseWritten   <- sequencePar(preparedPromises) { prepared =>
                            writePromises(prepared._1, prepared._2, prepared._3, GENEREATED_CSV_FILENAME)
                          }
      licensesCopied   <- copyLicenses(configAndPaths)
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



  private[this] def readTemplateFromFileSystem(techniqueIds: Set[TechniqueId]) : Box[Map[Cf3PromisesFileTemplateId, Cf3PromisesFileTemplateCopyInfo]] = {

    //list of (template id, template out path)
    val templatesToRead = for {
      technique <- techniqueRepository.getByIds(techniqueIds.toSeq)
      template  <- technique.templates
    } yield {
      (template.id, template.outPath)
    }

    val now = System.currentTimeMillis()

    val res = (sequence(templatesToRead) { case (templateId, templateOutPath) =>
      for {
        copyInfo <- techniqueRepository.getTemplateContent(templateId) { optInputStream =>
          optInputStream match {
            case None =>
              Failure(s"Error when trying to open template '${templateId.toString}${Cf3PromisesFileTemplate.templateExtension}'. Check that the file exists and is correctly commited in Git, or that the metadata for the technique are corrects.")
            case Some(inputStream) =>
              logger.trace(s"Loading template ${templateId} (from an input stream relative to ${techniqueRepository}")
              //string template does not allows "." in path name, so we are force to use a templateGroup by polity template (versions have . in them)
              val content = IOUtils.toString(inputStream, "UTF-8")
              Full(Cf3PromisesFileTemplateCopyInfo(content, templateId, templateOutPath))
          }
        }
      } yield {
        (copyInfo.id, copyInfo)
      }
    }).map( _.toMap)

    logger.debug(s"${templatesToRead.size} promises templates read in ${System.currentTimeMillis-now}ms")

    res
  }

  private[this] def writePromises(
      paths                 : NodePromisesPaths
    , preparedTemplates     : Seq[PreparedTemplates]
    , expectedReportLines   : Seq[String]
    , expectedReportFilename: String
  ) : Box[NodePromisesPaths] = {
    // write the promises of the current machine and set correct permission
    for {
      _ <- sequence(preparedTemplates) { preparedTemplate =>
             sequence(preparedTemplate.templatesToCopy.toSeq) { template =>
               writePromisesFiles(template, preparedTemplate.environmentVariables , paths.newFolder, expectedReportLines, expectedReportFilename)
             }
           }
    } yield {
      paths
    }
  }

  /**
   * For agent needing it, copy licences to the correct path
   */
  private[this] def copyLicenses(agentNodeConfigurations: Seq[AgentNodeConfiguration]): Box[Seq[AgentNodeConfiguration]] = {

    sequence(agentNodeConfigurations) { case x @ AgentNodeConfiguration(config, agentType, paths) =>

      agentType match {
        case NOVA_AGENT =>
          logger.debug("Writing licence for nodeConfiguration  " + config.nodeInfo.id);
          val sourceLicenceNodeId = if(config.nodeInfo.isPolicyServer) {
            config.nodeInfo.id
          } else {
            config.nodeInfo.policyServerId
          }

          licenseRepository.findLicense(sourceLicenceNodeId) match {
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
      val (errorCode, errors) =  {
        if(logger.isDebugEnabled) {
          //current time millis for debuging info
          val now = System.currentTimeMillis
          val res = executeCfPromise(agentType, paths.newFolder)
          val spent = System.currentTimeMillis - now
          logger.debug(s"` Execute cf-promises for '$paths.newFolder}': ${spent}ms (${spent/1000}s)")
          res
        } else {
          executeCfPromise(agentType, paths.newFolder)
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
        val completeErrorMsg = ( s"The generated promises are invalid!errormessage!cf-promise check fails for promises generated at '${paths.newFolder}'"
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
   * It always succeeed, even if it fails it simply delays the reloading (automatic) by the server
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
   * Write the current seq of template file a the path location, replacing the variables found in variableSet
   * @param fileSet : the set of template to be written
   * @param variableSet : the set of variable
   * @param path : where to write the files
   */
  private[this] def writePromisesFiles(
      templateInfo          : Cf3PromisesFileTemplateCopyInfo
    , variableSet           : Seq[STVariable]
    , outPath               : String
    , expectedReportsLines  : Seq[String]
    , expectedReportFilename: String
  ): Box[String] = {

    //here, we need a big try/catch, because almost anything in string template can
    //throw errors

    try {

      //string template does not allows "." in path name, so we are force to use a templateGroup by policy template (versions have . in them)
      val template = new StringTemplate(templateInfo.source, classOf[NormationAmpersandTemplateLexer]);
      template.registerRenderer(classOf[DateTime], new DateRenderer());
      template.registerRenderer(classOf[LocalDate], new LocalDateRenderer());
      template.registerRenderer(classOf[LocalTime], new LocalTimeRenderer());

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
      val csvContent = expectedReportsLines.mkString("\n")

      for {
        _ <- tryo { FileUtils.writeStringToFile(new File(outPath, templateInfo.destination), template.toString) } ?~!
               s"Bad format in Technique ${templateInfo.id.techniqueId} (file: ${templateInfo.destination})"
        _ <- tryo { FileUtils.writeStringToFile(new File(outPath, expectedReportFilename), csvContent) } ?~!
               s"Bad format in Technique ${templateInfo.id.techniqueId} (file: ${expectedReportFilename})"
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

  private[this] def executeCfPromise(agentType: AgentType, pathOfPromises: String) : (Int, List[String]) = {
    import scala.sys.process.{Process, ProcessLogger}

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
