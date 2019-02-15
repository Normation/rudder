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

import cats.implicits._
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
import com.normation.ldap.sdk.LdapResult._
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.LDAPEntry
import com.normation.ldap.sdk.RoLDAPConnection
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.ldap.sdk.boolean2LDAP
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants.A_GROUP_CATEGORY_UUID
import com.normation.rudder.domain.RudderLDAPConstants.A_IS_ENABLED
import com.normation.rudder.domain.RudderLDAPConstants.A_IS_SYSTEM
import com.normation.rudder.domain.RudderLDAPConstants.A_NODE_GROUP_UUID
import com.normation.rudder.domain.RudderLDAPConstants.A_RULE_TARGET
import com.normation.rudder.domain.RudderLDAPConstants.OC_GROUP_CATEGORY
import com.normation.rudder.domain.RudderLDAPConstants.OC_RUDDER_NODE_GROUP
import com.normation.rudder.domain.RudderLDAPConstants.OC_SPECIAL_TARGET
import com.normation.rudder.domain.nodes.NodeGroupCategory
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes._
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.policies._
import com.normation.rudder.repository.CategoryAndNodeGroup
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.repository.GitNodeGroupArchiver
import com.normation.rudder.repository.GroupCategoryRepositoryOrdering
import com.normation.rudder.repository.NodeGroupCategoryOrdering
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.WoNodeGroupRepository
import com.normation.rudder.services.user.PersonIdentService
import com.normation.utils.Control.sequence
import com.normation.utils.StringUuidGenerator
import com.unboundid.ldap.sdk.DN
import com.unboundid.ldap.sdk.Filter
import net.liftweb.common._

import scala.Option.option2Iterable
import scala.collection.immutable.SortedMap

