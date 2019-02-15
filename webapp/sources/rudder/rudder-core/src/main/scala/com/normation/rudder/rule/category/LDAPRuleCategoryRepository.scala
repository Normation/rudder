/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

package com.normation.rudder.rule.category

import cats.implicits._
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.ldap.ldif.LDIFNoopChangeRecord
import com.normation.ldap.sdk.BuildFilter._
import com.normation.ldap.sdk.LdapResult._
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.LDAPEntry
import com.normation.ldap.sdk.RoLDAPConnection
import com.normation.ldap.sdk._
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.rudder.repository.ldap.ScalaReadWriteLock
import com.normation.rudder.services.user.PersonIdentService
import com.normation.utils.StringUuidGenerator
import com.normation.utils.Utils
import com.unboundid.ldap.sdk.DN
import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.ResultCode
import net.liftweb.common._

/**
 * Here is the ordering for a List[RuleCategoryId]
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
  , val categoryMutex : ScalaReadWriteLock //that's a scala-level mutex to have some kind of consistency with LDAP
) extends RoRuleCategoryRepository with Loggable {
  repo =>


  /**
   * Get category with given Id
   */
  def get(id:RuleCategoryId) : Box[RuleCategory] = {
    for {
      con      <- ldap
      entry    <- getCategoryEntry(con, id).notOptional(s"Entry with ID '${id.value}' was not found")
      category <- (mapper.entry2RuleCategory(entry) ?~! s"Error when transforming LDAP entry ${entry} into a server group category").toLdapResult
    } yield {
      category
    }
  }.toBox

  /**
   * Retrieve the category entry for the given ID, with the given connection
   * Used to get the ldap dn
   */
  protected[category] def getCategoryEntry(con:RoLDAPConnection, id:RuleCategoryId, attributes:String*) : LdapResult[Option[LDAPEntry]] = {
    categoryMutex.readLock {
      con.searchSub(rudderDit.RULECATEGORY.dn,  EQ(A_RULE_CATEGORY_UUID, id.value), attributes:_*)
    }.flatMap { categoryEntries =>
      categoryEntries.size match {
        case 0 => None.success
        case 1 => Some(categoryEntries(0)).success
        case _ =>
          val categoryDN = categoryEntries.map( _.dn).mkString("; ")
          s"Error, the directory contains multiple occurrence of group category with id ${id.value}. DN: ${categoryDN}".failure
      }
    }
  }

  /**
   * get Root category
   */
  override def getRootCategory(): Box[RuleCategory] = {
    val catAttributes = Seq(A_OC, A_RULE_CATEGORY_UUID, A_NAME, A_RULE_TARGET, A_DESCRIPTION, A_IS_ENABLED, A_IS_SYSTEM)

    (for {
      con          <- ldap
      entries      <- categoryMutex.readLock { con.searchSub(rudderDit.RULECATEGORY.dn, IS(OC_RULE_CATEGORY), catAttributes:_*) }
      // look for sub categories
      categories   <- entries.toList.traverse { entry =>
                        (mapper.entry2RuleCategory(entry).map(c => (entry.dn, c)) ?~! s"Error when mapping from an LDAP entry to a RuleCategory: ${entry}").toLdapResult
                      }
      rootCategory <- buildHierarchy(rudderDit.RULECATEGORY.dn, categories.toList)
    } yield {
      rootCategory
    })
  }.toBox

  /**
   * Build the hierarchy defined by the list of categories, filling children.
   * The starting point is given by the root id.
   */
  private[this] def buildHierarchy(rootDn: DN, categories: List[(DN, RuleCategory)]): LdapResult[RuleCategory] = {
    def getChildren(parentDn: DN): List[RuleCategory] = categories.collect { case (dn, r) if(dn.getParent == parentDn) =>
      val cc = getChildren(dn)
      r.copy(childs = cc)
    }

    for {
      root <- categories.find( _._1 == rootDn).success.notOptional(s"The category with id '${rootDn}' was not found on the back but is referenced by other categories")
    } yield {
      root._2.copy(childs = getChildren(rootDn))
    }
  }
}

