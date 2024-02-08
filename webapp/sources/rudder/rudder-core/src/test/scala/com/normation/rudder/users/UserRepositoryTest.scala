package com.normation.rudder.users

import com.normation.errors
import com.normation.eventlog.EventActor
import com.normation.rudder.db.DBCommon
import com.normation.rudder.db.Doobie
import com.normation.zio._
import com.softwaremill.quicklens._
import net.liftweb.common.Loggable
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.annotation.nowarn
import zio.json.ast.Json

@RunWith(classOf[JUnitRunner])
class InMemoryUserRepositoryTest extends UserRepositoryTest {
  override def doobie: Doobie = null
}

// this one use postgres and will run only with -Dtest.postgres="true"
@RunWith(classOf[JUnitRunner])
class JdbcUserRepositoryTest extends UserRepositoryTest with DBCommon {
  override def doJdbcTest = doDatabaseConnection

  // format: off
  org.slf4j.LoggerFactory.getLogger("sql").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.TRACE)
  org.slf4j.LoggerFactory.getLogger("application.user").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.TRACE)
  // format: on

  override def afterAll(): Unit = {
    cleanDb()
  }
}

@nowarn("msg=a type was inferred to be `Any`; this may indicate a programming error.")
trait UserRepositoryTest extends Specification with Loggable {
  // either test in memory or postgresql depending on what is configured
  // When asking for pg test, we need a pg connection & all
  def doobie: Doobie
  def doJdbcTest = false

  // to compare UserInfo with ==, we need to have everything in UTC.
  implicit class ForceTimeUTC(users: List[UserInfo]) {
    def toUTC: List[UserInfo] = users.map(u => {
      u.modify(_.creationDate)
        .using(_.toDateTime(DateTimeZone.UTC))
        .modify(_.statusHistory)
        .using(hs => hs.map(h => h.modify(_.trace.actionDate).using(_.toDateTime(DateTimeZone.UTC))))
    })
  }

  sequential

  // for test, we imagine that we have a "test-init" plugin like the official "file" one
  val AUTH_PLUGIN_NAME_LOCAL  = "test-file"
  val AUTH_PLUGIN_NAME_REMOTE = "test-oidc"

  lazy val repo: UserRepository = if (doJdbcTest && doobie != null) {
    new JdbcUserRepository(doobie)
  } else {
    InMemoryUserRepository.make().runNow
  }

  val actor:    EventActor = EventActor("test")
  val dateInit: DateTime   = DateTime.parse("2023-09-01T01:01:01Z")

