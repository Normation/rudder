/*
 * *************************************************************************************
 * Copyright 2026 Normation SAS
 * *************************************************************************************
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
 * *************************************************************************************
 */
package com.normation.rudder.users

import com.normation.NamedZioLogger
import com.normation.errors.*
import com.normation.rudder.users.TotpError.EnrollmentError
import com.normation.utils.DateFormaterService
import enumeratum.*
import java.time.Instant
import zio.*
import zio.json.enumeratum.EnumCodec

opaque type UserId = String
object UserId {
  def apply(s: String): UserId = s

  extension (self: UserId) {
    def value: String = self
  }
}

// Need to be a class because opaque type doesn't allow toString override
final class TotpSecret private (secret: String) extends AnyVal {
  // Avoid printing the value
  override def toString: String = "[REDACTED TotpSecret]"

  def exposeSecret(): String = {
    secret
  }
}
object TotpSecret {
  def apply(secret: String) = new TotpSecret(secret)
}

/**
 * Represents a TOTP (Time-based One-Time Password) configuration for a user.
 * Stores the secret and creation timestamp.
 */
case class Totp(
    secret:  TotpSecret,
    created: Instant
)

/**
 * Enforcement level for globally enforced TOTP.
 * Derived globally from the configuration
 */
sealed trait TotpEnforcementLevel
object TotpEnforcementLevel {
  case object Enforced extends TotpEnforcementLevel
  case object Disabled extends TotpEnforcementLevel
  // case object Optional extends TotpEnforcementLevel // not used for now that we only have enforced configuration
}

/**
 * Per-user enrollment state.
 * This is the one exposed where we need to know if user OTP is defined or not yet.
 * Serialized value in OTP API to get OTP status of user
 */
sealed trait TotpEnrollmentStatus(override val entryName: String) extends EnumEntry
object TotpEnrollmentStatus                                       extends Enum[TotpEnrollmentStatus] with EnumCodec[TotpEnrollmentStatus] {
  private case object EnrollmentNeeded    extends TotpEnrollmentStatus("enrollmentNeeded")
  // derived from global enforcement level
  private case object EnrollmentNotNeeded extends TotpEnrollmentStatus("enrollmentNotNeeded")
  private case object Enrolled            extends TotpEnrollmentStatus("enrolled")

  extension (self: TotpEnrollmentStatus) {
    def isEnrolled: Boolean = self match {
      case Enrolled                               => true
      case EnrollmentNeeded | EnrollmentNotNeeded => false
    }
  }

  // In case of not known enrollment, we can have "not enrolled" if enrollment is optional
  def default(enforcement: TotpEnforcementLevel): TotpEnrollmentStatus = enforcement match {
    case TotpEnforcementLevel.Enforced => EnrollmentNeeded
    case TotpEnforcementLevel.Disabled => EnrollmentNotNeeded
  }

  def enrolled: TotpEnrollmentStatus = Enrolled

  override def values: IndexedSeq[TotpEnrollmentStatus] = findValues
}

/**
 * Per-user OTP display status in user management.
 * It's not strictly the same semantic as [[TotpEnrollmentStatus]] since
 * it represents user management semantics with provider options.
 */
sealed trait TotpUserStatus(override val entryName: String) extends EnumEntry
object TotpUserStatus                                       extends Enum[TotpUserStatus] with EnumCodec[TotpUserStatus] {
  case object Enrolled      extends TotpUserStatus("enrolled")
  case object NotEnrolled   extends TotpUserStatus("notEnrolled")
  case object NotApplicable extends TotpUserStatus("notApplicable")
  override def values: IndexedSeq[TotpUserStatus] = findValues

}

/**
 * All of a user's TOTP info.
 * Note that there is an inconsistency if status is not enrolled but enrollment says so
 */
final class TotpUser private (id: UserId, val status: TotpUserStatus)
object TotpUser {
  private val delegatedAuthProviders = Set("oidc", "oauth2")

