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

package com.normation.rudder.repository

import com.normation.cfclerk.domain.*
import com.normation.errors.*
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.archives.ParameterArchiveId
import com.normation.rudder.domain.archives.RuleArchiveId
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.properties.*
import com.normation.rudder.rule.category.*
import com.normation.rudder.tenants.*
import com.softwaremill.quicklens.*
import com.unboundid.ldif.LDIFChangeRecord
import java.time.Instant
import scala.collection.immutable.SortedMap
import zio.*

/*
 * This file contains the tenant filtering logic for technique, directive, rule, group, parameter repositories.
 * The main idea:
 *   - for all read operation, we post-filter all objects with the tenant scoping
 *   - for all write operation:
 *       - we check for an existing with object to see what tenants it could get,
 *       - then we check for see, and for write
 */

// ----- Directives -----------------------------------

class RoTenantDirectiveRepo(
    checkTenant: TenantCheckLogic,
    underlying:  RoDirectiveRepository
) extends RoDirectiveRepository {

  private def filterFATC(c: FullActiveTechniqueCategory)(using qc: QueryContext): Option[FullActiveTechniqueCategory] = {
    checkTenant
      .check(c)
      .map { cat =>
        cat
          .modify(_.subCategories)
          .setTo(cat.subCategories.flatMap(filterFATC))
          .modify(_.activeTechniques)
          .setTo(checkTenant.collect(cat.activeTechniques) { at =>
            at.modify(_.directives).setTo(checkTenant.filter(at.directives))
          })
      }
  }

  override def getFullDirectiveLibrary()(using qc: QueryContext): IOResult[FullActiveTechniqueCategory] = {
    underlying
      .getFullDirectiveLibrary()
      .map(filterFATC)
      .notOptional(s"Root directive library category is not visible by '${qc.actor.name}'")
  }

  override def getDirective(directiveId: DirectiveUid)(using qc: QueryContext): IOResult[Option[Directive]] = {
    underlying.getDirective(directiveId).map(checkTenant.flatMap(_))
  }

  override def getDirectiveWithContext(
      directiveId: DirectiveUid
  )(using qc: QueryContext): IOResult[Option[(Technique, ActiveTechnique, Directive)]] = {
    underlying.getDirectiveWithContext(directiveId).map {
      case None          => None
      // we don't check technique, only active technique. The technique at that level doesn't hold
      // tenants. It should likely. Like in the yaml, else it's not easy to migrate that part
      // with archive
      case Some(a, b, c) => checkTenant.check(b).zip(checkTenant.check(c)).map(_ => (a, b, c))
    }
  }

  override def getActiveTechniqueAndDirective(
      id: DirectiveId
  )(using qc: QueryContext): IOResult[Option[(ActiveTechnique, Directive)]] = {
    // only check directive
    underlying.getActiveTechniqueAndDirective(id).map(checkTenant.flatMap(_))
  }

  override def getDirectives(activeTechniqueId: ActiveTechniqueId, includeSystem: Boolean)(using
      qc: QueryContext
  ): IOResult[Seq[Directive]] = {
    underlying.getDirectives(activeTechniqueId, includeSystem).map(checkTenant.filter(_))
  }

  override def getActiveTechniqueByCategory(
      includeSystem: Boolean
  )(using qc: QueryContext): IOResult[SortedMap[List[ActiveTechniqueCategoryId], CategoryWithActiveTechniques]] = {
    underlying.getActiveTechniqueByCategory(includeSystem).map { map =>
      implicit val o: Ordering[List[ActiveTechniqueCategoryId]] = ActiveTechniqueCategoryOrdering
      SortedMap(map.collect {
        case (path, cwat) if checkTenant.check(cwat.category).isDefined =>
          path -> cwat.modify(_.templates).setTo(checkTenant.filter(cwat.templates))
      }.toSeq*)
    }
  }

  override def getActiveTechniqueByActiveTechnique(
      id: ActiveTechniqueId
  )(using qc: QueryContext): IOResult[Option[ActiveTechnique]] = {
    underlying.getActiveTechniqueByActiveTechnique(id).map(checkTenant.flatMap(_))
  }

  override def getActiveTechnique(techniqueName: TechniqueName)(using qc: QueryContext): IOResult[Option[ActiveTechnique]] = {
    underlying.getActiveTechnique(techniqueName).map(checkTenant.flatMap(_))
  }

  override def getAllActiveTechniqueCategories(includeSystem: Boolean)(using
      qc: QueryContext
  ): IOResult[Seq[ActiveTechniqueCategory]] = {
    underlying.getAllActiveTechniqueCategories(includeSystem).map(checkTenant.filter(_))
  }

  override def getActiveTechniqueCategory(id: ActiveTechniqueCategoryId)(using
      qc: QueryContext
  ): IOResult[Option[ActiveTechniqueCategory]] = {
    underlying.getActiveTechniqueCategory(id).map(checkTenant.flatMap(_))
  }

  // Navigation methods (delegate, tenant filtering would break semantic)
  override def activeTechniqueBreadCrump(id: ActiveTechniqueId)(using qc: QueryContext): IOResult[List[ActiveTechniqueCategory]] =
    underlying.activeTechniqueBreadCrump(id)

  // active category only has ID for children, we can't filter yet
  override def getActiveTechniqueLibrary(using qc: QueryContext): IOResult[ActiveTechniqueCategory] = {
    underlying.getActiveTechniqueLibrary
      .map(checkTenant.check(_))
      .notOptional(
        s"'${qc.actor}' doesn't have access to root directive category"
      )
  }

  override def getParentActiveTechniqueCategory(
      id: ActiveTechniqueCategoryId
  )(using qc: QueryContext): IOResult[ActiveTechniqueCategory] = {
    underlying
      .getParentActiveTechniqueCategory(id)
      .map(checkTenant.check(_))
      .notOptional(
        s"'${qc.actor}' doesn't have access to directive category '${id.value}''"
      )
  }

  override def getParentsForActiveTechniqueCategory(id: ActiveTechniqueCategoryId)(using
      qc: QueryContext
  ): IOResult[List[ActiveTechniqueCategory]] = {
    underlying.getParentsForActiveTechniqueCategory(id).map(checkTenant.filter(_))
  }

  override def getParentsForActiveTechnique(id: ActiveTechniqueId)(using qc: QueryContext): IOResult[ActiveTechniqueCategory] = {
    underlying
      .getParentsForActiveTechnique(id)
      .map(checkTenant.check(_))
      .notOptional(
        s"'${qc.actor}' doesn't have access to parent of category '${id.value}''"
      )
  }

  override def containsDirective(id: ActiveTechniqueCategoryId): zio.UIO[Boolean] = {
    underlying.containsDirective(id)
  }
}

