/*
 *************************************************************************************
 * Copyright 2019 Normation SAS
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
import com.normation.cfclerk.domain.*
import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.policies.AcceptationDateTime
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.rudder.domain.policies.ActiveTechniqueCategory
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import com.normation.rudder.domain.policies.ActiveTechniqueId
import com.normation.rudder.domain.policies.DeleteDirectiveDiff
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveSaveDiff
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.PolicyTypes
import com.normation.rudder.repository.CategoryWithActiveTechniques
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.WoDirectiveRepository
import com.normation.rudder.services.policies.TechniqueAcceptationUpdater
import com.normation.rudder.services.policies.TestNodeConfiguration
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import net.liftweb.common.*
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.AfterAll
import scala.collection.SortedMap
import zio.*
import zio.syntax.*

@RunWith(classOf[JUnitRunner])
class TechniqueRepositoryTest extends Specification with Loggable with AfterAll {

  // Set sequential execution
  sequential

  implicit val charset: Charset = StandardCharsets.UTF_8
  val setupRepos = new TestNodeConfiguration()

  val fsRepos = setupRepos.techniqueRepository
  val git     = setupRepos.repo.git
  val techniqueRoot: File = File(setupRepos.repo.db.getWorkTree.getAbsolutePath, "techniques")

  val modid = new ModificationId("test")
  val actor = new EventActor("test")

  object testCallback extends TechniquesLibraryUpdateNotification() {

    var techniques: Map[TechniqueName, TechniquesLibraryUpdateType] = Map.empty[TechniqueName, TechniquesLibraryUpdateType]
    var categories: Set[TechniqueCategoryModType]                   = Set.empty[TechniqueCategoryModType]

    override def name:  String = "test-callback"
    override def order: Int    = 0
    override def updatedTechniques(
        gitRev:            String,
        techniqueIds:      Map[TechniqueName, TechniquesLibraryUpdateType],
        updatedCategories: Set[TechniqueCategoryModType],
        modId:             ModificationId,
        actor:             EventActor,
        reason:            Option[String]
    ): Box[Unit] = {
      categories = updatedCategories
      techniques = techniqueIds
      Full(())
    }
  }

  // a repos just to check what methods are called
  object ldapRepo extends RoDirectiveRepository with WoDirectiveRepository {
    var added:             List[String]                           = Nil // only the category id
    var moved:             List[(String, String, Option[String])] = Nil // (what, where, new name)
    var deleted:           List[String]                           = Nil
    var updatedTechniques: List[String]                           = Nil

    override def addActiveTechniqueCategory(
        that:           ActiveTechniqueCategory,
        into:           ActiveTechniqueCategoryId,
        modificationId: ModificationId,
        actor:          EventActor,
        reason:         Option[String]
    ): IOResult[ActiveTechniqueCategory] = {
      added = that.id.value :: added
      that.succeed
    }
    override def deleteCategory(
        id:             ActiveTechniqueCategoryId,
        modificationId: ModificationId,
        actor:          EventActor,
        reason:         Option[String],
        checkEmpty:     Boolean
    ): IOResult[ActiveTechniqueCategoryId] = {
      deleted = id.value :: deleted
      id.succeed
    }
    override def move(
        categoryId:     ActiveTechniqueCategoryId,
        intoParent:     ActiveTechniqueCategoryId,
        optionNewName:  Option[ActiveTechniqueCategoryId],
        modificationId: ModificationId,
        actor:          EventActor,
        reason:         Option[String]
    ): IOResult[ActiveTechniqueCategoryId] = {
      moved = (categoryId.value, intoParent.value, optionNewName.map(_.value)) :: moved
      optionNewName.getOrElse(categoryId).succeed
    }
    override def addTechniqueInUserLibrary(
        categoryId:    ActiveTechniqueCategoryId,
        techniqueName: TechniqueName,
        versions:      Seq[TechniqueVersion],
        policyTypes:   PolicyTypes,
        modId:         ModificationId,
        actor:         EventActor,
        reason:        Option[String]
    ): IOResult[ActiveTechnique] = {
      updatedTechniques = techniqueName.value :: updatedTechniques
      ActiveTechnique(ActiveTechniqueId("empty"), techniqueName, AcceptationDateTime.empty).succeed
    }
    // ALL the following methods are useless for our test
    override def getFullDirectiveLibrary(): IOResult[FullActiveTechniqueCategory] = {
      FullActiveTechniqueCategory(
        ActiveTechniqueCategoryId("Active Techniques"),
        name = "",
        description = "",
        subCategories = Nil,
        activeTechniques = Nil,
        isSystem = true
      ).succeed
    }
    override def getDirective(directiveId: DirectiveUid): IOResult[Option[Directive]] = ???
    override def getDirectiveWithContext(directiveId: DirectiveUid): IOResult[Option[(Technique, ActiveTechnique, Directive)]] =
      ???
    override def getActiveTechniqueAndDirective(id:      DirectiveId): IOResult[Option[(ActiveTechnique, Directive)]] = ???
    override def getDirectives(activeTechniqueId:        ActiveTechniqueId, includeSystem: Boolean): IOResult[Seq[Directive]] = ???
    override def getActiveTechniqueByCategory(
        includeSystem: Boolean
    ): IOResult[SortedMap[List[ActiveTechniqueCategoryId], CategoryWithActiveTechniques]] = ???
    override def getActiveTechniqueByActiveTechnique(id: ActiveTechniqueId): IOResult[Option[ActiveTechnique]] = ???
    override def getActiveTechnique(techniqueName:       TechniqueName): IOResult[Option[ActiveTechnique]] = ???
    override def activeTechniqueBreadCrump(id:           ActiveTechniqueId): IOResult[List[ActiveTechniqueCategory]] = ???
    override def getActiveTechniqueLibrary: IOResult[ActiveTechniqueCategory] = ???
    override def getAllActiveTechniqueCategories(includeSystem: Boolean):                   IOResult[Seq[ActiveTechniqueCategory]]    = ???
    override def getActiveTechniqueCategory(id:                 ActiveTechniqueCategoryId): IOResult[Option[ActiveTechniqueCategory]] = ???
    override def getParentActiveTechniqueCategory(id:           ActiveTechniqueCategoryId): IOResult[ActiveTechniqueCategory]         = ???
    override def getParentsForActiveTechniqueCategory(id: ActiveTechniqueCategoryId): IOResult[List[ActiveTechniqueCategory]] =
      ???
    override def getParentsForActiveTechnique(id: ActiveTechniqueId):         IOResult[ActiveTechniqueCategory] = ???
    override def containsDirective(id:            ActiveTechniqueCategoryId): UIO[Boolean]                      = ???
    override def saveActiveTechniqueCategory(
        category:       ActiveTechniqueCategory,
        modificationId: ModificationId,
        actor:          EventActor,
        reason:         Option[String]
    ): IOResult[ActiveTechniqueCategory] = ???
    override def saveDirective(
        inActiveTechniqueId: ActiveTechniqueId,
        directive:           Directive,
        modId:               ModificationId,
        actor:               EventActor,
        reason:              Option[String]
    ): IOResult[Option[DirectiveSaveDiff]] = ???
    override def saveSystemDirective(
        inActiveTechniqueId: ActiveTechniqueId,
        directive:           Directive,
        modId:               ModificationId,
        actor:               EventActor,
        reason:              Option[String]
    ): IOResult[Option[DirectiveSaveDiff]] = ???
    override def delete(
        id:     DirectiveUid,
        modId:  ModificationId,
        actor:  EventActor,
        reason: Option[String]
    ): IOResult[Option[DeleteDirectiveDiff]] = ???
    override def deleteSystemDirective(
        id:     DirectiveUid,
        modId:  ModificationId,
        actor:  EventActor,
        reason: Option[String]
    ): IOResult[Option[DeleteDirectiveDiff]] = ???
    override def move(
        id:            ActiveTechniqueId,
        newCategoryId: ActiveTechniqueCategoryId,
        modId:         ModificationId,
        actor:         EventActor,
        reason:        Option[String]
    ): IOResult[ActiveTechniqueId] = ???
    override def changeStatus(
        id:     ActiveTechniqueId,
        status: Boolean,
        modId:  ModificationId,
        actor:  EventActor,
        reason: Option[String]
    ): IOResult[ActiveTechniqueId] = ???
    override def setAcceptationDatetimes(
        id:        ActiveTechniqueId,
        datetimes: Map[TechniqueVersion, DateTime],
        modId:     ModificationId,
        actor:     EventActor,
        reason:    Option[String]
    ): IOResult[ActiveTechniqueId] = ???
    override def deleteActiveTechnique(
        id:     ActiveTechniqueId,
        modId:  ModificationId,
        actor:  EventActor,
        reason: Option[String]
    ): IOResult[ActiveTechniqueId] = ???
  }

  val ldapCallBack = new TechniqueAcceptationUpdater("update", 0, ldapRepo, ldapRepo, fsRepos)

  fsRepos.registerCallback(testCallback)
  fsRepos.registerCallback(ldapCallBack)

  // add everything not added under technique, and commit with provided message
  def addCommitAll(commitMsg: String):    Unit                 = {
    git.add().addFilepattern("techniques").call()
    git.add().setUpdate(true).addFilepattern("techniques").call() // to take into account delete
    git.commit().setMessage(commitMsg).call()
  }
  // get category by directory name
  def getCategory(directoryName: String): SubTechniqueCategory = {
    fsRepos.getAllCategories.values
      .find(_.id.name.value == directoryName)
      .getOrElse(
        throw new Exception(s"error in test hypothesis: not found '${directoryName}'")
      )
      .asInstanceOf[SubTechniqueCategory]
  }

  val rootCategory = fsRepos.getTechniqueLibrary

  /**
   * Add a switch to be able to see tmp files (not clean themps) with
   * -Dtests.clean.tmp=false
   */
  override def afterAll(): Unit = {
    if (java.lang.System.getProperty("tests.clean.tmp") != "false") {
      logger.info("Deleting directory " + setupRepos.abstractRoot.getAbsolutePath)
      FileUtils.deleteDirectory(setupRepos.abstractRoot)
    }
  }

  def createCategory(path: String, name: String): Unit = {
    val dir        = File(techniqueRoot, path)
    dir.createDirectories()
    val descriptor = File(dir, "category.xml")
    descriptor.writeText(
      s"""<xml>
         |  <name>${name}</name>
         |  <description>Description for category ${name}</description>
         |</xml>
         |""".stripMargin
    )
    addCommitAll(s"Add category '${name}'")
  }

  "Creating a category should be notified" in {
    createCategory("test1", "Test 1")
    fsRepos.update(modid, actor, None)
    val cat = getCategory("test1")

    (testCallback.categories must containTheSameElementsAs(Seq(TechniqueCategoryModType.Added(cat, rootCategory.id)))) and
    (ldapRepo.added must beEqualTo(cat.id.name.value :: Nil))
  }

  "Renaming previously created empty category should be seen as a rename" in {
    val cat1 = getCategory("test1")
    File(techniqueRoot, "test1").moveTo(File(techniqueRoot, "test2"))
    addCommitAll("Move category with id test1 to test2. Keep same name")
    fsRepos.update(modid, actor, None)
    val cat2 = getCategory("test2")

    (testCallback.categories must containTheSameElementsAs(Seq(TechniqueCategoryModType.Moved(cat1.id, cat2.id)))) and
    (ldapRepo.moved must beEqualTo((cat1.id.name.value, "Active Techniques", Some(cat2.id.name.value)) :: Nil))
  }

  "We can delete an empty category" in {
    val cat2 = getCategory("test2")
    File(techniqueRoot, "test2").delete()
    addCommitAll("Delete category test2")
    fsRepos.update(modid, actor, None)

    (testCallback.categories must containTheSameElementsAs(Seq(TechniqueCategoryModType.Deleted(cat2)))) and
    (ldapRepo.deleted must beEqualTo(cat2.id.name.value :: Nil))
  }

  "We can't delete a technique category that contains techniques" in {
    val appCat = getCategory("applications")
    ldapRepo.deleted = Nil
    File(techniqueRoot, "applications").delete()
    addCommitAll("Deleted applicates")
    fsRepos.update(modid, actor, None)

    (testCallback.categories must containTheSameElementsAs(Seq(TechniqueCategoryModType.Deleted(appCat)))) and
    (fsRepos.getAllCategories.get(appCat.id) must beNone) and // technique repos doesn't have it anymore
    (ldapRepo.deleted must beEmpty)                           // but the ldap repo don't get the delete
  }

  "Renaming previously created category with a technique should be seen as a rename" in {
    val cat  = getCategory("fileDistribution")
    ldapRepo.moved = Nil
    File(techniqueRoot, "fileDistribution").moveTo(File(techniqueRoot, "fileDistribution2"))
    addCommitAll("Move category with id fileDistribution to fileDistribution2. Keep same name")
    fsRepos.update(modid, actor, None)
    val cat2 = getCategory("fileDistribution2")

    (testCallback.categories must containTheSameElementsAs(Seq(TechniqueCategoryModType.Moved(cat.id, cat2.id)))) and
    (fsRepos.getAllCategories.get(cat2.id) must beSome[TechniqueCategory]) and
    (ldapRepo.moved must beEqualTo(("fileDistribution", "Active Techniques", Some("fileDistribution2")) :: Nil)) and
    (ldapRepo.updatedTechniques must containTheSameElementsAs(Seq("copyGitFile", "fileTemplate")))
  }

  "Moving a sub-category to root works" in {
    ldapRepo.moved = Nil
    ldapRepo.added = Nil

    createCategory("fileDistribution2/fileSecurity", "File Security")
    fsRepos.update(modid, actor, None)
    val cat  = getCategory("fileSecurity")
    (techniqueRoot / "fileDistribution2" / "fileSecurity").moveTo(techniqueRoot / "fileSecurity")
    addCommitAll("Move a sub-category to root one")
    fsRepos.update(modid, actor, None)
    val cat2 = getCategory("fileSecurity")

    (testCallback.categories must containTheSameElementsAs(Seq(TechniqueCategoryModType.Moved(cat.id, cat2.id)))) and
    (fsRepos.getAllCategories.get(cat2.id) must beSome[TechniqueCategory]) and
    (ldapRepo.moved must beEqualTo(("fileSecurity", "Active Techniques", None) :: Nil)) and
    (ldapRepo.updatedTechniques must containTheSameElementsAs(Seq("fileTemplate", "copyGitFile")))

  }
}
