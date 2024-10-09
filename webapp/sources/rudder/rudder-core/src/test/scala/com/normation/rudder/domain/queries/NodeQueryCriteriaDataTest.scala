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
package com.normation.rudder.domain.queries

import com.normation.rudder.facts.nodes.IpAddress
import com.normation.rudder.services.policies.NodeConfigData
import com.normation.zio.*
import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.JUnitRunner
import zio.*

@RunWith(classOf[JUnitRunner])
class NodeQueryCriteriaDataTest extends Specification {

  val ips   = Chunk("192.168.10.10", "fe80::5054:ff:fefe:aa71", "127.0.0.1", "::1").map(IpAddress)
  val n     = NodeConfigData.fact1.copy(ipAddresses = ips)
  val empty = NodeConfigData.fact1.copy(ipAddresses = Chunk())

  val ipMatcher = NodeCriterionMatcherIpaddress

  "IPv4 comparator, equality" should {
    "find exact IPv4" in {
      ipMatcher.matches(n, Equals, "192.168.10.10").runNow === true
    }
    "find CIDR for IPv4" in {
      ipMatcher.matches(n, Equals, "192.168.0.0/16").runNow === true
    }
    "not find incorrect IPv4" in {
      ipMatcher.matches(n, Equals, "192.168.0.0").runNow === false
    }
    "not equals exact IPv4" in {
      ipMatcher.matches(n, NotEquals, "192.168.10.10").runNow === false
    }
    "not equals CIDR for IPv4" in {
      ipMatcher.matches(n, NotEquals, "192.168.0.0/16").runNow === false
    }
    "not equals incorrect IPv4" in {
      ipMatcher.matches(n, NotEquals, "192.168.0.0").runNow === true
    }
    "not equals CIDR for IPv4" in {
      ipMatcher.matches(n, NotEquals, "192.168.42.0/24").runNow === true
    }
  }

  "IPv6 comparator, equality" should {
    "find exact IPv6" in {
      ipMatcher.matches(n, Equals, "fe80::5054:ff:fefe:aa71").runNow === true
    }
    "find CIDR for IPv6" in {
      ipMatcher.matches(n, Equals, "fe80::5054:ff:0:0/96").runNow === true
    }
    "not find incorrect IPv6" in {
      ipMatcher.matches(n, Equals, "fe80::5054:ff:0:0").runNow === false
    }
    "not equals exact IPv6" in {
      ipMatcher.matches(n, NotEquals, "fe80::5054:ff:fefe:aa71").runNow === false
    }
    "not equals CIDR for IPv6" in {
      ipMatcher.matches(n, NotEquals, "fe80::5054:ff:0:0/96").runNow === false
    }
    "not equals incorrect IPv6" in {
      ipMatcher.matches(n, NotEquals, "fe80::5054:ff:0:0").runNow === true
    }
    "not equals CIDR for IPv6" in {
      ipMatcher.matches(n, NotEquals, "fe80::5054:11:0:0/96").runNow === true
    }
  }

  "When no IP is defined, equality" should {
    "doesn't equals exact IP" in {
      ipMatcher.matches(empty, Equals, "192.168.10.10").runNow === false and
      ipMatcher.matches(empty, Equals, "fe80::5054:ff:fefe:aa71").runNow === false
    }
    "doesn't equals CIDR" in {
      ipMatcher.matches(empty, Equals, "192.168.0.0/16").runNow === false and
      ipMatcher.matches(empty, Equals, "fe80::5054:ff:0:0/96").runNow === false
    }
  }

  "When no IP is defined, not equals" should {
    "be true for exact IP" in {
      ipMatcher.matches(empty, NotEquals, "192.168.10.10").runNow === true and
      ipMatcher.matches(empty, NotEquals, "fe80::5054:ff:fefe:aa71").runNow === true
    }
    "be true for any CIDR " in {
      ipMatcher.matches(empty, NotEquals, "192.168.0.0/16").runNow === true and
      ipMatcher.matches(empty, NotEquals, "fe80::5054:ff:0:0/96").runNow === true
    }
  }

  "it should revert to string for regex" >> {
    ipMatcher.matches(n, Regex, "192.168.10.[01]{2}").runNow === true
  }
}