  "basic sequential operations with users" >> {
    val users = List("alice", "bob", "charlie", "mallory")

    // INIT USERS
    val traceInit     = EventTrace(actor, dateInit)
    val historyInit   = List(StatusHistory(UserStatus.Active, traceInit))
    val userInfosInit = {
      /*
       *  When users are first init:
       * - user in file are active
       * - history is just their creation
       * - since it's a first time, all user creation date is date of first load
       * - they all originate from auth plugin
       */
      users.map(u => {
        UserInfo(
          u,
          dateInit,
          UserStatus.Active,
          AUTH_PLUGIN_NAME_LOCAL,
          None,
          None,
          None,
          historyInit,
          Json.Obj()
        )
      })
    }

    // BOB REMOVED
    val userFileBobRemoved  = users.filterNot(_ == "bob")
    val dateBobRemoved      = DateTime.parse("2023-09-02T02:02:02Z")
    val traceBobRemoved     = EventTrace(actor, dateBobRemoved)
    val userInfosBobRemoved = {
      /*
       * When bob is removed (ie not in the list of user from the files):
       * - Bob is in the list, but with a status deleted and a new history line
       * - other users are not changed
       */
      userInfosInit.map {
        case u if (u.id == "bob") =>
          u.modify(_.status)
            .setTo(UserStatus.Deleted)
            .modify(_.statusHistory)
            .using(StatusHistory(UserStatus.Deleted, traceBobRemoved) :: _)

        case u => u
      }
    }

    // BOB added back from OIDC
    val dateBobOidc      = DateTime.parse("2023-09-03T03:03:03Z")
    val traceBobOidc     = EventTrace(actor, dateBobOidc)
    val dateReload       = DateTime.parse("2023-09-04T04:04:04Z")
    val traceReload      = EventTrace(actor, dateReload)
    val userInfosBobOidc = {
      /*
       * When bob is back from OIDC (ie not in the list of user from the files):
       * - Bob is in the list. In the futur, we may want to resurrect people with "disabled" state, but not until it's manageable from Rudder
       * - and a new history line exists (keeping the old ones - it's the same bob)
       * - other users are not changed
       */
      userInfosBobRemoved.map {
        case u if (u.id == "bob") =>
          u.modify(_.status)
            .setTo(UserStatus.Active)
            .modify(_.statusHistory)
            .using(StatusHistory(UserStatus.Disabled, traceBobOidc) :: _)
            .modify(_.managedBy)
            .setTo(AUTH_PLUGIN_NAME_REMOTE)

        case u => u
      }
    }

    // BOB deleted, then purged and added back in OIDC
    val dateBob2      = DateTime.parse("2023-09-05T05:05:05Z")
    val traceBob2     = EventTrace(actor, dateBob2)
    val userInfosBob2 = {
      /*
       * When bob is purged and added back from OIDC (ie not in the list of user from the files):
       * - Bob is in the list, but with a status "active"
       * - history lines only contains the creation one
       * - other users are not changed
       */
      userInfosBobOidc.map {
        case u if (u.id == "bob") =>
          UserInfo(
            u.id,
            dateBob2,
            UserStatus.Active,
            AUTH_PLUGIN_NAME_REMOTE,
            None,
            None,
            None,
            StatusHistory(UserStatus.Active, traceBob2) :: Nil,
            Json.Obj()
          )

        case u => u
      }
    }

    "Creating users in an empty repo should create them all activated" >> {

      // not sure if we should have an empty json for otherInfo
      repo.setExistingUsers(AUTH_PLUGIN_NAME_LOCAL, users, traceInit).runNow
      repo.getAll().runNow.toUTC must containTheSameElementsAs(userInfosInit)
    }

    "If an user is removed from list, it is marked as 'deleted' (but node erased)" >> {
      repo.setExistingUsers(AUTH_PLUGIN_NAME_LOCAL, userFileBobRemoved, traceBobRemoved).runNow
      repo.getAll().runNow.toUTC must containTheSameElementsAs(userInfosBobRemoved)
    }

    "If an user is created by an other module and file reloaded, it's Active and remains so" >> {
      repo.setExistingUsers(AUTH_PLUGIN_NAME_REMOTE, List("bob"), traceBobOidc).runNow
      repo.setExistingUsers(AUTH_PLUGIN_NAME_LOCAL, userFileBobRemoved, traceReload).runNow
      repo.getAll().runNow.toUTC must containTheSameElementsAs(userInfosBobOidc)
    }
    "If an user is purged, then everything about it is lost and it is created fresh" >> {
      // we only purge deleted users
      (repo.delete(List("bob"), None, Nil, traceBobOidc) *>
      repo.purge(List("bob"), None, Nil, traceBobOidc)).runNow
      repo.setExistingUsers(AUTH_PLUGIN_NAME_REMOTE, List("bob"), traceBob2).runNow
      repo.getAll().runNow.toUTC must containTheSameElementsAs(userInfosBob2)
    }
  }