class WoTenantDirectiveRepo(
    checkTenant: TenantCheckLogic,
    tenantRepo:  TenantService,
    underlying:  WoDirectiveRepository,
    roRepo:      RoDirectiveRepository
) extends RoDirectiveRepository with WoDirectiveRepository {

  // the read part is just delegated to the (tenant-filtering) `roRepo`
  export roRepo.*

  private def saveInternal(inActiveTechniqueId: ActiveTechniqueId, directive: Directive, system: Boolean)(using
      cc: ChangeContext
  ): IOResult[Option[DirectiveSaveDiff]] = {
    given QueryContext = cc.toQC
    for {
      parentAt <- roRepo
                    .getActiveTechniqueByActiveTechnique(inActiveTechniqueId)
                    .notOptional(s"Can not find active technique with id '${inActiveTechniqueId.value}'")
      _        <- cc.accessGrant.canModifyOrFail(parentAt)(ZIO.unit)
      oldDir   <- roRepo.getActiveTechniqueAndDirective(DirectiveId(directive.id.uid))
      status   <- tenantRepo.getStatus
      result   <- checkTenant.manageUpdate(oldDir.map(_._2), directive, cc, status) { dir =>
                    if (system) underlying.saveSystemDirective(inActiveTechniqueId, dir)
                    else underlying.saveDirective(inActiveTechniqueId, dir)
                  }
    } yield result
  }

  override def saveDirective(inActiveTechniqueId: ActiveTechniqueId, directive: Directive)(using
      cc: ChangeContext
  ): IOResult[Option[DirectiveSaveDiff]] =
    saveInternal(inActiveTechniqueId, directive, system = false)

  override def saveSystemDirective(inActiveTechniqueId: ActiveTechniqueId, directive: Directive)(using
      cc: ChangeContext
  ): IOResult[Option[DirectiveSaveDiff]] =
    saveInternal(inActiveTechniqueId, directive, system = true)

  override def delete(id: DirectiveUid)(using cc: ChangeContext): IOResult[Option[DeleteDirectiveDiff]] = {
    given QueryContext = cc.toQC
    for {
      existing <- roRepo.getActiveTechniqueAndDirective(DirectiveId(id))
      _        <- ZIO.foreach(existing)(p => checkTenant.checkDelete(p._2, cc).toIO)
      result   <- underlying.delete(id)
    } yield result
  }

  override def deleteSystemDirective(id: DirectiveUid)(using cc: ChangeContext): IOResult[Option[DeleteDirectiveDiff]] = {
    given QueryContext = cc.toQC
    for {
      existing <- roRepo.getActiveTechniqueAndDirective(DirectiveId(id))
      _        <- ZIO.foreach(existing)(p => checkTenant.checkDelete(p._2, cc).toIO)
      result   <- underlying.deleteSystemDirective(id)
    } yield result
  }

  override def addTechniqueInUserLibrary(
      categoryId:    ActiveTechniqueCategoryId,
      techniqueName: TechniqueName,
      versions:      Seq[TechniqueVersion],
      policyTypes:   PolicyTypes
  )(implicit cc: ChangeContext): IOResult[ActiveTechnique] = {
    given QueryContext = cc.toQC
    val probe          = ActiveTechnique(
      ActiveTechniqueId(techniqueName.value),
      techniqueName,
      AcceptationDateTime(Map()),
      policyTypes = policyTypes,
      security = None
    )
    for {
      parentCat <- roRepo.getActiveTechniqueCategory(categoryId).notOptional(s"Category '${categoryId.value}' was not found")
      _         <- cc.accessGrant.canModifyOrFail(parentCat)(ZIO.unit)
      status    <- tenantRepo.getStatus
      result    <- checkTenant.manageCreate(probe, cc, status) { _ =>
                     underlying.addTechniqueInUserLibrary(categoryId, techniqueName, versions, policyTypes)
                   }
    } yield result
  }

  override def move(id: ActiveTechniqueId, newCategoryId: ActiveTechniqueCategoryId)(implicit
      cc: ChangeContext
  ): IOResult[ActiveTechniqueId] = {
    given QueryContext = cc.toQC
    for {
      at      <- roRepo.getActiveTechniqueByActiveTechnique(id).notOptional(s"Active technique '${id.value}' was not found")
      _       <- cc.accessGrant.canModifyOrFail(at)(ZIO.unit)
      destCat <- roRepo.getActiveTechniqueCategory(newCategoryId).notOptional(s"Category '${newCategoryId.value}' was not found")
      _       <- cc.accessGrant.canModifyOrFail(destCat)(ZIO.unit)
      result  <- underlying.move(id, newCategoryId)
    } yield result
  }

  override def changeStatus(id: ActiveTechniqueId, status: Boolean)(implicit cc: ChangeContext): IOResult[ActiveTechniqueId] = {
    given QueryContext = cc.toQC
    for {
      at     <- roRepo.getActiveTechniqueByActiveTechnique(id).notOptional(s"Active technique '${id.value}' was not found")
      _      <- cc.accessGrant.canModifyOrFail(at)(ZIO.unit)
      result <- underlying.changeStatus(id, status)
    } yield result
  }

  override def setAcceptationDatetimes(id: ActiveTechniqueId, datetimes: Map[TechniqueVersion, Instant])(implicit
      cc: ChangeContext
  ): IOResult[ActiveTechniqueId] = {
    given QueryContext = cc.toQC
    for {
      at     <- roRepo.getActiveTechniqueByActiveTechnique(id).notOptional(s"Active technique '${id.value}' was not found")
      _      <- cc.accessGrant.canModifyOrFail(at)(ZIO.unit)
      result <- underlying.setAcceptationDatetimes(id, datetimes)
    } yield result
  }

  override def deleteActiveTechnique(id: ActiveTechniqueId)(using cc: ChangeContext): IOResult[ActiveTechniqueId] = {
    given QueryContext = cc.toQC
    for {
      existing <- roRepo.getActiveTechniqueByActiveTechnique(id)
      _        <- ZIO.foreach(existing)(at => checkTenant.checkDelete(at, cc).toIO)
      result   <- underlying.deleteActiveTechnique(id)
    } yield result
  }

  override def addActiveTechniqueCategory(that: ActiveTechniqueCategory, into: ActiveTechniqueCategoryId)(implicit
      cc: ChangeContext
  ): IOResult[ActiveTechniqueCategory] = {
    given QueryContext = cc.toQC
    for {
      parentCat <- roRepo.getActiveTechniqueCategory(into).notOptional(s"Category '${into.value}' was not found")
      _         <- cc.accessGrant.canModifyOrFail(parentCat)(ZIO.unit)
      status    <- tenantRepo.getStatus
      result    <- checkTenant.manageCreate(that, cc, status)(cat => underlying.addActiveTechniqueCategory(cat, into))
    } yield result
  }

  override def saveActiveTechniqueCategory(category: ActiveTechniqueCategory)(implicit
      cc: ChangeContext
  ): IOResult[ActiveTechniqueCategory] = {
    given QueryContext = cc.toQC
    for {
      old    <- roRepo.getActiveTechniqueCategory(category.id).notOptional(s"Category '${category.id.value}' was not found")
      status <- tenantRepo.getStatus
      result <- checkTenant.manageUpdate(Some(old), category, cc, status)(cat => underlying.saveActiveTechniqueCategory(cat))
    } yield result
  }

  override def deleteCategory(id: ActiveTechniqueCategoryId, checkEmpty: Boolean)(implicit
      cc: ChangeContext
  ): IOResult[ActiveTechniqueCategoryId] = {
    given QueryContext = cc.toQC
    for {
      existing <- roRepo.getActiveTechniqueCategory(id)
      _        <- ZIO.foreach(existing)(cat => checkTenant.checkDelete(cat, cc).toIO)
      result   <- underlying.deleteCategory(id, checkEmpty)
    } yield result
  }

  override def move(
      categoryId:    ActiveTechniqueCategoryId,
      intoParent:    ActiveTechniqueCategoryId,
      optionNewName: Option[ActiveTechniqueCategoryId]
  )(implicit cc: ChangeContext): IOResult[ActiveTechniqueCategoryId] = {
    given QueryContext = cc.toQC
    for {
      cat       <- roRepo.getActiveTechniqueCategory(categoryId).notOptional(s"Category '${categoryId.value}' was not found")
      _         <- cc.accessGrant.canModifyOrFail(cat)(ZIO.unit)
      parentCat <- roRepo.getActiveTechniqueCategory(intoParent).notOptional(s"Category '${intoParent.value}' was not found")
      _         <- cc.accessGrant.canModifyOrFail(parentCat)(ZIO.unit)
      result    <- underlying.move(categoryId, intoParent, optionNewName)
    } yield result
  }
}

