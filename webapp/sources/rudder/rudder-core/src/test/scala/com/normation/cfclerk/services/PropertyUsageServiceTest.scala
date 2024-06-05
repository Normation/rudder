package com.normation.cfclerk.services

/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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

import com.normation.cfclerk.domain.ReportingLogic.FocusReport
import com.normation.errors.IOResult
import com.normation.errors.RudderError
import com.normation.inventory.domain.Version
import com.normation.rudder.MockDirectives
import com.normation.rudder.MockGitConfigRepo
import com.normation.rudder.MockTechniques
import com.normation.rudder.hooks.CmdResult
import com.normation.rudder.ncf.BundleName
import com.normation.rudder.ncf.EditorTechnique
import com.normation.rudder.ncf.EditorTechniqueReader
import com.normation.rudder.ncf.GenericMethod
import com.normation.rudder.ncf.MethodBlock
import com.normation.rudder.ncf.MethodCall
import com.normation.rudder.ncf.ParameterId
import com.normation.rudder.services.nodes.PropertyUsageService
import com.normation.zio.*
import net.liftweb.common.Loggable
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import zio.syntax.*

@RunWith(classOf[JUnitRunner])
class PropertyUsageServiceTest extends Specification with Loggable {
  sequential
//  isolated

  val mockGitRepo = new MockGitConfigRepo("")
  val mockTechniques: MockTechniques = MockTechniques(mockGitRepo)
  val mockDirectives: MockDirectives = new MockDirectives(mockTechniques)

  val techniqueBlock = {
    EditorTechnique(
      BundleName("technique_with_a_property_in_a_block"),
      new Version("1.0"),
      "Technique with a property in a block",
      "ncf_techniques",
      MethodBlock(
        "id-block",
        "Dummy block",
        FocusReport("focus"),
        "",
        MethodCall(
          BundleName("package_install"),
          "id4",
          Map(
            (ParameterId("package_name"), s"$${node.properties[my_property_in_block] | default = \"tutu\" | opt1 = \"hello\"}")
          ),
          "redhat",
          "Package install",
          false,
          None
        ) :: Nil,
        None
      ) :: Nil,
      "",
      "",
      Nil,
      Nil,
      Map(),
      None
    )
  }

  def generateTechniqueEditor(propertySyntax: String, techniqueName: String) = {
    val techniqueId = techniqueName.toLowerCase().replaceAll(" ", "_")
    EditorTechnique(
      BundleName(techniqueId),
      new Version("1.0"),
      techniqueName,
      "ncf_techniques",
      MethodCall(
        BundleName("command_execution"),
        "id4",
        Map(
          (ParameterId("command_execution"), s"/tmp/$propertySyntax/toto")
        ),
        "redhat",
        "Command Execution",
        false,
        None
      ) :: Nil,
      "",
      "",
      Nil,
      Nil,
      Map(),
      None
    )
  }

  val propertySubVal          = "my_property_subval"
  val propertyWithOptions     = "property_with_opts"
  val propertyWithEmptySubVal = "property_with_empty_subval"
  val propertyWithSyntaxTypo  = "property_with_typo"

  class MockEditorTechniquesForProperty() extends EditorTechniqueReader {
    def readTechniquesMetadataFile: IOResult[(List[EditorTechnique], Map[BundleName, GenericMethod], List[RudderError])] = {
      val methods: Map[BundleName, GenericMethod] = Map()
      (
        List(
          techniqueBlock,
          generateTechniqueEditor(s"$${node.properties[$propertySubVal][subval]}", "Test find usage of property with sub values"),
          generateTechniqueEditor(
            s"$${node.properties[$propertyWithOptions][subval]|default=\"hello\" | opt1 = \"world\"}",
            "Test find usage of property with optional values"
          ),
          generateTechniqueEditor(
            s"$${node.properties[$propertyWithEmptySubVal][]|default=\"hello\" | opt1 = \"world\"}",
            "Test find usage of property with an empty sub value"
          ),
          generateTechniqueEditor(
            s"$${node.property[$propertyWithSyntaxTypo][]|default=\"hello\" | opt1 = \"world\"}",
            "Test find usage of property with an empty sub value"
          )
        ),
        methods,
        List.empty
      ).succeed
    }
    def getMethodsMetadata:         IOResult[Map[BundleName, GenericMethod]]                                             = null
    def updateMethodsMetadataFile:  IOResult[CmdResult]                                                                  = null
  }

  val propertyUsageService = new PropertyUsageService(
    mockDirectives.directiveRepo,
    new MockEditorTechniquesForProperty()
  )

  "Find property usage service" should {
    "Find a usage of property inside a block" in {
      val techName = "Technique with a property in a block"
      val techId   = techName.toLowerCase().replaceAll(" ", "_")
      propertyUsageService.findPropertyInTechnique("my_property_in_block").runNow shouldEqual List((BundleName(techId), techName))
    }
    "Find a usage of property that use sub values" in {
      val techName = "Test find usage of property with sub values"
      val techId   = techName.toLowerCase().replaceAll(" ", "_")
      propertyUsageService.findPropertyInTechnique(propertySubVal).runNow shouldEqual List((BundleName(techId), techName))
    }
    "Find a usage of property that use optional values" in {
      val techName = "Test find usage of property with optional values"
      val techId   = techName.toLowerCase().replaceAll(" ", "_")
      propertyUsageService.findPropertyInTechnique(propertyWithOptions).runNow shouldEqual List((BundleName(techId), techName))
    }
    "Find a usage of property that use empty sub values" in {
      val techName = "Test find usage of property with an empty sub value"
      val techId   = techName.toLowerCase().replaceAll(" ", "_")
      propertyUsageService.findPropertyInTechnique(propertyWithEmptySubVal).runNow shouldEqual List(
        (BundleName(techId), techName)
      )
    }
    "Not find any usage for unknown property" in {
      propertyUsageService.findPropertyInTechnique("this_property_does_not_exist").runNow shouldEqual List.empty
    }
    "Not find any usage with typo syntax property" in {
      propertyUsageService.findPropertyInTechnique(propertyWithSyntaxTypo).runNow shouldEqual List.empty
    }

    "find a property hello_world in Directives" in {
      val dirId   = mockDirectives.directives.findPropUsageDirective.id
      val dirName = mockDirectives.directives.findPropUsageDirective.name
      propertyUsageService.findPropertyInDirective("hello_world").runNow shouldEqual List((dirId, dirName))
    }
  }

}
