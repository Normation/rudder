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

import cats.implicits._
import com.normation.NamedZioLogger
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.ldap.sdk.BuildFilter._
import com.normation.ldap.sdk.LdapResult._
import com.normation.ldap.sdk._
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.rudder.domain.archives.ParameterArchiveId
import com.normation.rudder.domain.parameters.GlobalParameter
import com.normation.rudder.domain.parameters._
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.repository._
import com.normation.rudder.services.user.PersonIdentService
import com.unboundid.ldif.LDIFChangeRecord
import net.liftweb.common._
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import com.normation.errors._
import scalaz.zio._
import scalaz.zio.syntax._

class RoLDAPParameterRepository(
    val rudderDit   : RudderDit
  , val ldap        : LDAPConnectionProvider[RoLDAPConnection]
  , val mapper      : LDAPEntityMapper
  , val userLibMutex: ScalaReadWriteLock //that's a scala-level mutex to have some kind of consistency with LDAP
) extends RoParameterRepository with NamedZioLogger {
  repo =>

  override def loggerName: String = this.getClass.getName

  def getGlobalParameter(parameterName : ParameterName) : IOResult[Option[GlobalParameter]] = {
    userLibMutex.readLock(for {
      con     <- ldap
      opt     <- con.get(rudderDit.PARAMETERS.parameterDN(parameterName))
      param   <- opt match {
                   case None        => None.succeed
                   case Some(entry) => mapper.entry2Parameter(entry).map(Some.apply).toIO.chainError(
                                         s"Error when transforming LDAP entry into global parameter for name '${parameterName}'. Entry: ${entry}"
                                       )
                 }
    } yield {
      param
    })
  }

  private def getGP(search: RoLDAPConnection => LdapResult[Seq[LDAPEntry]]): IOResult[Seq[GlobalParameter]] = {
    userLibMutex.readLock(for {
      con     <- ldap
      entries <- search(con)
      params  <- ZIO.foreach(entries) { entry =>
                   mapper.entry2Parameter(entry).toIO.chainError("Error when transforming LDAP entry into global parameter. Entry: %s".format(entry))
                 }
    } yield {
      params
    })
  }

  def getAllGlobalParameters() : IOResult[Seq[GlobalParameter]] = {
     getGP(con => con.searchSub(rudderDit.PARAMETERS.dn, IS(OC_PARAMETER)))
  }

  def getAllOverridable() : IOResult[Seq[GlobalParameter]] = {
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
) extends WoParameterRepository with NamedZioLogger {
  repo =>

  import roLDAPParameterRepository._

  override def loggerName: String = this.getClass.getName

  def saveParameter(
      parameter : GlobalParameter
    , modId     : ModificationId
    , actor     :EventActor
    , reason    :Option[String]) : IOResult[AddGlobalParameterDiff] = {
    repo.synchronized {
      for {
        con             <- ldap
        doesntExists    <- roLDAPParameterRepository.getGlobalParameter(parameter.name).flatMap {
                              case Some(entry) => Unconsistancy(s"Cannot create a global parameter with name ${parameter.name.value} : there is already a parameter with the same name").fail
                              case None => UIO.unit
                           }
        paramEntry      =  mapper.parameter2Entry(parameter)
        result          <- userLibMutex.writeLock {
                              con.save(paramEntry).chainError(s"Error when saving parameter entry in repository: ${paramEntry}")
                           }
        diff            <- diffMapper.addChangeRecords2GlobalParameterDiff(paramEntry.dn, result).toIO
        loggedAction    <- actionLogger.saveAddGlobalParameter(modId, principal = actor, addDiff = diff, reason = reason).chainError("Error when logging modification as an event")
        autoArchive     <- (if(autoExportOnModify) { //only persists if modification are present
                             for {
                               commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                               archive  <- gitParameterArchiver.archiveParameter(parameter,Some((modId, commiter, reason)))
                             } yield {
                               archive
                             }
                           } else UIO.unit)
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
   ) : IOResult[Option[ModifyGlobalParameterDiff]] = {
    repo.synchronized {
      for {
        con             <- ldap
        oldParamEntry   <- roLDAPParameterRepository.getGlobalParameter(parameter.name).notOptional(s"Cannot update Global Parameter '${parameter.name}': there is no parameter with that name")
        paramEntry      =  mapper.parameter2Entry(parameter)
        result          <- userLibMutex.writeLock {
                             con.save(paramEntry).chainError(s"Error when saving parameter entry in repository: ${paramEntry}")
                           }
        optDiff         <- diffMapper.modChangeRecords2GlobalParameterDiff(
                                parameter.name
                              , paramEntry.dn
                              , oldParamEntry
                              , result
                            ).toIO
        loggedAction    <-  optDiff match {
                               case None => UIO.unit
                               case Some(diff) => actionLogger.saveModifyGlobalParameter(modId, principal = actor, modifyDiff = diff, reason = reason).chainError("Error when logging modification as an event")
                             }
        autoArchive     <- if(autoExportOnModify  && optDiff.isDefined) {//only persists if modification are present
                             (for {
                               commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                               archive  <- gitParameterArchiver.archiveParameter(parameter,Some((modId, commiter, reason)))
                             } yield {
                               archive
                             })
                           } else UIO.unit
      } yield {
        optDiff
      }
    }
  }

  def delete(parameterName:ParameterName, modId: ModificationId, actor:EventActor, reason:Option[String]) : IOResult[DeleteGlobalParameterDiff] = {
    for {
      con          <- ldap
      oldParamEntry<- roLDAPParameterRepository.getGlobalParameter(parameterName).notOptional(s"Cannot delete Global Parameter '${parameterName}': there is no parameter with that name")
      deleted      <- userLibMutex.writeLock {
                        con.delete(roLDAPParameterRepository.rudderDit.PARAMETERS.parameterDN(parameterName)).chainError(s"Error when deleting Global Parameter with name ${parameterName}")
                      }
      diff         =  DeleteGlobalParameterDiff(oldParamEntry)
      loggedAction <- actionLogger.saveDeleteGlobalParameter(modId, principal = actor, deleteDiff = diff, reason = reason)
      autoArchive  <- if(autoExportOnModify && deleted.size > 0) {
                        (for {
                          commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                          archive  <- gitParameterArchiver.deleteParameter(parameterName,Some((modId, commiter, reason)))
                        } yield {
                          archive
                        })
                      } else UIO.unit
    } yield {
      diff
    }
  }

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
  def swapParameters(newParameters:Seq[GlobalParameter]) : IOResult[ParameterArchiveId] = {

    def saveParam(con:RwLDAPConnection, parameter:GlobalParameter) : LdapResult[LDIFChangeRecord] = {
      val entry = mapper.parameter2Entry(parameter)
      con.save(entry)
    }
    //restore the archive in case of error
    def restore(con:RwLDAPConnection, previousParams:Seq[GlobalParameter]): LdapResult[Seq[LDIFChangeRecord]] = {
      for {
        deleteParams  <- con.delete(rudderDit.PARAMETERS.dn)
        savedBack     <- ZIO.foreach(previousParams) { params =>
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
      swapParams      <- userLibMutex.writeLock(
                           (for {
                             //move old params to archive branch
                             renamed     <- con.move(rudderDit.PARAMETERS.dn, ou.dn.getParent, Some(ou.dn.getRDN))
                             //now, create back config param branch and save rules
                             crOu        <- con.save(rudderDit.PARAMETERS.model)
                             savedParams <- ZIO.foreach(newParameters) { rule =>
                                              saveParam(con, rule)
                                            }
                           } yield {
                             id
                           }).catchAll(
                             e => //ok, so there, we have a problem
                               logPure.error(s"Error when importing params, trying to restore old Parameters. Error was: ${e.msg}") *>
                               restore(con, existingParams).foldM(
                                 err   => Chained(s"Error when rollbacking corrupted import for parameters, expect other errors. Archive ID: ${id.value}", e).fail
                               , value => logPure.info("Rollback parameters: ok") *>
                                          Chained("Rollbacked imported parameters to previous state", e).fail
                               )
                           )
                         )
    } yield {
      id
    }
  }

  def deleteSavedParametersArchiveId(archiveId:ParameterArchiveId) : IOResult[Unit] = {
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