// ----- Groups -----------------------------------

class RoTenantNodeGroupRepo(
    checkTenant: TenantCheckLogic,
    underlying:  RoNodeGroupRepository
) extends RoNodeGroupRepository {

  private def filterFNGC(c: FullNodeGroupCategory)(using qc: QueryContext): Option[FullNodeGroupCategory] = {
    checkTenant
      .check(c)
      .map { cat =>
        cat
          .modify(_.subCategories)
          .setTo(cat.subCategories.flatMap(filterFNGC))
          .modify(_.targetInfos)
          .setTo(checkTenant.filter(cat.targetInfos))
      }
  }

  override def getFullGroupLibrary()(implicit qc: QueryContext): IOResult[FullNodeGroupCategory] = {
    underlying
      .getFullGroupLibrary()
      .map(filterFNGC)
      .notOptional(s"Root group library category is not visible by '${qc.actor.name}'")
  }

  override def getNodeGroupOpt(id: NodeGroupId)(implicit qc: QueryContext): IOResult[Option[(NodeGroup, NodeGroupCategoryId)]] = {
    // only the group holds a security tag, the category id does not
    underlying.getNodeGroupOpt(id).map(_.flatMap { case (g, c) => checkTenant.check(g).map(_ => (g, c)) })
  }

  override def getAll()(using qc: QueryContext): IOResult[Seq[NodeGroup]] =
    underlying.getAll().map(checkTenant.filter(_))

  override def getAllByIds(ids: Seq[NodeGroupId])(using qc: QueryContext): IOResult[Seq[NodeGroup]] =
    underlying.getAllByIds(ids).map(checkTenant.filter(_))

  override def getAllNodeIds()(using qc: QueryContext): IOResult[Map[NodeGroupId, Set[NodeId]]] =
    underlying.getAll().map(gs => checkTenant.collect(gs)(g => g.id -> g.serverList).toMap)

  override def getAllNodeIdsChunk()(using qc: QueryContext): IOResult[Map[NodeGroupId, Chunk[NodeId]]] =
    underlying.getAll().map(gs => checkTenant.collect(gs)(g => g.id -> Chunk.fromIterable(g.serverList)).toMap)

  override def getAllGroupCategories(includeSystem: Boolean)(using qc: QueryContext): IOResult[Seq[NodeGroupCategory]] =
    underlying.getAllGroupCategories(includeSystem).map(checkTenant.filter(_))

  override def getAllNonSystemCategories()(using qc: QueryContext): IOResult[Seq[NodeGroupCategory]] =
    underlying.getAllNonSystemCategories().map(checkTenant.filter(_))

  override def getGroupsByCategory(
      includeSystem: Boolean
  )(implicit qc: QueryContext): IOResult[SortedMap[List[NodeGroupCategoryId], CategoryAndNodeGroup]] = {
    underlying.getGroupsByCategory(includeSystem).map { map =>
      implicit val o: Ordering[List[NodeGroupCategoryId]] = NodeGroupCategoryOrdering
      SortedMap(map.collect {
        case (path, cag) if checkTenant.check(cag.category).isDefined =>
          path -> cag.modify(_.groups).setTo(checkTenant.filter(cag.groups))
      }.toSeq*)
    }
  }

  override def findGroupWithAnyMember(nodeIds: Seq[NodeId])(using qc: QueryContext): IOResult[Seq[NodeGroupId]] = {
    underlying.findGroupWithAnyMember(nodeIds).flatMap { ids =>
      ZIO.filter(ids)(id => underlying.getNodeGroupOpt(id).map(_.exists(g => checkTenant.check(g._1).isDefined)))
    }
  }

  override def findGroupWithAllMember(nodeIds: Seq[NodeId])(using qc: QueryContext): IOResult[Seq[NodeGroupId]] = {
    underlying.findGroupWithAllMember(nodeIds).flatMap { ids =>
      ZIO.filter(ids)(id => underlying.getNodeGroupOpt(id).map(_.exists(g => checkTenant.check(g._1).isDefined)))
    }
  }

  override def getCategoryHierarchy(using qc: QueryContext): IOResult[SortedMap[List[NodeGroupCategoryId], NodeGroupCategory]] = {
    underlying.getCategoryHierarchy.map { map =>
      implicit val o: Ordering[List[NodeGroupCategoryId]] = GroupCategoryRepositoryOrdering
      SortedMap(map.filter { case (_, cat) => checkTenant.check(cat).isDefined }.toSeq*)
    }
  }

  override def categoryExists(id: NodeGroupCategoryId)(using qc: QueryContext): IOResult[Boolean] =
    underlying.categoryExists(id)

  override def getNodeGroupCategory(id: NodeGroupId)(using qc: QueryContext): IOResult[NodeGroupCategory] = {
    underlying
      .getNodeGroupCategory(id)
      .map(checkTenant.check(_))
      .notOptional(s"'${qc.actor.name}' doesn't have access to the parent category of group '${id.serialize}'")
  }

  override def getGroupCategory(id: NodeGroupCategoryId)(using qc: QueryContext): IOResult[NodeGroupCategory] = {
    underlying
      .getGroupCategory(id)
      .map(checkTenant.check(_))
      .notOptional(s"'${qc.actor.name}' doesn't have access to group category '${id.value}'")
  }

  override def getParentGroupCategory(id: NodeGroupCategoryId)(using qc: QueryContext): IOResult[NodeGroupCategory] = {
    underlying
      .getParentGroupCategory(id)
      .map(checkTenant.check(_))
      .notOptional(s"'${qc.actor.name}' doesn't have access to the parent of group category '${id.value}'")
  }

  override def getParents_NodeGroupCategory(id: NodeGroupCategoryId)(using qc: QueryContext): IOResult[List[NodeGroupCategory]] =
    underlying.getParents_NodeGroupCategory(id).map(checkTenant.filter(_))

  // deprecated method returning a plain value: the root category is always visible (`Open`), so delegate
  override def getRootCategory()(using qc: QueryContext): NodeGroupCategory =
    underlying.getRootCategory()

  override def getRootCategoryPure()(using qc: QueryContext): IOResult[NodeGroupCategory] = {
    underlying
      .getRootCategoryPure()
      .map(checkTenant.check(_))
      .notOptional(s"'${qc.actor.name}' doesn't have access to the root group category")
  }
}

