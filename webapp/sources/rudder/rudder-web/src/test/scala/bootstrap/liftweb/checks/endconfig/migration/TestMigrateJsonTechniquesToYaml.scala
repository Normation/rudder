/*
 *************************************************************************************
 * Copyright 2022 Normation SAS
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

package bootstrap.liftweb.checks.endconfig.migration

import better.files.File
import com.normation.inventory.domain.Version
import com.normation.rudder.MockGitConfigRepo
import com.normation.rudder.MockTechniques
import com.normation.rudder.ncf.BundleName
import com.normation.rudder.ncf.CompilationResult
import com.normation.rudder.ncf.EditorTechniqueCompilationResult
import com.normation.rudder.rest.RestTestSetUp
import com.normation.zio.*
import org.junit.runner.*
import org.specs2.matcher.ContentMatchers
import org.specs2.mutable.*
import org.specs2.runner.*

/**
 * A simple test class to check that the demo data file is up to date
 * with the schema (there may still be a desynchronization if both
 * demo-data, test data and test schema for UnboundID are not synchronized
 * with OpenLDAP Schema).
 */
@RunWith(classOf[JUnitRunner])
class TestMigrateJsonTechniquesToYaml extends Specification with ContentMatchers {
  sequential

  // technique repos & all
  val gitMock = new MockGitConfigRepo("", "configuration-repository-migrate-json-8_0")

  // a place to keep the metadata.xml of the migrated technique
  val technique_with_blocks_metadata_save:     File = gitMock.abstractRoot / "technique_with_blocks_metadata_save"
  val technique_with_parameters_metadata_save: File = gitMock.abstractRoot / "technique_with_parameters_metadata_save"

  val techMock: MockTechniques = MockTechniques(gitMock)

  val restTestSetUp = new RestTestSetUp

  val migration = new MigrateJsonTechniquesToYaml(
    techMock.techniqueWriter,
    techMock.stringUuidGen,
    techMock.techniqueRepo,
    techMock.compilationStatusService,
    gitMock.configurationRepositoryRoot.pathAsString
  )

  // format: off
    org.slf4j.LoggerFactory.getLogger("bootchecks").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.TRACE)
    org.slf4j.LoggerFactory.getLogger("techniques").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.TRACE)
  // format: on

  "There is only three techniques to migrate" >> {
    val res = migration.getAllTechniqueFiles(gitMock.configurationRepositoryRoot / "techniques").runNow
    res.size === 3
    res.map(_.parent.parent.name) must containTheSameElementsAs(
      List("technique_with_blocks", "technique_with_error", "technique_with_parameters")
    )
  }

  "After migration, the config repo matches what is expected" >> {
    /*
     * What is expected:
     * - no techniques are changed apart the two with json under ncf_techniques (so test_import_export_archive is not changed)
     *
     * - for technique_with_blocks
     *   Migration is OK and so we went to regeneration of everything.
     *   The YAML file is correct (ie, we check that migration works well here)
     *   The content of generated files is "regenerated" because it's what our false compiler does.
     *
     * - for technique_with_error
     *   Migration of json to yaml was done properly
     *   Then compiler does an error. So generated files are deleted, but not regenerated.
     */

    // save metadata
    technique_with_blocks_metadata_save.write(
      (gitMock.configurationRepositoryRoot / "techniques/ncf_techniques/technique_with_blocks/1.0/metadata.xml").contentAsString
    )
    technique_with_parameters_metadata_save.write(
      (gitMock.configurationRepositoryRoot / "techniques/ncf_techniques/technique_with_parameters/1.0/metadata.xml").contentAsString
    )

    migration.checks()

    val resultDir    = gitMock.configurationRepositoryRoot / "techniques"
    val referenceDir = gitMock.configurationRepositoryRoot / "post-migration"

    resultDir.toJava must haveSameFilesAs(referenceDir.toJava)
  }

  "Compilation status should be error" in {
    techMock.compilationStatusService.get().runNow must containTheSameElementsAs(
      List(
        EditorTechniqueCompilationResult(
          BundleName("test_import_export_archive"),
          new Version("1.0"),
          "test import/export archive",
          CompilationResult.Success
        ),
        EditorTechniqueCompilationResult(
          BundleName("technique_with_parameters"),
          new Version("1.0"),
          "Technique with parameters",
          CompilationResult.Success
        ),
        EditorTechniqueCompilationResult(
          BundleName("technique_with_error"),
          new Version("1.0"),
          "technique with compile error",
          CompilationResult.Error("error stderr")
        ),
        EditorTechniqueCompilationResult(
          BundleName("technique_with_blocks"),
          new Version("1.0"),
          "technique with blocks",
          CompilationResult.Success
        )
      )
    )
  }

}
