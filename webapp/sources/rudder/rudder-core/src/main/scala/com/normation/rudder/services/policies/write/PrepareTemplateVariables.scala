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

import com.normation.cfclerk.domain.*
import com.normation.cfclerk.services.SystemVariableSpecService
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.errors.*
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.Certificate
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.logger.PolicyGenerationLoggerPure
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.PolicyMode.Enforce
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.services.policies.NodeConfiguration
import com.normation.rudder.services.policies.ParameterEntry
import com.normation.rudder.services.policies.Policy
import com.normation.templates.STVariable
import com.normation.zio.*
import org.joda.time.DateTime
import scala.collection.immutable.ArraySeq
import zio.*
import zio.syntax.*

case class PrepareTemplateTimer(
    buildBundleSeq:  Ref[Long],
    buildAgentVars:  Ref[Long],
    prepareTemplate: Ref[Long]
)

object PrepareTemplateTimer {
  def make(): ZIO[Any, Nothing, PrepareTemplateTimer] = for {
    a <- Ref.make(0L)
    b <- Ref.make(0L)
    c <- Ref.make(0L)
  } yield PrepareTemplateTimer(a, b, c)
}

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
      agentNodeConfig:   AgentNodeConfiguration,
      nodeConfigVersion: NodeConfigId,
      rootNodeConfigId:  NodeId,
      templates:         Map[(TechniqueResourceId, AgentType), TechniqueTemplateCopyInfo],
      allNodeConfigs:    Map[NodeId, NodeConfiguration],
      rudderIdCsvTag:    String,
      globalPolicyMode:  GlobalPolicyMode,
      generationTime:    DateTime,
      timer:             PrepareTemplateTimer
  ): IOResult[AgentNodeWritableConfiguration]

}

/**
 * This class is responsible of transforming a NodeConfiguration for a given agent type
 * into a set of templates and variables that could be filled to string template.
 */
