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

import com.normation.cfclerk.domain.TechniqueName
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
import com.normation.cfclerk.domain.TechniqueVersion
import net.liftweb.json.Printer
import net.liftweb.json.JsonAST
import scala.collection.immutable.SortedMap
import com.normation.utils.Control.sequence
import com.normation.utils.ScalaReadWriteLock
import com.normation.ldap.ldif.LDIFNoopChangeRecord
import com.normation.rudder.services.user.PersonIdentService
import com.normation.eventlog.EventActor

/**
 * Implementation of the repository for active techniques in 
 * LDAP. 
 *
 */
class LDAPActiveTechniqueRepository(
    rudderDit         : RudderDit
  , ldap              : LDAPConnectionProvider
  , mapper            : LDAPEntityMapper
  , diffMapper        : LDAPDiffMapper
  , uuidGen           : StringUuidGenerator
  , userCategoryRepo  : LDAPActiveTechniqueCategoryRepository
  , actionLogger      : EventLogRepository
  , gitArchiver       : GitActiveTechniqueArchiver
  , personIdentService: PersonIdentService
  , autoExportOnModify: Boolean
  , userLibMutex      : ScalaReadWriteLock //that's a scala-level mutex to have some kind of consistency with LDAP
) extends ActiveTechniqueRepository with Loggable {

  /**
   * Look in the subtree with root=active technique library
   * for and entry with the given id. 
   * We expect at most one result, more is a Failure
   */
  private[this] def getUPTEntry[ID](
      con:LDAPConnection, 
      id:ID, 
      filter: ID => Filter,
      attributes:String*) : Box[LDAPEntry] = {
    val uptEntries = con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, filter(id), attributes:_*)
    uptEntries.size match {
      case 0 => Empty
      case 1 => Full(uptEntries(0))
      case _ => Failure("Error, the directory contains multiple occurrence of active technique with ID %s. DNs involved: %s".format(id, uptEntries.map( _.dn).mkString("; ")))
    }     
  }
  
  def getUPTEntry(con:LDAPConnection, id:ActiveTechniqueId, attributes:String*) : Box[LDAPEntry] = {
    userLibMutex.readLock {
      this.getUPTEntry[ActiveTechniqueId](con, id, { id => EQ(A_ACTIVE_TECHNIQUE_UUID, id.value) }, attributes:_*)
    }
  }

  private[this] def getActiveTechnique[ID](id: ID, filter: ID => Filter): Box[ActiveTechnique] = { 
    for {
      con <- ldap
      uptEntry <- getUPTEntry(con, id, filter) ?~! "Can not find user policy entry in LDAP based on filter %s".format(filter(id))
      activeTechnique <- mapper.entry2ActiveTechnique(uptEntry) ?~! "Error when mapping active technique entry to its entity. Entry: %s".format(uptEntry)
    } yield {
      addDirectives(activeTechnique,uptEntry.dn,con)
    }
  }
  
  /**
   * Add directives ids for the given active technique which must
   * be mapped to the given dn in LDAP directory accessible by con
   */
  private[this] def addDirectives(activeTechnique:ActiveTechnique, dn:DN, con:LDAPConnection) : ActiveTechnique = {
    val piEntries = con.searchOne(dn, EQ(A_OC, OC_DIRECTIVE), "objectClass")
    activeTechnique.copy(
      directives = piEntries.map(e => mapper.dn2LDAPRuleID(e.dn)).toList
    )
  }
  
    
  def getActiveTechniqueByCategory(includeSystem:Boolean = false) : Box[SortedMap[List[ActiveTechniqueCategoryId], CategoryWithActiveTechniques]] = {
    for {
      locked       <- userLibMutex.readLock
      allCats      <- userCategoryRepo.getAllActiveTechniqueCategories(includeSystem)
      catsWithUPs  <- sequence(allCats) { ligthCat =>
                        for {
                          category <- userCategoryRepo.getActiveTechniqueCategory(ligthCat.id)
                          parents  <- userCategoryRepo.getParentsForActiveTechniqueCategory(category.id)
                          upts     <- sequence(category.items) { uactiveTechniqueId => this.getActiveTechnique(uactiveTechniqueId) }
                        } yield {
                          ( (category.id :: parents.map(_.id)).reverse, CategoryWithActiveTechniques(category, upts.toSet))
                        }
                      }
    } yield {
      implicit val ordering = ActiveTechniqueCategoryOrdering
      SortedMap[List[ActiveTechniqueCategoryId], CategoryWithActiveTechniques]() ++ catsWithUPs
    }
  }

  def getActiveTechnique(id: ActiveTechniqueId): Box[ActiveTechnique] = { 
    userLibMutex.readLock {
      this.getActiveTechnique[ActiveTechniqueId](id, { id => EQ(A_ACTIVE_TECHNIQUE_UUID, id.value) } )
    }
  }

  def getActiveTechnique(name: TechniqueName): Box[ActiveTechnique] = { 
    userLibMutex.readLock {
      this.getActiveTechnique[TechniqueName](name, { name => EQ(A_TECHNIQUE_UUID, name.value) } )
    }
  }

  def addTechniqueInUserLibrary(
      categoryId: ActiveTechniqueCategoryId, 
      techniqueName: TechniqueName,
      versions:Seq[TechniqueVersion],
      actor: EventActor, reason: Option[String]
  ): Box[ActiveTechnique] = { 
    //check if the technique is already in user lib, and if the category exists
    for {
      con           <- ldap
      noActiveTechnique         <- { //check that there is not already defined activeTechnique with such ref id
                         getUPTEntry[TechniqueName](
                           con, techniqueName, 
                           { name => EQ(A_TECHNIQUE_UUID, name.value) }, 
                           "1.1") match {
                             case Empty => Full("ok")
                             case Full(uptEntry) => Failure("Can not add a technique with id %s in user library. active technique %s is already defined with such a reference technique.".format(techniqueName,uptEntry.dn))
                             case f:Failure => f
                         }
                       }
      categoryEntry <- userCategoryRepo.getCategoryEntry(con, categoryId, "1.1") ?~! "Category entry with ID '%s' was not found".format(categoryId)
      newActiveTechnique        =  ActiveTechnique(ActiveTechniqueId(uuidGen.newUuid),techniqueName, versions.map(x => x -> DateTime.now()).toMap)
      uptEntry      =  mapper.activeTechnique2Entry(newActiveTechnique,categoryEntry.dn)
      result        <- userLibMutex.writeLock { con.save(uptEntry, true) }
      autoArchive   <- if(autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord]) {
                         for {
                           parents  <- this.activeTechniqueBreadCrump(newActiveTechnique.id)
                           commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                           archive  <- gitArchiver.archiveActiveTechnique(newActiveTechnique, parents.map( _.id), Some(commiter, reason))
                         } yield archive
                       } else Full("ok")
    } yield {
      newActiveTechnique
    }
  }

  def activeTechniqueBreadCrump(id: ActiveTechniqueId): Box[List[ActiveTechniqueCategory]] = { 
    //find the active technique entry for that id, and from that, build the parent bread crump
    userLibMutex.readLock { for {
      cat  <- userCategoryRepo.getParentsForActiveTechnique(id)
      cats <- userCategoryRepo.getParentsForActiveTechniqueCategory(cat.id)
    } yield {
      cat :: cats
    } }
  }

  
  /**
   * Move a technique to a new category.
   * Failure if the given technique or category
   * does not exists. 
   * 
   */
  def move(uactiveTechniqueId:ActiveTechniqueId, newCategoryId:ActiveTechniqueCategoryId, actor: EventActor, reason: Option[String]) : Box[ActiveTechniqueId] = {
     for {
      con         <- ldap
      oldParents  <- if(autoExportOnModify) {
                       this.activeTechniqueBreadCrump(uactiveTechniqueId)
                     } else Full(Nil)
      activeTechnique         <- getUPTEntry(con, uactiveTechniqueId, "1.1") ?~! "Can not move non existing template in use library with ID %s".format(uactiveTechniqueId)
      newCategory <- userCategoryRepo.getCategoryEntry(con, newCategoryId, "1.1") ?~! "Can not move template with ID %s into non existing category of user library %s".format(uactiveTechniqueId, newCategoryId)
      moved       <- userLibMutex.writeLock { con.move(activeTechnique.dn, newCategory.dn) ?~! "Error when moving technique %s to category %s".format(uactiveTechniqueId, newCategoryId) }
      autoArchive <- (if(autoExportOnModify && !moved.isInstanceOf[LDIFNoopChangeRecord]) {
                       for {
                         parents  <- this.activeTechniqueBreadCrump(uactiveTechniqueId)
                         newActiveTechnique   <- this.getActiveTechnique(uactiveTechniqueId)
                         commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                         moved    <- gitArchiver.moveActiveTechnique(newActiveTechnique, oldParents.map( _.id), parents.map( _.id), Some(commiter, reason))
                       } yield {
                         moved
                       }
                     } else Full("ok") ) ?~! "Error when trying to archive automatically the technique move"
    } yield {
      uactiveTechniqueId
    }   
  }
  
  /**
   * Set the status of the technique to the new value
   */
  def changeStatus(uactiveTechniqueId:ActiveTechniqueId, status:Boolean, actor: EventActor, reason: Option[String]) : Box[ActiveTechniqueId] = {
    for {
      con         <- ldap
      oldTechnique         <- getUPTEntry(con, uactiveTechniqueId)
      activeTechnique      <- getUPTEntry(con, uactiveTechniqueId)
      saved       <- {
                       activeTechnique +=! (A_IS_ENABLED, status.toLDAPString)
                       userLibMutex.writeLock { con.save(activeTechnique) }
                     }
      optDiff       <- diffMapper.modChangeRecords2TechniqueDiff(oldTechnique, saved) ?~! 
                       "Error when mapping technique '%s' update to an diff: %s"
                         .format(uactiveTechniqueId.value, saved)
      loggedAction  <- optDiff match {
                         case None => Full("OK")
                         case Some(diff) => actionLogger.saveModifyTechnique(principal = actor, modifyDiff = diff, reason = reason)
                       }
      autoArchive <- if(autoExportOnModify && !saved.isInstanceOf[LDIFNoopChangeRecord]) {
                         for {
                           parents  <- this.activeTechniqueBreadCrump(uactiveTechniqueId)
                           newActiveTechnique   <- getActiveTechnique(uactiveTechniqueId)
                           commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                           archive  <- gitArchiver.archiveActiveTechnique(newActiveTechnique, parents.map( _.id), Some(commiter, reason))
                         } yield archive
                       } else Full("ok")
    } yield {
      uactiveTechniqueId
    }
  }
  
  def setAcceptationDatetimes(uactiveTechniqueId:ActiveTechniqueId, datetimes: Map[TechniqueVersion,DateTime], actor: EventActor, reason: Option[String]) : Box[ActiveTechniqueId] = {
    for {
      con         <- ldap
      activeTechnique         <- getUPTEntry(con, uactiveTechniqueId, A_ACCEPTATION_DATETIME)
      saved       <- {
                       val oldAcceptations = mapper.unserializeAcceptations(activeTechnique(A_ACCEPTATION_DATETIME).getOrElse(""))
                       val json = Printer.compact(JsonAST.render(mapper.serializeAcceptations(oldAcceptations ++ datetimes)))
                       activeTechnique.+=!(A_ACCEPTATION_DATETIME, json)
                       userLibMutex.writeLock { con.save(activeTechnique) }
                     }
      autoArchive <- if(autoExportOnModify && !saved.isInstanceOf[LDIFNoopChangeRecord]) {
                         for {
                           parents <- this.activeTechniqueBreadCrump(uactiveTechniqueId)
                           newActiveTechnique  <- getActiveTechnique(uactiveTechniqueId)
                           commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                           archive <- gitArchiver.archiveActiveTechnique(newActiveTechnique, parents.map( _.id), Some(commiter, reason))
                         } yield archive
                       } else Full("ok")
    } yield {
      uactiveTechniqueId
    }
  }

  
  /**
   * Delete the technique in user library.
   * If no such element exists, it is a success.
   */
  def delete(uactiveTechniqueId:ActiveTechniqueId, actor: EventActor, reason: Option[String]) : Box[ActiveTechniqueId] = {
     for {
      con         <- ldap
      oldParents  <- if(autoExportOnModify) {
                       this.activeTechniqueBreadCrump(uactiveTechniqueId)
                     } else Full(Nil)
      activeTechnique         <- getUPTEntry(con, uactiveTechniqueId, A_TECHNIQUE_UUID)
      deleted     <- userLibMutex.writeLock { con.delete(activeTechnique.dn, false) }
      autoArchive <- (if(autoExportOnModify && deleted.size > 0) {
                       for {
                         ptName   <- Box(activeTechnique(A_TECHNIQUE_UUID)) ?~! "Missing required reference technique name"
                         commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                         res      <- gitArchiver.deleteActiveTechnique(TechniqueName(ptName),oldParents.map( _.id), Some(commiter, reason))
                       } yield res
                      } else Full("ok") )  ?~! "Error when trying to archive automatically the category deletion"
    } yield {
      uactiveTechniqueId
    }   
  }
}
