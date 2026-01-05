/*
 *************************************************************************************
 * Copyright 2019 Normation SAS
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

package com.normation.rudder.services.policies

import ch.qos.logback.classic.Logger
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.appconfig.FeatureSwitch
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import com.normation.rudder.domain.policies.ActiveTechniqueId
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.FullGroupTarget
import com.normation.rudder.domain.policies.FullRuleTargetInfo
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.PolicyModeOverrides
import com.normation.rudder.domain.policies.PolicyTypes
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.properties.ResolvedNodePropertyHierarchy
import com.normation.rudder.domain.reports.NodeModeConfig
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.repository.FullActiveTechnique
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.services.nodes.PropertyEngineServiceImpl
import com.softwaremill.quicklens.*
import org.joda.time.DateTime
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import scala.collection.SortedMap
import scala.concurrent.duration.*

/*
 * This class test the JsEngine. 6.0
 * It must works identically on Java 7 and Java 8.
 *
 */

@RunWith(classOf[JUnitRunner])
class TestBuildNodeConfiguration extends Specification {

  // a logger for timing information
  val logger: Logger = org.slf4j.LoggerFactory.getLogger("timing-test").asInstanceOf[ch.qos.logback.classic.Logger]
  // set to trace to see timing
  logger.setLevel(ch.qos.logback.classic.Level.OFF)

  sequential

  val t0: Long = System.currentTimeMillis()

  import NodeConfigData.*
  val data = new TestNodeConfiguration()
  val t0_1: Long = System.currentTimeMillis()
  logger.trace(s"Test node configuration   : ${t0_1 - t0} ms")

  def newNode(i: Int): CoreNodeFact =
    fact1.modify(_.id).setTo(NodeId("node" + i)).modify(_.fqdn).setTo(s"node$i.localhost")

  val allNodes:   Map[NodeId, CoreNodeFact]   = ((1 to 100).map(newNode) :+ factRoot).map(n => (n.id, n)).toMap
  // only one group with all nodes
  val group:      NodeGroup                   = {
    NodeGroup(
      NodeGroupId(NodeGroupUid("allnodes")),
      name = "allnodes",
      description = "",
      properties = Nil,
      query = None,
      isDynamic = false,
      serverList = allNodes.keySet.toSet,
      _isEnabled = true,
      security = None
    )
  }
  val groupLib:   FullNodeGroupCategory       = FullNodeGroupCategory(
    NodeGroupCategoryId("test_root"),
    "",
    "",
    Nil,
    List(
      FullRuleTargetInfo(
        FullGroupTarget(GroupTarget(group.id), group),
        name = "",
        description = "",
        isEnabled = true,
        isSystem = false,
        security = None
      )
    ),
    isSystem = false,
    security = None
  )
  val directives: Map[DirectiveId, Directive] = {
    ((1 to 100).map(i => data.rpmDirective("rpm" + i, "somepkg" + i)) :+
    // add a directive with a node property with a default value of " " - it should work, see https://issues.rudder.io/issues/25557
    data.rpmDirective("blank_default_node", """${node.properties[udp_open_ports] | default=" "}""")).map(d => (d.id, d))
  }.toMap

  val directiveLib: FullActiveTechniqueCategory = FullActiveTechniqueCategory(
    ActiveTechniqueCategoryId("root"),
    name = "root",
    description = "",
    subCategories = Nil,
    activeTechniques = List(
      FullActiveTechnique(
        ActiveTechniqueId("common"),
        techniqueName = data.commonTechnique.id.name,
        acceptationDatetimes = SortedMap(data.commonTechnique.id.version -> DateTime.now),
        techniques = SortedMap(data.commonTechnique.id.version -> data.commonTechnique),
        directives = List(data.commonDirective),
        isEnabled = true,
        policyTypes = PolicyTypes.rudderSystem,
        security = None
      ),
      FullActiveTechnique(
        ActiveTechniqueId("rpmPackageInstallation"),
        techniqueName = data.rpmTechnique.id.name,
        acceptationDatetimes = SortedMap(data.rpmTechnique.id.version -> DateTime.now),
        techniques = SortedMap(data.rpmTechnique.id.version -> data.rpmTechnique),
        directives = directives.values.toList,
        isEnabled = true,
        policyTypes = PolicyTypes.rudderSystem,
        security = None
      )
    ),
    isSystem = true,
    security = None
  )

