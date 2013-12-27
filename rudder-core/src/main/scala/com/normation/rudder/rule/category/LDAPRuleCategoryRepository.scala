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
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat


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
   * Add sub entries
   * the given category which MUST be mapped to an entry with the given
   * DN in the LDAP backend, accessible with the given connection.
   */
  private[this] def addSubEntries(category:RuleCategory, dn:DN, con:RoLDAPConnection) : RuleCategory = {
    val subEntries =
      con.searchOne(
          dn
        , IS(OC_RULE_CATEGORY)
        , A_OC
        , A_RULE_CATEGORY_UUID
        , A_NAME
        , A_RULE_TARGET
        , A_DESCRIPTION
        , A_IS_ENABLED
        , A_IS_SYSTEM
      ).partition(e => e.isA(OC_RULE_CATEGORY))

    val subCategories = {
      subEntries._1.flatMap { e =>
        val category = mapper.entry2RuleCategory(e)
        category.map(addSubEntries(_,e.dn,con))
      }.toList
    }

    category.copy(childs = subCategories)
  }

  /**
   * Get category with given Id
   */
  def get(id:RuleCategoryId) : Box[RuleCategory] = {
    for {
      con      <- ldap
      entry    <- getCategoryEntry(con, id) ?~! s"Entry with ID '${id.value}' was not found"
      category <- mapper.entry2RuleCategory(entry) ?~! s"Error when transforming LDAP entry ${entry} into a server group category"
    } yield {
      category
    }
  }

  /**
   * Retrieve the category entry for the given ID, with the given connection
   * Used to get the ldap dn
   */
  def getCategoryEntry(con:RoLDAPConnection, id:RuleCategoryId, attributes:String*) : Box[LDAPEntry] = {
    val categoryEntries = categoryMutex.readLock {
      con.searchSub(rudderDit.RULECATEGORY.dn,  EQ(A_RULE_CATEGORY_UUID, id.value), attributes:_*)
    }
    categoryEntries.size match {
      case 0 => Empty
      case 1 => Full(categoryEntries(0))
      case _ =>
        val categoryDN = categoryEntries.map( _.dn).mkString("; ")
        Failure(s"Error, the directory contains multiple occurrence of group category with id ${id.value}. DN: ${categoryDN}")
    }
  }

  /**
   * get Root category
   */
  override def getRootCategory(): Box[RuleCategory] = {
    (for {
      con <- ldap
      rootCategoryEntry <- categoryMutex.readLock { con.get(rudderDit.RULECATEGORY.dn) ?~! "The root category of the Rule category seems to be missing in LDAP directory. Please check its content" }
      // look for sub categories
      rootCategory <- mapper.entry2RuleCategory(rootCategoryEntry) ?~! s"Error when mapping from an LDAP entry to a RuleCategory: ${rootCategoryEntry}"
    } yield {
      addSubEntries(rootCategory,rootCategoryEntry.dn, con)
    })
  }

  /**
   * Return the list of parents for that category, from the root category to the category itself
   */
  def getParents(id:RuleCategoryId) : Box[List[RuleCategory]] = {
    for {
      root <- getRootCategory
      parents <- root.childPath(id)
    } yield {
      parents
    }
  }
}

