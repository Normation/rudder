/*
 * *************************************************************************************
 * Copyright 2026 Normation SAS
 * *************************************************************************************
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
 * *************************************************************************************
 */
package com.normation.rudder.users

import com.normation.errors.IOResult
import com.normation.rudder.db.Doobie
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.*
import zio.interop.catz.*

trait TotpRepository {
  def getByUserId(userId: UserId): IOResult[Option[Totp]]
  def create(userId:      UserId, totp: Totp): IOResult[Unit]
  def delete(userId:      UserId): IOResult[Unit]
  def getEnabledUsers(): IOResult[Set[UserId]]
}

object JdbcTotpRepository {

  given Meta[UserId]     = Meta[String].imap(UserId(_))(_.value)
  given Meta[TotpSecret] = Meta[String].imap(TotpSecret(_))(_.exposeSecret())
  given Read[Totp]       = Read.derived
  given Write[Totp]      = Write.derived

  def getByUserIdSQL(userId: UserId): Query0[Totp] =
    sql"SELECT secret, created_at FROM userstotp WHERE user_id = ${userId.value}".query[Totp]

  def upsertQuerySQL(userId: UserId, totp: Totp): Update0 =
    sql"INSERT INTO userstotp (user_id, secret, created_at) VALUES (${userId.value}, ${totp}) ON CONFLICT (user_id) DO UPDATE SET secret=EXCLUDED.secret, created_at=EXCLUDED.created_at".update

  def deleteQuerySQL(userId: UserId): Update0 =
    sql"DELETE FROM userstotp WHERE user_id=${userId.value}".update

  def getAllByUserSQL(): Query0[String] =
    sql"SELECT user_id FROM userstotp".query[String]
}

class JdbcTotpRepository(doobie: Doobie) extends TotpRepository {
  import JdbcTotpRepository.*
  import doobie.*

  def getByUserId(userId: UserId): IOResult[Option[Totp]] = {
    transactIOResult(s"Error when getting TOTP for user ${userId.value}")(xa => getByUserIdSQL(userId).option.transact(xa))
  }

  def create(userId: UserId, totp: Totp): IOResult[Unit] = {
    transactIOResult(s"Error when creating TOTP for user ${userId.value}")(
      upsertQuerySQL(userId, totp).run.transact(_).unit
    )
  }

  def delete(userId: UserId): IOResult[Unit] = {
    transactIOResult(s"Error deleting TOTP for user ${userId.value}")(
      deleteQuerySQL(userId).run.transact(_).unit
    )
  }

  def getEnabledUsers(): IOResult[Set[UserId]] = {
    transactIOResult("Error when getting enabled TOTP users")(
      getAllByUserSQL().to[Set].map(_.map(UserId(_))).transact(_)
    )
  }
}
