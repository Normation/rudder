/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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
package com.normation.rudder.services.policies.fetchinfo

import com.normation.box.*
import com.normation.errors.*
import com.normation.errors.IOResult
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.logger.PolicyGenerationLogger
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.properties.*
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.services.policies.*
import com.softwaremill.quicklens.*
import net.liftweb.common.*
import net.liftweb.json.*
import scala.annotation.nowarn
import zio.{System as _, *}
import zio.syntax.*

final case class NodesContextResult(
    ok:    Map[NodeId, InterpolationContext],
    error: Map[NodeId, String]
)

/*
 * This service build the interpolation context for nodes.
 * It means that it resolves all node properties from system variable, JS, and other
 * sources and create the "final" list of expended properties for the node.
 */
trait NodeContextBuilder {

  /**
   * Build interpolation contexts.
   *
   * An interpolation context is a node-dependant
   * context for resolving ("expanding", "binding")
   * interpolation variable in directive values.
   *
   * It's also the place where parameters are looked for
   * local overrides.
   */
  def getNodeContexts(
      nodeIds:              Set[NodeId],
      nodeFacts:            Map[NodeId, CoreNodeFact],
      inheritedProps:       Map[NodeId, ResolvedNodePropertyHierarchy],
      allGroups:            FullNodeGroupCategory,
      globalParameters:     List[GlobalParameter],
      globalAgentRun:       AgentRunInterval,
      globalComplianceMode: ComplianceMode,
      globalPolicyMode:     GlobalPolicyMode
  ): IOResult[NodesContextResult]
}

/*
 * Default implementation of the node context builder
 */
