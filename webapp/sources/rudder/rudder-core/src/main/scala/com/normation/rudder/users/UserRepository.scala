package com.normation.rudder.users

/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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

import cats.data.NonEmptyList
import com.normation.errors.IOResult
import com.normation.rudder.db.Doobie
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.utils.DateFormaterService
import com.softwaremill.quicklens._
import doobie._
import doobie.free.connection.{unit => connectionUnit}
import doobie.implicits._
import doobie.postgres.implicits._
import org.joda.time.DateTime
import zio._
import zio.interop.catz._
import zio.json.ast._

/*
 * Repository that deals with users and sessions
 */

trait UserRepository {

  /*
   * Create a session for given user, authenticated by given authenticator (ie the backend that authenticated
   * the user).
   */
  def logStartSession(
      userId:            String,
      permissions:       List[String],
      tenants:           String, // this is the serialisation of tenants (ie nodePerms.value)
      sessionId:         SessionId,
      authenticatorName: String,
      date:              DateTime
  ): IOResult[Unit]

  def logCloseSession(userId: String, date: DateTime, cause: String): IOResult[Unit]

  /*
   * Close all opened sessions (typically for when rudder restart)
   */
  def closeAllOpenSession(endDate: DateTime, endCause: String): IOResult[Unit]

  /*
   * Get the last previous session for user
   * (it the last closed session)
   */
  def getLastPreviousLogin(userId: String): IOResult[Option[UserSession]]

  /*
   * Delete session that are older than the given date
   */
  def deleteOldSessions(olderThan: DateTime): IOResult[Unit]

  /*
   * Get users created by the given authenticator
   */

  /*
   * Update active users created by given authenticator. Other users for that authenticator will be marked "deleted".
   * - non existing user will be created,
   * - deleted user will be set-back to active.
   * (locked won't be touched).
   * For user whose status is changed, also add the status update in its history.
   * User from other authenticators are not changed.
   */
  def setExistingUsers(origin: String, users: List[String], trace: EventTrace): IOResult[Unit]

  /*
   * Disable users based on filter.
   *
   * Only active users are affected by that method
   *
   * User with status "disabled" get an error message on login attempt that informs the users of that status.
   *
   * Filter targeted users based on:
   * - ID. If the list is empty, only the other filter will be used. Undefined userId are ignored
   * - based on date since last login (or creation date if never logged).
   * - notOrigin: exclude user deleting for user not on that origin (typically file and rootUser).
   *              This is a post-filter, meaning that if no users are selected in the first two and this one is
   *              empty then none user are deleted (and not all users)
   *
   * Returns the list of impacted userIds
   */
  def disable(
      userId:            List[String],
      notLoggedSince:    Option[DateTime],
      excludeFromOrigin: List[String],
      trace:             EventTrace
  ): IOResult[List[String]]

  /*
   * Delete users based on filter.
   *
   * Only active or disabled users are affected by that method
   *
   * After that operation, the user is unknown from other part of Rudder.
   * If an user with the same ID is added again, then it will retrieve user
   * information. You then need to be careful when using that operation and not "purge", or
   * at least be careful on the addition part (perhaps create the resurrected user as "disabled" in that
   * case).
   *
   * Filter targeted users based on:
   * - ID. If the list is empty, only the other filter will be used. Undefined userId are ignored
   * - based on date since last login (or creation date if never logged).
   * - notOrigin: exclude user deleting for user not on that origin (typically file and rootUser).
   *              This is a post-filter, meaning that if no users are selected in the first two and this one is
   *              empty then none user are deleted (and not all users)
   *
   * Returns the list of impacted userIds
   */
  def delete(
      userId:            List[String],
      notLoggedSince:    Option[DateTime],
      excludeFromOrigin: List[String],
      trace:             EventTrace
  ): IOResult[List[String]]

