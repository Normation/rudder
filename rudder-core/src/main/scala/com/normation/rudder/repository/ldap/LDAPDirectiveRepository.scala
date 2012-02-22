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
import com.normation.inventory.ldap.core.LDAPConstants.A_OC
import com.unboundid.ldap.sdk.{DN,Filter}
import com.normation.ldap.sdk._
import BuildFilter._
import com.normation.rudder.domain.{RudderDit,RudderLDAPConstants}
import RudderLDAPConstants._
import net.liftweb.common._
import com.normation.utils.Control.sequence
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.log.{
  DeleteDirective,AddDirective,ModifyDirective
}
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.cfclerk.domain.TechniqueId
import com.normation.utils.ScalaReadWriteLock
import com.normation.rudder.services.user.PersonIdentService

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
) extends DirectiveRepository {
    
  import scala.collection.mutable.{Map => MutMap}
  import scala.xml.Text
  
  /**
   * Retrieve the policy instance entry for the given ID, with the given connection
   */
  private[this] def getDirectiveEntry(con:LDAPConnection, id:DirectiveId, attributes:String*) : Box[LDAPEntry] = {
    val piEntries = con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn,  EQ(A_DIRECTIVE_UUID, id.value), attributes:_*)
    piEntries.size match {
      case 0 => Empty
      case 1 => Full(piEntries(0))
      case _ => Failure("Error, the directory contains multiple occurrence of policy instance with id %s. DN: %s".format(id, piEntries.map( _.dn).mkString("; ")))
    }
  }
  
  
  private[this] def policyFilter(includeSystem:Boolean = false) = if(includeSystem) IS(OC_DIRECTIVE) else AND(IS(OC_DIRECTIVE), EQ(A_IS_SYSTEM,false.toLDAPString))
  
  /**
   * Try to find the policy instance with the given ID.
   * Empty: no policy instance with such ID
   * Full((parent,directive)) : found the policy instance (directive.id == directiveId) in given parent
   * Failure => an error happened.
   */
  def getDirective(id:DirectiveId) : Box[Directive] = {
    for {
      locked  <- userLibMutex.readLock
      con     <- ldap 
      piEntry <- getDirectiveEntry(con, id) ?~! "Can not find Policy Instance with id %s".format(id)
      directive      <- mapper.entry2Directive(piEntry) ?~! "Error when transforming LDAP entry into a Policy Instance for id %s. Entry: %s".format(id, piEntry)
    } yield {
      directive
    }
  }
  
  def getAll(includeSystem:Boolean = false) : Box[Seq[Directive]] = {
    for {
      locked <- userLibMutex.readLock
      con    <- ldap
      //for each directive entry, map it. if one fails, all fails
      directives    <- sequence(con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn,  policyFilter(includeSystem))) { piEntry => 
                  mapper.entry2Directive(piEntry) ?~! "Error when transforming LDAP entry into a Policy Instance. Entry: %s".format(piEntry)
                }
    } yield {
      directives
    } 
  }

  
  /**
   * Find the user policy template for which the given policy
   * instance is an instance. 
   * 
   * Return empty if no such policy instance is known, 
   * fails if no User policy template match the policy instance.
   */
  def getActiveTechnique(id:DirectiveId) : Box[ActiveTechnique] = {
    for {
      locked  <- userLibMutex.readLock
      con     <- ldap 
      piEntry <- getDirectiveEntry(con, id, "1.1") ?~! "Can not find Policy Instance with id %s".format(id)
      activeTechnique     <- ldapActiveTechniqueRepository.getActiveTechnique(mapper.dn2ActiveTechniqueId(piEntry.dn.getParent))
    } yield {
      activeTechnique
    }
  }
  
  
  /**
   * Get policy instances for given policy template.
   * A not known policy template id is a failure.
   */
  override def getDirectives(activeTechniqueId:ActiveTechniqueId, includeSystem:Boolean = false) : Box[Seq[Directive]] = {
    for {
      locked  <- userLibMutex.readLock
      con     <- ldap 
      ptEntry <- ldapActiveTechniqueRepository.getUPTEntry(con, activeTechniqueId, "1.1")
      directives     <- sequence(con.searchOne(ptEntry.dn, policyFilter(includeSystem))) { piEntry => 
                   mapper.entry2Directive(piEntry) ?~! "Error when transforming LDAP entry into a Policy Instance. Entry: %s".format(piEntry)
                 }
    } yield {
      directives
    }
  }
  
  /**
   * Save the given policy instance into given user policy template
   * If the policy instance is already present in the system but not
   * in the given category, raise an error.
   * If the policy instance is already in the given policy template,
   * update the policy instance.
   * If the policy instance is not in the system, add it.
   * 
   * Returned the saved WBUserDirective
   */
  def saveDirective(inActiveTechniqueId:ActiveTechniqueId,directive:Directive, actor:EventActor) : Box[Option[DirectiveSaveDiff]] = {
    for {
      con         <- ldap
      uptEntry    <- ldapActiveTechniqueRepository.getUPTEntry(con, inActiveTechniqueId, "1.1") ?~! "Can not find the User Policy Entry with id %s to add Policy Instance %s".format(inActiveTechniqueId, directive.id)
      canAdd      <- { //check if the directive already exists elsewhere
                        getDirectiveEntry(con, directive.id) match {
                          case f:Failure => f
                          case Empty => Full(None)
                          case Full(otherPi) => 
                            if(otherPi.dn.getParent == uptEntry.dn) Full(Some(otherPi))

                            else Failure("An other policy instance with the id %s exists in an other category that the one with id %s : %s".format(directive.id, inActiveTechniqueId, otherPi.dn))
                        }
                      }
      piEntry     =  mapper.userDirective2Entry(directive, uptEntry.dn)
      result      <- userLibMutex.writeLock { con.save(piEntry, true) }
      //for log event - perhaps put that elsewhere ?
      activeTechnique         <- ldapActiveTechniqueRepository.getActiveTechnique(inActiveTechniqueId) ?~! "Can not find the User Policy Entry with id %s to add Policy Instance %s".format(inActiveTechniqueId, directive.id)
      val activeTechniqueId    =  TechniqueId(activeTechnique.techniqueName,directive.techniqueVersion)
      technique          <- Box(techniqueRepository.get(activeTechniqueId)) ?~! "Can not find the Policy Template with ID '%s'".format(activeTechniqueId.toString)
      optDiff     <- diffMapper.modChangeRecords2DirectiveSaveDiff(technique.id.name, technique.rootSection, piEntry.dn, canAdd, result) ?~! "Error when processing saved modification to log them"
      eventLogged <- optDiff match {
                       case None => Full("OK")
                       case Some(diff:AddDirectiveDiff) => 
                         actionLogger.saveAddDirective(
                             principal = actor, addDiff = diff, varsRootSectionSpec = technique.rootSection
                         )
                       case Some(diff:ModifyDirectiveDiff) => 
                         actionLogger.saveModifyDirective(principal = actor, modifyDiff = diff)
                     }
      autoArchive <- if(autoExportOnModify && optDiff.isDefined) {
                       for {
                         parents  <- ldapActiveTechniqueRepository.activeTechniqueBreadCrump(activeTechnique.id)
                         commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                         archived <- gitPiArchiver.archiveDirective(directive, technique.id.name, parents.map( _.id), technique.rootSection, Some(commiter))
                       } yield archived
                     } else Full("ok")
    } yield {
      optDiff
    }
  }


  /**
   * Delete a policy instance.
   * No dependency check are done, and so you will have to
   * delete dependent rule (or other items) by
   * hand if you want.
   */
  def delete(id:DirectiveId, actor:EventActor) : Box[DeleteDirectiveDiff] = {
    for {
      con          <- ldap
      entry        <- getDirectiveEntry(con, id)
      //for logging, before deletion
      directive           <- mapper.entry2Directive(entry)
      activeTechnique          <- this.getActiveTechnique(id) ?~! "Can not find the User Policy Temple Entry for Policy Instance %s".format(id)
      technique           <- techniqueRepository.get(TechniqueId(activeTechnique.techniqueName,directive.techniqueVersion))
      //delete
      deleted      <- userLibMutex.writeLock { con.delete(entry.dn) }
      diff         =  DeleteDirectiveDiff(technique.id.name, directive)
      loggedAction <- actionLogger.saveDeleteDirective(
                          principal = actor, deleteDiff = diff, varsRootSectionSpec = technique.rootSection
                      )
      autoArchive  <- if(autoExportOnModify && deleted.size > 0) {
                        for {
                          parents  <- ldapActiveTechniqueRepository.activeTechniqueBreadCrump(activeTechnique.id)
                          commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                          archived <- gitPiArchiver.deleteDirective(directive.id, activeTechnique.techniqueName, parents.map( _.id), Some(commiter))
                        } yield archived
                      } else Full("ok")
    } yield {
      diff
    }
  }

}