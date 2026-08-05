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

package bootstrap.liftweb.checks.earlyconfig.db

import bootstrap.liftweb.BootstrapChecks
import bootstrap.liftweb.BootstrapLogger
import com.normation.errors.IOResult
import com.normation.rudder.db.Doobie
import com.normation.zio.*
import doobie.implicits.*
import zio.interop.catz.*

/*
 * Multi-tenants: the event log now records the tenant security tag the changed object had *before* the
 * change, so that event-log reads can be filtered by the reader's tenant grant (see the multi-tenants
 * object-tenant-tag-lifecycle ADR).
 *
 * This migration adds the nullable `securitytag` column to the existing `eventlog` table. The tag is stored
 * with the standard SecurityTag JSON serialization, so the column is `jsonb`. Existing rows keep a NULL tag,
 * which is interpreted as admin-only (fail closed): pre-migration events are only visible to an all-tenants
 * (admin) grant. The migration is convergent (ADD COLUMN IF NOT EXISTS + CREATE INDEX IF NOT EXISTS). Unlike
 * most event-log migrations it runs *synchronously* (blocking boot): every event-log insert now references the
 * column, so it must exist before the application starts writing events. The single ADD COLUMN is fast.
 *
 * A GIN index on the column speeds the tenant-visibility filter (`jsonb_exists_any` / containment) used when
 * reading event logs under a tenant-restricted grant.
 */
class MigrateEventLogAddSecurityTag(
    doobie: Doobie
) extends BootstrapChecks {

  import doobie.*

  override def description: String =
    "Check if the 'eventlog' table has a 'securitytag' column, otherwise add it"

  def addColumnStatement: IOResult[Unit] = {
    val sql = sql"""ALTER TABLE IF EXISTS eventlog ADD COLUMN IF NOT EXISTS securitytag jsonb;"""
    transactIOResult(s"Error when adding column 'securitytag' to table 'eventlog'")(xa => sql.update.run.transact(xa)).unit
  }

  def addIndexStatement: IOResult[Unit] = {
    val sql = sql"""CREATE INDEX IF NOT EXISTS eventlog_securitytag_idx ON eventlog USING gin (securitytag);"""
    transactIOResult(s"Error when adding index 'eventlog_securitytag_idx' to table 'eventlog'")(xa =>
      sql.update.run.transact(xa)
    ).unit
  }

  override def checks(): Unit = {
    // run synchronously: the column is required by every subsequent event-log insert
    (addColumnStatement *> addIndexStatement)
      .catchAll(err =>
        BootstrapLogger.Early.DB.error(s"Error when trying to add column 'securitytag' to table 'eventlog': ${err.fullMsg}")
      )
      .runNow
  }

}
