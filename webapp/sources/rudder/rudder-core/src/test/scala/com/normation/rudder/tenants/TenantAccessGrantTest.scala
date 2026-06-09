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

/*
 * Test the serialization/parsing of `TenantAccess` / `TenantAccessGrant`, especially the
 * backward compatibility with the previous `TenantId`-only serialization (a bare tenant id
 * means `ReadWrite`), and the new read/write permission suffix (`:r`, `:rw`).
 */
@RunWith(classOf[JUnitRunner])
class TenantAccessGrantTest extends Specification {

  import TenantPermission.*

  def access(id:    String, p: TenantPermission) = TenantAccess(TenantId(id), p)
  def byTenants(ts: TenantAccess*) = TenantAccessGrant.ByTenants(Chunk.fromIterable(ts))

  "TenantAccess serialization" should {
    "serialize ReadWrite as a bare tenant id (backward compatible)" in {
      access("zoneA", ReadWrite).serialize must beEqualTo("zoneA")
    }
    "serialize Read with the ':r' suffix" in {
      access("zoneA", Read).serialize must beEqualTo("zoneA:r")
    }
    "parse a bare tenant id as ReadWrite (backward compatible)" in {
      TenantAccess.parse("zoneA") must beSome(access("zoneA", ReadWrite))
    }
    "parse ':r' as Read and ':rw' as ReadWrite" in {
      (TenantAccess.parse("zoneA:r") must beSome(access("zoneA", Read))) and
      (TenantAccess.parse("zoneA:rw") must beSome(access("zoneA", ReadWrite)))
    }
    "refuse an invalid tenant id or permission token" in {
      (TenantAccess.parse("zone A") must beNone) and
      (TenantAccess.parse("zoneA:x") must beNone) and
      (TenantAccess.parse("zoneA:") must beNone)
    }
    "round-trip serialize/parse" in {
      Seq(access("zoneA", ReadWrite), access("zoneB", Read)).map(a => TenantAccess.parse(a.serialize)) must beEqualTo(
        Seq(Some(access("zoneA", ReadWrite)), Some(access("zoneB", Read)))
      )
    }
  }

  "TenantAccessGrant parsing" should {
    "parse '*' as All and '-' as None" in {
      (TenantAccessGrant.parse(Some("*")) must beRight(TenantAccessGrant.All: TenantAccessGrant)) and
      (TenantAccessGrant.parse(Some("-")) must beRight(TenantAccessGrant.None: TenantAccessGrant))
    }
    "parse a previously-serialized list of bare tenant ids as all-ReadWrite (backward compatible)" in {
      TenantAccessGrant.parse(Some("zoneA,zoneB")) must beRight(
        byTenants(access("zoneA", ReadWrite), access("zoneB", ReadWrite)): TenantAccessGrant
      )
    }
    "parse a mix of read-only and read-write tenants" in {
      TenantAccessGrant.parse(Some("zoneA,zoneB:r")) must beRight(
        byTenants(access("zoneA", ReadWrite), access("zoneB", Read)): TenantAccessGrant
      )
    }
    "serialize a grant back to the compact string form" in {
      byTenants(access("zoneA", ReadWrite), access("zoneB", Read)).serialize must beEqualTo("zoneA,zoneB:r")
    }
    "be stable through serialize then parse" in {
      val grant = byTenants(access("zoneA", ReadWrite), access("zoneB", Read), access("zoneC", ReadWrite))
      TenantAccessGrant.parse(Some(grant.serialize)) must beRight(grant: TenantAccessGrant)
    }
  }

  "Tenant permission semantics" should {
    "let ReadWrite read and write, Read only read, None neither" in {
      (ReadWrite.canRead must beTrue) and (ReadWrite.canWrite must beTrue) and
      (Read.canRead must beTrue) and (Read.canWrite must beFalse) and
      (None.canRead must beFalse) and (None.canWrite must beFalse)
    }
    "restrict a grant to its write-capable tenants" in {
      byTenants(access("zoneA", ReadWrite), access("zoneB", Read)).restrictToWrite must beEqualTo(
        byTenants(access("zoneA", ReadWrite)): TenantAccessGrant
      )
    }
    "restrict a read-only-only grant to None" in {
      byTenants(access("zoneA", Read)).restrictToWrite must beEqualTo(TenantAccessGrant.None: TenantAccessGrant)
    }
  }