  /*
   * totally delete from persistent storage users. After that operation, adding the user will
   * create a pristine user.
   *
   * Chosen users can be restricted by:
   * - userIds. If the list is empty, that filter will be ignored. If an user does not exists, it's a no-op.
   * - deletedSince: if that option is set, then only deleted user will be purged, and only if deletion is older than
   *                 given date.
   * - excludeFromOrigin: exclude user deleting for user not on that origin (typically file and rootUser).
   *                      This is a post-filter, meaning that if no users are selected in the first two and this one is
   *                      empty then none user are deleted (and not all users)
   *
   * Since the goal of that operation is to clean-up user information, action history about that user will be
   * deleted too. An external log should trace that information about that user were purged (with trace info)
   *
   * Returns the list of impacted userIds
   */
  def purge(
      userId:            List[String],
      deletedSince:      Option[DateTime],
      excludeFromOrigin: List[String],
      trace:             EventTrace
  ): IOResult[List[String]]

  /*
   * Set given user to "active", changing its `deleted` or `disabled` status.
   * If the user does not exist, it's an error.
   */
  def setActive(userId: List[String], trace: EventTrace): IOResult[Unit]

  def updateInfo(
      id:        String,
      name:      Option[Option[String]],
      email:     Option[Option[String]],
      otherInfo: Option[Json.Obj]
  ): IOResult[Unit]

  /*
   * Retrieve all users (will likely need filters)
   */
  def getAll(): IOResult[List[UserInfo]]

  def get(userId: String): IOResult[Option[UserInfo]]
}

object UserRepository {

  /*
   * Compute the list of update user info (and only them) from:
   * - a list of active user from given origin,
   * - the list of zombies from that list, ie know users with deleted status, but in the activeUsers list,
   * - the list of non-deleted managed users from that origin
   */
  def computeUpdatedUserList(
      activeUsers: List[String],
      origin:      String,
      trace:       EventTrace,
      zombies:     Map[String, UserInfo],
      managed:     Map[String, UserInfo]
  ): Map[String, UserInfo] = {
    val allUpdatable = zombies.keySet ++ managed.keySet

    // the real new ones are neither zombies nor managed by that origin, bootstrap them.
    val realNew = activeUsers.collect {
      case k if (!allUpdatable.contains(k)) =>
        (
          k,
          UserInfo(
            k,
            trace.actionDate,
            UserStatus.Active,
            origin,
            None,
            None,
            None,
            List(StatusHistory(UserStatus.Active, trace)),
            Json.Obj()
          )
        )
    }.toMap

    // `resurrected` are zombie that get back to live with the new origin.
    // We resurrect users with "active" status because we have nothing to manage "disabled" for now.
    val resurrected = zombies.map {
      case (k, v) =>
        // check that status is really deleted, just in case
        if (v.status == UserStatus.Deleted) {
          (
            k,
            v.modify(_.status)
              .setTo(UserStatus.Active)
              .modify(_.statusHistory)
              .using(StatusHistory(UserStatus.Disabled, trace) :: _)
              .modify(_.managedBy)
              .setTo(origin)
          )
        } else (k, v)
    }

    // deleted users are currently managed, with status not deleted, not in the list anymore
    val deleted = managed.collect {
      case (k, v) if (v.status != UserStatus.Deleted && !activeUsers.contains(k)) =>
        (
          k,
          v.modify(_.status)
            .setTo(UserStatus.Deleted)
            .modify(_.statusHistory)
            .using(StatusHistory(UserStatus.Deleted, trace) :: _)
        )
    }

    realNew ++ resurrected ++ deleted
  }

}

object InMemoryUserRepository {
  def make(): ZIO[Any, Nothing, InMemoryUserRepository] = {
    for {
      users    <- Ref.make(Map[String, UserInfo]())
      sessions <- Ref.make(List[UserSession]())
    } yield {
      new InMemoryUserRepository(users, sessions)
    }
  }
}

class InMemoryUserRepository(userBase: Ref[Map[String, UserInfo]], sessionBase: Ref[List[UserSession]]) extends UserRepository {

  override def logStartSession(
      userId:            String,
      permissions:       List[String],
      tenants:           String,
      sessionId:         SessionId,
      authenticatorName: String,
      date:              DateTime
  ): IOResult[Unit] = {
    sessionBase.update(UserSession(userId, sessionId, date, authenticatorName, permissions.sorted, tenants, None, None) :: _) *>
    userBase.update(_.map { case (k, v) => if (k == userId) (k, v.modify(_.lastLogin).setTo(Some(date))) else (k, v) })
  }

