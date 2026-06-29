package com.normation.rudder.users

import com.normation.errors.IOResult
import com.normation.rudder.db.Doobie
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.*
import zio.interop.catz.*

trait TotpRepository {
  def getByUserId(userId: String): IOResult[Option[Totp]]
  def create(userId:      String, totp: Totp): IOResult[Unit]
  def delete(userId:      String): IOResult[Unit]
  def getEnabledUsers(): IOResult[Set[String]]
}

object JdbcTotpRepository {

  given Read[TotpSecret]  = Read[String].map(TotpSecret(_))
  given Write[TotpSecret] = Write[String].contramap(_.exposeSecret())
  given Read[Totp]        = Read.derived
  given Write[Totp]       = Write.derived

  def getByUserIdSQL(userId: String): Query0[(String, Totp)] =
    sql"SELECT user_id, secret, created_at FROM userstotp WHERE user_id = ${userId}".query[(String, Totp)]

  def upsertQuerySQL(userId: String, totp: Totp): Update0 =
    sql"INSERT INTO userstotp (user_id, secret, created_at) VALUES (${userId}, ${totp}) ON CONFLICT (user_id) DO UPDATE SET secret=EXCLUDED.secret, created_at=EXCLUDED.created_at".update

  def deleteQuerySQL(userId: String): Update0 =
    sql"DELETE FROM userstotp WHERE user_id=${userId}".update

  def getAllByUserSQL(): Query0[String] =
    sql"SELECT user_id FROM userstotp".query[String]
}

class JdbcTotpRepository(doobie: Doobie) extends TotpRepository {
  import JdbcTotpRepository.*
  import doobie.*

  def getByUserId(userId: String): IOResult[Option[Totp]] = {
    transactIOResult(s"Error when getting TOTP for user ${userId}")(xa =>
      getByUserIdSQL(userId).option.map(_.map(_._2)).transact(xa)
    )
  }

  def create(userId: String, totp: Totp): IOResult[Unit] = {
    transactIOResult(s"Error when creating TOTP for user ${userId}")(
      upsertQuerySQL(userId, totp).run.transact(_).unit
    )
  }

  def delete(userId: String): IOResult[Unit] = {
    transactIOResult(s"Error deleting TOTP for user ${userId}")(
      deleteQuerySQL(userId).run.transact(_).unit
    )
  }

  def getEnabledUsers(): IOResult[Set[String]] = {
    transactIOResult("Error when getting enabled TOTP users")(
      getAllByUserSQL().to[List].map(rows => rows.toSet).transact(_)
    )
  }
}
