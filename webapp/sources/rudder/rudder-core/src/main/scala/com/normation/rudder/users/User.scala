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
import com.normation.rudder.AuthorizationType
import com.normation.rudder.Rights
import com.normation.rudder.api.ApiAccount
import com.normation.rudder.api.ApiAuthorization
import com.normation.rudder.facts.nodes.NodeSecurityContext
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.utils.DateFormaterService
import enumeratum.*
import io.scalaland.chimney.Transformer
import java.security.SecureRandom
import org.joda.time.DateTime
import zio.json.*
import zio.json.ast.Json

/*
 * Base data structures about users and everything related to their authentication: user information, user session.
 * Final authentication structures are defined in rudder-rest since they bridge with SpringSecurity UserDetails, etc.
 */

/**
 * Rudder user details must know if the account is for a
 * rudder user or an api account, and in the case of an
 * api account, what sub-case of it.
 */
sealed trait RudderAccount

object RudderAccount {
  final case class User(login: String, password: UserPassword) extends RudderAccount

  final case class Api(api: ApiAccount) extends RudderAccount
}

/**
 * An authenticated user with the relevant authentication information within the API.
 * That structure will be kept in session (or for API, in the request processing).
 */
trait AuthenticatedUser {
  def account: RudderAccount

  def name:  String = account match {
    case RudderAccount.User(login, _) => login
    case RudderAccount.Api(api)       => api.name.value
  }
  def login: String = name

  def authz:       Rights
  def apiAuthz:    ApiAuthorization
  def nodePerms:   NodeSecurityContext
  implicit def qc: QueryContext = {
    QueryContext(EventActor(name), nodePerms)
  }

  def checkRights(auth: AuthorizationType): Boolean
}

sealed trait UserPassword {

  /**
   * Override the toString when needed for subtypes
   */
  override def toString: String = "[REDACTED UserPassword]"
}

object UserPassword {

  /**
   * Type class to declare that secret (clear-text) password can be encoded.
   * This avoids breaking private visibility of the secret,
   * by only making it possible to obtain a hashed password after encoding (see usage)
   */
  trait UserPasswordEncoder[T <: UserPassword] {
    protected[users] def encode(t: CharSequence): String
  }

  given (using encoder: UserPasswordEncoder[SecretUserPassword]): Transformer[SecretUserPassword, HashedUserPassword] =
    s => HashedUserPassword(encoder.encode(s.secret))

  private val secureRandom = new SecureRandom()

  sealed trait StorableUserPassword extends UserPassword {
    def exposeValue(): String
  }

  case class HashedUserPassword private[UserPassword] (private val value: String) extends StorableUserPassword {
    // if we apply strict check on hash format, maybe we should the display first chars, to know at least the hash algo ?
    override def toString: String = "[REDACTED HashedUserPassword]"

    override def exposeValue(): String = value
  }

  /**
   * When the password is raw user input that was not hashed
   */
  case class SecretUserPassword private[UserPassword] (private[UserPassword] val secret: String) extends UserPassword {
    override def toString: String = "[REDACTED SecretUserPassword]"
  }

  /**
   * For cases when user cannot have a password e.g. with remote authentication, it should never match
   */
  case class UnknownPassword(value: String) extends StorableUserPassword {
    override def toString: String = "[REDACTED UnknownPassorwd]"

    override def exposeValue(): String = value
  }

  case class RandomHexaPassword private[UserPassword] (randomValue: String) extends StorableUserPassword {
    override def exposeValue(): String = randomValue
  }

  /**
   * Create a hashed password from string, the string format is not checked for hash properties,
   * it is only checked for emptiness (maybe later, it should be strictly checked
   * with our RudderPasswordEncoder#getFromEncoded).
   * The hash could then be used as a "hashed" password type.
   */
  def unsafeHashed(s: String): StorableUserPassword = {
    if (s.strip().nonEmpty) {
      HashedUserPassword(s)
    } else {
      UnknownPassword(s)
    }
  }

  // password can be optional when an other authentication backend is used.
  // When the tag is omitted, we generate a 32 bytes random value in place of the pass internally
  // to avoid any cases where the empty string will be used if all other backend are in failure.
  // Also forbid empty or all blank passwords.
  // If the attribute is defined several times, use the first occurrence.
  // see https://stackoverflow.com/a/44227131
  // produce a random hexa string of 32 chars
  def randomHexa32: RandomHexaPassword = {
    // here, we can be unlucky with the chosen token which convert to an int starting with one or more 0.
    // In that case, just complete the string
    def randInternal: String = {
      val token = new Array[Byte](16)
      secureRandom.nextBytes(token)
      new java.math.BigInteger(1, token).toString(16)
    }

    var s = randInternal
    while (s.length < 32) { // we can be very unlucky and keep drawing 000s
      s = s + randInternal.substring(0, 32 - s.length)
    }
    RandomHexaPassword(s)
  }

  def fromSecret(s: String): SecretUserPassword = SecretUserPassword(s)

  def unknown: UnknownPassword = UnknownPassword("") // this is allowed to be stored, not used as hash

  /**
   * Applying checks on the string, but this does not validate the format of the password
   */
  object checkHashedPassword {
    def unapply(s: String): Option[HashedUserPassword] = unsafeHashed(s) match {
      case h: HashedUserPassword => Some(h)
      case _ => None
    }
  }
}

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
sealed trait UserStatus extends EnumEntry {
  def value: String
}

object UserStatus extends Enum[UserStatus] {
  case object Active   extends UserStatus { override def value = "active"   }
  case object Disabled extends UserStatus { override def value = "disabled" }
  case object Deleted  extends UserStatus { override def value = "deleted"  }

  val values:           IndexedSeq[UserStatus]     = findValues
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
    authz:        List[String],
    tenants:      Option[String],
    endDate:      Option[DateTime],
    endCause:     Option[String]
) {
  def isOpen: Boolean = endDate.isEmpty
}

object UserSerialization {
  implicit val codecUserStatus: JsonCodec[UserStatus] = new JsonCodec[UserStatus](
    JsonEncoder.string.contramap(_.value),
    JsonDecoder.string.mapOrFail(s => UserStatus.parse(s))
  )

  // in scala 2, zio-json was happily deriving a Codec for an AnyVal as for any case class.
  // it's not the case in scala 3
  // it's probably not the original intent of the author but for compatibility purpose,
  // we need to maintain this serialization format.
  private case class EventActorSerializable(name: String)
  implicit val codecEventActor: JsonCodec[EventActor] = DeriveJsonCodec
    .gen[EventActorSerializable]
    .transform(serializable => EventActor(name = serializable.name), eventActor => EventActorSerializable(name = eventActor.name))

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
