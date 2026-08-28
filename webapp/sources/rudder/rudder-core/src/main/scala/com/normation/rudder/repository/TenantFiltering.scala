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
import zio.syntax.*

/*
 * This file contains the tenant filtering logic for technique, directive, rule, group, parameter repositories.
 * The main idea:
 *   - for all read operation, we post-filter all objects with the tenant scoping
 *   - for all write operation:
 *       - we check for an existing with object to see what tenants it could get,
 *       - then we check for see, and for write
 */

/*
 * Note on authorization: repositories never reach into `cc.accessGrant.*` directly, and every write goes through the
 * one `TenantCheckLogic` operation corresponding to its case: `manageCreate`, `manageUpdate`, `manageSave`,
 * `manageModify`, `manageMove`, `manageDelete`.
 * This that operation that checks all the tenant consistency, using system-level `QueryContext` to check
 * object existence and properties if needed.
 *
 * `checkAdmin` remains for admin-only operations that have no `HasSecurityTag` object at all (e.g. policy
 * server targets).
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

  // Navigation method: the ancestor path can not be meaningfully pruned (a partial breadcrumb is nonsense),
  // so instead gate on the LEAF - if the caller can not see the active technique, do not reveal its ancestry
  // (no existence oracle). When it is visible, the full path to root is contextually fine.
  override def activeTechniqueBreadCrump(
      id: ActiveTechniqueId
  )(using qc: QueryContext): IOResult[List[ActiveTechniqueCategory]] = {
    underlying.getActiveTechniqueByActiveTechnique(id).map(checkTenant.flatMap(_)).flatMap {
      case None    => List.empty[ActiveTechniqueCategory].succeed
      case Some(_) => underlying.activeTechniqueBreadCrump(id)
    }
  }

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
    underlying:  WoDirectiveRepository,
    roRepo:      RoDirectiveRepository
) extends RoDirectiveRepository with WoDirectiveRepository {

  // the read part is just delegated to the (tenant-filtering) `roRepo`
  export roRepo.*

  private def saveInternal(inActiveTechniqueId: ActiveTechniqueId, directive: Directive, system: Boolean)(using
      cc: ChangeContext
  ): IOResult[Option[DirectiveSaveDiff]] = {
    // a directive save is an upsert: the active technique it is created under is its container
    checkTenant.manageSave(
      directive,
      roRepo.getActiveTechniqueAndDirective(DirectiveId(directive.id.uid)).map(_.map(_._2)),
      roRepo
        .getActiveTechniqueByActiveTechnique(inActiveTechniqueId)
        .notOptional(s"Can not find active technique with id '${inActiveTechniqueId.value}'")
    ) { dir =>
      if (system) underlying.saveSystemDirective(inActiveTechniqueId, dir)
      else underlying.saveDirective(inActiveTechniqueId, dir)
    }
  }

  override def saveDirective(inActiveTechniqueId: ActiveTechniqueId, directive: Directive)(using
      cc: ChangeContext
  ): IOResult[Option[DirectiveSaveDiff]] =
    saveInternal(inActiveTechniqueId, directive, system = false)

  override def saveSystemDirective(inActiveTechniqueId: ActiveTechniqueId, directive: Directive)(using
      cc: ChangeContext
  ): IOResult[Option[DirectiveSaveDiff]] =
    saveInternal(inActiveTechniqueId, directive, system = true)

  // a restore may put back a narrower tag: that exception is defined in `manageRestore`
  override def restoreDirective(inActiveTechniqueId: ActiveTechniqueId, directive: Directive)(using
      cc: ChangeContext
  ): IOResult[Option[DirectiveSaveDiff]] = {
    checkTenant.manageRestore(
      directive,
      roRepo.getActiveTechniqueAndDirective(DirectiveId(directive.id.uid)).map(_.map(_._2)),
      roRepo
        .getActiveTechniqueByActiveTechnique(inActiveTechniqueId)
        .notOptional(s"Can not find active technique with id '${inActiveTechniqueId.value}'")
    )(dir => underlying.saveDirective(inActiveTechniqueId, dir))
  }

  // deleting a directive that does not exist is a no-op
  override def delete(id: DirectiveUid)(using cc: ChangeContext): IOResult[Option[DeleteDirectiveDiff]] = {
    checkTenant.manageDelete(
      roRepo.getActiveTechniqueAndDirective(DirectiveId(id)).map(_.map(_._2)),
      IfAbsent.Noop(Option.empty[DeleteDirectiveDiff])
    )(_ => underlying.delete(id))
  }

  override def deleteSystemDirective(id: DirectiveUid)(using cc: ChangeContext): IOResult[Option[DeleteDirectiveDiff]] = {
    checkTenant.manageDelete(
      roRepo.getActiveTechniqueAndDirective(DirectiveId(id)).map(_.map(_._2)),
      IfAbsent.Noop(Option.empty[DeleteDirectiveDiff])
    )(_ => underlying.deleteSystemDirective(id))
  }

  override def addTechniqueInUserLibrary(
      categoryId:    ActiveTechniqueCategoryId,
      techniqueName: TechniqueName,
      versions:      Seq[TechniqueVersion],
      policyTypes:   PolicyTypes,
      security:      Option[SecurityTag]
  )(implicit cc: ChangeContext): IOResult[ActiveTechnique] = {
    // the active technique about to be created, as the tenant law needs to see it: `manageCreate` gives back
    // the same object with the tag the actor may actually give it, and THAT tag is the one persisted.
    val created = ActiveTechnique(
      ActiveTechniqueId(techniqueName.value),
      techniqueName,
      AcceptationDateTime(Map()),
      policyTypes = policyTypes,
      security = security
    )
    checkTenant.manageCreate(
      created,
      roRepo.getActiveTechniqueCategory(categoryId).notOptional(s"Category '${categoryId.value}' was not found")
    )(at => underlying.addTechniqueInUserLibrary(categoryId, techniqueName, versions, policyTypes, at.security))
  }

  override def move(id: ActiveTechniqueId, newCategoryId: ActiveTechniqueCategoryId)(implicit
      cc: ChangeContext
  ): IOResult[ActiveTechniqueId] = {
    checkTenant.manageMove(
      roRepo.getActiveTechniqueByActiveTechnique(id),
      roRepo.getActiveTechniqueCategory(newCategoryId).notOptional(s"Category '${newCategoryId.value}' was not found"),
      IfAbsent.Fail(s"Active technique '${id.value}' was not found")
    )(_ => underlying.move(id, newCategoryId))
  }

  override def changeStatus(id: ActiveTechniqueId, status: Boolean)(implicit cc: ChangeContext): IOResult[ActiveTechniqueId] = {
    checkTenant.manageModify(
      roRepo.getActiveTechniqueByActiveTechnique(id),
      IfAbsent.Fail(s"Active technique '${id.value}' was not found")
    )(_ => underlying.changeStatus(id, status))
  }

  override def setAcceptationDatetimes(id: ActiveTechniqueId, datetimes: Map[TechniqueVersion, Instant])(implicit
      cc: ChangeContext
  ): IOResult[ActiveTechniqueId] = {
    checkTenant.manageModify(
      roRepo.getActiveTechniqueByActiveTechnique(id),
      IfAbsent.Fail(s"Active technique '${id.value}' was not found")
    )(_ => underlying.setAcceptationDatetimes(id, datetimes))
  }

  // deleting an active technique that does not exist is a noop
  override def deleteActiveTechnique(id: ActiveTechniqueId)(using cc: ChangeContext): IOResult[ActiveTechniqueId] = {
    checkTenant.manageDelete(roRepo.getActiveTechniqueByActiveTechnique(id), IfAbsent.Noop(id))(_ =>
      underlying.deleteActiveTechnique(id)
    )
  }

  override def addActiveTechniqueCategory(that: ActiveTechniqueCategory, into: ActiveTechniqueCategoryId)(implicit
      cc: ChangeContext
  ): IOResult[ActiveTechniqueCategory] = {
    checkTenant.manageCreate(
      that,
      roRepo.getActiveTechniqueCategory(into).notOptional(s"Category '${into.value}' was not found")
    )(cat => underlying.addActiveTechniqueCategory(cat, into))
  }

  override def saveActiveTechniqueCategory(category: ActiveTechniqueCategory)(implicit
      cc: ChangeContext
  ): IOResult[ActiveTechniqueCategory] = {
    checkTenant.manageUpdate(
      category,
      roRepo.getActiveTechniqueCategory(category.id),
      IfAbsent.Fail(s"Category '${category.id.value}' was not found")
    )(cat => underlying.saveActiveTechniqueCategory(cat))
  }

  // deleting a category that does not exist is a noop
  override def deleteCategory(id: ActiveTechniqueCategoryId, checkEmpty: Boolean)(implicit
      cc: ChangeContext
  ): IOResult[ActiveTechniqueCategoryId] = {
    checkTenant.manageDelete(roRepo.getActiveTechniqueCategory(id), IfAbsent.Noop(id))(_ =>
      underlying.deleteCategory(id, checkEmpty)
    )
  }

  override def move(
      categoryId:    ActiveTechniqueCategoryId,
      intoParent:    ActiveTechniqueCategoryId,
      optionNewName: Option[ActiveTechniqueCategoryId]
  )(implicit cc: ChangeContext): IOResult[ActiveTechniqueCategoryId] = {
    checkTenant.manageMove(
      roRepo.getActiveTechniqueCategory(categoryId),
      roRepo.getActiveTechniqueCategory(intoParent).notOptional(s"Category '${intoParent.value}' was not found"),
      IfAbsent.Fail(s"Category '${categoryId.value}' was not found")
    )(_ => underlying.move(categoryId, intoParent, optionNewName))
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

  // a category the caller can not see must read as "does not exist" - otherwise this is an existence oracle
  // (reachable e.g. through the archive API). So: exists AND visible.
  override def categoryExists(id: NodeGroupCategoryId)(using qc: QueryContext): IOResult[Boolean] = {
    underlying.categoryExists(id).flatMap {
      case false => false.succeed
      case true  => underlying.getGroupCategory(id).map(checkTenant.check(_).isDefined)
    }
  }

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

  // Deprecated method returning a PLAIN value (no effect), so it can not do the IO lookups a real tenant
  // check needs. Safe by invariant: the root category is always `Open`, hence visible to everyone (its `Pure`
  // sibling below encodes exactly that check). Its `items` (`RuleTargetInfo`) carry no `SecurityTag`, so
  // per-item pruning is not possible here regardless; group visibility is enforced on the group getters.
  // Prefer `getRootCategoryPure()` in new code.
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
    underlying:  WoNodeGroupRepository,
    roRepo:      RoNodeGroupRepository
) extends RoNodeGroupRepository with WoNodeGroupRepository {

  // the read part is just delegated to the (tenant-filtering) `roRepo`
  export roRepo.*

  override def create(nodeGroup: NodeGroup, into: NodeGroupCategoryId)(implicit cc: ChangeContext): IOResult[AddNodeGroupDiff] = {
    checkTenant.manageCreate(nodeGroup, roRepo.getGroupCategory(into))(ng => underlying.create(ng, into))
  }

  override def update(nodeGroup: NodeGroup)(implicit cc: ChangeContext): IOResult[Option[ModifyNodeGroupDiff]] = {
    checkTenant.manageUpdate(
      nodeGroup,
      roRepo.getNodeGroupOpt(nodeGroup.id).map(_.map(_._1)),
      IfAbsent.Fail(s"Cannot update group with id ${nodeGroup.id.serialize} : there is no group with that id")
    )(ng => underlying.update(ng))
  }

  // a restore may put back a narrower tag: that exception is defined in `manageRestore`
  override def restore(group: NodeGroup)(implicit cc: ChangeContext): IOResult[Option[ModifyNodeGroupDiff]] = {
    checkTenant.manageRestore(
      group,
      roRepo.getNodeGroupOpt(group.id).map(_.map(_._1)),
      // a group restore does not state a category: reverting the deletion of a group is done by `create`,
      // which does state one
      Container.none
    )(g => underlying.update(g))
  }

  override def updateSystemGroup(nodeGroup: NodeGroup)(implicit cc: ChangeContext): IOResult[Option[ModifyNodeGroupDiff]] = {
    checkTenant.manageUpdate(
      nodeGroup,
      roRepo.getNodeGroupOpt(nodeGroup.id).map(_.map(_._1)),
      IfAbsent.Fail(s"Cannot update group with id ${nodeGroup.id.serialize} : there is no group with that id")
    )(ng => underlying.updateSystemGroup(ng))
  }

  override def updateDynGroupNodes(group: NodeGroup)(implicit cc: ChangeContext): IOResult[Option[ModifyNodeGroupDiff]] = {
    checkTenant.manageUpdate(
      group,
      roRepo.getNodeGroupOpt(group.id).map(_.map(_._1)),
      IfAbsent.Fail(s"Cannot update group with id ${group.id.serialize} : there is no group with that id")
    )(ng => underlying.updateDynGroupNodes(ng))
  }

  // the node list changes, not the group definition nor its tag: this is a `manageModify`
  override def updateDiffNodes(
      nodeGroupId: NodeGroupId,
      add:         List[NodeId],
      delete:      List[NodeId]
  )(implicit cc: ChangeContext): IOResult[Option[ModifyNodeGroupDiff]] = {
    checkTenant.manageModify(
      roRepo.getNodeGroupOpt(nodeGroupId).map(_.map(_._1)),
      IfAbsent.Fail(s"Cannot update group with id ${nodeGroupId.serialize} : there is no group with that id")
    )(_ => underlying.updateDiffNodes(nodeGroupId, add, delete))
  }

  override def move(nodeGroupId: NodeGroupId, containerId: NodeGroupCategoryId)(implicit
      cc: ChangeContext
  ): IOResult[Option[ModifyNodeGroupDiff]] = {
    checkTenant.manageMove(
      roRepo.getNodeGroupOpt(nodeGroupId).map(_.map(_._1)),
      roRepo.getGroupCategory(containerId),
      IfAbsent.Fail(s"Group ${nodeGroupId.serialize} not found")
    )(_ => underlying.move(nodeGroupId, containerId))
  }

  // semantic for that one is different from all other delete: it's an error if the group is not there
  override def delete(id: NodeGroupId)(implicit cc: ChangeContext): IOResult[DeleteNodeGroupDiff] = {
    checkTenant.manageDelete(
      roRepo.getNodeGroupOpt(id).map(_.map(_._1)),
      IfAbsent.Fail(s"Group ${id.serialize} not found")
    )(_ => underlying.delete(id))
  }

  override def addGroupCategoryToCategory(that: NodeGroupCategory, into: NodeGroupCategoryId)(implicit
      cc: ChangeContext
  ): IOResult[NodeGroupCategory] = {
    checkTenant.manageCreate(that, roRepo.getGroupCategory(into))(cat => underlying.addGroupCategoryToCategory(cat, into))
  }

  override def saveGroupCategory(category: NodeGroupCategory)(implicit cc: ChangeContext): IOResult[NodeGroupCategory] = {
    checkTenant.manageUpdate(
      category,
      // that getter fails (rather than returning None) when the category is not there
      roRepo.getGroupCategory(category.id).map(Some(_)),
      IfAbsent.Fail(s"Group category ${category.id.value} not found")
    )(cat => underlying.saveGroupCategory(cat))
  }

  // that variant also states a container, but historically it is not checked here (only the category
  // itself is) - see `WoTenantRuleCategoryRepo.updateAndMove` for the checked form.
  override def saveGroupCategory(category: NodeGroupCategory, containerId: NodeGroupCategoryId)(implicit
      cc: ChangeContext
  ): IOResult[NodeGroupCategory] = {
    checkTenant.manageUpdate(
      category,
      roRepo.getGroupCategory(category.id).map(Some(_)),
      IfAbsent.Fail(s"Group category ${category.id.value} not found")
    )(cat => underlying.saveGroupCategory(cat, containerId))
  }

  // semantic is a noop if the category is already deleted
  override def delete(id: NodeGroupCategoryId, checkEmpty: Boolean)(implicit
      cc: ChangeContext
  ): IOResult[NodeGroupCategoryId] = {
    given QueryContext = QueryContext.systemQC
    roRepo.categoryExists(id).flatMap {
      case false => id.succeed
      case true  =>
        checkTenant.manageDelete(roRepo.getGroupCategory(id).map(Some(_)), IfAbsent.Noop(id))(_ =>
          underlying.delete(id, checkEmpty)
        )
    }
  }

  // a policy server target is a system topology object: only an administrator may create or delete it
  override def createPolicyServerTarget(target: PolicyServerTarget)(implicit cc: ChangeContext): IOResult[LDIFChangeRecord] =
    checkTenant.checkAdmin *> underlying.createPolicyServerTarget(target)

  override def deletePolicyServerTarget(
      policyServer: PolicyServerTarget
  )(implicit cc: ChangeContext): IOResult[PolicyServerTarget] =
    checkTenant.checkAdmin *> underlying.deletePolicyServerTarget(policyServer)
}

// ----- Rules -----------------------------------

class RoTenantRuleRepo(
    checkTenant: TenantCheckLogic,
    underlying:  RoRuleRepository
) extends RoRuleRepository {

  override def getOpt(ruleId: RuleId)(using qc: QueryContext): IOResult[Option[Rule]] = {
    for {
      r <- underlying.getOpt(ruleId)

      x = checkTenant.flatMap(r)
    } yield x
  }

  override def getAll(includeSystem: Boolean)(using qc: QueryContext): IOResult[Seq[Rule]] =
    underlying.getAll(includeSystem).map(checkTenant.filter(_))

  override def getIds(includeSystem: Boolean)(using qc: QueryContext): IOResult[Set[RuleId]] =
    getAll(includeSystem).map(_.map(_.id).toSet)
}

class WoTenantRuleRepo(
    checkTenant:    TenantCheckLogic,
    underlying:     WoRuleRepository,
    roRepo:         RoRuleRepository,
    roRuleCategory: RoRuleCategoryRepository
) extends RoRuleRepository with WoRuleRepository {

  // the read part is just delegated to the (tenant-filtering) `roRepo`
  export roRepo.*

  override def create(rule: Rule)(using cc: ChangeContext): IOResult[AddRuleDiff] = {
    checkTenant.manageCreate(
      rule,
      roRuleCategory.get(rule.categoryId).notOptional(s"Category with ID '${rule.categoryId.value}' was not found")
    )(r => underlying.create(r))
  }

  override def update(rule: Rule)(using cc: ChangeContext): IOResult[Option[ModifyRuleDiff]] = {
    checkTenant.manageUpdate(
      rule,
      roRepo.getOpt(rule.id),
      IfAbsent.Fail(s"Cannot update rule with id ${rule.id.serialize} : there is no rule with that id")
    )(r => underlying.update(r))
  }

  // a restore may put back a narrower tag: that exception is defined in `manageRestore`
  override def restore(rule: Rule)(using cc: ChangeContext): IOResult[Option[ModifyRuleDiff]] = {
    checkTenant.manageRestore(
      rule,
      roRepo.getOpt(rule.id),
      roRuleCategory.get(rule.categoryId).notOptional(s"Category with ID '${rule.categoryId.value}' was not found")
    )(r => underlying.update(r))
  }

  override def updateSystem(rule: Rule)(using cc: ChangeContext): IOResult[Option[ModifyRuleDiff]] = {
    checkTenant.manageUpdate(
      rule,
      roRepo.getOpt(rule.id),
      IfAbsent.Fail(s"Cannot update rule with id ${rule.id.serialize} : there is no rule with that id")
    )(r => underlying.updateSystem(r))
  }

  // `load` puts back in the active repository a rule that was unloaded: it is a save
  override def load(rule: Rule)(using cc: ChangeContext): IOResult[Unit] = {
    checkTenant.manageSave(
      rule,
      roRepo.getOpt(rule.id),
      roRuleCategory.get(rule.categoryId).notOptional(s"Category with ID '${rule.categoryId.value}' was not found")
    )(r => underlying.load(r))
  }

  // unloading a rule that is not there is a noop
  override def unload(ruleId: RuleId)(using cc: ChangeContext): IOResult[Unit] = {
    checkTenant.manageDelete(roRepo.getOpt(ruleId), IfAbsent.Noop(()))(_ => underlying.unload(ruleId))
  }

  // here, the semantic is that an absent rule leads to an error
  override def delete(id: RuleId)(using cc: ChangeContext): IOResult[DeleteRuleDiff] = {
    checkTenant.manageDelete(roRepo.getOpt(id), IfAbsent.Fail(s"Rule '${id.serialize}' was not found"))(_ =>
      underlying.delete(id)
    )
  }

  override def deleteSystemRule(id: RuleId)(using cc: ChangeContext): IOResult[DeleteRuleDiff] = {
    checkTenant.manageDelete(roRepo.getOpt(id), IfAbsent.Fail(s"Rule '${id.serialize}' was not found"))(_ =>
      underlying.deleteSystemRule(id)
    )
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
    underlying:  WoParameterRepository,
    roRepo:      RoParameterRepository
) extends RoParameterRepository with WoParameterRepository {

  // the read part is just delegated to the (tenant-filtering) `roRepo`
  export roRepo.*

  // a global parameter lives at the root of its own namespace: there is no container to check
  override def saveParameter(parameter: GlobalParameter)(using cc: ChangeContext): IOResult[AddGlobalParameterDiff] = {
    checkTenant.manageCreate(parameter, Container.none)(p => underlying.saveParameter(p))
  }

  override def updateParameter(
      parameter: GlobalParameter
  )(using cc: ChangeContext): IOResult[Option[ModifyGlobalParameterDiff]] = {
    checkTenant.manageUpdate(
      parameter,
      roRepo.getGlobalParameter(parameter.name),
      IfAbsent.Fail(s"Cannot update Global Parameter '${parameter.name}': there is no parameter with that name")
    )(p => underlying.updateParameter(p))
  }

  // a restore may put back a narrower tag: that exception is defined in `manageRestore`
  override def restoreParameter(
      parameter: GlobalParameter
  )(using cc: ChangeContext): IOResult[Option[ModifyGlobalParameterDiff]] = {
    checkTenant.manageRestore(
      parameter,
      roRepo.getGlobalParameter(parameter.name),
      Container.none
    )(p => underlying.updateParameter(p))
  }

  // deleting a parameter that does not exist is a noop
  override def delete(
      parameterName: String,
      provider:      Option[PropertyProvider]
  )(using cc: ChangeContext): IOResult[Option[DeleteGlobalParameterDiff]] = {
    checkTenant.manageDelete(
      roRepo.getGlobalParameter(parameterName),
      IfAbsent.Noop(Option.empty[DeleteGlobalParameterDiff])
    )(_ => underlying.delete(parameterName, provider))
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

  override def get(id: RuleCategoryId)(using qc: QueryContext): IOResult[Option[RuleCategory]] = {
    // `RuleCategory` carries its child subtree, so prune the invisible children here too - otherwise
    // `GET /rules/categories/{id}` would disclose categories the caller can not see (getRootCategory,
    // one method below, already prunes; this was the inconsistency).
    underlying.get(id).map(_.map(filterTree))
  }

  override def getRootCategory()(using qc: QueryContext): IOResult[RuleCategory] =
    underlying.getRootCategory().map(filterTree)
}

class WoTenantRuleCategoryRepo(
    checkTenant: TenantCheckLogic,
    underlying:  WoRuleCategoryRepository,
    roRepo:      RoRuleCategoryRepository
) extends RoRuleCategoryRepository with WoRuleCategoryRepository {

  // the read part is just delegated to the (tenant-filtering) `roRepo`
  export roRepo.*

  override def create(that: RuleCategory, into: RuleCategoryId)(implicit cc: ChangeContext): IOResult[RuleCategory] = {
    checkTenant.manageCreate(that, roRepo.get(into).notOptional(s"Category with ID '${into.value}' was not found"))(cat =>
      underlying.create(cat, into)
    )
  }

  // that one both changes the category and (re)places it under `into`, so both are authorized
  override def updateAndMove(that: RuleCategory, into: RuleCategoryId)(implicit cc: ChangeContext): IOResult[RuleCategory] = {
    checkTenant.manageUpdateAndMove(
      that,
      roRepo.get(that.id),
      roRepo.get(into).notOptional(s"Category with ID '${into.value}' was not found"),
      IfAbsent.Fail(s"Category with ID '${that.id.value}' was not found")
    )(cat => underlying.updateAndMove(cat, into))
  }

  // deleting a category that does not exist is a noop
  override def delete(category: RuleCategoryId, checkEmpty: Boolean)(implicit cc: ChangeContext): IOResult[RuleCategoryId] = {
    checkTenant.manageDelete(roRepo.get(category), IfAbsent.Noop(category))(_ => underlying.delete(category, checkEmpty))
  }
}
