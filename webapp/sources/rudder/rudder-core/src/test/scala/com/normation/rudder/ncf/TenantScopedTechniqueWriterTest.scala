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

import com.normation.cfclerk.domain.*
import com.normation.cfclerk.services.DummyTechniqueRepository
import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.Version
import com.normation.rudder.tenants.ChangeContext
import com.normation.rudder.tenants.DefaultTenantCheckLogic
import com.normation.rudder.tenants.InMemoryTenantService
import com.normation.rudder.tenants.SecurityTag
import com.normation.rudder.tenants.TenantAccess
import com.normation.rudder.tenants.TenantAccessGrant
import com.normation.rudder.tenants.TenantId
import com.normation.zio.*
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.*
import zio.syntax.*

/*
 * Check the technique tenant filtering proxy
 */
@RunWith(classOf[JUnitRunner])
class TenantScopedTechniqueWriterTest extends Specification {

  private val zoneATag = Some(SecurityTag.ByTenants(Chunk(TenantId("zoneA"))))
  private val zoneBTag = Some(SecurityTag.ByTenants(Chunk(TenantId("zoneB"))))

  private def cc(name: String) = {
    ChangeContext.newFor(EventActor(name), TenantAccessGrant.ByTenants(Chunk(TenantAccess(TenantId(name)))))
  }
  private val zoneA            = cc("zoneA")

  private def technique(name: String, version: String, security: Option[SecurityTag]): Technique = {
    Technique(
      TechniqueId(TechniqueName(name), TechniqueVersionHelper(version)),
      name,
      "",
      AgentConfig(AgentType.CfeCommunity, Nil, Nil, List(BundleName(name)), Nil) :: Nil,
      TrackerVariableSpec(id = None),
      SectionSpec(name = "root"),
      None,
      security = security
    )
  }

  private def editor(name: String, security: Option[SecurityTag]): EditorTechnique = {
    EditorTechnique(
      com.normation.rudder.ncf.BundleName(name),
      new Version("1.0"),
      name,
      "ncf_techniques",
      Seq(),
      "",
      "",
      Seq(),
      Seq(),
      Map(),
      None,
      None,
      security
    )
  }

  private class Library(techniques: List[Technique], category: TechniqueCategoryMetadata)
      extends DummyTechniqueRepository(techniques) {
    override def getByName(name: TechniqueName):                Map[TechniqueVersion, Technique] = {
      techniques.filter(_.id.name == name).map(t => (t.id.version, t)).toMap
    }
    override def getTechniqueCategory(id: TechniqueCategoryId): IOResult[TechniqueCategory]      = {
      SubTechniqueCategory(
        SubTechniqueCategoryId(TechniqueCategoryName("ncf_techniques"), RootTechniqueCategoryId),
        category.name,
        category.description,
        isSystem = category.isSystem,
        security = category.security
      ).succeed
    }
  }

  private class RecordingWriter extends TechniqueWriter {
    val written: Ref[Option[EditorTechnique]] = Ref.make(Option.empty[EditorTechnique]).runNow
    val deleted: Ref[Option[String]]          = Ref.make(Option.empty[String]).runNow

    override def deleteTechnique(name: String, version: String, deleteDirective: Boolean)(implicit
        cc: ChangeContext
    ): IOResult[Unit] = deleted.set(Some(name))

    override def writeTechniqueAndUpdateLib(t: EditorTechnique)(implicit cc: ChangeContext): IOResult[EditorTechnique] =
      written.set(Some(t)).as(t)

    override def writeTechnique(t: EditorTechnique)(implicit cc: ChangeContext): IOResult[EditorTechnique] =
      written.set(Some(t)).as(t)

    override def writeTechniques(ts: List[EditorTechnique])(implicit cc: ChangeContext): IOResult[List[EditorTechnique]] =
      ZIO.foreach(ts)(t => written.set(Some(t)).as(t))
  }

  private class RecordingCategoryWriter extends TechniqueCategoryWriter {
    val created: Ref[Option[Option[SecurityTag]]] = Ref.make(Option.empty[Option[SecurityTag]]).runNow
    val touched: Ref[Option[String]]              = Ref.make(Option.empty[String]).runNow

    private def info(id: TechniqueCategoryId) = TechniqueCategoryInfo(id, "n", "d")

    override def createCategory(parent: TechniqueCategoryId, name: String, description: String, security: Option[SecurityTag])(
        implicit cc: ChangeContext
    ): IOResult[TechniqueCategoryInfo] = created.set(Some(security)).as(info(parent))

    override def updateCategory(id: TechniqueCategoryId, name: Option[String], description: Option[String])(implicit
        cc: ChangeContext
    ): IOResult[TechniqueCategoryInfo] = touched.set(Some("update")).as(info(id))

    override def deleteCategory(id: TechniqueCategoryId)(implicit cc: ChangeContext): IOResult[Unit] =
      touched.set(Some("delete"))
  }

  private def checkLogic = {
    val service = InMemoryTenantService.make(Set(TenantId("zoneA"), TenantId("zoneB"))).runNow
    service.setTenantEnabled(true).runNow
    new DefaultTenantCheckLogic(service)
  }

  private val openCategory = TechniqueCategoryMetadata("User Techniques", "", isSystem = false, Some(SecurityTag.OpenRo))

  private def techniqueProxy(stored: List[Technique], writer: RecordingWriter) = {
    new TenantScopedTechniqueWriter(writer, new Library(stored, openCategory), checkLogic)
  }