class WoTenantNodeGroupRepo(
    checkTenant: TenantCheckLogic,
    tenantRepo:  TenantService,
    underlying:  WoNodeGroupRepository,
    roRepo:      RoNodeGroupRepository
) extends RoNodeGroupRepository with WoNodeGroupRepository {

  // the read part is just delegated to the (tenant-filtering) `roRepo`
  export roRepo.*

  override def create(nodeGroup: NodeGroup, into: NodeGroupCategoryId)(implicit cc: ChangeContext): IOResult[AddNodeGroupDiff] = {
    given QueryContext = cc.toQC
    for {
      parentCat <- roRepo.getGroupCategory(into)
      _         <- cc.accessGrant.canModifyOrFail(parentCat)(ZIO.unit)
      status    <- tenantRepo.getStatus
      result    <- checkTenant.manageCreate(nodeGroup, cc, status)(ng => underlying.create(ng, into))
    } yield result
  }

  override def update(nodeGroup: NodeGroup)(implicit cc: ChangeContext): IOResult[Option[ModifyNodeGroupDiff]] = {
    for {
      existingOpt <- roRepo.getNodeGroupOpt(nodeGroup.id)(using cc.toQC)
      status      <- tenantRepo.getStatus
      result      <- checkTenant.manageUpdate(existingOpt.map(_._1), nodeGroup, cc, status)(ng => underlying.update(ng))
    } yield result
  }

  override def updateSystemGroup(nodeGroup: NodeGroup)(implicit cc: ChangeContext): IOResult[Option[ModifyNodeGroupDiff]] = {
    for {
      existingOpt <- roRepo.getNodeGroupOpt(nodeGroup.id)(using cc.toQC)
      status      <- tenantRepo.getStatus
      result      <- checkTenant.manageUpdate(existingOpt.map(_._1), nodeGroup, cc, status)(ng => underlying.updateSystemGroup(ng))
    } yield result
  }

  override def updateDynGroupNodes(group: NodeGroup)(implicit cc: ChangeContext): IOResult[Option[ModifyNodeGroupDiff]] = {
    for {
      existingOpt <- roRepo.getNodeGroupOpt(group.id)(using cc.toQC)
      status      <- tenantRepo.getStatus
      result      <- checkTenant.manageUpdate(existingOpt.map(_._1), group, cc, status)(ng => underlying.updateDynGroupNodes(ng))
    } yield result
  }

  override def updateDiffNodes(
      nodeGroupId: NodeGroupId,
      add:         List[NodeId],
      delete:      List[NodeId]
  )(implicit cc: ChangeContext): IOResult[Option[ModifyNodeGroupDiff]] = {
    for {
      existingOpt <- roRepo.getNodeGroupOpt(nodeGroupId)(using cc.toQC)
      result      <- existingOpt match {
                       case None           => underlying.updateDiffNodes(nodeGroupId, add, delete)
                       case Some((old, _)) =>
                         val newGroup = old.modify(_.serverList).setTo((old.serverList -- delete) ++ add)
                         for {
                           status <- tenantRepo.getStatus
                           r      <- checkTenant.manageUpdate(Some(old), newGroup, cc, status) { _ =>
                                       underlying.updateDiffNodes(nodeGroupId, add, delete)
                                     }
                         } yield r
                     }
    } yield result
  }

  override def move(nodeGroupId: NodeGroupId, containerId: NodeGroupCategoryId)(implicit
      cc: ChangeContext
  ): IOResult[Option[ModifyNodeGroupDiff]] = {
    given QueryContext = cc.toQC
    for {
      existingOpt <- roRepo.getNodeGroupOpt(nodeGroupId)
      existing    <- existingOpt.map(_._1).notOptional(s"Group ${nodeGroupId.serialize} not found")
      _           <- cc.accessGrant.canModifyOrFail(existing)(ZIO.unit)
      destCat     <- roRepo.getGroupCategory(containerId)
      _           <- cc.accessGrant.canModifyOrFail(destCat)(ZIO.unit)
      result      <- underlying.move(nodeGroupId, containerId)
    } yield result
  }

  override def delete(id: NodeGroupId)(implicit cc: ChangeContext): IOResult[DeleteNodeGroupDiff] = {
    given QueryContext = cc.toQC
    for {
      existingOpt <- roRepo.getNodeGroupOpt(id)
      existing    <- existingOpt.map(_._1).notOptional(s"Group ${id.serialize} not found")
      _           <- checkTenant.checkDelete(existing, cc).toIO
      result      <- underlying.delete(id)
    } yield result
  }

  override def addGroupCategoryToCategory(that: NodeGroupCategory, into: NodeGroupCategoryId)(implicit
      cc: ChangeContext
  ): IOResult[NodeGroupCategory] = {
    given QueryContext = cc.toQC
    for {
      parent <- roRepo.getGroupCategory(into)
      _      <- cc.accessGrant.canModifyOrFail(parent)(ZIO.unit)
      status <- tenantRepo.getStatus
      result <- checkTenant.manageUpdate[NodeGroupCategory, NodeGroupCategory, NodeGroupCategory](None, that, cc, status) { cat =>
                  underlying.addGroupCategoryToCategory(cat, into)
                }
    } yield result
  }

  override def saveGroupCategory(category: NodeGroupCategory)(implicit cc: ChangeContext): IOResult[NodeGroupCategory] = {
    given QueryContext = cc.toQC
    for {
      old    <- roRepo.getGroupCategory(category.id)
      _      <- cc.accessGrant.canModifyOrFail(old)(ZIO.unit)
      status <- tenantRepo.getStatus
      result <- checkTenant.manageUpdate(Some(old), category, cc, status)(cat => underlying.saveGroupCategory(cat))
    } yield result
  }

  override def saveGroupCategory(category: NodeGroupCategory, containerId: NodeGroupCategoryId)(implicit
      cc: ChangeContext
  ): IOResult[NodeGroupCategory] =
    underlying.saveGroupCategory(category, containerId)

  override def delete(id: NodeGroupCategoryId, checkEmpty: Boolean)(implicit
      cc: ChangeContext
  ): IOResult[NodeGroupCategoryId] = {
    given QueryContext = cc.toQC
    for {
      catOpt <- roRepo.getGroupCategory(id).option
      _      <- ZIO.foreach(catOpt)(cat => checkTenant.checkDelete(cat, cc).toIO)
      result <- underlying.delete(id, checkEmpty)
    } yield result
  }

  override def createPolicyServerTarget(target: PolicyServerTarget)(implicit cc: ChangeContext): IOResult[LDIFChangeRecord] =
    underlying.createPolicyServerTarget(target)

  override def deletePolicyServerTarget(
      policyServer: PolicyServerTarget
  )(implicit cc: ChangeContext): IOResult[PolicyServerTarget] =
    underlying.deletePolicyServerTarget(policyServer)
}

