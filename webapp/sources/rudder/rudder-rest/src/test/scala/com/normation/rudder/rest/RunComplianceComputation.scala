/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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

package com.normation.rudder.rest

import better.files.File
import com.normation.cfclerk.domain.TechniqueVersionHelper
import com.normation.errors
import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.FullInventory
import com.normation.inventory.domain.InventoryStatus
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.Software
import com.normation.rudder.MockDirectives
import com.normation.rudder.MockGitConfigRepo
import com.normation.rudder.MockTechniques
import com.normation.rudder.domain.archives.RuleArchiveId
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.reports.*
import com.normation.rudder.facts.nodes.*
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.reports.ComplianceModeName.FullCompliance
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.repository.*
import com.normation.rudder.rest.lift.*
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.services.policies.NodeConfigData
import com.normation.rudder.services.policies.PolicyId
import com.normation.rudder.services.reports.ComputeCompliance
import com.normation.rudder.services.reports.InMemoryNodeStatusReportStorage
import com.normation.rudder.services.reports.NodeStatusReportInternal
import com.normation.rudder.services.reports.NodeStatusReportRepositoryImpl
import com.normation.rudder.services.reports.ReportingService
import com.normation.rudder.services.reports.ReportingServiceImpl2
import com.normation.rudder.tenants.*
import com.normation.zio.*
import org.joda.time.DateTime
import scala.collection.MapView
import scala.collection.immutable.SortedMap
import zio.*
import zio.syntax.*

/*
 * Run a compliance computation (the same that is given by API for rules,
 * ie the same that is used when opening rules page).
 * The number of nodes and rules is given as parameter of the set-up.
 *
 * The computation is instrumented by async-profiler: https://github.com/async-profiler/async-profiler
 * And the result is written the `file` as a flamegraph.
 */
object RunTestCompliance {

  // parameters of the run
  val setUp = new SetUpCompliance(numNodes = 5000, numRules = 300)
  // write result
  val file  = "/tmp/test_compliance_computation/%p.html"

  File(file).parent.createDirectories()

  val cs       = setUp.complianceAPIService
  val profiler = one.profiler.AsyncProfiler.getInstance();

  def main(args: Array[String]): Unit = {
    println(s"pid: ${new java.io.File("/proc/self").getCanonicalFile().getName()}")
    println(s"events: ${profiler.execute("list")}")

    // Parameter line for the profiler. here, we build a flamegraph based on CPU
    // and default parameters. The flamegraph will be written in html in `file`.
    // See https://github.com/async-profiler/async-profiler/blob/v4.0/src/arguments.cpp
    // for more parameters. You can look for memory allocation, time spent in tree form, etc.
    profiler.execute(s"start,event=cpu,tree,file=${file}")
    println(s"computing compliance...")

    // This is the actual thing we want to measure
    /////////////// -- ///////////////
    val (t, x) = cs.getRulesCompliancePure(Some(1))(using QueryContext.systemQC).timed.runNow
    /////////////// -- ///////////////

    // it looks like if we want to be sure to have the file written, we must dump it before stop...
    profiler.execute(s"dump,file=${file}")
    profiler.execute("stop")
    // and give some time to finish writing.
    Thread.sleep(100)

    println(s"Number rule compliance: ${x.size}")
    x.sortBy(_.name).foreach(println)
    println(s"Done in ********> ${t.render}")
    java.lang.System.exit(0)
  }
}

class SetUpCompliance(numNodes: Int, numRules: Int) {

  private val mockGitRepo    = new MockGitConfigRepo("")
  private val mockTechniques = MockTechniques(mockGitRepo)
  private val mockDirectives = new MockDirectives(mockTechniques)
  private val directives     = mockDirectives.directives

  private val kindNodes = 6
  private val nodeRange = (1 to numNodes).by(kindNodes)
  private val kindRules = 6
  private val ruleRange = (1 to numRules).by(kindRules)

  private def nodeId(id:      Int): NodeId      = NodeId(f"bn${id}%04d")
  private def ruleId(id:      Int): RuleId      = RuleId(RuleUid(f"br${id}%04d"))
  private def nodeGroupId(id: Int): NodeGroupId = NodeGroupId(NodeGroupUid(f"bg${id}%04d"))

