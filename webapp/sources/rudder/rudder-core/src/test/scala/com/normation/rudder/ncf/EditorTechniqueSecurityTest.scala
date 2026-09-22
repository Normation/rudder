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

package com.normation.rudder.ncf

import com.normation.eventlog.EventActor
import com.normation.inventory.domain.Version
import com.normation.rudder.tenants.ChangeContext
import com.normation.rudder.tenants.SecurityTag
import com.normation.rudder.tenants.TenantAccess
import com.normation.rudder.tenants.TenantAccessGrant
import com.normation.rudder.tenants.TenantId
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.Chunk

/*
 * Who decides the tenants of a technique written through the API: the client when it states them, the
 * server otherwise. `TechniqueApi` passes the creator's writable tenants on creation and the tag the
 * technique already has on update.
 */
@RunWith(classOf[JUnitRunner])
class EditorTechniqueSecurityTest extends Specification {

  private def technique(security: Option[SecurityTag]) = {
    EditorTechnique(
      BundleName("t"),
      new Version("1.0"),
      "a technique",
      "ncf_techniques",
      Seq(),
      "",
      "",
      Seq(),
      Seq(),
      Map(),
      None,
      None,
      security
    )
  }

  private val zoneA = SecurityTag.ByTenants(Chunk(TenantId("zoneA")))
  private val zoneB = SecurityTag.ByTenants(Chunk(TenantId("zoneB")))
  private def cc(grant: TenantAccessGrant) = ChangeContext.newFor(EventActor("u"), grant)
  private val aliceCc = cc(TenantAccessGrant.ByTenants(Chunk(TenantAccess(TenantId("zoneA")))))
  private val adminCc = ChangeContext.newForRudder()

  "a technique that declares no tenants" should {
    "get the creator's writable tenants" in {
      technique(None).withSecurityIfUndeclared(aliceCc.accessGrant.restrictToWrite.toSecurityTag).security must beSome(
        zoneA: SecurityTag
      )
    }
    // an all-tenants grant has no tag, so a deployment without tenants is unchanged
    "stay untagged when an administrator creates it" in {
      technique(None).withSecurityIfUndeclared(adminCc.accessGrant.restrictToWrite.toSecurityTag).security must beNone
    }
    // the editor posts the technique back without the field as long as it knows nothing about tenants
    "keep the tag it already has on update" in {
      technique(None).withSecurityIfUndeclared(Some(zoneA)).security must beSome(zoneA: SecurityTag)
    }
  }

  "a technique that declares its tenants" should {
    "keep what it declares, whoever writes it" in {
      (technique(Some(zoneB))
        .withSecurityIfUndeclared(aliceCc.accessGrant.restrictToWrite.toSecurityTag)
        .security must beSome(zoneB: SecurityTag)) and
      (technique(Some(zoneB)).withSecurityIfUndeclared(Some(zoneA)).security must beSome(zoneB: SecurityTag))
    }
    "not have the fallback evaluated at all" in {
      technique(Some(zoneB)).withSecurityIfUndeclared(throw new RuntimeException("must not be read")).security must beSome(
        zoneB: SecurityTag
      )
    }
  }
}