class RoLDAPNodeGroupRepository(
    val rudderDit     : RudderDit
  , val ldap          : LDAPConnectionProvider[RoLDAPConnection]
  , val mapper        : LDAPEntityMapper
  , val groupLibMutex : ScalaReadWriteLock //that's a scala-level mutex to have some kind of consistency with LDAP
) extends RoNodeGroupRepository with Loggable {
  repo =>

  /**
   * Find sub entries (children group categories and server groups for
   * the given category which MUST be mapped to an entry with the given
   * DN in the LDAP backend, accessible with the given connection.
   */
  private[this] def addSubEntries(category:NodeGroupCategory, dn:DN, con:RoLDAPConnection) : NodeGroupCategory = {
    val subEntries = con.searchOne(dn, OR(IS(OC_GROUP_CATEGORY),IS(OC_RUDDER_NODE_GROUP),IS(OC_SPECIAL_TARGET)),
        A_OC, A_NODE_GROUP_UUID, A_NAME, A_RULE_TARGET, A_DESCRIPTION, A_IS_ENABLED, A_IS_SYSTEM).getOrElse(Seq()).partition(e => e.isA(OC_GROUP_CATEGORY))
    category.copy(
      children = subEntries._1.sortBy(e => e(A_NAME).map(_.toLowerCase())).map(e => mapper.dn2NodeGroupCategoryId(e.dn)).toList,
      items = subEntries._2.sortBy(e => e(A_NAME).map(_.toLowerCase())).flatMap(entry => mapper.entry2RuleTargetInfo(entry) match {
        case Full(targetInfo) => Some(targetInfo)
        case e:EmptyBox =>
          logger.error((e ?~! "Error when trying to get the child of group category '%s' with DN '%s'".format(category.id, entry.dn)).messageChain)
          None
      }).toList
    )
  }

  def getSGEntry(con:RoLDAPConnection, id:NodeGroupId, attributes:String*) : LdapResult[Option[LDAPEntry]] = {
    con.searchSub(rudderDit.GROUP.dn, EQ(A_NODE_GROUP_UUID, id.value), attributes:_*).flatMap { srvEntries  =>
      srvEntries.size match {
        case 0 => None.success
        case 1 => Some(srvEntries(0)).success
        case _ => s"Error, the directory contains multiple occurrence of the server group with ID '${id.value}'. DNs involved: ${srvEntries.map( _.dn).mkString("; ")}".failure
      }
    }
  }

  /**
   * Retrieve the category entry for the given ID, with the given connection
   * Used to get the ldap dn
   */
  def getCategoryEntry(con:RoLDAPConnection, id:NodeGroupCategoryId, attributes:String*) : LdapResult[Option[LDAPEntry]] = {
    groupLibMutex.readLock {
      con.searchSub(rudderDit.GROUP.dn,  EQ(A_GROUP_CATEGORY_UUID, id.value), attributes:_*)
    }.flatMap { categoryEntries => categoryEntries.size match {
      case 0 => None.success
      case 1 => Some(categoryEntries(0)).success
      case _ => s"Error, the directory contains multiple occurrence of group category with id '${id.value}}'. DN: ${categoryEntries.map( _.dn).mkString("; ")}".failure
    } }
  }

  def getGroupsByCategory(includeSystem:Boolean = false) : Box[SortedMap[List[NodeGroupCategoryId], CategoryAndNodeGroup]] = {
    groupLibMutex.readLock { for {
      allCats        <- getAllGroupCategories(includeSystem)
      catsWithGroups <- sequence(allCats) { ligthCat =>
                          for {
                            category <- getGroupCategory(ligthCat.id)
                            parents  <- getParents_NodeGroupCategory(category.id)
                            groups   <- (sequence(category.items) { targetInfo => targetInfo.target match {
                                          case group:GroupTarget => this.getNodeGroup(group.groupId).map{ case(g,catId) => Some(g) }
                                          //just ignore other target type
                                          case _ => Full(None)
                                        } }).map( _.flatten )

                          } yield {
                            ( (category.id :: parents.map(_.id)).reverse, CategoryAndNodeGroup(category, groups.toSet))
                          }
                        }
    } yield {
      implicit val ordering = NodeGroupCategoryOrdering
      SortedMap[List[NodeGroupCategoryId], CategoryAndNodeGroup]() ++ catsWithGroups
    } }
  }

  def getNodeGroup(id: NodeGroupId): Box[(NodeGroup, NodeGroupCategoryId)] = {
    for {
      con     <- ldap
      sgEntry <- groupLibMutex.readLock { getSGEntry(con, id) }.notOptional(s"Error when retrieving the entry for NodeGroup '${id.value}")
      sg      <- (mapper.entry2NodeGroup(sgEntry) ?~! "Error when mapping server group entry to its entity. Entry: %s".format(sgEntry)).toLdapResult
    } yield {
      //a group parent entry is its parent category
      (sg, mapper.dn2NodeGroupCategoryId(sgEntry.dn.getParent))
    }
  }.toBox

  def getAllGroupCategories(includeSystem:Boolean = false) : Box[List[NodeGroupCategory]] = {
    groupLibMutex.readLock { for {
      con             <- ldap
      filter          =  if(includeSystem) IS(OC_GROUP_CATEGORY) else AND(NOT(EQ(A_IS_SYSTEM, true.toLDAPString)),IS(OC_GROUP_CATEGORY))
      categoryEntries <- con.searchSub(rudderDit.GROUP.dn, filter)
      ids             =  categoryEntries.map(e => mapper.dn2NodeGroupCategoryId(e.dn))
      result          <- ids.toList.traverse(id => getGroupCategory(id).toLdapResult)
    } yield {
      result
    } }
  }.toBox

  /**
   * Root group category
   */
  def getRootCategory(): NodeGroupCategory = {
    (for {
      con <- ldap
      rootCategoryEntry <- groupLibMutex.readLock { con.get(rudderDit.GROUP.dn).notOptional("The root category of the server group category seems to be missing in LDAP directory. Please check its content") }
      // look for sub category and technique
      rootCategory <- (mapper.entry2NodeGroupCategory(rootCategoryEntry) ?~! s"Error when mapping from an LDAP entry to a Node Group Category: '${rootCategoryEntry}'").toLdapResult
    } yield {
      addSubEntries(rootCategory,rootCategoryEntry.dn, con)
    }) match {
      case Right(root) => root
      case Left(e)     => throw new RuntimeException(e.msg)
    }
  }

  /**
   * retrieve the hierarchy of group category/group containing the selected node
   */
  protected def findGroupHierarchy(categoryId : NodeGroupCategoryId, targets : Seq[RuleTarget])  : Box[NodeGroupCategory] = {
    for {
      category    <- groupLibMutex.readLock { getGroupCategory(categoryId) }
      newCategory =  new NodeGroupCategory(
                         category.id
                       , category.name
                       , category.description
                       , category.children.filter( x => findGroupHierarchy(x, targets) match {
                            case Full(x) if x.isSystem == false => true
                            case _ => false
                         })
                       , category.items.filter( p => targets.contains(p.target))
                       , category.isSystem
                     )
      checked     <- if ((newCategory.items.size == 0) && (newCategory.children.size==0)) {
                       Failure("Not element found in category hierarchy")
                     } else {
                       Full(newCategory)
                     }
    } yield {
      checked
    }
  }

  /**
   * Return the list of parents for that category, the nearest parent
   * first, until the root of the library.
   * The the last parent is not the root of the library, return a Failure.
   * Also return a failure if the path to top is broken in any way.
   */
  def getParentsForCategory(id:NodeGroupCategoryId, root: NodeGroupCategory) : Box[List[NodeGroupCategory]] = {
    //TODO : LDAPify that, we can have the list of all DN from id to root at the begining
    if(id == root.id) Full(Nil)
    else getParentGroupCategory(id) match {
        case Full(parent) => getParentsForCategory(parent.id, root).map(parents => parent :: parents)
        case e:EmptyBox => e
    }
  }

  /**
   * Get a group category by its id
   * */
  def getGroupCategory(id: NodeGroupCategoryId): Box[NodeGroupCategory] = {
    for {
      con           <- ldap
      categoryEntry <- groupLibMutex.readLock { getCategoryEntry(con, id).notOptional(s"Entry with ID '${id.value}' was not found") }
      category      <- (mapper.entry2NodeGroupCategory( categoryEntry) ?~! s"Error when transforming LDAP entry ${categoryEntry} into a server group category").toLdapResult
    } yield {
      addSubEntries(category,categoryEntry.dn, con)
    }
  }.toBox

  /**
   * Get the direct parent of the given category.
   * Return empty for root of the hierarchy, fails if the category
   * is not in the repository
   */
  def getParentGroupCategory(id: NodeGroupCategoryId): Box[NodeGroupCategory] = {
    groupLibMutex.readLock { for {
      con                 <- ldap
      categoryEntry       <- getCategoryEntry(con, id, "1.1").notOptional(s"Entry with ID '${id.value}' was not found")
      parentCategoryEntry <- con.get(categoryEntry.dn.getParent).notOptional(s"Parent category of entry with ID '${id.value}' was not found")
      parentCategory      <- (mapper.entry2NodeGroupCategory(parentCategoryEntry) ?~! "Error when transforming LDAP entry %s into an active technqiue category".format(parentCategoryEntry)).toLdapResult
    } yield {
      addSubEntries(parentCategory, parentCategoryEntry.dn, con)
    }  }
  }.toBox

  def getParents_NodeGroupCategory(id:NodeGroupCategoryId) : Box[List[NodeGroupCategory]] = {
     //TODO : LDAPify that, we can have the list of all DN from id to root at the begining (just dn.getParent until rudderDit.NOE_GROUP.dn)
    if(id == getRootCategory.id) Full(Nil)
    else getParentGroupCategory(id) match {
      case Full(parent) => getParents_NodeGroupCategory(parent.id).map(parents => parent :: parents)
      case e:EmptyBox => e
    }
 }

 /**
   * Returns all non system categories + the root category
   * Caution, they are "lightweight" group categories (no children)
   */
  def getAllNonSystemCategories(): Box[Seq[NodeGroupCategory]] = {
    groupLibMutex.readLock { for {
       con               <- ldap
       rootCategoryEntry <- con.get(rudderDit.GROUP.dn).notOptional("The root category of the server group category seems to be missing in LDAP directory. Please check its content")
       categoryEntries   <- con.searchSub(rudderDit.GROUP.dn, AND(NOT(EQ(A_IS_SYSTEM, true.toLDAPString)),IS(OC_GROUP_CATEGORY)))
       allEntries        =  categoryEntries :+ rootCategoryEntry
       entries           <- allEntries.toVector.traverse(x => mapper.entry2NodeGroupCategory(x).toLdapResult)
    } yield {
      entries
    } }
  }.toBox

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
  def getCategoryHierarchy : Box[SortedMap[List[NodeGroupCategoryId], NodeGroupCategory]] = {
    for {
      allCats      <- getAllNonSystemCategories().toLdapResult
      rootCat      =  getRootCategory
      catsWithUPs  <- allCats.toVector.traverse { ligthCat =>
                        (for {
                          category <- getGroupCategory(ligthCat.id)
                          parents  <- getParentsForCategory(ligthCat.id, rootCat)
                        } yield {
                          ( (category.id :: parents.map(_.id)).reverse, category )
                        }).toLdapResult
                      }
    } yield {
      implicit val ordering = GroupCategoryRepositoryOrdering
      SortedMap[List[NodeGroupCategoryId], NodeGroupCategory]() ++ catsWithUPs
    }
  }.toBox

  /**
   * Fetch the parent category of the NodeGroup
   * Caution, its a lightweight version of the entry (no children nor item)
   * @param id
   * @return
   */
  def getParentGroupCategory(id: NodeGroupId): Box[NodeGroupCategory] = {
    groupLibMutex.readLock { for {
      con                 <- ldap
      groupEntry          <- getSGEntry(con, id, "1.1").notOptional(s"Entry with ID '${id.value}' was not found")
      parentCategoryEntry <- con.get(groupEntry.dn.getParent).notOptional(s"Parent category of entry with ID '${id.value}' was not found")
      parentCategory      <- (mapper.entry2NodeGroupCategory(parentCategoryEntry) ?~! "Error when transforming LDAP entry %s into an active technique category".format(parentCategoryEntry)).toLdapResult
    } yield {
      parentCategory
    } }
  }.toBox

  def getAll : Box[Seq[NodeGroup]] = {
    groupLibMutex.readLock { for {
      con    <- ldap
      //for each directive entry, map it. if one fails, all fails
      entries <- con.searchSub(rudderDit.GROUP.dn,  EQ(A_OC, OC_RUDDER_NODE_GROUP))
      groups  <- entries.toVector.traverse( groupEntry =>
                   (mapper.entry2NodeGroup(groupEntry) ?~! s"Error when transforming LDAP entry into a Group instance. Entry: ${groupEntry}").toLdapResult
                 )
    } yield {
      groups
    } }
  }.toBox

  private[this] def findGroupWithFilter(filter:Filter) : Box[Seq[NodeGroupId]] = {
    groupLibMutex.readLock { for {
      con      <- ldap
      entries  <- con.searchSub(rudderDit.GROUP.dn,  filter, "1.1")
      groupIds <- entries.toVector.traverse { entry =>
                    (rudderDit.GROUP.getGroupId(entry.dn) ?~! "DN '%s' seems to not be a valid group DN".format(entry.dn)).toLdapResult
                  }
    } yield {
      groupIds.map(id => NodeGroupId(id))
    } }
  }.toBox

  /**
   * Retrieve all groups that have at least one of the given
   * node ID in there member list.
   * @param nodeIds
   * @return
   */
  def findGroupWithAnyMember(nodeIds:Seq[NodeId]) : Box[Seq[NodeGroupId]] = {
    val filter = AND(
       IS(OC_RUDDER_NODE_GROUP),
       OR(nodeIds.distinct.map(nodeId => EQ(LDAPConstants.A_NODE_UUID, nodeId.value)):_*)
    )
    findGroupWithFilter(filter)
  }

  /**
   * Retrieve all groups that have ALL given node ID in their
   * member list.
   * @param nodeIds
   * @return
   */
  def findGroupWithAllMember(nodeIds:Seq[NodeId]) : Box[Seq[NodeGroupId]] = {
    val filter = AND(
       IS(OC_RUDDER_NODE_GROUP),
       AND(nodeIds.distinct.map(nodeId => EQ(LDAPConstants.A_NODE_UUID, nodeId.value)):_*)
    )
    findGroupWithFilter(filter)
  }

  /**
   * Get the full group tree with all information
   * for categories and groups.
   * Returns the objects sorted by name within
   */
  def getFullGroupLibrary() : Box[FullNodeGroupCategory] = {

    case class AllMaps(
        categories: Map[NodeGroupCategoryId, NodeGroupCategory]
      , categoriesByCategory: Map[NodeGroupCategoryId, List[NodeGroupCategoryId]]
      , targetByCategory: Map[NodeGroupCategoryId, List[FullRuleTargetInfo]]
    )

    def fromCategory(catId: NodeGroupCategoryId, maps:AllMaps): FullNodeGroupCategory = {
      def getCat(id: NodeGroupCategoryId) = maps.categories.getOrElse(id, throw new IllegalArgumentException(s"Missing categories with id ${catId} in the list of available categories"))
      val cat = getCat(catId)

      FullNodeGroupCategory(
          id = catId
        , name = cat.name
        , description = cat.description
        , subCategories = maps.categoriesByCategory.getOrElse(catId, Nil).map { id =>
            fromCategory(id, maps)
          }.sortBy(_.name)
        , targetInfos = maps.targetByCategory.getOrElse(catId, Nil).sortBy(_.name)
        , isSystem = cat.isSystem
      )
    }

    /*
     * strategy: load the full subtree from the root category id,
     * then process entrie mapping them to their light version,
     * then call fromCategory on the root (we know its id).
     */

    val emptyAll = AllMaps(Map(), Map(), Map())
    import rudderDit.GROUP._

    def mappingError(current:AllMaps, e:LDAPEntry, eb:EmptyBox) : AllMaps = {
      val error = eb ?~! s"Error when mapping entry with DN '${e.dn.toString}' from node groups library"
      logger.warn(error.messageChain)
      current
    }

    for {
      con     <- ldap
      entries <- groupLibMutex.readLock( con.getTree(rudderDit.GROUP.dn) ).notOptional("The root category of the node group library seems to be missing in LDAP directory. Please check its content")
    } yield {
      val allMaps =  (emptyAll /: entries.toSeq) { case (current, e) =>
         if(isACategory(e)) {
           mapper.entry2NodeGroupCategory(e) match {
             case Full(category) =>
               //for categories other than root, add it in the list
               //of its parent subcategories
               val updatedSubCats = if(e.dn == rudderDit.GROUP.dn) {
                 current.categoriesByCategory
               } else {
                 val catId = mapper.dn2NodeGroupCategoryId(e.dn.getParent)
                 val subCats = category.id :: current.categoriesByCategory.getOrElse(catId, Nil)
                 current.categoriesByCategory + (catId -> subCats)
               }
               current.copy(
                   categories = current.categories + (category.id -> category)
                 , categoriesByCategory =  updatedSubCats
               )
             case eb:EmptyBox => mappingError(current, e, eb)
           }
         } else if(isAGroup(e) || isASpecialTarget(e)){
           mapper.entry2RuleTargetInfo(e) match {
             case Full(info) =>
               val fullRuleTarget = info.target match {
                 case GroupTarget(groupId) =>
                   FullGroupTarget(
                       target = GroupTarget(groupId)
                     , nodeGroup = mapper.entry2NodeGroup(e) match {
                         case Full(g) => g
                         case eb:EmptyBox => return eb //yeah, a return
                       }
                   )
                 case t:NonGroupRuleTarget =>
                   FullOtherTarget(t)
                 case rt: CompositeRuleTarget => FullCompositeRuleTarget(rt)
               }

               val fullInfo = FullRuleTargetInfo(
                   target = fullRuleTarget
                 , name = info.name
                 , description = info.description
                 , isEnabled = info.isEnabled
                 , isSystem = info.isSystem
               )

               val catId = mapper.dn2NodeGroupCategoryId(e.dn.getParent)
               val infosForCatId = fullInfo :: current.targetByCategory.getOrElse(catId, Nil)
               current.copy(
                   targetByCategory = current.targetByCategory + (catId -> infosForCatId)
               )
             case eb:EmptyBox => mappingError(current, e, eb)
           }
         } else {
           //log error, continue
           logger.warn(s"Entry with DN '${e.dn}' was ignored because it is of an unknow type. Known types are categories, active techniques, directives")
           current
         }
      }

      fromCategory(NodeGroupCategoryId(rudderDit.GROUP.rdnValue._1), allMaps)
    }
  }.toBox
}

