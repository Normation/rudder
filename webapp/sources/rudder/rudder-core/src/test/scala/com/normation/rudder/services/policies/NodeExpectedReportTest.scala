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

import org.junit.runner._
import org.specs2.runner.JUnitRunner
import org.specs2.mutable._
import com.normation.cfclerk.domain.{AgentConfig, BundleName, SectionSpec, SectionVariableSpec, TechniqueId, TechniqueName, TechniqueVersionHelper, TrackerVariableSpec, Variable}
import com.normation.inventory.domain.AgentType
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.DirectiveUid
import org.joda.time.DateTime
import cats.data.NonEmptyList
import com.normation.cfclerk.services.impl.TechniqueRepositoryImpl
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.reports.ExpectedReportsSerialisation
import net.liftweb.json._
import net.liftweb.json.JsonAST.JArray

/**
 * Test that a NodeConfiguration is correctly transformed to
 * NodeExpectedReport and that the serialisation look like what we want.
 */
@RunWith(classOf[JUnitRunner])
class NodeExpectedReportTest extends Specification {


  /*
   * The different case to test are:
   *
   * - several directive from same technique, different rules => splited in
   *   correct rules / directives
   * - several directive in the same rule => correctly groupes
   * - several components / values => correctly grouped
   */

  //a technique with a var non multi-valued and a mutivalued section with 2 components
  // a non multivalued var
  def tvar(x: String)  = SectionVariableSpec(s"var_${x}", "", "INPUT", valueslabels = Nil, providedValues = Nil, id = None)
  // a multivalued var
  def tmvar(x: String) = SectionVariableSpec(s"m_var_${x}", "", "INPUT", multivalued = true, valueslabels = Nil, providedValues = Nil, id = None)

  // return the couple of (var name, var with value)
  def v(x: String, values: String*) = {
    val v = tvar(x).toVariable(values)
    (ComponentId(v.spec.name,  v.spec.name :: "root" :: Nil), v)
  }
  // same with multivalued
  def mv(x: String, values: String*) = {
    val v = tmvar(x).toVariable(values)
    (ComponentId(v.spec.name, v.spec.name :: "root":: Nil), v)
  }

  def technique(x: String) = {
    PolicyTechnique(
        TechniqueId(TechniqueName("t"+x), TechniqueVersionHelper("1.0"))
      , AgentConfig(AgentType.CfeCommunity, Nil, Nil, List(BundleName("t"+x)), Nil)
      , TrackerVariableSpec(Some(s"m_var_${x}_1"), None)
      , SectionSpec(name = "root", isMultivalued = false, isComponent = false, componentKey = None, children = List(
          SectionSpec(name = s"var_${x}_0", isMultivalued = false, isComponent = true, componentKey = Some(s"var_${x}_0"), children = List(
            tvar(x+"_0")
          ))
        , SectionSpec(name = s"m_var_${x}_1", isMultivalued = true, isComponent = true, componentKey = Some(s"m_var_${x}_1"), children = List(
            tmvar(x+"_1")
          , SectionSpec(name = s"m_var_${x}_2", isMultivalued = false, isComponent = true, componentKey = Some(s"m_var_${x}_2"), children = List(
              tmvar(x+"_2")
            ))
          ))
        ))
      , Set()
    )
  }
  val t1 = technique("1")
  val t2 = technique("2")


  // used in NodeExpectedReportTest
  val testNodeConfiguration = new TestNodeConfiguration("")
  implicit class UnsafeGet(repo: TechniqueRepositoryImpl) {
    def unsafeGet(id: TechniqueId) = repo.get(id).getOrElse(throw new RuntimeException(s"Bad init for test: technique '${id.serialize}' not found"))
  }
  val ncfTechniqueWithBlocks = testNodeConfiguration.techniqueRepository.unsafeGet(TechniqueId(TechniqueName("technique_with_blocks"), TechniqueVersionHelper("1.0")))

