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

import scala.io.Codec

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
import com.normation.rudder.services.policies.nodeconfig.NodeConfiguration
import com.normation.templates.STVariable
import com.normation.utils.Control._

import org.joda.time.DateTime

import net.liftweb.common._
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyMode

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
    , globalPolicyMode : GlobalPolicyMode
  ): Box[AgentNodeWritableConfiguration]

}

/**
 * This class is responsible of transforming a NodeConfiguration for a given agent type
 * into a set of templates and variables that could be filled to string template.
 */
class PrepareTemplateVariablesImpl(
    techniqueRepository      : TechniqueRepository        // only for getting reports file content
  , systemVariableSpecService: SystemVariableSpecService
) extends PrepareTemplateVariables with Loggable {

  override def prepareTemplateForAgentNodeConfiguration(
      agentNodeConfig  : AgentNodeConfiguration
    , nodeConfigVersion: NodeConfigId
    , rootNodeConfigId : NodeId
    , templates        : Map[TechniqueResourceId, TechniqueTemplateCopyInfo]
    , allNodeConfigs   : Map[NodeId, NodeConfiguration]
    , rudderIdCsvTag   : String
    , globalPolicyMode : GlobalPolicyMode
  ): Box[AgentNodeWritableConfiguration] = {

   logger.debug(s"Writting promises for node '${agentNodeConfig.config.nodeInfo.hostname}' (${agentNodeConfig.config.nodeInfo.id.value})")

    val container = new Cf3PolicyDraftContainer(
          agentNodeConfig.config.parameters.map(x => ParameterEntry(x.name.value, x.value)).toSet
        , agentNodeConfig.config.policyDrafts
    )

    //Generate for each node an unique timestamp.
    val generationTimestamp = DateTime.now().getMillis

    val systemVariables = agentNodeConfig.config.nodeContext ++ List(
        systemVariableSpecService.get("NOVA"     ).toVariable(if(agentNodeConfig.agentType == NOVA_AGENT     ) Seq("true") else Seq())
      , systemVariableSpecService.get("COMMUNITY").toVariable(if(agentNodeConfig.agentType == COMMUNITY_AGENT) Seq("true") else Seq())
      , systemVariableSpecService.get("AGENT_TYPE").toVariable(Seq(agentNodeConfig.agentType.tagValue))
      , systemVariableSpecService.get("RUDDER_NODE_CONFIG_ID").toVariable(Seq(nodeConfigVersion.value))
    ).map(x => (x.spec.name, x)).toMap

    for {
      bundleVars <- prepareBundleVars(agentNodeConfig.config.nodeInfo.id, agentNodeConfig.config.nodeInfo.policyMode, globalPolicyMode, container)
    } yield {
      val allSystemVars = systemVariables.toMap ++ bundleVars
      val preparedTemplate = prepareTechniqueTemplate(
          agentNodeConfig.config.nodeInfo.id, container, allSystemVars, templates, generationTimestamp
      )

      logger.trace(s"${agentNodeConfig.config.nodeInfo.id.value}: creating lines for expected reports CSV files")
      val csv = ExpectedReportsCsv(prepareReportingDataForMetaTechnique(container, rudderIdCsvTag))

      AgentNodeWritableConfiguration(agentNodeConfig.agentType, agentNodeConfig.paths, preparedTemplate.values.toSeq, csv, allSystemVars)
    }
  }

  private[this] def prepareTechniqueTemplate(
      nodeId              : NodeId // for log message
    , container           : Cf3PolicyDraftContainer
    , extraSystemVariables: Map[String, Variable]
    , allTemplates        : Map[TechniqueResourceId, TechniqueTemplateCopyInfo]
    , generationTimestamp : Long
  ) : Map[TechniqueId, PreparedTechnique] = {

    val techniques = container.getTechniques().values.toList
    val variablesByTechnique = prepareVariables(nodeId, container, extraSystemVariables, techniques)

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
  private[this] def prepareBundleVars(
      nodeId          : NodeId
    , nodePolicyMode  : Option[PolicyMode]
    , globalPolicyMode: GlobalPolicyMode
    , container       : Cf3PolicyDraftContainer
  ) : Box[Map[String,Variable]] = {

    BuildBundleSequence.prepareBundleVars(nodeId, nodePolicyMode, globalPolicyMode, container).map { bundleVars =>

      List(
        SystemVariable(systemVariableSpecService.get("INPUTLIST") , bundleVars.inputlist  :: Nil)
      , SystemVariable(systemVariableSpecService.get("BUNDLELIST"), bundleVars.bundlelist :: Nil)
      , SystemVariable(systemVariableSpecService.get("RUDDER_SYSTEM_DIRECTIVES_INPUTS")  , bundleVars.systemDirectivesInputs    :: Nil)
      , SystemVariable(systemVariableSpecService.get("RUDDER_SYSTEM_DIRECTIVES_SEQUENCE"), bundleVars.systemDirectivesUsebundle :: Nil)
      , SystemVariable(systemVariableSpecService.get("RUDDER_DIRECTIVES_INPUTS")  , bundleVars.directivesInputs    :: Nil)
      , SystemVariable(systemVariableSpecService.get("RUDDER_DIRECTIVES_SEQUENCE"), bundleVars.directivesUsebundle :: Nil)
      ).map(x => (x.spec.name, x)).toMap
    }
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
      (techniqueId, technique) <- cf3PolicyDraftContainer.getTechniques
    } yield {
      val cf3PolicyDraftVariables = scala.collection.mutable.Map[String, Variable]()

      for {
        // over each cf3PolicyDraft for this name
        (directiveId, cf3PolicyDraft) <- cf3PolicyDraftContainer.findById(techniqueId)
      } yield {
        // start by setting the TRACKINGKEY variable
        // we need to deal with it appart because we need to have the
        // number of values for the tracked variable
        val (trackingKeyVariable, trackedVariable) = cf3PolicyDraft.getDirectiveVariable

        val values = {
          // Only multi-instance policy may have a trackingKeyVariable with high cardinal
          // Because if the technique is Unique, then we can't have several directive ID on the same
          // rule, and we just always use the same cf3PolicyDraftId
          val size = if (technique.isMultiInstance) { trackedVariable.values.size } else { 1 }
          Seq.fill(size)(createRudderId(cf3PolicyDraft))
        }
        cf3PolicyDraftVariables.get(trackingKeyVariable.spec.name) match {
          case None =>
              //directiveVariable.values = scala.collection.mutable.Buffer[String]()
              cf3PolicyDraftVariables.put(trackingKeyVariable.spec.name, trackingKeyVariable.copy(values = values))
          case Some(x) =>
              cf3PolicyDraftVariables.put(trackingKeyVariable.spec.name, x.copyWithAppendedValues(values))
        }

        // All other variables now
        for (variable <- cf3PolicyDraft.getVariables) {
          variable._2 match {
            case _     : TrackerVariable => // nothing, it's been dealt with already
            case newVar: Variable        =>
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

      (techniqueId, cf3PolicyDraftVariables.toMap)
    }).toMap
  }

  /**
   * From a container, containing meta technique, fetch the csv included, and add the Rudder UUID within, and return the new lines
   */
  private[this] def prepareReportingDataForMetaTechnique(cf3PolicyDraftContainer: Cf3PolicyDraftContainer, rudderTag: String ): Seq[String] = {
    (for {
      // iterate over each policyName
      (techniqueId, technique) <- cf3PolicyDraftContainer.getTechniques()
    } yield {

      technique.providesExpectedReports match {
        case true =>
            // meta Technique are UNIQUE, hence we can get at most ONE cf3PolicyDraft per activeTechniqueId
            cf3PolicyDraftContainer.findById(techniqueId) match {
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
                            case true  => line
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
    }).toList.flatten
  }

}
