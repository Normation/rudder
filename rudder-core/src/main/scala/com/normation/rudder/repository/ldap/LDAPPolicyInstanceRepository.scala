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
  DeletePolicyInstance,AddPolicyInstance,ModifyPolicyInstance
}
import com.normation.cfclerk.services.PolicyPackageService
import com.normation.cfclerk.domain.PolicyPackageId
import com.normation.utils.ScalaReadWriteLock

class LDAPPolicyInstanceRepository(
    rudderDit                       : RudderDit
  , ldap                            : LDAPConnectionProvider
  , mapper                          : LDAPEntityMapper
  , diffMapper                      : LDAPDiffMapper
  , ldapUserPolicyTemplateRepository: LDAPUserPolicyTemplateRepository
  , policyPackageService            : PolicyPackageService
  , actionLogger                    : EventLogRepository
  , gitPiArchiver                   : GitPolicyInstanceArchiver
  , autoExportOnModify              : Boolean
  , userLibMutex                    : ScalaReadWriteLock //that's a scala-level mutex to have some kind of consistency with LDAP
) extends PolicyInstanceRepository {
    
  import scala.collection.mutable.{Map => MutMap}
  import scala.xml.Text
  
  /**
   * Retrieve the policy instance entry for the given ID, with the given connection
   */
  private[this] def getPolicyInstanceEntry(con:LDAPConnection, id:PolicyInstanceId, attributes:String*) : Box[LDAPEntry] = {
    val piEntries = con.searchSub(rudderDit.POLICY_TEMPLATE_LIB.dn,  EQ(A_WBPI_UUID, id.value), attributes:_*)
    piEntries.size match {
      case 0 => Empty
      case 1 => Full(piEntries(0))
      case _ => Failure("Error, the directory contains multiple occurrence of policy instance with id %s. DN: %s".format(id, piEntries.map( _.dn).mkString("; ")))
    }
  }
  
  
  private[this] def policyFilter(includeSystem:Boolean = false) = if(includeSystem) IS(OC_WBPI) else AND(IS(OC_WBPI), EQ(A_IS_SYSTEM,false.toLDAPString))
  
  /**
   * Try to find the policy instance with the given ID.
   * Empty: no policy instance with such ID
   * Full((parent,pi)) : found the policy instance (pi.id == piId) in given parent
   * Failure => an error happened.
   */
  def getPolicyInstance(id:PolicyInstanceId) : Box[PolicyInstance] = {
    for {
      locked  <- userLibMutex.readLock
      con     <- ldap 
      piEntry <- getPolicyInstanceEntry(con, id) ?~! "Can not find Policy Instance with id %s".format(id)
      pi      <- mapper.entry2PolicyInstance(piEntry) ?~! "Error when transforming LDAP entry into a Policy Instance for id %s. Entry: %s".format(id, piEntry)
    } yield {
      pi
    }
  }
  
  def getAll(includeSystem:Boolean = false) : Box[Seq[PolicyInstance]] = {
    for {
      locked <- userLibMutex.readLock
      con    <- ldap
      //for each pi entry, map it. if one fails, all fails
      pis    <- sequence(con.searchSub(rudderDit.POLICY_TEMPLATE_LIB.dn,  policyFilter(includeSystem))) { piEntry => 
                  mapper.entry2PolicyInstance(piEntry) ?~! "Error when transforming LDAP entry into a Policy Instance. Entry: %s".format(piEntry)
                }
    } yield {
      pis
    } 
  }

  
  /**
   * Find the user policy template for which the given policy
   * instance is an instance. 
   * 
   * Return empty if no such policy instance is known, 
   * fails if no User policy template match the policy instance.
   */
  def getUserPolicyTemplate(id:PolicyInstanceId) : Box[UserPolicyTemplate] = {
    for {
      locked  <- userLibMutex.readLock
      con     <- ldap 
      piEntry <- getPolicyInstanceEntry(con, id, "1.1") ?~! "Can not find Policy Instance with id %s".format(id)
      upt     <- ldapUserPolicyTemplateRepository.getUserPolicyTemplate(mapper.dn2UserPolicyTemplateId(piEntry.dn.getParent))
    } yield {
      upt
    }
  }
  
  
  /**
   * Get policy instances for given policy template.
   * A not known policy template id is a failure.
   */
  override def getPolicyInstances(ptId:UserPolicyTemplateId, includeSystem:Boolean = false) : Box[Seq[PolicyInstance]] = {
    for {
      locked  <- userLibMutex.readLock
      con     <- ldap 
      ptEntry <- ldapUserPolicyTemplateRepository.getUPTEntry(con, ptId, "1.1")
      pis     <- sequence(con.searchOne(ptEntry.dn, policyFilter(includeSystem))) { piEntry => 
                   mapper.entry2PolicyInstance(piEntry) ?~! "Error when transforming LDAP entry into a Policy Instance. Entry: %s".format(piEntry)
                 }
    } yield {
      pis
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
   * Returned the saved WBUserPolicyInstance
   */
  def savePolicyInstance(inUserPolicyTemplateId:UserPolicyTemplateId,pi:PolicyInstance, actor:EventActor) : Box[Option[PolicyInstanceSaveDiff]] = {
    for {
      con         <- ldap
      uptEntry    <- ldapUserPolicyTemplateRepository.getUPTEntry(con, inUserPolicyTemplateId, "1.1") ?~! "Can not find the User Policy Entry with id %s to add Policy Instance %s".format(inUserPolicyTemplateId, pi.id)
      canAdd      <- { //check if the pi already exists elsewhere
                        getPolicyInstanceEntry(con, pi.id) match {
                          case f:Failure => f
                          case Empty => Full(None)
                          case Full(otherPi) => 
                            if(otherPi.dn.getParent == uptEntry.dn) Full(Some(otherPi))

                            else Failure("An other policy instance with the id %s exists in an other category that the one with id %s : %s".format(pi.id, inUserPolicyTemplateId, otherPi.dn))
                        }
                      }
      piEntry     =  mapper.userPolicyInstance2Entry(pi, uptEntry.dn)
      result      <- userLibMutex.writeLock { con.save(piEntry, true) }
      //for log event - perhaps put that elsewhere ?
      upt         <- ldapUserPolicyTemplateRepository.getUserPolicyTemplate(inUserPolicyTemplateId) ?~! "Can not find the User Policy Entry with id %s to add Policy Instance %s".format(inUserPolicyTemplateId, pi.id)
      val ptId    =  PolicyPackageId(upt.referencePolicyTemplateName,pi.policyTemplateVersion)
      pt          <- Box(policyPackageService.getPolicy(ptId)) ?~! "Can not find the Policy Template with ID '%s'".format(ptId.toString)
      optDiff     <- diffMapper.modChangeRecords2PolicyInstanceSaveDiff(pt.id.name, pt.rootSection, piEntry.dn, canAdd, result) ?~! "Error when processing saved modification to log them"
      eventLogged <- optDiff match {
                       case None => Full("OK")
                       case Some(diff:AddPolicyInstanceDiff) => 
                         actionLogger.saveAddPolicyInstance(
                             principal = actor, addDiff = diff, varsRootSectionSpec = pt.rootSection
                         )
                       case Some(diff:ModifyPolicyInstanceDiff) => 
                         actionLogger.saveModifyPolicyInstance(principal = actor, modifyDiff = diff)
                     }
      autoArchive <- if(autoExportOnModify) {
                       for {
                         parents  <- ldapUserPolicyTemplateRepository.userPolicyTemplateBreadCrump(upt.id)
                         archived <- gitPiArchiver.archivePolicyInstance(pi, pt.id.name, parents.map( _.id), pt.rootSection)
                       } yield archived
                     } else Full("ok")
    } yield {
      optDiff
    }
  }


  /**
   * Delete a policy instance.
   * No dependency check are done, and so you will have to
   * delete dependent configuration rule (or other items) by
   * hand if you want.
   */
  def delete(id:PolicyInstanceId, actor:EventActor) : Box[DeletePolicyInstanceDiff] = {
    for {
      con          <- ldap
      entry        <- getPolicyInstanceEntry(con, id)
      //for logging, before deletion
      pi           <- mapper.entry2PolicyInstance(entry)
      upt          <- this.getUserPolicyTemplate(id) ?~! "Can not find the User Policy Temple Entry for Policy Instance %s".format(id)
      pt           <- policyPackageService.getPolicy(PolicyPackageId(upt.referencePolicyTemplateName,pi.policyTemplateVersion))
      //delete
      deleted      <- userLibMutex.writeLock { con.delete(entry.dn) }
      diff         =  DeletePolicyInstanceDiff(pt.id.name, pi)
      loggedAction <- actionLogger.saveDeletePolicyInstance(
                          principal = actor, deleteDiff = diff, varsRootSectionSpec = pt.rootSection
                      )
      autoArchive  <- if(autoExportOnModify) {
                        for {
                          parents  <- ldapUserPolicyTemplateRepository.userPolicyTemplateBreadCrump(upt.id)
                          archived <- gitPiArchiver.deletePolicyInstance(pi.id, upt.referencePolicyTemplateName, parents.map( _.id))
                        } yield archived
                      } else Full("ok")
    } yield {
      diff
    }
  }

}