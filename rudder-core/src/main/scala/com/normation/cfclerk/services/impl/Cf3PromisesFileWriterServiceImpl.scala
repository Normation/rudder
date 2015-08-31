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

package com.normation.cfclerk.services.impl

import com.normation.cfclerk.services._
import com.normation.cfclerk.domain._
import com.normation.cfclerk.exceptions._
import com.normation.stringtemplate.language._
import com.normation.stringtemplate.language.formatter._
import java.io._
import net.liftweb.common._
import org.antlr.stringtemplate._
import org.antlr.stringtemplate.language._
import org.apache.commons.io.{IOUtils,FileUtils}
import org.joda.time._
import org.xml.sax.SAXParseException
import scala.io.Source
import scala.xml._

import com.normation.utils.Control.sequence

/**
 * The class that handles all the writing of templates
 *
 */
class Cf3PromisesFileWriterServiceImpl(
    techniqueRepository      : TechniqueRepository
  , systemVariableSpecService: SystemVariableSpecService
  ) extends Cf3PromisesFileWriterService with Loggable {

  logger.trace("Repository loaded")

  private[this] val generationTimestampVariable = "GENERATIONTIMESTAMP"
  private[this] val GENEREATED_CSV_FILENAME = "rudder_expected_reports.csv"
  private[this] val TAG_OF_RUDDER_ID = "@@RUDDER_ID@@"


  override def readTemplateFromFileSystem(techniqueIds: Set[TechniqueId]) : Box[Map[Cf3PromisesFileTemplateId, Cf3PromisesFileTemplateCopyInfo]] = {

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

  /**
   * Compute the TMLs list to be written
   * @param container : the container of the policies we want to write
   * @param extraVariables : optional : extra system variables that we could want to add
   * @return
   */
  def prepareCf3PromisesFileTemplate(
      container: Cf3PolicyDraftContainer
    , extraSystemVariables: Map[String, Variable]
    , templates: Map[Cf3PromisesFileTemplateId, Cf3PromisesFileTemplateCopyInfo]
  ) : Map[TechniqueId, PreparedTemplates] = {

    val rudderParametersVariable = getParametersVariable(container)
    val techniques = techniqueRepository.getByIds(container.getAllIds)
    val variablesByTechnique = prepareVariables(container, prepareBundleVars(container) ++ extraSystemVariables, techniques)



    techniques.map {technique =>
      val copyInfos = templates.filterKeys(_.techniqueId == technique.id).values.toSet

      (
          technique.id
        , PreparedTemplates(copyInfos, variablesByTechnique(technique.id) :+ rudderParametersVariable )
      )
    }.toMap
  }


  /**
   * Move the generated promises from the new folder to their final folder, backuping previous promises in the way
   * @param folder : (Container identifier, (base folder, new folder of the policies, backup folder of the policies) )
   */
  def movePromisesToFinalPosition(folders: Seq[PromisesFinalMoveInfo]): Seq[PromisesFinalMoveInfo] = {
    // We need to sort the folders by "depth", so that we backup and move the deepest one first
    val sortedFolder = folders.sortBy(x => x.baseFolder.count(_ =='/')).reverse

    val newFolders = scala.collection.mutable.Buffer[PromisesFinalMoveInfo]()
    try {
      // Folders is a map of machine.uuid -> (base_machine_folder, backup_machine_folder, machine)
      for (folder @ PromisesFinalMoveInfo(containerId, baseFolder, newFolder, backupFolder) <- sortedFolder) {
        // backup old promises
        logger.debug("Backuping old promises from %s to %s ".format(baseFolder, backupFolder))
        backupNodeFolder(baseFolder, backupFolder)
        try {
          newFolders += folder

          logger.debug("Copying new promises into %s ".format(baseFolder))
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
   * Write the current seq of template file a the path location, replacing the variables found in variableSet
   * @param fileSet : the set of template to be written
   * @param variableSet : the set of variable
   * @param path : where to write the files
   */
  override def writePromisesFiles(
      fileSet             : Set[Cf3PromisesFileTemplateCopyInfo]
    , variableSet         : Seq[STVariable]
    , outPath             : String
    , expectedReportsLines: Seq[String]
  ): Unit = {
    try {
      val generationVariable = getGenerationVariable()

      for (fileEntry <- fileSet) {

        //string template does not allows "." in path name, so we are force to use a templateGroup by polity template (versions have . in them)
        val template = new StringTemplate(fileEntry.source, classOf[NormationAmpersandTemplateLexer]);
        template.registerRenderer(classOf[DateTime], new DateRenderer());
        template.registerRenderer(classOf[LocalDate], new LocalDateRenderer());
        template.registerRenderer(classOf[LocalTime], new LocalTimeRenderer());

        for (variable <- variableSet++generationVariable) {
          // Only System Variables have nullable entries
          if ( variable.isSystem && variable.mayBeEmpty &&
              ( (variable.values.size == 0) || (variable.values.size ==1 && variable.values.head == "") ) ) {
            template.setAttribute(variable.name, null)
          } else if (!variable.mayBeEmpty && variable.values.size == 0) {
            throw new VariableException("Mandatory variable %s is empty, can not write %s".format(variable.name, fileEntry.destination))
          } else {
            logger.trace(s"Adding variable ${outPath + "/" + fileEntry.destination} : ${variable.name} values ${variable.values.mkString("[",",","]")}")
            variable.values.foreach { value => template.setAttribute(variable.name, value)
            }
          }
        }

        // write the files to the new promise folder
        logger.trace("Create promises file %s %s".format(outPath, fileEntry.destination))
        try {
          FileUtils.writeStringToFile(new File(outPath, fileEntry.destination), template.toString)
        } catch {
          case e : Exception =>
            val message = s"Bad format in Technique ${fileEntry.id.techniqueId} (file: ${fileEntry.destination}) cause is: ${e.getMessage}"
            throw new RuntimeException(message,e)
        }
      }

      // Writing csv file
      val csvContent = expectedReportsLines.mkString("\n")
      try {
        FileUtils.writeStringToFile(new File(outPath, GENEREATED_CSV_FILENAME), csvContent)
      } catch {
        case e : Exception =>
          val message = s"Impossible to write CSV file (file: ${GENEREATED_CSV_FILENAME}) cause is: ${e.getMessage}"
          throw new RuntimeException(message,e)
      }


    } catch {
      case ex: IOException => logger.error("Writing promises error : ", ex); throw new IOException("Could not create new promises", ex)
      case ex: NullPointerException => logger.error("Writing promises error : ", ex); throw new IOException("Could not create new promises", ex)
      case ex: VariableException => logger.error("Writing promises error in fileSet " + fileSet, ex); throw ex
      case ex: Exception => logger.error("Writing promises error : ", ex); throw ex
    }

  }

  /**
   * Returns variable relative to a specific promise generation
   * For the moment, only the timestamp
   */
  private[this] def getGenerationVariable() : Seq[STVariable]= {
    // compute the generation timestamp
    val promiseGenerationTimestamp = DateTime.now().getMillis()

    Seq(STVariable(generationTimestampVariable, false, Seq(promiseGenerationTimestamp), true))
  }

  private[this] def prepareVariables(
      container: Cf3PolicyDraftContainer
    , systemVars: Map[String, Variable]
    , techniques: Seq[Technique]
  ) : Map[TechniqueId,Seq[STVariable]] = {

    logger.debug("Preparing the PI variables for container %s".format(container.outPath))
    val variablesValues = prepareAllCf3PolicyDraftVariables(container)

    // fill the variable
    (for {
      technique <- techniques
    } yield {
      val ptValues = variablesValues(technique.id)

      val variables:Seq[Variable] = (for {
        variableSpec <- technique.getAllVariableSpecs
      } yield {
        variableSpec match {
          case x : TrackerVariableSpec => Some(x.toVariable(ptValues(x.name).values))
          case x : SystemVariableSpec => systemVars.get(x.name) match {
              case None =>
                if(x.constraint.mayBeEmpty) { //ok, that's expected
                  logger.debug("Variable system named %s not found in the extended variables environnement ".format(x.name))
                } else {
                  logger.warn("Mandatory variable system named %s not found in the extended variables environnement ".format(x.name))
                }
                None
              case Some(sysvar) => Some(x.toVariable(sysvar.values))
          }
          case x : SectionVariableSpec => Some(x.toVariable(ptValues(x.name).values))
        }
      }).flatten

      //return STVariable in place of Rudder variables
      val stVariables = variables.map { v => STVariable(
          name = v.spec.name
        , mayBeEmpty = v.spec.constraint.mayBeEmpty
        , values = v.getTypedValues match {
            case Full(seq) => seq
            case e:EmptyBox => throw new VariableException("Wrong type of variable " + v)
          }
        , v.spec.isSystem
      ) }
      (technique.id,stVariables)
    }).toMap
  }

  /**
   * Compute the TMLs list to be written and their variables
   * @param container : the container of the policies we want to write
   * @param extraVariables : optional : extra system variables that we could want to add
   * @return
   */
  private[this] def prepareBundleVars(container: Cf3PolicyDraftContainer) : Map[String,Variable] = {
    // Compute the correct bundlesequence
    // ncf technique must call before-hand a bundle to register which ncf technique is being called
    val NCF_REPORT_DEFINITION_BUNDLE_NAME = "current_technique_report_info"

    //ad-hoc data structure to store a technique and the promisee name to use for it
    case class BundleTechnique(
      technique: Technique
    , promisee : String
    )
    implicit def pairs2BundleTechniques(seq: Seq[(Technique, List[BundleOrder])]): Seq[BundleTechnique] = {
      seq.map( x => BundleTechnique(x._1, x._2.map(_.value).mkString("/")))
    }

    logger.trace(s"Preparing bundle list and input list for container : ${container} ")

    // Fetch the policies configured, with the system policies first
    val techniques: Seq[BundleTechnique] =  sortTechniques(techniqueRepository.getByIds(container.getAllIds), container)

    //list of inputs file to include: all the outPath of templates that should be "included".
    //returned the pair of (technique, outpath)
    val inputs: Seq[(Technique, String)] = techniques.flatMap { case bt =>
      bt.technique.templates.collect { case template if(template.included) => (bt.technique, template.outPath) }
    }

    val bundleSeq: Seq[(Technique, String, Bundle)] = techniques.flatMap { case BundleTechnique(technique, promiser) =>
      // We need to remove zero-length bundle name from the bundlesequence (like, if there is no ncf bundles to call)
      // to avoid having two successives commas in the bundlesequence
      val techniqueBundles = technique.bundlesequence.flatMap { bundle =>
        if(bundle.name.trim.size > 0) {
          Some((technique, promiser, bundle))
        } else {
          logger.warn(s"Technique '${technique.id}' contains some bundle with empty name, which is forbidden and so they are ignored in the final bundle sequence")
          None
        }
      }

      //now, for each technique that provided reports (i.e: an ncf technique), we must add the
      //NCF_REPORT_DEFINITION_BUNDLE_NAME just before the other bundle of the technique

      //we assume that the bundle name to use as suffix of NCF_REPORT_DEFINITION_BUNDLE_NAME
      // is the first of the provided bundle sequence for that technique
      if(technique.providesExpectedReports) {
        techniqueBundles match {
          case Seq() => Seq()
          case (t, p,b) +: tail => (t, p, Bundle(s"${NCF_REPORT_DEFINITION_BUNDLE_NAME}(${b.name})")) +: (t, p,b) +: tail
        }
      } else {
        techniqueBundles
      }
    }

    //split system and user directive (technique)
    val (systemInputs, userInputs) = inputs.partition { case (t,i) => t.isSystem }
    val (systemBundle, userBundle) = bundleSeq.partition { case(t, p, b) => t.isSystem }

    //utilitary method for formating list of "promisee usebundle => bundlename;"
    def formatUsebundle(x:Seq[(Technique, String, Bundle)]) = {
      val alignWidth = x.map(_._2.size).max
      x.map { case (t, promiser, bundle) => s""""${promiser}"${" "*Math.max(0, alignWidth - promiser.size)} usebundle => ${bundle.name};"""}.mkString( "\n")
    }

    //utilitary method for formating an input list
    def formatInputs(x: Seq[(Technique, String)]) = x.map(_._2).distinct.mkString("\"", s"""",\n${" "*14}"""", s""""\n  """)

    List(
      SystemVariable(systemVariableSpecService.get("INPUTLIST"), Seq(formatInputs(inputs)))
    , SystemVariable(systemVariableSpecService.get("BUNDLELIST"), Seq(bundleSeq.map( _._3.name).mkString(", ", ", ", "")))
    , SystemVariable(systemVariableSpecService.get("RUDDER_SYSTEM_DIRECTIVES_INPUTS")  , Seq(formatInputs(systemInputs)))
    , SystemVariable(systemVariableSpecService.get("RUDDER_SYSTEM_DIRECTIVES_SEQUENCE"), Seq(formatUsebundle(systemBundle)))
    , SystemVariable(systemVariableSpecService.get("RUDDER_DIRECTIVES_INPUTS")  , Seq(formatInputs(userInputs)))
    , SystemVariable(systemVariableSpecService.get("RUDDER_DIRECTIVES_SEQUENCE"), Seq(formatUsebundle(userBundle)))
    ).map(x => (x.spec.name, x)).toMap

  }

  /**
   * Sort the techniques according to the order of the associated BundleOrder of Cf3PolicyDraft.
   * Sort at best: sort rule then directives, and take techniques on that order, only one time
   * Sort system directive first.
   *
   * CAREFUL: this method only take care of sorting based on "BundleOrder", other sorting (like
   * "system must go first") are not taken into account here !
   */
  private[this] def sortTechniques(techniques: Seq[Technique], container: Cf3PolicyDraftContainer): Seq[(Technique, List[BundleOrder])] = {

    def sortByOrder(tech: Seq[Technique], container: Cf3PolicyDraftContainer): Seq[(Technique, List[BundleOrder])] = {
      def compareBundleOrder(a: List[BundleOrder], b: List[BundleOrder]): Boolean = {
        BundleOrder.compareList(a, b) <= 0
      }
      val drafts = container.getAll().values.toSeq

      //for each technique, get it's best order from draft (if several directive use it) and return a pair (technique, List(order))
      val pairs = tech.map { t =>
        val tDrafts = drafts.filter { _.technique.id == t.id }.sortWith( (d1,d2) => compareBundleOrder(d1.order, d2.order))

        //the order we want is the one with the lowest draft order, or the default one if no draft found (but that should not happen by construction)
        val order = tDrafts.map( _.order ).headOption.getOrElse(List(BundleOrder.default))

        (t, order)
      }

      //now just sort the pair by order and keep only techniques
      val ordered = pairs.sortWith { case ((_, o1), (_, o2)) => BundleOrder.compareList(o1, o2) <= 0 }

      //some debug info to understand what order was used for each node:
      if(logger.isDebugEnabled) {
        logger.debug(s"Sorted Technique for path [${container.outPath}] (and their Rules and Directives used to sort):")
        ordered.map(p => s" `-> ${p._1.name}: [${p._2.map(_.value).mkString(" | ")}]").foreach { logger.debug(_) }
      }

      ordered
    }

    sortByOrder(techniques, container)
  }


  /**
   * From the container, convert the parameter into StringTemplate variable, that contains a list of
   * parameterName, parameterValue (really, the ParameterEntry itself)
   * This is quite naive for the moment
   */
  private[this] def getParametersVariable(container: Cf3PolicyDraftContainer) : STVariable = {
    STVariable(
        PARAMETER_VARIABLE
      , true
      , container.parameters.toSeq
      , true
    )
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
      if (dest.isDirectory)
        // force deletion of previous backup
        FileUtils.forceDelete(dest)

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

    logger.debug("Moving folders from %s to %s".format(src, destinationFolder))

    if (src.isDirectory()) {
      val dest = new File(destinationFolder)

      if (dest.isDirectory)
        // force deletion of previous promises
        FileUtils.forceDelete(dest)

      FileUtils.moveDirectory(src, dest)

      // force deletion of dandling new promise folder
      if ( (src.getParentFile().isDirectory) && (src.getParent().endsWith("rules.new")))
        FileUtils.forceDelete(src.getParentFile())

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

  /**
   * Concatenate all the variables for each policy Instances.
   *
   * The serialization is done
   */
  override def prepareAllCf3PolicyDraftVariables(cf3PolicyDraftContainer: Cf3PolicyDraftContainer): Map[TechniqueId, Map[String, Variable]] = {
    (for {
      // iterate over each policyName
      activeTechniqueId <- cf3PolicyDraftContainer.getAllIds
    } yield {
      val technique = techniqueRepository.get(activeTechniqueId).getOrElse(
          throw new RuntimeException("Error, can not find policy with id '%s' and version ".format(activeTechniqueId.name.value) +
              "'%s' in the policy service".format(activeTechniqueId.name.value)))
      val cf3PolicyDraftVariables = scala.collection.mutable.Map[String, Variable]()

      for {
        // over each cf3PolicyDraft for this name
        (directiveId, cf3PolicyDraft) <- cf3PolicyDraftContainer.findById(activeTechniqueId)
      } yield {
        // start by setting the directiveVariable
        val (directiveVariable, boundingVariable) = cf3PolicyDraft.getDirectiveVariable

        cf3PolicyDraftVariables.get(directiveVariable.spec.name) match {
          case None =>
              //directiveVariable.values = scala.collection.mutable.Buffer[String]()
              cf3PolicyDraftVariables.put(directiveVariable.spec.name, directiveVariable.copy(values = Seq()))
          case Some(x) => // value is already there
        }

        // Only multi-instance policy may have a policyinstancevariable with high cardinal
        val size = if (technique.isMultiInstance) { boundingVariable.values.size } else { 1 }
        val values = Seq.fill(size)(createRudderId(cf3PolicyDraft))
        val variable = cf3PolicyDraftVariables(directiveVariable.spec.name).copyWithAppendedValues(values)
        cf3PolicyDraftVariables(directiveVariable.spec.name) = variable

        // All other variables now
        for (variable <- cf3PolicyDraft.getVariables) {
          variable._2 match {
            case newVar: TrackerVariable => // nothing, it's been dealt with already
            case newVar: Variable =>
              if ((!newVar.spec.checked) || (newVar.spec.isSystem)) {} else { // Only user defined variables should need to be agregated
                val variable = cf3PolicyDraftVariables.get(newVar.spec.name) match {
                  case None =>
                    Variable.matchCopy(newVar, setMultivalued = true) //asIntance is ok here, I believe
                  case Some(existingVariable) => // value is already there
                    // hope it is multivalued, otherwise BAD THINGS will happen
                    if (!existingVariable.spec.multivalued) {
                      logger.warn("Attempt to append value into a non multivalued variable, bad things may happen")
                    }
                    existingVariable.copyWithAppendedValues(newVar.values)
                }
                cf3PolicyDraftVariables.put(newVar.spec.name, variable)
              }
          }
        }
      }
      (activeTechniqueId, cf3PolicyDraftVariables.toMap)
    }).toMap
  }

  /**
   * From a container, containing meta technique, fetch the csv included, and add the Rudder UUID within, and return the new lines
   */
  def prepareReportingDataForMetaTechnique(cf3PolicyDraftContainer: Cf3PolicyDraftContainer): Seq[String] = {
    (for {
      // iterate over each policyName
      activeTechniqueId <- cf3PolicyDraftContainer.getAllIds
    } yield {
      val technique = techniqueRepository.get(activeTechniqueId).getOrElse(
          throw new RuntimeException("Error, can not find technique with id '%s' and version ".format(activeTechniqueId.name.value) +
              "'%s' in the policy service".format(activeTechniqueId.name.value)))

      technique.providesExpectedReports match {
        case true =>
            // meta Technique are UNIQUE, hence we can get at most ONE cf3PolicyDraft per activeTechniqueId
            cf3PolicyDraftContainer.findById(activeTechniqueId) match {
              case seq if seq.size == 0 =>
                Seq[String]()
              case seq if seq.size == 1 =>
                val cf3PolicyDraft = seq.head._2
                val rudderId = createRudderId(cf3PolicyDraft)
                val csv = techniqueRepository.getReportingDetailsContent[Seq[String]](technique.id) { optInputStream =>
                    optInputStream match {
                      case None => throw new RuntimeException(s"Error when trying to open reports descriptor `expected_reports.csv` for technique ${technique}. Check that the report descriptor exist and is correctly commited in Git, or that the metadata for the technique are corrects.")
                      case Some(inputStream) =>
                        scala.io.Source.fromInputStream(inputStream).getLines().map{ case line =>
                          line.trim.startsWith("#") match {
                            case true => line
                            case false => line.replaceAll(TAG_OF_RUDDER_ID, rudderId)
                          }
                        }.toSeq
                    }
                }
                csv
              case _ =>
                throw new RuntimeException("There cannot be two identical meta Technique on a same node");
            }
        case false =>
          Seq[String]()
      }
    }).flatten
  }

  /**
   * Create the value of the Rudder Id from the Id of the Cf3PolicyDraft and
   * the serial
   */
   private def createRudderId(cf3PolicyDraft: Cf3PolicyDraft): String = {
     cf3PolicyDraft.id.value + "@@" + cf3PolicyDraft.serial
   }
}
