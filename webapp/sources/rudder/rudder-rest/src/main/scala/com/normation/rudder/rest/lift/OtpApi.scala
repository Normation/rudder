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

package com.normation.rudder.rest.lift

import com.normation.errors.*
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.rest.{OtpApi => API, *}
import com.normation.rudder.rest.RudderJsonRequest.*
import com.normation.rudder.rest.RudderJsonResponse.syntax.*
import com.normation.rudder.users.*
import com.normation.rudder.users.TotpStatus
import net.liftweb.common.*
import net.liftweb.http.*
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import zio.*

/**
 * OTP API implementation following the LiftApiModuleProvider pattern.
 */
class OtpApi(
    otpGeneratorService: TotpGeneratorService & TotpVerificationService
) extends LiftApiModuleProvider[API] {

  def schemas: ApiModuleProvider[API] = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map {
      case API.OtpStatus   => OtpStatus
      case API.OtpGenerate => OtpGenerate
      case API.OtpVerify   => OtpVerify
      case API.OtpReset    => OtpReset
    }
  }

  object OtpStatus extends LiftApiModule0 {
    val schema: API.OtpStatus.type = API.OtpStatus

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      val userId         = authzToken.user.name
      val needGeneration = otpGeneratorService.needGeneration(userId)
      val status         = needGeneration.map(n => TotpStatus(n))
      status.toLiftResponseOne(params, schema, None)
    }
  }

  object OtpGenerate extends LiftApiModule0 {
    val schema: API.OtpGenerate.type = API.OtpGenerate

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      val userId = authzToken.user.name
      (for {
        secret <- otpGeneratorService.generateUserSecret(userId)
        res     = TotpSecretContainer(secret)
      } yield {
        res
      }).toLiftResponseOne(params, schema, None)
    }
  }

  object OtpVerify extends LiftApiModule0 {
    val schema: API.OtpVerify.type = API.OtpVerify

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      val securityContextRepository = HttpSessionSecurityContextRepository()

      val ctx = securityContextRepository.loadDeferredContext(
        req.request.asInstanceOf[net.liftweb.http.provider.servlet.HTTPRequestServlet].req
      )

      val userId = authzToken.user.name

      (for {
        code <- req.fromJson[String].toIO
        _    <-
          otpGeneratorService
            .verifyGenerated(userId, code)
            .tap(totp => ApplicationLoggerPure.Auth.info(s"User '${userId}' registered TOTP at ${totp.created}"))
            .orElse(
              otpGeneratorService.verify(userId, code) *>
              ApplicationLoggerPure.Auth.debug(s"User '${userId}' submitted a valid TOTP code")
            )
            .chainError(
              "Could not verify user OTP, pleasy retry with a valid code, generate a new OTP, or ask admin to reset your OTP if you lost it"
            )

        // FIXME: Weird place to change auth, but it's actually valid since it's called from logged in user
        _     = {
          val springSecurityContextKey = HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY
          val user                     = ctx.get().getAuthentication
          if (user.getAuthorities.stream.anyMatch(_.getAuthority.equals("ROLE_PRE_AUTH"))) {
            // put back the user role, see pendant in AppConfigAuth
            val fullyAuthenticated =
              new UsernamePasswordAuthenticationToken(user, null, java.util.List.of(new SimpleGrantedAuthority("ROLE_USER")))
            ctx.get().setAuthentication(fullyAuthenticated)
            // TODO: this doesn't seem to work at all, but doesn't give a Jetty 403 error at least
            // we may need to create a dedicated Spring filter for this request
            S.session.flatMap(_.httpSession).foreach(_.setAttribute(springSecurityContextKey, ctx))
          }
        }
      } yield {
        "ok"
      }).toLiftResponseOne(params, schema, None)
    }
  }

  object OtpReset extends LiftApiModule0 {
    val schema: API.OtpReset.type = API.OtpReset

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      ???
    }
  }
}