  private def nodeGroupsRepo(nodeGroups: List[NodeGroup]) = new RoNodeGroupRepository {
    val nodesByGroup: Map[NodeGroupId, Chunk[NodeId]] = nodeGroups.map(g => (g.id, Chunk.fromIterable(g.serverList))).toMap

    override def getAllNodeIdsChunk(): IOResult[Map[NodeGroupId, Chunk[NodeId]]] = {
      nodesByGroup.succeed
    }

    override def getNodeGroupOpt(
        id: NodeGroupId
    )(implicit qc: QueryContext): IOResult[Option[(NodeGroup, NodeGroupCategoryId)]] = {
      nodeGroups.find(_.id == id).map((_, NodeGroupCategoryId("cat1"))).succeed
    }

    override def getFullGroupLibrary()(implicit qc: QueryContext): IOResult[FullNodeGroupCategory] = {
      FullNodeGroupCategory(
        NodeGroupCategoryId("GroupRoot"),
        name = "GroupRoot",
        description = "root of group categories",
        subCategories = Nil,
        targetInfos = nodeGroups.map(g => {
          FullRuleTargetInfo(FullGroupTarget(GroupTarget(g.id), g), g.name, g.description, g.isEnabled, g.isSystem, g.security)
        }),
        isSystem = true,
        security = None
      ).succeed
    }

    def categoryExists(id:       NodeGroupCategoryId): IOResult[Boolean]           = ???
    def getNodeGroupCategory(id: NodeGroupId):         IOResult[NodeGroupCategory] = ???
    def getAll(): IOResult[Seq[NodeGroup]] = ???
    def getAllByIds(ids: Seq[NodeGroupId]): IOResult[Seq[NodeGroup]] = ???
    def getAllNodeIds(): IOResult[Map[NodeGroupId, Set[NodeId]]] = ???
    def getGroupsByCategory(includeSystem: Boolean)(implicit
        qc: QueryContext
    ): IOResult[SortedMap[List[NodeGroupCategoryId], CategoryAndNodeGroup]] = ???
    def findGroupWithAnyMember(nodeIds: Seq[NodeId]): IOResult[Seq[NodeGroupId]] = ???
    def findGroupWithAllMember(nodeIds: Seq[NodeId]): IOResult[Seq[NodeGroupId]] = ???
    def getRootCategory():     NodeGroupCategory                                                 = ???
    def getRootCategoryPure(): IOResult[NodeGroupCategory]                                       = ???
    def getCategoryHierarchy:  IOResult[SortedMap[List[NodeGroupCategoryId], NodeGroupCategory]] = ???
    def getAllGroupCategories(includeSystem: Boolean):             IOResult[Seq[NodeGroupCategory]]  = ???
    def getGroupCategory(id:                 NodeGroupCategoryId): IOResult[NodeGroupCategory]       = ???
    def getParentGroupCategory(id:           NodeGroupCategoryId): IOResult[NodeGroupCategory]       = ???
    def getParents_NodeGroupCategory(id:     NodeGroupCategoryId): IOResult[List[NodeGroupCategory]] = ???
    def getAllNonSystemCategories(): IOResult[Seq[NodeGroupCategory]] = ???
  }

  object nodeFactRepo extends NodeFactRepository {
    override def getAll()(implicit qc: QueryContext, status: SelectNodeStatus): IOResult[MapView[NodeId, CoreNodeFact]] = {
      val _                                           = (qc, status) // ignore "unused" warning
      def build(id: NodeId, mode: Option[PolicyMode]) = {
        val nodeFact = NodeConfigData.fact1
        nodeFact.copy(id = id, rudderSettings = nodeFact.rudderSettings.copy(policyMode = mode))
      }
      // build nodes 1 to 500
      val nodes                                       = nodeRange.flatMap { i =>
        Seq(
          build(nodeId(i), Some(PolicyMode.Audit)),
          build(nodeId(i + 1), Some(PolicyMode.Audit)),
          build(nodeId(i + 2), Some(PolicyMode.Enforce)),
          build(nodeId(i + 3), Some(PolicyMode.Enforce)),
          build(nodeId(i + 4), Some(PolicyMode.Enforce))
        )
      }

      nodes.map(n => (n.id, n)).toMap.view.succeed
    }

