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

import com.normation.cfclerk.domain.{PARAMETER_VARIABLE, SectionVariableSpec, SystemVariableSpec, TechniqueFile, TechniqueGenerationMode, TechniqueId, TechniqueResourceId, TrackerVariableSpec, Variable}
import com.normation.cfclerk.exceptions.VariableException
import com.normation.cfclerk.services.SystemVariableSpecService
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.templates.STVariable
import com.normation.utils.Control.bestEffort
import net.liftweb.common._
import org.joda.time.DateTime
import com.normation.inventory.domain.AgentType
import com.normation.rudder.services.policies.ParameterEntry
import com.normation.rudder.services.policies.Policy
import com.normation.utils.Control.sequence
import com.normation.utils.Control

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
    , templates        : Map[(TechniqueResourceId, AgentType), TechniqueTemplateCopyInfo]
    , rudderIdCsvTag   : String
    , globalPolicyMode : GlobalPolicyMode
    , generationTime   : DateTime
  ): Box[AgentNodeWritableConfiguration]

}

/**
 * This class is responsible of transforming a NodeConfiguration for a given agent type
 * into a set of templates and variables that could be filled to string template.
 */
class PrepareTemplateVariablesImpl(
    techniqueRepository      : TechniqueRepository        // only for getting reports file content
  , systemVariableSpecService: SystemVariableSpecService
  , buildBundleSequence      : BuildBundleSequence
  , agentRegister            : AgentRegister
) extends PrepareTemplateVariables with Loggable {


  override def prepareTemplateForAgentNodeConfiguration(
      agentNodeConfig  : AgentNodeConfiguration
    , nodeConfigVersion: NodeConfigId
    , rootNodeConfigId : NodeId
    , templates        : Map[(TechniqueResourceId, AgentType), TechniqueTemplateCopyInfo]
    , rudderIdCsvTag   : String
    , globalPolicyMode : GlobalPolicyMode
    , generationTime   : DateTime
  ) : Box[AgentNodeWritableConfiguration] = {

    val nodeId = agentNodeConfig.config.nodeInfo.id
    logger.debug(s"Writing policies for node '${agentNodeConfig.config.nodeInfo.hostname}' (${nodeId.value})")

    val systemVariables = agentNodeConfig.config.nodeContext ++ List(
        systemVariableSpecService.get("NOVA"     ).toVariable(if(agentNodeConfig.agentType == AgentType.CfeEnterprise) Seq("true") else Seq())
      , systemVariableSpecService.get("COMMUNITY").toVariable(if(agentNodeConfig.agentType == AgentType.CfeCommunity ) Seq("true") else Seq())
      , systemVariableSpecService.get("AGENT_TYPE").toVariable(Seq(agentNodeConfig.agentType.toString))
      , systemVariableSpecService.get("RUDDER_NODE_CONFIG_ID").toVariable(Seq(nodeConfigVersion.value))

    ).map(x => (x.spec.name, x)).toMap

    val agentNodeProps = AgentNodeProperties(
        nodeId
      , agentNodeConfig.agentType
      , agentNodeConfig.config.nodeInfo.osDetails
      , agentNodeConfig.config.nodeInfo.isPolicyServer
      , agentNodeConfig.config.nodeInfo.serverRoles
    )

    for {
      (parameters, allSystemVars) <- for {
          bundleVars    <- buildBundleSequence.prepareBundleVars(
            agentNodeProps
            , agentNodeConfig.config.nodeInfo.policyMode
            , globalPolicyMode
            , agentNodeConfig.config.policies
            , agentNodeConfig.config.runHooks
          ).map { bundleVars => bundleVars.map(x => (x.spec.name, x)).toMap }

          parameters    <- Control.sequence(agentNodeConfig.config.parameters.toSeq) { x =>
            agentRegister.findMap(agentNodeProps) { agent =>
              Full(ParameterEntry(x.name.value, agent.escape(x.value), agentNodeConfig.agentType))
            }
          }

          allSystemVars = systemVariables.toMap ++ bundleVars
      } yield {
        (parameters, allSystemVars)
      }
      preparedTemplate <- prepareTechniqueTemplate(
                              agentNodeProps
                            , agentNodeConfig.config.policies
                            , parameters
                            , allSystemVars
                            , templates
                            , generationTime.getMillis
                          )
    } yield {

      AgentNodeWritableConfiguration(
          agentNodeProps
        , agentNodeConfig.paths
        , preparedTemplate
        , allSystemVars
      )
    }
  }


  private[this] def prepareTechniqueTemplate(
      agentNodeProps      : AgentNodeProperties
    , policies            : List[Policy]
    , parameters          : Seq[ParameterEntry]
    , systemVars          : Map[String, Variable]
    , allTemplates        : Map[(TechniqueResourceId, AgentType), TechniqueTemplateCopyInfo]
    , generationTimestamp : Long
  ) : Box[Seq[PreparedTechnique]] = {

    val rudderParametersVariable = STVariable(PARAMETER_VARIABLE, true, parameters, true)
    val generationVariable = STVariable("GENERATIONTIMESTAMP", false, Seq(generationTimestamp), true)

    for {
      variableHandler    <- agentRegister.findHandler(agentNodeProps) ?~! s"Error when trying to fetch variable escaping method for node ${agentNodeProps.nodeId.value}"
      preparedTechniques <-  sequence(policies) { p =>
        for {
          variables <- prepareVariables(agentNodeProps, variableHandler, p, systemVars) ?~! s"Error when trying to build variables for technique(s) in node ${agentNodeProps.nodeId.value}"
        } yield {
          val techniqueTemplatesIds = p.technique.templatesIds
          // only set if technique is multi-policy
          val reportId = p.technique.generationMode match {
            case TechniqueGenerationMode.MultipleDirectives => Some(p.id)
            case _ => None
          }
          //if technique is multi-policy, we need to update destination path to add an unique id along with the version
          //to have one directory by directive.
          val techniqueTemplates = {
            val templates = allTemplates.filterKeys(k => techniqueTemplatesIds.contains(k._1) && k._2 == agentNodeProps.agentType).values.toSet
            p.technique.generationMode match {
              case TechniqueGenerationMode.MultipleDirectives =>
                templates.map(copyInfo => copyInfo.copy(destination = Policy.makeUniqueDest(copyInfo.destination, p)))
              case _ =>
                templates
            }
          }
          val files = {
            val files = p.technique.agentConfig.files.toSet[TechniqueFile]
            p.technique.generationMode match {
              case TechniqueGenerationMode.MultipleDirectives =>
                files.map(file => file.copy(outPath = Policy.makeUniqueDest(file.outPath, p)))
              case _ =>
                files
            }
          }

          PreparedTechnique(techniqueTemplates, variables :+ rudderParametersVariable :+ generationVariable, files, reportId)
        }
      }
    } yield {
      preparedTechniques
    }
  }

  // Create a STVariable from a Variable
  private[this] def stVariableFromVariable(
      v               : Variable
    , variableEscaping: AgentSpecificGeneration
    , nodeId: NodeId
    , techniqueId: TechniqueId
  ) : STVariable = {
    STVariable(
        name = v.spec.name
      , mayBeEmpty = v.spec.constraint.mayBeEmpty
      , values = v.getValidatedValue(variableEscaping.escape) ?~! s"Wrong value type for variable '${v.spec.name}'"  match {
          case Full(seq) => seq
          case eb: EmptyBox =>
            val e = (eb ?~! s"Error when preparing variable for node with ID '${nodeId.value}' on Technique '${techniqueId.toString()}'")
            throw new VariableException(e.messageChain)
        }
      , v.spec.isSystem
      )
  }

  private[this] def prepareVariables(
      agentNodeProps      : AgentNodeProperties
    , agentVariableHandler: AgentSpecificGeneration
    , policy              : Policy
    , systemVars          : Map[String, Variable]
  ) : Box[Seq[STVariable]] = {

    // we want to check that all technique variables are correctly provided.
    // we can't do it when we built node configuration because some (system variable at least (note: but only? If so, we could just check
    // them here, not everything).

    val variables = policy.expandedVars + ((policy.trackerVariable.spec.name, policy.trackerVariable))

    for {
      stVariables <- bestEffort(policy.technique.getAllVariableSpecs) { variableSpec =>
                     variableSpec match {
                       case x : TrackerVariableSpec =>
                         variables.get(x.name) match {
                           case None    => Failure(s"[${agentNodeProps.nodeId.value}:${policy.technique.id}] Misssing mandatory value for tracker variable: '${x.name}'")
                           //case Some(v) => Full(Some(x.toVariable(v.values)))
                           case Some(v) => Full(Some(stVariableFromVariable(v, agentVariableHandler, agentNodeProps.nodeId, policy.technique.id)))
                         }
                       case x : SystemVariableSpec => systemVars.get(x.name) match {
                           case None =>
                             if(x.constraint.mayBeEmpty) { //ok, that's expected
                               logger.trace(s"[${agentNodeProps.nodeId.value}:${policy.technique.id}] Variable system named '${x.name}' not found in the extended variables environnement")
                               Full(None)
                             } else {
                               Failure(s"[${agentNodeProps.nodeId.value}:${policy.technique.id}] Missing value for system variable: '${x.name}'")
                             }
                           case Some(sysvar) =>
                             Full(Some(stVariableFromVariable(sysvar, agentVariableHandler, agentNodeProps.nodeId, policy.technique.id)))
                       }
                       case x : SectionVariableSpec =>
                         variables.get(x.name) match {
                           case None    => Failure(s"[${agentNodeProps.nodeId.value}:${policy.technique.id}] Misssing value for standard variable: '${x.name}'")
                           case Some(v) => Full(Some(stVariableFromVariable(v, agentVariableHandler, agentNodeProps.nodeId, policy.technique.id)))
                         }
                     }
                   }
    } yield {
      //return the collection without the none
      stVariables.flatten
    }
  }

}
