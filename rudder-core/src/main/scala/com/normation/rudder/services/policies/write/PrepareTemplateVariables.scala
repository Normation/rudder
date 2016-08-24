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

import scala.annotation.migration
import com.normation.utils.Control.bestEffort
import com.normation.cfclerk.domain.Bundle
import com.normation.cfclerk.domain.PARAMETER_VARIABLE
import com.normation.cfclerk.domain.SectionVariableSpec
import com.normation.cfclerk.domain.SystemVariable
import com.normation.cfclerk.domain.SystemVariableSpec
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueResourceId
import com.normation.cfclerk.domain.TrackerVariable
import com.normation.cfclerk.domain.TrackerVariableSpec
import com.normation.cfclerk.domain.Variable
import com.normation.cfclerk.exceptions.VariableException
import com.normation.cfclerk.services.SystemVariableSpecService
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.inventory.domain.COMMUNITY_AGENT
import com.normation.inventory.domain.NOVA_AGENT
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.services.policies.BundleOrder
import com.normation.rudder.services.policies.nodeconfig.NodeConfiguration
import org.joda.time.DateTime
import net.liftweb.common._
import scala.io.Codec

trait PrepareTemplateVariables {

  /**
   * This methods contains all the logic that allows to transform an
   * agent node configuration (as (mostly) viewed by Rudder) into
   * a list of path info with formatted variables that can be feed to StringTemplate
   * to actually generate the promises with the correct variables replaced.
   *
   * It's also that method that handle all the special variables, like "GENERATION TIMESTAMP",
   * "BUNDLESEQUENCE", "INPUTLIST" etc.
   *
   */
  def prepareTemplateForAgentNodeConfiguration(
      agentNodeConfig  : AgentNodeConfiguration
    , nodeConfigVersion: NodeConfigId
    , rootNodeConfigId : NodeId
    , templates        : Map[TechniqueResourceId, TechniqueTemplateCopyInfo]
    , allNodeConfigs   : Map[NodeId, NodeConfiguration]
    , rudderIdCsvTag   : String
  ): AgentNodeWritableConfiguration

}

object PrepareTemplateVariables extends Loggable {

  /*
   * utilitary method for formating list of "promisee usebundle => bundlename;"
   */
  def formatMethodsUsebundle(x:Seq[(String, Bundle)]): String = {

    val alignWidth = if(x.size <= 0) 0 else x.map(_._1.size).max
    x.map { case (promiser, bundle) =>
      val escaped = ParameterEntry.escapeString(promiser)
      //the promiser value (may) comes from user input, so we need to escape "
      s""""${escaped}"${" "*Math.max(0, alignWidth - escaped.size)} usebundle => ${bundle.name};"""
    }.mkString( "\n")
  }

  /*
   * utilitary method for formating an input list
   */
  def formatBundleFileInputs(x: Seq[String]) = {
    val inputs = x.distinct
    if (inputs.isEmpty) {
      ""
    } else {
      inputs.mkString("\"", s"""",\n"""", s"""",""")
    }
  }