class PrepareTemplateVariablesImpl(
    techniqueRepository: TechniqueRepository, // only for getting reports file content

    systemVariableSpecService: SystemVariableSpecService,
    buildBundleSequence:       BuildBundleSequence,
    agentRegister:             AgentRegister
) extends PrepareTemplateVariables {

  override def prepareTemplateForAgentNodeConfiguration(
      agentNodeConfig:   AgentNodeConfiguration,
      nodeConfigVersion: NodeConfigId,
      rootNodeConfigId:  NodeId,
      templates:         Map[(TechniqueResourceId, AgentType), TechniqueTemplateCopyInfo],
      allNodeConfigs:    Map[NodeId, NodeConfiguration],
      rudderIdCsvTag:    String,
      globalPolicyMode:  GlobalPolicyMode,
      generationTime:    DateTime,
      timer:             PrepareTemplateTimer
  ): IOResult[AgentNodeWritableConfiguration] = {

    import com.normation.rudder.services.policies.SystemVariableService.*

    val nodeId = agentNodeConfig.config.nodeInfo.id

    // Computing policy mode of the node
    val agentPolicyMode = {
      PolicyMode.computeMode(globalPolicyMode, agentNodeConfig.config.nodeInfo.rudderSettings.policyMode, Seq()) match {
        case Left(r)      =>
          PolicyGenerationLoggerPure.error(
            s"Failed to compute policy mode for node ${agentNodeConfig.config.nodeInfo.id.value}, cause is ${r} - defaulting to enforce"
          ); Enforce
        case Right(value) => value
      }
    }

    val systemVariables = agentNodeConfig.config.nodeContext ++ List(
      systemVariableSpecService
        .get("COMMUNITY")
        .toVariable(if (agentNodeConfig.agentType == AgentType.CfeCommunity) Seq("true") else Seq()),
      systemVariableSpecService.get("AGENT_TYPE").toVariable(Seq(agentNodeConfig.agentType.toString)),
      systemVariableSpecService.get("RUDDER_NODE_CONFIG_ID").toVariable(Seq(nodeConfigVersion.value)),
      systemVariableSpecService.get("RUDDER_COMPLIANCE_MODE").toVariable(Seq(agentPolicyMode.name)),
      // For compatibility with pre-8.0 agents
      systemVariableSpecService.get("RUDDER_VERIFY_CERTIFICATES").toVariable(Seq("false"))
    ).map(x => (x.spec.name, x)).toMap

    val agentNodeProps = AgentNodeProperties(
      nodeId,
      agentNodeConfig.agentType,
      agentNodeConfig.config.nodeInfo.os,
      agentNodeConfig.config.nodeInfo.rudderSettings.isPolicyServer
    )

    /*
     * Policy server certificates for the node.
     * We don't fail policy generation when a certificate is missing, but we trace
     * the problem in log.
     */
    val policyServerCertificates = {
      def getCertificate(nodeId: NodeId): IOResult[String] = {
        allNodeConfigs.get(nodeId) match {
          case None    =>
            "".succeed.tap(_ => {
              PolicyGenerationLoggerPure.error(
                s"Policy server '${nodeId.value}' was not found during generation: can not provide its certificate"
              )
            })
          case Some(n) =>
            n.nodeInfo.rudderAgent.securityToken match {
              case cert: Certificate =>
                cert.key.succeed
            }
        }
      }

      val root         = getCertificate(Constants.ROOT_POLICY_SERVER_ID)
      val policyServer = agentNodeConfig.config.nodeInfo.rudderSettings.policyServerId match {
        case Constants.ROOT_POLICY_SERVER_ID => None
        case id                              => Some(getCertificate(id))
      }
      PolicyServerCertificates(root, policyServer)
    }

    for {
      res                        <- for {
                                      t0           <- currentTimeMillis
                                      bundleVars   <- buildBundleSequence.prepareBundleVars(
                                                        agentNodeProps,
                                                        agentNodeConfig.config.nodeInfo.rudderSettings.policyMode,
                                                        globalPolicyMode,
                                                        agentNodeConfig.config.policies,
                                                        agentNodeConfig.config.runHooks
                                                      )
                                      t1           <- currentTimeMillis
                                      _            <- timer.buildBundleSeq.update(_ + t1 - t0)
                                      parameters   <- ZIO.foreach(agentNodeConfig.config.parameters) { x =>
                                                        agentRegister
                                                          .findMap(agentNodeProps) { agent =>
                                                            Right(ParameterEntry(x.name, agent.escape(x.value), agentNodeConfig.agentType))
                                                          }
                                                          .toIO
                                                      }
                                      allSystemVars = systemVariables ++ bundleVars
                                      t2           <- currentTimeMillis
                                      _            <- timer.buildAgentVars.update(_ + t2 - t1)
                                    } yield {
                                      (parameters, allSystemVars)
                                    }
      (parameters, allSystemVars) = res
      t2                         <- currentTimeMillis
      preparedTemplate           <- prepareTechniqueTemplate(
                                      agentNodeProps,
                                      agentNodeConfig.config.policies,
                                      parameters.toList,
                                      allSystemVars,
                                      templates,
                                      generationTime.getMillis
                                    )
      t3                         <- currentTimeMillis
      _                          <- timer.prepareTemplate.update(_ + t3 - t2)
    } yield {
      AgentNodeWritableConfiguration(
        agentNodeProps,
        agentNodeConfig.paths,
        preparedTemplate,
        allSystemVars,
        allNodeConfigs.get(nodeId).map(_.policies).getOrElse(Nil),
        policyServerCertificates
      )
    }
  }

  private def prepareTechniqueTemplate(
      agentNodeProps:      AgentNodeProperties,
      policies:            List[Policy],
      parameters:          Seq[ParameterEntry],
      systemVars:          Map[String, Variable],
      allTemplates:        Map[(TechniqueResourceId, AgentType), TechniqueTemplateCopyInfo],
      generationTimestamp: Long
  ): IOResult[Seq[PreparedTechnique]] = {

    val rudderParametersVariable =
      STVariable(PARAMETER_VARIABLE, mayBeEmpty = true, values = parameters.to(ArraySeq), isSystem = true)
    val generationVariable       =
      STVariable("GENERATIONTIMESTAMP", mayBeEmpty = false, values = ArraySeq(generationTimestamp), isSystem = true)

    for {
      variableHandler    <-
        agentRegister
          .findHandler(agentNodeProps)
          .toIO
          .chainError(s"Error when trying to fetch variable escaping method for node ${agentNodeProps.nodeId.value}")
      // here, `traverse` seems to give similar but more consistent results than `traverseParN`
      preparedTechniques <- ZIO.foreach(policies) { p =>
                              for {
                                _         <-
                                  PolicyGenerationLoggerPure.trace(
                                    s"Processing node '${agentNodeProps.nodeId.value}':${p.ruleName}/${p.directiveName} [${p.id.value}]"
                                  )
                                variables <-
                                  prepareVariables(agentNodeProps, variableHandler, p, systemVars).chainError(
                                    s"Error when trying to build variables for technique(s) in node ${agentNodeProps.nodeId.value}"
                                  )
                              } yield {
                                val techniqueTemplatesIds = p.technique.templatesIds
                                // only set if technique is multi-policy
                                val reportId              = p.technique.generationMode match {
                                  case TechniqueGenerationMode.MultipleDirectives => Some(p.id)
                                  case _                                          => None
                                }
                                // if technique is multi-policy, we need to update destination path to add an unique id along with the version
                                // to have one directory by directive.
                                val techniqueTemplates    = {
                                  val templates = allTemplates.view
                                    .filterKeys(k => techniqueTemplatesIds.contains(k._1) && k._2 == agentNodeProps.agentType)
                                    .values
                                    .toSet
                                  p.technique.generationMode match {
                                    case TechniqueGenerationMode.MultipleDirectives =>
                                      templates.map(copyInfo =>
                                        copyInfo.copy(destination = Policy.makeUniqueDest(copyInfo.destination, p))
                                      )
                                    case _                                          =>
                                      templates
                                  }
                                }
                                val files                 = {
                                  val files = p.technique.agentConfig.files.toSet[TechniqueFile]
                                  p.technique.generationMode match {
                                    case TechniqueGenerationMode.MultipleDirectives =>
                                      files.map(file => file.copy(outPath = Policy.makeUniqueDest(file.outPath, p)))
                                    case _                                          =>
                                      files
                                  }
                                }

                                PreparedTechnique(
                                  techniqueTemplates,
                                  variables :+ rudderParametersVariable :+ generationVariable,
                                  files,
                                  reportId
                                )
                              }
                            }
    } yield {
      preparedTechniques
    }
  }

  // Create a STVariable from a Variable
  private def stVariableFromVariable(
      v:                Variable,
      variableEscaping: AgentSpecificGeneration,
      nodeId:           NodeId,
      techniqueId:      TechniqueId
  ): IOResult[STVariable] = {
    for {
      values <-
        v.getValidatedValue(variableEscaping.escape)
          .toIO
          .chainError(
            s"Error when preparing variable for node with ID '${nodeId.value}' on Technique '${techniqueId.debugString}: wrong value type for variable '${v.spec.name}'"
          )
    } yield {
      STVariable(
        name = v.spec.name,
        mayBeEmpty = v.spec.constraint.mayBeEmpty,
        values = values.to(ArraySeq),
        v.spec.isSystem
      )
    }
  }

  private def prepareVariables(
      agentNodeProps:       AgentNodeProperties,
      agentVariableHandler: AgentSpecificGeneration,
      policy:               Policy,
      systemVars:           Map[String, Variable]
  ): IOResult[Seq[STVariable]] = {

    // here, we want to be sure to get the system variable that were late defined in priority.
    ZIO.foreach(policy.trackerVariable :: (policy.expandedVars ++ systemVars).values.toList)(v =>
      stVariableFromVariable(v, agentVariableHandler, agentNodeProps.nodeId, policy.technique.id)
    )
  }

}
