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

  val ips = Chunk("192.168.10.10", "fe80::5054:ff:fefe:aa71", "127.0.0.1", "::1").map(IpAddress)
  val n   = NodeConfigData.fact1.copy(ipAddresses = ips)

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

  "it should revert to string for regex" >> {
    ipMatcher.matches(n, Regex, "192.168.10.[01]{2}").runNow === true
  }
}
