/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.rule.category

import com.normation.rudder.domain.RudderDit
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.utils.ScalaReadWriteLock
import net.liftweb.common._
import com.normation.ldap.sdk.RoLDAPConnection
import com.unboundid.ldap.sdk.Filter._
import com.normation.ldap.sdk.BuildFilter._
import com.unboundid.ldap.sdk.DN
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.ldap.sdk.LDAPEntry
import scala.collection.immutable.SortedMap
import com.normation.ldap.sdk._
import com.normation.utils.Control.{boxSequence, sequence}
import com.normation.utils.Utils
import com.normation.utils.StringUuidGenerator
import com.normation.eventlog.ModificationId
import com.normation.eventlog.EventActor
import com.normation.ldap.ldif.LDIFNoopChangeRecord
import com.normation.rudder.services.user.PersonIdentService
import com.normation.rudder.repository.GitRuleArchiver
import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.ResultCode


/**
 * Here is the ordering for a List[NodeGroupCategoryId]
 * MUST start by the root !
 */
object RuleCategoryOrdering extends Ordering[List[RuleCategoryId]] {
  type ID = RuleCategoryId
  override def compare(x:List[ID],y:List[ID]) = {
    Utils.recTreeStringOrderingCompare(x.map( _.value ), y.map( _.value ))
  }
}

