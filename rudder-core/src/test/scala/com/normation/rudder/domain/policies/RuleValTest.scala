/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.domain.policies

import org.junit.Test
import org.junit._
import org.junit.Assert._
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import org.springframework.context.annotation.{ Bean, Configuration, Import }
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import com.normation.cfclerk.domain.VariableSpec
import com.normation.utils.Utils._
import org.joda.time.DateTime
import com.normation.cfclerk.domain._

import com.normation.rudder.domain.nodes.NodeGroupId
import net.liftweb.common._
import Box._
import org.joda.time.{ LocalDate, LocalTime, Duration, DateTime }
import com.normation.rudder.domain._
import com.normation.cfclerk.domain.InputVariable

@RunWith(classOf[BlockJUnit4ClassRunner])
class RuleValTest {

  def newTechnique(id: TechniqueId) = Technique(id, "tech" + id, "", Seq(), Seq(), TrackerVariableSpec(), SectionSpec("plop"), Set(), None)

  /* create representation of meta techniques */
  def makePredefinedSectionSpec(name: String, providedValues: (String, Seq[String])) =
    PredefinedValuesVariableSpec(
        name
      , "description"
      , providedValues
    )
  def makeComponentSectionSpec(name:String) =
    SectionSpec(
        name
      , true
      , true
      , None
      , HighDisplayPriority
      , ""
      , Seq(makePredefinedSectionSpec(name, ("variable", Seq(name+"one", name+"two"))))
    )

  def makeRootSectionSpec() =
    SectionSpec(
        "root section"
      , false
      , false
      , None
      , HighDisplayPriority
      , ""
      , Seq(
            makeComponentSectionSpec("component1")
          , makeComponentSectionSpec("component2")
        )
    )

  def makeMetaTechnique(id: TechniqueId) =
    Technique(
        id
      , "meta" + id
      , ""
      , Seq()
      , Seq()
      , TrackerVariableSpec(None)
      , makeRootSectionSpec
      , Set()
      , None
      , false
      , ""
      , false
      , true
    )
  @Test
  def testCRwithNoPI() {
    new RuleVal(
      new RuleId("id"),
      Set(new GroupTarget(new NodeGroupId("target"))),
      Seq(),
      0)
    //TODO: what that test actually tests ?
    //return Unit
    ()
  }

  @Test
  def testBasicRuleVal() {
    val container = new DirectiveVal(
      newTechnique(new TechniqueId(TechniqueName("id"), TechniqueVersion("1.0"))),
   //   new ActiveTechniqueId("id"),
      new DirectiveId("id"),
      0,
      TrackerVariableSpec().toVariable(),
      Map(),
      Map())

    new RuleVal(
      new RuleId("id"),
      Set(new GroupTarget(new NodeGroupId("target"))),
      Seq(container),
      0)

    //TODO: what that test actually tests ?
    //return Unit
    ()
  }

  @Test
  def testToRuleWithCf3PolicyDraft() {
    val container = new DirectiveVal(
      newTechnique(new TechniqueId(TechniqueName("ppId"), TechniqueVersion("1.0"))),
    //  new ActiveTechniqueId("uactiveTechniqueId"),
      new DirectiveId("directiveId"),
      2,
      TrackerVariableSpec().toVariable(),
      Map(),
      Map())

    val crVal = new RuleVal(
      new RuleId("ruleId"),
      Set(new GroupTarget(new NodeGroupId("target"))),
      Seq(container),
      1)

    val beans = crVal.toPolicyDrafts.map(_.toRuleWithCf3PolicyDraft)
    assert(beans.length == 1)
    assertEquals(beans.head.draftId.value, "ruleId@@directiveId")
    assertEquals(beans.head.ruleId.value, "ruleId")
    assertEquals(beans.head.cf3PolicyDraft.technique.id.name.value, "ppId")
    assertEquals(beans.head.cf3PolicyDraft.technique.id.version.toString, "1.0")
    assertEquals(beans.head.cf3PolicyDraft.getVariables.size.toLong, 0L)
    assertEquals(beans.head.cf3PolicyDraft.priority.toLong, 2L)
    assertEquals(beans.head.cf3PolicyDraft.serial.toLong, 1L)
  }

  @Test
  def testToDirectiveBeanWithVar() {
    val container = new DirectiveVal(
      newTechnique(new TechniqueId(TechniqueName("ppId"), TechniqueVersion("1.0"))),
   //   new ActiveTechniqueId("uactiveTechniqueId"),
      new DirectiveId("directiveId"),
      2,
      TrackerVariableSpec().toVariable(),
      Map("foo" -> InputVariableSpec("foo", "bar").toVariable()),
      Map())

    val crVal = new RuleVal(
      new RuleId("ruleId"),
      Set(new GroupTarget(new NodeGroupId("target"))),
      Seq(container),
      1)

    val beans = crVal.toPolicyDrafts.map(_.toRuleWithCf3PolicyDraft)
    assert(beans.length == 1)
    assertEquals(beans.head.draftId.value, "ruleId@@directiveId")
    assertEquals(beans.head.ruleId.value, "ruleId")
    assertEquals(beans.head.cf3PolicyDraft.technique.id.name.value, "ppId")
    assertEquals(beans.head.cf3PolicyDraft.technique.id.version.toString, "1.0")
    assertEquals(beans.head.cf3PolicyDraft.getVariables.size.toLong, 1L)
    assertEquals(beans.head.cf3PolicyDraft.priority.toLong, 2L)
    assertEquals(beans.head.cf3PolicyDraft.serial.toLong, 1L)
    assertEquals(beans.head.cf3PolicyDraft.getVariables,
      Map("foo" -> InputVariableSpec("foo", "bar").toVariable()))
  }


  @Test
  def testBasicRuleValBasedOnMetaTechnique() {
    val container = new DirectiveVal(
      makeMetaTechnique(new TechniqueId(TechniqueName("id"), TechniqueVersion("1.0"))),
      new DirectiveId("id"),
      0,
      TrackerVariableSpec().toVariable(),
      Map(),
      Map())

    new RuleVal(
      new RuleId("id"),
      Set(new GroupTarget(new NodeGroupId("target"))),
      Seq(container),
      0)

    //TODO: what that test actually tests ?
    //return Unit
    ()
  }

  @Test
  def testToRuleWithCf3PolicyDraftBasedOnMetaTechnique() {
    val container = new DirectiveVal(
      makeMetaTechnique(new TechniqueId(TechniqueName("ppId"), TechniqueVersion("1.0"))),
    //  new ActiveTechniqueId("uactiveTechniqueId"),
      new DirectiveId("directiveId"),
      2,
      TrackerVariableSpec().toVariable(),
      Map(),
      Map())

    val crVal = new RuleVal(
      new RuleId("ruleId"),
      Set(new GroupTarget(new NodeGroupId("target"))),
      Seq(container),
      1)

    val beans = crVal.toPolicyDrafts.map(_.toRuleWithCf3PolicyDraft)
    assert(beans.length == 1)
    assertEquals(beans.head.draftId.value, "ruleId@@directiveId")
    assertEquals(beans.head.ruleId.value, "ruleId")
    assertEquals(beans.head.cf3PolicyDraft.technique.id.name.value, "ppId")
    assertEquals(beans.head.cf3PolicyDraft.technique.id.version.toString, "1.0")
    assertEquals(beans.head.cf3PolicyDraft.getVariables.size.toLong, 0L)
    assertEquals(beans.head.cf3PolicyDraft.priority.toLong, 2L)
    assertEquals(beans.head.cf3PolicyDraft.serial.toLong, 1L)
  }
}