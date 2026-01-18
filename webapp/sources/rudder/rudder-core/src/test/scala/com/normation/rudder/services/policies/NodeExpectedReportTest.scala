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
package com.normation.rudder.services.policies

import cats.data.NonEmptyList
import com.normation.cfclerk.domain.AgentConfig
import com.normation.cfclerk.domain.BundleName
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.domain.SectionVariableSpec
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersionHelper
import com.normation.cfclerk.domain.TrackerVariableSpec
import com.normation.cfclerk.domain.Variable
import com.normation.cfclerk.services.impl.TechniqueRepositoryImpl
import com.normation.inventory.domain.AgentType
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.reports.ExpectedReportsSerialisation.Version7_1.JsonRuleExpectedReports7_1
import com.normation.rudder.domain.reports.RuleExpectedReports
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.runner.*
import org.specs2.matcher.MatchResult
import org.specs2.mutable.*
import org.specs2.runner.JUnitRunner
import zio.json.*

/**
 * Test that a NodeConfiguration is correctly transformed to
 * NodeExpectedReport and that the serialisation look like what we want.
 */
@RunWith(classOf[JUnitRunner])
class NodeExpectedReportTest extends Specification {

  val yesterday = LocalDateTime.now.minusDays(1).toInstant(ZoneOffset.UTC)
  /*
   * The different case to test are:
   *
   * - several directive from same technique, different rules => splited in
   *   correct rules / directives
   * - several directive in the same rule => correctly groupes
   * - several components / values => correctly grouped
   */

  // a technique with a var non multi-valued and a mutivalued section with 2 components
  // a non multivalued var
  def tvar(x: String):  SectionVariableSpec =
    SectionVariableSpec(s"var_${x}", "", "INPUT", None, valueslabels = Nil, providedValues = Nil, id = None)
  // a multivalued var
  def tmvar(x: String): SectionVariableSpec =
    SectionVariableSpec(s"m_var_${x}", "", "INPUT", None, multivalued = true, valueslabels = Nil, providedValues = Nil, id = None)

  // return the couple of (var name, var with value)
  def v(x: String, values: String*):  (ComponentId, SectionVariableSpec#V) = {
    val v = tvar(x).toVariable(values)
    (ComponentId(v.spec.name, v.spec.name :: "root" :: Nil, None), v)
  }
  // same with multivalued
  def mv(x: String, values: String*): (ComponentId, SectionVariableSpec#V) = {
    val v = tmvar(x).toVariable(values)
    (ComponentId(v.spec.name, v.spec.name :: "root" :: Nil, None), v)
  }

  def parse(s: String): List[RuleExpectedReports] = s.fromJson[List[JsonRuleExpectedReports7_1]] match {
    case Left(err) => throw new IllegalArgumentException(err)
    case Right(v)  => v.map(_.transform)
  }

  def technique(x: String): PolicyTechnique = {
    PolicyTechnique(
      TechniqueId(TechniqueName("t" + x), TechniqueVersionHelper("1.0")),
      AgentConfig(AgentType.CfeCommunity, Nil, Nil, List(BundleName("t" + x)), Nil),
      TrackerVariableSpec(Some(s"m_var_${x}_1"), None),
      SectionSpec(
        name = "root",
        isMultivalued = false,
        isComponent = false,
        componentKey = None,
        children = List(
          SectionSpec(
            name = s"var_${x}_0",
            isMultivalued = false,
            isComponent = true,
            componentKey = Some(s"var_${x}_0"),
            children = List(
              tvar(x + "_0")
            )
          ),
          SectionSpec(
            name = s"m_var_${x}_1",
            isMultivalued = true,
            isComponent = true,
            componentKey = Some(s"m_var_${x}_1"),
            children = List(
              tmvar(x + "_1"),
              SectionSpec(
                name = s"m_var_${x}_2",
                isMultivalued = false,
                isComponent = true,
                componentKey = Some(s"m_var_${x}_2"),
                children = List(
                  tmvar(x + "_2")
                )
              )
            )
          )
        )
      ),
      Set()
    )
  }
  val tNoComponent:         PolicyTechnique = {
    val x = "noComponent"
    PolicyTechnique(
      TechniqueId(TechniqueName("t" + x), TechniqueVersionHelper("1.0")),
      AgentConfig(AgentType.CfeCommunity, Nil, Nil, List(BundleName("t" + x)), Nil),
      TrackerVariableSpec(Some(s"m_var_${x}_1"), None),
      SectionSpec(
        name = "root",
        isMultivalued = false,
        isComponent = false,
        componentKey = None,
        children = List(
          SectionSpec(
            name = s"var_${x}_0",
            isMultivalued = false,
            isComponent = false,
            componentKey = Some(s"var_${x}_0"),
            children = List(
              tvar(x + "_0")
            )
          ),
          SectionSpec(
            name = s"m_var_${x}_1",
            isMultivalued = true,
            isComponent = false,
            componentKey = Some(s"m_var_${x}_1"),
            children = List(
              tmvar(x + "_1"),
              SectionSpec(
                name = s"m_var_${x}_2",
                isMultivalued = false,
                isComponent = false,
                componentKey = Some(s"m_var_${x}_2"),
                children = List(
                  tmvar(x + "_2")
                )
              )
            )
          )
        )
      ),
      Set()
    )
  }

