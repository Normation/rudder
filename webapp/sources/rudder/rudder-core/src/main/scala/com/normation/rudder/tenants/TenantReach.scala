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

/*
 * This is the counter part to ReaderScope. It answers: "does this object apply to that one", as opposed to
 * `ReaderScope`, which answers "may this actor read that object".
 *
 * Used by every object-to-object tenant clamp:
 * - a rule to the nodes it generates on (ADR 28945-tenant-enforcement-at-policy-generation),
 * - a global property to the nodes it is distributed to (ADR 28945-global-properties-and-tenant-scoping).
 *
 * We use an opaque type because we are on a hot-path and it's actually the same representation as a grant.
 */
opaque type TenantReach = TenantAccessGrant

object TenantReach {

  /*
   * The reach of an object carrying that tag. Untagged and open objects reach everything.
   */
  def of(tag: Option[SecurityTag]): TenantReach = TenantAccessGrant.fromSecurityScope(tag)

  extension (reach: TenantReach) {
    def reaches(tag: Option[SecurityTag]): Boolean = (reach: TenantAccessGrant).toReaderScope.canSee(tag)

    def reaches[A: HasSecurityTag](a: A): Boolean = reaches(a.security)
  }
}
