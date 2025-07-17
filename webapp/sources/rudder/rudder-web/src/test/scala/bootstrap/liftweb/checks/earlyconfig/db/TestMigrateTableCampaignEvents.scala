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

package bootstrap.liftweb.checks.earlyconfig.db

import cats.*
import cats.implicits.*
import com.normation.rudder.campaigns.*
import com.normation.rudder.db.DBCommon
import com.normation.rudder.db.json.implicits.*
import com.normation.utils.DateFormaterService
import com.normation.zio.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.scalaland.chimney.*
import io.scalaland.chimney.syntax.*
import java.time.*
import java.time.format.DateTimeFormatter
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import zio.*
import zio.interop.catz.*
import zio.json.*
import zio.json.ast.*

@RunWith(classOf[JUnitRunner])
class TestMigrateTableCampaignEvents extends DBCommon {
  import doobie.*

//  private lazy val migrate = new MigrateTableCampaignEvents(doobie)

  // The previous schema, with the renamed table for this test
  // We need to know for sure the initial state and the final state of the migrated table,
  // so we define and use the previous schema without conflicting with the current one (with all values renamed)
  private val previousSchemaDDL = sql"""
    DROP TABLE CampaignEventsStateHistory;
    DROP TABLE CampaignEvents;
    DROP TYPE campaignEventState;

    CREATE TABLE CampaignEvents (
      campaignId   text
    , eventid      text PRIMARY KEY
    , name         text
    , state        jsonb
    , startDate    timestamp with time zone NOT NULL
    , endDate      timestamp with time zone NOT NULL
    , campaignType text
    );

    CREATE INDEX event_state_index ON CampaignEvents ((state->>'value'));
  """

  // some data to check that the migration actually works

  case class OldCampaignEvent(
      campaignId:   String,
      eventId:      String,
      name:         String,
      state:        Json,
      start:        Instant,
      end:          Instant,
      campaignType: String
  ) {
    def getStateName: String = {
      def err = new RuntimeException(s"can't find state name in: ${state.toJson}")
      state match {
        case Json.Obj(pairs) => pairs.collectFirst { case (k, Json.Str(v)) if k == "value" => v }.getOrElse(throw err)
        case _               => throw err
      }
    }

    def getData: Option[Json] = {
      // when we don't have a correct pair, we say that reason is the empty message
      val REASON = "reason"
      def toJson(k: String, v: String) = Some(Json.Obj(Chunk((REASON, Json.Str(v)))))

      state match {
        case Json.Obj(pairs) =>
          pairs.collectFirst {
            case (k, v) if k == REASON =>
              v match {
                case Json.Str(s) => toJson(REASON, s)
                case _           => toJson(REASON, "")
              }
          }.flatten

        case _ =>
          None
      }
    }

  }

  object OldCampaignEvent {
    implicit val transformOldCampaignEvent: Transformer[OldCampaignEvent, NewCampaignEvent] = {
      Transformer
        .define[OldCampaignEvent, NewCampaignEvent]
        .withFieldComputed(_.state, _.getStateName)
        .buildTransformer
    }

    implicit val transformToHistory: Transformer[OldCampaignEvent, EventHistory] = {
      Transformer
        .define[OldCampaignEvent, EventHistory]
        .withFieldComputed(_.state, _.getStateName)
        .withFieldComputed(_.data, _.getData)
        .buildTransformer
    }
  }

  // a simple test version for new events
  case class NewCampaignEvent(
      campaignId:   String,
      eventId:      String,
      name:         String,
      state:        String,
      start:        Instant,
      end:          Instant,
      campaignType: String
  )

  case class EventHistory(
      eventId: String,
      state:   String,
      start:   Instant,
      end:     Instant,
      data:    Option[Json]
  )

  implicit private class ToDate(s: String) {
    private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssX")

    def date: Instant = {
      // parse postgresql format: 2024-04-02 09:59:00+00
      OffsetDateTime.parse(s, format).toInstant
    }

    def dateJT: DateTime = {
      DateFormaterService.toDateTime(date)
    }
  }

  implicit private class ToJson(s: String) {
    def json: Json = {
      s.fromJson[Json] match {
        case Left(err) => throw new RuntimeException(s"error in json test data, parsing: \n${s}\nerror: ${err}")
        case Right(x)  => x
      }
    }
  }

