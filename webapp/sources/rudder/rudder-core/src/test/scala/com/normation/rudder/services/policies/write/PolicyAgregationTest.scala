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
import com.normation.inventory.domain.AgentType
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.PolicyModeOverrides
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.services.policies.BoundPolicyDraft
import com.normation.rudder.services.policies.BundleOrder
import com.normation.rudder.services.policies.ComponentId
import com.normation.rudder.services.policies.MergePolicyService
import com.normation.rudder.services.policies.NodeConfigData
import com.normation.rudder.services.policies.PolicyId
import com.softwaremill.quicklens.*
import java.time.Instant
import org.junit.runner.RunWith
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class PolicyAgregationTest extends Specification {
  implicit def str2pId(id: String): TechniqueId = TechniqueId(TechniqueName(id), TechniqueVersionHelper("1.0"))
  implicit def str2PolicyId(id: String): PolicyId =
    PolicyId(RuleId(RuleUid("r_" + id)), DirectiveId(DirectiveUid("d_" + id)), TechniqueVersionHelper("1.0"))

  // we are testing error cases, so we don't want to output error log for them
  org.slf4j.LoggerFactory
    .getLogger("policy-generation")
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.OFF)

  def compareValues(expected: Seq[(String, String)], actual1: Seq[String], actual2: Seq[String]): MatchResult[Any] = {
    val actual = actual1.zip(actual2)

    (actual1.size must beEqualTo(expected.size)) and
    (actual1.size must beEqualTo(expected.size)) and
    (actual must containTheSameElementsAs(expected))
  }

  val policyMode: GlobalPolicyMode = GlobalPolicyMode(PolicyMode.Enforce, PolicyModeOverrides.Always)

  val trackerVariableSpec: TrackerVariableSpec = TrackerVariableSpec(Some("card"), None)
  val trackerVariable:     TrackerVariable     = TrackerVariable(trackerVariableSpec, Seq())

  val cfe: AgentConfig = AgentConfig(AgentType.CfeCommunity, Nil, Nil, Nil, Nil)

  //  a simple mutli-instance technique
  val technique1_1: Technique = Technique(
    TechniqueId(TechniqueName("tech_1"), TechniqueVersionHelper("1.0")),
    "tech_1",
    "DESCRIPTION",
    cfe :: Nil,
    trackerVariableSpec,
    SectionSpec(name = "root", children = Seq()),
    None,
    isMultiInstance = true
  )

  //  a simple mutli-instance technique
  val technique2_1: Technique = technique1_1.copy(
    id = TechniqueId(TechniqueName("tech_2"), TechniqueVersionHelper("1.0")),
    name = "tech_2"
  )

  // the same in an other version
  val technique2_2: Technique = technique1_1.copy(
    id = TechniqueId(TechniqueName("tech_2"), TechniqueVersionHelper("2.0")),
    name = "tech_2"
  )

  // the same in an other version, multi-policy
  val technique2_3: Technique = technique1_1.copy(
    id = TechniqueId(TechniqueName("tech_2"), TechniqueVersionHelper("3.0")),
    name = "tech_2",
    generationMode = TechniqueGenerationMode.MultipleDirectives
  )

  // that one is not multi-instance, not multi-policy
  val technique3_1: Technique = technique1_1.copy(
    id = TechniqueId(TechniqueName("tech_3"), TechniqueVersionHelper("1.0")),
    name = "tech_3",
    isMultiInstance = false
  )

  // an other version still not multi-policy
  val technique3_2: Technique = technique3_1.copy(id = TechniqueId(TechniqueName("tech_3"), TechniqueVersionHelper("2.0")))

  def newPolicy(technique: Technique, id: String, varName: String, values: Seq[String]): BoundPolicyDraft = {
    val v = InputVariable(
      InputVariableSpec(
        "card",
        "description for " + varName,
        None,
        multivalued = true,
        id = None,
        constraint = Constraint(mayBeEmpty = true)
      ),
      values
    )
    BoundPolicyDraft(
      id,
      ruleName = "rule name",
      directiveName = "directive name",
      technique.modify(_.rootSection.children).setTo(List(v.spec)),
      acceptationDate = Instant.now,
      expandedVars = Map(ComponentId(v.spec.name, List("root"), None) -> v),
      originalVars = Map(ComponentId(v.spec.name, List("root"), None) -> v),
      trackerVariable,
      priority = 5,
      isSystem = false,
      policyMode = None,
      ruleOrder = BundleOrder(id.ruleId.serialize),
      directiveOrder = BundleOrder(id.directiveId.serialize),
      overrides = Set()
    )
  }

  sequential

  // Create a Directive, with value , and add it to a server, and aggregate values
  "Multi-instance, non-multi-policy technique" should {

    "correctly merge technique by name" in {
      val drafts = Seq(
        newPolicy(technique1_1, "id1", "card", "value1" :: Nil),
        newPolicy(technique1_1, "id2", "card", "value2" :: Nil),
        newPolicy(technique2_1, "id3", "card", "value3" :: Nil)
      )

      val policies =
        MergePolicyService.buildPolicy(NodeConfigData.factRoot, policyMode, drafts).openOrThrowException("Failing test")

      policies.map(p =>
        (p.technique.id, p.expandedVars.toList.map { case (n, v) => (n, v.values) }, p.trackerVariable.values)
      ) must containTheSameElementsAs(
        Seq(
          (technique1_1.id, Seq(("card", Seq("value1", "value2"))), Seq("r_id1@@d_id1@@0", "r_id2@@d_id2@@0")),
          (technique2_1.id, Seq(("card", Seq("value3"))), Seq("r_id3@@d_id3@@0"))
        )
      )
    }

    "correctly merge directive with bound tracking key" in {
      val drafts = Seq(
        newPolicy(technique1_1, "id1", "card", "value1" :: Nil),
        newPolicy(technique1_1, "id2", "card", "value2" :: "value3" :: Nil)
      )

      val policies =
        MergePolicyService.buildPolicy(NodeConfigData.factRoot, policyMode, drafts).openOrThrowException("Failing test")

      policies.map(p =>
        (p.technique.id, p.expandedVars.toList.map { case (n, v) => (n, v.values) }, p.trackerVariable.values)
      ) must containTheSameElementsAs(
        Seq(
          (
            technique1_1.id,
            Seq(("card", Seq("value1", "value2", "value3"))),
            Seq("r_id1@@d_id1@@0", "r_id2@@d_id2@@0", "r_id2@@d_id2@@0")
          )
        )
      )
    }

    "correctly handle null and empty values" in {
      val drafts = Seq(
        newPolicy(technique1_1, "id1", "card", null :: Nil),
        newPolicy(technique1_1, "id2", "card", null :: "value2" :: Nil)
      )

      val policies =
        MergePolicyService.buildPolicy(NodeConfigData.factRoot, policyMode, drafts).openOrThrowException("Failing test")
      policies.map(p =>
        (p.technique.id, p.expandedVars.toList.map { case (n, v) => (n, v.values) }, p.trackerVariable.values)
      ) must containTheSameElementsAs(
        Seq(
          (
            technique1_1.id,
            Seq(("card", Seq(null, null, "value2"))),
            Seq("r_id1@@d_id1@@0", "r_id2@@d_id2@@0", "r_id2@@d_id2@@0")
          )
        )
      )
    }

    "correctly choose the most prioritary technique" in {
      val drafts = Seq(
        newPolicy(technique3_1, "id1", "card", "value1" :: Nil),
        newPolicy(technique3_1, "id2", "card", "value2" :: Nil).copy(priority = 0) // higher priority
      )

      val policies =
        MergePolicyService.buildPolicy(NodeConfigData.factRoot, policyMode, drafts).openOrThrowException("Failing test")
      policies.map(p =>
        (p.technique.id, p.expandedVars.toList.map { case (n, v) => (n, v.values) }, p.trackerVariable.values)
      ) must containTheSameElementsAs(
        Seq((technique3_1.id, Seq(("card", Seq("value2"))), Seq("r_id2@@d_id2@@0")))
      )
    }

    "choose the one with the first name with different technique version on non multi-policy" in {
      val drafts = Seq(
        newPolicy(technique3_1, "xx", "card", "value1" :: Nil),
        newPolicy(technique3_2, "aa", "card", "value2" :: Nil) // first one
      )

      val policies =
        MergePolicyService.buildPolicy(NodeConfigData.factRoot, policyMode, drafts).openOrThrowException("Failing test")
      policies.map(p =>
        (p.technique.id, p.expandedVars.toList.map { case (n, v) => (n, v.values) }, p.trackerVariable.values)
      ) must containTheSameElementsAs(
        Seq((technique3_2.id, Seq(("card", Seq("value2"))), Seq("r_aa@@d_aa@@0")))
      )
    }

    "create one policy for each multi-policy technique and choose one other for other merged" in {
      val drafts = Seq(
        newPolicy(technique2_1, "xx", "card", "value1" :: Nil),
        newPolicy(technique2_1, "aa", "card", "value2" :: Nil), // first one in the merge

        newPolicy(technique2_3, "00", "card", "value3" :: "value4" :: Nil),
        newPolicy(technique2_3, "11", "card", "value5" :: Nil)
      )

      val policies =
        MergePolicyService.buildPolicy(NodeConfigData.factRoot, policyMode, drafts).openOrThrowException("Failing test")
      policies.map(p =>
        (p.technique.id, p.expandedVars.toList.map { case (n, v) => (n, v.values) }, p.trackerVariable.values)
      ) must containTheSameElementsAs(
        Seq(
          (technique2_1.id, Seq(("card", Seq("value2", "value1"))), Seq("r_aa@@d_aa@@0", "r_xx@@d_xx@@0")),
          (technique2_3.id, Seq(("card", Seq("value3", "value4"))), Seq("r_00@@d_00@@0", "r_00@@d_00@@0")),
          (technique2_3.id, Seq(("card", Seq("value5"))), Seq("r_11@@d_11@@0"))
        )
      )
    }
  }
}
