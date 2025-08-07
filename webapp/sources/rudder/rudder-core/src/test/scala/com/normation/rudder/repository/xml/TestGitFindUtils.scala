/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
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

package com.normation.rudder.repository.xml

import com.normation.errors.*
import com.normation.rudder.git.GitFindUtils
import com.normation.rudder.git.ZipUtils
import com.normation.zio.*
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import net.liftweb.common.Loggable
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.Status
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.junit.runner.RunWith
import org.specs2.matcher.ContentMatchers
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.AfterAll

@RunWith(classOf[JUnitRunner])
class TestGitFindUtils extends Specification with Loggable with AfterAll with ContentMatchers {

  ////////// set up / clean-up and utilities //////////

  lazy val root    = new File("/tmp/test-jgit-" + DateTime.now(DateTimeZone.UTC).toString())
  lazy val gitRoot = new File(root, "repo")

  override def afterAll(): Unit = {
    if (System.getProperty("tests.clean.tmp") != "false") {
      logger.debug("Deleting directory " + root.getAbsolutePath)
      FileUtils.deleteDirectory(root)
    }
  }

  // create a file with context = its name, creating its parent dir if needed
  def mkfile(relativePath: String, name: String): Unit = {
    val d = new File(gitRoot, relativePath)
    d.mkdirs
    FileUtils.writeStringToFile(new File(d, name), name, StandardCharsets.UTF_8)
  }

  ////////// init test directory structure //////////

  // init the file layout
  val all: List[(String, String)] = {
    ("a", "root.txt") ::
    ("a", "root.pdf") ::
    ("a/a", "f.txt") ::
    ("b", "root.txt") ::
    ("b", "root.pdf") ::
    ("b/a", "f.txt") ::
    ("b/a", "f.plop") ::
    ("x/f.txt", "f.txt") ::
    Nil
  }

  // build
  all.foreach {
    case (path, file) =>
      mkfile(path, file)
  }

  val allPaths: List[String] = all.map { case (d, f) => d + "/" + f }
  val allPdf:   List[String] = all.collect { case (d, f) if f.endsWith(".pdf") => d + "/" + f }
  val allTxt:   List[String] = all.collect { case (d, f) if f.endsWith(".txt") => d + "/" + f }
  val allDirA:  List[String] = all.collect { case (d, f) if d.startsWith("a") => d + "/" + f }
  val allDirAA: List[String] = all.collect { case (d, f) if d.startsWith("a/a") => d + "/" + f }
  val allDirB:  List[String] = all.collect { case (d, f) if d.startsWith("b") => d + "/" + f }
  val allDirX:  List[String] = List("x/f.txt/f.txt")

  val db: FileRepository = ((new FileRepositoryBuilder).setWorkTree(gitRoot).build).asInstanceOf[FileRepository]
  if (!db.getConfig.getFile.exists) {
    db.create()
  }
  val git = new Git(db)
  git.add.addFilepattern(".").call
  val id: ObjectId =
    ZioRuntime.runNow(GitFindUtils.findRevTreeFromRevString(db, git.commit.setMessage("initial commit").call.name))

  def list(rootDirectories: List[String], endPaths: List[String]): Set[String] =
    ZioRuntime.runNow(GitFindUtils.listFiles(db, id, rootDirectories, endPaths))

  ////////// actual tests //////////

  sequential

  "the walk" should {
    "return all results when no filter provided" in {
      list(Nil, Nil) must contain(exactly(allPaths*))
    }

    "return all results when all dir are provided as filter" in {
      list(List("a", "b", "x"), Nil) must contain(exactly(allPaths*))
    }

    "return all results when all extension are provided" in {
      list(Nil, List("pdf", "txt", "plop")) must contain(exactly(allPaths*))
    }

    "return only files under a when filter for 'a'" in {
      list(List("a"), Nil) must contain(exactly(allDirA*))
    }

    "return only files under a when filter for a/" in {
      list(List("a/"), Nil) must contain(exactly(allDirA*))
    }

    "return only files under a when filter for a/a" in {
      list(List("a/a"), Nil) must contain(exactly(allDirAA*))
    }

    "return only files under a when filter for 'b'" in {
      list(List("b"), Nil) must contain(exactly(allDirB*))
    }

    "return both files under 'a' and 'b'" in {
      list(List("a", "b"), Nil) must contain(exactly(allDirA ++ allDirB*))
    }

    "return all .txt" in {
      list(Nil, List(".txt")) must contain(exactly(allTxt*))
    }

    "return all .txt and .pdf" in {
      list(Nil, List(".txt", "pdf")) must contain(exactly(allTxt ++ allPdf*))
    }

    "return x/f.txt/f.txt" in {
      list(List("x"), List(".txt")) must contain(exactly(allDirX*))
    }

    "return nothing" in {
      list(List("x"), List("plop")) must beEmpty
    }

    "ignore empty path" in {
      list(List("x", ""), Nil) must contain(exactly(allDirX*))
    }

    "ignore empty extension" in {
      list(Nil, List("txt", "")) must contain(exactly(allTxt*))
    }

  }