  val policyTechniqueWithBlock = PolicyTechnique(
      ncfTechniqueWithBlocks.id
    , AgentConfig(AgentType.CfeCommunity, Nil, Nil, List(BundleName("technique_with_blocks")), Nil)
    , ncfTechniqueWithBlocks.trackerVariableSpec
    , ncfTechniqueWithBlocks.rootSection
    , ncfTechniqueWithBlocks.systemVariableSpecs
    , ncfTechniqueWithBlocks.isMultiInstance
    , ncfTechniqueWithBlocks.isSystem
    , ncfTechniqueWithBlocks.generationMode
    , ncfTechniqueWithBlocks.useMethodReporting
  )


  val r1 = RuleId(RuleUid("rule_1"))
  val r2 = RuleId(RuleUid("rule_2"))
  val r3 = RuleId(RuleUid("rule_3"))
  val d1 = DirectiveId(DirectiveUid("directive_1"))
  val d2 = DirectiveId(DirectiveUid("directive_2"))
  val d3 = DirectiveId(DirectiveUid("directive_3"))
  val d4 = DirectiveId(DirectiveUid("directive_4"))

  val p1_id = PolicyId(r1, d1, TechniqueVersionHelper("1.0"))
  val p1 = Policy(
      p1_id
    , "rule name"
    , "directive name"
    , technique      = t1
    , DateTime.now.minusDays(1)
    , policyVars     = NonEmptyList.of(
          PolicyVars(
              PolicyId(r1, d1, TechniqueVersionHelper("1.0"))
            , Some(PolicyMode.Audit)
            , Map(v("1_0", "1_0_0"), mv("1_1", "1_1_0", "1_1_1"), mv("1_2", "1_2_0", "1_2_1"))
            , Map(v("1_0", "1_0_0"), mv("1_1", "1_1_0", "1_1_1"), mv("1_2", "1_2_0", "1_2_1"))
            , t1.trackerVariableSpec.toVariable(p1_id.getReportId :: p1_id.getReportId :: Nil)
          )
        , PolicyVars(
              PolicyId(r2, d2, TechniqueVersionHelper("1.0"))
            , None
            , Map(v("1_0", "2_0_0"), mv("1_1", "2_1_0"), mv("1_2", "2_2_0"))
            , Map(v("1_0", "2_0_0"), mv("1_1", "2_1_0"), mv("1_2", "2_2_0"))
            , t1.trackerVariableSpec.toVariable(p1_id.getReportId :: p1_id.getReportId :: Nil)
          )
      )
    , priority       = 0
    , policyMode     = None
    , ruleOrder      = BundleOrder("1")
    , directiveOrder = BundleOrder("1")
    , overrides      = Set()
  )

  val p2_id = PolicyId(r2, d3, TechniqueVersionHelper("1.0"))
  val p2 = Policy(
      p2_id
    , "rule name"
    , "directive name"
    , technique      = t2
    , DateTime.now.minusDays(1)
    , policyVars     = NonEmptyList.of(
          PolicyVars(
              PolicyId(r2, d3, TechniqueVersionHelper("1.0"))
            , None
            , Map(v("2_0", "3_0_0"), mv("2_1", "3_1_0"), mv("2_2", "3_2_0"))
            , Map(v("2_0", "3_0_0"), mv("2_1", "3_1_0"), mv("2_2", "3_2_0"))
            , t1.trackerVariableSpec.toVariable(p2_id.getReportId :: p2_id.getReportId :: Nil)
          )
      )
    , priority       = 0
    , policyMode     = None
    , ruleOrder      = BundleOrder("1")
    , directiveOrder = BundleOrder("1")
    , overrides      = Set()
  )

  def sortJs(js: JValue): JValue = js match {
    case JObject(fields) => JObject(fields.sortBy{ case x =>
      x.name match {
        case "componentName" => x.value match { // special case for component name, we want also to sort by value
          case  JString(value) => value
          case _ => x.value.toString
        }
        case _ => x.name
      }} .map { case JField(k, v) => JField(k, sortJs(v)) })
    case JArray(array) => JArray(array.sortBy{ case x => x.values.toString }.map(e => sortJs(e))) // this toString is not optimal but it's consistent :)
    case _ => js
  }

