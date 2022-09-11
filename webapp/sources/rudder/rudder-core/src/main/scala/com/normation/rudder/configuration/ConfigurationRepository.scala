/*
 *************************************************************************************
 * Copyright 2020 Normation SAS
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

package com.normation.rudder.configuration

import com.normation.GitVersion
import com.normation.GitVersion.Revision
import com.normation.GitVersion.RevisionInfo
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueCategoryName
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.errors.IOResult
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.repository.xml.TechniqueRevisionRepository
import zio._
import zio.syntax._

/*
 * Easier te manage data
 */
final case class ActiveDirective(activeTechnique: ActiveTechnique, directive: Directive)
final case class GroupAndCat(group: NodeGroup, categoryId: NodeGroupCategoryId)

/*
 * This class is in charge of loading and updating configuration objects in rudder.
 * All configuration objects are versionned and are associated with a pair of identifier:
 * - their unique identifier (an uuid),
 * - their version identifier (a git commit id, ie a sha1/sha256)
 *
 * The repository has a notion of "current version", which is commit id for head in default branch.
 */
trait ConfigurationRepository extends RoConfigurationRepository with WoConfigurationRepository

trait RoConfigurationRepository {

  /*
   * Get a directive and its matching active technique for the given (id, version)
   */
  def getDirective(id: DirectiveId): IOResult[Option[ActiveDirective]]
  def getTechnique(id: TechniqueId): IOResult[Option[(Chunk[TechniqueCategoryName], Technique)]]
  def getRule(id:      RuleId):      IOResult[Option[Rule]]
  def getGroup(id:     NodeGroupId): IOResult[Option[GroupAndCat]]

  def getDirectiveLibrary(ids: Set[DirectiveId]): IOResult[FullActiveTechniqueCategory]

  def getDirectiveRevision(uid: DirectiveUid): IOResult[List[RevisionInfo]]
}

trait WoConfigurationRepository {}

trait DirectiveRevisionRepository {

  def getDirectiveRevision(uid: DirectiveUid, rev: Revision): IOResult[Option[(ActiveTechnique, Directive)]]
  def getRevisions(uid:         DirectiveUid): IOResult[List[RevisionInfo]]
}

trait RuleRevisionRepository {
  def getRuleRevision(uid: RuleUid, rev: Revision): IOResult[Option[Rule]]
}

trait GroupRevisionRepository {
  def getGroupRevision(uid: NodeGroupUid, rev: Revision): IOResult[Option[GroupAndCat]]
}

/****************************************************************************************/

class ConfigurationRepositoryImpl(
    roDirectiveRepository: RoDirectiveRepository,
    techniqueRepository:   TechniqueRepository,
    roRuleRepository:      RoRuleRepository,
    roNodeGroupRepository: RoNodeGroupRepository,
    directiveRevisionRepo: DirectiveRevisionRepository,
    techniqueRevisionRepo: TechniqueRevisionRepository,
    ruleRevisionRepo:      RuleRevisionRepository,
    groupRevisionRepo:     GroupRevisionRepository
) extends ConfigurationRepository {

  override def getDirective(id: DirectiveId): IOResult[Option[ActiveDirective]] = {
    (id.rev match {
      case GitVersion.DEFAULT_REV =>
        roDirectiveRepository.getActiveTechniqueAndDirective(id)
      case r                      =>
        directiveRevisionRepo.getDirectiveRevision(id.uid, r)
    }).map(_.map { case (at, d) => ActiveDirective(at, d) })
  }

  override def getTechnique(id: TechniqueId): IOResult[Option[(Chunk[TechniqueCategoryName], Technique)]] = {
    id.version.rev match {
      case GitVersion.DEFAULT_REV =>
        techniqueRepository.get(id) match {
          case None    => None.succeed
          case Some(t) =>
            techniqueRepository.getTechniqueCategoriesBreadCrump(id).map(c => Some((Chunk.fromIterable(c.map(_.id.name)), t)))
        }
      case r                      =>
        techniqueRevisionRepo.getTechnique(id.name, id.version.version, r)
    }
  }

  override def getRule(id: RuleId): IOResult[Option[Rule]] = {
    id.rev match {
      case GitVersion.DEFAULT_REV =>
        roRuleRepository.getOpt(id)
      case r                      =>
        ruleRevisionRepo.getRuleRevision(id.uid, id.rev)
    }
  }

  override def getGroup(id: NodeGroupId): IOResult[Option[GroupAndCat]] = {
    id.rev match {
      case GitVersion.DEFAULT_REV =>
        roNodeGroupRepository.getNodeGroupOpt(id).map(_.map { case (g, c) => GroupAndCat(g, c) })
      case r                      =>
        groupRevisionRepo.getGroupRevision(id.uid, id.rev)
    }
  }

  override def getDirectiveRevision(uid: DirectiveUid): IOResult[List[RevisionInfo]] = {
    directiveRevisionRepo.getRevisions(uid)
  }

  def getDirectiveLibrary(ids: Set[DirectiveId]): IOResult[FullActiveTechniqueCategory] = {
    def nonDefaultRev(rev: Revision): Boolean = rev != GitVersion.DEFAULT_REV
    val versionedDirectives = ids.filter(x => nonDefaultRev(x.rev))
    for {
      optDirs  <- ZIO.foreach(versionedDirectives.toList)(getDirective) // TODO: find a way to do that without N git treewalks
      vDirs     = optDirs.collect { case Some(ad) => (ad.activeTechnique.techniqueName, ad.directive) }
      others   <- roDirectiveRepository.getFullDirectiveLibrary()
      lib       = others.addAndFilterDirectives(vDirs, ids)
      // now that we have all (and only) relevant techniques, find the one with version and retrieve them
      vTechIds  = lib.allDirectives.collect {
                    case (_, (fat, d)) if (nonDefaultRev(d.techniqueVersion.rev)) =>
                      TechniqueId(fat.techniqueName, d.techniqueVersion)
                  }
      optTechs <- ZIO.foreach(vTechIds)(getTechnique)                   // TODO: find a way to do that without N git treewalks
      // assume techniques don't change categories
      fullLib   = lib.addTechniques(optTechs.flatMap(_.map(_._2)).toList)
    } yield {
      fullLib
    }
  }
}