  val t1: PolicyTechnique = technique("1")
  val t2: PolicyTechnique = technique("2")

  // used in NodeExpectedReportTest
  val testNodeConfiguration = new TestNodeConfiguration("")
  implicit class UnsafeGet(repo: TechniqueRepositoryImpl) {
    def unsafeGet(id: TechniqueId): Technique =
      repo.get(id).getOrElse(throw new RuntimeException(s"Bad init for test: technique '${id.serialize}' not found"))
  }
  val ncfTechniqueWithBlocks: Technique = testNodeConfiguration.techniqueRepository.unsafeGet(
    TechniqueId(TechniqueName("technique_with_blocks"), TechniqueVersionHelper("1.0"))
  )

  val policyTechniqueWithBlock: PolicyTechnique = PolicyTechnique(
    ncfTechniqueWithBlocks.id,
    AgentConfig(AgentType.CfeCommunity, Nil, Nil, List(BundleName("technique_with_blocks")), Nil),
    ncfTechniqueWithBlocks.trackerVariableSpec,
    ncfTechniqueWithBlocks.rootSection,
    ncfTechniqueWithBlocks.systemVariableSpecs,
    ncfTechniqueWithBlocks.isMultiInstance,
    ncfTechniqueWithBlocks.policyTypes,
    ncfTechniqueWithBlocks.generationMode,
    ncfTechniqueWithBlocks.useMethodReporting
  )

  val r1: RuleId      = RuleId(RuleUid("rule_1"))
  val r2: RuleId      = RuleId(RuleUid("rule_2"))
  val r3: RuleId      = RuleId(RuleUid("rule_3"))
  val d1: DirectiveId = DirectiveId(DirectiveUid("directive_1"))
  val d2: DirectiveId = DirectiveId(DirectiveUid("directive_2"))
  val d3: DirectiveId = DirectiveId(DirectiveUid("directive_3"))
  val d4: DirectiveId = DirectiveId(DirectiveUid("directive_4"))

  val p1_id: PolicyId = PolicyId(r1, d1, TechniqueVersionHelper("1.0"))
  val p1:    Policy   = Policy(
    p1_id,
    "rule name",
    "directive name",
    technique = t1,
    yesterday,
    policyVars = NonEmptyList.of(
      PolicyVars(
        PolicyId(r1, d1, TechniqueVersionHelper("1.0")),
        Some(PolicyMode.Audit),
        Map(v("1_0", "1_0_0"), mv("1_1", "1_1_0", "1_1_1"), mv("1_2", "1_2_0", "1_2_1")),
        Map(v("1_0", "1_0_0"), mv("1_1", "1_1_0", "1_1_1"), mv("1_2", "1_2_0", "1_2_1")),
        t1.trackerVariableSpec.toVariable(p1_id.getReportId :: p1_id.getReportId :: Nil)
      ),
      PolicyVars(
        PolicyId(r2, d2, TechniqueVersionHelper("1.0")),
        None,
        Map(v("1_0", "2_0_0"), mv("1_1", "2_1_0"), mv("1_2", "2_2_0")),
        Map(v("1_0", "2_0_0"), mv("1_1", "2_1_0"), mv("1_2", "2_2_0")),
        t1.trackerVariableSpec.toVariable(p1_id.getReportId :: p1_id.getReportId :: Nil)
      )
    ),
    priority = 0,
    policyMode = None,
    ruleOrder = BundleOrder("1"),
    directiveOrder = BundleOrder("1"),
    overrides = Set()
  )

