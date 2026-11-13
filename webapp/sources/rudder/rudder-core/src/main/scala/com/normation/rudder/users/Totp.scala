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

import com.normation.errors.*
import com.normation.rudder.domain.logger.ApplicationLoggerPure
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
 * Lattice for known errors that happen to be retried/issued as security errors
 */
enum TotpVerifyError extends RudderError {
  case AlgorithmError(msg: String, userId: UserId)                  extends TotpVerifyError
  case UnknownUser(msg: String, databaseError: Option[RudderError]) extends TotpVerifyError
  case ReplayError(msg: String)                                     extends TotpVerifyError
}

object TotpVerifyError {
  extension (replay:      ReplayError) {
    def userMsg: String = s"${replay.msg}\nIf it was not by you, it might be a security problem and you " +
      s"should contact your Rudder administrator. Otherwise, please retry with the next TOTP code."
  }
  extension (verifyError: AlgorithmError) {
    def systemMsg: String = s"TOTP secret for user '${verifyError.userId.value}' could not be verified: ${verifyError.msg}"
  }

  extension (a: ZIO[Any, TotpVerifyError, Unit]) {
    /*
     * Verification endpoint has two entry-points:
     * - first, attempt to verify the code as a generated code for enrollment
     * - second, if the former failed, attempt to verify it for a known user
     *
     * Resulting failure result in both user-facing message, and emitted system log for ops details
     */
    def handleVerify(b: ZIO[Any, TotpVerifyError, Unit]): ZIO[Any, String, Unit] = for {
      first  <- a.either
      // fail-fast upon database error: there may be a database problem (users table just purged, ...)
      _      <- first match {
                  case Left(UnknownUser(msg, Some(dbError))) =>
                    ApplicationLoggerPure.Auth.error(dbError.fullMsg) *>
                    ZIO.fail(msg)
                  case _                                     => ZIO.unit
                }
      second <- b.either
      _      <- (first, second) match {
                  case (Right(_), _)                                           =>
                    ZIO.unit
                  case (_, Right(_))                                           =>
                    ZIO.unit
                  // second: database error
                  case (_, Left(UnknownUser(msg, Some(dbError))))              =>
                    ApplicationLoggerPure.Auth.error(dbError.fullMsg) *>
                    ZIO.fail(msg)
                  // replay errors deserve specific warning logs
                  case (Left(e: ReplayError), _)                               =>
                    ApplicationLoggerPure.Auth.warn(e.msg) *>
                    ZIO.fail(e.userMsg)
                  case (_, Left(e: ReplayError))                               =>
                    ApplicationLoggerPure.Auth.warn(e.msg) *>
                    ZIO.fail(e.userMsg)
                  // only log failed verification at debug (should rate limit to avoid verbose logs in case of brute force)
                  case (Left(e: AlgorithmError), _)                            =>
                    ApplicationLoggerPure.Auth.debug(e.systemMsg) *>
                    ZIO.fail(e.msg)
                  case (_, Left(e: AlgorithmError))                            =>
                    ApplicationLoggerPure.Auth.debug(e.systemMsg) *>
                    ZIO.fail(e.msg)
                  // fail-fast case above is eliminated, so it's:
                  // "user request verification without even having enrolled a code"
                  // That is a potential security error that needs to be logged
                  case (Left(UnknownUser(msg, _)), Left(UnknownUser(_, None))) =>
                    ApplicationLoggerPure.Auth.warn(
                      s"TOTP verification occurred for unknown user, this may be a security problem: ${msg}."
                    ) *>
                    ZIO.fail(msg)
                }
    } yield ()
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
