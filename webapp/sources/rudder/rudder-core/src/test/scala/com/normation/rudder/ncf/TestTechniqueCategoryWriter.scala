/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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
package com.normation.rudder.ncf

import better.files.File
import com.normation.cfclerk.domain.TechniqueCategoryId
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.MockGitConfigRepo
import com.normation.rudder.MockTechniques
import com.normation.rudder.tenants.ChangeContext
import com.normation.rudder.tenants.QueryContext
import com.normation.zio.*
import java.time.Instant
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestTechniqueCategoryWriter extends Specification {
  sequential

  private val mockGitRepo    = new MockGitConfigRepo("")
  private val mockTechniques = MockTechniques(mockGitRepo)
  private val writer         = mockTechniques.techniqueCategoryWriter

  implicit private val cc: ChangeContext = ChangeContext(
    EventActor("test-category-writer"),
    QueryContext.testQC.accessGrant,
    ModificationId("test-category-writer"),
    Instant.now(),
    None,
    None
  )

  private def techniquesDir: File = mockGitRepo.configurationRepositoryRoot / "techniques"

  private def categoryId(path: String) = TechniqueCategoryId.parse(path).getOrElse(throw new RuntimeException(path))

  private def categoryWriterCreate(name: String, description: String) = {
    writer.createCategory(UserTechniqueCategory.id, name, description).runNow.id
  }

  "Managing the categories of the technique editor" should {

    "create a sub-category, with a directory name derived from its name" in {
      val created = writer.createCategory(categoryId("ncf_techniques"), "My câtegory", "For my own use").runNow

      (TechniqueCategoryId.serialize(created.id) === "ncf_techniques/my_c_tegory") and
      ((techniquesDir / "ncf_techniques" / "my_c_tegory" / "category.xml").contentAsString ===
      """<xml>
        |  <name>My câtegory</name>
        |  <description>For my own use</description>
        |</xml>""".stripMargin) and
      (mockTechniques.techniqueRepo.getTechniqueCategory(created.id).runNow.name === "My câtegory")
    }

    "refuse to create a category whose directory already exists" in {
      writer.createCategory(categoryId("ncf_techniques"), "My câtegory", "Again").either.runNow must beLeft
    }

    "refuse to create a category with a name that has no usable character" in {
      writer.createCategory(categoryId("ncf_techniques"), "é", "").either.runNow must beLeft
    }

    "refuse to create a category outside of the technique editor categories" in {
      writer.createCategory(categoryId("systemSettings"), "My câtegory", "").either.runNow must beLeft
    }

    "refuse a directory name already used elsewhere in the library, whatever its case" in {
      // the library tells a move from a delete+add by directory name, and LDAP uses it as a category id
      val sub = writer.createCategory(categoryId("ncf_techniques"), "unique name", "").runNow.id
      (writer.createCategory(sub, "unique name", "").either.runNow must beLeft) and
      (writer.createCategory(categoryId("ncf_techniques"), "misc", "").either.runNow must beLeft) and
      (writer.createCategory(categoryId("ncf_techniques"), "MISC", "").either.runNow must beLeft)
    }

    "rename a category without moving its directory" in {
      val id      = categoryId("ncf_techniques/my_c_tegory")
      val updated = writer.updateCategory(id, Some("Renamed"), Some("New description")).runNow

      (updated.id === id) and
      ((techniquesDir / "ncf_techniques" / "my_c_tegory" / "category.xml").contentAsString must contain(
        "<name>Renamed</name>"
      )) and
      (mockTechniques.techniqueRepo.getTechniqueCategory(id).runNow.description === "New description")
    }

    "keep the name when the update does not mention it, and the description likewise" in {
      val id = categoryWriterCreate("keep what is not said", "a description")

      val keptName = writer.updateCategory(id, None, Some("another description")).runNow
      val keptDesc = writer.updateCategory(id, Some("Renamed again"), None).runNow

      (keptName.name === "keep what is not said") and
      (keptName.description === "another description") and
      (keptDesc.name === "Renamed again") and
      (keptDesc.description === "another description")
    }

    "remove a description when the update carries an empty one" in {
      val id = categoryWriterCreate("with a description", "to be removed")

      val updated = writer.updateCategory(id, None, Some("")).runNow

      (updated.description === "") and
      (mockTechniques.techniqueRepo.getTechniqueCategory(id).runNow.description === "")
    }

    "refuse an empty name, which the descriptor parser would replace by the directory name" in {
      val id = categoryWriterCreate("never nameless", "")

      (writer.updateCategory(id, Some(""), None).either.runNow must beLeft) and
      (writer.updateCategory(id, Some("   "), None).either.runNow must beLeft) and
      (writer.createCategory(UserTechniqueCategory.id, "  ", "").either.runNow must beLeft) and
      (mockTechniques.techniqueRepo.getTechniqueCategory(id).runNow.name === "never nameless")
    }

    "delete an empty category, and the empty sub-categories it holds" in {
      val parent = categoryId("ncf_techniques/my_c_tegory")
      val child  = writer.createCategory(parent, "child", "").runNow.id

      writer.deleteCategory(parent).runNow

      ((techniquesDir / "ncf_techniques" / "my_c_tegory").exists must beFalse) and
      (mockTechniques.techniqueRepo.getTechniqueCategory(parent).either.runNow must beLeft) and
      (mockTechniques.techniqueRepo.getTechniqueCategory(child).either.runNow must beLeft)
    }

    "refuse to delete a category that still holds a technique" in {
      // `ncf_techniques` holds the techniques of the test configuration repository
      writer.deleteCategory(categoryId("ncf_techniques")).either.runNow must beLeft
    }

    "refuse to delete a category whose directory holds something we do not know about" in {
      val id = writer.createCategory(categoryId("ncf_techniques"), "with_leftover", "").runNow.id
      (techniquesDir / "ncf_techniques" / "with_leftover" / "broken_technique").createDirectories()

      writer.deleteCategory(id).either.runNow must beLeft
    }

    "refuse to delete a category outside of the technique editor categories" in {
      writer.deleteCategory(categoryId("systemSettings/misc")).either.runNow must beLeft
    }
  }
}