  def apply(userInfo: UserInfo, enrollment: TotpEnrollmentStatus): TotpUser = {
    val status = {
      if (enrollment.isEnrolled) TotpUserStatus.Enrolled
      else if (delegatedAuthProviders.contains(userInfo.managedBy)) TotpUserStatus.NotApplicable
      else TotpUserStatus.NotEnrolled
    }
    new TotpUser(UserId(userInfo.id), status)
  }

  def default(userInfo: UserInfo, enforcement: TotpEnforcementLevel): TotpUser = {
    apply(userInfo, TotpEnrollmentStatus.default(enforcement))
  }
}

/**
 * Known errors that happen to be retried/issued from OTP spec implementation
 */
enum TotpError {
  // from otp implementations: invalid code
  case CodeError(msg: String, at: Instant, userId: UserId)

  // security case that is explicitly handled: https://issues.rudder.io/issues/30145
  case ReplayError(msg: String, from: Instant, userId: UserId) extends TotpError with SecurityError
}

object TotpError {
  import EnrollmentError.*
  import VerifyError.*

  /**
   * Our business logic errors
   */
  sealed trait ValidationError

  enum EnrollmentError extends ValidationError {
    case NoEnrolledSecret(msg: String, userId: UserId)
    case Database(details: RudderError, userId: UserId)
  }

  enum VerifyError extends ValidationError {
    case UnknownUser(databaseError: Option[RudderError], userId: UserId)
  }

  /**
   * Error-handling outcome that determines what is:
   * - logged by the system
   * - returned as a displayable and human-friendly error to a user
   * Both messages can be different for some OTP validation cases
   * (e.g. user already known their ID, database error are system and hidden in user error, ...).
   * 
   * The outcome should be independent of the evaluation context such as
   * error recovery, so that the declared outcome are as truthful as possible.
   */
  case class RudderOutcome[A](log: NamedZioLogger => UIO[Unit], error: RudderError) {
    def fail(using logger: NamedZioLogger): IOResult[Nothing] = log(logger) *> ZIO.fail(error)
  }

  sealed trait ToRudderErrorOutcome[A] {
    extension (a: A) def toRudderError: RudderOutcome[A]
  }

  given ToRudderErrorOutcome[ReplayError] with             {
    extension (e: ReplayError)
      def toRudderError: RudderOutcome[ReplayError] = {
        RudderOutcome(
          _.warn(s"${e.fullMsg} since ${e.from} by user '${e.userId.value}'"),
          Inconsistency(
            s"${e.msg}.\nIf it was not by you, it might be a security problem, you " +
            s"should notify your Rudder administrator. Otherwise, please retry with the next TOTP code"
          )
        )
      }
  }
  given ToRudderErrorOutcome[CodeError] with               {
    extension (e: CodeError)
      def toRudderError: RudderOutcome[CodeError] = {
        // plain invalid code:
        // only log at debug (we should rate limit to avoid verbose logs in case of brute force)
        RudderOutcome(
          _.debug(s"TOTP secret for user '${e.userId.value}' submitted at ${e.at.toString} could not be verified: ${e.msg}"),
          Inconsistency(s"${e.msg}.\nAsk your Rudder administrator to reset your TOTP if you lost it")
        )
      }
  }
  given ToRudderErrorOutcome[VerifyError.UnknownUser] with {
    extension (e: UnknownUser)
      def toRudderError: RudderOutcome[UnknownUser] = {
        RudderOutcome(
          _.error(s"User '${e.userId.value}' OTP was not found" + e.databaseError.map(m => s": ${m.fullMsg}").getOrElse("")),
          Unexpected("TOTP could not be verified. Please contact your Rudder administrator")
        )
      }
  }

  given ToRudderErrorOutcome[EnrollmentError.Database] with {
    extension (e: Database)
      def toRudderError: RudderOutcome[Database] = {
        RudderOutcome(
          _.error(
            s"User '${e.userId.value}' TOTP registration failed: could not store user TOTP in database: ${e.details.fullMsg}"
          ),
          Unexpected(s"TOTP registration failed. Please retry, or contact your Rudder administrator")
        )
      }
  }