class WoLDAPRuleCategoryRepository(
    roruleCategoryRepo : RoLDAPRuleCategoryRepository
  , ldap               : LDAPConnectionProvider[RwLDAPConnection]
  , uuidGen            : StringUuidGenerator
  , gitArchiver        : GitRuleArchiver
  , personIdentService : PersonIdentService
  , autoExportOnModify : Boolean
) extends WoRuleCategoryRepository with Loggable {
  repo =>

  import roruleCategoryRepo.{ldap => roLdap, _}

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
   * Add that category into the given parent category
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
      parentCategoryEntry <- getCategoryEntry(con, into, "1.1") ?~! s"The parent category '${into.value}' was not found, can not add"
      canAddByName        <- if (categoryExists(con, that.name, parentCategoryEntry.dn)) {
                               Failure(s"Cannot create the Node Group Category with name '${that.name}' : a category with the same name exists at the same level")
                             } else {
                               Full("OK, can add")
                             }
      categoryEntry       =  mapper.ruleCategory2ldap(that,parentCategoryEntry.dn)
      result              <- categoryMutex.writeLock { con.save(categoryEntry, removeMissingAttributes = true) }
      autoArchive         <- if(autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord] && !that.isSystem) {
                               for {
                                 parents  <- getParents(that.id)
                                 commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                 //archive  <- gitArchiver.archiveRuleCategory(that,parents.map( _.id), Some(modId,commiter, reason))
                               } yield commiter
                             } else Full("ok")
      newCategory         <- get(that.id) ?~! s"The newly created category '${that.id.value}' was not found"
    } yield {
      newCategory
    }
  }

  /**
   * Update an existing category
   */
  override def update(
      category : RuleCategory
    , modId    : ModificationId
    , actor    : EventActor
    , reason   : Option[String]
  ) : Box[RuleCategory] = {
    repo.synchronized { for {
      con              <- ldap
      oldCategoryEntry <- getCategoryEntry(con, category.id, "1.1") ?~! s"Entry with ID '${category.id.value}' was not found"
      categoryEntry    =  mapper.ruleCategory2ldap(category,oldCategoryEntry.dn.getParent)
      canAddByName     <- if (categoryExists(con, category.name, oldCategoryEntry.dn.getParent, category.id)) {
                            Failure(s"Cannot update the Node Group Category with name ${category.name} : a category with the same name exists at the same level")
                          } else {
                            Full("OK")
                          }
      result           <- categoryMutex.writeLock { con.save(categoryEntry, removeMissingAttributes = true) }
      updated          <- get(category.id)
      // Maybe we have to check if the parents are system or not too
      autoArchive      <- if(autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord] && !category.isSystem) {
                             for {
                              parents  <- getParents(category.id)
                              commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                              //archive  <- gitArchiver.archiveRuleCategory(updated,parents.map( _.id), Some(modId,commiter, reason))
                            } yield commiter //archive
                          } else Full("ok")
    } yield {
      updated
    } }
  }

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
                          } else Full(Nil)
      oldCategoryEntry <- getCategoryEntry(con, category.id, "1.1") ?~! s"Entry with ID '${category.id.value}' was not found"
      newParent        <- getCategoryEntry(con, containerId, "1.1") ?~! s"Parent entry with ID '${containerId.value}' was not found"
      canAddByName     <- if (categoryExists(con, category.name, newParent.dn, category.id)) {
                            Failure(s"Cannot update the Node Group Category with name ${category.name} : a category with the same name exists at the same level")
                          } else {
                            Full("OK")
                          }
      categoryEntry    =  mapper.ruleCategory2ldap(category,newParent.dn)
      moved            <- if (newParent.dn == oldCategoryEntry.dn.getParent) {
                            Full(LDIFNoopChangeRecord(oldCategoryEntry.dn))
                          } else {
                            categoryMutex.writeLock { con.move(oldCategoryEntry.dn, newParent.dn) }
                          }
      result           <- categoryMutex.writeLock { con.save(categoryEntry, removeMissingAttributes = true) }
      updated          <- get(category.id)
      autoArchive      <- (moved, result) match {
                            case (_:LDIFNoopChangeRecord, _:LDIFNoopChangeRecord) => Full("OK, nothing to archive")
                            case _ if(autoExportOnModify && !updated.isSystem) =>
                              (for {
                                parents  <- getParents(updated.id)
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
        getCategoryEntry(con, that) match {
          case Full(entry) =>
            for {
              parents     <- if(autoExportOnModify) {
                               getParents(that)
                             } else Full(Nil)
              ok          <- try {
                               categoryMutex.writeLock { con.delete(entry.dn, recurse = !checkEmpty) ?~! s"Error when trying to delete category with ID '${that.value}'" }
                             } catch {
                               case e:LDAPException if(e.getResultCode == ResultCode.NOT_ALLOWED_ON_NONLEAF) =>
                                 Failure("Can not delete a non empty category")
                               case e:Exception =>
                                 Failure(s"Exception when trying to delete category with ID '${that.value}'", Full(e), Empty)
                             }
              category    <- mapper.entry2RuleCategory(entry)
              autoArchive <- (if(autoExportOnModify && ok.size > 0 && !category.isSystem) {
                               for {
                                 commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                 //archive  <- gitArchiver.deleteRuleCategory(that.id,parents.map( _.id), Some(modId, commiter, reason))
                               } yield {
                                 commiter//archive
                               }
                             } else Full("ok") )  ?~! "Error when trying to archive automatically the category deletion"
            } yield {
              that
            }
          case Empty => Full(that)
          case f:Failure => f
        }
      }
    } yield {
      deleted
    }
  }

}