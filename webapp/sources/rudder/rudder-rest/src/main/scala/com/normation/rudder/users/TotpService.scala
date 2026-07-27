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

import cats.syntax.functor.*
import com.bastiaanjansen.otp.*
import com.normation.errors.*
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.users.Totp.*
import com.normation.utils.DateFormaterService
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import zio.*
import zio.json.*
import zio.syntax.*

/**
 * Service which has a state of generated OTP and can verify and register them
 */
trait TotpService {

  def generateUserSecret(userId: UserId): IOResult[TotpSecretData]

  /**
   * Verify the generated one
   */
  def verifyGenerated(
      userId: UserId,
      code:   String
  ): IOResult[Totp]

  /**
   * Reset (delete) the user's TOTP configuration, allowing a new one to be generated.
   */
  def reset(userId: UserId): IOResult[Unit]

  /**
   * Check OTP is globally enforced for all users.
   */
  def getGlobalStatus(): IOResult[Boolean]

  /**
   * Get per-user enrollment status
   */
  def getAllUserStatus(): IOResult[Map[UserId, TotpUserStatus]]

  /**
   * Get user enrollment status, if user does not exist, they need to enroll
   */
  def getUserStatus(userId: UserId): IOResult[TotpUserStatus]

  def verify(userId: UserId, code: String): IOResult[Unit]
}

private given JsonEncoder[TotpSecret] = JsonEncoder[String].contramap(_.exposeSecret())
private given JsonEncoder[URI]        = JsonEncoder[String].contramap(_.toString)

/**
 * Different secret formats to expose
 */
case class TotpSecretData(
    value: TotpSecret,
    uri:   URI
) derives JsonEncoder

case class TotpSecretContainer(
    secret: TotpSecretData
) derives JsonEncoder

trait TotpSecretGenerator {
  def generate: IOResult[TotpSecret]
}

sealed private trait TotpVerificator {
  def verify(secret: TotpSecret, at: Instant, code: String): PureResult[Instant]
}

/**
 * Verifies in-memory secrets that have been created upon "request new OTP" from user interface.
 *
 * These OTP secrets are not stored until they are verified, since they are not verified, and lost on reboot.
 *
 * It could be expired to avoid risking a user having the secret displayed for too long,
 * therefore the use of a Caffeine cache could be more secure.
 */
class InMemoryVerificationTotpService(
    enabledOtp:     Boolean,
    in:             Ref[Map[UserId, TotpSecret]],
    generator:      TotpSecretGenerator,
    validator:      UserTotpValidator,
    totpRepository: TotpRepository,
    verificator:    TotpVerificator
) extends TotpService {
  private val globalLevel = if (enabledOtp) TotpEnforcementLevel.Enforced else TotpEnforcementLevel.Disabled

  override def generateUserSecret(userId: UserId): IOResult[TotpSecretData] = {
    for {
      _      <- validator.validateUserCanCreateTotp(userId)
      secret <- generator.generate
      _      <- in.update(_.updated(userId, secret)) // just forget previously generated secret
      uri    <- totpUri(userId.value, secret)
    } yield {
      TotpSecretData(secret, uri)
    }
  }

  override def verifyGenerated(userId: UserId, code: String): IOResult[Totp] = {
    for {
      map <- in.get
      opt  = map.get(userId)
      s   <- opt.notOptional("User has no known generated TOTP")
      now <- Clock.instant
      _   <- verificator.verify(s, now, code).toIO
      totp = Totp(s, now)
      _   <-
        totpRepository
          .create(userId, totp)
          .chainError("Could not store user TOTP in Rudder")
          .tapError(err => ApplicationLoggerPure.Auth.error(s"User '${userId.value}' TOTP registration failed: ${err.fullMsg}"))
      // on success, user secret can be removed from cache
      _   <- in.update(_ - userId)
    } yield {
      totp
    }
  }

  override def verify(userId: UserId, code: String): IOResult[Unit] = {
    for {
      t   <- totpRepository.getByUserId(userId).map(_.map(_.secret))
      s   <- t.notOptional(s"User '${userId.value}' OTP was not found")
      now <- Clock.instant
      _   <-
        verificator.verify(s, now, code).toIO.chainError(s"TOTP secret for user '${userId.value}' does not match provided code")
    } yield ()
  }

  override def reset(userId: UserId): IOResult[Unit] = {
    for {
      _ <- totpRepository.delete(userId)
      _ <- in.update(_ - userId)
    } yield ()
  }

  override def getGlobalStatus(): IOResult[Boolean] = {
    enabledOtp.succeed
  }

  override def getAllUserStatus(): IOResult[Map[UserId, TotpUserStatus]] = {
    totpRepository
      .getEnabledUsers()
      .map(_.map(u => u -> TotpUserStatus.Enrolled).toMap)
  }

  override def getUserStatus(userId: UserId): IOResult[TotpUserStatus] = {
    totpRepository
      .getByUserId(userId)
      .map(_.as(TotpUserStatus.Enrolled).getOrElse(TotpUserStatus.default(globalLevel)))
  }
}

private def totpUri(userId: String, secret: TotpSecret): IOResult[URI] = {
  val query = s"secret=${secret.exposeSecret()}&issuer=Rudder"
  // this URI constructor handles encoding
  IOResult.attempt(URI("otpauth", "totp", s"/Rudder:${userId}", query, null))
}

object OtpJavaTotpService {

  /** Default TOTP period: 30 seconds */
  private val DEFAULT_PERIOD: Duration = 30.seconds

  /** Verification delay window: accept codes valid for 1 previous/next window (±30s tolerance) */
  private val DEFAULT_DELAY_WINDOW: Int = 1

  /** Generates a cryptographically secure base32-encoded TOTP secret using otp-java */
  private class OtpJavaSecretGenerator extends TotpSecretGenerator {
    override def generate: IOResult[TotpSecret] = {
      val secret = SecretGenerator.generate()
      TotpSecret(new String(secret)).succeed
    }
  }

  /** Verifies TOTP codes using otp-java TOTPGenerator with configurable delay window */
  private class OtpJavaTotpVerificator extends TotpVerificator {
    override def verify(secret: TotpSecret, at: Instant, code: String): PureResult[Instant] = {
      val totp = TOTPGenerator
        .Builder(secret.exposeSecret())
        .withClock(java.time.Clock.fixed(at, ZoneId.systemDefault()))
        .withPeriod(DEFAULT_PERIOD)
        .build

      val valid = totp.verify(code, DEFAULT_DELAY_WINDOW)

      if (valid) Right(at)
      else {
        Left(
          Inconsistency(
            s"Invalid TOTP code submitted at ${DateFormaterService.getDisplayDate(at)}, code is wrong or may be too old"
          )
        )
      }
    }
  }

  def make(
      enabledOtp:     Boolean,
      validator:      UserTotpValidator,
      totpRepository: TotpRepository
  ): UIO[InMemoryVerificationTotpService] = {
    Ref
      .make(Map.empty[UserId, TotpSecret])
      .map(
        InMemoryVerificationTotpService(
          enabledOtp,
          _,
          OtpJavaSecretGenerator(),
          validator,
          totpRepository,
          OtpJavaTotpVerificator()
        )
      )
  }
}