class RoLDAPRuleCategoryRepository(
    val rudderDit     : RudderDit
  , val ldap          : LDAPConnectionProvider[RoLDAPConnection]
  , val mapper        : LDAPEntityMapper
  , val groupLibMutex : ScalaReadWriteLock //that's a scala-level mutex to have some kind of consistency with LDAP
) extends RoRuleCategoryRepository with Loggable {
  repo =>

  /**
   * Find sub entries (children group categories and server groups for
   * the given category which MUST be mapped to an entry with the given
   * DN in the LDAP backend, accessible with the given connection.
   */
  private[this] def addSubEntries(category:RuleCategory, dn:DN, con:RoLDAPConnection) : RuleCategory = {
    val subEntries = con.searchOne(dn, IS(OC_RULE_CATEGORY),
        A_OC, A_RULE_CATEGORY_UUID, A_NAME, A_RULE_TARGET, A_DESCRIPTION, A_IS_ENABLED, A_IS_SYSTEM).partition(e => e.isA(OC_RULE_CATEGORY))
    category.copy(
      childs = subEntries._1.sortBy(e => e(A_NAME)).
                 flatMap{ e =>
                          val category = mapper.entry2RuleCategory(e)
                          category.map(addSubEntries(_,e.dn,con))
                          }.toList
      )
  }
/*
  def getSGEntry(con:RoLDAPConnection, id:RuleId, attributes:String*) : Box[LDAPEntry] = {
    val srvEntries = con.searchSub(rudderDit.RULECATEGORY.dn, EQ(A_RULE_CATEGORY_UUID, id.value), attributes:_*)
    srvEntries.size match {
      case 0 => Empty
      case 1 => Full(srvEntries(0))
      case _ => Failure("Error, the directory contains multiple occurrence of the server group with ID %s. DNs involved: %s".format(id, srvEntries.map( _.dn).mkString("; ")))
    }
  }
*/

  def get(id:RuleCategoryId) : Box[RuleCategory] = {
    for {
      con      <- ldap
      entry    <- getCategoryEntry(con, id)
      category <- mapper.entry2RuleCategory(entry)
    } yield {
      category
    }
  }
  /**
   * Retrieve the category entry for the given ID, with the given connection
   * Used to get the ldap dn
   */
  def getCategoryEntry(con:RoLDAPConnection, id:RuleCategoryId, attributes:String*) : Box[LDAPEntry] = {
    val categoryEntries = groupLibMutex.readLock {
      con.searchSub(rudderDit.RULECATEGORY.dn,  EQ(A_RULE_CATEGORY_UUID, id.value), attributes:_*)
    }
    categoryEntries.size match {
      case 0 => Empty
      case 1 => Full(categoryEntries(0))
      case _ => Failure("Error, the directory contains multiple occurrence of group category with id %s. DN: %s".format(id, categoryEntries.map( _.dn).mkString("; ")))
    }
  }

  /**
   * Root group category
   */
  override def getRootCategory(): Box[RuleCategory] = {
    (for {
      con <- ldap
      rootCategoryEntry <- groupLibMutex.readLock { con.get(rudderDit.RULECATEGORY.dn) ?~! "The root category of the Rule category seems to be missing in LDAP directory. Please check its content" }
      // look for sub category and technique
      rootCategory <- mapper.entry2RuleCategory(rootCategoryEntry) ?~! "Error when mapping from an LDAP entry to a RuleCategory: %s".format(rootCategoryEntry)
    } yield {
      addSubEntries(rootCategory,rootCategoryEntry.dn, con)
    })
  }

  /**
   * Return the list of parents for that category, the nearest parent
   * first, until the root of the library.
   * The the last parent is not the root of the library, return a Failure.
   * Also return a failure if the path to top is broken in any way.
   */


  def getParents(id:RuleCategoryId) : Box[List[RuleCategory]] = {
    //TODO : LDAPify that, we can have the list of all DN from id to root at the begining
    if(id == rudderDit.RULECATEGORY.rootCategoryId) getRootCategory.map(_ :: Nil)
    else (getParentGroupCategory(id),get(id)) match {
        case (Full(parent),Full(category)) => getParents(parent.id).map(_ ++ (category :: Nil))
        case (e:EmptyBox,_) => e
        case (_,e:EmptyBox) => e
    }
  }

  def getParentsForCategory(id:RuleCategoryId) : Box[List[RuleCategory]] = {
    //TODO : LDAPify that, we can have the list of all DN from id to root at the begining
    if(id == rudderDit.RULECATEGORY.rootCategoryId) Full(Nil)
    else getParentGroupCategory(id) match {
        case Full(parent : RuleCategory) => getParentsForCategory(parent.id).map(parents => parent :: parents)
        case e:EmptyBox => e
    }
  }


  /**
   * Get a group category by its id
   * */
  def getGroupCategory(id: RuleCategoryId): Box[RuleCategory] = {
    for {
      con <- ldap
      categoryEntry <- groupLibMutex.readLock { getCategoryEntry(con, id) ?~! "Entry with ID '%s' was not found".format(id) }
      category <- mapper.entry2RuleCategory(categoryEntry) ?~! "Error when transforming LDAP entry %s into a server group category".format(categoryEntry)
    } yield {
      addSubEntries(category,categoryEntry.dn, con)
    }
  }

  /**
   * Get the direct parent of the given category.
   * Return empty for root of the hierarchy, fails if the category
   * is not in the repository
   */
  def getParentGroupCategory(id: RuleCategoryId): Box[RuleCategory] = {
    groupLibMutex.readLock { for {
      con <- ldap
      categoryEntry <- getCategoryEntry(con, id, "1.1") ?~! "Entry with ID '%s' was not found".format(id)
      parentCategoryEntry <- con.get(categoryEntry.dn.getParent)
      parentCategory <- mapper.entry2RuleCategory(parentCategoryEntry) ?~! "Error when transforming LDAP entry %s into an active technqiue category".format(parentCategoryEntry)
    } yield {
      addSubEntries(parentCategory, parentCategoryEntry.dn, con)
    }  }
  }


  def getParents_RuleCategory(id:RuleCategoryId) : Box[List[RuleCategory]] = {
     //TODO : LDAPify that, we can have the list of all DN from id to root at the begining (just dn.getParent until rudderDit.NOE_RULECATEGORY.dn)
    for {
      root <- getRootCategory
      res <- if(id == root.id) {
               Full(Nil)
             } else {
               getParentGroupCategory(id) match {
                 case Full(parent:RuleCategory) =>
                   getParents_RuleCategory(parent.id).map(parents => parent :: parents)
                 case e:EmptyBox =>
                   e
               }
             }
    } yield {
      res
    }
 }

}

