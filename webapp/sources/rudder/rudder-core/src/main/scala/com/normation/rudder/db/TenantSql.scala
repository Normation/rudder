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
import doobie.*

/*
 * SQL rendering of the acting subject's tenant read reach, for direct-SQL repositories that can not use the
 * tenant filtering proxies (they must filter inside the query itself, e.g. so paging and counts stay
 * correct). This is a purely mechanical translation of a `ReaderScope` - the decision, computed by
 * `TenantCheckLogic` - into a SQL WHERE fragment; it holds no tenant decision logic of its own.
 */
object TenantSql {

  /*
   * Keep only rows the reader may see: rows whose `column` (a jsonb SecurityTag, stored with the standard
   * serialization: `"open"` or `{"tenants":[...]}`) is within the reader's scope:
   *   - `All`: no restriction (None);
   *   - `AnyOf(ids)`: the tag is `open`, or its tenants share one of the readable ids (an empty id set thus
   *     keeps only `open` rows).
   * A NULL column yields NULL in the comparisons and is therefore excluded (admin-only, fail closed).
   *
   * Emitted as a `Fragment.const` (a literal, not a bound parameter) because it embeds an `ARRAY[...]` of
   * tenant ids; this is safe from injection because a tenant id is constrained to ascii alphanumerics plus
   * '-'/'_' (see `TenantId.checkTenantId`) and we still defensively drop any id containing a quote. We use
   * the function `jsonb_exists_any(...)` rather than the `?|` operator on purpose: a literal `?` clashes
   * with JDBC bind-parameter parsing. `column` is a code constant, not user input.
   */
  def readerScopeFragment(scope: ReaderScope, column: String): Option[Fragment] = {
    scope.readableTenantIds match {
      case None      => None // unrestricted (All): no WHERE clause
      case Some(ids) =>
        val safeIds  = ids.map(_.value).filterNot(_.contains('\'')).toList.sorted
        val idsArray = safeIds.map(id => s"'${id}'").mkString("ARRAY[", ",", "]::text[]")
        Some(
          Fragment.const(
            s"(${column} = '\"open\"'::jsonb or jsonb_exists_any(${column} -> 'tenants', ${idsArray}))"
          )
        )
    }
  }
}