  // compare and json of expected reports with the one produced by RuleExpectedReports.
  // things are sorted and Jnothing values are cleaned up to keep things understandable
  def compareExpectedReportsJson(expected: JValue, policies: List[Policy]) = {
    val json = ExpectedReportsSerialisation.jsonRuleExpectedReports(RuleExpectedReportBuilder(policies).sortBy( _.ruleId.serialize))

    // we must compare the sorted diff
    val Diff(changed, added, deleted) = sortJs(expected) diff sortJs(json)
    // we don't want to deals with JNothing trailing leaf
    def clean(j: JValue): JValue = {
      j match {
        case JObject(fs) =>
          fs.map { case JField(n, v) => JField(n, clean(v)) }.filter( _.value != JNothing) match {
            case Nil => JNothing
            case l   => JObject(l)
          }
        case JArray(ll) => ll.map(clean).filter( _ != JNothing) match {
          case Nil => JNothing
          case l   => JArray(l)
        }
        case x => x
      }
    }

    def res(json: JValue) = json match {
      case JNothing => ""
      case x        => prettyRender(x)
    }

    // all that for that...
    (res(clean(changed)) === "") and (res(clean(added)) === "") and (res(clean(deleted)) === "")
  }

  // Ok, now I can test
  "The expected reports from a merged policy with two directives" should {
    "return a Full(RuleVal)" in {

      val expected = parse("""
        [
           {
             "ruleId": "rule_1"
           , "directives": [
               {
                 "directiveId": "directive_1"
               , "policyMode" : "audit"
               , "isSystem"   : false
               , "components" : [
                   {
                     "componentName": "var_1_0"
                   , "values"       : [
                       {
                         "unexpanded":"1_0_0"
                       , "value":"1_0_0"
                       }
                     ]
                   }
                 , {
                     "componentName": "m_var_1_1"
                   , "values"       : [
                       {
                         "unexpanded":"1_1_0"
                       , "value":"1_1_0"
                       }
                     , {
                         "unexpanded":"1_1_1"
                       ,  "value":"1_1_1"
                       }
                     ]
                   }
                 , {
                     "componentName": "m_var_1_2"
                   , "values"       : [
                       {
                         "unexpanded":"1_2_0"
                       , "value":"1_2_0"
                       }
                     , {
                         "unexpanded":"1_2_1"
                       ,  "value":"1_2_1"
                       }
                     ]
                   }
                 ]
               }
             ]
           }
         , {
             "ruleId": "rule_2"
           , "directives": [
               {
                 "directiveId": "directive_2"
               , "isSystem"   : false
               , "components" : [
                   {
                     "componentName": "var_1_0"
                   , "values"       : [
                       {
                         "unexpanded":"2_0_0"
                       , "value":"2_0_0"
                       }
                     ]
                   }
                 , {
                     "componentName": "m_var_1_1"
                   , "values"       : [
                       {
                         "unexpanded":"2_1_0"
                       , "value":"2_1_0"
                       }
                     ]
                   }
                 , {
                     "componentName": "m_var_1_2"
                   , "values"       : [
                       {
                         "unexpanded":"2_2_0"
                       , "value":"2_2_0"
                       }
                     ]
                   }
                 ]
               }
             , {
                 "directiveId": "directive_3"
               , "isSystem"   : false
               , "components" : [
                   {
                     "componentName": "var_2_0"
                   , "values"       : [
                       {
                         "unexpanded":"3_0_0"
                       , "value":"3_0_0"
                       }
                     ]
                   }
                 , {
                     "componentName":"m_var_2_1"
                   , "values"       : [
                       {
                         "unexpanded":"3_1_0"
                       , "value":"3_1_0"
                       }
                     ]
                   }
                 , {
                     "componentName": "m_var_2_2"
                   , "values"       : [
                       {
                         "unexpanded":"3_2_0"
                       , "value":"3_2_0"
                       }
                     ]
                   }
                 ]
               }
             ]
           }
         ]""")

      compareExpectedReportsJson(expected, p1 :: p2 :: Nil)
    }
  }

