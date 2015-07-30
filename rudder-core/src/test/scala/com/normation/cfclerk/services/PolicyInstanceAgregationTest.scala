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

package com.normation.cfclerk.services

import junit.framework.TestSuite
import org.junit.Test
import org.junit._
import org.junit.Assert._
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import com.normation.cfclerk.domain._
import com.normation.cfclerk.services.impl.Cf3PromisesFileWriterServiceImpl
import com.normation.cfclerk.services.impl.SystemVariableSpecServiceImpl
import com.normation.cfclerk.xmlparsers.CfclerkXmlConstants._

@RunWith(classOf[BlockJUnit4ClassRunner])
class DirectiveAgregationTest {
  import scala.language.implicitConversions
  implicit def str2pId(id: String) = TechniqueId(TechniqueName(id), TechniqueVersion("1.0"))
  implicit def str2directiveId(id: String) = Cf3PolicyDraftId(id)

  def compareValues(expected: Seq[(String, String)], actual1: Seq[String], actual2: Seq[String]) = {
    assertEquals(expected.size, actual1.size)
    assertEquals(expected.size, actual2.size)
    val actual = actual1.zip(actual2)

    expected.foreach { e =>
      assertEquals(expected.groupBy(x => x), actual.groupBy(x => x))
    }
  }

  def newTechnique(id: TechniqueId) = Technique(id, "tech" + id, "", Seq(), Seq(), TrackerVariableSpec(), SectionSpec("plop"), None, Set(), None)

  import scala.collection.immutable.Set
  val trackerVariableSpec = TrackerVariableSpec(Some("card"))
  val trackerVariable = TrackerVariable(trackerVariableSpec, Seq())

  val activeTechniqueId1 = TechniqueId(TechniqueName("name"), TechniqueVersion("1.0"))
  val activeTechniqueId2 = TechniqueId(TechniqueName("other"), TechniqueVersion("1.0"))

  val templateDependencies = new Cf3PromisesFileWriterServiceImpl(
    new DummyTechniqueRepository(Seq(
        Technique(
            activeTechniqueId1
          , "name"
          , "DESCRIPTION"
          , Seq()
          , Seq()
          , trackerVariableSpec
          , SectionSpec(name="root", children=Seq())
          , None
          , isMultiInstance = true
        )
      , Technique(
            activeTechniqueId2
          , "name"
          , "DESCRIPTION"
          , Seq()
          , Seq()
          , trackerVariableSpec
          , SectionSpec(name="root", children=Seq())
          , None
          , isMultiInstance = true
        )
    ) ),
    new SystemVariableSpecServiceImpl())

  def createDirectiveWithBinding(activeTechniqueId:TechniqueId, i: Int): Cf3PolicyDraft = {
    val instance = new Cf3PolicyDraft("id" + i, newTechnique(activeTechniqueId),
        Map(), trackerVariable, priority = 0, serial = 0, order = List())

    val variable = new InputVariable(InputVariableSpec("card", "varDescription1"), Seq("value" + i))
    instance.copyWithAddedVariable(variable)
  }

  def createDirectiveWithArrayBinding(activeTechniqueId:TechniqueId, i: Int): Cf3PolicyDraft = {
    val instance = new Cf3PolicyDraft("id" + i, newTechnique(activeTechniqueId), Map(), trackerVariable, priority = 0, serial = 0, order = List())

    val variable = InputVariable(
          InputVariableSpec("card", "varDescription1", multivalued = true)
        , values = (0 until i).map(_ => "value" + i).toSeq
    )

    instance.copyWithAddedVariable(variable)
  }