    def getNumberOfManagedNodes(): IOResult[RuntimeFlags] = ???
    def registerChangeCallbackAction(callback: NodeFactChangeEventCallback): IOResult[Unit] = ???
    def getStatus(id:                          NodeId)(implicit qc:   QueryContext): IOResult[InventoryStatus] = ???
    def get(nodeId:                            NodeId)(implicit qc:   QueryContext, status:    SelectNodeStatus): IOResult[Option[CoreNodeFact]]  = ???
    def slowGet(
        nodeId: NodeId
    )(implicit qc: QueryContext, status: SelectNodeStatus, attrs: SelectFacts): IOResult[Option[NodeFact]] = ???
    def getNodesBySoftwareName(softName:       String): IOResult[List[(NodeId, Software)]] = ???
    def slowGetAll()(implicit qc:              QueryContext, status:  SelectNodeStatus, attrs: SelectFacts):      errors.IOStream[NodeFact]       = ???
    def save(nodeFact:                         NodeFact)(implicit cc: ChangeContext, attrs:    SelectFacts):      IOResult[NodeFactChangeEventCC] = ???
    def setSecurityTag(nodeId: NodeId, tag: Option[SecurityTag])(implicit cc: ChangeContext): IOResult[NodeFactChangeEventCC] =
      ???
    def updateInventory(inventory: FullInventory, software: Option[Iterable[Software]])(implicit
        cc: ChangeContext
    ): IOResult[NodeFactChangeEventCC] = ???
    def changeStatus(nodeId: NodeId, into: InventoryStatus)(implicit cc: ChangeContext): IOResult[NodeFactChangeEventCC] = ???
    def delete(nodeId: NodeId)(implicit cc: ChangeContext): IOResult[NodeFactChangeEventCC] = ???
  }