  "The rule expected reports from a technique with blocks " should {
    def componentIdCreator(componentKey: String, parentPath: List[String], value: String) : (ComponentId, Variable)= {
      // expectedReportKey is the prefix of the variable name, so necessary
      val componentId = ComponentId("expectedReportKey " + componentKey, parentPath)
      val variable = SectionVariableSpec("expectedReportKey " + componentKey, "", "REPORTKEYS", valueslabels = Nil, providedValues = Seq(value), id = None)
      (componentId, variable.toVariable(Seq(value)))
    }

    val policyVarsMaps : Map[ComponentId, Variable]= Map(
        componentIdCreator("Command execution", List("Command execution", "SECTIONS")                              , "/bin/true #root1")
      , componentIdCreator("File absent"      , List("File absent", "SECTIONS")                                    , "/tmp/root2")
      , componentIdCreator("File absent"      , List("File absent", "First block", "SECTIONS")                     , "/tmp/block1")
      , componentIdCreator("File absent"      , List("File absent", "inner block", "First block", "SECTIONS")      , "/tmp/block1_1")
      , componentIdCreator("Command execution", List("Command execution", "inner block", "First block", "SECTIONS"), "/bin/true")
    )

    val p_with_block_id = PolicyId(r3, d4, TechniqueVersionHelper("1.0"))
    val p_with_block = Policy(
      p_with_block_id
      , "rule name with block"
      , "directive name with block"
      , technique = policyTechniqueWithBlock
      , DateTime.now.minusDays(1)
      , policyVars     = NonEmptyList.of(
        PolicyVars(
          PolicyId(r3, d4, TechniqueVersionHelper("1.0"))
          , Some(PolicyMode.Enforce)
          , policyVarsMaps
          , policyVarsMaps
          , policyTechniqueWithBlock.trackerVariableSpec.toVariable(p_with_block_id.getReportId :: Nil)
        )
      )
      , priority       = 0
      , policyMode     = None
      , ruleOrder      = BundleOrder("1")
      , directiveOrder = BundleOrder("1")
      , overrides      = Set()
    )

    "return the expected expected reports" in {

      val expectedJson =

        """
        [
           {
             "ruleId": "rule_3"
           , "directives": [
               {
                 "directiveId": "directive_4"
               , "policyMode" : "enforce"
               , "isSystem"   : false
               , "components":[
                   {
                     "componentName":"File absent"
                   , "values": [
                       {
                         "unexpanded":"/tmp/root2"
                       , "value":"/tmp/root2"
                       }
                     ]
                   }
                 , {
                     "componentName":"Command execution"
                   , "values": [
                       {
                         "unexpanded":"/bin/true #root1"
                       , "value":"/bin/true #root1"
                       }
                     ]
                   }
                 , {
                     "componentName":"First block"
                   , "reportingLogic":"weighted"
                   , "subComponents": [
                       {
                         "componentName":"File absent"
                       , "values":[
                       {
                         "unexpanded":"/tmp/block1"
                       , "value":"/tmp/block1"
                       }
                     ]
                       }
                     , {
                         "componentName":"inner block"
                       , "reportingLogic":"weighted"
                       , "subComponents": [
                         {
                           "componentName":"File absent"
                         , "values":[
                       {
                         "unexpanded":"/tmp/block1_1"
                       , "value":"/tmp/block1_1"
                       }
                     ]
                         }
                       , {
                           "componentName":"Command execution"
                         , "values": [
                       {
                         "unexpanded":"/bin/true"
                       , "value":"/bin/true"
                       }
                       ]
                         }
                       ]
                     }
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