  private def closeSession(userSession: UserSession, endDate: DateTime, endCause: String): UserSession = {
    userSession.modify(_.endDate).setTo(Some(endDate)).modify(_.endCause).setTo(Some(endCause))
  }

  override def closeAllOpenSession(endDate: DateTime, endCause: String): IOResult[Unit] = {
    sessionBase.update(_.map(s => if (s.endDate.isEmpty) closeSession(s, endDate, endCause) else s))
  }

  override def logCloseSession(userId: String, date: DateTime, cause: String): IOResult[Unit] = {
    sessionBase.update(_.map(s => if (s.userId == userId) closeSession(s, date, cause) else s))
  }

  override def getLastPreviousLogin(userId: String): IOResult[Option[UserSession]] = {
    // sessions are sorted oldest first, for find is ok
    sessionBase.get.map(_.find(s => s.userId == userId && s.endDate.isDefined))
  }

  override def deleteOldSessions(olderThan: DateTime): IOResult[Unit] = {
    sessionBase.update(_.collect { case session if (session.creationDate.isAfter(olderThan)) => session })
  }

  override def setExistingUsers(origin: String, users: List[String], trace: EventTrace): IOResult[Unit] = {
    /*
     * We need to modify only user:
     * - with status deleted and in the list of given users
     * - or with status active and same origin and not in the list
     */

    userBase.update { current =>
      val zombies = current.filter {
        case (k, v) =>
          (v.status == UserStatus.Deleted && users.contains(k))
      }
      val managed = current.filter {
        case (k, v) =>
          (v.managedBy == origin && v.status != UserStatus.Deleted)
      }

      current ++ UserRepository.computeUpdatedUserList(users, origin, trace, zombies, managed)
    }
  }

  override def getAll(): IOResult[List[UserInfo]] = {
    userBase.get.map(_.values.toList)
  }

  // predicates to purge user, ie return true if the user is to be purged
  private def predicateUser(u: UserInfo, ids: List[String]) = ids.contains(u.id)

  private def predicateLogDate(u: UserInfo, date: Option[DateTime]) = {
    (date, u.lastLogin) match {
      case (None, _)                 => false
      case (Some(limit), None)       => u.creationDate.isBefore(limit)
      case (Some(limit), Some(last)) => last.isBefore(limit)
    }
  }

  private def predicateDeletedSince(u: UserInfo, date: Option[DateTime]) = {
    u.statusHistory match { // only deleted get purged with that filter
      case StatusHistory(UserStatus.Deleted, EventTrace(_, d, _)) :: _ =>
        date match {
          case None        => false
          case Some(limit) => d.isBefore(limit)
        }
      case _                                                           =>
        false
    }
  }

  // this one is inverted
  private def predicateNotOrigin(u: UserInfo, origin: List[String]) = !origin.contains(u.managedBy)

  override def disable(
      userIds:           List[String],
      notLoggedSince:    Option[DateTime],
      excludeFromOrigin: List[String],
      trace:             EventTrace
  ): IOResult[List[String]] = {
    userBase.modify { users =>
      var modUserIds = List.empty[String]
      val m          = users.map {
        case (k, u) =>
          if (
            (predicateUser(u, userIds) || predicateLogDate(
              u,
              notLoggedSince
            )) && u.status == UserStatus.Active && predicateNotOrigin(u, excludeFromOrigin)
          ) {
            modUserIds = k :: modUserIds
            (
              k,
              u.modify(_.status)
                .setTo(UserStatus.Disabled)
                .modify(_.statusHistory)
                .using(StatusHistory(UserStatus.Disabled, trace) :: _)
            )
          } else {
            (k, u)
          }
      }
      (modUserIds, m)
    }
  }