  def createDirectiveWithArrayBindingAndNullValues(activeTechniqueId:TechniqueId, i: Int): Cf3PolicyDraft = {
    val instance = new Cf3PolicyDraft("id" + i, newTechnique(activeTechniqueId), Map(), trackerVariable, priority = 0, serial = 0, order = List())

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
    val node = new Cf3PolicyDraftContainer("node", Set())

    node.add(createDirectiveWithBinding(activeTechniqueId1, 1))
    node.add(createDirectiveWithBinding(activeTechniqueId1, 2))
    node.add(createDirectiveWithBinding(activeTechniqueId2, 3))

    for( id <- "id1" :: "id2" :: "id3" :: Nil) {
      node.get(id) match {
        case None => fail("Couldn't find the instance")
        case Some(x) => assert(x.id.value == id)
      }
    }

    val allVars = templateDependencies.prepareAllCf3PolicyDraftVariables(node)

    assertEquals(2, allVars(activeTechniqueId1).size)
    assertTrue(allVars(activeTechniqueId1).contains("card"))
    assertTrue(allVars(activeTechniqueId1).contains(TRACKINGKEY))

    compareValues(Seq(("value1", "id1@@0"), ("value2", "id2@@0")), allVars(activeTechniqueId1)("card").values, allVars(activeTechniqueId1)(TRACKINGKEY).values)

    assertEquals(2, allVars(activeTechniqueId2).size)
    assertTrue(allVars(activeTechniqueId2).contains("card"))
    assertTrue(allVars(activeTechniqueId2).contains(TRACKINGKEY))

    compareValues(Seq(("value3", "id3@@0")), allVars(activeTechniqueId2)("card").values, allVars(activeTechniqueId2)(TRACKINGKEY).values)
  }

  // Create a Directive with arrayed value , and add it to a server, and agregate values
  @Test
  def arrayedDirectiveTest() {
    val machineA = new Cf3PolicyDraftContainer("machineA", Set())

    val instance = new Cf3PolicyDraft("id", newTechnique(TechniqueId(TechniqueName("name"), TechniqueVersion("1.0"))),
        Map(), trackerVariable, priority = 0, serial = 0, order = List())
    machineA.add(createDirectiveWithArrayBinding(activeTechniqueId1,1))
    machineA.add(createDirectiveWithArrayBinding(activeTechniqueId1,2))

    machineA.get("id1") match {
      case None => fail("Couldn't find the instance")
      case Some(x) => assert(x.id.value == "id1")
    }
    machineA.get("id2") match {
      case None => fail("Couldn't find the instance")
      case Some(x) => assert(x.id.value == "id2")
    }

    val allVars = templateDependencies.prepareAllCf3PolicyDraftVariables(machineA)
    assert(allVars(activeTechniqueId1).size == 2)
    assert(allVars(activeTechniqueId1).contains("card"))
    assert(allVars(activeTechniqueId1).contains(TRACKINGKEY))

    compareValues(Seq(("value1", "id1@@0"), ("value2", "id2@@0"), ("value2", "id2@@0")), allVars(activeTechniqueId1)("card").values, allVars(activeTechniqueId1)(TRACKINGKEY).values)

  }

  // Create a Directive with arrayed & nulledvalue , and add it to a server, and agregate values
  @Test
  def arrayedAndNullDirectiveTest() {
    val machineA = new Cf3PolicyDraftContainer("machineA", Set())

    val instance = createDirectiveWithArrayBindingAndNullValues(activeTechniqueId1,1)
    machineA.add(instance)
    machineA.add(createDirectiveWithArrayBindingAndNullValues(activeTechniqueId1,2))

    machineA.get("id1") match {
      case None => fail("Couldn't find the instance")
      case Some(x) => assert(x.id.value == "id1")
    }
    machineA.get("id2") match {
      case None => fail("Couldn't find the instance")
      case Some(x) => assert(x.id.value == "id2")
    }

    val allVars = templateDependencies.prepareAllCf3PolicyDraftVariables(machineA)
    assert(allVars(activeTechniqueId1).size == 2)
    assert(allVars(activeTechniqueId1).contains("card"))
    assert(allVars(activeTechniqueId1).contains(TRACKINGKEY))

    compareValues(Seq((null, "id1@@0"), (null, "id2@@0"), ("value2", "id2@@0")), allVars(activeTechniqueId1)("card").values, allVars(activeTechniqueId1)(TRACKINGKEY).values)

  }
}
