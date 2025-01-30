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

package bootstrap.liftweb.checks.migration

import better.files.File
import com.normation.errors.*
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.Version
import com.normation.rudder.MockGitConfigRepo
import com.normation.rudder.MockTechniques
import com.normation.rudder.db.DB
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.git.GitCommitId
import com.normation.rudder.hooks.CmdResult
import com.normation.rudder.ncf.BundleName
import com.normation.rudder.ncf.CompilationResult
import com.normation.rudder.ncf.DeleteEditorTechnique
import com.normation.rudder.ncf.EditorTechniqueCompilationResult
import com.normation.rudder.ncf.EditorTechniqueReader
import com.normation.rudder.ncf.EditorTechniqueReaderImpl
import com.normation.rudder.ncf.GenericMethod
import com.normation.rudder.ncf.GitResourceFileService
import com.normation.rudder.ncf.ReadEditorTechniqueActiveStatus
import com.normation.rudder.ncf.ReadEditorTechniqueCompilationResult
import com.normation.rudder.ncf.ResourceFile
import com.normation.rudder.ncf.ResourceFileState
import com.normation.rudder.ncf.RuddercOptions
import com.normation.rudder.ncf.RuddercResult
import com.normation.rudder.ncf.RuddercService
import com.normation.rudder.ncf.RuddercTechniqueCompiler
import com.normation.rudder.ncf.TechniqueActiveStatus
import com.normation.rudder.ncf.TechniqueCompilationErrorsActorSync
import com.normation.rudder.ncf.TechniqueCompilationStatusService
import com.normation.rudder.ncf.TechniqueWriterImpl
import com.normation.rudder.ncf.yaml.YamlTechniqueSerializer
import com.normation.rudder.repository.GitModificationRepository
import com.normation.rudder.repository.xml.RudderPrettyPrinter
import com.normation.rudder.repository.xml.TechniqueArchiverImpl
import com.normation.rudder.repository.xml.TechniqueFiles
import com.normation.rudder.rest.RestTestSetUp
import com.normation.rudder.services.user.TrivialPersonIdentService
import com.normation.zio.*
import org.junit.runner.*
import org.specs2.matcher.ContentMatchers
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.*
import zio.syntax.*

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

  val xmlPrettyPrinter = new RudderPrettyPrinter(Int.MaxValue, 2)

  val deleteEditorTechnique: DeleteEditorTechnique = new DeleteEditorTechnique {
    override def deleteTechnique(
        techniqueName:    String,
        techniqueVersion: String,
        deleteDirective:  Boolean,
        modId:            ModificationId,
        committer:        QueryContext
    ): IOResult[Unit] = ZIO.unit
  }

  val rudderc: RuddercService = new RuddercService {
    override def compile(techniqueDir: File, options: RuddercOptions): IOResult[RuddercResult] = {
      // test implementation that just create the files with the technique name in them safe for
      if (techniqueDir.parent.name == "technique_with_error") {
        RuddercResult.Fail(42, Chunk(ResourceFile("foo", ResourceFileState.Deleted)), "error", "", "error stderr").succeed
      } else {
        // replace content to be sure we get there
        IOResult.attempt {
          println("technique " + techniqueDir)
          // we need to keep metadata.xml, it's parsed in technique writer for technique registration
          val metadata        = (techniqueDir / TechniqueFiles.Generated.metadata)
          val metadataContent = techniqueDir.parent.name match {
            case "technique_with_blocks"     =>
              technique_with_blocks_metadata_save.contentAsString()
            case "technique_with_parameters" =>
              technique_with_parameters_metadata_save.contentAsString()
            // add other if needed here, it will throw an exception if you forget
          }
          metadata.write(metadataContent.replace("<DESCRIPTION></DESCRIPTION>", "<DESCRIPTION>regenerated</DESCRIPTION>"))
          // we can replace other
          (TechniqueFiles.Generated.dsc ++ TechniqueFiles.Generated.cfengineRudderc).foreach { n =>
            (techniqueDir / n).write("regenerated")
          }
          RuddercResult.Ok(Chunk.empty, "", "", "")
        }
      }
    }
  }

  val techniqueCompiler = new RuddercTechniqueCompiler(
    rudderc,
    _.path,
    gitMock.configurationRepositoryRoot.pathAsString
  )

  val techniqueArchiver = new TechniqueArchiverImpl(
    gitMock.gitRepo,
    xmlPrettyPrinter,
    new GitModificationRepository {
      override def getCommits(modificationId: ModificationId): IOResult[Option[GitCommitId]] = None.succeed
      override def addCommit(commit: GitCommitId, modId: ModificationId): IOResult[DB.GitCommitJoin] =
        DB.GitCommitJoin(commit, modId).succeed
    },
    new TrivialPersonIdentService(),
    techMock.techniqueParser,
    techniqueCompiler,
    "root"
  )

  val editorTechniqueReader:        EditorTechniqueReader                = new EditorTechniqueReaderImpl(
    null,
    null,
    gitMock.gitRepo,
    null,
    null,
    "UTF-8",
    "test",
    new YamlTechniqueSerializer(new GitResourceFileService(gitMock.gitRepo)),
    null,
    "no-cmd",
    "no-methods",
    "no-methods"
  ) {
    override def getMethodsMetadata:        IOResult[Map[BundleName, GenericMethod]] = Map.empty.succeed
    override def updateMethodsMetadataFile: IOResult[CmdResult]                      = Inconsistency("this should not be called").fail
  }
  val compilationStatusService:     ReadEditorTechniqueCompilationResult = new TechniqueCompilationStatusService(
    editorTechniqueReader,
    techniqueCompiler
  )
  val techniqueActiveStatusService: ReadEditorTechniqueActiveStatus      = new ReadEditorTechniqueActiveStatus {
    override def getActiveStatus(id: BundleName): IOResult[Option[TechniqueActiveStatus]] = None.succeed
    override def getActiveStatuses(): IOResult[Map[BundleName, TechniqueActiveStatus]] = Map.empty.succeed
  }
  val compilationCache:             TechniqueCompilationErrorsActorSync  = {
    TechniqueCompilationErrorsActorSync
      .make(restTestSetUp.asyncDeploymentAgent, compilationStatusService, techniqueActiveStatusService)
      .runNow
  }
  val techniqueWriter = new TechniqueWriterImpl(
    techniqueArchiver,
    techMock.techniqueRepo,
    deleteEditorTechnique,
    techniqueCompiler,
    compilationCache,
    gitMock.configurationRepositoryRoot.pathAsString
  )

  val migration = new MigrateJsonTechniquesToYaml(
    techniqueWriter,
    techMock.stringUuidGen,
    techMock.techniqueRepo,
    compilationStatusService,
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
    compilationStatusService.get().runNow must containTheSameElementsAs(
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