// ----- Rules -----------------------------------

class RoTenantRuleRepo(
    checkTenant: TenantCheckLogic,
    underlying:  RoRuleRepository
) extends RoRuleRepository {

  override def getOpt(ruleId: RuleId)(using qc: QueryContext): IOResult[Option[Rule]] =
    underlying.getOpt(ruleId).map(checkTenant.flatMap(_))

  override def getAll(includeSystem: Boolean)(using qc: QueryContext): IOResult[Seq[Rule]] =
    underlying.getAll(includeSystem).map(checkTenant.filter(_))

  override def getIds(includeSystem: Boolean)(using qc: QueryContext): IOResult[Set[RuleId]] =
    getAll(includeSystem).map(_.map(_.id).toSet)
}

class WoTenantRuleRepo(
    checkTenant: TenantCheckLogic,
    tenantRepo:  TenantService,
    underlying:  WoRuleRepository,
    roRepo:      RoRuleRepository
) extends RoRuleRepository with WoRuleRepository {

  // the read part is just delegated to the (tenant-filtering) `roRepo`
  export roRepo.*

  override def create(rule: Rule)(using cc: ChangeContext): IOResult[AddRuleDiff] = {
    for {
      status <- tenantRepo.getStatus
      result <- checkTenant.manageCreate(rule, cc, status)(r => underlying.create(r))
    } yield result
  }

  override def update(rule: Rule)(using cc: ChangeContext): IOResult[Option[ModifyRuleDiff]] = {
    for {
      existing <- roRepo.getOpt(rule.id)(using cc.toQC)
      status   <- tenantRepo.getStatus
      result   <- checkTenant.manageUpdate(existing, rule, cc, status)(r => underlying.update(r))
    } yield result
  }

  override def updateSystem(rule: Rule)(using cc: ChangeContext): IOResult[Option[ModifyRuleDiff]] = {
    for {
      existing <- roRepo.getOpt(rule.id)(using cc.toQC)
      status   <- tenantRepo.getStatus
      result   <- checkTenant.manageUpdate(existing, rule, cc, status)(r => underlying.updateSystem(r))
    } yield result
  }

  override def load(rule: Rule)(using cc: ChangeContext): IOResult[Unit] = {
    for {
      existing <- roRepo.getOpt(rule.id)(using cc.toQC)
      status   <- tenantRepo.getStatus
      result   <- checkTenant.manageUpdate(existing, rule, cc, status)(r => underlying.load(r))
    } yield result
  }

  override def unload(ruleId: RuleId)(using cc: ChangeContext): IOResult[Unit] = {
    for {
      existing <- roRepo.getOpt(ruleId)(using cc.toQC)
      _        <- ZIO.foreach(existing)(r => checkTenant.checkDelete(r, cc).toIO)
      result   <- underlying.unload(ruleId)
    } yield result
  }

  override def delete(id: RuleId)(using cc: ChangeContext): IOResult[DeleteRuleDiff] = {
    for {
      existing <- roRepo.getOpt(id)(using cc.toQC)
      _        <- ZIO.foreach(existing)(r => checkTenant.checkDelete(r, cc).toIO)
      result   <- underlying.delete(id)
    } yield result
  }

  override def deleteSystemRule(id: RuleId)(using cc: ChangeContext): IOResult[DeleteRuleDiff] = {
    for {
      existing <- roRepo.getOpt(id)(using cc.toQC)
      _        <- ZIO.foreach(existing)(r => checkTenant.checkDelete(r, cc).toIO)
      result   <- underlying.deleteSystemRule(id)
    } yield result
  }

  override def swapRules(newRules: Seq[Rule]): IOResult[RuleArchiveId] =
    underlying.swapRules(newRules)

  override def deleteSavedRuleArchiveId(saveId: RuleArchiveId): IOResult[Unit] =
    underlying.deleteSavedRuleArchiveId(saveId)
}