class WoLDAPNodeGroupRepository(
    roGroupRepo       : RoLDAPNodeGroupRepository
  , ldap              : LDAPConnectionProvider[RwLDAPConnection]
  , diffMapper        : LDAPDiffMapper
  , uuidGen           : StringUuidGenerator
  , actionLogger      : EventLogRepository
  , gitArchiver       : GitNodeGroupArchiver
  , personIdentService: PersonIdentService
  , autoExportOnModify: Boolean
) extends WoNodeGroupRepository with Loggable {
  repo =>

  import roGroupRepo._

  /**
   * Check if a group category exist with the given name
   */
  private[this] def categoryExists(con:RoLDAPConnection, name : String, parentDn : DN) : Boolean = {
    con.searchOne(parentDn, AND(IS(OC_GROUP_CATEGORY), EQ(A_NAME, name)), A_GROUP_CATEGORY_UUID).size match {
      case 0 => false
      case 1 => true
      case _ => logger.error("More than one nodeCategory has %s name under %s".format(name, parentDn)); true
    }
  }

    /**
   * Check if a group category exist with the given name
   */
  private[this] def categoryExists(con:RoLDAPConnection, name : String, parentDn : DN, currentId : NodeGroupCategoryId) : Boolean = {
    con.searchOne(parentDn, AND(NOT(EQ(A_GROUP_CATEGORY_UUID, currentId.value)), AND(IS(OC_GROUP_CATEGORY), EQ(A_NAME, name))), A_GROUP_CATEGORY_UUID).size match {
      case 0 => false
      case 1 => true
      case _ => logger.error("More than one nodeCategory has %s name under %s".format(name, parentDn)); true
    }
  }

  /**
   * Check if a nodeGroup exists(id already exists) and name is unique
   */
  private[this] def checkNodeGroupExists(con:RoLDAPConnection, group : NodeGroup) : Boolean = {
    con.searchSub(rudderDit.GROUP.dn, AND(IS(OC_RUDDER_NODE_GROUP), OR(EQ(A_NODE_GROUP_UUID, group.id.value),EQ(A_NAME, group.name))), A_NODE_GROUP_UUID).size match {
      case 0 => false
      case 1 => true
      case _ => logger.error(s"There is more than one node group with id '${group.id.value}' or name '${group.name}'")
                true
    }
  }

  /**
   * Check if another nodeGroup exist with the given name
   */
  private[this] def checkNameAlreadyInUse(con:RoLDAPConnection, name : String, id: NodeGroupId) : Boolean = {
    con.searchSub(rudderDit.GROUP.dn, AND(NOT(EQ(A_NODE_GROUP_UUID, id.value)), AND(EQ(A_OC, OC_RUDDER_NODE_GROUP), EQ(A_NAME, name))), A_NODE_GROUP_UUID).size match {
      case 0 => false
      case 1 => true
      case _ => logger.error(s"More than one node group has '${name}' name")
                true
    }
  }

  private[this] def getContainerDn(con : RoLDAPConnection, id: NodeGroupCategoryId) : Box[DN] = {
    groupLibMutex.readLock { con.searchSub(rudderDit.GROUP.dn, AND(IS(OC_GROUP_CATEGORY), EQ(A_GROUP_CATEGORY_UUID, id.value)), A_GROUP_CATEGORY_UUID).toList match {
      case Nil => Empty
      case (head : com.normation.ldap.sdk.LDAPEntry) :: Nil => Full(head.dn)
      case _ => logger.error("Too many NodeGroupCategory found with this id %s".format(id.value))
                Failure("Too many NodeGroupCategory found with this id %s".format(id.value))
    } }
  }

  /**
   * Add that group category into the given parent category
   * Fails if the parent category does not exist or
   * if it already contains that category.
   *
   * return the new category.
   */
  override def addGroupCategorytoCategory(
      that: NodeGroupCategory
    , into: NodeGroupCategoryId
    , modId : ModificationId
    , actor:EventActor, reason: Option[String]
  ): Box[NodeGroupCategory] = {
    for {
      con                 <- ldap
      parentCategoryEntry <- getCategoryEntry(con, into, "1.1").notOptional("The parent category '%s' was not found, can not add".format(into))
      canAddByName        <- if (categoryExists(con, that.name, parentCategoryEntry.dn))
                               "Cannot create the Node Group Category with name %s : a category with the same name exists at the same level".format(that.name).failure
                             else "OK, can add".success
      categoryEntry       =  mapper.nodeGroupCategory2ldap(that,parentCategoryEntry.dn)
      result              <- groupLibMutex.writeLock { con.save(categoryEntry, removeMissingAttributes = true) }
      autoArchive         <- (if(autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord] && !that.isSystem) {
                               for {
                                 parents  <- getParents_NodeGroupCategory(that.id)
                                 commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                 archive  <- gitArchiver.archiveNodeGroupCategory(that,parents.map( _.id), Some((modId, commiter, reason)))
                               } yield archive
                             } else Full("ok")).toLdapResult
      newCategory         <- (getGroupCategory(that.id) ?~! "The newly created category '%s' was not found".format(that.id.value)).toLdapResult
    } yield {
      newCategory
    }
  }.toBox

  /**
   * Update an existing group category
   */
  override def saveGroupCategory(category: NodeGroupCategory, modId : ModificationId, actor:EventActor, reason: Option[String]): Box[NodeGroupCategory] = {
    repo.synchronized { for {
      con              <- ldap
      oldCategoryEntry <- getCategoryEntry(con, category.id, "1.1").notOptional("Entry with ID '%s' was not found".format(category.id))
      categoryEntry    =  mapper.nodeGroupCategory2ldap(category,oldCategoryEntry.dn.getParent)
      canAddByName     <- if (categoryExists(con, category.name, oldCategoryEntry.dn.getParent, category.id))
                            "Cannot update the Node Group Category with name %s : a category with the same name exists at the same level".format(category.name).failure
                          else "OK".success
      result           <- groupLibMutex.writeLock { con.save(categoryEntry, removeMissingAttributes = true) }
      updated          <- getGroupCategory(category.id).toLdapResult
      // Maybe we have to check if the parents are system or not too
      autoArchive      <- (if(autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord] && !category.isSystem) {
                             for {
                              parents  <- getParents_NodeGroupCategory(category.id)
                              commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                              archive  <- gitArchiver.archiveNodeGroupCategory(updated,parents.map( _.id), Some((modId, commiter, reason)))
                            } yield archive
                          } else Full("ok")).toLdapResult
    } yield {
      updated
    } }
  }.toBox

   /**
   * Update/move an existing group category
   */
  override def saveGroupCategory(category: NodeGroupCategory, containerId : NodeGroupCategoryId, modId : ModificationId, actor:EventActor, reason: Option[String]): Box[NodeGroupCategory] = {
    repo.synchronized { for {
      con              <- ldap
      oldParents       <- if(autoExportOnModify) {
                            getParents_NodeGroupCategory(category.id).toLdapResult
                          } else Nil.success
      oldCategoryEntry <- getCategoryEntry(con, category.id, "1.1").notOptional("Entry with ID '%s' was not found".format(category.id))
      newParent        <- getCategoryEntry(con, containerId, "1.1").notOptional("Parent entry with ID '%s' was not found".format(containerId))
      canAddByName     <- if (categoryExists(con, category.name, newParent.dn, category.id))
                            "Cannot update the Node Group Category with name %s : a category with the same name exists at the same level".format(category.name).failure
                          else "OK".success
      categoryEntry    =  mapper.nodeGroupCategory2ldap(category,newParent.dn)
      moved            <- if (newParent.dn == oldCategoryEntry.dn.getParent) {
                            LDIFNoopChangeRecord(oldCategoryEntry.dn).success
                          } else { groupLibMutex.writeLock { con.move(oldCategoryEntry.dn, newParent.dn) } }
      result           <- groupLibMutex.writeLock { con.save(categoryEntry, removeMissingAttributes = true) }
      updated          <- getGroupCategory(category.id).toLdapResult
      autoArchive      <- (moved, result) match {
                            case (_:LDIFNoopChangeRecord, _:LDIFNoopChangeRecord) => "OK, nothing to archive".success
                            case _ if(autoExportOnModify && !updated.isSystem) =>
                              ((for {
                                parents  <- getParents_NodeGroupCategory(updated.id)
                                commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                moved    <- gitArchiver.moveNodeGroupCategory(updated, oldParents.map( _.id), parents.map( _.id), Some((modId, commiter, reason)))
                              } yield {
                                moved
                              }) ?~! "Error when trying to archive automatically the category move").toLdapResult
                            case _ => "ok".success
                          }
    } yield {
      updated
    } }
  }.toBox

  /**
   * Delete the category with the given id.
   * If no category with such id exists, it is a success.
   * If checkEmtpy is set to true, the deletion may be done only if
   * the category is empty (else, category and children are deleted).
   * @param id
   * @param checkEmtpy
   * @return
   *  - Full(category id) for a success
   *  - Failure(with error message) iif an error happened.
   */
  override def delete(id:NodeGroupCategoryId, modId : ModificationId, actor:EventActor, reason: Option[String], checkEmpty:Boolean = true) : Box[NodeGroupCategoryId] = {
    for {
      con <-ldap
      deleted <- {
        getCategoryEntry(con, id).flatMap {
          case Some(entry) =>
            for {
              parents     <- if(autoExportOnModify) {
                               getParents_NodeGroupCategory(id).toLdapResult
                             } else Nil.success
              ok          <- groupLibMutex.writeLock { con.delete(entry.dn, recurse = !checkEmpty) ?~! "Error when trying to delete category with ID '%s'".format(id) }
              category    <- mapper.entry2NodeGroupCategory(entry).toLdapResult
              autoArchive <- ((if(autoExportOnModify && ok.size > 0 && !category.isSystem) {
                               for {
                                 commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                 archive  <- gitArchiver.deleteNodeGroupCategory(id,parents.map( _.id), Some((modId, commiter, reason)))
                               } yield {
                                 archive
                               }
                             } else Full("ok") )  ?~! "Error when trying to archive automatically the category deletion").toLdapResult
            } yield {
              id
            }
          case None => id.success
        }
      }
    } yield {
      deleted
    }
  }.toBox

  /**
   * Add the node group passed as parameter in the LDAP
   * The id used to save it will be the id provided by the nodeGroup
   */
  override def create(nodeGroup: NodeGroup, into: NodeGroupCategoryId, modId: ModificationId, actor:EventActor, reason:Option[String]): Box[AddNodeGroupDiff] = {
    for {
      con           <- ldap
      exists        <- if (checkNodeGroupExists(con, nodeGroup))
                         s"Cannot create a group '${nodeGroup.name}': a group with the same id (${nodeGroup.id.value}) or name already exists".failure
                       else Unit.success
      categoryEntry <- getCategoryEntry(con, into).notOptional("Entry with ID '%s' was not found".format(into))
      entry         =  rudderDit.GROUP.groupModel(
                           nodeGroup.id.value
                         , categoryEntry.dn
                         , nodeGroup.name
                         , nodeGroup.description
                         , nodeGroup.query
                         , nodeGroup.isDynamic
                         , nodeGroup.serverList
                         , nodeGroup.isEnabled
                       )
      result        <- groupLibMutex.writeLock { con.save(entry, true) }
      diff          <- diffMapper.addChangeRecords2NodeGroupDiff(entry.dn, result).toLdapResult
      loggedAction  <- actionLogger.saveAddNodeGroup(modId, principal = actor, addDiff = diff, reason = reason ).toLdapResult
      // We dont want to check if this is a system group or not, because new groups are not systems (see constructor)
      autoArchive   <- (if(autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord]) {
                         for {
                           parents  <- getParents_NodeGroupCategory(into)
                           commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                           archived <- gitArchiver.archiveNodeGroup(nodeGroup, into :: (parents.map( _.id)), Some((modId, commiter, reason)))
                         } yield archived
                       } else Full("ok")).toLdapResult
    } yield {
      diff
    }
  }.toBox

  private[this] def internalUpdate(nodeGroup:NodeGroup, modId: ModificationId, actor:EventActor, reason:Option[String], systemCall:Boolean, onlyUpdateNodes: Boolean): Box[Option[ModifyNodeGroupDiff]] = this.synchronized {
    for {
      con          <- ldap
      existing     <- getSGEntry(con, nodeGroup.id).notOptional("Error when trying to check for existence of group with id %s. Can not update".format(nodeGroup.id))
      oldGroup     <- (mapper.entry2NodeGroup(existing) ?~! "Error when trying to check for the group %s".format(nodeGroup.id.value)).toLdapResult
      systemCheck  <- if(onlyUpdateNodes) {
                        oldGroup.success
                      } else (oldGroup.isSystem, systemCall) match {
                        case (true, false) => "System group '%s' (%s) can not be modified".format(oldGroup.name, oldGroup.id.value).failure
                        case (false, true) => "You can not modify a non system group (%s) with that method".format(oldGroup.name).failure
                        case _ => oldGroup.success
                      }
      exists       <- if (checkNameAlreadyInUse(con, nodeGroup.name, nodeGroup.id) && !onlyUpdateNodes) {
                        s"Cannot change the group name to ${nodeGroup.name} : there is already a group with the same name".failure
                      } else Unit.success
      onlyNodes    <- if(!onlyUpdateNodes) {
                        "ok".success
                      } else { //check that nothing but the node list changed
                        if(nodeGroup.copy(serverList = oldGroup.serverList) == oldGroup) {
                          "ok".success
                        } else {
                          "The group configuration changed compared to the reference group you want to change the node list for. Aborting to preserve consistency".failure
                        }
                      }
      entry        =  rudderDit.GROUP.groupModel(
                          nodeGroup.id.value
                        , existing.dn.getParent
                        , nodeGroup.name
                        , nodeGroup.description
                        , nodeGroup.query
                        , nodeGroup.isDynamic
                        , nodeGroup.serverList
                        , nodeGroup.isEnabled
                        , nodeGroup.isSystem
                      )
      result       <- groupLibMutex.writeLock { con.save(entry, true) ?~! "Error when saving entry: %s".format(entry) }
      optDiff      <- (diffMapper.modChangeRecords2NodeGroupDiff(existing, result) ?~! "Error when mapping change record to a diff object: %s".format(result)).toLdapResult
      loggedAction <- optDiff match {
                        case None => "OK".success
                        case Some(diff) =>
                          (actionLogger.saveModifyNodeGroup(modId, principal = actor, modifyDiff = diff, reason = reason) ?~! "Error when logging modification as an event").toLdapResult
                      }
      autoArchive  <- (if(autoExportOnModify && optDiff.isDefined && !nodeGroup.isSystem) { //only persists if modification are present
                        for {
                          parent   <- getParentGroupCategory(nodeGroup.id)
                          parents  <- getParents_NodeGroupCategory(parent.id)
                          commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                          archived <- gitArchiver.archiveNodeGroup(nodeGroup, (parent :: parents).map( _.id), Some((modId, commiter, reason)))
                        } yield archived
                      } else Full("ok")).success
    } yield {
      optDiff
    }
  }.toBox

  override def updateDynGroupNodes(group:NodeGroup, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[Option[ModifyNodeGroupDiff]] = {
    internalUpdate(group, modId, actor, reason, true, true)
  }

  override def update(nodeGroup:NodeGroup, modId: ModificationId, actor:EventActor, reason:Option[String]): Box[Option[ModifyNodeGroupDiff]] = {
    internalUpdate(nodeGroup, modId, actor, reason, false, false)
  }

  override def updateSystemGroup(nodeGroup:NodeGroup, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[Option[ModifyNodeGroupDiff]] = {
    internalUpdate(nodeGroup, modId, actor, reason, true, false)
  }

  override def move(nodeGroupId:NodeGroupId, containerId : NodeGroupCategoryId, modId: ModificationId, actor:EventActor, reason:Option[String]): Box[Option[ModifyNodeGroupDiff]] = {

    for {
      con          <- ldap
      oldParents   <- if(autoExportOnModify) {
                        (for {
                          parent   <- getParentGroupCategory(nodeGroupId)
                          parents  <- getParents_NodeGroupCategory(parent.id)
                        } yield (parent::parents).map( _.id )).toLdapResult
                      } else Nil.success
      existing     <- getSGEntry(con, nodeGroupId).notOptional("Error when trying to check for existence of group with id %s. Can not update".format(nodeGroupId.value))
      oldGroup     <- (mapper.entry2NodeGroup(existing) ?~! "Error when trying to get the existing group with id %s".format(nodeGroupId.value)).toLdapResult
      systemCheck  <- if(oldGroup.isSystem) "You can not move system group".failure else "OK".success

      groupRDN     <- existing.rdn.success.notOptional("Error when retrieving RDN for an exising group - seems like a bug")
      exists       <- if (checkNameAlreadyInUse(con, oldGroup.name, nodeGroupId)) {
                        s"Cannot change the group name to ${oldGroup.name}: there is already a group with the same name".failure
                      } else Unit.success
      newParentDn  <- (getContainerDn(con, containerId) ?~! "Couldn't find the new parent category when updating group %s".format(oldGroup.name)).toLdapResult
      result       <- groupLibMutex.writeLock { con.move(existing.dn, newParentDn) }
      optDiff      <- diffMapper.modChangeRecords2NodeGroupDiff(existing, result).toLdapResult
      loggedAction <- optDiff match {
                        case None => "OK".success
                        case Some(diff) => actionLogger.saveModifyNodeGroup(modId, principal = actor, modifyDiff = diff, reason = reason ).toLdapResult
                      }
      res          <- getNodeGroup(nodeGroupId).toLdapResult
      (nodeGroup,_) = res
      autoArchive  <- ((if(autoExportOnModify && optDiff.isDefined && !nodeGroup.isSystem) { //only persists if that was a real move (not a move in the same category)
                         for {
                           (ng,cId) <- getNodeGroup(nodeGroupId)
                           parents  <- getParents_NodeGroupCategory(cId)
                           commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                           moved    <- gitArchiver.moveNodeGroup(ng, oldParents, cId :: parents.map( _.id ), Some((modId, commiter, reason)))
                         } yield {
                           moved
                         }
                       } else Full("ok") ) ?~! "Error when trying to archive automatically the category move").toLdapResult
    } yield {
      optDiff
    }
  }.toBox

  /**
   * Delete the given nodeGroup.
   * If no nodegroup has such id in the directory, return a success.
   * @param id
   * @return
   */
  override def delete(id:NodeGroupId, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[DeleteNodeGroupDiff] = {
    for {
      con          <- ldap
      parents      <- if(autoExportOnModify) {
                        (for {
                          parent   <- getParentGroupCategory(id)
                          parents  <- getParents_NodeGroupCategory(parent.id)
                        } yield {
                          (parent :: parents ) map ( _.id )
                        }).toLdapResult
                      } else Nil.success
      existing     <- getSGEntry(con, id).notOptional("Error when trying to check for existence of group with id %s. Can not update".format(id))
      oldGroup     <- mapper.entry2NodeGroup(existing).toLdapResult
      deleted      <- {
                        getSGEntry(con,id, "1.1") flatMap  {
                          case Some(entry) => {
                            for {
                              deleted <- groupLibMutex.writeLock { con.delete(entry.dn, recurse = false) }
                            } yield {
                              id
                            }
                          }
                          case None => id.success
                        }
                      }
      diff         =  DeleteNodeGroupDiff(oldGroup)
      loggedAction <- (actionLogger.saveDeleteNodeGroup(modId, principal = actor, deleteDiff = diff, reason = reason ) ?~! "Error when saving user event log for node deletion").toLdapResult
      autoArchive  <- (if(autoExportOnModify && !oldGroup.isSystem) {
                        for {
                          commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                          archive  <- gitArchiver.deleteNodeGroup(id, parents, Some((modId, commiter, reason)))
                        } yield {
                          archive
                        }
                      } else Full("ok")).toLdapResult
    } yield {
      diff
    }
  }.toBox

}
