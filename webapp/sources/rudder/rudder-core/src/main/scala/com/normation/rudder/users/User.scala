package com.normation.rudder.users

/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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

import com.normation.eventlog.EventActor
import com.normation.utils.DateFormaterService
import org.joda.time.DateTime
import zio.json._
import zio.json.ast._

/*
 * Base data structures about users and everything related to their authentication: user information, user session.
 * Authentication related things are defined in rudder-rest since they bridge with SpringSecurity UserDetails, etc.
 */

/**
 * Users in rudder database follow a lifecycle :
 * - pristine user are created `active` (exists, able to connect, etc). That's the default status for users in the rudder-users.xml` file)
 * - they can become `disabled` (for ex on an admin action or because of some business rule like "last log in is too old)
 * - they can be `deleted`: they are marked deleted but still present in DB, so that if the user is recreated, it retrieves
 *   his information. It also allows to keep status change history for a configurable while (for ex for security reason)
 * - a `deleted` user is created again in the `disabled` status so that an admin can check everything is ok and
 * - they can be `purged`: this is not a real status but it's the vocabulary to user for when the user is totally
 *   cleaned-up and if added anew, it will be considered pristine.
 *
 */
sealed trait UserStatus {
  def value: String
}

object UserStatus {
  case object Active   extends UserStatus { override def value = "active"   }
  case object Disabled extends UserStatus { override def value = "disabled" }
  case object Deleted  extends UserStatus { override def value = "deleted"  }

  def values = ca.mrvisser.sealerate.values[UserStatus]
  def parse(s: String): Either[String, UserStatus] = {
    values
      .find(_.value == s.toLowerCase)
      .toRight(s"Error: value '${s}' is not a valid UserStatus. Possible choices are: '${values.map(_.value).mkString("' ,'")}''")
  }
}

final case class EventTrace(actor: EventActor, actionDate: DateTime, reason: String = "")

/*
 * Track changes in Status.
 * We track the new status information (considering that creation is a new status to track)
 */
final case class StatusHistory(status: UserStatus, trace: EventTrace)

/**
 * General information about the user, NOT LINKED with authentication, like: email, phone number, creation date, etc
 * Persisted in postgresql.
 */
case class UserInfo(
    id:            String,
    creationDate:  DateTime,
    status:        UserStatus,
    managedBy:     String,
    name:          Option[String],
    email:         Option[String],
    lastLogin:     Option[DateTime],
    statusHistory: List[StatusHistory],
    otherInfo:     Json.Obj
)

/**
 * Id of a (web) sessions, generally a string with the content of "sessionid" cookie.
 */
final case class SessionId(value: String)

/**
 * Information about user sessions: start, end, what authenticator, etc
 * Persisted in postgresql
 */
case class UserSession(
    userId:       String,
    sessionId:    SessionId,
    creationDate: DateTime,
    authMethod:   String,
    permissions:  List[String],
    endDate:      Option[DateTime],
    endCause:     Option[String]
)

object UserSerialization {
  implicit val codecUserStatus:    JsonCodec[UserStatus]    = new JsonCodec[UserStatus](
    JsonEncoder.string.contramap(_.value),
    JsonDecoder.string.mapOrFail(s => UserStatus.parse(s))
  )
  implicit val codecEventActor:    JsonCodec[EventActor]    = DeriveJsonCodec.gen
  implicit val codecDateTime:      JsonCodec[DateTime]      = new JsonCodec[DateTime](
    JsonEncoder.string.contramap(_.toString(DateFormaterService.rfcDateformatWithMillis)),
    JsonDecoder.string.mapOrFail { s =>
      try {
        Right(DateFormaterService.rfcDateformatWithMillis.parseDateTime(s))
      } catch {
        case e: Exception => Left(e.getMessage)
      }
    }
  )
  implicit val codecEventTrace:    JsonCodec[EventTrace]    = DeriveJsonCodec.gen
  implicit val codecStatusHistory: JsonCodec[StatusHistory] = DeriveJsonCodec.gen

}
