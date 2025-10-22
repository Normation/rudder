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

package com.normation.rudder.repository.ldap

import cats.implicits.*
import com.normation.NamedZioLogger
import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.NodeId
import com.normation.inventory.ldap.core.LDAPConstants
import com.normation.inventory.ldap.core.LDAPConstants.A_DESCRIPTION
import com.normation.inventory.ldap.core.LDAPConstants.A_NAME
import com.normation.inventory.ldap.core.LDAPConstants.A_OC
import com.normation.ldap.ldif.LDIFNoopChangeRecord
import com.normation.ldap.sdk.BuildFilter.AND
import com.normation.ldap.sdk.BuildFilter.EQ
import com.normation.ldap.sdk.BuildFilter.IS
import com.normation.ldap.sdk.BuildFilter.NOT
import com.normation.ldap.sdk.BuildFilter.OR
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.LDAPEntry
import com.normation.ldap.sdk.LDAPIOResult.*
import com.normation.ldap.sdk.RoLDAPConnection
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.ldap.sdk.syntax.*
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants.A_GROUP_CATEGORY_UUID
import com.normation.rudder.domain.RudderLDAPConstants.A_IS_ENABLED
import com.normation.rudder.domain.RudderLDAPConstants.A_IS_SYSTEM
import com.normation.rudder.domain.RudderLDAPConstants.A_NODE_GROUP_UUID
import com.normation.rudder.domain.RudderLDAPConstants.A_RULE_TARGET
import com.normation.rudder.domain.RudderLDAPConstants.OC_GROUP_CATEGORY
import com.normation.rudder.domain.RudderLDAPConstants.OC_RUDDER_NODE_GROUP
import com.normation.rudder.domain.RudderLDAPConstants.OC_SPECIAL_TARGET
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.domain.policies.*
import com.normation.rudder.facts.nodes.ChangeContext
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.repository.CategoryAndNodeGroup
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.repository.GitNodeGroupArchiver
import com.normation.rudder.repository.GroupCategoryRepositoryOrdering
import com.normation.rudder.repository.NodeGroupCategoryOrdering
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.WoNodeGroupRepository
import com.normation.rudder.services.user.PersonIdentService
import com.normation.utils.StringUuidGenerator
import com.unboundid.ldap.sdk.DN
import com.unboundid.ldap.sdk.Filter
import com.unboundid.ldif.LDIFChangeRecord
import net.liftweb.common.*
import scala.collection.immutable.SortedMap
import zio.*
import zio.syntax.*

