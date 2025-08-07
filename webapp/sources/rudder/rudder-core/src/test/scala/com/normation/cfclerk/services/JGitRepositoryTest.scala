/*
 *************************************************************************************
 * Copyright 2021 Normation SAS
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

package com.normation.cfclerk.services

import better.files.File
import com.normation.cfclerk.domain.TechniqueCategoryMetadata
import com.normation.cfclerk.services.impl.SystemVariableSpecServiceImpl
import com.normation.cfclerk.xmlparsers.SectionSpecParser
import com.normation.cfclerk.xmlparsers.TechniqueParser
import com.normation.cfclerk.xmlparsers.VariableSpecParser
import com.normation.errors
import com.normation.errors.Inconsistency
import com.normation.errors.IOResult
import com.normation.errors.effectUioUnit
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.db.DB
import com.normation.rudder.git.GitCommitId
import com.normation.rudder.git.GitConfigItemRepository
import com.normation.rudder.git.GitRepositoryProvider
import com.normation.rudder.git.GitRepositoryProviderImpl
import com.normation.rudder.ncf.EditorTechnique
import com.normation.rudder.ncf.TechniqueCompilationOutput
import com.normation.rudder.ncf.TechniqueCompiler
import com.normation.rudder.ncf.TechniqueCompilerApp
import com.normation.rudder.repository.GitModificationRepository
import com.normation.rudder.repository.xml.RudderPrettyPrinter
import com.normation.rudder.repository.xml.TechniqueArchiverImpl
import com.normation.rudder.repository.xml.XmlArchiverUtils
import com.normation.rudder.services.user.TrivialPersonIdentService
import com.normation.zio.*
import net.liftweb.common.Loggable
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.AfterAll
import scala.annotation.nowarn
import scala.util.Random
import zio.{System as _, *}
import zio.syntax.*

/**
 * Details of tests executed in each instances of
 * the test.
 * To see values for gitRoot, ptLib, etc, see at the end
 * of that file.
 */
@nowarn("msg=a type was inferred to be `\\w+`; this may indicate a programming error.")
@RunWith(classOf[JUnitRunner])
class JGitRepositoryTest extends Specification with Loggable with AfterAll {

  val gitRoot: File = File("/tmp/test-jgit-" + DateTime.now(DateTimeZone.UTC).toString())

  // Set sequential execution
  sequential

  /**
   * Add a switch to be able to see tmp files (not clean temps) with
   * -Dtests.clean.tmp=false
   */
  override def afterAll(): Unit = {
    if (java.lang.System.getProperty("tests.clean.tmp") != "false") {
      logger.info("Deleting directory " + gitRoot.pathAsString)
      FileUtils.deleteDirectory(gitRoot.toJava)
    }
  }

  gitRoot.createDirectories()

  val repo:            GitRepositoryProviderImpl = GitRepositoryProviderImpl.make(gitRoot.pathAsString).runNow
  val prettyPrinter:   RudderPrettyPrinter       = new RudderPrettyPrinter(Int.MaxValue, 2)
  val modRepo:         GitModificationRepository = new GitModificationRepository {
    override def getCommits(modificationId: ModificationId): IOResult[Option[GitCommitId]] = None.succeed
    override def addCommit(commit: GitCommitId, modId: ModificationId): IOResult[DB.GitCommitJoin] =
      DB.GitCommitJoin(commit, modId).succeed
  }
  val personIdent:     TrivialPersonIdentService = new TrivialPersonIdentService()
  val techniqueParser: TechniqueParser           = {
    val varParser = new VariableSpecParser
    new TechniqueParser(varParser, new SectionSpecParser(varParser), new SystemVariableSpecServiceImpl())
  }
  val techniqueCompiler = new TechniqueCompiler {
    override def compileTechnique(technique: EditorTechnique): IOResult[TechniqueCompilationOutput] = {
      TechniqueCompilationOutput(TechniqueCompilerApp.Rudderc, 0, Chunk.empty, "", "", "").succeed
    }

    override def getCompilationOutputFile(technique: EditorTechnique): File = File("compilation-config.yml")

    override def getCompilationConfigFile(technique: EditorTechnique): File = File("compilation-output.yml")
  }

  // for test, we use as a group owner whatever git root directory has
  val currentUserName: String = repo.rootDirectory.groupName

