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

package com.normation.rudder.repository.xml

import better.files.File
import com.normation.GitVersion.Revision
import com.normation.cfclerk.domain.TechniqueCategoryName
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupCategory
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.git.GitRepositoryProviderImpl
import com.normation.rudder.services.marshalling.NodeGroupCategorySerialisationImpl
import com.normation.rudder.services.marshalling.NodeGroupCategoryUnserialisationImpl
import com.normation.rudder.services.marshalling.NodeGroupSerialisationImpl
import com.normation.rudder.services.marshalling.NodeGroupUnserialisationImpl
import com.normation.rudder.services.policies.TestTechniqueRepo
import com.normation.rudder.services.queries.CmdbQueryParser
import com.normation.utils.ParseVersion
import com.normation.zio.*
import net.liftweb.common.Loggable
import org.apache.commons.io.FileUtils
import org.junit.runner.RunWith
import org.specs2.matcher.ContentMatchers
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.AfterAll
import zio.Chunk

@RunWith(classOf[JUnitRunner])
class TestGitParseRudderObjects extends Specification with Loggable with AfterAll with ContentMatchers {

  ////////// set up / clean-up and utilities //////////

  lazy val testRepo     = new TestTechniqueRepo("", "same-end-technique-name", None)
  lazy val abstractRoot = testRepo.abstractRoot
  lazy val parseObjects = new GitParseTechniqueLibrary(
    testRepo.draftParser,
    testRepo.repo,
    testRepo.gitRevisionProvider,
    "techniques",
    "metadata.xml"
  )

  /*
   * A group library, laid out exactly as `GitNodeGroupArchiverImpl` writes it (see
   * `BuildCategoryPathName#newCategoryDirectory`):
   * - the root category is the group library directory itself, so its ID ("GroupRoot") appears
   *   nowhere in the paths - contrary to the other categories, whose directory is named after them,
   * - a group is a `<uuid>.xml` file directly in the directory of the category holding it.
   *
   * groups/                           <- root category
   *   category.xml
   *   <rootCatGroupId>.xml
   *   <subCatId>/                     <- a sub-category of the root category
   *     category.xml
   *     <subCatGroupId>.xml
   */
  val rootCatGroupId: NodeGroupUid        = NodeGroupUid("5aa4e2ba-4e6c-4d5e-a5d4-1cbc9a1ef2d1")
  val subCatGroupId:  NodeGroupUid        = NodeGroupUid("87e3d2cb-6f43-4b7a-9c8d-2f1a0e6b3c74")
  val subCatId:       NodeGroupCategoryId = NodeGroupCategoryId("2f37b1c4-4a2e-4ff0-9e46-0a2dcd7e07a1")
  val rootCatId:      NodeGroupCategoryId = NodeGroupCategoryId("GroupRoot")

  lazy val groupLibRoot: File = File.newTemporaryDirectory("rudder-test-group-lib-")

  lazy val groupLibRepo: GitRepositoryProviderImpl = {
    val xmlVersion = Constants.XML_CURRENT_FILE_FORMAT.toString
    val groupSer   = new NodeGroupSerialisationImpl(xmlVersion)
    val catSer     = new NodeGroupCategorySerialisationImpl(xmlVersion)

    def group(uid: NodeGroupUid) = NodeGroup(
      id = NodeGroupId(uid),
      name = s"group ${uid.value}",
      description = "a group for tests",
      properties = Nil,
      query = None,
      isDynamic = true,
      serverList = Set(),
      _isEnabled = true,
      isSystem = false,
      security = None
    )

    def category(id: NodeGroupCategoryId) = NodeGroupCategory(
      id = id,
      name = s"category ${id.value}",
      description = "a category for tests",
      children = Nil,
      items = Nil,
      isSystem = false,
      security = None
    )

    val groups = (groupLibRoot / "groups").createDirectories()
    (groups / "category.xml").write(catSer.serialise(category(rootCatId)).toString)
    (groups / s"${rootCatGroupId.value}.xml").write(groupSer.serialise(group(rootCatGroupId)).toString)
    val subCat = (groups / subCatId.value).createDirectories()
    (subCat / "category.xml").write(catSer.serialise(category(subCatId)).toString)
    (subCat / s"${subCatGroupId.value}.xml").write(groupSer.serialise(group(subCatGroupId)).toString)

    // that creates the git repository and commits everything in it as the initial commit on master
    GitRepositoryProviderImpl.make(groupLibRoot.pathAsString).runNow
  }

  lazy val parseGroupLib = new GitParseGroupLibrary(
    new NodeGroupCategoryUnserialisationImpl(),
    new NodeGroupUnserialisationImpl(CmdbQueryParser.jsonStrictParser(Map.empty)),
    groupLibRepo,
    "groups"
  )

  override def afterAll(): Unit = {
    if (System.getProperty("tests.clean.tmp") != "false") {
      logger.debug("Deleting directory " + abstractRoot.getAbsolutePath)
      FileUtils.deleteDirectory(abstractRoot.getAbsoluteFile)
      logger.debug("Deleting directory " + groupLibRoot.pathAsString)
      groupLibRoot.delete(swallowIOExceptions = true)
    }
  }

  val v1_0 = ParseVersion.parse("1.0").getOrElse(throw new RuntimeException("Version 1.0"))

  sequential

  "looking for technique with the same end name" should {
    "return the correct one" in {
      val res = parseObjects.getTechnique(TechniqueName("file"), v1_0, Revision("master")).runNow

      res.map(_._1) must (beSome((Chunk(TechniqueCategoryName("zz_last")))))
      res.map(_._2.id.serialize) must (beSome("file/1.0+master"))
    }

    "give the correct resources" in {
      val res = parseObjects.getTechniqueFileContents(TechniqueId(TechniqueName("file"), TechniqueVersion.V1_0)).runNow

      // must not contain Create_file.ps1 etc
      res.map(_.map(_._1).toSet) must (beSome(containTheSameElementsAs(List("metadata.xml", "packageManagement.st"))))
    }
  }

  "looking for a group at a given revision" should {
    "find a group of a sub-category and get its category from the name of the directory holding it" in {
      val res = parseGroupLib.getGroupRevision(subCatGroupId, Revision("master")).runNow

      res.map(_.group.id.uid) must beSome(subCatGroupId)
      res.map(_.categoryId) must beSome(subCatId)
    }

    // the root category is the group library directory itself: its ID can not be read back from the
    // path of the files it holds, and it must not be mistaken for the name of that directory ("groups")
    "find a group of the root category and map it to the root category ID, not to the library directory name" in {
      val res = parseGroupLib.getGroupRevision(rootCatGroupId, Revision("master")).runNow

      res.map(_.group.id.uid) must beSome(rootCatGroupId)
      res.map(_.categoryId) must beSome(rootCatId)
      res.map(_.categoryId) must not(beSome(NodeGroupCategoryId("groups")))
    }

    // revisions are given by git and not serialized in the archive, so the looked-up one is stamped
    // back into the group ID. Users of that method (like an item rollback) need to know it.
    "stamp the revision it was looked-up at into the group ID" in {
      val res = parseGroupLib.getGroupRevision(rootCatGroupId, Revision("master")).runNow

      res.map(_.group.id.rev) must beSome(Revision("master"))
    }

    "return no group when the ID is unknown" in {
      val res = parseGroupLib.getGroupRevision(NodeGroupUid("2b3e6f14-8c92-4b0d-a1f7-9e5c3d8a4b60"), Revision("master")).runNow

      res must beNone
    }
  }
}