class RoLDAPNodeGroupRepository(
    val rudderDit:     RudderDit,
    val ldap:          LDAPConnectionProvider[RoLDAPConnection],
    val mapper:        LDAPEntityMapper,
    val nodeFactRepo:  NodeFactRepository,
    val groupLibMutex: ScalaReadWriteLock // that's a scala-level mutex to have some kind of consistency with LDAP
) extends RoNodeGroupRepository with NamedZioLogger {
  repo =>

  override def loggerName: String = this.getClass.getName

  /**
   * Find sub entries (children group categories and server groups for
   * the given category which MUST be mapped to an entry with the given
   * DN in the LDAP backend, accessible with the given connection.
   */
  private def addSubEntries(category: NodeGroupCategory, dn: DN, con: RoLDAPConnection): IOResult[NodeGroupCategory] = {
    for {
      res <- con.searchOne(
               dn,
               OR(IS(OC_GROUP_CATEGORY), IS(OC_RUDDER_NODE_GROUP), IS(OC_SPECIAL_TARGET)),
               A_OC,
               A_NODE_GROUP_UUID,
               A_NAME,
               A_RULE_TARGET,
               A_DESCRIPTION,
               A_IS_ENABLED,
               A_IS_SYSTEM
             )
    } yield {
      val subEntries = res.partition(e => e.isA(OC_GROUP_CATEGORY))
      category.copy(
        children = subEntries._1.sortBy(e => e(A_NAME).map(_.toLowerCase())).map(e => mapper.dn2NodeGroupCategoryId(e.dn)).toList,
        items = (subEntries._2
          .sortBy(e => e(A_NAME).map(_.toLowerCase()))
          .flatMap(entry => {
            mapper.entry2RuleTargetInfo(entry) match {
              case Right(targetInfo) => Some(targetInfo.toTargetInfo)
              case Left(e)           =>
                logEffect.error(
                  s"Error when trying to get the child of group category '${category.id.value}' with DN '${entry.dn}': ${e.fullMsg}"
                )
                None
            }
          })
          .toList)
      )
    }
  }

  def getSGEntry(con: RoLDAPConnection, id: NodeGroupId, attributes: String*): IOResult[Option[LDAPEntry]] = {
    con.searchSub(rudderDit.GROUP.dn, EQ(A_NODE_GROUP_UUID, id.serialize), attributes*).flatMap { srvEntries =>
      srvEntries.size match {
        case 0 => None.succeed
        case 1 => Some(srvEntries(0)).succeed
        case _ =>
          Inconsistency(
            s"Error, the directory contains multiple occurrence of the server group with ID '${id.serialize}'. DNs involved: ${srvEntries.map(_.dn).mkString("; ")}"
          ).fail
      }
    }
  }

  /**
   * Retrieve the category entry for the given ID, with the given connection
   * Used to get the ldap dn
   */
  def getCategoryEntry(con: RoLDAPConnection, id: NodeGroupCategoryId, attributes: String*): IOResult[Option[LDAPEntry]] = {
    groupLibMutex.readLock {
      con.searchSub(rudderDit.GROUP.dn, EQ(A_GROUP_CATEGORY_UUID, id.value), attributes*)
    }.flatMap { categoryEntries =>
      categoryEntries.size match {
        case 0 => None.succeed
        case 1 => Some(categoryEntries(0)).succeed
        case _ =>
          Inconsistency(
            s"Error, the directory contains multiple occurrence of group category with id '${id.value}}'. DN: ${categoryEntries.map(_.dn).mkString("; ")}"
          ).fail
      }
    }
  }

  def getGroupsByCategory(
      includeSystem: Boolean = false
  )(implicit
      qc:            QueryContext
  ): IOResult[SortedMap[List[NodeGroupCategoryId], CategoryAndNodeGroup]] = {
    groupLibMutex.readLock {
      for {
        allCats        <- getAllGroupCategories(includeSystem)
        catsWithGroups <- ZIO.foreach(allCats) { ligthCat =>
                            for {
                              category <- getGroupCategory(ligthCat.id)
                              parents  <- getParents_NodeGroupCategory(category.id)
                              groups   <- (ZIO
                                            .foreach(category.items) { targetInfo =>
                                              targetInfo.target match {
                                                case group: GroupTarget =>
                                                  this.getNodeGroup(group.groupId).map { case (g, catId) => Some(g) }
                                                // just ignore other target type
                                                case _ => None.succeed
                                              }
                                            })
                                            .map(_.flatten)

                            } yield {
                              ((category.id :: parents.map(_.id)).reverse, CategoryAndNodeGroup(category, groups.toSet))
                            }
                          }
      } yield {
        implicit val ordering = NodeGroupCategoryOrdering
        SortedMap[List[NodeGroupCategoryId], CategoryAndNodeGroup]() ++ catsWithGroups
      }
    }
  }

  def getNodeGroupOpt(id: NodeGroupId)(implicit qc: QueryContext): IOResult[Option[(NodeGroup, NodeGroupCategoryId)]] = {
    groupLibMutex.readLock(for {
      con     <- ldap
      sgEntry <- getSGEntry(con, id)
      sg      <- sgEntry match {
                   case None    => None.succeed
                   case Some(x) =>
                     for {
                       g        <- mapper
                                     .entry2NodeGroup(x)
                                     .toIO
                                     .chainError(s"Error when mapping server group entry to its entity. Entry: ${sgEntry}")
                       allNodes <- nodeFactRepo.getAll()
                       nodeIds   = g.serverList.intersect(allNodes.keySet.toSet)
                       y         = g.copy(serverList = nodeIds)
                     } yield Some((y, mapper.dn2NodeGroupCategoryId(x.dn.getParent)))
                 }
    } yield {
      sg
    })
  }

  def getAllGroupCategories(includeSystem: Boolean = false): IOResult[Seq[NodeGroupCategory]] = {
    groupLibMutex.readLock {
      for {
        con             <- ldap
        filter           = if (includeSystem) IS(OC_GROUP_CATEGORY) else AND(NOT(EQ(A_IS_SYSTEM, true.toLDAPString)), IS(OC_GROUP_CATEGORY))
        categoryEntries <- con.searchSub(rudderDit.GROUP.dn, filter)
        ids              = categoryEntries.map(e => mapper.dn2NodeGroupCategoryId(e.dn))
        result          <- ZIO.foreach(ids)(id => getGroupCategory(id))
      } yield {
        result
      }
    }
  }

  /**
   * Root group category - must not be used in pure code, it will break things.
   * (see for ex: #18983)
   * Kept only for backward compatibility.
   */
  def getRootCategory(): NodeGroupCategory = {
    com.normation.zio.ZioRuntime.runNow(getRootCategoryPure().either) match {
      case Right(root) => root
      case Left(e)     => throw new RuntimeException(e.msg)
    }
  }

  def getRootCategoryPure(): IOResult[NodeGroupCategory] = {
    for {
      con               <- ldap
      rootCategoryEntry <-
        groupLibMutex.readLock {
          con
            .get(rudderDit.GROUP.dn)
            .notOptional(
              "The root category of the server group category seems to be missing in LDAP directory. Please check its content"
            )
        }
      // look for sub category and technique
      rootCategory      <- mapper
                             .entry2NodeGroupCategory(rootCategoryEntry)
                             .toIO
                             .chainError(s"Error when mapping from an LDAP entry to a Node Group Category: '${rootCategoryEntry}'")
      added             <- addSubEntries(rootCategory, rootCategoryEntry.dn, con)
    } yield {
      added
    }
  }

  /**
   * Return the list of parents for that category, the nearest parent
   * first, until the root of the library.
   * The the last parent is not the root of the library, return a Failure.
   * Also return a failure if the path to top is broken in any way.
   */
  def getParentsForCategory(id: NodeGroupCategoryId, root: NodeGroupCategory): IOResult[List[NodeGroupCategory]] = {
    // TODO : LDAPify that, we can have the list of all DN from id to root at the begining
    if (id == root.id) Nil.succeed
    else getParentGroupCategory(id).flatMap(parent => getParentsForCategory(parent.id, root).map(parents => parent :: parents))
  }

  override def categoryExists(id: NodeGroupCategoryId): IOResult[Boolean] = {
    for {
      con           <- ldap
      categoryEntry <- groupLibMutex.readLock(getCategoryEntry(con, id, "dn"))
    } yield categoryEntry.nonEmpty
  }

  /**
   * Get a group category by its id
   * */
  def getGroupCategory(id: NodeGroupCategoryId): IOResult[NodeGroupCategory] = {
    for {
      con           <- ldap
      categoryEntry <- groupLibMutex.readLock {
                         getCategoryEntry(con, id).notOptional(s"Entry with ID '${id.value}' was not found")
                       }
      category      <- mapper
                         .entry2NodeGroupCategory(categoryEntry)
                         .toIO
                         .chainError(s"Error when transforming LDAP entry ${categoryEntry} into a server group category")
      added         <- addSubEntries(category, categoryEntry.dn, con)
    } yield {
      added
    }
  }

  /**
   * Get the direct parent of the given category.
   * Fails if the category is not in the repository or for root category
   */
  def getParentGroupCategory(id: NodeGroupCategoryId): IOResult[NodeGroupCategory] = {
    groupLibMutex.readLock {
      for {
        con                 <- ldap
        categoryEntry       <- getCategoryEntry(con, id, "1.1").notOptional(s"Entry with ID '${id.value}' was not found")
        parentCategoryEntry <-
          con.get(categoryEntry.dn.getParent).notOptional(s"Parent category of entry with ID '${id.value}' was not found")
        parentCategory      <-
          mapper
            .entry2NodeGroupCategory(parentCategoryEntry)
            .toIO
            .chainError("Error when transforming LDAP entry %s into an active technqiue category".format(parentCategoryEntry))
        added               <- addSubEntries(parentCategory, parentCategoryEntry.dn, con)
      } yield {
        added
      }
    }
  }

  def getParents_NodeGroupCategory(id: NodeGroupCategoryId): IOResult[List[NodeGroupCategory]] = {
    // TODO : LDAPify that, we can have the list of all DN from id to root at the begining (just dn.getParent until rudderDit.NOE_GROUP.dn)
    for {
      root <- getRootCategoryPure()
      res  <- if (id == root.id) Nil.succeed
              else {
                getParentGroupCategory(id).flatMap(parent =>
                  getParents_NodeGroupCategory(parent.id).map(parents => parent :: parents)
                )
              }
    } yield res
  }

  /**
   * Returns all non system categories + the root category
   * Caution, they are "lightweight" group categories (no children)
   */
  def getAllNonSystemCategories(): IOResult[Seq[NodeGroupCategory]] = {
    groupLibMutex.readLock {
      for {
        con               <- ldap
        rootCategoryEntry <-
          con
            .get(rudderDit.GROUP.dn)
            .notOptional(
              "The root category of the server group category seems to be missing in LDAP directory. Please check its content"
            )
        categoryEntries   <- con.searchSub(rudderDit.GROUP.dn, AND(NOT(EQ(A_IS_SYSTEM, true.toLDAPString)), IS(OC_GROUP_CATEGORY)))
        allEntries         = categoryEntries :+ rootCategoryEntry
        entries           <- allEntries.toVector.traverse(x => mapper.entry2NodeGroupCategory(x)).toIO
      } yield {
        entries
      }
    }
  }

  /**
   * Get all pairs of (categoryid, category)
   * in a map in which keys are the parent category of the
   * the template. The map is sorted by categories:
   * SortedMap {
   *   "/"           -> [root]
   *   "/cat1"       -> [cat1_details]
   *   "/cat1/cat11" -> [/cat1/cat11]
   *   "/cat2"       -> [/cat2_details]
   *   ...
   */
  def getCategoryHierarchy: IOResult[SortedMap[List[NodeGroupCategoryId], NodeGroupCategory]] = {
    for {
      allCats     <- getAllNonSystemCategories()
      rootCat     <- getRootCategoryPure()
      catsWithUPs <- ZIO.foreach(allCats) { ligthCat =>
                       (for {
                         category <- getGroupCategory(ligthCat.id)
                         parents  <- getParentsForCategory(ligthCat.id, rootCat)
                       } yield {
                         ((category.id :: parents.map(_.id)).reverse, category)
                       })
                     }
    } yield {
      implicit val ordering = GroupCategoryRepositoryOrdering
      SortedMap[List[NodeGroupCategoryId], NodeGroupCategory]() ++ catsWithUPs
    }
  }

  /**
   * Fetch the parent category of the NodeGroup
   * Caution, its a lightweight version of the entry (no children nor item)
   * @param id
   * @return
   */
  def getNodeGroupCategory(id: NodeGroupId): IOResult[NodeGroupCategory] = {
    groupLibMutex.readLock {
      for {
        con                 <- ldap
        groupEntry          <- getSGEntry(con, id, "1.1").notOptional(s"Entry with ID '${id.serialize}' was not found")
        parentCategoryEntry <-
          con.get(groupEntry.dn.getParent).notOptional(s"Parent category of entry with ID '${id.serialize}' was not found")
        parentCategory      <-
          mapper
            .entry2NodeGroupCategory(parentCategoryEntry)
            .toIO
            .chainError("Error when transforming LDAP entry %s into an active technique category".format(parentCategoryEntry))
      } yield {
        parentCategory
      }
    }
  }

  def getAll(): IOResult[Seq[NodeGroup]] = {
    for {
      con     <- ldap
      // for each directive entry, map it. if one fails, all fails
      entries <- groupLibMutex.readLock(con.searchSub(rudderDit.GROUP.dn, EQ(A_OC, OC_RUDDER_NODE_GROUP)))
      groups  <- ZIO.foreach(entries)(groupEntry => {
                   mapper
                     .entry2NodeGroup(groupEntry)
                     .toIO
                     .chainError(s"Error when transforming LDAP entry into a Group instance. Entry: ${groupEntry}")
                 })
    } yield {
      groups
    }
  }

  def getAllByIds(ids: Seq[NodeGroupId]): IOResult[Seq[NodeGroup]] = {
    for {
      con     <- ldap
      // for each directive entry, map it. if one fails, all fails
      entries <-
        groupLibMutex.readLock(con.searchSub(rudderDit.GROUP.dn, OR(ids.map(id => EQ(A_NODE_GROUP_UUID, id.serialize))*)))
      groups  <- ZIO.foreach(entries)(groupEntry => {
                   mapper
                     .entry2NodeGroup(groupEntry)
                     .toIO
                     .chainError(s"Error when transforming LDAP entry into a Group instance. Entry: ${groupEntry}")
                 })
    } yield {
      groups
    }
  }

  def getAllNodeIds(): IOResult[Map[NodeGroupId, Set[NodeId]]] = {
    for {
      con     <- ldap
      // for each directive entry, map it. if one fails, all fails
      entries <- groupLibMutex.readLock(con.searchSub(rudderDit.GROUP.dn, EQ(A_OC, OC_RUDDER_NODE_GROUP)))
      groups  <- ZIO.foreach(entries)(groupEntry => {
                   mapper
                     .entryToGroupNodeIds(groupEntry)
                     .toIO
                     .chainError(s"Error when transforming LDAP entry into a list of noes. Entry: ${groupEntry}")
                 })
    } yield {
      groups.toMap
    }
  }

  def getAllNodeIdsChunk(): IOResult[Map[NodeGroupId, Chunk[NodeId]]] = {
    for {
      con     <- ldap
      // for each directive entry, map it. if one fails, all fails
      entries <- groupLibMutex.readLock(con.searchSub(rudderDit.GROUP.dn, EQ(A_OC, OC_RUDDER_NODE_GROUP)))
      groups  <- ZIO.foreach(entries)(groupEntry => {
                   mapper
                     .entryToGroupNodeIdsChunk(groupEntry)
                     .toIO
                     .chainError(s"Error when transforming LDAP entry into a list of nodes. Entry: ${groupEntry}")
                 })
    } yield {
      groups.toMap
    }
  }

  private def findGroupWithFilter(filter: Filter): IOResult[Seq[NodeGroupId]] = {
    groupLibMutex.readLock {
      for {
        con      <- ldap
        entries  <- con.searchSub(rudderDit.GROUP.dn, filter, "1.1")
        groupIds <- ZIO.foreach(entries) { entry =>
                      rudderDit.GROUP
                        .getGroupId(entry.dn)
                        .toIO
                        .chainError("DN '%s' seems to not be a valid group DN".format(entry.dn))
                    }
        gids     <- ZIO.foreach(groupIds)(NodeGroupId.parse(_).toIO)
      } yield {
        gids
      }
    }
  }

  /**
   * Retrieve all groups that have at least one of the given
   * node ID in there member list.
   * @param nodeIds
   * @return
   */
  def findGroupWithAnyMember(nodeIds: Seq[NodeId]): IOResult[Seq[NodeGroupId]] = {
    val filter = AND(
      IS(OC_RUDDER_NODE_GROUP),
      OR(nodeIds.distinct.map(nodeId => EQ(LDAPConstants.A_NODE_UUID, nodeId.value))*)
    )
    findGroupWithFilter(filter)
  }

  /**
   * Retrieve all groups that have ALL given node ID in their
   * member list.
   * @param nodeIds
   * @return
   */
  def findGroupWithAllMember(nodeIds: Seq[NodeId]): IOResult[Seq[NodeGroupId]] = {
    val filter = AND(
      IS(OC_RUDDER_NODE_GROUP),
      AND(nodeIds.distinct.map(nodeId => EQ(LDAPConstants.A_NODE_UUID, nodeId.value))*)
    )
    findGroupWithFilter(filter)
  }

  /**
   * Get the full group tree with all information
   * for categories and groups.
   * Returns the objects sorted by name within
   */
  def getFullGroupLibrary(): IOResult[FullNodeGroupCategory] = {

    final case class AllMaps(
        categories:           Map[NodeGroupCategoryId, NodeGroupCategory],
        categoriesByCategory: Map[NodeGroupCategoryId, List[NodeGroupCategoryId]],
        targetByCategory:     Map[NodeGroupCategoryId, List[FullRuleTargetInfo]]
    )

    def fromCategory(catId: NodeGroupCategoryId, maps: AllMaps): FullNodeGroupCategory = {
      def getCat(id: NodeGroupCategoryId) = maps.categories.getOrElse(
        id,
        throw new IllegalArgumentException(s"Missing categories with id ${catId} in the list of available categories")
      )
      val cat                             = getCat(catId)

      FullNodeGroupCategory(
        id = catId,
        name = cat.name,
        description = cat.description,
        subCategories = maps.categoriesByCategory.getOrElse(catId, Nil).map(id => fromCategory(id, maps)).sortBy(_.name),
        targetInfos = maps.targetByCategory.getOrElse(catId, Nil).sortBy(_.name),
        isSystem = cat.isSystem
      )
    }

    /*
     * strategy: load the full subtree from the root category id,
     * then process entrie mapping them to their light version,
     * then call fromCategory on the root (we know its id).
     */

    val emptyAll = AllMaps(Map(), Map(), Map())
    import rudderDit.GROUP.*

    def mappingError(current: AllMaps, e: LDAPEntry, err: RudderError): AllMaps = {
      val error =
        Chained(s"Error when mapping entry with DN '${e.dn.toString}' from node groups library, that entry will be ignored", err)

      logEffect.warn(error.fullMsg)
      current
    }

    groupLibMutex
      .readLock(for {
        con     <- ldap
        entries <-
          con
            .getTree(rudderDit.GROUP.dn)
            .notOptional(
              "The root category of the node group library seems to be missing in LDAP directory. Please check its content"
            )
      } yield entries)
      .map { entries =>
        val allMaps = entries.toSeq.foldLeft(emptyAll) {
          case (current, e) =>
            if (isACategory(e)) {
              mapper.entry2NodeGroupCategory(e) match {
                case Right(category) =>
                  // for categories other than root, add it in the list
                  // of its parent subcategories
                  val updatedSubCats = if (e.dn == rudderDit.GROUP.dn) {
                    current.categoriesByCategory
                  } else {
                    val catId   = mapper.dn2NodeGroupCategoryId(e.dn.getParent)
                    val subCats = category.id :: current.categoriesByCategory.getOrElse(catId, Nil)
                    current.categoriesByCategory + (catId -> subCats)
                  }
                  current.copy(
                    categories = current.categories + (category.id -> category),
                    categoriesByCategory = updatedSubCats
                  )
                case Left(err)       => mappingError(current, e, err)
              }
            } else if (isAGroup(e) || isASpecialTarget(e)) {
              mapper.entry2RuleTargetInfo(e) match {
                case Right(fullInfo) =>
                  val catId         = mapper.dn2NodeGroupCategoryId(e.dn.getParent)
                  val infosForCatId = fullInfo :: current.targetByCategory.getOrElse(catId, Nil)
                  current.copy(
                    targetByCategory = current.targetByCategory + (catId -> infosForCatId)
                  )
                case Left(err)       => mappingError(current, e, err)
              }
            } else {
              // log error, continue
              logEffect.warn(
                s"Entry with DN '${e.dn}' was ignored because it is of an unknow type. Known types are categories, active techniques, directives"
              )
              current
            }
        }

        fromCategory(NodeGroupCategoryId(rudderDit.GROUP.rdnValue._1), allMaps)
      }
  }

}

