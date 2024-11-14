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
import com.normation.errors.IOResult
import com.normation.errors.RudderError
import com.normation.errors.effectUioUnit
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.db.DB
import com.normation.rudder.git.GitCommitId
import com.normation.rudder.git.GitItemRepository
import com.normation.rudder.git.GitRepositoryProvider
import com.normation.rudder.git.GitRepositoryProviderImpl
import com.normation.rudder.ncf.EditorTechnique
import com.normation.rudder.ncf.TechniqueCompilationOutput
import com.normation.rudder.ncf.TechniqueCompiler
import com.normation.rudder.ncf.TechniqueCompilerApp
import com.normation.rudder.repository.GitModificationRepository
import com.normation.rudder.repository.xml.RudderPrettyPrinter
import com.normation.rudder.repository.xml.TechniqueArchiver
import com.normation.rudder.repository.xml.TechniqueArchiverImpl
import com.normation.rudder.services.user.TrivialPersonIdentService
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import zio.Chunk
import zio.System
import zio.ULayer
import zio.ZIO
import zio.ZLayer
import zio.syntax.ToZio
import zio.test.*
import zio.test.Assertion.*

case class TempDir(path: File)

object TempDir {

  /**
   * Add a switch to be able to see tmp files (not clean temps) with
   * -Dtests.clean.tmp=false
   */
  val layer: ZLayer[Any, Nothing, TempDir] = ZLayer.scoped {
    for {
      keepFiles <- System.property("tests.clean.tmp").map(_.contains("false")).orDie
      tempDir   <- ZIO
                     .attemptBlockingIO(File.newTemporaryDirectory(prefix = "test-jgit-"))
                     .withFinalizer { dir =>
                       (ZIO.logInfo(s"Deleting directory ${dir.path}") *>
                       ZIO.attemptBlocking(dir.delete(swallowIOExceptions = true)))
                         .unless(keepFiles)
                         .orDie
                     }
                     .orDie
    } yield TempDir(tempDir)
  }
}

object JGitRepositoryTest extends ZIOSpecDefault {

  def prettyPrinter: RudderPrettyPrinter = new RudderPrettyPrinter(Int.MaxValue, 2)

  object StubGitModificationRepository {
    val layer: ULayer[GitModificationRepository] = ZLayer.succeed {
      new GitModificationRepository {
        override def getCommits(modificationId: ModificationId): IOResult[Option[GitCommitId]] = None.succeed

        override def addCommit(commit: GitCommitId, modId: ModificationId): IOResult[DB.GitCommitJoin] =
          DB.GitCommitJoin(commit, modId).succeed
      }
    }
  }

  val techniqueParserLayer: ULayer[TechniqueParser] = ZLayer.succeed {
    val varParser = new VariableSpecParser
    new TechniqueParser(varParser, new SectionSpecParser(varParser), new SystemVariableSpecServiceImpl())
  }

  object StubbedTechniqueCompiler {
    val layer: ULayer[TechniqueCompiler] = ZLayer.succeed {
      new TechniqueCompiler {
        override def compileTechnique(technique: EditorTechnique): IOResult[TechniqueCompilationOutput] = {
          TechniqueCompilationOutput(TechniqueCompilerApp.Rudderc, 0, Chunk.empty, "", "", "").succeed
        }

        override def getCompilationOutputFile(technique: EditorTechnique): File = File("compilation-config.yml")

        override def getCompilationConfigFile(technique: EditorTechnique): File = File("compilation-output.yml")
      }
    }
  }

  val gitRepositoryProviderLayer: ZLayer[TempDir, RudderError, GitRepositoryProviderImpl] = ZLayer {
    for {
      gitRoot <- ZIO.service[TempDir]
      result  <- GitRepositoryProviderImpl.make(gitRoot.path.pathAsString)
    } yield result
  }