  override def delete(
      userIds:           List[String],
      notLoggedSince:    Option[DateTime],
      excludeFromOrigin: List[String],
      trace:             EventTrace
  ): IOResult[List[String]] = {
    userBase.modify { users =>
      var modUserIds = List.empty[String]
      val m          = users.map {
        case (k, u) =>
          if (
            (predicateUser(u, userIds) || predicateLogDate(
              u,
              notLoggedSince
            )) && u.status != UserStatus.Deleted && predicateNotOrigin(u, excludeFromOrigin)
          ) {
            modUserIds = k :: modUserIds
            (
              k,
              u.modify(_.status)
                .setTo(UserStatus.Deleted)
                .modify(_.statusHistory)
                .using(StatusHistory(UserStatus.Deleted, trace) :: _)
            )
          } else {
            (k, u)
          }
      }
      (modUserIds, m)
    }
  }

  override def purge(
      userIds:           List[String],
      deletedSince:      Option[DateTime],
      excludeFromOrigin: List[String],
      trace:             EventTrace
  ): IOResult[List[String]] = {

    userBase.modify { users =>
      val initIds = users.keySet
      val m       = users.filterNot {
        case (_, u) =>
          (predicateUser(u, userIds) || predicateDeletedSince(u, deletedSince)) && predicateNotOrigin(u, excludeFromOrigin)
      }
      ((initIds -- m.keySet).toList, m)
    }
  }

  override def setActive(userId: List[String], trace: EventTrace): IOResult[Unit] = {
    userBase.update {
      _.map {
        case (k, u) =>
          if (userId.contains(u.id) && u.status == UserStatus.Disabled) {
            (
              k,
              u.modify(_.status)
                .setTo(UserStatus.Active)
                .modify(_.statusHistory)
                .using(StatusHistory(UserStatus.Active, trace) :: _)
            )
          } else {
            (k, u)
          }
      }
    }
  }

  override def get(userId: String): IOResult[Option[UserInfo]] = {
    userBase.get.map(_.get(userId))
  }

  override def updateInfo(
      id:        String,
      name:      Option[Option[String]],
      email:     Option[Option[String]],
      otherInfo: Option[Json.Obj]
  ): IOResult[Unit] = {
    userBase.update(users => {
      users.get(id) match {
        case None    => users
        case Some(u) =>
          users + (id -> u
            .modify(_.name)
            .setToIfDefined(name)
            .modify(_.email)
            .setToIfDefined(email)
            .modify(_.otherInfo)
            .setToIfDefined(otherInfo))
      }
    })
  }
}

/*
 * Postgresql storage of users. There is two tables, one for user, one for session.
 * Users:
 * id             text PRIMARY KEY NOT NULL CHECK (id <> '')
 * creationDate   timestamp with time zone NOT NULL
 * status         text NOT NULL
 * managedBy   text NOT NULL CHECK (managedBy <> '')
 * name           text
 * email          text
 * lastLogin      timestamp with time zone
 * statusHistory  jsonb
 * otherInfo      jsonb -- general additional user info
 *
 * UserSessions:
 *   userId       text NOT NULL CHECK (nodeId <> '')
 *   creationDate timestamp with time zone NOT NULL
 *   authMethod   text
 *   permissions  text[]
 *   tenants      text
 *   endDate      timestamp with time zone
 *   endCause     text
 */
class JdbcUserRepository(doobie: Doobie) extends UserRepository {
  import com.normation.rudder.db.Doobie.DateTimeMeta
  import com.normation.rudder.db.json.implicits._
  import com.normation.rudder.users.UserSerialization._
  import doobie._

  implicit val statusHistoryMeta: Meta[List[StatusHistory]] = new Meta(pgDecoderGet, pgEncoderPut)
  implicit val otherInfoMeta:     Meta[Json.Obj]            = new Meta(pgDecoderGet, pgEncoderPut)

  implicit val userWrite: Write[UserInfo] = {
    Write[
      (String, DateTime, String, String, Option[String], Option[String], Option[DateTime], List[StatusHistory], Json.Obj)
    ].contramap {
      case u =>
        (u.id, u.creationDate, u.status.value, u.managedBy, u.name, u.email, u.lastLogin, u.statusHistory, u.otherInfo)
    }
  }