  // format: off
  private val oldData = Vector(
    // this one is special and need to become a failure
    OldCampaignEvent("77d4cb38","7cca021a", "testing time #2" , """{"value": "skipped", "reason": "An error occurred when processing event: inconsistency in migration"}""".json, "2024-03-05 10:59:00+00".date, "2024-03-05 11:59:00+00".date, "system-update"),
    OldCampaignEvent("77d4cb38","a97ccca1", "testing time #7" , """{"value": "skipped", "reason": "User skipped campaign event"}""".json, "2024-03-26 10:59:00+00".date, "2024-03-26 11:59:00+00".date, "system-update"),
    OldCampaignEvent("1c2fceec","7aab5d52", "test1 #2"        , """{"value": "finished"}                                        """.json, "2024-04-01 10:00:00+00".date, "2024-04-01 16:00:00+00".date, "software-update"),
    OldCampaignEvent("840494c4","eab80401", "rearezarezr #3"  , """{"value": "running"}                                         """.json, "2024-04-01 10:00:00+00".date, "2024-04-01 16:00:00+00".date, "system-update"),
    OldCampaignEvent("77d4cb38","c34cf085", "testing time #8" , """{"value": "finished"}                                        """.json, "2024-04-02 09:59:00+00".date, "2024-04-02 10:59:00+00".date, "system-update"),
    OldCampaignEvent("77d4cb38","af039286", "testing time #9" , """{"value": "skipped", "reason": null}                         """.json, "2024-04-09 09:59:00+00".date, "2024-04-09 10:59:00+00".date, "system-update"),
    OldCampaignEvent("f2f68d18","4e615889", "test badge #2"   , """{"value": "scheduled"}                                       """.json, "2024-07-10 13:20:00+00".date, "2024-07-10 19:20:00+00".date, "software-update"),
 )
  // format: on

  // expected after migration
  private val newData = oldData.map(_.transformInto[NewCampaignEvent])
  private val history = oldData.map(_.transformInto[EventHistory])

  private val initDataOldSql = Update[OldCampaignEvent]("""
    INSERT INTO CampaignEvents (campaignId, eventid, name, state, startDate, endDate, campaignType)
    VALUES (?, ?, ?, ?, ?, ?, ?)""").updateMany(oldData)

  override def initDb(): Unit = {
    super.initDb()
    doobie
      .transactRunEither(xa => {
        (for {
          _ <- previousSchemaDDL.update.run
          _ <- initDataOldSql
        } yield ()).transact(xa)
      })
      .left
      .map(throw _)
  }

  private lazy val repo = new CampaignEventRepositoryImpl(doobie, new CampaignSerializer())

  sequential

  "MigrateTableCampaignEvents" should {

    "data are correctly initialized" in {

      val sql = sql"""SELECT count(*) FROM CampaignEvents"""

      doobie.transactRunEither(sql.query[Int].unique.transact(_)) must beRight(beEqualTo(oldData.size))

    }

    "correctly execute step1 and step2 - update state column to new enum type" in {

      val step12 = (
        transactIOResult(s"step1")(xa => MigrateTableCampaignEvents.sql1.update.run.transact(xa)) *>
          transactIOResult(s"step2")(xa => MigrateTableCampaignEvents.sql2.update.run.transact(xa))
      ).either.runNow

      // get back events as new one
      val sql = sql"""SELECT campaignId, eventid, name, state, startDate, endDate, campaignType from CampaignEvents"""

      step12 must beRight

      doobie.transactRunEither(sql.query[NewCampaignEvent].to[Seq].transact(_)) must beRight(
        containTheSameElementsAs(newData)
      )
    }

    "correctly execute step3 - create history table and migrate data" in {
      val step3 = (
        transactIOResult(s"step3")(xa => MigrateTableCampaignEvents.sql3.update.run.transact(xa))
      ).either.runNow

      // we should have details for all skipped
      step3 must beRight

      val sql1 = sql"""SELECT eventid, state, startDate, endDate, data from CampaignEventsStateHistory"""

      doobie.transactRunEither(sql1.query[EventHistory].to[Seq].transact(_)) must beRight(
        containTheSameElementsAs(history)
      )

      // jsonstate column was deleted
      val sql2 = sql"""SELECT jsontate from CampaignEvents"""
      val res  = doobie.transactRunEither(sql2.query[EventHistory].to[Seq].transact(_))

      // for some reason, the "matching" matcher does not work here
      res must beLeft
    }

    "correctly execute step4 - skipped to failure" in {
      val step4 = (
        transactIOResult(s"step4")(xa => MigrateTableCampaignEvents.sql4.update.run.transact(xa))
      ).either.runNow

      step4 must beRight

      // event with id 7cca021a must be a failure with the correct messages
      val sql1 = sql"""SELECT state FROM CampaignEvents WHERE eventid = '7cca021a'"""
      doobie.transactRunEither(sql1.query[String].option.transact(_)) must beRight(
        beSome(
          CampaignEventStateType.TFailure.entryName
        )
      )

      val sql2 = sql"""SELECT state, data FROM CampaignEventsStateHistory  WHERE eventid = '7cca021a'"""
      doobie.transactRunEither(sql2.query[(String, Json)].option.transact(_)) must beRight(
        beSome(
          (
            CampaignEventStateType.TFailure.entryName,
            Json.Obj(
              "cause"   -> Json.Str("An error occurred when processing event"),
              "message" -> Json.Str("An error occurred when processing event: inconsistency in migration")
            )
          )
        )
      )
    }
  }

