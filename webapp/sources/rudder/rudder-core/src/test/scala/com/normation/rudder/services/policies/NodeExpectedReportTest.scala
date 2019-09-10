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
import com.normation.cfclerk.domain.AgentConfig
import com.normation.inventory.domain.AgentType
import com.normation.cfclerk.domain.BundleName
import com.normation.cfclerk.domain.TrackerVariableSpec
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.cfclerk.domain.SectionVariableSpec
import org.joda.time.DateTime
import cats.data.NonEmptyList
import com.normation.rudder.domain.policies.PolicyMode
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
  def tvar(x: String)  = SectionVariableSpec(s"var_${x}", "", "INPUT", valueslabels = Nil, providedValues = Nil)
  // a multivalued var
  def tmvar(x: String) = SectionVariableSpec(s"m_var_${x}", "", "INPUT", multivalued = true, valueslabels = Nil, providedValues = Nil)

  // return the couple of (var name, var with value)
  def v(x: String, values: String*) = {
    val v = tvar(x).toVariable(values)
    (v.spec.name, v)
  }
  // same with multivalued
  def mv(x: String, values: String*) = {
    val v = tmvar(x).toVariable(values)
    (v.spec.name, v)
  }

  def technique(x: String) = {
    PolicyTechnique(
        TechniqueId(TechniqueName("t"+x), TechniqueVersion("1.0"))
      , AgentConfig(AgentType.CfeCommunity, Nil, Nil, List(BundleName("t"+x)), Nil)
      , TrackerVariableSpec(Some(s"m_var_${x}_1"))
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

  val r1 = RuleId("rule_1")
  val r2 = RuleId("rule_2")
  val d1 = DirectiveId("directive_1")
  val d2 = DirectiveId("directive_2")
  val d3 = DirectiveId("directive_3")

  val p1_id = PolicyId(r1, d1, TechniqueVersion("1.0"))
  val p1 = Policy(
      p1_id
    , "rule name"
    , "directive name"
    , technique      = t1
    , DateTime.now.minusDays(1)
    , policyVars     = NonEmptyList.of(
          PolicyVars(
              PolicyId(r1, d1, TechniqueVersion("1.0"))
            , Some(PolicyMode.Audit)
            , Map(v("1_0", "1_0_0"), mv("1_1", "1_1_0", "1_1_1"), mv("1_2", "1_2_0", "1_2_1"))
            , Map(v("1_0", "1_0_0"), mv("1_1", "1_1_0", "1_1_1"), mv("1_2", "1_2_0", "1_2_1"))
            , t1.trackerVariableSpec.toVariable(p1_id.getReportId :: p1_id.getReportId :: Nil)
          )
        , PolicyVars(
              PolicyId(r2, d2, TechniqueVersion("1.0"))
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

  val p2_id = PolicyId(r2, d3, TechniqueVersion("1.0"))
  val p2 = Policy(
      p2_id
    , "rule name"
    , "directive name"
    , technique      = t2
    , DateTime.now.minusDays(1)
    , policyVars     = NonEmptyList.of(
          PolicyVars(
              PolicyId(r2, d3, TechniqueVersion("1.0"))
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
                   , "values"       : ["1_0_0"]
                   , "unexpanded"   : ["1_0_0"]
                   }
                 , {
                     "componentName": "m_var_1_1"
                   , "values"       : ["1_1_0", "1_1_1"]
                   , "unexpanded"   : ["1_1_0", "1_1_1"]
                   }
                 , {
                     "componentName": "m_var_1_2"
                   , "values"       : ["1_2_0", "1_2_1"]
                   , "unexpanded"   : ["1_2_0", "1_2_1"]
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
                   , "values"       : ["2_0_0"]
                   , "unexpanded"   : ["2_0_0"]
                   }
                 , {
                     "componentName": "m_var_1_1"
                   , "values"       : ["2_1_0"]
                   , "unexpanded"   : ["2_1_0"]
                   }
                 , {
                     "componentName": "m_var_1_2"
                   , "values"       : ["2_2_0"]
                   , "unexpanded"   : ["2_2_0"]
                   }
                 ]
               }
             , {
                 "directiveId": "directive_3"
               , "isSystem"   : false
               , "components" : [
                   {
                     "componentName": "var_2_0"
                   , "values"       : ["3_0_0"]
                   , "unexpanded"   : ["3_0_0"]
                   }
                 , {
                     "componentName":"m_var_2_1"
                   , "values"       : ["3_1_0"]
                   , "unexpanded"   : ["3_1_0"]
                   }
                 , {
                     "componentName": "m_var_2_2"
                   , "values"       : ["3_2_0"]
                   , "unexpanded"   : ["3_2_0"]
                   }
                 ]
               }
             ]
           }
         ]""")



      val json = ExpectedReportsSerialisation.jsonRuleExpectedReports(RuleExpectedReportBuilder(p1 :: p2 :: Nil).sortBy( _.ruleId.value))
      val Diff(changed, added, deleted) = expected diff json
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
      // all that for that...
      (clean(changed) === JNothing) and (clean(added) === JNothing) and (clean(deleted) === JNothing)
    }
  }
}
