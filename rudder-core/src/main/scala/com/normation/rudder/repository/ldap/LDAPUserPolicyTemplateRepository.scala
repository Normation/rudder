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

package com.normation.rudder.repository
package ldap

import com.normation.cfclerk.domain.PolicyPackageName
import com.normation.utils.StringUuidGenerator
import com.unboundid.ldap.sdk.{DN,Filter}
import com.normation.ldap.sdk._
import BuildFilter.EQ
import com.normation.rudder.domain.{RudderDit,RudderLDAPConstants}
import com.normation.rudder.domain.policies._
import com.normation.inventory.ldap.core.LDAPConstants.{A_OC}
import RudderLDAPConstants._
import net.liftweb.common._
import org.joda.time.DateTime
import com.normation.cfclerk.domain.PolicyVersion
import net.liftweb.json.Printer
import net.liftweb.json.JsonAST
import scala.collection.immutable.SortedMap
import com.normation.utils.Control.sequence
import com.normation.utils.ScalaReadWriteLock
import com.normation.ldap.ldif.LDIFNoopChangeRecord
import com.normation.rudder.services.user.PersonIdentService
import com.normation.eventlog.EventActor

/**
 * Implementation of the repository for User Policy Templates in 
 * LDAP. 
 *
 */
class LDAPUserPolicyTemplateRepository(
    rudderDit         : RudderDit
  , ldap              : LDAPConnectionProvider
  , mapper            : LDAPEntityMapper
  , uuidGen           : StringUuidGenerator
  , userCategoryRepo  : LDAPUserPolicyTemplateCategoryRepository
  , gitArchiver       : GitUserPolicyTemplateArchiver
  , personIdentService: PersonIdentService
  , autoExportOnModify: Boolean
  , userLibMutex      : ScalaReadWriteLock //that's a scala-level mutex to have some kind of consistency with LDAP
) extends UserPolicyTemplateRepository with Loggable {

  /**
   * Look in the subtree with root=user policy template library
   * for and entry with the given id. 
   * We expect at most one result, more is a Failure
   */
  private[this] def getUPTEntry[ID](
      con:LDAPConnection, 
      id:ID, 
      filter: ID => Filter,
      attributes:String*) : Box[LDAPEntry] = {
    val uptEntries = con.searchSub(rudderDit.POLICY_TEMPLATE_LIB.dn, filter(id), attributes:_*)
    uptEntries.size match {
      case 0 => Empty
      case 1 => Full(uptEntries(0))
      case _ => Failure("Error, the directory contains multiple occurrence of user policy template with ID %s. DNs involved: %s".format(id, uptEntries.map( _.dn).mkString("; ")))
    }     
  }
  
  def getUPTEntry(con:LDAPConnection, id:UserPolicyTemplateId, attributes:String*) : Box[LDAPEntry] = {
    userLibMutex.readLock {
      this.getUPTEntry[UserPolicyTemplateId](con, id, { id => EQ(A_USER_POLICY_TEMPLATE_UUID, id.value) }, attributes:_*)
    }
  }

  private[this] def getUserPolicyTemplate[ID](id: ID, filter: ID => Filter): Box[UserPolicyTemplate] = { 
    for {
      con <- ldap
      uptEntry <- getUPTEntry(con, id, filter) ?~! "Can not find user policy entry in LDAP based on filter %s".format(filter(id))
      upt <- mapper.entry2UserPolicyTemplate(uptEntry) ?~! "Error when mapping user policy template entry to its entity. Entry: %s".format(uptEntry)
    } yield {
      addPolicyInstances(upt,uptEntry.dn,con)
    }
  }
  
  /**
   * Add policy instances ids for the given user policy template which must
   * be mapped to the given dn in LDAP directory accessible by con
   */
  private[this] def addPolicyInstances(upt:UserPolicyTemplate, dn:DN, con:LDAPConnection) : UserPolicyTemplate = {
    val piEntries = con.searchOne(dn, EQ(A_OC, OC_WBPI), "objectClass")
    upt.copy(
      policyInstances = piEntries.map(e => mapper.dn2LDAPConfigurationRuleID(e.dn)).toList
    )
  }
  
    
  def getUPTbyCategory(includeSystem:Boolean = false) : Box[SortedMap[List[UserPolicyTemplateCategoryId], CategoryAndUPT]] = {
    for {
      locked       <- userLibMutex.readLock
      allCats      <- userCategoryRepo.getAllUserPolicyTemplateCategories(includeSystem)
      catsWithUPs  <- sequence(allCats) { ligthCat =>
                        for {
                          category <- userCategoryRepo.getUserPolicyTemplateCategory(ligthCat.id)
                          parents  <- userCategoryRepo.getParents_UserPolicyTemplateCategory(category.id)
                          upts     <- sequence(category.items) { uptId => this.getUserPolicyTemplate(uptId) }
                        } yield {
                          ( (category.id :: parents.map(_.id)).reverse, CategoryAndUPT(category, upts.toSet))
                        }
                      }
    } yield {
      implicit val ordering = UPTCategoryOrdering
      SortedMap[List[UserPolicyTemplateCategoryId], CategoryAndUPT]() ++ catsWithUPs
    }
  }

  def getUserPolicyTemplate(id: UserPolicyTemplateId): Box[UserPolicyTemplate] = { 
    userLibMutex.readLock {
      this.getUserPolicyTemplate[UserPolicyTemplateId](id, { id => EQ(A_USER_POLICY_TEMPLATE_UUID, id.value) } )
    }
  }

  def getUserPolicyTemplate(name: PolicyPackageName): Box[UserPolicyTemplate] = { 
    userLibMutex.readLock {
      this.getUserPolicyTemplate[PolicyPackageName](name, { name => EQ(A_REFERENCE_POLICY_TEMPLATE_UUID, name.value) } )
    }
  }

  def addPolicyTemplateInUserLibrary(
      categoryId: UserPolicyTemplateCategoryId, 
      policyTemplateName: PolicyPackageName,
      versions:Seq[PolicyVersion],
      actor: EventActor
  ): Box[UserPolicyTemplate] = { 
    //check if the policy template is already in user lib, and if the category exists
    for {
      con           <- ldap
      noUpt         <- { //check that there is not already defined upt with such ref id
                         getUPTEntry[PolicyPackageName](
                           con, policyTemplateName, 
                           { name => EQ(A_REFERENCE_POLICY_TEMPLATE_UUID, name.value) }, 
                           "1.1") match {
                             case Empty => Full("ok")
                             case Full(uptEntry) => Failure("Can not add a policy template with id %s in user library. User policy template %s is already defined with such a reference policy template.".format(policyTemplateName,uptEntry.dn))
                             case f:Failure => f
                         }
                       }
      categoryEntry <- userCategoryRepo.getCategoryEntry(con, categoryId, "1.1") ?~! "Category entry with ID '%s' was not found".format(categoryId)
      newUpt        =  UserPolicyTemplate(UserPolicyTemplateId(uuidGen.newUuid),policyTemplateName, versions.map(x => x -> DateTime.now()).toMap)
      uptEntry      =  mapper.userPolicyTemplate2Entry(newUpt,categoryEntry.dn)
      result        <- userLibMutex.writeLock { con.save(uptEntry, true) }
      autoArchive   <- if(autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord]) {
                         for {
                           parents  <- this.userPolicyTemplateBreadCrump(newUpt.id)
                           commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                           archive  <- gitArchiver.archiveUserPolicyTemplate(newUpt, parents.map( _.id), Some(commiter))
                         } yield archive
                       } else Full("ok")
    } yield {
      newUpt
    }
  }

  def userPolicyTemplateBreadCrump(id: UserPolicyTemplateId): Box[List[UserPolicyTemplateCategory]] = { 
    //find the user policy template entry for that id, and from that, build the parent bread crump
    userLibMutex.readLock { for {
      cat  <- userCategoryRepo.getParentUserPolicyTemplateCategory_forTemplate(id)
      cats <- userCategoryRepo.getParents_UserPolicyTemplateCategory(cat.id)
    } yield {
      cat :: cats
    } }
  }

  
  /**
   * Move a policy template to a new category.
   * Failure if the given policy template or category
   * does not exists. 
   * 
   */
  def move(uptId:UserPolicyTemplateId, newCategoryId:UserPolicyTemplateCategoryId, actor: EventActor) : Box[UserPolicyTemplateId] = {
     for {
      con         <- ldap
      oldParents  <- if(autoExportOnModify) {
                       this.userPolicyTemplateBreadCrump(uptId)
                     } else Full(Nil)
      upt         <- getUPTEntry(con, uptId, "1.1") ?~! "Can not move non existing template in use library with ID %s".format(uptId)
      newCategory <- userCategoryRepo.getCategoryEntry(con, newCategoryId, "1.1") ?~! "Can not move template with ID %s into non existing category of user library %s".format(uptId, newCategoryId)
      moved       <- userLibMutex.writeLock { con.move(upt.dn, newCategory.dn) ?~! "Error when moving policy template %s to category %s".format(uptId, newCategoryId) }
      autoArchive <- (if(autoExportOnModify && !moved.isInstanceOf[LDIFNoopChangeRecord]) {
                       for {
                         parents  <- this.userPolicyTemplateBreadCrump(uptId)
                         newUpt   <- this.getUserPolicyTemplate(uptId)
                         commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                         moved    <- gitArchiver.moveUserPolicyTemplate(newUpt, oldParents.map( _.id), parents.map( _.id), Some(commiter))
                       } yield {
                         moved
                       }
                     } else Full("ok") ) ?~! "Error when trying to archive automatically the policy template move"
    } yield {
      uptId
    }   
  }
  
  /**
   * Set the status of the policy template to the new value
   */
  def changeStatus(uptId:UserPolicyTemplateId, status:Boolean, actor: EventActor) : Box[UserPolicyTemplateId] = {
    for {
      con         <- ldap
      upt         <- getUPTEntry(con, uptId, A_IS_ACTIVATED)
      saved       <- {
                       upt +=! (A_IS_ACTIVATED, status.toLDAPString)
                       userLibMutex.writeLock { con.save(upt) }
                     }
      autoArchive <- if(autoExportOnModify && !saved.isInstanceOf[LDIFNoopChangeRecord]) {
                         for {
                           parents  <- this.userPolicyTemplateBreadCrump(uptId)
                           newUpt   <- getUserPolicyTemplate(uptId)
                           commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                           archive  <- gitArchiver.archiveUserPolicyTemplate(newUpt, parents.map( _.id), Some(commiter))
                         } yield archive
                       } else Full("ok")
    } yield {
      uptId
    }
  }
  
  def setAcceptationDatetimes(uptId:UserPolicyTemplateId, datetimes: Map[PolicyVersion,DateTime], actor: EventActor) : Box[UserPolicyTemplateId] = {
    for {
      con         <- ldap
      upt         <- getUPTEntry(con, uptId, A_ACCEPTATION_DATETIME)
      saved       <- {
                       val oldAcceptations = mapper.unserializeAcceptations(upt(A_ACCEPTATION_DATETIME).getOrElse(""))
                       val json = Printer.compact(JsonAST.render(mapper.serializeAcceptations(oldAcceptations ++ datetimes)))
                       upt.+=!(A_ACCEPTATION_DATETIME, json)
                       userLibMutex.writeLock { con.save(upt) }
                     }
      autoArchive <- if(autoExportOnModify && !saved.isInstanceOf[LDIFNoopChangeRecord]) {
                         for {
                           parents <- this.userPolicyTemplateBreadCrump(uptId)
                           newUpt  <- getUserPolicyTemplate(uptId)
                           commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                           archive <- gitArchiver.archiveUserPolicyTemplate(newUpt, parents.map( _.id), Some(commiter))
                         } yield archive
                       } else Full("ok")
    } yield {
      uptId
    }
  }

  
  /**
   * Delete the policy template in user library.
   * If no such element exists, it is a success.
   */
  def delete(uptId:UserPolicyTemplateId, actor: EventActor) : Box[UserPolicyTemplateId] = {
     for {
      con         <- ldap
      oldParents  <- if(autoExportOnModify) {
                       this.userPolicyTemplateBreadCrump(uptId)
                     } else Full(Nil)
      upt         <- getUPTEntry(con, uptId, A_REFERENCE_POLICY_TEMPLATE_UUID)
      deleted     <- userLibMutex.writeLock { con.delete(upt.dn, false) }
      autoArchive <- (if(autoExportOnModify && deleted.size > 0) {
                       for {
                         ptName   <- Box(upt(A_REFERENCE_POLICY_TEMPLATE_UUID)) ?~! "Missing required reference policy template name"
                         commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                         res      <- gitArchiver.deleteUserPolicyTemplate(PolicyPackageName(ptName),oldParents.map( _.id), Some(commiter))
                       } yield res
                      } else Full("ok") )  ?~! "Error when trying to archive automatically the category deletion"
    } yield {
      uptId
    }   
  }
}
