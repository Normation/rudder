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
import com.normation.cfclerk.services.impl.GitRepositoryProviderImpl
import com.normation.errors.IOResult
import com.normation.errors.Inconsistency
import com.normation.errors.effectUioUnit
import com.normation.eventlog.ModificationId
import com.normation.rudder.db.DB
import com.normation.rudder.repository.GitCommitId
import com.normation.rudder.repository.GitModificationRepository
import com.normation.rudder.repository.xml.GitArchiverUtils
import com.normation.rudder.repository.xml.RudderPrettyPrinter
import org.apache.commons.io.FileUtils
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.AfterAll
import net.liftweb.common.Loggable
import org.joda.time.DateTime
import com.normation.zio._
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import zio.syntax._
import zio._

import scala.util.Random

/**
 * Details of tests executed in each instances of
 * the test.
 * To see values for gitRoot, ptLib, etc, see at the end
 * of that file.
 */
@RunWith(classOf[JUnitRunner])
class JGitRepositoryTest extends Specification with Loggable with AfterAll {

  val gitRoot = File("/tmp/test-jgit-" + DateTime.now().toString())

  // Set sequential execution
  sequential

  /**
   * Add a switch to be able to see tmp files (not clean themps) with
   * -Dtests.clean.tmp=false
   */
  override def afterAll() = {
    if(System.getProperty("tests.clean.tmp") != "false") {
      logger.info("Deleting directory " + gitRoot.pathAsString)
      FileUtils.deleteDirectory(gitRoot.toJava)
    }
  }

  gitRoot.createDirectories()

  val repo = GitRepositoryProviderImpl.make(gitRoot.pathAsString).runNow
  val archive = new GitArchiverUtils {
    override val gitRepo = repo
    override val gitRootDirectory = gitRoot.toJava
    override def relativePath: String = ""
    override def xmlPrettyPrinter = new RudderPrettyPrinter(Int.MaxValue, 2)
    override def encoding: String = "UTF-8"
    override def gitModificationRepository: GitModificationRepository = new GitModificationRepository {
      override def getCommits(modificationId: ModificationId): IOResult[Option[GitCommitId]] = None.succeed
      override def addCommit(commit: GitCommitId, modId: ModificationId): IOResult[DB.GitCommitJoin] = DB.GitCommitJoin(commit, modId).succeed
    }
  }

  // listing files at a commit is complicated

  import org.eclipse.jgit.treewalk.TreeWalk

  def readElementsAt(repository: Repository, commit: String) = {
    val ref = repository.findRef(commit)

    // a RevWalk allows to walk over commits based on some filtering that is defined
    val walkM = ZManaged.make(IOResult.effect(new RevWalk(repository)))(x => effectUioUnit(x.close()))
    val treeWalkM = ZManaged.make(IOResult.effect(new TreeWalk(repository)))(x => effectUioUnit(x.close()))

    walkM.use(walk =>
      for {
        commit <- IOResult.effect(walk.parseCommit(ref.getObjectId))
        tree   <- IOResult.effect(commit.getTree)
        res    <- treeWalkM.use{treeWalk =>
                    treeWalk.setRecursive(true) // ok, can't throw exception

                    IOResult.effect(treeWalk.addTree(tree)) *>
                    ZIO.loop(treeWalk)(_.next, identity)(x => IOResult.effect(x.getPathString))
                  }
      } yield res
    )
  }

  "The test lib" should {
    "not throw JGitInternalError on concurrent write" in {

      // you can remove `gitRepo.semaphore.withPermit` in `commitAddFile`
      // to check that you get the JGitInternalException

      val actor = new PersonIdent("test", "test@test.com")

      def getName(length: Int) = {
        if(length < 1) Inconsistency("Length must be positive").fail
        else {
          IOResult.effect("")(Random.alphanumeric.take(length).toList.mkString(""))
        }
      }
      def add(i: Int) = (for {
        name <- getName(8).map(s => i.toString + "_" + s)
        file =  gitRoot / name
        f    <- IOResult.effect(file.write("something in " + name))
        _    <- archive.commitAddFile(ModificationId(name), actor, name, "add " + name)
      } yield (name))

      logger.debug(s"Commiting files in: " + gitRoot.pathAsString)
      val files = ZIO.foreachParN(16)(1 to 50) { i => add(i) }.runNow

      val created = readElementsAt(repo.db, "refs/heads/master").runNow
      created must containTheSameElementsAs(files)

    }
  }

}

