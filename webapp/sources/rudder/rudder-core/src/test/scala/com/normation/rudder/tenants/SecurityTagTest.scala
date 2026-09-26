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

package com.normation.rudder.tenants

import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.Chunk
import zio.json.*

/*
 * Serialization of `SecurityTag` - JSON for the API and the event-log jsonb column, a bare word in the
 * `securityTag` LDAP attribute, XML for git archives and technique metadata - and the visibility lattice
 * the monotonic-growth law is built on.
 *
 * The 9.1 `open` tag was split in `open-ro` and `open-rw`, and reads as `open-ro`.
 */
@RunWith(classOf[JUnitRunner])
class SecurityTagTest extends Specification {

  private val zoneA    = SecurityTag.ByTenants(Chunk(TenantId("zoneA")))
  private val twoZones = SecurityTag.ByTenants(Chunk(TenantId("zoneA"), TenantId("zoneB")))

  private def parse(s: String): Either[String, SecurityTag] = s.fromJson[SecurityTag]

  "JSON serialization" should {
    "write the open tags as plain strings" in {
      ((SecurityTag.OpenRo: SecurityTag).toJson must beEqualTo(""""open-ro"""")) and
      ((SecurityTag.OpenRw: SecurityTag).toJson must beEqualTo(""""open-rw""""))
    }
    "write a tenant list as an object" in {
      (zoneA: SecurityTag).toJson must beEqualTo("""{"tenants":["zoneA"]}""")
    }
    "round-trip every tag" in {
      List[SecurityTag](SecurityTag.OpenRo, SecurityTag.OpenRw, zoneA, twoZones, SecurityTag.empty)
        .map(t => parse(t.toJson) must beRight(t))
        .reduce(_ and _)
    }
    "read the 9.1 `open` tag as `open-ro`" in {
      parse(""""open"""") must beRight(SecurityTag.OpenRo: SecurityTag)
    }
    "reject an unknown string" in {
      parse(""""open-rx"""") must beLeft
    }
  }

  // an open tag is a bare word in the attribute, a tenant list keeps the JSON object
  "the LDAP form of a tag" should {
    "write an open tag as the bare word and a tenant list as JSON" in {
      (SecurityTag.toLdapValue(SecurityTag.OpenRo) must beEqualTo("open-ro")) and
      (SecurityTag.toLdapValue(SecurityTag.OpenRw) must beEqualTo("open-rw")) and
      (SecurityTag.toLdapValue(zoneA) must beEqualTo("""{"tenants":["zoneA"]}"""))
    }
    "round-trip every tag" in {
      List[SecurityTag](SecurityTag.OpenRo, SecurityTag.OpenRw, zoneA, twoZones, SecurityTag.empty)
        .map(t => SecurityTag.parseLdapValue(Some(SecurityTag.toLdapValue(t)), "test") must beSome(t))
        .reduce(_ and _)
    }
    // 9.1 wrote the JSON form of the tag into the attribute
    "read the quoted form 9.1 wrote" in {
      (SecurityTag.parseLdapValue(Some(""""open-ro""""), "test") must beSome(SecurityTag.OpenRo: SecurityTag)) and
      (SecurityTag.parseLdapValue(Some(""""open""""), "test") must beSome(SecurityTag.OpenRo: SecurityTag))
    }
    "read the legacy bare `open` as `open-ro`" in {
      SecurityTag.parseLdapValue(Some("open"), "test") must beSome(SecurityTag.OpenRo: SecurityTag)
    }
    "ignore the surrounding whitespace an LDIF may carry" in {
      SecurityTag.parseLdapValue(Some(" open-ro "), "test") must beSome(SecurityTag.OpenRo: SecurityTag)
    }
    // fails closed, and `parseLdapValue` warns
    "be admin-only when it can not be read" in {
      (SecurityTag.parseLdapValue(Some("open-rx"), "test") must beNone) and
      (SecurityTag.parseLdapValue(Some("""{"tenant":["zoneA"]}"""), "test") must beNone) and
      (SecurityTag.parseLdapValue(None, "test") must beNone)
    }
  }

  "XML serialization" should {
    "round-trip every tag" in {
      List[SecurityTag](SecurityTag.OpenRo, SecurityTag.OpenRw, zoneA, twoZones)
        .map(t => SecurityTag.fromXml(<obj>{SecurityTag.toXml(Some(t))}</obj>) must beSome(t))
        .reduce(_ and _)
    }
    "write nothing for an untagged object, and read it back as untagged" in {
      (SecurityTag.toXml(None).isEmpty must beTrue) and
      (SecurityTag.fromXml(<obj></obj>) must beNone)
    }
    "read the 9.1 `<open/>` element as `open-ro`" in {
      SecurityTag.fromXml(<obj><security><open/></security></obj>) must beSome(SecurityTag.OpenRo: SecurityTag)
    }
  }

  // `None` (administrators only) is the bottom, the open tags are the top, a tenant list is above the lists
  // it contains. Two disjoint tenant lists are not comparable.
  "the visibility lattice" should {
    "put both open tags above everything" in {
      List(Some(zoneA: SecurityTag), Some(twoZones: SecurityTag), None).flatMap { t =>
        List(SecurityTag.OpenRo, SecurityTag.OpenRw).map(o => SecurityTag.isWiderOrEqual(Some(o), t) must beTrue)
      }.reduce(_ and _)
    }
    "not let an open tag be narrowed" in {
      SecurityTag.isWiderOrEqual(Some(zoneA), Some(SecurityTag.OpenRo)) must beFalse
    }
    "consider the two open tags equally wide: they differ on who writes, not on who sees" in {
      (SecurityTag.isWiderOrEqual(Some(SecurityTag.OpenRo), Some(SecurityTag.OpenRw)) must beTrue) and
      (SecurityTag.isWiderOrEqual(Some(SecurityTag.OpenRw), Some(SecurityTag.OpenRo)) must beTrue)
    }
    "put the untagged (admin-only) object at the bottom" in {
      (SecurityTag.isWiderOrEqual(Some(zoneA), None) must beTrue) and
      (SecurityTag.isWiderOrEqual(None, Some(zoneA)) must beFalse) and
      (SecurityTag.isWiderOrEqual(None, None) must beTrue)
    }
    "compare tenant lists by inclusion" in {
      (SecurityTag.isWiderOrEqual(Some(twoZones), Some(zoneA)) must beTrue) and
      (SecurityTag.isWiderOrEqual(Some(zoneA), Some(twoZones)) must beFalse) and
      (SecurityTag.isWiderOrEqual(
        Some(zoneA),
        Some(SecurityTag.ByTenants(Chunk(TenantId("zoneB"))))
      ) must beFalse)
    }
  }

  "the join of two tags" should {
    "be the union of two tenant lists" in {
      SecurityTag.join(Some(zoneA), Some(SecurityTag.ByTenants(Chunk(TenantId("zoneB"))))) must beSome(twoZones: SecurityTag)
    }
    "not duplicate a tenant present on both sides" in {
      SecurityTag.join(Some(twoZones), Some(zoneA)) must beSome(twoZones: SecurityTag)
    }
    "keep the other tag when one side is untagged" in {
      (SecurityTag.join(None, Some(zoneA)) must beSome(zoneA: SecurityTag)) and
      (SecurityTag.join(Some(zoneA), None) must beSome(zoneA: SecurityTag))
    }
    // equally visible, so the join keeps the narrower write
    "be read-only as soon as one side is read-only" in {
      (SecurityTag.join(Some(SecurityTag.OpenRo), Some(SecurityTag.OpenRw)) must beSome(SecurityTag.OpenRo: SecurityTag)) and
      (SecurityTag.join(Some(SecurityTag.OpenRw), Some(SecurityTag.OpenRo)) must beSome(SecurityTag.OpenRo: SecurityTag)) and
      (SecurityTag.join(Some(SecurityTag.OpenRo), Some(zoneA)) must beSome(SecurityTag.OpenRo: SecurityTag))
    }
    "be an upper bound of both sides" in {
      val tags = List[Option[SecurityTag]](None, Some(zoneA), Some(twoZones), Some(SecurityTag.OpenRo), Some(SecurityTag.OpenRw))
      (for {
        a <- tags
        b <- tags
      } yield {
        val j = SecurityTag.join(a, b)
        (SecurityTag.isWiderOrEqual(j, a) must beTrue) and (SecurityTag.isWiderOrEqual(j, b) must beTrue)
      }).reduce(_ and _)
    }
  }
}