  /*
   * Sort the techniques according to the order of the associated BundleOrder of Cf3PolicyDraft.
   * Sort at best: sort rule then directives, and take techniques on that order, only one time
   * Sort system directive first.
   *
   * CAREFUL: this method only take care of sorting based on "BundleOrder", other sorting (like
   * "system must go first") are not taken into account here !
   */
  def sortTechniques(nodeId: NodeId, techniques: Seq[Technique], container: Cf3PolicyDraftContainer): Seq[(Technique, List[BundleOrder])] = {

    def compareBundleOrder(a: Cf3PolicyDraft, b: Cf3PolicyDraft): Boolean = {
      BundleOrder.compareList(List(a.ruleOrder, a.directiveOrder), List(b.ruleOrder, b.directiveOrder)) <= 0
    }

    val drafts = container.getAll().values.toSeq

    //for each technique, get it's best order from draft (if several directive use it) and return a pair (technique, List(order))
    val pairs = techniques.map { t =>
      val tDrafts = drafts.filter { _.technique.id == t.id }.sortWith( compareBundleOrder )

      //the order we want is the one with the lowest draft order, or the default one if no draft found (but that should not happen by construction)
      val order = tDrafts.map( t => List(t.ruleOrder, t.directiveOrder)).headOption.getOrElse(List(BundleOrder.default))

      (t, order)
    }

    //now just sort the pair by order and keep only techniques
    val ordered = pairs.sortWith { case ((_, o1), (_, o2)) => BundleOrder.compareList(o1, o2) <= 0 }

    //some debug info to understand what order was used for each node:
    if(logger.isDebugEnabled) {
      val sorted = ordered.map(p => s"${p._1.name}: [${p._2.map(_.value).mkString(" | ")}]").mkString("[","][", "]")
      logger.debug(s"Sorted Technique (and their Rules and Directives used to sort): ${sorted}")
    }

    ordered

  }
}


/**
 * This class is responsible of transforming a NodeConfiguration for a given agent type
 * into a set of templates and variables that could be filled to string template.
 */