  // This one can be a database error upon verify for already enrolled user.
  // If it's not database, that is a potential security error that needs to be logged:
  // a user not known in database makes an API request to verify without even having enrolled a secret.
  given ToRudderErrorOutcome[(UnknownUser, NoEnrolledSecret)] with {
    extension (e: (UnknownUser, NoEnrolledSecret))
      def toRudderError: RudderOutcome[(UnknownUser, NoEnrolledSecret)] = {
        e match {
          case (UnknownUser(Some(error), _), _)                      =>
            RudderOutcome(
              _.error(error.fullMsg),
              Unexpected("TOTP could not be verified. Please contact your Rudder administrator")
            )
          case (UnknownUser(None, _), NoEnrolledSecret(msg, userId)) =>
            RudderOutcome(
              _.warn(
                s"TOTP verification occurred for unknown user '${userId.value}', this may be a security problem: ${msg}"
              ),
              // generic message to not give any hints
              Unexpected("TOTP could not be found")
            )
        }
      }
  }

}

object TotpVerifyError {
  import TotpError.*

  private def fail[E](err: E)(using ToRudderErrorOutcome[E], NamedZioLogger): IOResult[Nothing] = {
    err.toRudderError.fail
  }

  extension [T](using NamedZioLogger)(
      a: ZIO[Any, TotpError | EnrollmentError, T]
  ) {
    /*
     * Verification endpoint has two entry-points:
     * - first, attempt to verify the code as a generated code for enrollment
     * - second, if the former failed, attempt to verify it for a known user
     *
     * It's done in this order, because the generated code check is cheaper (in-memory)
     * than existing user check (which requires database access that we want to avoid).
     *
     * Having 2 dedicated endpoints/filters would change the error-handling logic
     */
    def handleVerify(b: ZIO[Any, TotpError | VerifyError, Unit]): UIO[PureResult[Unit]] = (for {
      first  <- a.either
      _      <-
        // fail-fast upon database error: there may be a database problem (users table just purged, ...)
        first match {
          case Left(dbError: EnrollmentError.Database) =>
            fail(dbError)
          case _                                       =>
            ZIO.unit
        }
      // no need to run second if first is right
      second <- ZIO.unless(first.isRight)(b.either).someOrElse(Right(()))
      _      <- (first, second) match {
                  case (Right(_), _)                                                                 =>
                    ZIO.unit
                  case (_, Right(_))                                                                 =>
                    ZIO.unit
                  case (Left(e: ReplayError), _)                                                     =>
                    fail(e)
                  case (_, Left(e: ReplayError))                                                     =>
                    fail(e)
                  case (Left(e: CodeError), _)                                                       =>
                    fail(e)
                  case (_, Left(e: CodeError))                                                       =>
                    fail(e)
                  case (Left(e: EnrollmentError.NoEnrolledSecret), Left(u: VerifyError.UnknownUser)) =>
                    fail((u, e))
                  case (Left(e: EnrollmentError.Database), Left(u: VerifyError.UnknownUser))         =>
                    // this one is handled above and shouldn't happen
                    fail(u)
                }
    } yield ()).either
  }
}

/**
 * Logic:
 *  - if user does not exist, reject
 *  - if user already has one, reject (we only support 1 OTP, to simplify management page, but WRT storage it could be extended to more)
 */
class UserTotpValidator(userRepository: UserRepository, totpRepository: TotpRepository) {
  def validateUserCanCreateTotp(userId: UserId): IOResult[Unit] = {
    for {
      _ <- userRepository.get(userId.value).notOptional(s"User '${userId.value}' is not known, cannot create TOTP")
      _ <- totpRepository.getByUserId(userId).reject {
             case Some(value) =>
               Inconsistency(
                 s"User '${userId.value}' already has a TOTP (created on ${DateFormaterService.getDisplayDate(value.created)}), reset it first to create a new one"
               )
           }
    } yield ()
  }
}
