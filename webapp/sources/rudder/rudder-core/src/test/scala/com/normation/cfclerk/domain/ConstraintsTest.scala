/*
 *************************************************************************************
* Copyright 2016 Normation SAS
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

package com.normation.cfclerk.domain

import com.normation.errors.PureResult
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import org.specs2.matcher.Matcher
import org.specs2.matcher.Expectable


@RunWith(classOf[JUnitRunner])
class ConstraintsTest extends Specification {

  import IP._

  "ipv6 validation" should {

    "matches when it's an actual ipv6" in {
      validIPV6 must contain((s: String) => Ipv6Regex.check(s, s) must beFull ).foreach
    }

    "reject when it's a faulty ipv6" in {
      (invalidIPV6 ::: validIPV4) must contain((s: String) => Ipv6Regex.check(s, s) must beFailed ).foreach
    }
  }

  "ipv6+ipv4 validation" should {

    "matches when it's an actual ipv6" in {
      (validIPV4 ::: validIPV6) must contain((s: String) => IpRegex.check(s, s) must beFull ).foreach
    }

    "reject when it's a faulty ipv6" in {
      (invalidIPV4 ::: invalidIPV6) must contain((s: String) => IpRegex.check(s, s) must beFailed ).foreach
    }
  }


  "ipv4 validation" should {

    "matches when it's an actual ipv4" in {
      validIPV4 must contain((s: String) => Ipv4Regex.check(s, s) must beFull ).foreach
    }

    "reject when it's a faulty ipv4" in {
      invalidIPV4 must contain((s: String) => Ipv4Regex.check(s, s) must beFailed ).foreach
    }
  }

  //////// utility matcher ////////

  class BeFullMatcher[T] extends Matcher[PureResult[T]] {
    def apply[S <: PureResult[T]](v: Expectable[S]) = {
      result(v.value match {
        case Right(_) => true
        case _ => false
      }, v.description + " is true", v.description + " is false", v)
    }
  }
  class BeFailedMatcher[T] extends Matcher[PureResult[T]] {
    def apply[S <: PureResult[T]](v: Expectable[S]) = {
      result(v.value match {
        case Left( _) => true
        case _ => false
      }, v.description + " is true", v.description + " is false", v)
    }
  }

  def beFailed[T] = new BeFailedMatcher[T]
  def beFull[T]   = new BeFullMatcher[T]
}


object IP { //example taken from http://static.helpsystems.com/intermapper/third-party/test-ipv6-regex.pl

  val validIPV4 =
    """1.2.3.4""" ::
    """255.255.255.255""" ::
    Nil

  val invalidIPV4 =
    """2.3.4""" ::
    """256.255.255.255""" ::
    Nil

  val validIPV6 =
    """::1""" ::  // loopback, compressed, non-routable
    """0:0:0:0:0:0:0:1""" ::  // loopback, full
    """0:0:0:0:0:0:0:0""" ::  // unspecified, full
    """2001:DB8:0:0:8:800:200C:417A""" ::  // unicast, full
    """FF01:0:0:0:0:0:0:101""" ::  // multicast, full
    """2001:DB8::8:800:200C:417A""" ::  // unicast, compressed
    """FF01::101""" ::  // multicast, compressed
    """fe80::217:f2ff:fe07:ed62""" ::
    """2001:0000:1234:0000:0000:C1C0:ABCD:0876""" ::
    """3ffe:0b00:0000:0000:0001:0000:0000:000a""" ::
    """FF02:0000:0000:0000:0000:0000:0000:0001""" ::
    """0000:0000:0000:0000:0000:0000:0000:0001""" ::
    """0000:0000:0000:0000:0000:0000:0000:0000""" ::
    """2::10""" ::
    """ff02::1""" ::
    """fe80::""" ::
    """2002::""" ::
    """2001:db8::""" ::
    """2001:0db8:1234::""" ::
    """::ffff:0:0""" ::
    """::1""" ::
    """1:2:3:4:5:6:7:8""" ::
    """1:2:3:4:5:6::8""" ::
    """1:2:3:4:5::8""" ::
    """1:2:3:4::8""" ::
    """1:2:3::8""" ::
    """1:2::8""" ::
    """1::8""" ::
    """1::2:3:4:5:6:7""" ::
    """1::2:3:4:5:6""" ::
    """1::2:3:4:5""" ::
    """1::2:3:4""" ::
    """1::2:3""" ::
    """1::8""" ::
    """::2:3:4:5:6:7:8""" ::
    """::2:3:4:5:6:7""" ::
    """::2:3:4:5:6""" ::
    """::2:3:4:5""" ::
    """::2:3:4""" ::
    """::2:3""" ::
    """::8""" ::
    """1:2:3:4:5:6::""" ::
    """1:2:3:4:5::""" ::
    """1:2:3:4::""" ::
    """1:2:3::""" ::
    """1:2::""" ::
    """1::""" ::
    """1:2:3:4:5::7:8""" ::
    """1:2:3:4::7:8""" ::
    """1:2:3::7:8""" ::
    """1:2::7:8""" ::
    """1::7:8""" ::
    """1:2:3:4:5:6:1.2.3.4""" ::
    """1:2:3:4:5::1.2.3.4""" ::
    """1:2:3:4::1.2.3.4""" ::
    """1:2:3::1.2.3.4""" ::
    """1:2::1.2.3.4""" ::
    """1::1.2.3.4""" ::
// these one are not supported by the regexp
//  the pattern is N.::..N.ipv4 (ie "::" in the middle + ipv4)
//    """1:2:3:4::5:1.2.3.4""" ::
//    """1:2:3::5:1.2.3.4""" ::
//    """1:2::5:1.2.3.4""" ::
//    """1::5:1.2.3.4""" ::
//    """1::5:11.22.33.44""" ::
//    """fe80::217:f2ff:254.7.237.98""" ::
    """::ffff:192.168.1.26""" ::
    """::ffff:192.168.1.1""" ::
    """0:0:0:0:0:0:13.1.68.3""" ::  // IPv4-compatible IPv6 address, full, deprecated
    """0:0:0:0:0:FFFF:129.144.52.38""" ::  // IPv4-mapped IPv6 address, full
    """::13.1.68.3""" ::  // IPv4-compatible IPv6 address, compressed, deprecated
    """::FFFF:129.144.52.38""" ::  // IPv4-mapped IPv6 address, compressed
    """fe80:0:0:0:204:61ff:254.157.241.86""" ::
//    """fe80::204:61ff:254.157.241.86""" ::
    """::ffff:12.34.56.78""" ::
    """::ffff:192.0.2.128""" ::     // but this is OK, since there's a single digit
    """fe80:0000:0000:0000:0204:61ff:fe9d:f156""" ::
    """fe80:0:0:0:204:61ff:fe9d:f156""" ::
    """fe80::204:61ff:fe9d:f156""" ::
    """::1""" ::
    """fe80::""" ::
    """fe80::1""" ::
    """::ffff:c000:280""" ::
    """2001:0db8:85a3:0000:0000:8a2e:0370:7334""" ::
    """2001:db8:85a3:0:0:8a2e:370:7334""" ::
    """2001:db8:85a3::8a2e:370:7334""" ::
    """2001:0db8:0000:0000:0000:0000:1428:57ab""" ::
    """2001:0db8:0000:0000:0000::1428:57ab""" ::
    """2001:0db8:0:0:0:0:1428:57ab""" ::
    """2001:0db8:0:0::1428:57ab""" ::
    """2001:0db8::1428:57ab""" ::
    """2001:db8::1428:57ab""" ::
    """0000:0000:0000:0000:0000:0000:0000:0001""" ::
    """::1""" ::
    """::ffff:0c22:384e""" ::
    """2001:0db8:1234:0000:0000:0000:0000:0000""" ::
    """2001:0db8:1234:ffff:ffff:ffff:ffff:ffff""" ::
    """2001:db8:a::123""" ::
    """fe80::""" ::
    """1111:2222:3333:4444:5555:6666:7777:8888""" ::
    """1111:2222:3333:4444:5555:6666:7777::""" ::
    """1111:2222:3333:4444:5555:6666::""" ::
    """1111:2222:3333:4444:5555::""" ::
    """1111:2222:3333:4444::""" ::
    """1111:2222:3333::""" ::
    """1111:2222::""" ::
    """1111::""" ::
    """1111:2222:3333:4444:5555:6666::8888""" ::
    """1111:2222:3333:4444:5555::8888""" ::
    """1111:2222:3333:4444::8888""" ::
    """1111:2222:3333::8888""" ::
    """1111:2222::8888""" ::
    """1111::8888""" ::
    """::8888""" ::
    """1111:2222:3333:4444:5555::7777:8888""" ::
    """1111:2222:3333:4444::7777:8888""" ::
    """1111:2222:3333::7777:8888""" ::
    """1111:2222::7777:8888""" ::
    """1111::7777:8888""" ::
    """::7777:8888""" ::
    """1111:2222:3333:4444::6666:7777:8888""" ::
    """1111:2222:3333::6666:7777:8888""" ::
    """1111:2222::6666:7777:8888""" ::
    """1111::6666:7777:8888""" ::
    """::6666:7777:8888""" ::
    """1111:2222:3333::5555:6666:7777:8888""" ::
    """1111:2222::5555:6666:7777:8888""" ::
    """1111::5555:6666:7777:8888""" ::
    """::5555:6666:7777:8888""" ::
    """1111:2222::4444:5555:6666:7777:8888""" ::
    """1111::4444:5555:6666:7777:8888""" ::
    """::4444:5555:6666:7777:8888""" ::
    """1111::3333:4444:5555:6666:7777:8888""" ::
    """::3333:4444:5555:6666:7777:8888""" ::
    """::2222:3333:4444:5555:6666:7777:8888""" ::
//    """1111:2222:3333:4444:5555:6666:123.123.123.123""" ::
    """1111:2222:3333:4444:5555::123.123.123.123""" ::
    """1111:2222:3333:4444::123.123.123.123""" ::
    """1111:2222:3333::123.123.123.123""" ::
    """1111:2222::123.123.123.123""" ::
    """1111::123.123.123.123""" ::
    """::123.123.123.123""" ::
//    """1111:2222:3333:4444::6666:123.123.123.123""" ::
//    """1111:2222:3333::6666:123.123.123.123""" ::
//    """1111:2222::6666:123.123.123.123""" ::
//    """1111::6666:123.123.123.123""" ::
    """::6666:123.123.123.123""" ::
//    """1111:2222:3333::5555:6666:123.123.123.123""" ::
//    """1111:2222::5555:6666:123.123.123.123""" ::
//    """1111::5555:6666:123.123.123.123""" ::
    """::5555:6666:123.123.123.123""" ::
//    """1111:2222::4444:5555:6666:123.123.123.123""" ::
//    """1111::4444:5555:6666:123.123.123.123""" ::
    """::4444:5555:6666:123.123.123.123""" ::
//    """1111::3333:4444:5555:6666:123.123.123.123""" ::
    """::2222:3333:4444:5555:6666:123.123.123.123""" ::
    """::0:0:0:0:0:0:0""" ::
    """::0:0:0:0:0:0""" ::
    """::0:0:0:0:0""" ::
    """::0:0:0:0""" ::
    """::0:0:0""" ::
    """::0:0""" ::
    """::0""" ::
    """0:0:0:0:0:0:0::""" ::
    """0:0:0:0:0:0::""" ::
    """0:0:0:0:0::""" ::
    """0:0:0:0::""" ::
    """0:0:0::""" ::
    """0:0::""" ::
    """0::""" ::
    """::0:a:b:c:d:e:f""" ::   // syntactically correct, but bad form (::0:... could be combined
    """a:b:c:d:e:f:0::""" ::
//    """':10.0.0.1""" ::
    Nil

  val invalidIPV6 =
    """2001:DB8:0:0:8:800:200C:417A:221""" ::  // unicast, full
    """FF01::101::2""" ::  // multicast, compressed
    """02001:0000:1234:0000:0000:C1C0:ABCD:0876""" ::  // extra 0 not allowed!
    """2001:0000:1234:0000:00001:C1C0:ABCD:0876""" ::  // extra 0 not allowed!
    """2001:0000:1234:0000:0000:C1C0:ABCD:0876  0""" ::  // junk after valid address
    """2001:0000:1234: 0000:0000:C1C0:ABCD:0876""" ::  // internal space
    """3ffe:0b00:0000:0001:0000:0000:000a""" ::   // seven segments
    """FF02:0000:0000:0000:0000:0000:0000:0000:0001""" ::  // nine segments
    """3ffe:b00::1::a""" ::  // double "::"
    """::1111:2222:3333:4444:5555:6666::""" ::  // double "::"
    """1:2:3::4:5::7:8""" ::  // Double "::"
    """12345::6:7:8""" ::
    """1::5:400.2.3.4""" ::
    """1::5:260.2.3.4""" ::
    """1::5:256.2.3.4""" ::
    """1::5:1.256.3.4""" ::
    """1::5:1.2.256.4""" ::
    """1::5:1.2.3.256""" ::
    """1::5:300.2.3.4""" ::
    """1::5:1.300.3.4""" ::
    """1::5:1.2.300.4""" ::
    """1::5:1.2.3.300""" ::
    """1::5:900.2.3.4""" ::
    """1::5:1.900.3.4""" ::
    """1::5:1.2.900.4""" ::
    """1::5:1.2.3.900""" ::
    """1::5:300.300.300.300""" ::
    """1::5:3000.30.30.30""" ::
    """1::400.2.3.4""" ::
    """1::260.2.3.4""" ::
    """1::256.2.3.4""" ::
    """1::1.256.3.4""" ::
    """1::1.2.256.4""" ::
    """1::1.2.3.256""" ::
    """1::300.2.3.4""" ::
    """1::1.300.3.4""" ::
    """1::1.2.300.4""" ::
    """1::1.2.3.300""" ::
    """1::900.2.3.4""" ::
    """1::1.900.3.4""" ::
    """1::1.2.900.4""" ::
    """1::1.2.3.900""" ::
    """1::300.300.300.300""" ::
    """1::3000.30.30.30""" ::
    """::400.2.3.4""" ::
    """::260.2.3.4""" ::
    """::256.2.3.4""" ::
    """::1.256.3.4""" ::
    """::1.2.256.4""" ::
    """::1.2.3.256""" ::
    """::300.2.3.4""" ::
    """::1.300.3.4""" ::
    """::1.2.300.4""" ::
    """::1.2.3.300""" ::
    """::900.2.3.4""" ::
    """::1.900.3.4""" ::
    """::1.2.900.4""" ::
    """::1.2.3.900""" ::
    """::300.300.300.300""" ::
    """::3000.30.30.30""" ::
    """2001:1:1:1:1:1:255Z255X255Y255""" ::  // garbage instead of ""."" in IPv4"
    """::ffff:192x168.1.26""" ::  // ditto"
    """::ffff:2.3.4""" ::
    """::ffff:257.1.2.3""" ::
    """1.2.3.4:1111:2222:3333:4444::5555""" ::    // Aeron
    """1.2.3.4:1111:2222:3333::5555""" ::
    """1.2.3.4:1111:2222::5555""" ::
    """1.2.3.4:1111::5555""" ::
    """1.2.3.4::5555""" ::
    """1.2.3.4::""" ::
    """fe80:0000:0000:0000:0204:61ff:254.157.241.086""" ::
    """XXXX:XXXX:XXXX:XXXX:XXXX:XXXX:1.2.3.4""" ::
//    """1111:2222:3333:4444:5555:6666:00.00.00.00""" ::  acceptd but should not... Not to grave
    """1111:2222:3333:4444:5555:6666:000.000.000.000""" ::
    """1111:2222:3333:4444:5555:6666:256.256.256.256""" ::
    """:""" ::
    """1111:2222:3333:4444::5555:""" ::
    """1111:2222:3333::5555:""" ::
    """1111:2222::5555:""" ::
    """1111::5555:""" ::
    """::5555:""" ::
    """:::""" ::
    """1111:00:00""" ::
    """:""" ::
    """:1111:2222:3333:4444::5555""" ::
    """:1111:2222:3333::5555""" ::
    """:1111:2222::5555""" ::
    """:1111::5555""" ::
    """:::5555""" ::
    """:::""" ::
    """123""" ::
    """ldkfj""" ::
    """2001::FFD3::57ab""" ::
    """2001:db8:85a3::8a2e:37023:7334""" ::
    """2001:db8:85a3::8a2e:370k:7334""" ::
    """1:2:3:4:5:6:7:8:9""" ::
    """1::2::3""" ::
    """1:::3:4:5""" ::
    """1:2:3::4:5:6:7:8:9""" ::
    """XXXX:XXXX:XXXX:XXXX:XXXX:XXXX:XXXX:XXXX""" ::
    """1111:2222:3333:4444:5555:6666:7777:8888:9999""" ::
    """1111:2222:3333:4444:5555:6666:7777:8888::""" ::
    """::2222:3333:4444:5555:6666:7777:8888:9999""" ::
    """1111:2222:3333:4444:5555:6666:7777""" ::
    """1111:2222:3333:4444:5555:6666""" ::
    """1111:2222:3333:4444:5555""" ::
    """1111:2222:3333:4444""" ::
    """1148:57:33""" ::
    """1148:02:00""" ::
    """1111""" ::
    """11112222:3333:4444:5555:6666:7777:8888""" ::
    """1111:22223333:4444:5555:6666:7777:8888""" ::
    """1111:2222:33334444:5555:6666:7777:8888""" ::
    """1111:2222:3333:44445555:6666:7777:8888""" ::
    """1111:2222:3333:4444:55556666:7777:8888""" ::
    """1111:2222:3333:4444:5555:66667777:8888""" ::
    """1111:2222:3333:4444:5555:6666:77778888""" ::
    """1111:2222:3333:4444:5555:6666:7777:8888:""" ::
    """1111:2222:3333:4444:5555:6666:7777:""" ::
    """1111:2222:3333:4444:5555:6666:""" ::
    """1111:2222:3333:4444:5555:""" ::
    """1111:2222:3333:4444:""" ::
    """1148:57:33""" ::
    """1148:02:00""" ::
    """1111:00:00""" ::
    """:""" ::
    """:8888""" ::
    """:7777:8888""" ::
    """:6666:7777:8888""" ::
    """:5555:6666:7777:8888""" ::
    """:4444:5555:6666:7777:8888""" ::
    """:3333:4444:5555:6666:7777:8888""" ::
    """:2222:3333:4444:5555:6666:7777:8888""" ::
    """:1111:2222:3333:4444:5555:6666:7777:8888""" ::
    """:::2222:3333:4444:5555:6666:7777:8888""" ::
    """1111:::3333:4444:5555:6666:7777:8888""" ::
    """1111:2222:::4444:5555:6666:7777:8888""" ::
    """1111:2222:3333:::5555:6666:7777:8888""" ::
    """1111:2222:3333:4444:::6666:7777:8888""" ::
    """1111:2222:3333:4444:5555:::7777:8888""" ::
    """1111:2222:3333:4444:5555:6666:::8888""" ::
    """1111:2222:3333:4444:5555:6666:7777:::""" ::
    """::2222:3333::5555:6666:7777:8888""" ::
    """::2222:3333:4444::6666:7777:8888""" ::
    """::2222:3333:4444:5555::7777:8888""" ::
    """::2222:3333:4444:5555:7777::8888""" ::
    """::2222:3333:4444:5555:7777:8888::""" ::
    """::2222:3333:4444:5555:7777:8888::""" ::
    """1111::3333:4444::6666:7777:8888""" ::
    """1111::3333:4444:5555::7777:8888""" ::
    """1111::3333:4444:5555:6666::8888""" ::
    """1111::3333:4444:5555:6666:7777::""" ::
    """1111::3333:4444:5555:6666:7777::""" ::
    """1111:2222::4444:5555::7777:8888""" ::
    """1111:2222::4444:5555:6666::8888""" ::
    """1111:2222::4444:5555:6666:7777::""" ::
    """1111:2222::4444:5555:6666:7777::""" ::
    """1111:2222:3333::5555:6666::8888""" ::
    """1111:2222:3333::5555:6666:7777::""" ::
    """1111:2222:3333::5555:6666:7777::""" ::
    """1111:2222:3333:4444::6666:7777::""" ::
    """1111:2222:3333:4444::6666:7777::""" ::
    """1111:2222:3333:4444:5555::7777::""" ::
    """1111:2222:3333:4444:5555:6666:7777:1.2.3.4""" ::
    """1111:2222:3333:4444:5555:6666::1.2.3.4""" ::
    """::2222:3333:4444:5555:6666:7777:1.2.3.4""" ::
    """1111:2222:3333:4444:5555:6666:1.2.3.4.5""" ::
    """1111:2222:3333:4444:5555:6666:1.2.3.4.5""" ::
    """1111:2222:3333:4444:1.2.3.4""" ::
    """1111:2222:3333:1.2.3.4""" ::
    """1111:2222:1.2.3.4""" ::
    """1111:1.2.3.4""" ::
    """1111:22223333:4444:5555:6666:1.2.3.4""" ::
    """1111:2222:33334444:5555:6666:1.2.3.4""" ::
    """1111:2222:3333:44445555:6666:1.2.3.4""" ::
    """1111:2222:3333:4444:55556666:1.2.3.4""" ::
    """1111:2222:3333:4444:5555:66661.2.3.4""" ::
    """1111:2222:3333:4444:5555:66661.2.3.4""" ::
    """1111:2222:3333:4444:5555:6666:255.255255.255""" ::
    """1111:2222:3333:4444:5555:6666:255.255.255255""" ::
    """1111:2222:3333:4444:5555:6666:255.255.255255""" ::
    """:6666:1.2.3.4""" ::
    """:5555:6666:1.2.3.4""" ::
    """:4444:5555:6666:1.2.3.4""" ::
    """:3333:4444:5555:6666:1.2.3.4""" ::
    """:2222:3333:4444:5555:6666:1.2.3.4""" ::
    """:1111:2222:3333:4444:5555:6666:1.2.3.4""" ::
    """:1111:2222:3333:4444:5555:6666:1.2.3.4""" ::
    """1111:::3333:4444:5555:6666:1.2.3.4""" ::
    """1111:2222:::4444:5555:6666:1.2.3.4""" ::
    """1111:2222:3333:::5555:6666:1.2.3.4""" ::
    """1111:2222:3333:4444:::6666:1.2.3.4""" ::
    """1111:2222:3333:4444:5555:::1.2.3.4""" ::
    """1111:2222:3333:4444:5555:::1.2.3.4""" ::
    """::2222:3333::5555:6666:1.2.3.4""" ::
    """::2222:3333:4444::6666:1.2.3.4""" ::
    """::2222:3333:4444:5555::1.2.3.4""" ::
    """::2222:3333:4444:5555::1.2.3.4""" ::
    """1111::3333:4444::6666:1.2.3.4""" ::
    """1111::3333:4444:5555::1.2.3.4""" ::
    """1111::3333:4444:5555::1.2.3.4""" ::
    """1111:2222::4444:5555::1.2.3.4""" ::
    """1111:2222::4444:5555::1.2.3.4""" ::
    """1111:2222:3333::5555::1.2.3.4""" ::
    """::..""" ::
    """::...""" ::
    """::1...""" ::
    """::1.2..""" ::
    """::1.2.3.""" ::
    """::.2..""" ::
    """::.2.3.""" ::
    """::.2.3.4""" ::
    """::..3.""" ::
    """::..3.4""" ::
    """::...4""" ::
    """::...4""" ::
    """:1111:2222:3333:4444:5555:6666::""" ::
    """:1111:2222:3333:4444:5555::""" ::
    """:1111:2222:3333:4444::""" ::
    """:1111:2222:3333::""" ::
    """:1111:2222::""" ::
    """:1111::""" ::
    """:::""" ::
    """:1111:2222:3333:4444:5555:6666::8888""" ::
    """:1111:2222:3333:4444:5555::8888""" ::
    """:1111:2222:3333:4444::8888""" ::
    """:1111:2222:3333::8888""" ::
    """:1111:2222::8888""" ::
    """:1111::8888""" ::
    """:::8888""" ::
    """:1111:2222:3333:4444:5555::7777:8888""" ::
    """:1111:2222:3333:4444::7777:8888""" ::
    """:1111:2222:3333::7777:8888""" ::
    """:1111:2222::7777:8888""" ::
    """:1111::7777:8888""" ::
    """:::7777:8888""" ::
    """:1111:2222:3333:4444::6666:7777:8888""" ::
    """:1111:2222:3333::6666:7777:8888""" ::
    """:1111:2222::6666:7777:8888""" ::
    """:1111::6666:7777:8888""" ::
    """:::6666:7777:8888""" ::
    """:1111:2222:3333::5555:6666:7777:8888""" ::
    """:1111:2222::5555:6666:7777:8888""" ::
    """:1111::5555:6666:7777:8888""" ::
    """:::5555:6666:7777:8888""" ::
    """:1111:2222::4444:5555:6666:7777:8888""" ::
    """:1111::4444:5555:6666:7777:8888""" ::
    """:::4444:5555:6666:7777:8888""" ::
    """:1111::3333:4444:5555:6666:7777:8888""" ::
    """:::3333:4444:5555:6666:7777:8888""" ::
    """:::2222:3333:4444:5555:6666:7777:8888""" ::
    """:1111:2222:3333:4444:5555:6666:1.2.3.4""" ::
    """:1111:2222:3333:4444:5555::1.2.3.4""" ::
    """:1111:2222:3333:4444::1.2.3.4""" ::
    """:1111:2222:3333::1.2.3.4""" ::
    """:1111:2222::1.2.3.4""" ::
    """:1111::1.2.3.4""" ::
    """:::1.2.3.4""" ::
    """:1111:2222:3333:4444::6666:1.2.3.4""" ::
    """:1111:2222:3333::6666:1.2.3.4""" ::
    """:1111:2222::6666:1.2.3.4""" ::
    """:1111::6666:1.2.3.4""" ::
    """:::6666:1.2.3.4""" ::
    """:1111:2222:3333::5555:6666:1.2.3.4""" ::
    """:1111:2222::5555:6666:1.2.3.4""" ::
    """:1111::5555:6666:1.2.3.4""" ::
    """:::5555:6666:1.2.3.4""" ::
    """:1111:2222::4444:5555:6666:1.2.3.4""" ::
    """:1111::4444:5555:6666:1.2.3.4""" ::
    """:::4444:5555:6666:1.2.3.4""" ::
    """:1111::3333:4444:5555:6666:1.2.3.4""" ::
    """:::2222:3333:4444:5555:6666:1.2.3.4""" ::
    """:::2222:3333:4444:5555:6666:1.2.3.4""" ::
    """1111:2222:3333:4444:5555:6666:::""" ::
    """1111:2222:3333:4444:5555:::""" ::
    """1111:2222:3333:4444:::""" ::
    """1111:2222:3333:::""" ::
    """1111:2222:::""" ::
    """1111:::""" ::
    """:::""" ::
    """1111:2222:3333:4444:5555:6666::8888:""" ::
    """1111:2222:3333:4444:5555::8888:""" ::
    """1111:2222:3333:4444::8888:""" ::
    """1111:2222:3333::8888:""" ::
    """1111:2222::8888:""" ::
    """1111::8888:""" ::
    """::8888:""" ::
    """1111:2222:3333:4444:5555::7777:8888:""" ::
    """1111:2222:3333:4444::7777:8888:""" ::
    """1111:2222:3333::7777:8888:""" ::
    """1111:2222::7777:8888:""" ::
    """1111::7777:8888:""" ::
    """::7777:8888:""" ::
    """1111:2222:3333:4444::6666:7777:8888:""" ::
    """1111:2222:3333::6666:7777:8888:""" ::
    """1111:2222::6666:7777:8888:""" ::
    """1111::6666:7777:8888:""" ::
    """::6666:7777:8888:""" ::
    """1111:2222:3333::5555:6666:7777:8888:""" ::
    """1111:2222::5555:6666:7777:8888:""" ::
    """1111::5555:6666:7777:8888:""" ::
    """::5555:6666:7777:8888:""" ::
    """1111:2222::4444:5555:6666:7777:8888:""" ::
    """1111::4444:5555:6666:7777:8888:""" ::
    """::4444:5555:6666:7777:8888:""" ::
    """1111::3333:4444:5555:6666:7777:8888:""" ::
    """::3333:4444:5555:6666:7777:8888:""" ::
    """::2222:3333:4444:5555:6666:7777:8888:""" ::
    """::2222:3333:4444:5555:6666:7777:8888:""" ::
    """':10.0.0.1""" ::
    Nil
}
