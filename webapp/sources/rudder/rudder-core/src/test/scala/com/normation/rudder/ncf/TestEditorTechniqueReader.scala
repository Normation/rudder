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

import cats.syntax.either.*
import com.normation.errors.IOResult
import com.normation.errors.Unexpected
import com.normation.inventory.domain.Version
import com.normation.rudder.MockGitConfigRepo
import com.normation.rudder.ncf.ParameterType.BasicParameterTypeService
import org.junit.runner.RunWith
import zio.Scope
import zio.syntax.*
import zio.test.*
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class TestEditorTechniqueReader extends ZIOSpecDefault {
  private val mockGitRepo = MockGitConfigRepo()

  private val baseTechnique = EditorTechnique(
    BundleName(""),
    Version(""),
    "",
    "",
    List.empty,
    "",
    "",
    List.empty,
    List.empty,
    Map.empty,
    None
  )

  private val invalidIOId      = "invalid_io"
  private val invalidParsingId = "invalid_parsing"

  private val fakeEditorTechniqueYamlReader: EditorTechniqueYamlReader = new EditorTechniqueYamlReader {
    override def read(file: EditorTechniquePath): IOResult[Either[EditorTechniqueParsingError, EditorTechnique]] = {
      file.id.value match {
        case `invalidParsingId` =>
          EditorTechniqueParsingError(file, "error").asLeft.succeed
        case `invalidIOId`      =>
          Unexpected("invalid_io").fail
        case _                  =>
          baseTechnique
            .copy(
              id = file.id,
              version = file.version,
              name = file.id.value,
              category = file.category.getOrElse("")
            )
            .asRight
            .succeed
      }
    }
  }
  private val typeParamService = new BasicParameterTypeService

  // SUT
  private val techniqueReader = EditorTechniqueReaderImpl(
    null,
    null,
    mockGitRepo.gitRepo,
    null,
    null,
    "UTF-8",
    "test",
    fakeEditorTechniqueYamlReader,
    typeParamService,
    "no-cmd",
    "no-methods",
    "no-methods"
  )

  override def spec: Spec[TestEnvironment & Scope, Any] = {
    suite("EditorTechniqueReader")(
      test("read success techniques")(
        techniqueReader.readTechniquesMetadataFile.map(t => {
          assertTrue(t.techniques.length == 4, t.allErrors.isEmpty)
        })
      ) @@ TestAspect.before(initMethods),
      test("read parsing error")(
        techniqueReader.readTechniquesMetadataFile.map(t => {
          assertTrue(t.parsingErrors.length == 1, t.allErrors.length == 1, t.techniques.length == 4)
        })
      ) @@ TestAspect.before(initInvalidYaml),
      test("read I/O error")(
        techniqueReader.readTechniquesMetadataFile.map(t => {
          assertTrue(t.errors.length == 1, t.parsingErrors.length == 1, t.allErrors.length == 2, t.techniques.length == 4)
        })
      ) @@ TestAspect.before(initInvalidFileIO)
    ) @@ TestAspect.sequential
  }

  // Methods file is needed to prevent "update"
  private def initMethods = {
    val methodsFile = mockGitRepo.gitRepo.rootDirectory / "ncf" / "generic_methods.json"
    IOResult.attempt {
      methodsFile.createFileIfNotExists(createParents = true)
      methodsFile.write("{}")
    }
  }

  // Create technique file
  private def initInvalidYaml = {
    val techniqueFile = techniqueDir(invalidParsingId) / "1.0" / "technique.yml"
    IOResult.attempt {
      techniqueFile.createFileIfNotExists(createParents = true)
      techniqueFile.write("""
                            |id: "invalid_parsing"
                            |name: "Invalid parsing techniques"
                            |""".stripMargin)
    }
  }

  // Create technique file
  private def initInvalidFileIO = {
    val techniqueFile = techniqueDir(invalidIOId) / "1.0" / "technique.yml"
    IOResult.attempt {
      techniqueFile.createFileIfNotExists(createParents = true)
    }
  }

  private def techniqueDir(techniqueId: String) =
    mockGitRepo.gitRepo.rootDirectory / "techniques" / "ncf_techniques" / techniqueId
}