  implicit val userRead: Read[UserInfo] = {
    Read[
      (String, DateTime, String, String, Option[String], Option[String], Option[DateTime], List[StatusHistory], Json.Obj)
    ].map {
      (u: (
          (
              String,
              DateTime,
              String,
              String,
              Option[String],
              Option[String],
              Option[DateTime],
              List[StatusHistory],
              Json.Obj
          )
      )) =>
        UserInfo(
          u._1,
          u._2,
          UserStatus.parse(u._3).toOption.getOrElse(UserStatus.Disabled),
          u._4,
          u._5,
          u._6,
          u._7,
          u._8,
          u._9
        )
    }
  }

  // when we create a session for an user, we also update his "lastLogin" tom
  override def logStartSession(
      userId:            String,
      permissions:       List[String],
      tenants:           String,
      sessionId:         SessionId,
      authenticatorName: String,
      date:              DateTime
  ): IOResult[Unit] = {
    val session   = {
      sql"""insert into usersessions (sessionid, userid, creationdate, authmethod, permissions, tenants)
            values (${sessionId}, ${userId}, ${date}, ${authenticatorName}, ${permissions.sorted}, ${tenants})"""
    }
    val lastLogin = {
      sql"""update users set lastlogin = ${date} where id = ${userId}"""
    }

    transactIOResult(s"Error when saving session '${sessionId.value}' info for user '${userId}'")(xa => {
      (for {
        _ <- session.update.run
        _ <- lastLogin.update.run
      } yield ()).transact(xa)
    }).unit
  }

  override def logCloseSession(userId: String, endDate: DateTime, endCause: String): IOResult[Unit] = {
    val sql =
      sql"""update usersessions set enddate = ${endDate}, endcause = ${endCause} where enddate is null and userid = ${userId}"""
    transactIOResult(s"Error when closing opened session for user '${userId}'")(xa => sql.update.run.transact(xa)).unit
  }

  override def closeAllOpenSession(endDate: DateTime, endCause: String): IOResult[Unit] = {
    val sql = sql"""update usersessions set enddate = ${endDate}, endcause = ${endCause} where enddate is null returning userid"""
    transactIOResult(s"Error when closing opened user session")(xa => sql.query[String].to[List].transact(xa)).flatMap(users =>
      ApplicationLoggerPure.User.info(s"Close open sessions with reason '${endCause}' for users '${users.mkString("', '")}'")
    )
  }

  override def getLastPreviousLogin(userId: String): IOResult[Option[UserSession]] = {
    val sql =
      sql"""select * from usersessions where userid = ${userId} and enddate is not null order by creationdate desc limit 1"""

    transactIOResult(s"Error when retrieving information for previous session for '${userId}'")(xa =>
      sql.query[UserSession].option.transact(xa)
    )
  }

  override def deleteOldSessions(olderThan: DateTime): IOResult[Unit] = {
    val sql = sql"""delete from usersessuins where creationdate < ${olderThan}"""

    transactIOResult(s"Error when purging user sessions older then: ${DateFormaterService.serialize(olderThan)}")(xa =>
      sql.update.run.transact(xa)
    ).flatMap(deletedSessionCount => {
      ApplicationLoggerPure.User.info(
        s"${deletedSessionCount} user sessions older than ${DateFormaterService.serialize(olderThan)} were deleted"
      )
    })
  }

  override def setExistingUsers(origin: String, users: List[String], trace: EventTrace): IOResult[Unit] = {
    def toMap(us: Iterable[UserInfo]) = us.map(u => (u.id, u)).toMap

    users match {
      case Nil    => ZIO.unit
      case h :: t =>
        // get managed (all from that origin) and zombies (in the given list of existing, but deleted)
        val updatable = sql"""select * from users where managedby = ${origin} or (status = 'deleted' and """ ++
          Fragments.in(fr"id", NonEmptyList.of(h, t: _*)) ++
          fr")"

        def update(updated: Vector[UserInfo]): ConnectionIO[Int] = {
          val sql =
            """insert into users (id, creationdate, status, managedby, name, email, lastlogin, statushistory, otherinfo)
                  values (?,?,?,?,? , ?,?,?,?)
               on conflict (id) do update
                set (creationdate, status, managedby, name, email, lastlogin, statushistory, otherinfo) =
                  (EXCLUDED.creationdate, EXCLUDED.status, EXCLUDED.managedby, EXCLUDED.name, EXCLUDED.email, EXCLUDED.lastlogin, EXCLUDED.statushistory, EXCLUDED.otherinfo)"""

          Update[UserInfo](sql).updateMany(updated)
        }

        transactIOResult("Erreur when updating the list of") { xa =>
          (for {
            maybeUpdate       <- updatable.query[UserInfo].to[Vector]
            (zombies, managed) = maybeUpdate.partition(_.status == UserStatus.Deleted)
            updated            = UserRepository.computeUpdatedUserList(users, origin, trace, toMap(zombies), toMap(managed))
            _                 <- update(updated.values.toVector)
          } yield ()).transact(xa)
        }
    }
  }