// ----- Properties -----------------------------------

class RoTenantParameterRepo(
    checkTenant: TenantCheckLogic,
    underlying:  RoParameterRepository
) extends RoParameterRepository {

  override def getGlobalParameter(parameterName: String)(using qc: QueryContext): IOResult[Option[GlobalParameter]] =
    underlying.getGlobalParameter(parameterName).map(checkTenant.flatMap(_))

  override def getAllGlobalParameters()(using qc: QueryContext): IOResult[Seq[GlobalParameter]] =
    underlying.getAllGlobalParameters().map(checkTenant.filter(_))
}

class WoTenantParameterRepo(
    checkTenant: TenantCheckLogic,
    tenantRepo:  TenantService,
    underlying:  WoParameterRepository,
    roRepo:      RoParameterRepository
) extends RoParameterRepository with WoParameterRepository {

  // the read part is just delegated to the (tenant-filtering) `roRepo`
  export roRepo.*

  override def saveParameter(parameter: GlobalParameter)(using cc: ChangeContext): IOResult[AddGlobalParameterDiff] = {
    for {
      status <- tenantRepo.getStatus
      result <- checkTenant.manageCreate(parameter, cc, status)(p => underlying.saveParameter(p))
    } yield result
  }

  override def updateParameter(
      parameter: GlobalParameter
  )(using cc: ChangeContext): IOResult[Option[ModifyGlobalParameterDiff]] = {
    for {
      existing <- roRepo.getGlobalParameter(parameter.name)(using cc.toQC)
      status   <- tenantRepo.getStatus
      result   <- checkTenant.manageUpdate(existing, parameter, cc, status)(p => underlying.updateParameter(p))
    } yield result
  }

  override def delete(
      parameterName: String,
      provider:      Option[PropertyProvider]
  )(using cc: ChangeContext): IOResult[Option[DeleteGlobalParameterDiff]] = {
    for {
      existing <- roRepo.getGlobalParameter(parameterName)(using cc.toQC)
      _        <- ZIO.foreach(existing)(p => checkTenant.checkDelete(p, cc).toIO)
      result   <- underlying.delete(parameterName, provider)
    } yield result
  }

  override def swapParameters(newParameters: Seq[GlobalParameter]): IOResult[ParameterArchiveId] =
    underlying.swapParameters(newParameters)

  override def deleteSavedParametersArchiveId(saveId: ParameterArchiveId): IOResult[Unit] =
    underlying.deleteSavedParametersArchiveId(saveId)
}