  val p2_id: PolicyId = PolicyId(r2, d3, TechniqueVersionHelper("1.0"))
  val p2:    Policy   = Policy(
    p2_id,
    "rule name",
    "directive name",
    technique = t2,
    yesterday,
    policyVars = NonEmptyList.of(
      PolicyVars(
        PolicyId(r2, d3, TechniqueVersionHelper("1.0")),
        None,
        Map(v("2_0", "3_0_0"), mv("2_1", "3_1_0"), mv("2_2", "3_2_0")),
        Map(v("2_0", "3_0_0"), mv("2_1", "3_1_0"), mv("2_2", "3_2_0")),
        t1.trackerVariableSpec.toVariable(p2_id.getReportId :: p2_id.getReportId :: Nil)
      )
    ),
    priority = 0,
    policyMode = None,
    ruleOrder = BundleOrder("1"),
    directiveOrder = BundleOrder("1"),
    overrides = Set()
  )

  val p3_id: PolicyId = PolicyId(r2, d4, TechniqueVersionHelper("1.0"))
  val p3:    Policy   = Policy(
    p3_id,
    "rule name",
    "directive name",
    technique = tNoComponent,
    yesterday,
    policyVars = NonEmptyList.of(
      PolicyVars(
        PolicyId(r2, d4, TechniqueVersionHelper("1.0")),
        None,
        Map(v("2_0", "3_0_0"), mv("2_1", "3_1_0"), mv("2_2", "3_2_0")),
        Map(v("2_0", "3_0_0"), mv("2_1", "3_1_0"), mv("2_2", "3_2_0")),
        t1.trackerVariableSpec.toVariable(p3_id.getReportId :: p3_id.getReportId :: Nil)
      )
    ),
    priority = 0,
    policyMode = None,
    ruleOrder = BundleOrder("1"),
    directiveOrder = BundleOrder("1"),
    overrides = Set()
  )

  // compare and json of expected reports with the one produced by RuleExpectedReports.
  // things are sorted and Jnothing values are cleaned up to keep things understandable
  def compareExpectedReportsJson(
      expected: List[RuleExpectedReports],
      policies: List[Policy]
  ): MatchResult[List[RuleExpectedReports]] = {
    val rules = RuleExpectedReportBuilder(policies).sortBy(_.ruleId.serialize)

    expected === rules
  }

  // Ok, now I can test
  "The expected reports from a merged policy with two directives" should {
    "return a Full(RuleVal)" in {

      val expected = parse("""
        [
           {
             "rid": "rule_1"
           , "ds": [
               {
                 "did": "directive_1"
               , "pm" : "audit"
               , "s"   : false
               , "cs" : [
                   {
                     "vid": "var_1_0"
                   , "vs" : [
                       ["1_0_0"]
                     ]
                   }
                 , {
                     "vid": "m_var_1_1"
                   , "vs"       : [
                       ["1_1_0"]
                     , ["1_1_1"]
                     ]
                   }
                 , {
                     "vid": "m_var_1_2"
                   , "vs"       : [
                       ["1_2_0"]
                     , ["1_2_1"]
                     ]
                   }
                 ]
               }
             ]
           }
         , {
             "rid": "rule_2"
           , "ds": [
               {
                 "did": "directive_2"
               , "s"   : false
               , "cs" : [
                   {
                     "vid": "var_1_0"
                   , "vs"       : [
                       ["2_0_0"]
                     ]
                   }
                 , {
                     "vid": "m_var_1_1"
                   , "vs"       : [
                       ["2_1_0"]
                     ]
                   }
                 , {
                     "vid": "m_var_1_2"
                   , "vs"       : [
                       ["2_2_0"]
                     ]
                   }
                 ]
               }
             , {
                 "did": "directive_3"
               , "s"   : false
               , "cs" : [
                   {
                     "vid": "var_2_0"
                   , "vs"       : [
                       ["3_0_0"]
                     ]
                   }
                 , {
                     "vid":"m_var_2_1"
                   , "vs"       : [
                       ["3_1_0"]
                     ]
                   }
                 , {
                     "vid": "m_var_2_2"
                   , "vs"       : [
                       ["3_2_0"]
                     ]
                   }
                 ]
               }
             , {
                 "did": "directive_4"
               , "s"   : false
               , "cs" : [
                   {
                     "vid": "tnoComponent"
                   , "vs" : [["None"]]
                   }
                 ]
               }
             ]
           }
         ]""")

      compareExpectedReportsJson(expected, p1 :: p2 :: p3 :: Nil)
    }
  }

