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

package com.normation.rudder.tenants

import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersionHelper
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import com.normation.rudder.services.policies.ReferenceTag
import com.normation.rudder.services.policies.TechniqueLibraryTenantSync.declaredFor
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.Chunk

/*
 * What the reference library declares for an object of the user library, which the sync then copies as is.
 * `Absent` and `Declares(None)` are different answers: the first leaves the object alone, the second
 * removes its tag.
 */
@RunWith(classOf[JUnitRunner])
class TechniqueTenantInheritanceTest extends Specification {

  private val library = SecurityTag.LIBRARY_SECURITY_TAG
  private def tenants(ids: String*): Option[SecurityTag] =
    Some(SecurityTag.ByTenants(Chunk.fromIterable(ids.map(TenantId(_)))))

  private def technique(version: String, security: Option[SecurityTag]): Technique = {
    Technique(
      TechniqueId(TechniqueName("t"), TechniqueVersionHelper(version)),
      "t",
      "",
      Nil,
      com.normation.cfclerk.domain.TrackerVariableSpec(None, None),
      com.normation.cfclerk.domain.SectionSpec("root"),
      None,
      security = security
    )
  }

  "a technique that is not on disk" should {
    "leave the active technique alone" in {
      declaredFor(Nil) must beEqualTo(ReferenceTag.Absent)
    }
  }

  "an active technique" should {
    // it covers every version of its technique, so it shows what any of them shows
    "take the join of what its versions declare" in {
      declaredFor(List(technique("1.0", tenants("zoneA")), technique("2.0", tenants("zoneB")))) must beEqualTo(
        ReferenceTag.Declares(tenants("zoneA", "zoneB"))
      )
    }
    "take what its single version declares" in {
      declaredFor(List(technique("1.0", library))) must beEqualTo(ReferenceTag.Declares(library))
    }
    "have no tag when no version declares one" in {
      declaredFor(List(technique("1.0", None), technique("2.0", None))) must beEqualTo(ReferenceTag.Declares(None))
    }
  }

  "a category" should {
    val onDisk = Map(ActiveTechniqueCategoryId("c") -> tenants("zoneA"))

    "take what its `category.xml` declares" in {
      declaredFor(onDisk, ActiveTechniqueCategoryId("c")) must beEqualTo(ReferenceTag.Declares(tenants("zoneA")))
    }
    "be left alone when it has no counterpart on disk" in {
      declaredFor(onDisk, ActiveTechniqueCategoryId("other")) must beEqualTo(ReferenceTag.Absent)
    }
    "have its tag removed when its counterpart declares none" in {
      declaredFor(Map(ActiveTechniqueCategoryId("c") -> None), ActiveTechniqueCategoryId("c")) must beEqualTo(
        ReferenceTag.Declares(None)
      )
    }
  }
}