class WoLDAPNodeGroupRepository(
    roGroupRepo:        RoLDAPNodeGroupRepository,
    ldap:               LDAPConnectionProvider[RwLDAPConnection],
    diffMapper:         LDAPDiffMapper,
    uuidGen:            StringUuidGenerator,
    actionlogEffect:    EventLogRepository,
    gitArchiver:        GitNodeGroupArchiver,
    personIdentService: PersonIdentService,
    autoExportOnModify: Boolean
) extends WoNodeGroupRepository with Loggable {
  repo =>

  import roGroupRepo.*

  /**
   * Check if a group category exist with the given name
   */
  private def categoryExists(con: RoLDAPConnection, name: String, parentDn: DN): IOResult[Boolean] = {
    groupLibMutex.readLock(
      con
        .searchOne(parentDn, AND(IS(OC_GROUP_CATEGORY), EQ(A_NAME, name)), A_GROUP_CATEGORY_UUID)
        .flatMap(_.size match {
          case 0 => false.succeed
          case 1 => true.succeed
          case _ => logPure.error("More than one nodeCategory has %s name under %s".format(name, parentDn)) *> true.succeed
        })
    )
  }

  /**
   * Check if a group category exist with the given name
   */
  private def categoryExists(
      con:       RoLDAPConnection,
      name:      String,
      parentDn:  DN,
      currentId: NodeGroupCategoryId
  ): IOResult[Boolean] = {
    groupLibMutex.readLock(
      con
        .searchOne(
          parentDn,
          AND(NOT(EQ(A_GROUP_CATEGORY_UUID, currentId.value)), AND(IS(OC_GROUP_CATEGORY), EQ(A_NAME, name))),
          A_GROUP_CATEGORY_UUID
        )
        .flatMap(_.size match {
          case 0 => false.succeed
          case 1 => true.succeed
          case _ => logPure.error("More than one nodeCategory has %s name under %s".format(name, parentDn)) *> true.succeed
        })
    )
  }

  /**
   * Check if a nodeGroup exists(id already exists) and name is unique
   */
  private def checkNodeGroupExists(con: RoLDAPConnection, group: NodeGroup): IOResult[Boolean] = {
    groupLibMutex.readLock(
      con
        .searchSub(
          rudderDit.GROUP.dn,
          AND(IS(OC_RUDDER_NODE_GROUP), OR(EQ(A_NODE_GROUP_UUID, group.id.serialize), EQ(A_NAME, group.name))),
          A_NODE_GROUP_UUID
        )
        .flatMap(_.size match {
          case 0 => false.succeed
          case 1 => true.succeed
          case _ =>
            logPure
              .error(s"There is more than one node group with id '${group.id.serialize}' or name '${group.name}'") *> true.succeed
        })
    )
  }

  /**
   * Check if another nodeGroup exist with the given name
   */
  private def checkNameAlreadyInUse(con: RoLDAPConnection, name: String, id: NodeGroupId): IOResult[Boolean] = {
    groupLibMutex.readLock(
      con
        .searchSub(
          rudderDit.GROUP.dn,
          AND(NOT(EQ(A_NODE_GROUP_UUID, id.serialize)), AND(EQ(A_OC, OC_RUDDER_NODE_GROUP), EQ(A_NAME, name))),
          A_NODE_GROUP_UUID
        )
        .flatMap(_.size match {
          case 0 => false.succeed
          case 1 => true.succeed
          case _ => logPure.error(s"More than one node group has '${name}' name") *> true.succeed
        })
    )
  }

  private def getContainerDn(con: RoLDAPConnection, id: NodeGroupCategoryId): IOResult[DN] = {
    groupLibMutex.readLock {
      con
        .searchSub(rudderDit.GROUP.dn, AND(IS(OC_GROUP_CATEGORY), EQ(A_GROUP_CATEGORY_UUID, id.value)), A_GROUP_CATEGORY_UUID)
        .flatMap(_.toList match {
          case Nil         => Inconsistency(s"Impossible to find parent group category for category '${id.value}'").fail
          case head :: Nil => head.dn.succeed
          case _           =>
            logPure.error(s"Too many NodeGroupCategory found with this id '${id.value}'") *>
            Inconsistency(s"Too many NodeGroupCategory found with this id ${id.value}").fail
        })
    }
  }

  /**
   * Add that group category into the given parent category
   * Fails if the parent category does not exist or
   * if it already contains that category.
   *
   * return the new category.
   */
  override def addGroupCategorytoCategory(
      that:   NodeGroupCategory,
      into:   NodeGroupCategoryId,
      modId:  ModificationId,
      actor:  EventActor,
      reason: Option[String]
  ): IOResult[NodeGroupCategory] = {
    groupLibMutex.writeLock(for {
      con                 <- ldap
      parentCategoryEntry <-
        getCategoryEntry(con, into, "1.1").notOptional("The parent category '%s' was not found, can not add".format(into))
      exists              <- categoryExists(con, that.name, parentCategoryEntry.dn)
      canAddByName        <-
        if (exists)
          s"Cannot create the Node Group Category with name '${that.name}': a category with the same name exists at the same level".fail
        else "OK, can add".succeed
      categoryEntry        = mapper.nodeGroupCategory2ldap(that, parentCategoryEntry.dn)
      result              <- con.save(categoryEntry, removeMissingAttributes = true)
      autoArchive         <- (if (autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord] && !that.isSystem) {
                                for {
                                  parents  <- getParents_NodeGroupCategory(that.id)
                                  commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                  archive  <-
                                    gitArchiver.archiveNodeGroupCategory(that, parents.map(_.id), Some((modId, commiter, reason)))
                                } yield archive
                              } else ZIO.unit)
      newCategory         <- getGroupCategory(that.id).chainError(s"The newly created category '${that.id.value}' was not found")
    } yield {
      newCategory
    })
  }

  /**
   * Update an existing group category
   */
  override def saveGroupCategory(
      category: NodeGroupCategory,
      modId:    ModificationId,
      actor:    EventActor,
      reason:   Option[String]
  ): IOResult[NodeGroupCategory] = {
    groupLibMutex.writeLock(for {
      con              <- ldap
      oldCategoryEntry <-
        getCategoryEntry(con, category.id, "1.1").notOptional(s"Entry with ID '${category.id.value}' was not found")
      categoryEntry     = mapper.nodeGroupCategory2ldap(category, oldCategoryEntry.dn.getParent)
      exists           <- categoryExists(con, category.name, oldCategoryEntry.dn.getParent, category.id)
      canAddByName     <-
        if (exists)
          s"Cannot update the Node Group Category with name '${category.name}': a category with the same name exists at the same level".fail
        else ZIO.unit
      result           <- con.save(categoryEntry, removeMissingAttributes = true)
      updated          <- getGroupCategory(category.id)
      // Maybe we have to check if the parents are system or not too
      autoArchive      <- (if (autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord] && !category.isSystem) {
                             for {
                               parents  <- getParents_NodeGroupCategory(category.id)
                               commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                               archive  <-
                                 gitArchiver.archiveNodeGroupCategory(updated, parents.map(_.id), Some((modId, commiter, reason)))
                             } yield archive
                           } else ZIO.unit)
    } yield {
      updated
    })
  }

  /**
   * Update/move an existing group category
   */
  override def saveGroupCategory(
      category:    NodeGroupCategory,
      containerId: NodeGroupCategoryId,
      modId:       ModificationId,
      actor:       EventActor,
      reason:      Option[String]
  ): IOResult[NodeGroupCategory] = {
    groupLibMutex.writeLock(for {
      con              <- ldap
      oldParents       <- if (autoExportOnModify) {
                            getParents_NodeGroupCategory(category.id)
                          } else Nil.succeed
      oldCategoryEntry <-
        getCategoryEntry(con, category.id, "1.1").notOptional(s"Entry with ID '${category.id.value}' was not found")
      newParent        <-
        getCategoryEntry(con, containerId, "1.1").notOptional(s"Parent entry with ID '${containerId.value}' was not found")
      exists           <- categoryExists(con, category.name, newParent.dn, category.id)
      canAddByName     <-
        if (exists) {
          "Cannot update the Node Group Category with name %s : a category with the same name exists at the same level"
            .format(category.name)
            .fail
        } else ZIO.unit
      categoryEntry     = mapper.nodeGroupCategory2ldap(category, newParent.dn)
      moved            <- if (newParent.dn == oldCategoryEntry.dn.getParent) {
                            LDIFNoopChangeRecord(oldCategoryEntry.dn).succeed
                          } else { con.move(oldCategoryEntry.dn, newParent.dn) }
      result           <- con.save(categoryEntry, removeMissingAttributes = true)
      updated          <- getGroupCategory(category.id)
      autoArchive      <- (moved, result) match {
                            case (_: LDIFNoopChangeRecord, _: LDIFNoopChangeRecord) => "OK, nothing to archive".succeed
                            case _ if (autoExportOnModify && !updated.isSystem)     =>
                              (for {
                                parents  <- getParents_NodeGroupCategory(updated.id)
                                commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                moved    <- gitArchiver.moveNodeGroupCategory(
                                              updated,
                                              oldParents.map(_.id),
                                              parents.map(_.id),
                                              Some((modId, commiter, reason))
                                            )
                              } yield {
                                moved
                              }).chainError("Error when trying to archive automatically the category move")
                            case _                                                  => ZIO.unit
                          }
    } yield {
      updated
    })
  }

  /**
   * Delete the category with the given id.
   * If no category with such id exists, it is a success.
   * If checkEmtpy is set to true, the deletion may be done only if
   * the category is empty (else, category and children are deleted).
   * @return
   *  - Full(category id) for a success
   *  - Failure(with error message) iif an error happened.
   */
  override def delete(
      id:         NodeGroupCategoryId,
      modId:      ModificationId,
      actor:      EventActor,
      reason:     Option[String],
      checkEmpty: Boolean = true
  ): IOResult[NodeGroupCategoryId] = {
    groupLibMutex.writeLock(for {
      con     <- ldap
      deleted <- {
        getCategoryEntry(con, id).flatMap {
          case Some(entry) =>
            for {
              parents     <- if (autoExportOnModify) {
                               getParents_NodeGroupCategory(id)
                             } else Nil.succeed
              ok          <- con
                               .delete(entry.dn, recurse = !checkEmpty)
                               .chainError(s"Error when trying to delete category with ID '${id.value}'")
              category    <- mapper.entry2NodeGroupCategory(entry).toIO
              autoArchive <- (if (autoExportOnModify && ok.size > 0 && !category.isSystem) {
                                for {
                                  commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                  archive  <-
                                    gitArchiver.deleteNodeGroupCategory(id, parents.map(_.id), Some((modId, commiter, reason)))
                                } yield {
                                  archive
                                }
                              } else ZIO.unit).chainError("Error when trying to archive automatically the category deletion")
            } yield {
              id
            }
          case None        => id.succeed
        }
      }
    } yield {
      deleted
    })
  }

  /**
   * Add the node group passed as parameter in the LDAP
   * The id used to save it will be the id provided by the nodeGroup
   */
  override def create(
      nodeGroup: NodeGroup,
      into:      NodeGroupCategoryId,
      modId:     ModificationId,
      actor:     EventActor,
      reason:    Option[String]
  ): IOResult[AddNodeGroupDiff] = {
    groupLibMutex.writeLock(for {
      con           <- ldap
      exists        <- checkNodeGroupExists(con, nodeGroup)
      exists        <- if (exists) {
                         Inconsistency(
                           s"Cannot create a group '${nodeGroup.name}': a group with the same id (${nodeGroup.id.serialize}) or name already exists"
                         ).fail
                       } else ZIO.unit
      categoryEntry <- getCategoryEntry(con, into).notOptional(s"Entry with ID '${into.value}' was not found")
      entry          = mapper.nodeGroupToLdap(nodeGroup, categoryEntry.dn)
      result        <- con.save(entry, removeMissingAttributes = true)
      diff          <- diffMapper.addChangeRecords2NodeGroupDiff(entry.dn, result).toIO
      loggedAction  <- actionlogEffect.saveAddNodeGroup(modId, principal = actor, addDiff = diff, reason = reason)
      // We dont want to check if this is a system group or not, because new groups are not systems (see constructor)
      autoArchive   <- (if (autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord]) {
                          for {
                            parents  <- getParents_NodeGroupCategory(into)
                            commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                            archived <-
                              gitArchiver.archiveNodeGroup(nodeGroup, into :: (parents.map(_.id)), Some((modId, commiter, reason)))
                          } yield archived
                        } else ZIO.unit)
    } yield {
      diff
    })
  }

  override def createPolicyServerTarget(
      policyServer: PolicyServerTarget,
      modId:        ModificationId,
      actor:        EventActor,
      reason:       Option[String]
  ): IOResult[LDIFChangeRecord] = {
    groupLibMutex.writeLock(for {
      con           <- ldap
      categoryEntry <-
        getCategoryEntry(con, NodeGroupCategoryId("SystemGroups")).notOptional("Entry with ID 'SystemGroups' was not found")
      entry          = rudderDit.RULETARGET.ruleTargetModel(
                         policyServer.target,
                         s"${policyServer.target} policy server",
                         categoryEntry.dn,
                         s"Only the ${policyServer.target} policy server"
                       )
      result        <- con.save(entry, removeMissingAttributes = true)
    } yield {
      result
    })
  }

  /**
   * Delete the given policyServerTarget.
   * If no policyServerTarget has such id in the directory, return a success.
   */
  override def deletePolicyServerTarget(policyServer: PolicyServerTarget): IOResult[PolicyServerTarget] = {
    val rdn = rudderDit.RULETARGET.ruleTargetDN(policyServer.target)
    groupLibMutex.writeLock(for {
      con <- ldap
      _   <- getCategoryEntry(con, NodeGroupCategoryId("SystemGroups")).flatMap {
               case Some(categoryEntry) =>
                 val entry = LDAPEntry(new DN(rdn, categoryEntry.dn))
                 con.delete(entry.dn)
               case None                => policyServer.target.succeed
             }
    } yield {
      policyServer
    })
  }

  private def internalUpdate(
      nodeGroup:       NodeGroup,
      modId:           ModificationId,
      actor:           EventActor,
      reason:          Option[String],
      systemCall:      Boolean,
      onlyUpdateNodes: Boolean
  ): IOResult[Option[ModifyNodeGroupDiff]] = {
    groupLibMutex.writeLock(
      for {
        con      <- ldap
        existing <- getSGEntry(con, nodeGroup.id).notOptional(
                      "Error when trying to check for existence of group with id %s. Can not update".format(nodeGroup.id)
                    )
        oldGroup <- mapper
                      .entry2NodeGroup(existing)
                      .toIO
                      .chainError("Error when trying to check for the group %s".format(nodeGroup.id.serialize))
        // check if old group is the same as new group
        result   <- if (oldGroup.equals(nodeGroup)) {
                      None.succeed
                    } else {
                      for {
                        systemCheck <- if (onlyUpdateNodes) {
                                         oldGroup.succeed
                                       } else {
                                         (oldGroup.isSystem, systemCall) match {
                                           case (true, false) =>
                                             "System group '%s' (%s) can not be modified"
                                               .format(oldGroup.name, oldGroup.id.serialize)
                                               .fail
                                           case (false, true) =>
                                             "You can not modify a non system group (%s) with that method"
                                               .format(oldGroup.name)
                                               .fail
                                           case _             => oldGroup.succeed
                                         }
                                       }
                        name        <- checkNameAlreadyInUse(con, nodeGroup.name, nodeGroup.id)
                        exists      <- ZIO.when(name && !onlyUpdateNodes) {
                                         s"Cannot change the group name to ${nodeGroup.name} : there is already a group with the same name".fail
                                       }
                        onlyNodes   <- if (!onlyUpdateNodes) {
                                         ZIO.unit
                                       } else { // check that nothing but the node list changed
                                         if (nodeGroup.copy(serverList = oldGroup.serverList) == oldGroup) {
                                           ZIO.unit
                                         } else {
                                           logPure.debug(
                                             s"Inconsistency when modifying node lists for nodeGroup ${nodeGroup.name}: previous content was ${oldGroup}, new is ${nodeGroup} - only the node list should change"
                                           ) *>
                                           "The group configuration changed compared to the reference group you want to change the node list for. Aborting to preserve consistency".fail
                                         }
                                       }
                        change      <- saveModifyNodeGroupDiff(existing, con, nodeGroup, modId, actor, reason)
                      } yield {
                        change
                      }
                    }
      } yield {
        result
      }
    )
  }

  protected def saveModifyNodeGroupDiff(
      existing:  LDAPEntry,
      con:       RwLDAPConnection,
      nodeGroup: NodeGroup,
      modId:     ModificationId,
      actor:     EventActor,
      reason:    Option[String]
  ): IOResult[Option[ModifyNodeGroupDiff]] = {
    val entry = mapper.nodeGroupToLdap(nodeGroup, existing.dn.getParent)
    // note: this is not protected by a lock because of the risk of calling it in a read lock
    // in a subclass. All callers must call it in a `writeLock`.
    for {
      result       <- con.save(entry, removeMissingAttributes = true).chainError(s"Error when saving entry: ${entry}")
      optDiff      <- diffMapper
                        .modChangeRecords2NodeGroupDiff(existing, result)
                        .toIO
                        .chainError(s"Error when mapping change record to a diff object: ${result}")
      loggedAction <- optDiff match {
                        case None       => ZIO.unit
                        case Some(diff) =>
                          actionlogEffect
                            .saveModifyNodeGroup(modId, principal = actor, modifyDiff = diff, reason = reason)
                            .chainError("Error when logging modification as an event")
                      }
      autoArchive  <-
        (ZIO
          .when(autoExportOnModify && optDiff.isDefined && !nodeGroup.isSystem) { // only persists if modification are present
            for {
              parent   <- getNodeGroupCategory(nodeGroup.id)
              parents  <- getParents_NodeGroupCategory(parent.id)
              commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
              archived <- gitArchiver.archiveNodeGroup(nodeGroup, (parent :: parents).map(_.id), Some((modId, commiter, reason)))
            } yield archived
          })
          .chainError("Error when trying to archive automatically nodegroup change")
    } yield {
      optDiff
    }
  }

  override def updateDynGroupNodes(
      group:  NodeGroup,
      modId:  ModificationId,
      actor:  EventActor,
      reason: Option[String]
  ): IOResult[Option[ModifyNodeGroupDiff]] = {
    internalUpdate(group, modId, actor, reason, systemCall = true, onlyUpdateNodes = true)
  }

  override def update(
      nodeGroup: NodeGroup,
      modId:     ModificationId,
      actor:     EventActor,
      reason:    Option[String]
  ): IOResult[Option[ModifyNodeGroupDiff]] = {
    internalUpdate(nodeGroup, modId, actor, reason, systemCall = false, onlyUpdateNodes = false)
  }

  override def updateSystemGroup(
      nodeGroup: NodeGroup,
      modId:     ModificationId,
      actor:     EventActor,
      reason:    Option[String]
  ): IOResult[Option[ModifyNodeGroupDiff]] = {
    internalUpdate(nodeGroup, modId, actor, reason, systemCall = true, onlyUpdateNodes = false)
  }

  // this one does not seem able to use internalUpdate
  override def updateDiffNodes(
      nodeGroupId: NodeGroupId,
      add:         List[NodeId],
      delete:      List[NodeId],
      modId:       ModificationId,
      actor:       EventActor,
      reason:      Option[String]
  ): IOResult[Option[ModifyNodeGroupDiff]] = {
    groupLibMutex.writeLock(
      for {
        con      <- ldap
        existing <- getSGEntry(con, nodeGroupId).notOptional(
                      s"Error when trying to check for existence of group with id '${nodeGroupId.serialize}'. Can not update"
                    )
        oldGroup <-
          mapper.entry2NodeGroup(existing).toIO.chainError(s"Error when trying to check for the group '${nodeGroupId.serialize}'")
        newGroup  = oldGroup.copy(serverList = (oldGroup.serverList -- delete) ++ add)
        result   <- saveModifyNodeGroupDiff(existing, con, newGroup, modId, actor, reason)
      } yield {
        result
      }
    )
  }

  override def move(
      nodeGroupId: NodeGroupId,
      containerId: NodeGroupCategoryId
  )(implicit cc: ChangeContext): IOResult[Option[ModifyNodeGroupDiff]] = {
    import cc.*
    groupLibMutex.writeLock(for {
      con         <- ldap
      oldParents  <- if (autoExportOnModify) {
                       (for {
                         parent  <- getNodeGroupCategory(nodeGroupId)
                         parents <- getParents_NodeGroupCategory(parent.id)
                       } yield (parent :: parents).map(_.id))
                     } else Nil.succeed
      existing    <- getSGEntry(con, nodeGroupId).notOptional(
                       "Error when trying to check for existence of group with id %s. Can not update".format(nodeGroupId.serialize)
                     )
      oldGroup    <- mapper
                       .entry2NodeGroup(existing)
                       .toIO
                       .chainError("Error when trying to get the existing group with id %s".format(nodeGroupId.serialize))
      systemCheck <- if (oldGroup.isSystem) "You can not move system group".fail else ZIO.unit

      groupRDN      <- existing.rdn.notOptional("Error when retrieving RDN for an existing group - seems like a bug")
      name          <- checkNameAlreadyInUse(con, oldGroup.name, nodeGroupId)
      exists        <- ZIO.when(name) {
                         s"Cannot change the group name to ${oldGroup.name}: there is already a group with the same name".fail
                       }
      newParentDn   <-
        getContainerDn(con, containerId).chainError(s"Couldn't find the new parent category when updating group ${oldGroup.name}")
      result        <- con.move(existing.dn, newParentDn)
      optDiff       <- diffMapper.modChangeRecords2NodeGroupDiff(existing, result).toIO
      loggedAction  <- optDiff match {
                         case None       => ZIO.unit
                         case Some(diff) =>
                           actionlogEffect.saveModifyNodeGroup(modId, principal = actor, modifyDiff = diff, reason = message)
                       }
      res           <- getNodeGroup(nodeGroupId)(using cc.toQuery)
      (nodeGroup, _) = res
      autoArchive   <-
        ZIO
          .when(autoExportOnModify && optDiff.isDefined && !nodeGroup.isSystem) { // only persists if that was a real move (not a move in the same category)
            for {
              get      <- getNodeGroup(nodeGroupId)(using cc.toQuery)
              (ng, cId) = get
              parents  <- getParents_NodeGroupCategory(cId)
              commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
              moved    <- gitArchiver.moveNodeGroup(ng, oldParents, cId :: parents.map(_.id), Some((modId, commiter, message)))
            } yield {
              moved
            }
          }
          .chainError("Error when trying to archive automatically the category move")
    } yield {
      optDiff
    })
  }

  /**
   * Delete the given nodeGroup.
   * If no nodegroup has such id in the directory, return a success.
   * @param id
   * @return
   */
  override def delete(
      id:     NodeGroupId,
      modId:  ModificationId,
      actor:  EventActor,
      reason: Option[String]
  ): IOResult[DeleteNodeGroupDiff] = {
    groupLibMutex.writeLock(for {
      con          <- ldap
      parents      <- if (autoExportOnModify) {
                        (for {
                          parent  <- getNodeGroupCategory(id)
                          parents <- getParents_NodeGroupCategory(parent.id)
                        } yield {
                          (parent :: parents) map (_.id)
                        })
                      } else Nil.succeed
      existing     <-
        getSGEntry(con, id).notOptional("Error when trying to check for existence of group with id %s. Can not update".format(id))
      oldGroup     <- mapper.entry2NodeGroup(existing).toIO
      deleted      <- {
        getSGEntry(con, id, "1.1") flatMap {
          case Some(entry) => {
            for {
              deleted <- con.delete(entry.dn, recurse = false)
            } yield {
              id
            }
          }
          case None        => id.succeed
        }
      }
      diff          = DeleteNodeGroupDiff(oldGroup)
      loggedAction <- actionlogEffect
                        .saveDeleteNodeGroup(modId, principal = actor, deleteDiff = diff, reason = reason)
                        .chainError("Error when saving user event log for node deletion")
      autoArchive  <- ZIO.when(autoExportOnModify && !oldGroup.isSystem) {
                        for {
                          commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                          archive  <- gitArchiver.deleteNodeGroup(id, parents, Some((modId, commiter, reason)))
                        } yield {
                          archive
                        }
                      }
    } yield {
      diff
    })
  }

}