  // predicate used as condition for selecting users for both delete/disable and purge
  private[users] def predicateUser(userIds: List[String]): Option[Fragment] = userIds match {
    case Nil    => None
    case h :: t => Some(Fragments.in(fr"id", NonEmptyList.of(h, t: _*)))
  }

  private[users] def predicateLogDate(lastLogin: Option[DateTime]): Option[Fragment] = lastLogin match {
    case None        => None
    case Some(limit) => // look for lastlogin and if null (coalesce) creationdate
      Some(fr"COALESCE(lastlogin, creationdate) < ${limit}")
  }

  // this one is inverted: origin empty means you can always purge (but only preselected user from other filters)
  private[users] def predicateNotOrigin(origins: List[String]): Option[Fragment] = origins match {
    case Nil    => None
    case h :: t => Some(Fragments.notIn(fr"managedby", NonEmptyList.of(h, t: _*)))
  }

  // select users ID and StatusHistory based on previous predicate and possibly further refinements.
  // if both userIds and notLoggedSince are empty/None, select all or none depending of defaultToNone value
  private[users] def select(
      userIds:           List[String],
      notLoggedSince:    Option[DateTime],
      defaultToNone:     Boolean,         // if both userIds = Nil and notLoggedSince = None, if true return no user else all matching other params
      excludeFromOrigin: List[String],
      andSelectFragment: Option[Fragment] // refined selection
  ): ConnectionIO[List[(String, List[StatusHistory])]] = {
    val selectUsers = (predicateUser(userIds), predicateLogDate(notLoggedSince)) match {
      case (Some(a), Some(b)) => Some(Fragments.or(a, b))
      case (Some(a), None)    => Some(a)
      case (None, Some(b))    => Some(b)
      case (None, None)       => None
    }

    if (selectUsers.isEmpty && defaultToNone) {
      connectionUnit.map(_ => Nil)
    } else {
      val f: Fragment = Fragments.whereAndOpt(
        selectUsers,
        predicateNotOrigin(excludeFromOrigin),
        andSelectFragment
      )

      (sql"select id, statushistory from users " ++ f).query[(String, List[StatusHistory])].to[List]
    }
  }

