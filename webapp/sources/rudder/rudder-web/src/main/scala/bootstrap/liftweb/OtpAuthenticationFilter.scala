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
package bootstrap.liftweb

import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.users.*
import com.normation.zio.UnsafeRun
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.*
import org.springframework.security.authentication.*
import org.springframework.security.core.*
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.*
import scala.jdk.CollectionConverters.*
import zio.*

/**
 * Filter for OTP check which is run when the enrollment/verify page submits the verification code
 */
class OtpAuthenticationFilter extends AbstractAuthenticationProcessingFilter("/secure/otp/verify") {

  private val otpService = RudderConfig.otpService

  @throws[AuthenticationException]
  override def attemptAuthentication(req: HttpServletRequest, res: HttpServletResponse): Authentication = {
    val current = SecurityContextHolder.getContext().getAuthentication

    val validAuthorities = RudderAuthType.PreAuthUser.grantedAuthorities.asScala.toSet
    if (
      current == null || !current
        .getAuthorities()
        .stream()
        .anyMatch(a => validAuthorities.contains(a))
    ) {
      throw new InsufficientAuthenticationException("OTP step requires prior password auth");
    }

    (Option(req.getParameter("code")), current.getPrincipal) match {
      case (Some(code), preAuth: RudderPreAuthUser) =>
        val u        = preAuth.authenticatingUser
        val userId   = u.getUsername
        def errorMsg =
          "Could not verify user OTP, please retry with a valid code, generate a new OTP, or ask admin to reset your OTP if you lost it"
        // need to verify a newly generated OTP too
        otpService
          .verifyGenerated(UserId(userId), code)
          .tap(totp => ApplicationLoggerPure.Auth.info(s"User '${userId}' registered OTP at ${totp.created}"))
          .orElse(
            otpService
              .verify(UserId(userId), code)
          )
          .chainError(errorMsg)
          .foldZIO(
            err =>
              // Send only error msg
              ApplicationLoggerPure.Auth
                .debug(err.fullMsg)
                .as(
                  errorResponse(res, HttpStatus.FORBIDDEN, errorMsg)
                ),
            _ => {
              ApplicationLoggerPure.Auth
                .debug(s"User '${userId}' submitted a valid OTP code")
                .as(
                  new UsernamePasswordAuthenticationToken(u, null, RudderAuthType.User.grantedAuthorities)
                )
            }
          )
          .runNow

      case (_, u: RudderUserDetail) =>
        errorResponse(
          res,
          HttpStatus.CONFLICT,
          "User is already authenticated"
        )
      case _                        =>
        errorResponse(
          res,
          HttpStatus.UNAUTHORIZED,
          "Unknown user or code, not able to authenticate OTP"
        )
    }
  }

  private def errorResponse(
      response:   HttpServletResponse,
      httpStatus: HttpStatus,
      message:    String
  ): Authentication = {
    response.setStatus(httpStatus.value())
    val writer = response.getWriter()
    writer.write(message)
    writer.flush()
    // need to return an invalid authentication to avoid stack trace (Spring is sadly happy about this)
    null
  }
}