  val archive: GitConfigItemRepository & XmlArchiverUtils = new GitConfigItemRepository with XmlArchiverUtils {
    override val gitRepo:      GitRepositoryProvider = repo
    override def relativePath: String                = ""
    override def xmlPrettyPrinter = prettyPrinter
    override def encoding:                  String                    = "UTF-8"
    override def gitModificationRepository: GitModificationRepository = modRepo

    override def groupOwner: String = currentUserName
  }

  val techniqueArchive: TechniqueArchiverImpl =
    new TechniqueArchiverImpl(repo, prettyPrinter, modRepo, personIdent, techniqueParser, techniqueCompiler, currentUserName)

  // listing files at a commit is complicated

  import org.eclipse.jgit.treewalk.TreeWalk

  def readElementsAt(repository: Repository, commit: String): ZIO[Any, errors.SystemError, List[String]] = {
    val ref = repository.findRef(commit)

    // a RevWalk allows to walk over commits based on some filtering that is defined
    val walkM     = ZIO.acquireRelease(IOResult.attempt(new RevWalk(repository)))(x => effectUioUnit(x.close()))
    val treeWalkM = ZIO.acquireRelease(IOResult.attempt(new TreeWalk(repository)))(x => effectUioUnit(x.close()))

    ZIO.scoped[Any](
      walkM.flatMap(walk => {
        for {
          commit <- IOResult.attempt(walk.parseCommit(ref.getObjectId))
          tree   <- IOResult.attempt(commit.getTree)
          res    <- ZIO.scoped(treeWalkM.flatMap { treeWalk =>
                      treeWalk.setRecursive(true) // ok, can't throw exception

                      IOResult.attempt(treeWalk.addTree(tree)) *>
                      ZIO.loop(treeWalk)(_.next, identity)(x => IOResult.attempt(x.getPathString))
                    })
        } yield res
      })
    )
  }

  "The test lib" should {
    "not throw JGitInternalError on concurrent write" in {

      // to assess the usefulness of semaphore, you can remove `gitRepo.semaphore.withPermit`
      // in `commitAddFile` to check that you get the JGitInternalException.
      // More advanced tests may be needed to handle more complex cases of concurrent access,
      // see: https://issues.rudder.io/issues/19910

      val actor = new PersonIdent("test", "test@test.com")

      def getName(length: Int) = {
        if (length < 1) Inconsistency("Length must be positive").fail
        else {
          IOResult.attempt("")(Random.alphanumeric.take(length).toList.mkString(""))
        }
      }
      def add(i: Int)          = (for {
        name <- getName(8).map(s => i.toString + "_" + s)
        file  = gitRoot / name
        f    <- IOResult.attempt(file.write("something in " + name))
        _    <- archive.commitAddFileWithModId(ModificationId(name), actor, name, "add " + name)
      } yield (name))

      logger.debug(s"Commiting files in: " + gitRoot.pathAsString)
      val files = ZIO.foreachPar(1 to 50)(i => add(i)).withParallelism(16).runNow

      val created = readElementsAt(repo.db, "refs/heads/master").runNow
      created must containTheSameElementsAs(files)

    }

    "save a category" should {

      val category = TechniqueCategoryMetadata("My new category", "A new category", isSystem = false)
      val catPath  = List("systemSettings", "myNewCategory")

      val modId = new ModificationId("add-technique-cat")

      "create a new file and commit if the category does not exist" in {

        techniqueArchive
          .saveTechniqueCategory(
            catPath,
            category,
            modId,
            EventActor("test"),
            s"test: commit add category ${catPath.mkString("/")}"
          )
          .runNow

        val catFile = repo.rootDirectory / "techniques" / "systemSettings" / "myNewCategory" / "category.xml"

        val xml = catFile.contentAsString

        val lastCommitMsg = repo.git.log().setMaxCount(1).call().iterator().next().getFullMessage

        // note: no <system>false</system> ; it's only written when true
        (xml ===
        """<xml>
          |  <name>My new category</name>
          |  <description>A new category</description>
          |</xml>""".stripMargin) and (
          lastCommitMsg === "test: commit add category systemSettings/myNewCategory"
        )

      }

      "does nothing when the category already exsits" in {
        techniqueArchive.saveTechniqueCategory(catPath, category, modId, EventActor("test"), s"test: commit again").runNow
        val lastCommitMsg = repo.git.log().setMaxCount(1).call().iterator().next().getFullMessage

        // last commit must be the old one
        lastCommitMsg === "test: commit add category systemSettings/myNewCategory"

      }
    }

  }

}
