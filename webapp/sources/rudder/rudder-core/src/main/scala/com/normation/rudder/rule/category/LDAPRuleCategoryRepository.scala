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
import com.normation.NamedZioLogger
import com.normation.errors._
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.ldap.ldif.LDIFNoopChangeRecord
import com.normation.ldap.sdk.BuildFilter._
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
import scalaz.zio._
import scalaz.zio.syntax._

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
) extends RoRuleCategoryRepository with NamedZioLogger {
  repo =>

  override def loggerName: String = this.getClass.getName

  /**
   * Get category with given Id
   */
  def get(id:RuleCategoryId) : IOResult[RuleCategory] = {
    for {
      con      <- ldap
      entry    <- getCategoryEntry(con, id).notOptional(s"Entry with ID '${id.value}' was not found")
      category <- mapper.entry2RuleCategory(entry).toIO.chainError(s"Error when transforming LDAP entry ${entry} into a server group category")
    } yield {
      category
    }
  }

  /**
   * Retrieve the category entry for the given ID, with the given connection
   * Used to get the ldap dn
   */
  protected[category] def getCategoryEntry(con:RoLDAPConnection, id:RuleCategoryId, attributes:String*) : IOResult[Option[LDAPEntry]] = {
    categoryMutex.readLock {
      con.searchSub(rudderDit.RULECATEGORY.dn,  EQ(A_RULE_CATEGORY_UUID, id.value), attributes:_*)
    }.flatMap { categoryEntries =>
      categoryEntries.size match {
        case 0 => None.succeed
        case 1 => Some(categoryEntries(0)).succeed
        case _ =>
          val categoryDN = categoryEntries.map( _.dn).mkString("; ")
          Unconsistancy(s"Error, the directory contains multiple occurrence of group category with id ${id.value}. DN: ${categoryDN}").fail
      }
    }
  }

  /**
   * get Root category
   */
  override def getRootCategory(): IOResult[RuleCategory] = {
    val catAttributes = Seq(A_OC, A_RULE_CATEGORY_UUID, A_NAME, A_RULE_TARGET, A_DESCRIPTION, A_IS_ENABLED, A_IS_SYSTEM)

    (for {
      con          <- ldap
      entries      <- categoryMutex.readLock { con.searchSub(rudderDit.RULECATEGORY.dn, IS(OC_RULE_CATEGORY), catAttributes:_*) }
      // look for sub categories
      categories   <- ZIO.foreach(entries) { entry =>
                        mapper.entry2RuleCategory(entry).map(c => (entry.dn, c)).toIO.chainError(s"Error when mapping from an LDAP entry to a RuleCategory: ${entry}")
                      }
      rootCategory <- buildHierarchy(rudderDit.RULECATEGORY.dn, categories.toList)
    } yield {
      rootCategory
    })
  }

  /**
   * Build the hierarchy defined by the list of categories, filling children.
   * The starting point is given by the root id.
   */
  private[this] def buildHierarchy(rootDn: DN, categories: List[(DN, RuleCategory)]): IOResult[RuleCategory] = {
    def getChildren(parentDn: DN): List[RuleCategory] = categories.collect { case (dn, r) if(dn.getParent == parentDn) =>
      val cc = getChildren(dn)
      r.copy(childs = cc)
    }

    for {
      root <- categories.find( _._1 == rootDn).succeed.notOptional(s"The category with id '${rootDn}' was not found on the back but is referenced by other categories")
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
) extends WoRuleCategoryRepository with NamedZioLogger {
  repo =>

  import roruleCategoryRepo._

  override def loggerName: String = this.getClass.getName

  /**
   * Check if a category exist with the given name
   */
  private[this] def categoryExists(
      con      : RoLDAPConnection
    , name     : String
    , parentDn : DN
  ) : IOResult[Boolean] = {
    con.searchOne(parentDn, AND(IS(OC_RULE_CATEGORY), EQ(A_NAME, name)), A_RULE_CATEGORY_UUID).flatMap( _.size match {
      case 0 => false.succeed
      case 1 => true.succeed
      case _ =>
        logPure.error(s"More than one Rule Category has ${name} name under ${parentDn}") *>
        true.succeed
    })
  }

  /**
   * Check if a category exist with the given name
   */
  private[this] def categoryExists(
      con       : RoLDAPConnection
    , name      : String
    , parentDn  : DN
    , currentId : RuleCategoryId
  ) : IOResult[Boolean] = {
    con.searchOne(parentDn, AND(NOT(EQ(A_RULE_CATEGORY_UUID, currentId.value)), AND(IS(OC_RULE_CATEGORY), EQ(A_NAME, name))), A_RULE_CATEGORY_UUID).flatMap(_.size match {
      case 0 => false.succeed
      case 1 => true.succeed
      case _ =>
        logPure.error(s"More than one Rule Category has ${name} name under ${parentDn}") *>
        true.succeed
    })
  }


  /**
   * Return the list of parents for that category, from the root category
   */
  private[this] def getParents(id:RuleCategoryId) : IOResult[List[RuleCategory]] = {
    for {
      root    <- getRootCategory
      parents <- root.findParents(id).leftMap(s => Unconsistancy(s)).toIO
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
  ): IOResult[RuleCategory] = {
    for {
      con                 <- ldap
      parentCategoryEntry <- getCategoryEntry(con, into, "1.1").notOptional(s"The parent category '${into.value}' was not found, can not add")
      exists              <- categoryExists(con, that.name, parentCategoryEntry.dn)
      canAddByName        <- if (exists) {
                               Unconsistancy(s"Cannot create the Node Group Category with name '${that.name}' : a category with the same name exists at the same level").fail
                             } else {
                               UIO.unit
                             }
      categoryEntry       =  mapper.ruleCategory2ldap(that,parentCategoryEntry.dn)
      result              <- categoryMutex.writeLock { con.save(categoryEntry, removeMissingAttributes = true) }
      autoArchive         <- if(autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord] && !that.isSystem) {
                               for {
                                 parents  <- getParents(that.id)
                                 commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                 archive  <- gitArchiver.archiveRuleCategory(that,parents.map( _.id), Some((modId, commiter, reason)))
                               } yield {
                                 archive
                               }
                             } else UIO.unit
      newCategory         <- get(that.id).chainError(s"The newly created category '${that.id.value}' was not found")
    } yield {
      newCategory
    }
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
  ) : IOResult[RuleCategory] = {
    repo.synchronized { for {
      con              <- ldap
      oldParents       <- if(autoExportOnModify) {
                            getParents(category.id)
                          } else Nil.succeed
      oldCategoryEntry <- getCategoryEntry(con, category.id, "1.1").notOptional(s"Entry with ID '${category.id.value}' was not found")
      newParent        <- getCategoryEntry(con, containerId, "1.1").notOptional(s"Parent entry with ID '${containerId.value}' was not found")
      exists           <- categoryExists(con, category.name, newParent.dn, category.id)
      canAddByName     <- if (exists) {
                            Unconsistancy(s"Cannot update the Node Group Category with name ${category.name} : a category with the same name exists at the same level").fail
                          } else {
                            UIO.unit
                          }
      categoryEntry    =  mapper.ruleCategory2ldap(category,newParent.dn)
      moved            <- if (newParent.dn == oldCategoryEntry.dn.getParent) {
                            LDIFNoopChangeRecord(oldCategoryEntry.dn).succeed
                          } else {
                            categoryMutex.writeLock { con.move(oldCategoryEntry.dn, newParent.dn) }
                          }
      result           <- categoryMutex.writeLock { con.save(categoryEntry, removeMissingAttributes = true) }
      updated          <- get(category.id)
      autoArchive      <- (moved, result) match {
                            case (_:LDIFNoopChangeRecord, _:LDIFNoopChangeRecord) => "OK, nothing to archive".succeed
                            case _ if(autoExportOnModify && !updated.isSystem) =>
                              (for {
                                parents  <- getParents(updated.id)
                                commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                moved    <- gitArchiver.moveRuleCategory(updated, oldParents.map( _.id), parents.map( _.id), Some((modId, commiter, reason)))
                              } yield {
                                moved
                              }).chainError("Error when trying to  automaticallyarchive the category move or update")
                            case _ => "ok".succeed
                          }
    } yield {
      updated
    } }
  }


  /**
   * Delete the category.
   * If no category with such id exists, it is a succeed.
   * If checkEmtpy is set to true, the deletion may be done only if
   * the category is empty (else, category and children are deleted).
   * @return
   *  - Full(category id) for a succeed
   *  - fail(with error message) if an error happened.
   */
  override def delete(
      that       : RuleCategoryId
    , modId      : ModificationId
    , actor      : EventActor
    , reason     : Option[String]
    , checkEmpty : Boolean = true
  ) : IOResult[RuleCategoryId] = {
    for {
      con     <-ldap
      deleted <- {
        getCategoryEntry(con, that).flatMap {
          case Some(entry) =>
            for {
              parents     <- if(autoExportOnModify) {
                               getParents(that)
                             } else Nil.succeed
              ok          <- categoryMutex.writeLock { con.delete(entry.dn, recurse = !checkEmpty) }.chainError(s"Error when trying to delete category with ID '${that.value}'")
              category    <- mapper.entry2RuleCategory(entry).toIO
              autoArchive <- (if(autoExportOnModify && ok.size > 0 && !category.isSystem) {
                               for {
                                 commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                 archive  <- gitArchiver.deleteRuleCategory(that,parents.map( _.id), Some((modId, commiter, reason)))
                               } yield {
                                 archive
                               }
                             } else UIO.unit ) .chainError("Error when trying to archive automatically the category deletion")
            } yield {
              that
            }
          case None => that.succeed
        }
      }
    } yield {
      deleted
    }
  }

}
