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

package com.normation.rudder.rest

import com.normation.rudder.rest.lift.RelayApiHttpClient
import java.io.IOException
import java.net.ConnectException
import java.net.http.HttpConnectTimeoutException
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/*
 * Test the HTTP plumbing used to call the relay API (remote-run). See `RelayApiHttpClient`.
 */
@RunWith(classOf[JUnitRunner])
class TestRelayApiHttpClient extends Specification {

  "form encoding" should {

    "encode the remote-run parameters like a browser form" in {
      RelayApiHttpClient.formUrlEncodedBody(
        ("classes", "cls1,cls2") :: ("keep_output", "true") :: ("asynchronous", "false") :: Nil
      ) === "classes=cls1%2Ccls2&keep_output=true&asynchronous=false"
    }

    "escape everything that isn't safe in a form body" in {
      RelayApiHttpClient.formUrlEncodedBody(("classes", "a b&c=d+e/f") :: Nil) === "classes=a+b%26c%3Dd%2Be%2Ff"
    }

    "give an empty body when there is no parameter" in {
      RelayApiHttpClient.formUrlEncodedBody(Nil) === ""
    }
  }

  /*
   * "the relay is not answering" must be reported with a dedicated message, and the JDK HTTP client
   * can report it either directly or wrapped in an IOException.
   */
  "connection failure detection" should {

    "recognize a direct connection refused" in {
      RelayApiHttpClient.isConnectionFailure(new ConnectException("Connection refused")) === true
    }

    "recognize a connection failure wrapped by the HTTP client" in {
      RelayApiHttpClient.isConnectionFailure(new IOException("wrapped", new ConnectException("Connection refused"))) === true
    }

    "recognize a connection timeout" in {
      RelayApiHttpClient.isConnectionFailure(new HttpConnectTimeoutException("too slow")) === true
    }

    "not mistake another error for a connection failure" in {
      RelayApiHttpClient.isConnectionFailure(new IOException("broken pipe")) === false
    }

    "not loop on a throwable whose cause is itself" in {
      // `initCause(this)` is forbidden by the JDK, but nothing stops an implementation from
      // returning itself from `getCause`, and we must not spin on it
      val loop = new IOException("loop") { override def getCause: Throwable = this }
      RelayApiHttpClient.isConnectionFailure(loop) === false
    }

    "not fail on a null throwable" in {
      RelayApiHttpClient.isConnectionFailure(null) === false
    }
  }
}
