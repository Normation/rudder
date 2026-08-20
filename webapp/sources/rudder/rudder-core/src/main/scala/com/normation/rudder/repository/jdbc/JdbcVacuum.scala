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

package com.normation.rudder.repository.jdbc

import cats.syntax.apply.*
import com.normation.errors.IOResult
import com.normation.rudder.db.Doobie
import doobie.*
import doobie.implicits.*
import zio.*
import zio.interop.catz.*

/**
 * Interface for vacuuming/maintaining a table:
 * this is a Postgres-specific maintenance operation, which can be combined,
 * e.g. as batch database maintenance operations defined in the webapp
 */
sealed trait JdbcVacuum {
  def vacuum(): IOResult[Unit]
}

object JdbcVacuum {
  // all the vacuum operations to run in the database maintenance schedule
  def all(doobie: Doobie): List[JdbcVacuum] = List(
    new JdbcVacuumFull("NodeLastCompliance")(doobie)
  )
}

/**
 * Postgres VACUUM FULL implementation: rewrites the entire table, acquiring a lock,
 * but clears more disk space.
 */
class JdbcVacuumFull(table: String)(doobie: Doobie) extends JdbcVacuum {

  import doobie.*

  override def vacuum(): IOResult[Unit] = {
    val query = s"VACUUM FULL ${table}"

    transactIOResult(s"error when vacuuming full table ${table}")(xa =>
      (FC.setAutoCommit(true) *> Update0(query, None).run <* FC.setAutoCommit(false)).transact(xa)
    ).unit
  }
}