class WoLDAPRuleCategoryRepository(
    roruleCategoryRepo : RoLDAPRuleCategoryRepository
  , ldap               : LDAPConnectionProvider[RwLDAPConnection]
  //, diffMapper        : LDAPDiffMapper
  , uuidGen            : StringUuidGenerator
  , gitArchiver       : GitRuleArchiver
  , personIdentService: PersonIdentService
  , autoExportOnModify: Boolean
) extends WoRuleCategoryRepository with Loggable {
  repo =>

  import roruleCategoryRepo.{ldap => roLdap, _}

  /**
   * Check if a group category exist with the given name
   */
  private[this] def categoryExists(con:RoLDAPConnection, name : String, parentDn : DN) : Boolean = {
    con.searchOne(parentDn, AND(IS(OC_RULE_CATEGORY), EQ(A_NAME, name)), A_RULE_CATEGORY_UUID).size match {
      case 0 => false
      case 1 => true
      case _ => logger.error("More than one nodeCategory has %s name under %s".format(name, parentDn)); true
    }
  }

    /**
   * Check if a group category exist with the given name
   */
  private[this] def categoryExists(con:RoLDAPConnection, name : String, parentDn : DN, currentId : RuleCategoryId) : Boolean = {
    con.searchOne(parentDn, AND(NOT(EQ(A_RULE_CATEGORY_UUID, currentId.value)), AND(IS(OC_RULE_CATEGORY), EQ(A_NAME, name))), A_RULE_CATEGORY_UUID).size match {
      case 0 => false
      case 1 => true
      case _ => logger.error("More than one nodeCategory has %s name under %s".format(name, parentDn)); true
    }
  }

  private[this] def getContainerDn(con : RoLDAPConnection, id: RuleCategoryId) : Box[DN] = {
    groupLibMutex.readLock { con.searchSub(rudderDit.RULECATEGORY.dn, AND(IS(OC_RULE_CATEGORY), EQ(A_RULE_CATEGORY_UUID, id.value)), A_RULE_CATEGORY_UUID).toList match {
      case Nil => Empty
      case (head : com.normation.ldap.sdk.LDAPEntry) :: Nil => Full(head.dn)
      case _ => logger.error("Too many RuleCategory found with this id %s".format(id.value))
                Failure("Too many RuleCategory found with this id %s".format(id.value))
    } }
  }

  /**
   * Add that group categoy into the given parent category
   * Fails if the parent category does not exists or
   * if it already contains that category.
   *
   * return the new category.
   */
  override def create (
      that   : RuleCategory
    , into   : RuleCategoryId
    , modId  : ModificationId
    , actor  : EventActor
    , reason : Option[String]
  ): Box[RuleCategory] = {
    for {
      con                 <- ldap
      parentCategoryEntry <- getCategoryEntry(con, into, "1.1") ?~! "The parent category '%s' was not found, can not add".format(into)
      canAddByName        <- if (categoryExists(con, that.name, parentCategoryEntry.dn))
                               Failure("Cannot create the Node Group Category with name %s : a category with the same name exists at the same level".format(that.name))
                             else Full("OK, can add")
      categoryEntry       =  mapper.ruleCategory2ldap(that,parentCategoryEntry.dn)
      result              <- groupLibMutex.writeLock { con.save(categoryEntry, removeMissingAttributes = true) }
      autoArchive         <- if(autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord] && !that.isSystem) {
                               for {
                                 parents  <- getParents_RuleCategory(that.id)
                                 commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                 //archive  <- gitArchiver.archiveRuleCategory(that,parents.map( _.id), Some(modId,commiter, reason))
                               } yield commiter
                             } else Full("ok")
      newCategory         <- getGroupCategory(that.id) ?~! "The newly created category '%s' was not found".format(that.id.value)
    } yield {
      newCategory
    }
  }

  /**
   * Update an existing group category
   */
  override def update(category: RuleCategory, modId : ModificationId, actor:EventActor, reason: Option[String]): Box[RuleCategory] = {
    repo.synchronized { for {
      con              <- ldap
      oldCategoryEntry <- getCategoryEntry(con, category.id, "1.1") ?~! "Entry with ID '%s' was not found".format(category.id)
      categoryEntry    =  mapper.ruleCategory2ldap(category,oldCategoryEntry.dn.getParent)
      canAddByName     <- if (categoryExists(con, category.name, oldCategoryEntry.dn.getParent, category.id))
                            Failure("Cannot update the Node Group Category with name %s : a category with the same name exists at the same level".format(category.name))
                          else Full("OK")
      result           <- groupLibMutex.writeLock { con.save(categoryEntry, removeMissingAttributes = true) }
      updated          <- getGroupCategory(category.id)
      // Maybe we have to check if the parents are system or not too
      autoArchive      <- if(autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord] && !category.isSystem) {
                             for {
                              parents  <- getParents_RuleCategory(category.id)
                              commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                             // archive  <- gitArchiver.archiveRuleCategory(updated,parents.map( _.id), Some(modId,commiter, reason))
                            } yield commiter //archive
                          } else Full("ok")
    } yield {
      updated
    } }
  }

   /**
   * Update/move an existing group category
   */
  override def updateAndMove(category: RuleCategory, containerId : RuleCategoryId, modId : ModificationId, actor:EventActor, reason: Option[String]): Box[RuleCategory] = {
    repo.synchronized { for {
      con              <- ldap
      oldParents       <- if(autoExportOnModify) {
                            getParents_RuleCategory(category.id)
                          } else Full(Nil)
      oldCategoryEntry <- getCategoryEntry(con, category.id, "1.1") ?~! "Entry with ID '%s' was not found".format(category.id)
      newParent        <- getCategoryEntry(con, containerId, "1.1") ?~! "Parent entry with ID '%s' was not found".format(containerId)
      canAddByName     <- if (categoryExists(con, category.name, newParent.dn, category.id))
                            Failure("Cannot update the Node Group Category with name %s : a category with the same name exists at the same level".format(category.name))
                          else Full("OK")
      categoryEntry    =  mapper.ruleCategory2ldap(category,newParent.dn)
      moved            <- if (newParent.dn == oldCategoryEntry.dn.getParent) {
                            Full(LDIFNoopChangeRecord(oldCategoryEntry.dn))
                          } else { groupLibMutex.writeLock { con.move(oldCategoryEntry.dn, newParent.dn) } }
      result           <- groupLibMutex.writeLock { con.save(categoryEntry, removeMissingAttributes = true) }
      updated          <- getGroupCategory(category.id)
      autoArchive      <- (moved, result) match {
                            case (_:LDIFNoopChangeRecord, _:LDIFNoopChangeRecord) => Full("OK, nothing to archive")
                            case _ if(autoExportOnModify && !updated.isSystem) =>
                              (for {
                                parents  <- getParents_RuleCategory(updated.id)
                                commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                //moved    <- gitArchiver.moveRuleCategory(updated, oldParents.map( _.id), parents.map( _.id), Some(modId,commiter, reason))
                              } yield {
                                commiter
                              }) ?~! "Error when trying to archive automatically the category move"
                            case _ => Full("ok")
                          }
    } yield {
      updated
    } }
  }


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
  override def delete(id:RuleCategoryId, modId : ModificationId, actor:EventActor, reason: Option[String], checkEmpty:Boolean = true) : Box[RuleCategoryId] = {
    for {
      con <-ldap
      deleted <- {
        getCategoryEntry(con, id) match {
          case Full(entry) =>
            for {
              parents     <- if(autoExportOnModify) {
                               getParents_RuleCategory(id)
                             } else Full(Nil)
              ok          <- try {
                               groupLibMutex.writeLock { con.delete(entry.dn, recurse = !checkEmpty) ?~! "Error when trying to delete category with ID '%s'".format(id) }
                             } catch {
                               case e:LDAPException if(e.getResultCode == ResultCode.NOT_ALLOWED_ON_NONLEAF) => Failure("Can not delete a non empty category")
                               case e:Exception => Failure("Exception when trying to delete category with ID '%s'".format(id), Full(e), Empty)
                             }
              category    <- mapper.entry2RuleCategory(entry)
              autoArchive <- (if(autoExportOnModify && ok.size > 0 && !category.isSystem) {
                               for {
                                 commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                 //archive  <- gitArchiver.deleteRuleCategory(id,parents.map( _.id), Some(modId, commiter, reason))
                               } yield {
                                 commiter//archive
                               }
                             } else Full("ok") )  ?~! "Error when trying to archive automatically the category deletion"
            } yield {
              id
            }
          case Empty => Full(id)
          case f:Failure => f
        }
      }
    } yield {
      deleted
    }
  }


}