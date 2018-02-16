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

import com.normation.cfclerk.domain._
import com.normation.cfclerk.services.impl.SystemVariableSpecServiceImpl
import com.normation.cfclerk.xmlparsers.CfclerkXmlConstants._
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.services.policies.BundleOrder
import org.junit._
import org.junit.Assert._
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import com.normation.cfclerk.services.DummyTechniqueRepository
import org.joda.time.DateTime
import com.normation.inventory.domain.AgentType



@RunWith(classOf[BlockJUnit4ClassRunner])
class DirectiveAgregationTest {
  import scala.language.implicitConversions
  implicit def str2pId(id: String) = TechniqueId(TechniqueName(id), TechniqueVersion("1.0"))
  implicit def str2directiveId(id: String) = Cf3PolicyDraftId(RuleId("r_"+id), DirectiveId("d_"+id))

  def compareValues(expected: Seq[(String, String)], actual1: Seq[String], actual2: Seq[String]) = {
    val actual = actual1.zip(actual2)
    expected.foreach { e =>
      assertEquals(expected.groupBy(x => x), actual.groupBy(x => x))
    }
    assertEquals(expected.size, actual1.size)
    assertEquals(expected.size, actual2.size)
  }


  import scala.collection.immutable.Set
  val trackerVariableSpec = TrackerVariableSpec(Some("card"))
  val trackerVariable = TrackerVariable(trackerVariableSpec, Seq())

  val activeTechniqueId1 = TechniqueId(TechniqueName("name"), TechniqueVersion("1.0"))
  val activeTechniqueId2 = TechniqueId(TechniqueName("other"), TechniqueVersion("1.0"))

  val techniqueRepository = new DummyTechniqueRepository(Seq(
      Technique(
          activeTechniqueId1
        , "name"
        , "DESCRIPTION"
        , Nil
        , trackerVariableSpec
        , SectionSpec(name="root", children=Seq())
        , None
        , isMultiInstance = true
      )
    , Technique(
          activeTechniqueId2
        , "name"
        , "DESCRIPTION"
        , Nil
        , trackerVariableSpec
        , SectionSpec(name="root", children=Seq())
        , None
        , isMultiInstance = true
      )
  ) )

  val systemVariableServiceSpec = new SystemVariableSpecServiceImpl()
  val prepareTemplate = new PrepareTemplateVariablesImpl(
      techniqueRepository
    , systemVariableServiceSpec
    , new BuildBundleSequence(systemVariableServiceSpec, new WriteAllAgentSpecificFiles())
  )

  def createInstance(activeTechniqueId:TechniqueId, id: String) = {
    new Cf3PolicyDraft(
        id
      , techniqueRepository.get(activeTechniqueId).get
      , DateTime.now
      , Map()
      , trackerVariable
      , 0
      , false
      , None
      , AgentType.CfeCommunity
      , 0
      , BundleOrder("r")
      , BundleOrder("d")
      , Set()
    )
  }

  def createDirectiveWithBinding(activeTechniqueId:TechniqueId, i: Int): Cf3PolicyDraft = {
    val instance = createInstance(activeTechniqueId, "id" + i)

    val variable = new InputVariable(InputVariableSpec("card", "varDescription1"), Seq("value" + i))
    instance.copyWithAddedVariable(variable)
  }

  def createDirectiveWithArrayBinding(activeTechniqueId:TechniqueId, i: Int): Cf3PolicyDraft = {
    val instance = createInstance(activeTechniqueId, "id" + i)

    val variable = InputVariable(
          InputVariableSpec("card", "varDescription1", multivalued = true)
        , values = (0 until i).map(_ => "value" + i).toSeq
    )

    instance.copyWithAddedVariable(variable)
  }

  def createDirectiveWithArrayBindingAndNullValues(activeTechniqueId:TechniqueId, i: Int): Cf3PolicyDraft = {
    val instance = createInstance(activeTechniqueId, "id" + i)

    val values = (0 until i).map(j =>
      if (j > 0) "value" + i
      else null
    )

    val variable = InputVariable(InputVariableSpec("card", "varDescription1", multivalued = true), values)

    instance.copyWithAddedVariable(variable)
  }

  // Create a Directive, with value , and add it to a server, and aggregate values
  @Test
  def simpleDirectiveTest() {
    val node = new Cf3PolicyDraftContainer(Set(), Set(
        createDirectiveWithBinding(activeTechniqueId1, 1)
      , createDirectiveWithBinding(activeTechniqueId1, 2)
      , createDirectiveWithBinding(activeTechniqueId2, 3)
    ))

    val allVars = prepareTemplate.prepareAllCf3PolicyDraftVariables(node)


    compareValues(Seq(("value1", "r_id1@@d_id1@@0"), ("value2", "r_id2@@d_id2@@0")), allVars(activeTechniqueId1)("card").values, allVars(activeTechniqueId1)(TRACKINGKEY).values)
    assertEquals(2, allVars(activeTechniqueId1).size)
    assertTrue(allVars(activeTechniqueId1).contains("card"))
    assertTrue(allVars(activeTechniqueId1).contains(TRACKINGKEY))

    compareValues(Seq(("value3", "r_id3@@d_id3@@0")), allVars(activeTechniqueId2)("card").values, allVars(activeTechniqueId2)(TRACKINGKEY).values)
    assertEquals(2, allVars(activeTechniqueId2).size)
    assertTrue(allVars(activeTechniqueId2).contains("card"))
    assertTrue(allVars(activeTechniqueId2).contains(TRACKINGKEY))
  }

  // Create a Directive with arrayed value , and add it to a server, and agregate values
  @Test
  def arrayedDirectiveTest() {

    val machineA = new Cf3PolicyDraftContainer(Set(), Set(
        createDirectiveWithArrayBinding(activeTechniqueId1,1)
      , createDirectiveWithArrayBinding(activeTechniqueId1,2)
    ))


    val allVars = prepareTemplate.prepareAllCf3PolicyDraftVariables(machineA)
    assert(allVars(activeTechniqueId1).size == 2)
    assert(allVars(activeTechniqueId1).contains("card"))
    assert(allVars(activeTechniqueId1).contains(TRACKINGKEY))

    compareValues(Seq(("value1", "r_id1@@d_id1@@0"), ("value2", "r_id2@@d_id2@@0"), ("value2", "r_id2@@d_id2@@0")), allVars(activeTechniqueId1)("card").values, allVars(activeTechniqueId1)(TRACKINGKEY).values)

  }

  // Create a Directive with arrayed & nulledvalue , and add it to a server, and agregate values
  @Test
  def arrayedAndNullDirectiveTest() {
    val instance = createDirectiveWithArrayBindingAndNullValues(activeTechniqueId1,1)
    val machineA = new Cf3PolicyDraftContainer(Set(), Set(
        instance
      , createDirectiveWithArrayBindingAndNullValues(activeTechniqueId1,2)
    ))


    val allVars = prepareTemplate.prepareAllCf3PolicyDraftVariables(machineA)
    assert(allVars(activeTechniqueId1).size == 2)
    assert(allVars(activeTechniqueId1).contains("card"))
    assert(allVars(activeTechniqueId1).contains(TRACKINGKEY))

    compareValues(Seq((null, "r_id1@@d_id1@@0"), (null, "r_id2@@d_id2@@0"), ("value2", "r_id2@@d_id2@@0")), allVars(activeTechniqueId1)("card").values, allVars(activeTechniqueId1)(TRACKINGKEY).values)

  }
}