  "visibleSecurityTag" should {
    val zoneATag = SecurityTag.ByTenants(Chunk(TenantId("zoneA")))
    val twoZones = SecurityTag.ByTenants(Chunk(TenantId("zoneA"), TenantId("zoneB")))

    "pass through None for admin" in {
      TenantAccessGrant.All.visibleSecurityTag(scala.None: Option[SecurityTag]) must beNone
    }
    "pass through Some(Open) for admin" in {
      TenantAccessGrant.All.visibleSecurityTag(Some(SecurityTag.Open)) must beSome(SecurityTag.Open: SecurityTag)
    }
    "pass through ByTenants unchanged for admin" in {
      TenantAccessGrant.All.visibleSecurityTag(Some(twoZones)) must beSome(twoZones: SecurityTag)
    }
    "return None for TenantAccessGrant.None regardless of tag" in {
      (TenantAccessGrant.None.visibleSecurityTag(scala.None: Option[SecurityTag]) must beNone) and
      (TenantAccessGrant.None.visibleSecurityTag(Some(SecurityTag.Open)) must beNone) and
      (TenantAccessGrant.None.visibleSecurityTag(Some(twoZones)) must beNone)
    }
    "return None for ByTenants user on untagged object" in {
      byTenants(access("zoneA", ReadWrite)).visibleSecurityTag(scala.None: Option[SecurityTag]) must beNone
    }
    "pass through Open for any ByTenants user" in {
      byTenants(access("zoneA", ReadWrite)).visibleSecurityTag(Some(SecurityTag.Open)) must beSome(SecurityTag.Open: SecurityTag)
    }
    "return intersection of user tenants and object tenants" in {
      byTenants(access("zoneA", ReadWrite)).visibleSecurityTag(Some(twoZones)) must beSome(zoneATag: SecurityTag)
    }
    "return empty ByTenants when intersection is empty" in {
      byTenants(access("zoneB", ReadWrite)).visibleSecurityTag(Some(zoneATag)) must beSome(
        SecurityTag.ByTenants(Chunk.empty): SecurityTag
      )
    }
  }

  "canSee" should {
    val zoneATag = SecurityTag.ByTenants(Chunk(TenantId("zoneA")))

    "allow admin to see any tag" in {
      (TenantAccessGrant.All.canSee(SecurityTag.Open) must beTrue) and
      (TenantAccessGrant.All.canSee(zoneATag) must beTrue)
    }
    "deny TenantAccessGrant.None for ByTenants but allow Open" in {
      (TenantAccessGrant.None.canSee(zoneATag) must beFalse) and
      (TenantAccessGrant.None.canSee(SecurityTag.Open) must beTrue)
    }
    "allow ByTenants user to see a matching tenant tag" in {
      byTenants(access("zoneA", ReadWrite)).canSee(zoneATag) must beTrue
    }
    "allow ByTenants user with read-only access to see the tag (read grants visibility)" in {
      byTenants(access("zoneA", Read)).canSee(zoneATag) must beTrue
    }
    "deny ByTenants user for a non-matching tenant tag" in {
      byTenants(access("zoneB", ReadWrite)).canSee(zoneATag) must beFalse
    }
    "only allow admin to see untagged objects (Option[SecurityTag] = None)" in {
      (TenantAccessGrant.All.canSee(scala.None: Option[SecurityTag]) must beTrue) and
      (byTenants(access("zoneA", ReadWrite)).canSee(scala.None: Option[SecurityTag]) must beFalse) and
      (TenantAccessGrant.None.canSee(scala.None: Option[SecurityTag]) must beFalse)
    }
  }

  "canModify" should {
    val zoneATag = SecurityTag.ByTenants(Chunk(TenantId("zoneA")))

    "allow admin to modify anything" in {
      TenantAccessGrant.All.restrictToWrite.canSee(zoneATag) must beTrue
    }
    "deny TenantAccessGrant.None" in {
      TenantAccessGrant.None.restrictToWrite.canSee(zoneATag) must beFalse
    }
    "allow ReadWrite user to modify a matching tenant object" in {
      byTenants(access("zoneA", ReadWrite)).restrictToWrite.canSee(zoneATag) must beTrue
    }
    "deny Read-only user from modifying even a matching tenant object" in {
      byTenants(access("zoneA", Read)).restrictToWrite.canSee(zoneATag) must beFalse
    }
  }
}