  private def categoryProxy(category: TechniqueCategoryMetadata, writer: RecordingCategoryWriter) = {
    new TenantScopedTechniqueCategoryWriter(writer, new Library(Nil, category), checkLogic)
  }

  private val categoryId = SubTechniqueCategoryId(TechniqueCategoryName("ncf_techniques"), RootTechniqueCategoryId)

  "deleting a technique" should {

    "be allowed on one of the actor's own tenants" in {
      val writer = new RecordingWriter
      val proxy  = techniqueProxy(technique("backup", "1.0", zoneATag) :: Nil, writer)
      proxy.deleteTechnique("backup", "1.0", false)(using zoneA).either.runNow
      writer.deleted.get.runNow must beSome("backup")
    }

    "be refused on another tenant's technique" in {
      val writer = new RecordingWriter
      val proxy  = techniqueProxy(technique("backup", "1.0", zoneBTag) :: Nil, writer)
      (proxy.deleteTechnique("backup", "1.0", false)(using zoneA).either.runNow must beLeft) and
      (writer.deleted.get.runNow must beNone)
    }

    // deleting what is not there is a no-op, and it answers the same as a technique one may not see
    "be a no-op on an unknown technique" in {
      val writer = new RecordingWriter
      val proxy  = techniqueProxy(Nil, writer)
      (proxy.deleteTechnique("backup", "1.0", false)(using zoneA).either.runNow must beRight) and
      (writer.deleted.get.runNow must beNone)
    }
  }

  "writing a technique" should {

    "be refused when it overwrites another tenant's technique" in {
      val writer = new RecordingWriter
      val proxy  = techniqueProxy(technique("backup", "1.0", zoneBTag) :: Nil, writer)
      (proxy.writeTechniqueAndUpdateLib(editor("backup", zoneBTag))(using zoneA).either.runNow must beLeft) and
      (writer.written.get.runNow must beNone)
    }

    "keep the stored tag rather than the posted one" in {
      val writer = new RecordingWriter
      val proxy  = techniqueProxy(technique("backup", "1.0", zoneATag) :: Nil, writer)
      proxy.writeTechniqueAndUpdateLib(editor("backup", zoneATag))(using zoneA).either.runNow
      writer.written.get.runNow.flatMap(_.security) must beEqualTo(zoneATag)
    }

    // the creator does not get to name the tenants: a posted tag it may not write is replaced
    "tag a new technique from the creator's writable tenants, whatever it declares" in {
      val writer = new RecordingWriter
      val proxy  = techniqueProxy(Nil, writer)
      proxy.writeTechniqueAndUpdateLib(editor("backup", zoneBTag))(using zoneA).either.runNow
      writer.written.get.runNow.flatMap(_.security) must beEqualTo(zoneATag)
    }

    "authorize each technique of a batch on its own" in {
      val writer = new RecordingWriter
      val proxy  = techniqueProxy(technique("backup", "1.0", zoneBTag) :: Nil, writer)
      proxy.writeTechniques(editor("backup", None) :: Nil)(using zoneA).either.runNow must beLeft
    }
  }

  "a technique category" should {

    "be renamed only by a tenant that may write it" in {
      val writer = new RecordingCategoryWriter
      val proxy  = categoryProxy(openCategory.copy(security = zoneBTag), writer)
      (proxy.updateCategory(categoryId, Some("new"), None)(using zoneA).either.runNow must beLeft) and
      (writer.touched.get.runNow must beNone)
    }

    "be deleted only by a tenant that may write it" in {
      val writer = new RecordingCategoryWriter
      val proxy  = categoryProxy(openCategory.copy(security = zoneBTag), writer)
      (proxy.deleteCategory(categoryId)(using zoneA).either.runNow must beLeft) and
      (writer.touched.get.runNow must beNone)
    }

    "be renamed by its own tenant" in {
      val writer = new RecordingCategoryWriter
      val proxy  = categoryProxy(openCategory.copy(security = zoneATag), writer)
      proxy.updateCategory(categoryId, Some("new"), None)(using zoneA).either.runNow
      writer.touched.get.runNow must beSome("update")
    }

    // an open-ro parent is visible to every tenant, which is what makes it a usable place to create in
    "be created under an open parent, tagged from the creator's writable tenants" in {
      val writer = new RecordingCategoryWriter
      val proxy  = categoryProxy(openCategory, writer)
      proxy.createCategory(categoryId, "mine", "", zoneATag)(using zoneA).either.runNow
      writer.created.get.runNow must beSome(zoneATag)
    }

    "not be created under another tenant's parent" in {
      val writer = new RecordingCategoryWriter
      val proxy  = categoryProxy(openCategory.copy(security = zoneBTag), writer)
      (proxy.createCategory(categoryId, "mine", "", zoneATag)(using zoneA).either.runNow must beLeft) and
      (writer.created.get.runNow must beNone)
    }
  }

  "the versions of a technique name" should {

    "join their tags, so a name is one object for the law" in {
      val versions = technique("backup", "1.0", zoneATag) :: technique("backup", "2.0", zoneBTag) :: Nil
      TenantScopedTechniqueWriter.joinVersions(versions).flatMap(_.security) must beEqualTo(
        Some(SecurityTag.ByTenants(Chunk(TenantId("zoneA"), TenantId("zoneB"))))
      )
    }

    "be absent when the name is not in the library" in {
      TenantScopedTechniqueWriter.joinVersions(Nil) must beNone
    }
  }
}