  // listing files at a commit is complicated
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
          res    <- ZIO.scoped[Any](treeWalkM.flatMap { treeWalk =>
                      treeWalk.setRecursive(true) // ok, can't throw exception

                      IOResult.attempt(treeWalk.addTree(tree)) *>
                      ZIO.loop(treeWalk)(_.next, identity)(x => IOResult.attempt(x.getPathString))
                    })
        } yield res
      })
    )
  }

  val category = TechniqueCategoryMetadata("My new category", "A new category", isSystem = false)
  val catPath  = List("systemSettings", "myNewCategory")

  val modId = ModificationId("add-technique-cat")

  case class GroupOwner(value: String)

  // for test, we use as a group owner whatever git root directory has
  def currentUserName(repo: GitRepositoryProvider): GroupOwner = GroupOwner(repo.rootDirectory.groupName)

  val currentUserNameLayer: ZLayer[GitRepositoryProvider, Nothing, GroupOwner] = ZLayer {
    for {
      repo <- ZIO.service[GitRepositoryProvider]
    } yield currentUserName(repo)
  }

  val techniqueArchiverLayer: ZLayer[
    GroupOwner & TrivialPersonIdentService & GitRepositoryProvider & TechniqueCompiler & TechniqueParser & GitModificationRepository,
    Nothing,
    TechniqueArchiverImpl
  ] = ZLayer {
    for {
      techniqueParse        <- ZIO.service[TechniqueParser]
      techniqueCompiler     <- ZIO.service[TechniqueCompiler]
      gitRepositoryProvider <- ZIO.service[GitRepositoryProvider]
      personIdentservice    <- ZIO.service[TrivialPersonIdentService]
      groupOwner            <- ZIO.service[GroupOwner]
      techniqueArchive       = new TechniqueArchiverImpl(
                                 gitRepo = gitRepositoryProvider,
                                 xmlPrettyPrinter = prettyPrinter,
                                 personIdentservice = personIdentservice,
                                 techniqueParser = techniqueParse,
                                 techniqueCompiler = techniqueCompiler,
                                 groupOwner = groupOwner.value
                               )
    } yield techniqueArchive
  }

  val gitItemRepositoryLayer: ZLayer[GitModificationRepository & GitRepositoryProvider, Nothing, GitItemRepository] = {
    ZLayer {
      for {
        repository <- ZIO.service[GitRepositoryProvider]
      } yield new GitItemRepository(gitRepo = repository, relativePath = "")
    }
  }

  val spec: Spec[Any, RudderError] = suite("The test lib")(
    suite("GitItemRepository")(
      // to assess the usefulness of semaphore, you can remove `gitRepo.semaphore.withPermit`
      // in `commitAddFile` to check that you get the JGitInternalException.
      // More advanced tests may be needed to handle more complex cases of concurrent access,
      // see: https://issues.rudder.io/issues/19910
      test("should not throw JGitInternalError on concurrent write") {

        def addFileToRepositoryAndCommit(i: Int)(gitRoot: TempDir, archive: GitItemRepository) = for {
          name <- zio.Random.nextString(8).map(s => i.toString + "_" + s)
          file  = gitRoot.path / name
          _    <- IOResult.attempt(file.write("something in " + name))
          actor = new PersonIdent("test", "test@test.com")
          _    <- archive.commitAddFile(actor, name, "add " + name)
        } yield name

        for {
          gitRoot               <- ZIO.service[TempDir]
          gitRepositoryProvider <- ZIO.service[GitRepositoryProvider]
          archive               <- ZIO.service[GitItemRepository]
          files                 <- ZIO.foreachPar(1 to 50)(i => addFileToRepositoryAndCommit(i)(gitRoot, archive)).withParallelism(16)
          created               <- readElementsAt(gitRepositoryProvider.db, "refs/heads/master")
        } yield assert(created)(hasSameElements(files))

      }.provide(TempDir.layer, StubGitModificationRepository.layer, gitRepositoryProviderLayer, gitItemRepositoryLayer)
    ),
    suite("TechniqueArchiver.saveTechniqueCategory")(
      test("should create a new file and commit if the category does not exist") {
        for {
          gitRepositoryProvider <- ZIO.service[GitRepositoryProvider]
          techniqueArchive      <- ZIO.service[TechniqueArchiver]
          _                     <- techniqueArchive
                                     .saveTechniqueCategory(
                                       catPath,
                                       category,
                                       modId,
                                       EventActor("test"),
                                       s"test: commit add category ${catPath.mkString("/")}"
                                     )
          catFile                = gitRepositoryProvider.rootDirectory / "techniques" / "systemSettings" / "myNewCategory" / "category.xml"
          xml                    = catFile.contentAsString
          lastCommitMsg          = gitRepositoryProvider.git.log().setMaxCount(1).call().iterator().next().getFullMessage
        } yield {
          // note: no <system>false</system> ; it's only written when true
          assert(xml)(
            equalTo(
              """<xml>
                |  <name>My new category</name>
                |  <description>A new category</description>
                |</xml>""".stripMargin
            )
          ) &&
          assert(lastCommitMsg)(equalTo("test: commit add category systemSettings/myNewCategory"))
        }
      },
      test("should do nothing when the category already exists") {
        for {
          gitRepositoryProvider <- ZIO.service[GitRepositoryProvider]
          techniqueArchive      <- ZIO.service[TechniqueArchiver]
          _                     <- techniqueArchive
                                     .saveTechniqueCategory(
                                       catPath,
                                       category,
                                       modId,
                                       EventActor("test"),
                                       s"test: commit add category ${catPath.mkString("/")}"
                                     )
          _                     <- techniqueArchive.saveTechniqueCategory(catPath, category, modId, EventActor("test"), s"test: commit again")
          lastCommitMsg          = gitRepositoryProvider.git.log().setMaxCount(1).call().iterator().next().getFullMessage
        } yield assert(lastCommitMsg)(equalTo("test: commit add category systemSettings/myNewCategory"))
      }
    ).provide(
      TempDir.layer,
      StubGitModificationRepository.layer,
      techniqueParserLayer,
      StubbedTechniqueCompiler.layer,
      gitRepositoryProviderLayer,
      ZLayer.succeed(new TrivialPersonIdentService()),
      currentUserNameLayer,
      techniqueArchiverLayer
    )
  )

}