  "testing purge" >> {

    val date1 = DateTime.parse("2021-01-01T01:01:01Z")
    val date2 = DateTime.parse("2022-02-02T02:02:02Z")
    val date3 = DateTime.parse("2023-03-03T03:03:03Z")
    val date4 = DateTime.parse("2024-04-04T04:04:04Z")
    val date5 = DateTime.parse("2025-05-05T05:05:05Z")

    "deleting+purging all users not logged in since a date in the future should remove all users" >> {
      // set delete trace event to "dateInit" to make it simpler to know when the "deleted before" must be set to
      (
        repo
          .delete(Nil, Some(dateInit.plusYears(1)), Nil, EventTrace(actor, dateInit))
          .flatMap(users => errors.effectUioUnit(logger.debug(s"Users were marked deleted: ${users}"))) *>
        errors.effectUioUnit(println("**** delete done")) *>
        repo
          .purge(Nil, Some(dateInit.plusSeconds(1)), Nil, EventTrace(actor, DateTime.now()))
          .flatMap(users => errors.effectUioUnit(logger.debug(s"Users were purged: ${users}")))
        *> errors.effectUioUnit(println("**** purge done"))
      ).runNow

      repo.getAll().runNow must beEmpty
    }

    "add back users with different properties" >> {
      (
        // Alice logged but not since date2
        repo.setExistingUsers(AUTH_PLUGIN_NAME_LOCAL, List("alice"), EventTrace(actor, date1)) *>
        repo.logStartSession("alice", List("role1"), "all", SessionId("sessionAlice1"), AUTH_PLUGIN_NAME_LOCAL, date2) *>
        // Bob created at date1 but never logged since
        repo.setExistingUsers(AUTH_PLUGIN_NAME_LOCAL, List("alice", "bob"), EventTrace(actor, date1)) *>
        // same for Charlie from OIDC
        repo.setExistingUsers(AUTH_PLUGIN_NAME_REMOTE, List("charlie"), EventTrace(actor, date1)) *>
        // David created on date1 and logged recently
        repo.setExistingUsers(AUTH_PLUGIN_NAME_LOCAL, List("alice", "bob", "david"), EventTrace(actor, date1)) *>
        repo.logStartSession("david", List("role1"), "all", SessionId("sessionDavid1"), AUTH_PLUGIN_NAME_LOCAL, date4)
      ).runNow

      val users = repo.getAll().runNow
      (users.map(_.id) must containTheSameElementsAs(List("alice", "bob", "charlie", "david"))) and
      (users.map(_.status) must forall(beEqualTo(UserStatus.Active)))
    }

    "disable all before date4 only not local must only disable charlie (set disable to date2)" >> {
      repo.disable(Nil, Some(date4), List(AUTH_PLUGIN_NAME_LOCAL), EventTrace(actor, date2)).runNow
      val users = repo.getAll().runNow.toUTC
      (users.map(_.id) must containTheSameElementsAs(List("alice", "bob", "charlie", "david"))) and
      (users.filter(_.status == UserStatus.Disabled).map(_.id) must containTheSameElementsAs(List("charlie"))) and
      (users.collectFirst { case u if (u.id == "charlie") => u.statusHistory.head } must beEqualTo(
        Some(StatusHistory(UserStatus.Disabled, EventTrace(actor, date2, "")))
      ))
    }

    "delete all before date4 only not local must only delete charlie (set delete to date2)" >> {
      repo.delete(Nil, Some(date4), List(AUTH_PLUGIN_NAME_LOCAL), EventTrace(actor, date2)).runNow
      val users = repo.getAll().runNow.toUTC
      (users.map(_.id) must containTheSameElementsAs(List("alice", "bob", "charlie", "david"))) and
      (users.filter(_.status == UserStatus.Deleted).size === 1) and
      (
        users.collectFirst { case u if (u.id == "charlie") => u.statusHistory.head } must beEqualTo(
          Some(StatusHistory(UserStatus.Deleted, EventTrace(actor, date2, "")))
        )
      )
    }

    "delete all before date4 only not local must only remove charlie" >> {
      repo.purge(Nil, Some(date4), List(AUTH_PLUGIN_NAME_LOCAL), EventTrace(actor, date5)).runNow
      repo.getAll().runNow.map(_.id) must containTheSameElementsAs(List("alice", "bob", "david"))
    }

    "delete+purge all before date4 must also remove alice, bob" >> {
      (repo.delete(Nil, Some(date4), Nil, EventTrace(actor, date3)) *>
      repo.purge(Nil, Some(date4), Nil, EventTrace(actor, date5))).runNow
      repo.getAll().runNow.map(_.id) must containTheSameElementsAs(List("david"))
    }
  }
}
