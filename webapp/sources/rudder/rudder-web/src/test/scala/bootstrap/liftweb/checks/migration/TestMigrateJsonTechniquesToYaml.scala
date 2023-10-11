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
import com.normation.errors.IOResult
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.MockGitConfigRepo
import com.normation.rudder.MockTechniques
import com.normation.rudder.db.DB
import com.normation.rudder.git.GitCommitId
import com.normation.rudder.ncf.DeleteEditorTechnique
import com.normation.rudder.ncf.EditorTechnique
import com.normation.rudder.ncf.RuddercOptions
import com.normation.rudder.ncf.RuddercResult
import com.normation.rudder.ncf.RuddercService
import com.normation.rudder.ncf.TechniqueCompilationOutput
import com.normation.rudder.ncf.TechniqueCompiler
import com.normation.rudder.ncf.TechniqueCompilerApp
import com.normation.rudder.ncf.TechniqueCompilerWithFallback
import com.normation.rudder.ncf.TechniqueWriterImpl
import com.normation.rudder.repository.GitModificationRepository
import com.normation.rudder.repository.xml.RudderPrettyPrinter
import com.normation.rudder.repository.xml.TechniqueArchiverImpl
import com.normation.rudder.repository.xml.TechniqueFiles
import com.normation.rudder.services.user.TrivialPersonIdentService
import com.normation.zio._
import org.junit.runner._
import org.specs2.matcher.ContentMatchers
import org.specs2.mutable._
import org.specs2.runner._
import zio._
import zio.syntax._

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
  val technique_with_blocks_metadata_save = gitMock.abstractRoot / "technique_with_blocks_metadata_save"

  val techMock = MockTechniques(gitMock)

  val xmlPrettyPrinter = new RudderPrettyPrinter(Int.MaxValue, 2)

  val deleteEditorTechnique = new DeleteEditorTechnique {
    override def deleteTechnique(
        techniqueName:    String,
        techniqueVersion: String,
        deleteDirective:  Boolean,
        modId:            ModificationId,
        committer:        EventActor
    ): IOResult[Unit] = ZIO.unit
  }

  val rudderc = new RuddercService {
    override def compile(techniqueDir: File, options: RuddercOptions): IOResult[RuddercResult] = {
      // test implementation that just create the files with the technique name in them safe for
      if (techniqueDir.parent.name == "technique_with_error") {
        RuddercResult.Fail(42, "error", "", "error stderr").succeed
      } else {
        // replace content to be sure we get there
        IOResult.attempt {
          // we need to keep metadata.xml, it's parsed in technique writer for technique registration
          val metadata = (techniqueDir / TechniqueFiles.Generated.metadata)
          metadata.write(
            technique_with_blocks_metadata_save
              .contentAsString()
              .replace("<DESCRIPTION></DESCRIPTION>", "<DESCRIPTION>regenerated</DESCRIPTION>")
          )
          // we can replace other
          (TechniqueFiles.Generated.dsc ++ TechniqueFiles.Generated.cfengineRudderc).foreach(n =>
            (techniqueDir / n).write("regenerated")
          )
          RuddercResult.Ok("", "", "")
        }
      }
    }
  }

  val webappCompiler = new TechniqueCompiler {
    override def compileTechnique(technique: EditorTechnique): IOResult[TechniqueCompilationOutput] = {
      // this should not be called because we force rudderc via compilation config, so here we failed loudly if exec
      throw new IllegalArgumentException(s"Test is calling fallback compiler for technique: ${technique.path}")
    }

    override def getCompilationOutputFile(technique: EditorTechnique): File = null

    override def getCompilationConfigFile(technique: EditorTechnique): File = null
  }

  val techniqueCompiler = new TechniqueCompilerWithFallback(
    webappCompiler,
    rudderc,
    TechniqueCompilerApp.Rudderc,
    _.path,
    gitMock.configurationRepositoryRoot.pathAsString
  )

  val techniqueArchiver = new TechniqueArchiverImpl(
    gitMock.gitRepo,
    xmlPrettyPrinter,
    new GitModificationRepository {
      override def getCommits(modificationId: ModificationId):            IOResult[Option[GitCommitId]] = None.succeed
      override def addCommit(commit: GitCommitId, modId: ModificationId): IOResult[DB.GitCommitJoin]    =
        DB.GitCommitJoin(commit, modId).succeed
    },
    new TrivialPersonIdentService(),
    techMock.techniqueParser,
    techniqueCompiler,
    "root"
  )
  val techniqueWriter   = new TechniqueWriterImpl(
    techniqueArchiver,
    techMock.techniqueRepo,
    deleteEditorTechnique,
    techniqueCompiler,
    gitMock.configurationRepositoryRoot.pathAsString
  )

  val migration = new MigrateJsonTechniquesToYaml(
    techniqueWriter,
    techMock.stringUuidGen,
    techMock.techniqueRepo,
    gitMock.configurationRepositoryRoot.pathAsString
  )

  // format: off
    org.slf4j.LoggerFactory.getLogger("bootchecks").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.TRACE)
    org.slf4j.LoggerFactory.getLogger("techniques").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.TRACE)
  // format: on

  "There is only two techniques to migrate" >> {
    val res = migration.getAllTechniqueFiles(gitMock.configurationRepositoryRoot / "techniques").runNow
    (res.size === 2) and
    (res.map(_.parent.parent.name) must containTheSameElementsAs(List("technique_with_blocks", "technique_with_error")))
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

    migration.checks()

    val resultDir    = gitMock.configurationRepositoryRoot / "techniques"
    val referenceDir = gitMock.configurationRepositoryRoot / "post-migration"

    resultDir.toJava must haveSameFilesAs(referenceDir.toJava)
  }

}
