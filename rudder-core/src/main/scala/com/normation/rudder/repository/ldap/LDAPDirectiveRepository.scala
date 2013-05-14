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

import com.normation.rudder.domain.policies._
import com.normation.inventory.ldap.core.LDAPConstants.{A_OC, A_NAME}
import com.unboundid.ldap.sdk.{DN,Filter}
import com.normation.ldap.sdk._
import BuildFilter._
import com.normation.rudder.domain.{RudderDit,RudderLDAPConstants}
import RudderLDAPConstants._
import net.liftweb.common._
import com.normation.utils.Control.sequence
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.eventlog.{
  DeleteDirective,AddDirective,ModifyDirective
}
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.cfclerk.domain.TechniqueId
import com.normation.utils.ScalaReadWriteLock
import com.normation.rudder.services.user.PersonIdentService
import com.normation.cfclerk.domain.Technique
import com.normation.eventlog.ModificationId

class LDAPDirectiveRepository(
    rudderDit                     : RudderDit
  , ldap                          : LDAPConnectionProvider
  , mapper                        : LDAPEntityMapper
  , diffMapper                    : LDAPDiffMapper
  , ldapActiveTechniqueRepository : LDAPActiveTechniqueRepository
  , techniqueRepository           : TechniqueRepository
  , actionLogger                  : EventLogRepository
  , gitPiArchiver                 : GitDirectiveArchiver
  , personIdentService            : PersonIdentService
  , autoExportOnModify            : Boolean
  , userLibMutex                  : ScalaReadWriteLock //that's a scala-level mutex to have some kind of consistency with LDAP
) extends DirectiveRepository with Loggable {
    
  import scala.collection.mutable.{Map => MutMap}
  import scala.xml.Text
  
  /**
   * Retrieve the directive entry for the given ID, with the given connection
   */
  private[this] def getDirectiveEntry(con:LDAPConnection, id:DirectiveId, attributes:String*) : Box[LDAPEntry] = {
    val piEntries = con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn,  EQ(A_DIRECTIVE_UUID, id.value), attributes:_*)
    piEntries.size match {
      case 0 => Empty
      case 1 => Full(piEntries(0))
      case _ => Failure("Error, the directory contains multiple occurrence of directive with id %s. DN: %s".format(id, piEntries.map( _.dn).mkString("; ")))
    }
  }
  
  
  private[this] def policyFilter(includeSystem:Boolean = false) = if(includeSystem) IS(OC_DIRECTIVE) else AND(IS(OC_DIRECTIVE), EQ(A_IS_SYSTEM,false.toLDAPString))
  
  /**
   * Try to find the directive with the given ID.
   * Empty: no directive with such ID
   * Full((parent,directive)) : found the directive (directive.id == directiveId) in given parent
   * Failure => an error happened.
   */
  override def getDirective(id:DirectiveId) : Box[Directive] = {
    for {
      locked    <- userLibMutex.readLock
      con       <- ldap 
      piEntry   <- getDirectiveEntry(con, id) ?~! "Can not find directive with id %s".format(id)
      directive <- mapper.entry2Directive(piEntry) ?~! "Error when transforming LDAP entry into a directive for id %s. Entry: %s".format(id, piEntry)
    } yield {
      directive
    }
  }
  
  override def getDirectiveWithContext(directiveId:DirectiveId) : Box[(Technique, ActiveTechnique, Directive)] = {
    for {
      directive         <- this.getDirective(directiveId) ?~! "No user Directive with ID=%s.".format(directiveId)
      activeTechnique   <- this.getActiveTechnique(directiveId) ?~! "Can not find the Active Technique for Directive %s".format(directiveId)
      activeTechniqueId = TechniqueId(activeTechnique.techniqueName, directive.techniqueVersion)
      technique         <- Box(techniqueRepository.get(activeTechniqueId)) ?~! "No Technique with ID=%s found in reference library.".format(activeTechniqueId)
    } yield {
      (technique, activeTechnique, directive)
    }
  }

  
  override def getAll(includeSystem:Boolean = false) : Box[Seq[Directive]] = {
    for {
      locked     <- userLibMutex.readLock
      con        <- ldap
      //for each directive entry, map it. if one fails, all fails
      directives <- sequence(con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn,  policyFilter(includeSystem))) { piEntry => 
                      mapper.entry2Directive(piEntry) ?~! "Error when transforming LDAP entry into a directive. Entry: %s".format(piEntry)
                    }
    } yield {
      directives
    } 
  }

  
  /**
   * Find the active technique for which the given policy
   * instance is an instance. 
   * 
   * Return empty if no such directive is known, 
   * fails if no active technique match the directive.
   */
  override def getActiveTechnique(id:DirectiveId) : Box[ActiveTechnique] = {
    for {
      locked          <- userLibMutex.readLock
      con             <- ldap 
      piEntry         <- getDirectiveEntry(con, id, "1.1") ?~! "Can not find directive with id %s".format(id)
      activeTechnique <- ldapActiveTechniqueRepository.getActiveTechnique(mapper.dn2ActiveTechniqueId(piEntry.dn.getParent))
    } yield {
      activeTechnique
    }
  }
  
  /**
   * Find the active technique for which the given directive is an instance.
   *
   * Return empty if no such directive is known,
   * fails if no active technique match the directive.
   */
  override def getActiveTechniqueAndDirective(id:DirectiveId) : Box[(ActiveTechnique, Directive)] = {
    for {
      locked  <- userLibMutex.readLock
      con     <- ldap 
      piEntry <- getDirectiveEntry(con, id) ?~! "Can not find directive with id %s".format(id)
      activeTechnique <- ldapActiveTechniqueRepository.getActiveTechnique(mapper.dn2ActiveTechniqueId(piEntry.dn.getParent))
      directive      <- mapper.entry2Directive(piEntry) ?~! "Error when transforming LDAP entry into a directive for id %s. Entry: %s".format(id, piEntry)
    } yield {
      (activeTechnique, directive)
    }
  }
  /**
   * Get directives for given technique.
   * A not known technique id is a failure.
   */
  override def getDirectives(activeTechniqueId:ActiveTechniqueId, includeSystem:Boolean = false) : Box[Seq[Directive]] = {
    for {
      locked     <- userLibMutex.readLock
      con        <- ldap 
      ptEntry    <- ldapActiveTechniqueRepository.getUPTEntry(con, activeTechniqueId, "1.1")
      directives <- sequence(con.searchOne(ptEntry.dn, policyFilter(includeSystem))) { piEntry => 
                      mapper.entry2Directive(piEntry) ?~! "Error when transforming LDAP entry into a directive. Entry: %s".format(piEntry)
                    }
    } yield {
      directives
    }
  }
  
  /**
   * Save the given directive into given active technique
   * If the directive is already present in the system but not
   * in the given category, raise an error.
   * If the directive is already in the given technique,
   * update the directive.
   * If the directive is not in the system, add it.
   * 
   * Returned the saved WBUserDirective
   */
  private[this] def internalSaveDirective(inActiveTechniqueId:ActiveTechniqueId,directive:Directive, modId: ModificationId, actor:EventActor, reason:Option[String], systemCall:Boolean) : Box[Option[DirectiveSaveDiff]] = { 
    for {
      con         <- ldap
      uptEntry    <- ldapActiveTechniqueRepository.getUPTEntry(con, inActiveTechniqueId, "1.1") ?~! "Can not find the User Policy Entry with id %s to add directive %s".format(inActiveTechniqueId, directive.id)
      canAdd      <- { //check if the directive already exists elsewhere
                        getDirectiveEntry(con, directive.id) match {
                          case f:Failure => f
                          case Empty => Full(None)
                          case Full(otherPi) => 
                            if(otherPi.dn.getParent == uptEntry.dn) Full(Some(otherPi))
                            if(otherPi.dn.getParent == uptEntry.dn) {
                              mapper.entry2Directive(otherPi).flatMap { x =>
                                (x.isSystem, systemCall) match {
                                  case (true, false) => Failure("System directive '%s' (%s) can't be updated".format(x.name, x.id.value))
                                  case (false, true) => Failure("Non-system directive can not be updated with that method")
                                  case _ => Full(Some(otherPi))
                                }
                              }
                            } 
                            
                            else Failure("An other directive with the id %s exists in an other category that the one with id %s : %s".format(directive.id, inActiveTechniqueId, otherPi.dn))
                        }
                     }
      // We have to keep the old rootSection to generate the event log
      oldRootSection     =  {
        getDirective(directive.id) match {
          case Full(oldDirective) => getActiveTechnique(directive.id) match {
            case Full(oldActiveTechnique) =>
              val oldTechniqueId     =  TechniqueId(oldActiveTechnique.techniqueName,oldDirective.techniqueVersion)
              techniqueRepository.get(oldTechniqueId).map(_.rootSection)
            case eb:EmptyBox =>
              // Directory did not exist before, this is a Rule addition. but this should not happen So reporting an error
              logger.error("The rule did not existe before")
              None
          }
          case eb:EmptyBox =>
            // Directory did not exist before, this is a Rule addition.
            None
        }
      }
      nameIsAvailable    <- if (directiveNameExists(con, directive.name, directive.id))
                              Failure("Cannot set directive with name \"%s\" : this name is already in use.".format(directive.name))
                            else
                              Full(Unit)
      piEntry            =  mapper.userDirective2Entry(directive, uptEntry.dn)
      result             <- userLibMutex.writeLock { con.save(piEntry, true) }
      //for log event - perhaps put that elsewhere ?
      activeTechnique    <- ldapActiveTechniqueRepository.getActiveTechnique(inActiveTechniqueId) ?~! "Can not find the User Policy Entry with id %s to add directive %s".format(inActiveTechniqueId, directive.id)
      activeTechniqueId  =  TechniqueId(activeTechnique.techniqueName,directive.techniqueVersion)
      technique          <- Box(techniqueRepository.get(activeTechniqueId)) ?~! "Can not find the technique with ID '%s'".format(activeTechniqueId.toString)
      optDiff            <- diffMapper.modChangeRecords2DirectiveSaveDiff(
                                   technique.id.name
                                 , technique.rootSection
                                 , piEntry.dn
                                 , canAdd
                                 , result
                                 , oldRootSection
                               ) ?~! "Error when processing saved modification to log them"
      eventLogged <- optDiff match {
                       case None => Full("OK")
                       case Some(diff:AddDirectiveDiff) => 
                         actionLogger.saveAddDirective(
                             modId
                           , principal = actor
                           , addDiff = diff
                           , varsRootSectionSpec = technique.rootSection
                           , reason = reason
                         )
                       case Some(diff:ModifyDirectiveDiff) => 
                         actionLogger.saveModifyDirective(
                             modId
                           , principal = actor
                           , modifyDiff = diff
                           , reason = reason
                         )
                     }
      autoArchive <- if(autoExportOnModify && optDiff.isDefined && !directive.isSystem) {
                       for {
                         parents  <- ldapActiveTechniqueRepository.activeTechniqueBreadCrump(activeTechnique.id)
                         commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                         archived <- gitPiArchiver.archiveDirective(directive, technique.id.name, parents.map( _.id), technique.rootSection, Some(modId,commiter, reason))
                       } yield archived
                     } else Full("ok")
    } yield {
      optDiff
    }
  }

  override def saveDirective(inActiveTechniqueId:ActiveTechniqueId,directive:Directive, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[Option[DirectiveSaveDiff]] = {
    internalSaveDirective(inActiveTechniqueId, directive, modId, actor, reason, false)
  }
  
  override def saveSystemDirective(inActiveTechniqueId:ActiveTechniqueId,directive:Directive, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[Option[DirectiveSaveDiff]] = {
    internalSaveDirective(inActiveTechniqueId, directive, modId, actor, reason, true)
  }

  
  private[this] def directiveNameExists(con:LDAPConnection, name : String, id:DirectiveId) : Boolean = {
    val filter = AND(AND(IS(OC_DIRECTIVE), EQ(A_NAME,name), NOT(EQ(A_DIRECTIVE_UUID, id.value))))
    con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, filter).size match {
      case 0 => false
      case 1 => true
      case _ => logger.error("More than one directive has %s name".format(name)); true
    }
  }


  /**
   * Delete a directive.
   * No dependency check are done, and so you will have to
   * delete dependent rule (or other items) by
   * hand if you want.
   */
  override def delete(id:DirectiveId, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[DeleteDirectiveDiff] = {
    for {
      con             <- ldap
      entry           <- getDirectiveEntry(con, id)
      //for logging, before deletion
      directive       <- mapper.entry2Directive(entry)
      activeTechnique <- this.getActiveTechnique(id) ?~! "Can not find the User Policy Temple Entry for directive %s".format(id)
      technique       <- techniqueRepository.get(TechniqueId(activeTechnique.techniqueName,directive.techniqueVersion))
      //delete
      deleted         <- userLibMutex.writeLock { con.delete(entry.dn) }
      diff            =  DeleteDirectiveDiff(technique.id.name, directive)
      loggedAction    <- actionLogger.saveDeleteDirective(
                          modId, principal = actor, deleteDiff = diff, varsRootSectionSpec = technique.rootSection, reason = reason
                      )
      autoArchive     <- if (autoExportOnModify && deleted.size > 0 && !directive.isSystem) {
                           for {
                             parents  <- ldapActiveTechniqueRepository.activeTechniqueBreadCrump(activeTechnique.id)
                             commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                             archived <- gitPiArchiver.deleteDirective(directive.id, activeTechnique.techniqueName, parents.map( _.id), Some(modId,commiter, reason))
                           } yield archived
                         } else Full("ok")
    } yield {
      diff
    }
  }

}