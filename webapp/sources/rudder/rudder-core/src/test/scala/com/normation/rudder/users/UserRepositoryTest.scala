package com.normation.rudder.users

import com.normation.errors
import com.normation.eventlog.EventActor
import com.normation.rudder.db.DBCommon
import com.normation.rudder.db.Doobie
import com.normation.zio.*
import com.softwaremill.quicklens.*
import net.liftweb.common.Loggable
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.annotation.nowarn
import zio.json.ast.Json

@RunWith(classOf[JUnitRunner])
class UserRepositoryUtilsTest extends Specification {

  "UserRepositoryTest computeUpdatedUserList" should {
    val actor    = EventActor("test")
    val dateInit = DateTime.parse("2023-09-01T01:01:01Z")
    val date2    = DateTime.parse("2023-09-02T02:02:02Z")

    val users = List("alice", "bob", "charlie", "mallory")

    // INIT USERS
    val traceInit               = EventTrace(actor, dateInit)
    val historyInit             = List(StatusHistory(UserStatus.Active, traceInit))
    val trace2                  = EventTrace(actor, date2)
    val AUTH_PLUGIN_NAME_LOCAL  = "test-file"
    val AUTH_PLUGIN_NAME_REMOTE = "test-oidc"
    val userInfosInit           = {
      /*
       *  When users are first init:
       * - user in file are active
       * - history is just their creation
       * - since it's a first time, all user creation date is date of first load
       * - they all originate from auth plugin
       */
      users
        .map(u => {
          u -> UserInfo(
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
        .toMap
    }

    // update status history for a new user with the date of the trace
    // this method may be useful because computeUpdatedUserList always return a user using the trace date
    def updateInitUserWithTrace(name: String, trace: EventTrace): UserInfo = {
      userInfosInit(name)
        .modify(_.creationDate)
        .setTo(trace.actionDate)
        .modify(_.statusHistory)
        // default initial status. For now the computeUpdatedUserList method does not append the trace
        .setTo(StatusHistory(UserStatus.Active, trace) :: Nil)
    }

    "returns initial users when passed with empty arguments" in {
      val expected = {
        Map(
          "alice"   -> updateInitUserWithTrace("alice", trace2),
          "mallory" -> updateInitUserWithTrace("mallory", trace2),
          "bob"     -> updateInitUserWithTrace("bob", trace2),
          "charlie" -> updateInitUserWithTrace("charlie", trace2)
        )
      }
      UserRepository.computeUpdatedUserList(
        users,
        AUTH_PLUGIN_NAME_LOCAL,
        trace = EventTrace(actor, date2),
        zombies = Map.empty,
        managed = Map.empty
      ) must be equalTo (expected)

    }

    val bobZombie = userInfosInit("bob")
      .modify(_.status)
      .setTo(UserStatus.Deleted)
      .modify(_.statusHistory)
      .using(
        StatusHistory(UserStatus.Deleted, trace2) :: _
      )

    "returns same users when zombies are from the same origin" in {
      val activeUsers = List("alice", "mallory", "charlie")
      val expected    = Map(
        "alice"   -> updateInitUserWithTrace("alice", trace2),
        "mallory" -> updateInitUserWithTrace("mallory", trace2),
        "bob"     -> bobZombie,
        "charlie" -> updateInitUserWithTrace("charlie", trace2)
      )

      UserRepository.computeUpdatedUserList(
        activeUsers,
        AUTH_PLUGIN_NAME_LOCAL,
        trace = EventTrace(actor, date2),
        zombies = Map("bob" -> bobZombie),
        managed = Map.empty
      ) must be equalTo (expected)
    }

    "returns updated users when zombies is resurrected with a different origin" in {
      val bobRevived = userInfosInit("bob")
        .modify(_.status)
        .setTo(UserStatus.Active)
        .modify(_.managedBy)
        .setTo(AUTH_PLUGIN_NAME_REMOTE)
        .modify(_.statusHistory)
        .using(StatusHistory(UserStatus.Disabled, trace2) :: _)
      val expected   = Map(
        "bob" -> bobRevived
      )

      UserRepository.computeUpdatedUserList(
        List("bob"),
        AUTH_PLUGIN_NAME_REMOTE,
        trace = trace2,
        zombies = Map("bob" -> bobRevived),
        managed = Map.empty
      ) must be equalTo (expected)
    }

    "returns updated users when user is added back to active users list, from the same origin and after having been deleted" in {
      val activeUsers = List("alice", "bob", "charlie", "mallory")

      val bobRevived = bobZombie
        .modify(_.status)
        .setTo(UserStatus.Active)
        .modify(_.statusHistory)
        .using(StatusHistory(UserStatus.Active, trace2) :: _)

      val expected = Map(
        "alice"   -> updateInitUserWithTrace("alice", trace2),
        "mallory" -> updateInitUserWithTrace("mallory", trace2),
        "bob"     -> bobRevived,
        "charlie" -> updateInitUserWithTrace("charlie", trace2)
      )

      UserRepository.computeUpdatedUserList(
        activeUsers,
        AUTH_PLUGIN_NAME_LOCAL,
        trace = EventTrace(actor, date2),
        zombies = Map("bob" -> bobZombie),
        managed = Map.empty
      ) must be equalTo (expected)
    }

    "returns updated users when managed and not in the active users should be deleted" in {
      val activeUsers = List("alice", "charlie", "mallory")

      val bobManaged = userInfosInit("bob")
        .modify(_.status)
        .setTo(UserStatus.Deleted)
        .modify(_.statusHistory)
        .using(StatusHistory(UserStatus.Deleted, trace2) :: _)

      val expected = Map(
        "alice"   -> updateInitUserWithTrace("alice", trace2),
        "mallory" -> updateInitUserWithTrace("mallory", trace2),
        "bob"     -> bobManaged,
        "charlie" -> updateInitUserWithTrace("charlie", trace2)
      )

      UserRepository.computeUpdatedUserList(
        activeUsers,
        AUTH_PLUGIN_NAME_LOCAL,
        trace = EventTrace(actor, date2),
        zombies = Map.empty,
        managed = Map("bob" -> userInfosInit("bob"))
      ) must be equalTo (expected)
    }
  }
}

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
        .modify(_.lastLogin)
        .using(_.map(_.toDateTime(DateTimeZone.UTC)))
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

    // BOB is added again to the active users and is 'active'
    val dateBobReactivated      = DateTime.parse("2023-09-02T02:03:03Z")
    val traceBobReactivated     = EventTrace(actor, dateBobReactivated)
    val userInfosBobReactivated = {
      /*
       * When bob is added back (ie in the list of user from the files):
       * - Bob is in the list, but with a status "active"
       * - history lines only contains the creation one
       * - other users are not changed
       */
      userInfosBobRemoved.map {
        case u if (u.id == "bob") =>
          u.modify(_.status)
            .setTo(UserStatus.Active)
            .modify(_.statusHistory)
            .using(StatusHistory(UserStatus.Active, traceBobReactivated) :: _)

        case u => u
      }
    }

    // BOB is kept removed after setting the users again without BOB
    val dateBobRemovedIdempotent      = DateTime.parse("2023-09-02T02:03:04Z")
    val traceBobRemovedIdempotent     = EventTrace(actor, dateBobRemovedIdempotent)
    val userInfosBobRemovedIdempotent = {
      /*
       * When bob is removed again (ie not in the list of user from the files):
       * - Bob is in the list, but with a status deleted and a new history line
       * - other users are not changed
       */
      userInfosBobReactivated.map {
        case u if (u.id == "bob") =>
          u.modify(_.status)
            .setTo(UserStatus.Deleted)
            .modify(_.statusHistory)
            .using(StatusHistory(UserStatus.Deleted, traceBobRemovedIdempotent) :: _)

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
      userInfosBobRemovedIdempotent.map {
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

    // William is added from OIDC
    val dateWilliamOidc      = DateTime.parse("2023-09-05T05:05:05Z")
    val traceWilliamOidc     = EventTrace(actor, dateWilliamOidc)
    val userInfosWilliamOidc = {
      // Alice is added along with Bob
      userInfosBobOidc :+ UserInfo(
        "william",
        dateWilliamOidc,
        UserStatus.Active,
        AUTH_PLUGIN_NAME_REMOTE,
        None,
        None,
        None,
        StatusHistory(UserStatus.Active, traceWilliamOidc) :: Nil,
        Json.Obj()
      )
    }

    // All OIDC users deleted
    val dateOidcDeleted      = DateTime.parse("2023-09-06T06:06:06Z")
    val traceOidcDeleted     = EventTrace(actor, dateOidcDeleted)
    val userInfosOidcDeleted = {
      userInfosWilliamOidc.map {
        case u if u.id == "bob" || u.id == "william" =>
          u.modify(_.status)
            .setTo(UserStatus.Deleted)
            .modify(_.statusHistory)
            .using(StatusHistory(UserStatus.Deleted, traceOidcDeleted) :: _)

        case u => u
      }
    }

    // BOB deleted, then purged and added back in OIDC
    val dateBobPurged      = DateTime.parse("2023-09-07T07:07:07Z")
    val traceBobPurged     = EventTrace(actor, dateBobPurged)
    val userInfosBobPurged = {
      /*
       * When bob is purged and added back from OIDC (ie not in the list of user from the files):
       * - Bob is in the list, but with a status "active"
       * - history lines only contains the creation one
       * - other users are not changed
       */
      userInfosOidcDeleted.map {
        case u if (u.id == "bob") =>
          UserInfo(
            u.id,
            dateBobPurged,
            UserStatus.Active,
            AUTH_PLUGIN_NAME_REMOTE,
            None,
            None,
            None,
            StatusHistory(UserStatus.Active, traceBobPurged) :: Nil,
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

    "Getting user should handle case sensitivity parameter" >> {
      repo.get("alice").runNow must beSome
      repo.get("Alice").runNow must beNone
      repo.get("Alice", isCaseSensitive = false).runNow must beSome
    }
    "Setting users should handle case sensitivity parameter false value and return non-updated users" >> {
      val notUpdated = repo
        .setExistingUsers(
          AUTH_PLUGIN_NAME_LOCAL,
          users.map {
            case "alice" => "AlIcE" // the update will not occur
            case o       => o
          },
          traceInit,
          isCaseSensitive = false
        )
        .runNow
      notUpdated must containTheSameElementsAs(List("alice"))
      repo.getAll().runNow.toUTC must containTheSameElementsAs(userInfosInit)
    }
    "Setting users should handle case sensitivity parameter true value" >> {
      // even if "alice" already exist
      repo.setExistingUsers(AUTH_PLUGIN_NAME_LOCAL, "Alice" :: users, traceInit, isCaseSensitive = true).runNow
      repo.getAll().map(_.map(_.id)).runNow must containTheSameElementsAs("Alice" :: users)
    }

    "Getting user should raise an error for multiple found users" >> {
      repo.get("alice").runNow must beSome
      repo.get("Alice").runNow must beSome
      repo.get("alice", isCaseSensitive = false).either.runNow must beLeft(
        errors.Inconsistency("Multiple users found for id 'alice'")
      )
      repo.get("Alice", isCaseSensitive = false).either.runNow must beLeft(
        errors.Inconsistency("Multiple users found for id 'Alice'")
      )
      (repo.delete(List("Alice"), None, Nil, None, traceInit) *> repo.purge(
        List("Alice"),
        None,
        Nil,
        traceInit
      )).runNow must beEqualTo(
        List("Alice")
      )
    }

    "If an user is removed from list, it is marked as 'deleted' (but node erased)" >> {
      repo.setExistingUsers(AUTH_PLUGIN_NAME_LOCAL, userFileBobRemoved, traceBobRemoved).runNow
      repo.getAll().runNow.toUTC must containTheSameElementsAs(userInfosBobRemoved)
    }

    "Reactivating 'deleted' users by setting existing users" >> {
      repo.setExistingUsers(AUTH_PLUGIN_NAME_LOCAL, users, traceBobReactivated).runNow
      repo.getAll().runNow.toUTC must containTheSameElementsAs(userInfosBobReactivated)
    }

    "If an user is reloaded from the same origin, it should be kept as is" >> {
      // removing it again first, then a second time to check idempotency
      repo.setExistingUsers(AUTH_PLUGIN_NAME_LOCAL, userFileBobRemoved, traceBobRemovedIdempotent).runNow
      repo.setExistingUsers(AUTH_PLUGIN_NAME_LOCAL, userFileBobRemoved, traceBobRemovedIdempotent).runNow
      repo.getAll().runNow.toUTC must containTheSameElementsAs(userInfosBobRemovedIdempotent)
    }

    "If an user is created by an other module and file reloaded, it's Active and remains so" >> {
      repo.setExistingUsers(AUTH_PLUGIN_NAME_REMOTE, List("bob"), traceBobOidc).runNow
      repo.setExistingUsers(AUTH_PLUGIN_NAME_LOCAL, userFileBobRemoved, traceReload).runNow
      repo.getAll().runNow.toUTC must containTheSameElementsAs(userInfosBobOidc)
    }

    "If an user is also added in the other module" >> {
      repo.addUser(AUTH_PLUGIN_NAME_REMOTE, "william", traceWilliamOidc).runNow
      repo.getAll().runNow.toUTC must containTheSameElementsAs(userInfosWilliamOidc)
    }

    "If users are set to an empty list" >> {
      repo.setExistingUsers(AUTH_PLUGIN_NAME_REMOTE, List.empty, traceOidcDeleted).runNow
      repo.getAll().runNow.toUTC must containTheSameElementsAs(userInfosOidcDeleted)
    }

    "If an user is purged, then everything about it is lost and it is created fresh" >> {
      // we only purge deleted users
      (repo.delete(List("bob"), None, Nil, Some(UserStatus.Active), traceBobOidc) *>
      repo.purge(List("bob"), None, Nil, traceBobOidc)).runNow
      repo.getAll().runNow must beLike(_.map(_.id) must not(contain("bob")))

      repo.setExistingUsers(AUTH_PLUGIN_NAME_REMOTE, List("bob"), traceBobPurged).runNow
      repo.getAll().runNow.toUTC must containTheSameElementsAs(userInfosBobPurged)
    }
  }

  "testing purge" >> {

    val date1 = DateTime.parse("2021-01-01T01:01:01Z")
    val date2 = DateTime.parse("2022-02-02T02:02:02Z")
    val date3 = DateTime.parse("2023-03-03T03:03:03Z")
    val date4 = DateTime.parse("2024-04-04T04:04:04Z")
    val date5 = DateTime.parse("2025-05-05T05:05:05Z")
    val date6 = DateTime.parse("2023-09-06T06:06:06Z")

    val trace1          = EventTrace(actor, date1)
    val traceAllDeleted = EventTrace(actor, date6)

    val userInfosAllDeleted = List(
      UserInfo(
        "david",
        date1,
        UserStatus.Deleted,
        AUTH_PLUGIN_NAME_LOCAL,
        None,
        None,
        Some(date4),
        List(StatusHistory(UserStatus.Deleted, traceAllDeleted), StatusHistory(UserStatus.Active, trace1)),
        Json.Obj()
      )
    )

    "deleting+purging all users not logged in since a date in the future should remove all users" >> {
      // set delete trace event to "dateInit" to make it simpler to know when the "deleted before" must be set to
      repo
        .delete(Nil, Some(dateInit.plusYears(1)), Nil, None, EventTrace(actor, dateInit))
        .tap(users => errors.effectUioUnit(logger.debug(s"Users were marked deleted: ${users}")))
        .runNow must containTheSameElementsAs(List("alice", "charlie", "mallory", "bob")) // william is already deleted

      repo
        .purge(Nil, Some(dateInit.plusYears(1)), Nil, EventTrace(actor, DateTime.now()))
        .tap(users => errors.effectUioUnit(logger.debug(s"Users were purged: ${users}")))
        .runNow must containTheSameElementsAs(List("alice", "charlie", "mallory", "bob", "william"))

      repo.getAll().runNow must beEmpty
    }

    "add back users with different properties" >> {
      (
        // Alice logged but not since date2
        repo.setExistingUsers(AUTH_PLUGIN_NAME_LOCAL, List("alice"), EventTrace(actor, date1)) *>
        repo.logStartSession("alice", List("role1"), List.empty, "", SessionId("sessionAlice1"), AUTH_PLUGIN_NAME_LOCAL, date2) *>
        // Bob created at date1 but never logged since
        repo.setExistingUsers(AUTH_PLUGIN_NAME_LOCAL, List("alice", "bob"), EventTrace(actor, date1)) *>
        // same for Charlie from OIDC
        repo.setExistingUsers(AUTH_PLUGIN_NAME_REMOTE, List("charlie"), EventTrace(actor, date1)) *>
        // David created on date1 and logged recently
        repo.setExistingUsers(AUTH_PLUGIN_NAME_LOCAL, List("alice", "bob", "david"), EventTrace(actor, date1)) *>
        repo.logStartSession("david", List("role1"), List.empty, "", SessionId("sessionDavid1"), AUTH_PLUGIN_NAME_LOCAL, date4)
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
      repo.delete(Nil, Some(date4), List(AUTH_PLUGIN_NAME_LOCAL), None, EventTrace(actor, date2)).runNow
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
      (repo.delete(Nil, Some(date4), Nil, None, EventTrace(actor, date3)) *>
      repo.purge(Nil, Some(date4), Nil, EventTrace(actor, date5))).runNow
      repo.getAll().runNow.map(_.id) must containTheSameElementsAs(List("david"))
    }

    "delete all from setting existing users to Nil should set remaining users to 'deleted' status" >> {
      repo.setExistingUsers(AUTH_PLUGIN_NAME_LOCAL, List.empty, traceAllDeleted).runNow
      repo.getAll().runNow.toUTC must containTheSameElementsAs(userInfosAllDeleted)
    }
  }
}