  "The rule expected reports from a technique with blocks " should {
    def componentIdCreator(componentKey: String, parentPath: List[String], value: String): (ComponentId, Variable) = {
      // expectedReportKey is the prefix of the variable name, so necessary
      val variable    = SectionVariableSpec(
        "expectedReportKey " + componentKey,
        "",
        "REPORTKEYS",
        None,
        valueslabels = Nil,
        providedValues = Seq(value),
        id = None
      )
      val componentId = ComponentId("expectedReportKey " + componentKey, parentPath, variable.id)
      (componentId, variable.toVariable(Seq(value)))
    }

    val policyVarsMaps: Map[ComponentId, Variable] = Map(
      componentIdCreator("Command execution", List("Command execution", "SECTIONS"), "/bin/true #root1"),
      componentIdCreator("File absent", List("File absent", "SECTIONS"), "/tmp/root2"),
      componentIdCreator("File absent", List("File absent", "First block", "SECTIONS"), "/tmp/block1"),
      componentIdCreator("File absent", List("File absent", "inner block", "First block", "SECTIONS"), "/tmp/block1_1"),
      componentIdCreator("Command execution", List("Command execution", "inner block", "First block", "SECTIONS"), "/bin/true")
    )

    val p_with_block_id = PolicyId(r3, d4, TechniqueVersionHelper("1.0"))
    val p_with_block    = Policy(
      p_with_block_id,
      "rule name with block",
      "directive name with block",
      technique = policyTechniqueWithBlock,
      yesterday,
      policyVars = NonEmptyList.of(
        PolicyVars(
          PolicyId(r3, d4, TechniqueVersionHelper("1.0")),
          Some(PolicyMode.Enforce),
          policyVarsMaps,
          policyVarsMaps,
          policyTechniqueWithBlock.trackerVariableSpec.toVariable(p_with_block_id.getReportId :: Nil)
        )
      ),
      priority = 0,
      policyMode = None,
      ruleOrder = BundleOrder("1"),
      directiveOrder = BundleOrder("1"),
      overrides = Set()
    )

    "return the expected expected reports" in {

      val expectedJson =
        """
        [
           {
             "rid": "rule_3"
           , "ds": [
               {
                 "did": "directive_4"
               , "pm" : "enforce"
               , "s"   : false
               , "cs":[
                   {
                     "bid":"First block"
                   , "rl":"weighted"
                   , "scs": [
                       {
                         "bid":"inner block"
                       , "rl":"weighted"
                       , "scs": [
                         {
                           "vid":"Command execution"
                         , "vs": [
                             ["/bin/true"]
                           ]
                         }
                       , {
                           "vid":"File absent"
                         , "vs":[
                             ["/tmp/block1_1"]
                           ]
                         }
                       ]
                     }
                    , {
                       "vid":"File absent"
                     , "vs":[
                         ["/tmp/block1"]
                       ]
                     }
                     ]
                   }
                 , {
                     "vid":"Command execution"
                   , "vs": [
                       ["/bin/true #root1"]
                     ]
                   }
                 , {
                     "vid":"File absent"
                   , "vs": [
                       ["/tmp/root2"]
                     ]
                   }
                 ]
               }
             ]
           }
         ]
        """

      compareExpectedReportsJson(parse(expectedJson), p_with_block :: Nil)
    }
  }
}
