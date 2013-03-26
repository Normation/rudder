/*
*************************************************************************************
* Copyright 2013 Normation SAS
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
package com.normation.rudder.repository.ldap

import com.normation.rudder.domain.{RudderDit,RudderLDAPConstants}
import RudderLDAPConstants._
import net.liftweb.common._
import com.normation.ldap.sdk._
import com.normation.ldap.sdk.BuildFilter._
import com.unboundid.ldap.sdk.{DN,Filter}
import com.normation.rudder.repository._
import com.normation.rudder.domain.parameters.GlobalParameter
import com.normation.utils.Control.sequence
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.services.user.PersonIdentService
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.parameters._
import com.normation.eventlog.EventActor
import com.normation.utils.ScalaReadWriteLock
import com.normation.rudder.domain.archives.ParameterArchiveId
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import com.unboundid.ldif.LDIFChangeRecord

class RoLDAPParameterRepository(
    val rudderDit   : RudderDit
  , val ldap        : LDAPConnectionProvider[RoLDAPConnection]
  , val mapper      : LDAPEntityMapper
  , val userLibMutex: ScalaReadWriteLock //that's a scala-level mutex to have some kind of consistency with LDAP
) extends RoParameterRepository with Loggable {
  repo =>
  
  def getGlobalParameter(parameterName : ParameterName) : Box[GlobalParameter] = {
    for {
      locked  <- userLibMutex.readLock
      con     <- ldap
      entry   <- con.get(rudderDit.PARAMETERS.parameterDN(parameterName))
      param   <- mapper.entry2Parameter(entry) ?~! "Error when transforming LDAP entry into global parameter for name %s. Entry: %s".format(parameterName, entry)
    } yield {
      param
    }
  }
  
  def getAllGlobalParameters() : Box[Seq[GlobalParameter]] = {
     for {
      locked  <- userLibMutex.readLock
      con     <- ldap
      entries =  con.searchSub(rudderDit.PARAMETERS.dn, IS(OC_PARAMETER))
      params  <- sequence(entries) { entry =>
                   mapper.entry2Parameter(entry) ?~! "Error when transforming LDAP entry into global parameter. Entry: %s".format(entry) 
                 }
    } yield {
      params
    }
  }
  
  def getAllOverridable() : Box[Seq[GlobalParameter]] = {
    for {
      locked  <- userLibMutex.readLock
      con     <- ldap
      entries =  con.searchSub(rudderDit.PARAMETERS.dn, AND(IS(OC_PARAMETER), EQ(A_PARAMETER_OVERRIDABLE, true.toLDAPString)))
      params  <- sequence(entries) { entry =>
                   mapper.entry2Parameter(entry) ?~! "Error when transforming LDAP entry into global parameter. Entry: %s".format(entry) 
                 }
    } yield {
      params
    }
  }
}

class WoLDAPParameterRepository(
    roLDAPParameterRepository : RoLDAPParameterRepository
  , ldap                      : LDAPConnectionProvider[RwLDAPConnection]
  , diffMapper                : LDAPDiffMapper
  , actionLogger              : EventLogRepository
  , gitParameterArchiver      : GitParameterArchiver
  , personIdentService        : PersonIdentService
  , autoExportOnModify        : Boolean
) extends WoParameterRepository with Loggable {
  repo =>
  
  import roLDAPParameterRepository.{ ldap => roLdap, _ }
    
  def saveParameter(
      parameter : GlobalParameter
    , modId     : ModificationId
    , actor     :EventActor
    , reason    :Option[String]) : Box[AddGlobalParameterDiff] = {
    repo.synchronized { 
      for {
        con             <- ldap
        doesntExists    <- roLDAPParameterRepository.getGlobalParameter(parameter.name) match {
                              case Full(entry) => Failure("Cannot create a global parameter with name %s : there is already a parameter with the same name".format(parameter.name.value))
                              case Empty => Full("OK")
                              case e:Failure => e
                           }
        paramEntry      =  mapper.parameter2Entry(parameter)
        result          <- userLibMutex.writeLock {
                              con.save(paramEntry) ?~! "Error when saving parameter entry in repository: %s".format(paramEntry)
                           }
        diff            <- diffMapper.addChangeRecords2GlobalParameterDiff(
                                paramEntry.dn
                              , result)
        loggedAction    <- actionLogger.saveAddGlobalParameter(modId, principal = actor, addDiff = diff, reason = reason) ?~! "Error when logging modification as an event"
        autoArchive     <- if(autoExportOnModify) { //only persists if modification are present
                             for {
                               commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                               archive  <- gitParameterArchiver.archiveParameter(parameter, Some(modId,commiter, reason))
                             } yield {
                               archive
                             }
                           } else Full("ok")
      } yield {
        diff
      }
    }
  }

  def updateParameter(
      parameter : GlobalParameter
    , modId     : ModificationId
    , actor     : EventActor
    , reason    : Option[String]
   ) : Box[Option[ModifyGlobalParameterDiff]] = {
    repo.synchronized { 
      for {
        con             <- ldap
        oldParamEntry   <- roLDAPParameterRepository.getGlobalParameter(parameter.name) ?~! "Cannot update Global Parameter %s : there is no parameter with that name".format(parameter.name)
        paramEntry      =  mapper.parameter2Entry(parameter)
        result          <- userLibMutex.writeLock { 
                             con.save(paramEntry) ?~! "Error when saving parameter entry in repository: %s".format(paramEntry)
                           }
        optDiff         <- diffMapper.modChangeRecords2GlobalParameterDiff(
                                parameter.name
                              , paramEntry.dn
                              , oldParamEntry
                              , result)
        loggedAction    <-  optDiff match {
                               case None => Full("OK")
                               case Some(diff) => actionLogger.saveModifyGlobalParameter(modId, principal = actor, modifyDiff = diff, reason = reason) ?~! "Error when logging modification as an event"
                             }
        autoArchive     <- if(autoExportOnModify  && optDiff.isDefined) {//only persists if modification are present
                             for {
                               commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                               archive  <- gitParameterArchiver.archiveParameter(parameter, Some(modId,commiter, reason))
                             } yield {
                               archive
                             }
                           } else Full("ok")
      } yield {
        optDiff
      }
    }
  }

  def delete(parameterName:ParameterName, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[DeleteGlobalParameterDiff] = {
    for {
      con          <- ldap
      oldParamEntry<- roLDAPParameterRepository.getGlobalParameter(parameterName) ?~! "Cannot delete Global Parameter %s : there is no parameter with that name".format(parameterName)
      deleted      <- userLibMutex.writeLock {
                        con.delete(roLDAPParameterRepository.rudderDit.PARAMETERS.parameterDN(parameterName)) ?~! "Error when deleting Global Parameter with name %s".format(parameterName)
                      }
      diff         =  DeleteGlobalParameterDiff(oldParamEntry)
      loggedAction <- actionLogger.saveDeleteGlobalParameter(modId, principal = actor, deleteDiff = diff, reason = reason)
      autoArchive  <- if(autoExportOnModify && deleted.size > 0) {
                        for {
                          commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                          archive  <- gitParameterArchiver.deleteParameter(parameterName, Some(modId,commiter, reason))
                        } yield {
                          archive
                        }
                      } else Full("ok")
    } yield {
      diff
    }
  }

  /**
   * Implementation logic:
   * - lock LDAP for other writes (more precisely, only that repos, with
   *   synchronized method),
   * - create a ou=Parameter-YYYY-MM-DD_HH-mm in the "archive" branche
   * - move ALL current parameters in the previous 'ou'
   * - save newParameters
   * - release lock
   *
   * If something goes wrong, try to restore.
   */
  def swapParameters(newParameters:Seq[GlobalParameter]) : Box[ParameterArchiveId] = {

    def saveParam(con:RwLDAPConnection, parameter:GlobalParameter) : Box[LDIFChangeRecord] = {
      val entry = mapper.parameter2Entry(parameter)
      con.save(entry)
    }
    //restore the archive in case of error
    def restore(con:RwLDAPConnection, previousParams:Seq[GlobalParameter]) = {
      for {
        deleteParams  <- con.delete(rudderDit.PARAMETERS.dn)
        savedBack     <- sequence(previousParams) { params =>
                         saveParam(con,params)
                      }
      } yield {
        savedBack
      }
    }

    ///// actual code for swapRules /////

    val id = ParameterArchiveId((DateTime.now()).toString(ISODateTimeFormat.dateTime))
    val ou = rudderDit.ARCHIVES.parameterModel(id)

    for {
        existingParams <- getAllGlobalParameters
        con            <- ldap
        //ok, now that's the dangerous part
        swapParams      <- userLibMutex.writeLock { (for {
                         //move old params to archive branch
                         renamed     <- con.move(rudderDit.PARAMETERS.dn, ou.dn.getParent, Some(ou.dn.getRDN))
                         //now, create back config param branch and save rules
                         crOu        <- con.save(rudderDit.PARAMETERS.model)
                         savedParams    <- sequence(newParameters) { rule =>
                                           saveParam(con, rule)
                                        }

                       } yield {
                         id
                       })  match {
                         case ok:Full[_] => ok
                         case eb:EmptyBox => //ok, so there, we have a problem
                           val e = eb ?~! "Error when importing params, trying to restore old Parameters"
                           logger.error(eb)
                           restore(con, existingParams) match {
                             case _:Full[_] =>
                               logger.info("Rollback parameters")
                               eb ?~! "Rollbacked imported parameters to previous state"
                             case x:EmptyBox =>
                               val m = "Error when rollbacking corrupted import for parameters, expect other errors. Archive ID: '%s'".format(id.value)
                               eb ?~! m
                           }

                       } }
      } yield {
        id
      }

  }
  
  def deleteSavedParametersArchiveId(archiveId:ParameterArchiveId) : Box[Unit] = {
    repo.synchronized {
      for {
        con     <- ldap
        deleted <- con.delete(rudderDit.ARCHIVES.parameterModel(archiveId).dn)
      } yield {
        {}
      }
    }
  }
}