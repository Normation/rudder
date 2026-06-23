package com.normation.rudder.users

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
trait TotpGeneratorService {

  /**
   * Check if the user needs to enroll (generate) a new OTP secret.
   * Returns true if the user does not yet have an OTP configured.
   */
  def needGeneration(userId: String): IOResult[Boolean]

  def generateUserSecret(userId: String): IOResult[TotpSecretData]

  /**
   * Verify the generated one
   */
  def verifyGenerated(
      userId: String,
      code:   String
  ): IOResult[Totp]

  /**
   * Reset (delete) the user's TOTP configuration, allowing a new one to be generated.
   */
  def reset(userId: String): IOResult[Unit]

}

//TODO: need to be split: the splitted services may not be the right separation
type TotpService = TotpGeneratorService & TotpVerificationService

trait TotpVerificationService {
  def verify(userId: String, code: String): IOResult[Unit]
}

/**
 * Status of OTP enrollment for a user
 */
case class TotpStatus(
    needEnrollment: Boolean
) derives JsonEncoder

private given JsonEncoder[TotpSecret] = JsonEncoder[String].contramap(_.value)
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

/**
 * Logic:
 *  - if user does not exist, reject
 *  - if user already has one, reject (we only support 1 OTP, to simplify management page, but WRT storage it could be extended to more)
 */
class UserTotpValidator(userRepository: UserRepository, totpRepository: TotpRepository) {
  def validateUserCanCreateTotp(userId: String): IOResult[Unit] = {
    for {
      _ <- userRepository.get(userId).notOptional(s"User '${userId}' is not known, cannot create OTP")
      _ <- totpRepository.getByUserId(userId).reject {
             case Some(value) =>
               Inconsistency(
                 s"User '${userId}' already has an OTP (created on ${DateFormaterService.getDisplayDate(value.created)}), reset it first to create a new one"
               )
           }
    } yield ()
  }
}

sealed private trait TotpVerificator {
  def verify(secret: TotpSecret, at: Instant, code: String): PureResult[Instant]
}

/**
 * Verifies in-memory secrets, that have been created upon "request new OTP" from user interface.
 *
 * These OTP secret are not stored until they are verified, since they are not verified, and lost on reboot.
 *
 * It could be expired to avoid risking a user having the secret displayed for too long,
 * therefore the use of a Caffeine cache could be more secure.
 */
abstract class InMemoryVerificationTotpService(
    in:             Ref[Map[String, TotpSecret]],
    generator:      TotpSecretGenerator,
    validator:      UserTotpValidator,
    totpRepository: TotpRepository,
    verificator:    TotpVerificator
) extends TotpGeneratorService with TotpVerificationService {
  override def needGeneration(userId: String): IOResult[Boolean] = {
    totpRepository.getByUserId(userId).map(_.isEmpty)
  }

  override def generateUserSecret(userId: String): IOResult[TotpSecretData] = {
    for {
      _      <- validator.validateUserCanCreateTotp(userId)
      secret <- generator.generate
      _      <- in.update(_.updated(userId, secret)) // just forget previously generated secret
      uri    <- totpUri(userId, secret)
    } yield {
      TotpSecretData(secret, uri)
    }
  }

  override def verifyGenerated(userId: String, code: String): IOResult[Totp] = {
    for {
      map <- in.get
      opt  = map.get(userId)
      s   <- opt.notOptional("User has no known generated OTP")
      now <- Clock.instant
      _   <- verificator.verify(s, now, code).toIO.chainError(s"OTP secret for user '${userId}' does not match provided code")
      totp = Totp(s, now)
      _   <- totpRepository
               .create(userId, totp)
               .chainError("Could not store user OTP in Rudder")
               .tapError(err => ApplicationLoggerPure.Auth.error(s"User '${userId}' TOTP registration failed: ${err.fullMsg}"))
      // on success, user secret can be removed from cache
      _   <- in.update(_ - userId)
    } yield {
      totp
    }
  }

  override def verify(userId: String, code: String): IOResult[Unit] = {
    for {
      t   <- totpRepository.getByUserId(userId).map(_.map(_.secret))
      s   <- t.notOptional(s"User '${userId}' OTP was not found")
      now <- Clock.instant
      _   <- verificator.verify(s, now, code).toIO.chainError(s"OTP secret for user '${userId}' does not match provided code")
    } yield ()
  }

  override def reset(userId: String): IOResult[Unit] = {
    for {
      _ <- totpRepository.delete(userId)
      _ <- in.update(_ - userId)
    } yield ()
  }
}

private def totpUri(userId: String, secret: TotpSecret): IOResult[URI] = {
  val query = s"secret=${secret.value}&issuer=Rudder"
  // this URI constructor handles encoding
  IOResult.attempt(URI("otpauth", "totp", s"/Rudder:${userId}", query, null))
}

// otp-java based TOTP service implementation
class OtpJavaTotpService(
    in:             Ref[Map[String, TotpSecret]],
    generator:      TotpSecretGenerator,
    validator:      UserTotpValidator,
    totpRepository: TotpRepository,
    verificator:    TotpVerificator
) extends InMemoryVerificationTotpService(in, generator, validator, totpRepository, verificator)

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
        .Builder(secret.value)
        .withClock(java.time.Clock.fixed(at, ZoneId.systemDefault()))
        .withPeriod(DEFAULT_PERIOD)
        .build

      val valid = totp.verify(code, DEFAULT_DELAY_WINDOW)

      if (valid) Right(at)
      else Left(Inconsistency(s"Invalid TOTP code at ${at}, code is wrong or may be too old"))
    }
  }

  def make(
      validator:      UserTotpValidator,
      totpRepository: TotpRepository
  ): UIO[OtpJavaTotpService] = {
    Ref
      .make(Map.empty[String, TotpSecret])
      .map(
        OtpJavaTotpService(
          _,
          OtpJavaSecretGenerator(),
          validator,
          totpRepository,
          OtpJavaTotpVerificator()
        )
      )
  }
}
