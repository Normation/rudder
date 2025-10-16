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

import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.facts.nodes.IpAddress
import com.normation.rudder.services.policies.NodeConfigData
import com.normation.zio.*
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.JUnitRunner
import org.specs2.specification.AfterAll
import org.specs2.specification.BeforeAll
import zio.*

@RunWith(classOf[JUnitRunner])
class NodeQueryCriteriaDataTest extends Specification with AfterAll with BeforeAll {
  sequential

  private val tz = DateTimeZone.getDefault

  private val ips     = Chunk("192.168.10.10", "fe80::5054:ff:fefe:aa71", "127.0.0.1", "::1").map(IpAddress.apply)
  private val dateStr = "2025-10-23T16:35:59Z"
  private val date    = DateTime.parse(dateStr)
  private val n       = NodeConfigData.fact1.copy(ipAddresses = ips, lastInventoryDate = Some(date))

  // we need to set the default time zone, because date parsing is sensitive to timezone ("2025-10-23" is considered local timezone)
  override def beforeAll(): Unit = {
    DateTimeZone.setDefault(DateTimeZone.UTC)
  }
  override def afterAll():  Unit = {
    DateTimeZone.setDefault(tz)
  }

  "IPv4 comparator" should {

    val ipMatcher = NodeCriterionMatcherIpaddress

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

    "IPv6 comparator, equality" in {
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

      val empty = NodeConfigData.fact1.copy(ipAddresses = Chunk())

      "When no IP is defined, equality" in {
        "doesn't equals exact IP" in {
          ipMatcher.matches(empty, Equals, "192.168.10.10").runNow === false and
          ipMatcher.matches(empty, Equals, "fe80::5054:ff:fefe:aa71").runNow === false
        }
        "doesn't equals CIDR" in {
          ipMatcher.matches(empty, Equals, "192.168.0.0/16").runNow === false and
          ipMatcher.matches(empty, Equals, "fe80::5054:ff:0:0/96").runNow === false
        }
      }

      "When no IP is defined, not equals" in {
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
  }

  "Date comparator" should {

    val dateMatcher     = NodeCriterionMatcherDate((n: CoreNodeFact) => Chunk.from(n.lastInventoryDate))
    val dateTimeMatcher = NodeCriterionMatcherDateTime((n: CoreNodeFact) => Chunk.from(n.lastInventoryDate))
    val combined        = NodeCriterionMatcher.combineBy(
      (n: CoreNodeFact) => Chunk.from(n.lastInventoryDate),
      NodeCriterionMatcherDate(_),
      NodeCriterionMatcherDateTime(_)
    )
    val dateOnly        = (d: DateTime) => d.toString("yyyy-MM-dd")
    val dateTime        = (d: DateTime) => d.toString("yyyy-MM-dd'T'HH:mm:ssZ")

    "match with equality" in {
      "on date only" in {
        dateMatcher.matches(n, Equals, dateOnly(date)).runNow === true
        dateMatcher.matches(n, Equals, dateOnly(date.plusDays(2))).runNow === false
      }

      "on datetime" in {
        dateTimeMatcher.matches(n, Equals, dateTime(date)).runNow === true
        dateTimeMatcher.matches(n, Equals, dateTime(date.plusDays(2))).runNow === false
      }

      "on combined" in {
        combined.matches(n, Equals, dateOnly(date)).runNow === true
        combined.matches(n, Equals, dateTime(date)).runNow === true
        combined.matches(n, Equals, dateOnly(date.plusDays(2))).runNow === false
        combined.matches(n, Equals, dateTime(date.plusDays(2))).runNow === false
      }
    }

    "match with inequality" in {
      "on date only" in {
        dateMatcher.matches(n, NotEquals, dateOnly(date)).runNow === false
        dateMatcher.matches(n, NotEquals, dateOnly(date.plusDays(2))).runNow === true
      }

      "on datetime" in {
        dateTimeMatcher.matches(n, NotEquals, dateTime(date)).runNow === false
        dateTimeMatcher.matches(n, NotEquals, dateTime(date.plusDays(2))).runNow === true
      }

      "on combined" in {
        combined.matches(n, NotEquals, dateOnly(date)).runNow === false
        combined.matches(n, NotEquals, dateTime(date)).runNow === false
        combined.matches(n, NotEquals, dateOnly(date.plusDays(2))).runNow === true
        combined.matches(n, NotEquals, dateTime(date.plusDays(2))).runNow === true
      }
    }

    "match with lt" in {
      "on date only" in {
        dateMatcher.matches(n, LesserEq, dateOnly(date.plusDays(2))).runNow === true
        dateMatcher.matches(n, LesserEq, dateOnly(date)).runNow === true
        dateMatcher.matches(n, LesserEq, dateOnly(date.minusDays(2))).runNow === false
      }

      "on datetime" in {
        dateTimeMatcher.matches(n, LesserEq, dateTime(date.plusDays(2))).runNow === true
        dateTimeMatcher.matches(n, LesserEq, dateTime(date)).runNow === true
        dateTimeMatcher.matches(n, LesserEq, dateTime(date.minusDays(2))).runNow === false
      }

      "on combined" in {
        combined.matches(n, LesserEq, dateOnly(date.plusDays(2))).runNow === true
        combined.matches(n, LesserEq, dateTime(date.plusDays(2))).runNow === true
        combined.matches(n, LesserEq, dateOnly(date)).runNow === true
        combined.matches(n, LesserEq, dateTime(date)).runNow === true
        combined.matches(n, LesserEq, dateOnly(date.minusDays(2))).runNow === false
        combined.matches(n, LesserEq, dateTime(date.minusDays(2))).runNow === false
      }
    }
  }
}
