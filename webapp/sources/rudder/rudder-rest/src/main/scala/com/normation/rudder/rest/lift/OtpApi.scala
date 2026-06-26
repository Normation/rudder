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

import com.normation.rudder.api.ApiVersion
import com.normation.rudder.rest.{OtpApi as API, *}
import com.normation.rudder.rest.RudderJsonResponse.syntax.*
import com.normation.rudder.users.*
import net.liftweb.common.*
import net.liftweb.http.*
import zio.*

/**
 * OTP API implementation following the LiftApiModuleProvider pattern.
 */
class OtpApi(
    otpGeneratorService: TotpService
) extends LiftApiModuleProvider[API] {

  def schemas: ApiModuleProvider[API] = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map {
      case API.OtpStatus   => OtpStatus
      case API.OtpGenerate => OtpGenerate
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
}