// ----- Rule categories -----------------------------------

class RoTenantRuleCategoryRepo(
    checkTenant: TenantCheckLogic,
    underlying:  RoRuleCategoryRepository
) extends RoRuleCategoryRepository {

  // recursively prune the children the current security context can not see; the root category
  // itself is always kept (it is the library root, like the other configuration object libraries).
  private def filterTree(cat: RuleCategory)(using qc: QueryContext): RuleCategory = {
    cat.modify(_.childs).setTo(cat.childs.collect { case c if qc.accessGrant.canSee(c.security) => filterTree(c) })
  }

  override def get(id: RuleCategoryId)(using qc: QueryContext): IOResult[RuleCategory] =
    underlying.get(id)

  override def getRootCategory()(using qc: QueryContext): IOResult[RuleCategory] =
    underlying.getRootCategory().map(filterTree)
}

class WoTenantRuleCategoryRepo(
    checkTenant: TenantCheckLogic,
    tenantRepo:  TenantService,
    underlying:  WoRuleCategoryRepository,
    roRepo:      RoRuleCategoryRepository
) extends RoRuleCategoryRepository with WoRuleCategoryRepository {

  // the read part is just delegated to the (tenant-filtering) `roRepo`
  export roRepo.*

  override def create(that: RuleCategory, into: RuleCategoryId)(implicit cc: ChangeContext): IOResult[RuleCategory] = {
    given QueryContext = cc.toQC
    for {
      parent <- roRepo.get(into)
      _      <- cc.accessGrant.canModifyOrFail(parent)(ZIO.unit)
      status <- tenantRepo.getStatus
      result <- checkTenant.manageCreate(that, cc, status)(cat => underlying.create(cat, into))
    } yield result
  }

  override def updateAndMove(that: RuleCategory, into: RuleCategoryId)(implicit cc: ChangeContext): IOResult[RuleCategory] = {
    given QueryContext = cc.toQC
    for {
      old    <- roRepo.get(that.id)
      parent <- roRepo.get(into)
      _      <- cc.accessGrant.canModifyOrFail(parent)(ZIO.unit)
      status <- tenantRepo.getStatus
      result <- checkTenant.manageUpdate(Some(old), that, cc, status)(cat => underlying.updateAndMove(cat, into))
    } yield result
  }

  override def delete(category: RuleCategoryId, checkEmpty: Boolean)(implicit cc: ChangeContext): IOResult[RuleCategoryId] = {
    given QueryContext = cc.toQC
    for {
      old    <- roRepo.get(category).option
      _      <- ZIO.foreach(old)(cat => checkTenant.checkDelete(cat, cc).toIO)
      result <- underlying.delete(category, checkEmpty)
    } yield result
  }
}
