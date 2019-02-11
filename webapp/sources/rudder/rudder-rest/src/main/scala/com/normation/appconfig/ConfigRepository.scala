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
package com.normation.appconfig

import com.normation.ldap.sdk.BuildFilter.IS
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants.OC_PROPERTY
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.rudder.domain.appconfig.RudderWebProperty
import com.normation.rudder.domain.eventlog.ModifyGlobalPropertyEventType
import com.normation.rudder.repository.EventLogRepository
import com.normation.eventlog.ModificationId
import com.normation.utils.StringUuidGenerator
import com.normation.eventlog.EventActor

import com.normation.errors._
import scalaz.zio._
import scalaz.zio.syntax._

/**
 * A basic config repository, NOT typesafe.
 * The typecasting should be handle at a
 * higher level.
 */
trait ConfigRepository {


  def getConfigParameters(): IOResult[Seq[RudderWebProperty]]

  def saveConfigParameter(parameter: RudderWebProperty, modifyGlobalPropertyInfo : Option[ModifyGlobalPropertyInfo] ): IOResult[RudderWebProperty]

}


class LdapConfigRepository(
    rudderDit          : RudderDit
  , ldap               : LDAPConnectionProvider[RwLDAPConnection]
  , mapper             : LDAPEntityMapper
  , eventLogRepository : EventLogRepository
  , uuidGen            : StringUuidGenerator
) extends ConfigRepository {

  def getConfigParameters(): IOResult[Seq[RudderWebProperty]] = {
     for {
      con        <- ldap
      entries    <- con.searchSub(rudderDit.APPCONFIG.dn, IS(OC_PROPERTY))
      properties <- ZIO.foreach(entries) { entry =>
                      mapper.entry2RudderConfig(entry).toIO.chainError(s"Error when transforming LDAP entry into an application parameter. Entry: ${entry}")
                    }
    } yield {
      properties
    }
  }

  def saveConfigParameter(property: RudderWebProperty, modifyGlobalPropertyInfo : Option[ModifyGlobalPropertyInfo] ) = {
    for {
      allProperties <- getConfigParameters()
      // Find our old property, and if we need to save
      // We don't need to save if the value of the property has already the good value
      (oldProperty,needSave) =
        allProperties.find(_.name == property.name) match {
          case None =>
            // The property does not exist, should not happen, create and "empty" property (value "")
            (property.copy(value = ""),true)
          case Some(oldProperty) =>
            (oldProperty,oldProperty.value != property.value)
        }
      res <- {
        if (needSave) {
          // Save then save event log
          for {
            con       <- ldap
            propEntry =  mapper.rudderConfig2Entry(property)
            result    <- con.save(propEntry).chainError(s"Error when saving parameter entry in repository: ${propEntry}")
            eventLog  <-
              modifyGlobalPropertyInfo match {
                case Some(info) =>
                  val modId = ModificationId(uuidGen.newUuid)
                  eventLogRepository.saveModifyGlobalProperty(modId, info.actor, oldProperty, property, info.eventLogType, info.reason)
                case _ =>
                  UIO.unit
              }
          } yield {
            property
          }
        } else {
          property.succeed
        }
      }
    } yield {
     property
    }
  }

}


case class ModifyGlobalPropertyInfo(
    eventLogType : ModifyGlobalPropertyEventType
  , actor:EventActor
  , reason:Option[String]
)
