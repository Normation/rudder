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
import com.normation.utils.DateFormaterService
import enumeratum.*
import enumeratum.EnumEntry.Lowercase
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
 * This is the one exposed where we need to know if user OTP is defined or not yet
 */
sealed trait TotpUserStatus extends EnumEntry with Lowercase
object TotpUserStatus       extends Enum[TotpUserStatus] with EnumCodec[TotpUserStatus] {
  case object EnrollmentNeeded    extends TotpUserStatus
  // derived from global enforcement level
  case object EnrollmentNotNeeded extends TotpUserStatus
  case object Enrolled            extends TotpUserStatus

  extension (self: TotpUserStatus) {
    def isEnabled: Boolean = self match {
      case Enrolled                               => true
      case EnrollmentNeeded | EnrollmentNotNeeded => false
    }
  }

  // In case of not known enrollment, we can have "not enrolled" if enrollment is optional
  def default(enforcement: TotpEnforcementLevel): TotpUserStatus = enforcement match {
    case TotpEnforcementLevel.Enforced => EnrollmentNeeded
    case TotpEnforcementLevel.Disabled => EnrollmentNotNeeded
  }

  override def values: IndexedSeq[TotpUserStatus] = findValues
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