  "Repository on new table" should {

    "be able to do simple get" in {
      repo.get(CampaignEventId("7aab5d52")).either.runNow must beRight(
        beSome(
          CampaignEvent(
            CampaignEventId("7aab5d52"),
            CampaignId("1c2fceec"),
            "test1 #2",
            CampaignEventState.Finished,
            "2024-04-01 10:00:00+00".dateJT,
            "2024-04-01 16:00:00+00".dateJT,
            CampaignType("software-update")
          )
        )
      )
    }

    "be able to do by criteria search" in {
      repo
        .getWithCriteria(
          CampaignEventStateType.TFinished :: CampaignEventStateType.TFailure :: Nil,
          CampaignType("system-update") :: Nil,
          None,
          Some(5),
          None,
          afterDate = Some("2024-03-01 10:00:00+00".dateJT),
          beforeDate = Some("2024-05-01 10:00:00+00".dateJT),
          Some(CampaignSortOrder.StartDate),
          Some(CampaignSortDirection.Desc)
        )
        .either
        .runNow
        .map(_.map(_.id)) must beRight(
        beEqualTo(
          List(
            CampaignEventId("c34cf085"),
            CampaignEventId("7cca021a")
          )
        )
      )
    }

    "save and retrieve several time a new event with different states" in {
      val e = CampaignEvent(
        CampaignEventId("ba43ca8b"),
        CampaignId("1c2fceec"),
        "test new #1",
        CampaignEventState.Scheduled,
        "2024-04-01 10:00:00+00".dateJT,
        "2024-04-01 16:00:00+00".dateJT,
        CampaignType("software-update")
      )

      val s = e.copy(
        state = CampaignEventState.Skipped("user asked to skip event"),
        start = "2024-04-02 10:00:00+00".dateJT,
        end = "2024-04-02 10:00:00+00".dateJT
      )

      val res = (for {
        _  <- repo.saveCampaignEvent(e)
        e1 <- repo.get(e.id)
        _  <- repo.saveCampaignEvent(s)
        e2 <- repo.get(e.id)
      } yield (e1, e2)).either.runNow

      res must beRight(beEqualTo((Some(e), Some(s))))
    }

    // I'm not at all sure that's the semantic we want.
    // Here, we say that a state is only reachable one time, ie that the progression
    // in the state graph is linear without loop. So we "can't" have an update
    // to an existing state, only the end data can change.
    // Another semantic would be that a state identity is (eventId, state type, start date) so
    // that we can reach several times each state. If it's needed, the SQL schema
    // will need to be changed for a new key definition.
    "update an existing event with different states" in {

      val e = CampaignEvent(
        CampaignEventId("a97ccca1"),
        CampaignId("77d4cb38"),
        "testing time #7",
        CampaignEventState.Skipped("User skipped campaign event"),
        "2024-03-26 10:59:00+00".dateJT,
        "2024-03-26 11:59:00+00".dateJT,
        CampaignType("system-update")
      )

      val s = e.copy(
        state = CampaignEventState.Skipped("user asked to skip event"),
        start = "2024-04-02 10:00:00+00".dateJT,
        end = "2024-04-02 10:00:00+00".dateJT
      )

      val expectedUpdate = s.copy(state = e.state) // keep state

      val res = (for {
        _  <- repo.saveCampaignEvent(e)
        e1 <- repo.get(e.id)
        _  <- repo.saveCampaignEvent(s)
        e2 <- repo.get(e.id)
      } yield (e1, e2)).either.runNow

      res must beRight(beEqualTo((Some(e), Some(expectedUpdate))))
    }

    "deleting an event works" in {
      val id = CampaignEventId("a97ccca1")

      val res = (for {
        _ <- repo.deleteEvent(Some(id))
        e <- repo.get(id)
      } yield e).either.runNow

      res must beRight(beNone)
    }
  }
}