  "zip and unzip is identity" >> {

    val archive = new File(root, "archive.zip")

    // a place to dezip
    val unzip = new File(root, "unzip")
    unzip.mkdir()

    ZioRuntime.runNow(
      GitFindUtils.getZip(db, id).flatMap(bytes => IOResult.attempt(FileUtils.writeByteArrayToFile(archive, bytes))) *> ZipUtils
        .unzip(new ZipFile(archive), unzip)
    )

    gitRoot must haveSameFilesAs(unzip).withFilter((file: File) => !file.getAbsolutePath.contains(".git"))
  }

  // all modification
  def allModif(s: Status): List[String] = {
    import scala.jdk.CollectionConverters.*
    List(
      s.getAdded,
      s.getChanged,
      s.getConflicting,
      s.getIgnoredNotInIndex,
      s.getMissing,
      s.getModified,
      s.getRemoved,
      s.getUncommittedChanges,
      s.getUntracked,
      s.getUntrackedFolders
    ).map(_.asScala).reduce(_ ++ _).toList.sorted
  }

  "give correct status" in {

    // delete a/root.txt, a/root.pdf, a/a/f.txt
    FileUtils.deleteDirectory(new File(gitRoot, "a"))

    // create not added c/untracked
    mkfile("c", "untracked")

    // create and add, not commited d/added
    mkfile("d", "added")
    git.add().addFilepattern("d/added").call

    val all = GitFindUtils.getStatus(git, Nil).runNow

    val a = GitFindUtils.getStatus(git, List("a")).runNow

    val ad = GitFindUtils.getStatus(git, "a" :: "d" :: Nil).runNow

    git.add().setUpdate(true).addFilepattern(".").call()  // deleted and modified
    git.add().setUpdate(false).addFilepattern(".").call() // untracked
    git.commit().setMessage("Commit all").call()

    val x = GitFindUtils.getStatus(git, Nil).runNow

    (allModif(all) === List("a/a/f.txt", "a/root.pdf", "a/root.txt", "c", "c/untracked", "d/added")) and
    (allModif(a) === List("a/a/f.txt", "a/root.pdf", "a/root.txt")) and
    (allModif(ad) === List("a/a/f.txt", "a/root.pdf", "a/root.txt", "d/added")) and
    (allModif(x) === List())
  }

  "give correct status with sub git repos - BUG IN JGIT, BE CAREFULL" in {

    // create a sub dir that is going to be a repos.
    mkfile("subgit", "file")
    val db2  = ((new FileRepositoryBuilder).setWorkTree(new File(gitRoot, "subgit")).build).asInstanceOf[FileRepository]
    if (!db2.getConfig.getFile.exists) {
      db2.create()
    }
    val git2 = new Git(db2)
    git2.add().addFilepattern(".").call()
    git2.commit().setMessage("first commit").call()

    // also add in parent
    git.add().addFilepattern(".").call()
    git.commit().setMessage("add subrepo").call()

    // modif file
    FileUtils.writeStringToFile(new File(git2.getRepository.getDirectory, "file"), "some other text", StandardCharsets.UTF_8)

    // commit only on sub repos
    git2.add().addFilepattern(".").call()
    git2.commit().setMessage("second commit").call()

    (
      (allModif(GitFindUtils.getStatus(git2, Nil).runNow) === Nil)
      and (allModif(GitFindUtils.getStatus(git, Nil).runNow) === List("subgit"))
      // not sure why but subgit is created as a submodule
      // comment until https://bugs.eclipse.org/bugs/show_bug.cgi?id=565251 is corrected
//      and (allModif(GitFindUtils.getStatus(git , List("a")).runNow) === Nil)
    )
  }
}
