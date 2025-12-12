/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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

package com.normation.rudder.rest.data

import com.normation.rudder.api.*
import com.normation.rudder.tenants.TenantAccessGrant
import com.normation.utils.DateFormaterService.DateTimeCodecs
import com.normation.zio.*
import io.scalaland.chimney.syntax.*
import java.time.Instant
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import zio.*
import zio.syntax.*

@RunWith(classOf[JUnitRunner])
class ApiAccountTest extends Specification with DateTimeCodecs {

  private val now       = Instant.parse("2025-02-10T10:10:10Z")
  private val accountId = ApiAccountId("account1")
  private val secret    = ApiTokenSecret("token1")

  // test transformer with fixed id generation, fix token id, fixe date now
  private val mapper = new ApiAccountMapping(
    now.succeed,
    accountId.succeed,
    secret.transformInto[ClearTextSecret].succeed,
    s => ApiTokenHash.fromSecret(s.transformInto[ApiTokenSecret]).succeed
  )

  "New account with the minimal of data get a new id and are limited to one month" >> {
    val data = NewRestApiAccount(
      None,
      ApiAccountName("a1"),
      None,
      ApiAccountStatus.Disabled,
      TenantAccessGrant.None,
      None,
      None,
      None,
      None,
      None
    )

    val (account, _) = mapper.fromNewApiAccount(data).runNow

    account.id === accountId
    account.token === AccountToken(Some(ApiTokenHash.fromSecret(secret)), now)
    account.kind match {
      case ApiAccountKind.PublicApi(_, expirationDate) =>
        expirationDate === ApiAccountExpirationPolicy.ExpireAtDate(now).plusOneMonth // default
      case x                                           => ko(s"The account type should not be '${x}'")
    }
    account.isEnabled === false
  }

  "If we fix expiration and account ID, they are fixed" >> {
    val id         = ApiAccountId("defined-id")
    val expiration = now.plus(365.days)

    val data = NewRestApiAccount(
      Some(id),
      ApiAccountName("a2"),
      None,
      ApiAccountStatus.Disabled,
      TenantAccessGrant.None,
      None,
      None,
      Some(expiration),
      None,
      None
    )

    val (account, _) = mapper.fromNewApiAccount(data).runNow

    account.id === id
    account.token === AccountToken(Some(ApiTokenHash.fromSecret(secret)), now)
    account.kind match {
      case ApiAccountKind.PublicApi(_, expirationPolicy) =>
        expirationPolicy === ApiAccountExpirationPolicy.ExpireAtDate(expiration)
      case x                                             => ko(s"The account type should not be '${x}'")
    }
  }
}