class PrepareTemplateVariablesImpl(
    techniqueRepository      : TechniqueRepository
  , systemVariableSpecService: SystemVariableSpecService
) extends PrepareTemplateVariables with Loggable {

  import PrepareTemplateVariables._

  override def prepareTemplateForAgentNodeConfiguration(
      agentNodeConfig  : AgentNodeConfiguration
    , nodeConfigVersion: NodeConfigId
    , rootNodeConfigId : NodeId
    , templates        : Map[TechniqueResourceId, TechniqueTemplateCopyInfo]
    , allNodeConfigs   : Map[NodeId, NodeConfiguration]
    , rudderIdCsvTag   : String
  ): AgentNodeWritableConfiguration = {

   logger.debug(s"Writting promises for node '${agentNodeConfig.config.nodeInfo.hostname}' (${agentNodeConfig.config.nodeInfo.id.value})")

    val container = new Cf3PolicyDraftContainer(
          agentNodeConfig.config.parameters.map(x => ParameterEntry(x.name.value, x.value)).toSet
        , agentNodeConfig.config.policyDrafts
    )

    //Generate for each node an unique timestamp.
    val generationTimestamp = DateTime.now().getMillis

    val preparedTemplate = {
      val systemVariables = agentNodeConfig.config.nodeContext ++ List(
          systemVariableSpecService.get("NOVA"     ).toVariable(if(agentNodeConfig.agentType == NOVA_AGENT     ) Seq("true") else Seq())
        , systemVariableSpecService.get("COMMUNITY").toVariable(if(agentNodeConfig.agentType == COMMUNITY_AGENT) Seq("true") else Seq())
        , systemVariableSpecService.get("RUDDER_NODE_CONFIG_ID").toVariable(Seq(nodeConfigVersion.value))
      ).map(x => (x.spec.name, x)).toMap

      prepareTechniqueTemplate(agentNodeConfig.config.nodeInfo.id, container, systemVariables.toMap, templates, generationTimestamp)
    }

    logger.trace(s"${agentNodeConfig.config.nodeInfo.id.value}: creating lines for expected reports CSV files")
    val csv = ExpectedReportsCsv(prepareReportingDataForMetaTechnique(container, rudderIdCsvTag))

    AgentNodeWritableConfiguration(agentNodeConfig.paths, preparedTemplate.values.toSeq, csv)
  }

  private[this] def prepareTechniqueTemplate(
      nodeId: NodeId // for log message
    , container: Cf3PolicyDraftContainer
    , extraSystemVariables: Map[String, Variable]
    , allTemplates: Map[TechniqueResourceId, TechniqueTemplateCopyInfo]
    , generationTimestamp: Long
  ) : Map[TechniqueId, PreparedTechnique] = {

    val techniques = techniqueRepository.getByIds(container.getAllIds)
    val variablesByTechnique = prepareVariables(nodeId, container, prepareBundleVars(nodeId, container) ++ extraSystemVariables, techniques)

    /*
     * From the container, convert the parameter into StringTemplate variable, that contains a list of
     * parameterName, parameterValue (really, the ParameterEntry itself)
     * This is quite naive for the moment
     */
    val rudderParametersVariable = STVariable(
        PARAMETER_VARIABLE
      , true
      , container.parameters.toSeq
      , true
    )
    val generationVariable = STVariable("GENERATIONTIMESTAMP", false, Seq(generationTimestamp), true)

    techniques.map {technique =>
      val techniqueTemplatesIds = technique.templatesMap.keySet
      // this is an optimisation to avoid re-redeading template each time, for each technique. We could
      // just, from a strict correctness point of view, just do a techniquerepos.getcontent for each technique.template here
      val techniqueTemplates = allTemplates.filterKeys(k => techniqueTemplatesIds.contains(k)).values.toSet
      val variables = variablesByTechnique(technique.id) :+ rudderParametersVariable :+ generationVariable
      (
          technique.id
        , PreparedTechnique(techniqueTemplates, variables, technique.files.toSet)
      )
    }.toMap
  }

  /**
   * Create the value of the Rudder Id from the Id of the Cf3PolicyDraft and
   * the serial
   */
  private[this] def createRudderId(cf3PolicyDraft: Cf3PolicyDraft): String = {
    cf3PolicyDraft.id.value + "@@" + cf3PolicyDraft.serial
  }

  private[this] def prepareVariables(
      nodeId: NodeId // for log message
    , container : Cf3PolicyDraftContainer
    , systemVars: Map[String, Variable]
    , techniques: Seq[Technique]
  ) : Map[TechniqueId,Seq[STVariable]] = {

    val variablesValues = prepareAllCf3PolicyDraftVariables(container)

    // fill the variable
    (bestEffort(techniques) { technique =>

      val techniqueValues = variablesValues(technique.id)
      for {
        variables <- bestEffort(technique.getAllVariableSpecs) { variableSpec =>
                       variableSpec match {
                         case x : TrackerVariableSpec =>
                           techniqueValues.get(x.name) match {
                             case None => Failure(s"[${nodeId.value}:${technique.id}] Misssing mandatory value for tracker variable: '${x.name}'")
                             case Some(v) => Full(Some(x.toVariable(v.values)))
                           }
                         case x : SystemVariableSpec => systemVars.get(x.name) match {
                             case None =>
                               if(x.constraint.mayBeEmpty) { //ok, that's expected
                                 logger.trace(s"[${nodeId.value}:${technique.id}] Variable system named '${x.name}' not found in the extended variables environnement")
                                 Full(None)
                               } else {
                                 Failure(s"[${nodeId.value}:${technique.id}] Missing value for system variable: '${x.name}'")
                               }
                             case Some(sysvar) => Full(Some(x.toVariable(sysvar.values)))
                         }
                         case x : SectionVariableSpec =>
                           techniqueValues.get(x.name) match {
                             case None => Failure(s"[${nodeId.value}:${technique.id}] Misssing value for standard variable: '${x.name}'")
                             case Some(v) => Full(Some(x.toVariable(v.values)))
                           }
                       }
                     }
      } yield {
      //return STVariable in place of Rudder variables
      val stVariables = variables.flatten.map { v => STVariable(
          name = v.spec.name
        , mayBeEmpty = v.spec.constraint.mayBeEmpty
        , values = v.getTypedValues match {
            case Full(seq) => seq
            case e:EmptyBox => throw new VariableException("Wrong type of variable " + v)
          }
        , v.spec.isSystem
      ) }
      (technique.id,stVariables)
    }
    }) match {
      case Full(seq) => seq.toMap
      case eb: EmptyBox =>

        val errors = (eb ?~! "").messageChain.split(" <- ").tail.distinct.sorted

        errors.foreach { msg =>
          logger.error(s"${msg}")
        }

        throw new VariableException( s"Error when trying to build variables for technique(s) in node ${nodeId.value}: ${errors.mkString("; ")}")
      }
  }

  /**
   * Compute the TMLs list to be written and their variables
   * @param container : the container of the policies we want to write
   * @param extraVariables : optional : extra system variables that we could want to add
   * @return
   */
  private[this] def prepareBundleVars(nodeId: NodeId, container: Cf3PolicyDraftContainer) : Map[String,Variable] = {
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

    logger.trace(s"Preparing bundle list and input list for node : ${nodeId.value}")

    // Fetch the policies configured, with the system policies first
    val bundleTechniques: Seq[BundleTechnique] =  sortTechniques(nodeId, techniqueRepository.getByIds(container.getAllIds), container)

    //list of inputs file to include: all the outPath of templates that should be "included".
    //returned the pair of (technique, outpath)
    val inputs: Seq[(Technique, String)] = bundleTechniques.flatMap {
      case bt =>
        bt.technique.templates.collect { case template if(template.included) => (bt.technique, template.outPath) } ++
        bt.technique.files.collect { case file if(file.included) => (bt.technique, file.outPath) }

    }

    val bundleSeq: Seq[(Technique, String, Bundle)] = bundleTechniques.flatMap { case BundleTechnique(technique, promiser) =>
      // We need to remove zero-length bundle name from the bundlesequence (like, if there is no ncf bundles to call)
      // to avoid having two successives commas in the bundlesequence
      val techniqueBundles = technique.bundlesequence.flatMap { bundle =>
        if(bundle.name.trim.size > 0) {
          Some((technique, promiser, bundle))
        } else {
          logger.warn(s"Technique '${technique.id}' used in node '${nodeId.value}' contains some bundle with empty name, which is forbidden and so they are ignored in the final bundle sequence")
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



    List(
      SystemVariable(systemVariableSpecService.get("INPUTLIST"), Seq(formatBundleFileInputs(inputs.map(_._2))))
    , SystemVariable(systemVariableSpecService.get("BUNDLELIST"), Seq(bundleSeq.map( _._3.name).mkString(", ", ", ", "")))
    , SystemVariable(systemVariableSpecService.get("RUDDER_SYSTEM_DIRECTIVES_INPUTS")  , Seq(formatBundleFileInputs(systemInputs.map(_._2))))
    , SystemVariable(systemVariableSpecService.get("RUDDER_SYSTEM_DIRECTIVES_SEQUENCE"), Seq(formatMethodsUsebundle(systemBundle.map(x => (x._2,x._3)))))
    , SystemVariable(systemVariableSpecService.get("RUDDER_DIRECTIVES_INPUTS")  , Seq(formatBundleFileInputs(userInputs.map(_._2))))
    , SystemVariable(systemVariableSpecService.get("RUDDER_DIRECTIVES_SEQUENCE"), Seq(formatMethodsUsebundle(userBundle.map(x => (x._2,x._3)))))
    ).map(x => (x.spec.name, x)).toMap

  }

  /**
   * Concatenate all the variables for each policy Instances.
   *
   * The serialization is done
   *
   * visibility needed for tests
   */
  def prepareAllCf3PolicyDraftVariables(cf3PolicyDraftContainer: Cf3PolicyDraftContainer): Map[TechniqueId, Map[String, Variable]] = {
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
  private[this] def prepareReportingDataForMetaTechnique(cf3PolicyDraftContainer: Cf3PolicyDraftContainer, rudderTag: String ): Seq[String] = {
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
                        scala.io.Source.fromInputStream(inputStream)(Codec.UTF8).getLines().map{ case line =>
                          line.trim.startsWith("#") match {
                            case true => line
                            case false => line.replaceAll(rudderTag, rudderId)
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

}
