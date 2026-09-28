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

package com.normation.rudder.db

import com.normation.rudder.tenants.ReaderScope
import com.normation.rudder.tenants.TenantId
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*

/*
 * The WHERE fragment that restricts a direct-SQL repository to what a reader may see. Whether it is valid
 * SQL against a real PostgreSQL is checked by `EventLogJdbcRepositoryTest` (which needs a database); what
 * is checked here is which tags it lets through, since getting that wrong silently hides rows.
 */
@RunWith(classOf[JUnitRunner])
class TenantSqlTest extends Specification {

  private def fragment(ids: String*): String = {
    TenantSql
      .readerScopeFragment(ReaderScope.ofReadableTenants(ids.map(TenantId(_)).toSet), "securitytag")
      .map(_.toString)
      .getOrElse("")
  }

  "the reader-scope fragment" should {
    "be absent for an unrestricted reader, so no row is filtered out" in {
      TenantSql.readerScopeFragment(ReaderScope.all, "securitytag") must beNone
    }

    // the regression this pins: matching only `"open"` would hide every row written since the tag was
    // split, and matching only the two new ones would hide every row a 9.1 instance had already written
    "let through every form of the open tag, including the 9.1 one" in {
      val f = fragment("zoneA")
      (f must contain("""'"open-ro"'::jsonb""")) and
      (f must contain("""'"open-rw"'::jsonb""")) and
      (f must contain("""'"open"'::jsonb"""))
    }

    "let through a row sharing one of the readable tenants" in {
      fragment("zoneA", "zoneB") must contain("""jsonb_exists_any(securitytag -> 'tenants', ARRAY['zoneA','zoneB']::text[])""")
    }

    // an empty set is a reader with no tenant at all: it still sees the library objects, nothing else
    "keep only the open rows when the reader has no tenant" in {
      val f = fragment()
      (f must contain("""'"open-ro"'::jsonb""")) and
      (f must contain("ARRAY[]::text[]"))
    }

    // the fragment is a literal, not a bound parameter, so a quote in an id would break out of it
    "drop a tenant id containing a quote rather than embed it" in {
      fragment("zoneA", "zo'neB") must contain("ARRAY['zoneA']::text[]")
    }
  }
}
