/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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
package com.normation.rudder.api

import io.scalaland.chimney.syntax.*
import java.time.Instant
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.core.Fragments

@RunWith(classOf[JUnitRunner])
class ApiAccountExpirationPolicyTest extends Specification {

  "ApiAccountExpirationPolicy" should {
    val accountExpireDate = Instant.parse("2025-08-12T00:00:00Z")

    "make a system and user API accounts never expire" in {
      (ApiAccountKind.User: ApiAccountKind).transformInto[ApiAccountExpirationPolicy] === ApiAccountExpirationPolicy.NeverExpire
      (ApiAccountKind.System: ApiAccountKind).transformInto[ApiAccountExpirationPolicy] === ApiAccountExpirationPolicy.NeverExpire
    }

    "make a public API account" in {
      "never expire without an expiration date" in {
        (ApiAccountKind.PublicApi.apply(ApiAuthorization.RO, None): ApiAccountKind)
          .transformInto[ApiAccountExpirationPolicy] === ApiAccountExpirationPolicy.NeverExpire
      }
      "expire at a given date" in {
        (ApiAccountKind.PublicApi.apply(ApiAuthorization.RO, Some(accountExpireDate)): ApiAccountKind)
          .transformInto[ApiAccountExpirationPolicy] === ApiAccountExpirationPolicy.ExpireAtDate(accountExpireDate)
      }
    }

    "map the kind of a policy of an API Account" in {
      val accountKinds = List[ApiAccountKind](
        ApiAccountKind.User,
        ApiAccountKind.System,
        ApiAccountKind.PublicApi(ApiAuthorization.RW, ApiAccountExpirationPolicy.NeverExpire),
        ApiAccountKind.PublicApi(ApiAuthorization.RW, ApiAccountExpirationPolicy.ExpireAtDate(accountExpireDate))
      )

      // each transformation to policy should have same kind as direct transformation to kind
      Fragments.foreach(accountKinds) { kind =>
        val expirationKind = kind.transformInto[ApiAccountExpirationPolicyKind]
        s"Account kind '${kind.kind.name}' maps to expiration policy kind '${expirationKind.entryName}'" in {
          kind.transformInto[ApiAccountExpirationPolicy].kind === expirationKind
        }
      }
    }
  }
}