class NodeContextBuilderImpl(
    interpolatedValueCompiler: InterpolatedValueCompiler,
    systemVarService:          SystemVariableService
) extends NodeContextBuilder {

  // we should get the context to replace the value (PureResult[String] to String)
  // public: used in `TestNodeAndGlobalParameterLookup`
  def parseJValue(value: JValue, context: InterpolationContext): IOResult[JValue] = {
    def rec(v: JValue): IOResult[JValue] = v match {
      case JObject(l) => ZIO.foreach(l)(field => rec(field.value).map(x => field.copy(value = x))).map(JObject(_))
      case JArray(l)  => ZIO.foreach(l)(v => rec(v)).map(JArray(_))
      case JString(s) =>
        for {
          v <- interpolatedValueCompiler
                 .compile(s)
                 .toIO
                 .chainError(s"Error when looking for interpolation variable '${s}' in node property")
          s <- v(context)
        } yield {
          JString(s)
        }
      case x          => x.succeed
    }
    rec(value)
  }

  /**
   * Build interpolation contexts.
   *
   * An interpolation context is a node-dependant
   * context for resolving ("expanding", "binding")
   * interpolation variable in directive values.
   *
   * It's also the place where parameters are looked for
   * local overrides.
   */
  override def getNodeContexts(
      nodeIds:              Set[NodeId],
      nodeFacts:            Map[NodeId, CoreNodeFact],
      inheritedProps:       Map[NodeId, ResolvedNodePropertyHierarchy],
      allGroups:            FullNodeGroupCategory,
      globalParameters:     List[GlobalParameter],
      globalAgentRun:       AgentRunInterval,
      globalComplianceMode: ComplianceMode,
      globalPolicyMode:     GlobalPolicyMode
  ): IOResult[NodesContextResult] = {

    /*
     * parameters have to be taken apart:
     *
     * - they can be overridden by node - not handled here, it will be in the resolution of node
     *   when implemented. Most likely, we will have the information in the node info. And
     *   in that case, we could just use an interpolation variable
     *
     * - they can be plain string => nothing to do
     * - they can contains interpolated strings:
     *   - to node info parameters: ok
     *   - to parameters : hello loops!
     */
    def buildParams(
        parameters: List[GlobalParameter]
    ): PureResult[Map[GlobalParameter, ParamInterpolationContext => IOResult[String]]] = {
      parameters.accumulatePure { param =>
        for {
          p <- interpolatedValueCompiler
                 .compileParam(param.valueAsString)
                 .chainError(s"Error when looking for interpolation variable in global parameter '${param.name}'")
        } yield {
          (param, p)
        }
      }.map(_.toMap)
    }

    var timeNanoMergeProp = 0L
    for {
      globalSystemVariables <- systemVarService.getGlobalSystemVariables(globalAgentRun)
      parameters            <- buildParams(globalParameters).toBox ?~! "Can not parsed global parameter (looking for interpolated variables)"
    } yield {
      val all = nodeIds.foldLeft(NodesContextResult(Map(), Map())) {
        case (res, nodeId) =>
          (for {
            info              <- Box(nodeFacts.get(nodeId)) ?~! s"Node with ID ${nodeId.value} was not found"
            policyServer      <-
              Box(
                nodeFacts.get(info.rudderSettings.policyServerId)
              ) ?~! s"Policy server '${info.rudderSettings.policyServerId.value}' of Node '${nodeId.value}' was not found"
            context            = ParamInterpolationContext(
                                   info,
                                   policyServer,
                                   globalPolicyMode,
                                   parameters.map { case (p, i) => (p.name, i) }
                                 )
            nodeParam         <- ZIO
                                   .foreach(parameters.toList) {
                                     case (param, interpol) =>
                                       for {
                                         i <- interpol(context)
                                         v <- GenericProperty.parseValue(i).toIO
                                         p  = param.withValue(v)
                                       } yield {
                                         (p.name, p)
                                       }
                                   }
                                   .toBox
            nodeTargets        = allGroups.getTarget(info).map(_._2).toList
            timeMerge          = System.nanoTime
            mergedProps       <- inheritedProps.get(nodeId) match {
                                   case None                                  =>
                                     Full(Chunk.empty)
                                   case Some(s: SuccessNodePropertyHierarchy) =>
                                     Full(s.resolved)
                                   case Some(f: FailedNodePropertyHierarchy)  =>
                                     Failure(s"Property resolution failed for node ${nodeId.value} with error : ${f.getMessage}")

                                 }
            nodeContextBefore <- systemVarService.getSystemVariables(
                                   info,
                                   nodeFacts,
                                   nodeTargets,
                                   globalSystemVariables,
                                   globalAgentRun,
                                   globalComplianceMode: ComplianceMode
                                 )
            // Not sure if I should InterpolationContext or create a "EngineInterpolationContext
            contextEngine      = InterpolationContext(
                                   info,
                                   policyServer,
                                   globalPolicyMode,
                                   nodeContextBefore,
                                   nodeParam.map { case (k, g) => (k, g.value) }.toMap
                                 )
            _                  = { timeNanoMergeProp = timeNanoMergeProp + System.nanoTime - timeMerge }
            propsCompiled     <- ZIO
                                   .foreach(mergedProps) { p =>
                                     for {
                                       x     <- parseJValue(p.prop.toJsonObj, contextEngine)
                                       // we need to fetch only the value, and nothing else, for the property
                                       value  = GenericProperty.fromJsonValue(x.\("value")): @nowarn("msg=deprecated")
                                       result = NodeProperty(p.prop.config.getString("name"), value, None, None)
                                     } yield {
                                       result
                                     }
                                   }
                                   .toBox
            nodeInfo           = info.modify(_.properties).setTo(Chunk.fromIterable(propsCompiled))
            nodeContext       <- systemVarService.getSystemVariables(
                                   nodeInfo,
                                   nodeFacts,
                                   nodeTargets,
                                   globalSystemVariables,
                                   globalAgentRun,
                                   globalComplianceMode: ComplianceMode
                                 )
            // now we set defaults global parameters to all nodes
            withDefaults      <- CompareProperties
                                   .updateProperties(
                                     nodeParam.toList.map { case (k, v) => NodeProperty(k, v.value, v.inheritMode, None) },
                                     Some(nodeInfo.properties.toList)
                                   )
                                   .map(p => nodeInfo.modify(_.properties).setTo(Chunk.fromIterable(p)))
                                   .toBox
          } yield {
            (
              nodeId,
              InterpolationContext(
                withDefaults,
                policyServer,
                globalPolicyMode,
                nodeContext,
                nodeParam.map { case (k, g) => (k, g.value) }.toMap
              )
            )
          }) match {
            case eb: EmptyBox =>
              val e =
                eb ?~! s"Error while building target configuration node for node '${nodeId.value}' which is one of the target of rules. Ignoring it for the rest of the process"
              PolicyGenerationLogger.error(e.messageChain)
              res.copy(error = res.error + ((nodeId, e.messageChain)))

            case Full(x) => res.copy(ok = res.ok + x)
          }
      }
      PolicyGenerationLogger.timing.debug(
        s"Merge group properties took ${timeNanoMergeProp / (1_000_000)} ms for ${nodeIds.size} nodes"
      )
      all
    }
  }.toIO

}