class WoLDAPRuleCategoryRepository(
    roruleCategoryRepo : RoLDAPRuleCategoryRepository
  , ldap               : LDAPConnectionProvider[RwLDAPConnection]
  , uuidGen            : StringUuidGenerator
  , gitArchiver        : GitRuleCategoryArchiver
  , personIdentService : PersonIdentService
  , autoExportOnModify : Boolean
) extends WoRuleCategoryRepository with Loggable {
  repo =>

  import roruleCategoryRepo._

  /**
   * Check if a category exist with the given name
   */
  private[this] def categoryExists(
      con      : RoLDAPConnection
    , name     : String
    , parentDn : DN
  ) : Boolean = {
    con.searchOne(parentDn, AND(IS(OC_RULE_CATEGORY), EQ(A_NAME, name)), A_RULE_CATEGORY_UUID).size match {
      case 0 => false
      case 1 => true
      case _ =>
        logger.error(s"More than one Rule Category has ${name} name under ${parentDn}")
        true
    }
  }

  /**
   * Check if a category exist with the given name
   */
  private[this] def categoryExists(
      con       : RoLDAPConnection
    , name      : String
    , parentDn  : DN
    , currentId : RuleCategoryId
  ) : Boolean = {
    con.searchOne(parentDn, AND(NOT(EQ(A_RULE_CATEGORY_UUID, currentId.value)), AND(IS(OC_RULE_CATEGORY), EQ(A_NAME, name))), A_RULE_CATEGORY_UUID).size match {
      case 0 => false
      case 1 => true
      case _ =>
        logger.error(s"More than one Rule Category has ${name} name under ${parentDn}")
        true
    }
  }


  /**
   * Return the list of parents for that category, from the root category
   */
  private[this] def getParents(id:RuleCategoryId) : LdapResult[List[RuleCategory]] = {
    for {
      root    <- getRootCategory.toLdapResult
      parents <- root.findParents(id).leftMap(s => LdapResultError.Consistancy(s))
    } yield {
      parents
    }
  }
  /**
   * Add that category into the given parent category
   * Fails if the parent category does not exist or
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
      parentCategoryEntry <- getCategoryEntry(con, into, "1.1").notOptional(s"The parent category '${into.value}' was not found, can not add")
      canAddByName        <- if (categoryExists(con, that.name, parentCategoryEntry.dn)) {
                               s"Cannot create the Node Group Category with name '${that.name}' : a category with the same name exists at the same level".failure
                             } else {
                               "OK, can add".success
                             }
      categoryEntry       =  mapper.ruleCategory2ldap(that,parentCategoryEntry.dn)
      result              <- categoryMutex.writeLock { con.save(categoryEntry, removeMissingAttributes = true) }
      autoArchive         <- (if(autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord] && !that.isSystem) {
                               for {
                                 parents  <- getParents(that.id).toBox
                                 commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                 archive  <- gitArchiver.archiveRuleCategory(that,parents.map( _.id), Some((modId, commiter, reason)))
                               } yield {
                                 archive
                               }
                             } else Full("ok")).toLdapResult
      newCategory         <- (get(that.id) ?~! s"The newly created category '${that.id.value}' was not found").toLdapResult
    } yield {
      newCategory
    }
  }.toBox

  /**
   * Update and move an existing category
   */
  override def updateAndMove(
      category    : RuleCategory
    , containerId : RuleCategoryId
    , modId       : ModificationId
    , actor       : EventActor
    , reason      : Option[String]
  ) : Box[RuleCategory] = {
    repo.synchronized { for {
      con              <- ldap
      oldParents       <- if(autoExportOnModify) {
                            getParents(category.id)
                          } else Nil.success
      oldCategoryEntry <- getCategoryEntry(con, category.id, "1.1").notOptional(s"Entry with ID '${category.id.value}' was not found")
      newParent        <- getCategoryEntry(con, containerId, "1.1").notOptional(s"Parent entry with ID '${containerId.value}' was not found")
      canAddByName     <- if (categoryExists(con, category.name, newParent.dn, category.id)) {
                            s"Cannot update the Node Group Category with name ${category.name} : a category with the same name exists at the same level".failure
                          } else {
                            "OK".success
                          }
      categoryEntry    =  mapper.ruleCategory2ldap(category,newParent.dn)
      moved            <- if (newParent.dn == oldCategoryEntry.dn.getParent) {
                            LDIFNoopChangeRecord(oldCategoryEntry.dn).success
                          } else {
                            categoryMutex.writeLock { con.move(oldCategoryEntry.dn, newParent.dn) }
                          }
      result           <- categoryMutex.writeLock { con.save(categoryEntry, removeMissingAttributes = true) }
      updated          <- get(category.id).toLdapResult
      autoArchive      <- (moved, result) match {
                            case (_:LDIFNoopChangeRecord, _:LDIFNoopChangeRecord) => "OK, nothing to archive".success
                            case _ if(autoExportOnModify && !updated.isSystem) =>
                              ((for {
                                parents  <- getParents(updated.id).toBox
                                commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                moved    <- gitArchiver.moveRuleCategory(updated, oldParents.map( _.id), parents.map( _.id), Some((modId, commiter, reason)))
                              } yield {
                                moved
                              }) ?~! "Error when trying to  automaticallyarchive the category move or update").toLdapResult
                            case _ => "ok".success
                          }
    } yield {
      updated
    } }
  }.toBox


  /**
   * Delete the category.
   * If no category with such id exists, it is a success.
   * If checkEmtpy is set to true, the deletion may be done only if
   * the category is empty (else, category and children are deleted).
   * @param category to delete
   * @param checkEmtpy Is a category containing subElements can be deleted
   *                   true => can only delete empty category
   * @return
   *  - Full(category id) for a success
   *  - Failure(with error message) if an error happened.
   */
  override def delete(
      that       : RuleCategoryId
    , modId      : ModificationId
    , actor      : EventActor
    , reason     : Option[String]
    , checkEmpty : Boolean = true
  ) : Box[RuleCategoryId] = {
    for {
      con <-ldap
      deleted <- {
        getCategoryEntry(con, that).flatMap {
          case Some(entry) =>
            for {
              parents     <- if(autoExportOnModify) {
                               getParents(that)
                             } else Nil.success
              ok          <- categoryMutex.writeLock { con.delete(entry.dn, recurse = !checkEmpty) ?~! s"Error when trying to delete category with ID '${that.value}'" }
              category    <- mapper.entry2RuleCategory(entry).toLdapResult
              autoArchive <- ((if(autoExportOnModify && ok.size > 0 && !category.isSystem) {
                               for {
                                 commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                 archive  <- gitArchiver.deleteRuleCategory(that,parents.map( _.id), Some((modId, commiter, reason)))
                               } yield {
                                 archive
                               }
                             } else Full("ok") )  ?~! "Error when trying to archive automatically the category deletion").toLdapResult
            } yield {
              that
            }
          case None => that.success
        }
      }
    } yield {
      deleted
    }
  }.toBox

}
