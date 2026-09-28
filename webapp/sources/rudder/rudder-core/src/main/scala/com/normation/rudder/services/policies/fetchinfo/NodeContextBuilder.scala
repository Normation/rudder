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
import com.normation.rudder.domain.nodes.NodeAndServerIds
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.properties.*
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.services.policies.*
import com.normation.rudder.tenants.TenantReachIndex
import com.softwaremill.quicklens.*
import net.liftweb.common.*
import zio.{System as _, *}
import zio.json.*
import zio.json.ast.*
import zio.json.ast.Json.*
import zio.syntax.*

final case class NodesContextResult(
    ok:    Map[NodeId, InterpolationContext],
    error: Map[NodeId, String]
)

/*
 * Which global properties reach a node at generation:
 * - the ones its scope admits (ADR 29409)
 * - and the one whose tenants can see the node (ADR 28945-global-parameters-and-tenant-scoping).
 *
 * Both filters are per node and resolved once for the fleet: a scope to its node ids per property,
 * the tenant reach memoized on the node tag.
 */
final class GlobalPropertyDistribution[A] private (
    unscoped: TenantReachIndex[(GlobalParameter, A)],
    scoped:   TenantReachIndex[(Set[NodeId], (GlobalParameter, A))]
) {

  def forNode(node: CoreNodeFact): List[(GlobalParameter, A)] = {
    val security       = node.rudderSettings.security
    val inScope        = scoped.reaching(security).collect { case (ids, p) if ids.contains(node.id) => p }
    val unscopedByName = unscoped.reaching(security).map(p => (p._1.name, p)).toMap
    // a scoped property overrides the unscoped one of the same name, as in the property hierarchy
    (unscopedByName ++ inScope.map(p => (p._1.name, p))).values.toList
  }
}

object GlobalPropertyDistribution {

  def apply[A](
      globalProperties: Map[GlobalParameter, A],
      allGroups:        FullNodeGroupCategory,
      nodeFacts:        Map[NodeId, CoreNodeFact]
  ): GlobalPropertyDistribution[A] = {
    val ids                = NodeAndServerIds.fromFacts(nodeFacts)
    val (unscoped, scoped) = globalProperties.toList.partitionMap {
      case (p, a) =>
        p.scope match {
          case None    => Left((p, a))
          case Some(t) => Right((allGroups.getNodeIds(Set(t), ids), (p, a)))
        }
    }
    new GlobalPropertyDistribution(
      TenantReachIndex(unscoped.map { case (p, a) => (p.security, (p, a)) }),
      TenantReachIndex(scoped.map { case (nodeIds, (p, a)) => (p.security, (nodeIds, (p, a))) })
    )
  }
}

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
  def parseJValue(value: Json, context: InterpolationContext): IOResult[Json] = {
    def rec(v: Json): IOResult[Json] = v match {
      case Obj(l) => ZIO.foreach(l)((k, v) => rec(v).map((k, _))).map(Obj(_))
      case Arr(l) => ZIO.foreach(l)(v => rec(v)).map(Arr(_))
      case Str(s) =>
        for {
          v <- interpolatedValueCompiler
                 .compile(s)
                 .toIO
                 .chainError(s"Error when looking for interpolation variable '${s}' in node property")
          s <- v(context)
        } yield {
          Str(s)
        }
      case x      => x.succeed
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
     * A global parameter value can still be interpolated (`${rudder.node.x}`,
     * `${node.properties[x]}`, property engines), but it can not reference an other global
     * parameter anymore, so there is no cycle to fear here.
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
      val distribution = GlobalPropertyDistribution(parameters, allGroups, nodeFacts)

      val all = nodeIds.foldLeft(NodesContextResult(Map(), Map())) {
        case (res, nodeId) =>
          (for {
            info              <- Box(nodeFacts.get(nodeId)) ?~! s"Node with ID ${nodeId.value} was not found"
            policyServer      <-
              Box(
                nodeFacts.get(info.rudderSettings.policyServerId)
              ) ?~! s"Policy server '${info.rudderSettings.policyServerId.value}' of Node '${nodeId.value}' was not found"
            context            = ParamInterpolationContext(info, policyServer, globalPolicyMode)
            nodeParam         <- ZIO
                                   .foreach(distribution.forNode(info)) {
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
                                   nodeParam.toMap
                                 )
            _                  = { timeNanoMergeProp = timeNanoMergeProp + System.nanoTime - timeMerge }
            propsCompiled     <- ZIO
                                   .foreach(mergedProps) { p =>
                                     for {
                                       x     <- parseJValue(p.prop.jsonZio, contextEngine)
                                       // we need to fetch only the value, and nothing else, for the property
                                       value  = GenericProperty.fromZioJson(x)
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
                nodeParam.toMap
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