  val rule: Rule = Rule(
    RuleId(RuleUid("rule")),
    name = "rule",
    categoryId = RuleCategoryId("rootcat"),
    targets = Set(GroupTarget(group.id)),
    directiveIds = directiveLib.allDirectives.keySet,
    shortDescription = "",
    longDescription = "",
    isEnabledStatus = true,
    isSystem = true,
    security = None
  )
  val propertyEngineService = new PropertyEngineServiceImpl(List.empty)
  val valueCompiler         = new InterpolatedValueCompilerImpl(propertyEngineService)
  val ruleValService        = new RuleValServiceImpl(valueCompiler)
  val buildContext:     PromiseGeneration_BuildNodeContext = new PromiseGeneration_BuildNodeContext {
    override def interpolatedValueCompiler: InterpolatedValueCompiler = valueCompiler
    override def systemVarService:          SystemVariableService     = data.systemVariableService
  }
  val globalPolicyMode: GlobalPolicyMode                   = GlobalPolicyMode(PolicyMode.Enforce, PolicyModeOverrides.Always)
  val activeNodeIds:    Set[NodeId]                        = Set(rootId, node1Node.id, node2Node.id)
  val allNodeModes:     Map[NodeId, NodeModeConfig]        = allNodes.map { case (id, _) => (id, defaultModesConfig) }.toMap
  val scriptEngineEnabled = FeatureSwitch.Disabled
  val maxParallelism      = 8
  val jsTimeout: FiniteDuration = FiniteDuration(5, "minutes")
  val generationContinueOnError = false

  val inheritedProps: Map[NodeId, ResolvedNodePropertyHierarchy] = Map()

  // you can debug detail timing by setting "TRACE" level below:
  org.slf4j.LoggerFactory
    .getLogger("policy.generation")
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.INFO)

  "build node configuration" in {

    logger.trace(s"init   : ${System.currentTimeMillis - t0} ms")

    logger.trace("\n--------------------------------")
    val t1           = System.currentTimeMillis()
    val ruleVal      =
      ruleValService.buildRuleVal(rule, directiveLib, groupLib, allNodes.view.mapValues(_.rudderSettings.isPolicyServer).toMap)
    val ruleVals     = Seq(ruleVal.getOrElse(throw new RuntimeException("oups")))
    val t2           = System.currentTimeMillis()
    val nodeContexts = buildContext
      .getNodeContexts(
        allNodes.keySet.toSet,
        allNodes,
        inheritedProps,
        groupLib,
        Nil,
        data.globalAgentRun,
        data.globalComplianceMode,
        globalPolicyMode
      )
      .getOrElse(throw new RuntimeException("oups"))
    val t3           = System.currentTimeMillis()
    val res          = BuildNodeConfiguration
      .buildNodeConfigurations(
        activeNodeIds,
        ruleVals,
        nodeContexts.ok,
        allNodeModes,
        Map(),
        scriptEngineEnabled,
        globalPolicyMode,
        maxParallelism,
        jsTimeout,
        generationContinueOnError
      )
      .openOrThrowException(throw new RuntimeException(s"Error: node configuration failed"))
    val t4           = System.currentTimeMillis()

    logger.trace(s"ruleval: ${t2 - t1} ms")
    logger.trace(s"context: ${t3 - t2} ms")
    logger.trace(s"config : ${t4 - t3} ms")

    res.errors == Nil
  }
}