  // We want to ignore rules that are defined in `MockRules` because they may target all nodes and pollute our compliance tests
  private def rulesRepo(rules: List[Rule]) = new RoRuleRepository with WoRuleRepository {
    override def getOpt(ruleId: RuleId):         IOResult[Option[Rule]] = {
      rules.find(_.id == ruleId).succeed
    }
    override def getAll(includeSystem: Boolean): IOResult[Seq[Rule]]    = {
      rules.succeed
    }

    override def getIds(includeSystem:            Boolean): IOResult[Set[RuleId]] = ???
    override def create(rule:                     Rule, modId:   ModificationId, actor: EventActor, reason: Option[String]): IOResult[AddRuleDiff] = ???
    override def update(
        rule:   Rule,
        modId:  ModificationId,
        actor:  EventActor,
        reason: Option[String]
    ): IOResult[Option[ModifyRuleDiff]] = ???
    override def load(rule:                       Rule, modId:   ModificationId, actor: EventActor, reason: Option[String]): IOResult[Unit]        = ???
    override def unload(ruleId:                   RuleId, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[Unit]        = ???
    override def updateSystem(
        rule:   Rule,
        modId:  ModificationId,
        actor:  EventActor,
        reason: Option[String]
    ): IOResult[Option[ModifyRuleDiff]] = ???
    override def delete(id: RuleId, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[DeleteRuleDiff] =
      ???
    override def deleteSystemRule(
        id:     RuleId,
        modId:  ModificationId,
        actor:  EventActor,
        reason: Option[String]
    ): IOResult[DeleteRuleDiff] = ???
    override def swapRules(newRules:              Seq[Rule]): IOResult[RuleArchiveId] = ???
    override def deleteSavedRuleArchiveId(saveId: RuleArchiveId): IOResult[Unit] = ???
  }

  def reportingService(statusReports: Map[NodeId, NodeStatusReport]): ReportingService = {
    val ref = Ref.make(statusReports).runNow
    new ReportingServiceImpl2(new NodeStatusReportRepositoryImpl(new InMemoryNodeStatusReportStorage(ref), ref))
  }

  val complianceAPIService: ComplianceAPIService = {
    import complexExample.*
    buildComplianceService(
      complexCustomRules,
      complexCustomNodeGroups,
      complexStatusReports
    )
  }

  object complexExample {

    /*
    Nodes N1, N2, N3, N4, N5, N6 (and then modulo 6)

    Groups G1 (with N1, N2), G2 (N3), G3 (N1), G4 (N4, N5, N6), G5 (N3, N4), G6(N5, N6)

    Directives D1, D2, D3, D4, D5, D6

    Rules

    - R1, which applies D1 to G1
    - R2, which applies D2 to G1 but not G2
    - R3, which applies D3 to G2 and G3
    - R4, which applies D4 to G4 and G5
    - R5, which applies D5 to G6 but not G4 (so nothing)
    - R6, which applies D4,D6 to G6 (so we will have skipped on D4)
     */

    val nodesG1 = nodeRange.flatMap(i => Seq(nodeId(i), nodeId(i + 1))).toSet
//    println(s"nodes G1: " + nodesG1)
    val g1: NodeGroup = NodeGroup(
      nodeGroupId(1),
      name = "G1",
      description = "",
      properties = Nil,
      query = None,
      isDynamic = true,
      serverList = nodesG1,
      _isEnabled = true,
      security = None
    )

    val nodesG2 = nodeRange.flatMap(i => Seq(nodeId(i + 2))).toSet
//    println(s"nodes G2: " + nodesG2)
    val g2: NodeGroup = NodeGroup(
      nodeGroupId(2),
      name = "G2",
      description = "",
      properties = Nil,
      query = None,
      isDynamic = true,
      serverList = nodesG2,
      _isEnabled = true,
      security = None
    )

    val nodesG3 = nodeRange.flatMap(i => Seq(nodeId(i))).toSet
//    println(s"nodes G3: " + nodesG3)
    val g3: NodeGroup = NodeGroup(
      nodeGroupId(3),
      name = "G3",
      description = "",
      properties = Nil,
      query = None,
      isDynamic = true,
      serverList = nodesG3,
      _isEnabled = true,
      security = None
    )

    val nodesG4 = nodeRange.flatMap(i => Seq(nodeId(i + 3), nodeId(i + 4), nodeId(i + 5))).toSet
//    println(s"nodes G4: " + nodesG4)
    val g4: NodeGroup = NodeGroup(
      nodeGroupId(4),
      name = "G4",
      description = "",
      properties = Nil,
      query = None,
      isDynamic = true,
      serverList = nodesG4,
      _isEnabled = true,
      security = None
    )

    val nodesG5 = nodeRange.flatMap(i => Seq(nodeId(i + 2), nodeId(i + 3))).toSet
//    println(s"nodes G5: " + nodesG5)
    val g5: NodeGroup = NodeGroup(
      nodeGroupId(5),
      name = "G5",
      description = "",
      properties = Nil,
      query = None,
      isDynamic = true,
      serverList = nodesG5,
      _isEnabled = true,
      security = None
    )

    val nodesG6 = nodeRange.flatMap(i => Seq(nodeId(i + 4), nodeId(i + 5))).toSet
//    println(s"nodes G6: " + nodesG6)
    val g6: NodeGroup = NodeGroup(
      nodeGroupId(6),
      name = "G6",
      description = "",
      properties = Nil,
      query = None,
      isDynamic = true,
      serverList = nodesG6,
      _isEnabled = true,
      security = None
    )

    val d1 = directives.fileTemplateDirecive1
    val d2 = directives.fileTemplateVariables2
    val d3 = directives.rpmDirective
    val d4 = directives.fileTemplateDirecive1
    val d5 = directives.fileTemplateVariables2
    val d6 = directives.copyGitFileDirective

    val complexCustomRules: List[Rule] = ruleRange
      .flatMap(i => {
        List(
          Rule( // br1 %6
            ruleId(i),
            f"R1-${i}%03d",
            RuleCategoryId("rulecat1"),
            Set(GroupTarget(g1.id)),
            Set(d1.id),
            security = None
          ),
          Rule( // br2 %6
            ruleId(i + 1),
            f"R2-${i + 1}%03d",
            RuleCategoryId("rulecat1"),
            Set(
              TargetExclusion(TargetIntersection(Set(GroupTarget(g1.id))), TargetIntersection(Set(GroupTarget(g2.id))))
            ),  // include G1 but not G2
            Set(d2.id),
            security = None
          ),
          Rule( // br3 %6
            ruleId(i + 2),
            f"R3-${i + 2}%03d",
            RuleCategoryId("rulecat1"),
            Set(GroupTarget(g2.id), GroupTarget(g3.id)),
            Set(d3.id),
            security = None
          ),
          Rule( // br4 %6
            ruleId(i + 3),
            f"R4-${i + 3}%03d",
            RuleCategoryId("rulecat1"),
            Set(GroupTarget(g4.id), GroupTarget(g5.id)),
            Set(d4.id),
            security = None
          ),
          Rule( // br5 %6
            ruleId(i + 4),
            f"R5-${i + 4}%03d",
            RuleCategoryId("rulecat1"),
            Set(
              TargetExclusion(TargetIntersection(Set(GroupTarget(g6.id))), TargetIntersection(Set(GroupTarget(g4.id))))
            ),  // include G6 but not G4 (no node at all)
            Set(d5.id),
            security = None
          ),
          Rule( // br6 %6
            ruleId(i + 5),
            f"R6-${i + 5}%03d",
            RuleCategoryId("rulecat1"),
            Set(GroupTarget(g6.id)),
            Set(d4.id, d6.id),
            security = None
          )
        )
      })
      .toList

    val complexCustomNodeGroups: List[NodeGroup] = List(g1, g2, g3, g4, g5, g6)

    val complexStatusReports: Map[NodeId, NodeStatusReport] = nodeRange.flatMap { i =>
      Map(
        // R1, R2, R3 apply on N1
        nodeId(i)     -> simpleNodeStatusReport(
          nodeId(i),
          ruleRange.flatMap { j =>
            List(
              simpleRuleNodeStatusReport(nodeId(i), ruleId(j), d1.id, ReportType.EnforceSuccess),
              simpleRuleNodeStatusReport(nodeId(i), ruleId(j + 1), d2.id, ReportType.EnforceRepaired),
              simpleRuleNodeStatusReport(nodeId(i), ruleId(j + 2), d3.id, ReportType.EnforceError)
            )
          }.toSet
        ),
        // R1, R3, R4 apply on N2
        nodeId(i + 1) -> simpleNodeStatusReport(
          nodeId(i + 1),
          ruleRange.flatMap { j =>
            List(
              simpleRuleNodeStatusReport(nodeId(i + 1), ruleId(j), d1.id, ReportType.EnforceSuccess),
              simpleRuleNodeStatusReport(nodeId(i + 1), ruleId(j + 2), d3.id, ReportType.EnforceError),
              simpleRuleNodeStatusReport(nodeId(i + 1), ruleId(j + 3), d4.id, ReportType.EnforceSuccess)
            )
          }.toSet
        ),
        // R4 applies on N3
        nodeId(i + 2) -> simpleNodeStatusReport(
          nodeId(i + 2),
          ruleRange.flatMap { j =>
            List(
              simpleRuleNodeStatusReport(nodeId(i + 2), ruleId(j + 3), d4.id, ReportType.EnforceSuccess)
            )
          }.toSet
        ),
        // R4 applies D4 on N4
        // R6 applies D6 on N4
        nodeId(i + 3) -> simpleNodeStatusReport(
          nodeId(i + 3),
          ruleRange.flatMap { j =>
            List(
              simpleRuleNodeStatusReport(nodeId(i + 3), ruleId(j + 3), d4.id, ReportType.EnforceSuccess),
              simpleRuleNodeStatusReport(nodeId(i + 3), ruleId(j + 5), d6.id, ReportType.EnforceSuccess)
            )
          }.toSet,
          ruleRange.flatMap { j =>
            List(
              OverriddenPolicy(
                PolicyId(ruleId(j + 5), d4.id, TechniqueVersionHelper("1.0")),
                PolicyId(ruleId(j + 4), d4.id, TechniqueVersionHelper("1.0"))
              ),
              OverriddenPolicy(
                PolicyId(ruleId(j + 5), d4.id, TechniqueVersionHelper("1.0")),
                PolicyId(ruleId(j + 4), d4.id, TechniqueVersionHelper("1.0"))
              )
            )
          }.toList
        ),
        // R4 applies D4 on N5
        // R6 applies D6 on N5
        nodeId(i + 4) -> simpleNodeStatusReport(
          nodeId(i + 4),
          ruleRange.flatMap { j =>
            List(
              simpleRuleNodeStatusReport(nodeId(i + 4), ruleId(j + 3), d4.id, ReportType.EnforceSuccess),
              simpleRuleNodeStatusReport(nodeId(i + 4), ruleId(j + 5), d6.id, ReportType.EnforceSuccess)
            )
          }.toSet,
          ruleRange.flatMap { j =>
            List(
              OverriddenPolicy(
                PolicyId(ruleId(j + 5), d4.id, TechniqueVersionHelper("1.0")),
                PolicyId(ruleId(j + 4), d4.id, TechniqueVersionHelper("1.0"))
              ),
              OverriddenPolicy(
                PolicyId(ruleId(j + 5), d4.id, TechniqueVersionHelper("1.0")),
                PolicyId(ruleId(j + 4), d4.id, TechniqueVersionHelper("1.0"))
              )
            )
          }.toList
        ),
        // R5 targets nothing at all because no node is targeted
        // R6 is skipped, there are some reports with 'overrides'
        nodeId(i + 5) -> simpleNodeStatusReport(
          nodeId(i + 5),
          ruleRange.flatMap { j =>
            List(
              simpleRuleNodeStatusReport(nodeId(i + 5), ruleId(j + 3), d4.id, ReportType.NoAnswer),
              simpleRuleNodeStatusReport(nodeId(i + 5), ruleId(j + 4), d4.id, ReportType.NoAnswer)
            )
          }.toSet,
          ruleRange.flatMap { j =>
            List(
              OverriddenPolicy(
                PolicyId(ruleId(j + 5), d4.id, TechniqueVersionHelper("1.0")),
                PolicyId(ruleId(j + 4), d4.id, TechniqueVersionHelper("1.0"))
              ),
              OverriddenPolicy(
                PolicyId(ruleId(j + 5), d4.id, TechniqueVersionHelper("1.0")),
                PolicyId(ruleId(j + 4), d4.id, TechniqueVersionHelper("1.0"))
              )
            )
          }.toList
        )
      )
    }.toMap
  }

  private def buildComplianceService(
      customRules:      List[Rule],
      customNodeGroups: List[NodeGroup],
      statusesReports:  Map[NodeId, NodeStatusReport]
  ): ComplianceAPIService = {
    new ComplianceAPIService(
      rulesRepo(customRules),
      nodeFactRepo,
      nodeGroupsRepo(customNodeGroups),
      reportingService(statusesReports),
      mockDirectives.directiveRepo,
      GlobalComplianceMode(FullCompliance).succeed,
      GlobalPolicyMode(PolicyMode.Enforce, PolicyModeOverrides.Always).succeed
    )
  }

  private def simpleRuleNodeStatusReport(
      nodeId:      NodeId,
      ruleId:      RuleId,
      directiveId: DirectiveId,
      reportType:  ReportType
  ): RuleNodeStatusReport = {
    RuleNodeStatusReport(
      nodeId,
      ruleId,
      PolicyTypeName.rudderBase,
      None,
      None,
      Map(
        directiveId -> DirectiveStatusReport(
          directiveId,
          PolicyTypes.rudderBase,
          None,
          List(
            ValueStatusReport(
              s"${directiveId.serialize}-component-${ruleId.serialize}-${nodeId.value}",
              s"${directiveId.serialize}-component-${ruleId.serialize}-${nodeId.value}",
              List(
                ComponentValueStatusReport(
                  s"${directiveId.serialize}-component-value-${ruleId.serialize}-${nodeId.value}",
                  s"${directiveId.serialize}-component-value-${ruleId.serialize}-${nodeId.value}",
                  s"report-${ruleId.serialize}-${nodeId.value}",
                  List(MessageStatusReport(reportType, None))
                )
              )
            )
          )
        )
      ),
      DateTime.parse("2100-01-01T00:00:00.000Z")
    )
  }

  private def simpleNodeStatusReport(
      nodeId:          NodeId,
      ruleNodeReports: Set[RuleNodeStatusReport],
      overrides:       List[OverriddenPolicy] = List.empty
  ): NodeStatusReport = {
    NodeStatusReportInternal
      .buildWith(
        nodeId,
        ComputeCompliance(
          DateTime.parse("2023-01-01T00:00:00.000Z"),
          NodeExpectedReports(
            nodeId,
            NodeConfigId(s"${nodeId.value}-config"),
            DateTime.parse("2023-01-01T00:00:00.000Z"),
            None,
            NodeModeConfig(
              GlobalComplianceMode(FullCompliance),
              AgentRunInterval(None, 1, 0, 0, 0),
              None,
              GlobalPolicyMode(PolicyMode.Enforce, PolicyModeOverrides.Unoverridable),
              None
            ),
            List.empty,
            List.empty
          ),
          DateTime.parse("2024-01-01T00:00:00.000Z")
        ),
        RunComplianceInfo.OK,
        overrides,
        ruleNodeReports
      )
      .toNodeStatusReport()
  }
}