  // change status of an user
  private[users] def changeStatus(
      userIds:           List[String],
      notLoggedSince:    Option[DateTime],
      excludeFromOrigin: List[String],
      trace:             EventTrace,
      targetStatus:      UserStatus,
      andSelectFragment: Option[Fragment] // refined selection
  ): IOResult[List[String]] = {

    // in order of ?: status, history, id
    def update(newStatus: List[(String, List[StatusHistory], String)]) = {
      Update[(String, List[StatusHistory], String)](
        """update users set (status, statushistory) = (?, ?) where id = ?"""
      )
        .updateMany(newStatus)
    }

    ApplicationLoggerPure.User.trace(
      s"Request to change status of users '${userIds.mkString("','")}' " +
      s"to ${targetStatus.value} with criteria 'notLoggedSince': '${notLoggedSince}'; " +
      s"'excludeFromOrigin': '${excludeFromOrigin.mkString(", ")}'; 'andSelectFragment': '${andSelectFragment}''"
    ) *>
    transactIOResult(s"Error when changing status to '${targetStatus.value}' for users with ids: ${userIds.mkString(", ")}") {
      xa =>
        (
          for {
            selected <- select(userIds, notLoggedSince, true, excludeFromOrigin, andSelectFragment)
            updated   = selected.map {
                          case (id, hist) =>
                            (
                              targetStatus.value,
                              StatusHistory(
                                targetStatus,
                                trace
                              ) :: hist,
                              id
                            )
                        }
            commit   <- update(updated)
          } yield (selected.map(_._1))
        ).transact(xa).tap(users => ApplicationLoggerPure.User.trace(s"Updated users: '${users.mkString("', ")}'"))
    }
  }

  override def delete(
      userIds:           List[String],
      notLoggedSince:    Option[DateTime],
      excludeFromOrigin: List[String],
      trace:             EventTrace
  ): IOResult[List[String]] = {
    changeStatus(
      userIds,
      notLoggedSince,
      excludeFromOrigin,
      trace,
      UserStatus.Deleted,
      Some(Fragment.const(s"status != '${UserStatus.Deleted.value}'"))
    )
  }

  override def disable(
      userIds:           List[String],
      notLoggedSince:    Option[DateTime],
      excludeFromOrigin: List[String],
      trace:             EventTrace
  ): IOResult[List[String]] = {
    changeStatus(
      userIds,
      notLoggedSince,
      excludeFromOrigin,
      trace,
      UserStatus.Disabled,
      Some(Fragment.const(s"status = '${UserStatus.Active.value}'"))
    )
  }

  override def purge(
      userIds:           List[String],
      deletedSince:      Option[DateTime],
      excludeFromOrigin: List[String],
      trace:             EventTrace
  ): IOResult[List[String]] = {

    transactIOResult(
      s"Error when purging users with condition: [ids in: ${userIds.mkString(", ")} ; " +
      s"deleted since: ${deletedSince.map(DateFormaterService.serialize(_)).getOrElse("ignored")} ; " +
      s"exclude backends: ${excludeFromOrigin.mkString(", ")} "
    )(xa => {
      (for {
        // we need to give a date here, else select won't select anyone
        selected <-
          select(userIds, None, false, excludeFromOrigin, Some(Fragment.const(s"status = '${UserStatus.Deleted.value}'")))
        toDelete  = deletedSince match {
                      case None        => selected.map(_._1)
                      case Some(limit) =>
                        selected.collect {
                          case (id, StatusHistory(UserStatus.Deleted, trace) :: t) if (trace.actionDate.isBefore(limit)) => id
                        }
                    }
        _        <- Update[String]("delete from users where id = ?").updateMany(toDelete)
      } yield toDelete).transact(xa)
    })
  }

  // only disabled user can be set back to active
  override def setActive(userId: List[String], trace: EventTrace): IOResult[Unit] = {
    changeStatus(userId, None, Nil, trace, UserStatus.Active, Some(fr"status = '${UserStatus.Disabled.value}'")).unit
  }

  override def getAll(): IOResult[List[UserInfo]] = {
    val sql = sql"""select * from users"""

    transactIOResult(s"Error when retrieving user information")(xa => sql.query[UserInfo].to[List].transact(xa))
  }

  override def get(userId: String): IOResult[Option[UserInfo]] = {
    val sql = sql"""select * from users where id = ${userId}"""

    transactIOResult(s"Error when retrieving user information for '${userId}'")(xa => sql.query[UserInfo].option.transact(xa))
  }

  override def updateInfo(
      id:        String,
      name:      Option[Option[String]],
      email:     Option[Option[String]],
      otherInfo: Option[Json.Obj]
  ): IOResult[Unit] = {
    val params = {
      List(
        name.map((x => fr"name = ${x}")),
        email.map((x => fr"email = ${x}")),
        otherInfo.map((x => fr"otherinfo = ${x}"))
      ).flatten
    }
    params match {
      case Nil       => ZIO.unit
      case h :: tail =>
        val sql = sql"""update users""" ++ Fragments.set(h, tail: _*) ++ fr"""where id = ${id}"""

        transactIOResult(s"Error when updating user information for '${id}'")(xa => sql.update.run.transact(xa)).unit
    }
  }
}
