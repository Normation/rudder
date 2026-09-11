/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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

package bootstrap.liftweb

import bootstrap.liftweb.RudderUrlAuthenticationSuccessHandler.otpRedirectParam
import org.junit.runner.RunWith
import zio.test.*
import zio.test.Assertion.*
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class RudderUrlAuthenticationSuccessHandlerTest extends ZIOSpecDefault {
  def spec = {
    suite("OTP page redirect parameter")(
      test("no saved request means no redirect: the user goes to the home page") {
        assert(otpRedirectParam(None))(isEmptyString)
      },
      test("a saved request is url-encoded into the redirect parameter") {
        assert(otpRedirectParam(Some("https://rudder.example.com/rudder/secure/nodeManager/node?id=root")))(
          equalTo("?redirect=https%3A%2F%2Frudder.example.com%2Frudder%2Fsecure%2FnodeManager%2Fnode%3Fid%3Droot")
        )
      },
      test("the OTP page itself is never used as redirect") {
        assert(otpRedirectParam(Some("https://rudder.example.com/rudder/secure/otp.html")))(isEmptyString)
      }
    )
  }
}
