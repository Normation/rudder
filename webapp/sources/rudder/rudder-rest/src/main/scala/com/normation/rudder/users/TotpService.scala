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
  def getGlobalStatus(): IOResult[TotpEnforcementLevel]

  /**
   * Get per-user enrollment status for provided users
   */
  def getAllTotpUser(users: List[UserInfo]): IOResult[Map[UserId, TotpUser]]

  /**
   * Get user enrollment status, if user does not exist, they need to enroll
   */
  def getUserStatus(userId: UserId): IOResult[TotpEnrollmentStatus]

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
object TotpSecretData {
  def make(userId: UserId, secret: TotpSecret): IOResult[TotpSecretData] = {
    val uri: IOResult[URI] = {
      val query = s"secret=${secret.exposeSecret()}&issuer=Rudder"
      // this URI constructor handles encoding
      IOResult.attempt(URI("otpauth", "totp", s"/Rudder:${userId.value}", query, null))
    }

    uri.map(TotpSecretData(secret, _))
  }
}

case class TotpSecretContainer(
    secret: TotpSecretData
) derives JsonEncoder

trait TotpSecretGenerator {
  def generate: IOResult[TotpSecret]
}

/**
 * Verifies secrets with OTP crypto implementation with parameters.
 *
 * It does additional mandatory verification too for replay resistance,
 * as per the spec: [[https://www.rfc-editor.org/rfc/rfc6238#section-5.2]].
 * It is implemented in-memory, provided that period and window are small and
 * code validity expires before the duration of a webapp restart.
 */
trait TotpVerificator(val period: Duration, val delayWindow: Int)(
    usedCodes: Ref[Map[UserId, Chunk[(String, Instant)]]]
) {
  // a code is accepted for the previous, current and next steps
  private val codeLifetime: Duration = period.multipliedBy(2L * delayWindow + 1)

  def verify(secret: TotpSecret, userId: UserId, at: Instant, code: String): IOResult[Unit] = {
    for {
      _        <- verifyImpl(secret, at, code).toIO
      replayed <- usedCodes.modify { m =>
                    val used = m.getOrElse(userId, Chunk.empty).filter((_, end) => at.isBefore(end))
                    if (used.exists((c, _) => c == code)) {
                      (true, m.updated(userId, used))
                    } else {
                      (false, m.updated(userId, used.appended(code, at.plus(codeLifetime))))
                    }
                  }
      _        <- ZIO.when(replayed) {
                    ApplicationLoggerPure.Auth.warn(s"User '${userId.value}' submitted an already used TOTP code") *>
                    Inconsistency(
                      s"Invalid TOTP code submitted at ${DateFormaterService.getDisplayDate(at)}, retry with another code"
                    ).fail
                  }
    } yield ()
  }

  protected def verifyImpl(secret: TotpSecret, at: Instant, code: String): PureResult[Instant]
}

/**
 * Verifies in-memory secrets that have been created upon "request new OTP" from user interface.
 * Generated OTP secrets are not stored in repository until they are verified:
 * - they are lost on reboot
 * - newly generated code for a user replaces previously generated one
 *
 * Generated ones could be expired to avoid risking a user having the secret displayed for too long.
 * It could also be used to rate-limit failures.
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
      res    <- TotpSecretData.make(userId, secret)
    } yield {
      res
    }
  }

  override def verifyGenerated(userId: UserId, code: String): IOResult[Totp] = {
    for {
      map <- in.get
      opt  = map.get(userId)
      s   <- opt.notOptional("User has no known generated TOTP")
      now <- Clock.instant
      _   <- verificator.verify(s, userId, now, code)
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
        verificator
          .verify(s, userId, now, code)
          .chainError(s"TOTP secret for user '${userId.value}' does not match provided code")
    } yield ()
  }

  override def reset(userId: UserId): IOResult[Unit] = {
    for {
      _ <- totpRepository.delete(userId)
      _ <- in.update(_ - userId)
    } yield ()
  }

  override def getGlobalStatus(): IOResult[TotpEnforcementLevel] = {
    if (enabledOtp) TotpEnforcementLevel.Enforced.succeed
    else TotpEnforcementLevel.Disabled.succeed
  }

  override def getAllTotpUser(users: List[UserInfo]): IOResult[Map[UserId, TotpUser]] = {
    totpRepository
      .getEnabledUsers()
      .map(enabledUsers => {
        users
          .flatMap(u => {
            val userId = UserId(u.id)
            if (enabledUsers.contains(userId)) Some((userId, TotpUser(u, TotpEnrollmentStatus.enrolled)))
            else None
          })
          .toMap
      })
  }

  override def getUserStatus(userId: UserId): IOResult[TotpEnrollmentStatus] = {
    totpRepository
      .getByUserId(userId)
      .map(_.as(TotpEnrollmentStatus.enrolled).getOrElse(TotpEnrollmentStatus.default(globalLevel)))
  }
}

object OtpJavaTotpService {

  /** Generates a cryptographically secure base32-encoded TOTP secret using otp-java */
  private class OtpJavaSecretGenerator extends TotpSecretGenerator {
    override def generate: IOResult[TotpSecret] = {
      val secret = SecretGenerator.generate()
      TotpSecret(new String(secret)).succeed
    }
  }

  /** Verifies TOTP codes using otp-java TOTPGenerator with configurable delay window */
  abstract private[users] class OtpJavaTotpVerificator(
      period:      zio.Duration,
      delayWindow: Int
  )(usedCodes: Ref[Map[UserId, Chunk[(String, Instant)]]])
      extends TotpVerificator(period, delayWindow)(usedCodes) {
    protected def verifyImpl(
        secret: TotpSecret,
        at:     Instant,
        code:   String
    ): PureResult[Instant] = {
      val totp = TOTPGenerator
        .Builder(secret.exposeSecret())
        .withClock(java.time.Clock.fixed(at, ZoneId.systemDefault()))
        .withPeriod(period)
        .build

      val valid = totp.verify(code, delayWindow)

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

  private[users] object OtpJavaTotpVerificator {
    def make(
        /** Default TOTP period: 30 seconds */
        period:      Duration = 30.seconds,
        /** Verification delay window: accept codes valid for 1 previous/next window period */
        delayWindow: Int = 1
    ): UIO[OtpJavaTotpVerificator] =
      Ref.make(Map.empty[UserId, Chunk[(String, Instant)]]).map(c => new OtpJavaTotpVerificator(period, delayWindow)(c) {})
  }

  def make(
      enabledOtp:     Boolean,
      validator:      UserTotpValidator,
      totpRepository: TotpRepository
  ): UIO[InMemoryVerificationTotpService] = {
    for {
      generatedSecrets <- Ref.make(Map.empty[UserId, TotpSecret])
      verificator      <- OtpJavaTotpVerificator.make()
    } yield {
      InMemoryVerificationTotpService(
        enabledOtp,
        generatedSecrets,
        OtpJavaSecretGenerator(),
        validator,
        totpRepository,
        verificator
      )
    }
  }
}
