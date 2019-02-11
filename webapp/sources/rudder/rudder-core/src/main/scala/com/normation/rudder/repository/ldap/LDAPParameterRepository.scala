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
package com.normation.rudder.repository.ldap

import com.normation.rudder.domain.{RudderDit,RudderLDAPConstants}
import RudderLDAPConstants._
import net.liftweb.common._
import com.normation.ldap.sdk._
import com.normation.ldap.sdk.BuildFilter._
import com.normation.rudder.repository._
import com.normation.rudder.domain.parameters.GlobalParameter
import com.normation.utils.Control.sequence
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.services.user.PersonIdentService
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.parameters._
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.archives.ParameterArchiveId
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import com.unboundid.ldif.LDIFChangeRecord
import com.normation.ldap.sdk.IOLdap._
import cats.implicits._

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
      entry   <- con.get(rudderDit.PARAMETERS.parameterDN(parameterName)).notOptional(s"Global parameter with name '${parameterName.value}' was not found")
      param   <- (mapper.entry2Parameter(entry) ?~! "Error when transforming LDAP entry into global parameter for name %s. Entry: %s".format(parameterName, entry)).toIOLdap
    } yield {
      param
    }
  }.toBox

  private def getGP(search: RoLDAPConnection => IOLdap[Seq[LDAPEntry]]): Box[Seq[GlobalParameter]] = {
     for {
      locked  <- userLibMutex.readLock
      con     <- ldap
      entries <- search(con)
      params  <- entries.toVector.traverse { entry =>
                   (mapper.entry2Parameter(entry) ?~! "Error when transforming LDAP entry into global parameter. Entry: %s".format(entry)).toIOLdap
                 }
    } yield {
      params
    }
  }.toBox

  def getAllGlobalParameters() : Box[Seq[GlobalParameter]] = {
     getGP(con => con.searchSub(rudderDit.PARAMETERS.dn, IS(OC_PARAMETER)))
  }

  def getAllOverridable() : Box[Seq[GlobalParameter]] = {
    getGP(con => con.searchSub(rudderDit.PARAMETERS.dn, AND(IS(OC_PARAMETER), EQ(A_PARAMETER_OVERRIDABLE, true.toLDAPString))))
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

  import roLDAPParameterRepository._

  def saveParameter(
      parameter : GlobalParameter
    , modId     : ModificationId
    , actor     :EventActor
    , reason    :Option[String]) : Box[AddGlobalParameterDiff] = {
    repo.synchronized {
      for {
        con             <- ldap
        doesntExists    <- roLDAPParameterRepository.getGlobalParameter(parameter.name) match {
                              case Full(entry) => "Cannot create a global parameter with name %s : there is already a parameter with the same name".format(parameter.name.value).failureIOLdap()
                              case Empty => "OK".successIOLdap()
                              case e:Failure => e.toIOLdap
                           }
        paramEntry      =  mapper.parameter2Entry(parameter)
        result          <- userLibMutex.writeLock {
                              con.save(paramEntry) ?~! "Error when saving parameter entry in repository: %s".format(paramEntry)
                           }
        diff            <- diffMapper.addChangeRecords2GlobalParameterDiff(paramEntry.dn, result).toIOLdap
        loggedAction    <- (actionLogger.saveAddGlobalParameter(modId, principal = actor, addDiff = diff, reason = reason) ?~! "Error when logging modification as an event").toIOLdap
        autoArchive     <- (if(autoExportOnModify) { //only persists if modification are present
                             for {
                               commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                               archive  <- gitParameterArchiver.archiveParameter(parameter,Some((modId, commiter, reason)))
                             } yield {
                               archive
                             }
                           } else Full("ok")).toIOLdap
      } yield {
        diff
      }
    }
  }.toBox

  def updateParameter(
      parameter : GlobalParameter
    , modId     : ModificationId
    , actor     : EventActor
    , reason    : Option[String]
   ) : Box[Option[ModifyGlobalParameterDiff]] = {
    repo.synchronized {
      for {
        con             <- ldap
        oldParamEntry   <- (roLDAPParameterRepository.getGlobalParameter(parameter.name) ?~! "Cannot update Global Parameter %s : there is no parameter with that name".format(parameter.name)).toIOLdap
        paramEntry      =  mapper.parameter2Entry(parameter)
        result          <- userLibMutex.writeLock {
                             con.save(paramEntry) ?~! "Error when saving parameter entry in repository: %s".format(paramEntry)
                           }
        optDiff         <- diffMapper.modChangeRecords2GlobalParameterDiff(
                                parameter.name
                              , paramEntry.dn
                              , oldParamEntry
                              , result
                            ).toIOLdap
        loggedAction    <-  optDiff match {
                               case None => "OK".successIOLdap()
                               case Some(diff) => (actionLogger.saveModifyGlobalParameter(modId, principal = actor, modifyDiff = diff, reason = reason) ?~! "Error when logging modification as an event").toIOLdap
                             }
        autoArchive     <- if(autoExportOnModify  && optDiff.isDefined) {//only persists if modification are present
                             (for {
                               commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                               archive  <- gitParameterArchiver.archiveParameter(parameter,Some((modId, commiter, reason)))
                             } yield {
                               archive
                             }).toIOLdap
                           } else "ok".successIOLdap()
      } yield {
        optDiff
      }
    }
  }.toBox

  def delete(parameterName:ParameterName, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[DeleteGlobalParameterDiff] = {
    for {
      con          <- ldap
      oldParamEntry<- (roLDAPParameterRepository.getGlobalParameter(parameterName) ?~! "Cannot delete Global Parameter %s : there is no parameter with that name".format(parameterName)).toIOLdap
      deleted      <- userLibMutex.writeLock {
                        con.delete(roLDAPParameterRepository.rudderDit.PARAMETERS.parameterDN(parameterName)) ?~! "Error when deleting Global Parameter with name %s".format(parameterName)
                      }
      diff         =  DeleteGlobalParameterDiff(oldParamEntry)
      loggedAction <- actionLogger.saveDeleteGlobalParameter(modId, principal = actor, deleteDiff = diff, reason = reason).toIOLdap
      autoArchive  <- if(autoExportOnModify && deleted.size > 0) {
                        (for {
                          commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                          archive  <- gitParameterArchiver.deleteParameter(parameterName,Some((modId, commiter, reason)))
                        } yield {
                          archive
                        }).toIOLdap
                      } else "ok".successIOLdap()
    } yield {
      diff
    }
  }.toBox

  /**
   * Implementation logic:
   * - lock LDAP for other writes (more precisely, only that repos, with
   *   synchronized method),
   * - create a ou=Parameter-YYYY-MM-dd_HH-mm in the "archive" branche
   * - move ALL current parameters in the previous 'ou'
   * - save newParameters
   * - release lock
   *
   * If something goes wrong, try to restore.
   */
  def swapParameters(newParameters:Seq[GlobalParameter]) : Box[ParameterArchiveId] = {

    def saveParam(con:RwLDAPConnection, parameter:GlobalParameter) : IOLdap[LDIFChangeRecord] = {
      val entry = mapper.parameter2Entry(parameter)
      con.save(entry)
    }
    //restore the archive in case of error
    def restore(con:RwLDAPConnection, previousParams:Seq[GlobalParameter]): IOLdap[Seq[LDIFChangeRecord]] = {
      for {
        deleteParams  <- con.delete(rudderDit.PARAMETERS.dn)
        savedBack     <- previousParams.toVector.traverse { params =>
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
      existingParams <- getAllGlobalParameters.toIOLdap
      con            <- ldap
      //ok, now that's the dangerous part
      swapParams      <- userLibMutex.writeLock { (for {
                       //move old params to archive branch
                       renamed     <- con.move(rudderDit.PARAMETERS.dn, ou.dn.getParent, Some(ou.dn.getRDN))
                       //now, create back config param branch and save rules
                       crOu        <- con.save(rudderDit.PARAMETERS.model)
                       savedParams    <- newParameters.toVector.traverse { rule =>
                                         saveParam(con, rule)
                                      }

                     } yield {
                       id
                     })  match {
                       case Right(value) => value.successIOLdap()
                       case Left(e) => //ok, so there, we have a problem
                         logger.error(s"Error when importing params, trying to restore old Parameters. Error was: ${e.messageChain}")
                         restore(con, existingParams) match {
                           case Right(value) =>
                             logger.info("Rollback parameters")
                             LDAPConnectionError.Chained("Rollbacked imported parameters to previous state", e).failureIOLdap()
                           case Left(_) =>
                             LDAPConnectionError.Chained("Error when rollbacking corrupted import for parameters, expect other errors. Archive ID: '%s'".format(id.value), e).failureIOLdap()
                         }

                     } }
    } yield {
      id
    }
  }.toBox

  def deleteSavedParametersArchiveId(archiveId:ParameterArchiveId) : Box[Unit] = {
    repo.synchronized {
      for {
        con     <- ldap
        deleted <- con.delete(rudderDit.ARCHIVES.parameterModel(archiveId).dn)
      } yield {
        {}
      }
    }
  }.toBox
}
