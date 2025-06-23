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
import com.normation.rudder.db.DBCommon
import com.normation.rudder.db.json.implicits.*
import com.normation.zio.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.scalaland.chimney.*
import io.scalaland.chimney.syntax.*
import java.time.*
import java.time.format.DateTimeFormatter
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
      state match {
        case Json.Obj(pairs) =>
          pairs.collectFirst { case (k, Json.Str(v)) if k == "reason" => Some(Json.Obj(Chunk(("reason", Json.Str(v))))) }.flatten
        case _               =>
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
    OldCampaignEvent("77d4cb38","7cca021a", "testing time #2" , """{"value": "skipped", "reason": "An error occurred"}          """.json, "2024-03-05 10:59:00+00".date, "2024-03-05 11:59:00+00".date, "system-update"),
    OldCampaignEvent("77d4cb38","a97ccca1", "testing time #7" , """{"value": "skipped", "reason": "User skipped campaign event"}""".json, "2024-03-26 10:59:00+00".date, "2024-03-26 11:59:00+00".date, "system-update"),
    OldCampaignEvent("1c2fceec","7aab5d52", "test1 #2"        , """{"value": "finished"}                                        """.json, "2024-04-01 10:00:00+00".date, "2024-04-01 16:00:00+00".date, "software-update"),
    OldCampaignEvent("840494c4","eab80401", "rearezarezr #3"  , """{"value": "running"}                                         """.json, "2024-04-01 10:00:00+00".date, "2024-04-01 16:00:00+00".date, "system-update"),
    OldCampaignEvent("77d4cb38","c34cf085", "testing time #8" , """{"value": "finished"}                                        """.json, "2024-04-02 09:59:00+00".date, "2024-04-02 10:59:00+00".date, "system-update"),
    OldCampaignEvent("77d4cb38","af039286", "testing time #9" , """{"value": "skipped"}                                         """.json, "2024-04-09 09:59:00+00".date, "2024-04-09 10:59:00+00".date, "system-update"),
    OldCampaignEvent("f2f68d18","4e615889", "test badge #2"   , """{"value": "scheduled"}                                       """.json, "2024-07-10 13:20:00+00".date, "2024-07-10 19:20:00+00".date, "software-update"),
 )
  // format: on

  // expected after migration
  private val newData = oldData.map(_.transformInto[NewCampaignEvent])
  private val history = oldData.map(_.transformInto[EventHistory])

  private val initDataSql = Update[OldCampaignEvent]("""
    INSERT INTO CampaignEvents (campaignId, eventid, name, state, startDate, endDate, campaignType)
    VALUES (?, ?, ?, ?, ?, ?, ?)""").updateMany(oldData)

  override def initDb(): Unit = {
    super.initDb()
    doobie
      .transactRunEither(xa => {
        (for {
          _ <- previousSchemaDDL.update.run
          _ <- initDataSql
        } yield ()).transact(xa)
      })
      .left
      .map(throw _)
  }

  sequential

  "MigrateTableCampaignEvents" should {

    "data are correctly initialized" in {

      val sql = sql"""SELECT count(*) FROM CampaignEvents"""

      doobie.transactRunEither(sql.query[Int].unique.transact(_)) must beRight(beEqualTo(oldData.size))

    }

    "correctly execute step1 and step2" in {

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

    "correctly execute step3" in {
      val step3 = (
        transactIOResult(s"step3")(xa => MigrateTableCampaignEvents.sql3.update.run.transact(xa))
      ).either.runNow

      val sql = sql"""SELECT eventid, state, startDate, endDate, data from CampaignEventsStateHistory"""

      // we should have details for all skipped
      step3 must beRight

      doobie.transactRunEither(sql.query[EventHistory].to[Seq].transact(_)) must beRight(
        containTheSameElementsAs(history)
      )
    }
  }
}

//    "migrate all columns successfully" in {
//      migrateChangeValidation.migrateAsync.runNow.join.runNow
//
//      doobie.transactRunEither(
//        (sql"""
//          SELECT DISTINCT column_name, is_nullable
//          FROM INFORMATION_SCHEMA.COLUMNS
//        """ ++ whereOr(
//          and(fr"table_name = ${tempCRTableName}", fr"column_name = 'content'"),
//          and(fr"table_name = ${tempWorkflowTableName}", fr"column_name = 'id'"),
//          and(fr"table_name = ${tempWorkflowTableName}", fr"column_name = 'state'")
//        ))
//          .query[(String, String)]
//          .to[List]
//          .transact(_)
//      ) match {
//        case Right(res) =>
//          res must containTheSameElementsAs(
//            List(
//              ("content", "NO"),
//              ("id", "NO"),
//              ("state", "NO")
//            )
//          )
//        case Left(ex)   =>
//          ko(
//            s"The migration of 'ChangeRequest' and 'Workflow' tables does not add NOT NULL constraint to all columns with error : ${ex.getMessage}"
//          )
//      }
//    }
//
//    "catch all migration errors" in {
//      lazy val migrateChangeValidation =
//        new MigrateChangeValidationEnforceSchemaTempTable(doobie, Some(ZIO.fail(new Exception("some database error"))))
//
//      Try(migrateChangeValidation.migrateAsync.runNow.join.runNow) match {
//        case Failure(_)  => ko("The parent program should not fail even if the fiber failed")
//        case Success(()) => ok("The parent program continued to run despite the migration fiber error")
//      }
//

//object TestMigrateTableCampaignEvents {
//  private val tempCRTableName       = "changerequest_migratetest"
//  private val tempCRTable           = fragment.Fragment.const(tempCRTableName)
//  private val tempWorkflowTableName = "workflow_migratetest"
//  private val tempWorkflowTable     = fragment.Fragment.const(tempWorkflowTableName)
//
//  private class MigrateChangeValidationEnforceSchemaTempTable(doobie: Doobie, overrideEffect: Option[Task[Unit]] = None)
//      extends MigrateChangeValidationEnforceSchema(doobie) {
//    override def changeRequestTableName:                         String     = tempCRTableName
//    override def workflowTableName:                              String     = tempWorkflowTableName
//    override def migrationEffect(implicit xa: Transactor[Task]): Task[Unit] = {
//      val default = super.migrationEffect(xa)
//      overrideEffect.getOrElse(default)
//    }
//  }
//
//  val sqlMigration = {
//    """
//      | CREATE TYPE campaignEventState AS enum ('scheduled', 'prehooks', 'running', 'posthooks', 'finished', 'skipped');
//      | ALTER TABLE campaignevents RENAME state TO stateJson;
//      | ALTER TABLE campaignevents ADD COLUMN state campaigneventstate;
//      | UPDATE campaignevents SET state = (stateJson ->> 'value')::campaigneventstate;
//      | ALTER TABLE campaignevents UPDATE COLUMN state NOT NULL;
//      |
//      | DROP INDEX event_state_index ;
//      |
//      | INSERT INTO campaigneventsstatehistory (eventid, state, startdate, enddate, data)
//      |   SELECT
//      |     c.eventid,
//      |     c.state,
//      |     c.startdate,
//      |     c.enddate,
//      |     (SELECT json_build_object('reason', c.statejson ->> 'reason')::json WHERE c.state = 'skipped') as js
//      |   FROM campaignevents c;
//      |
//      | delete column stateJson
//      |
//      |
//      |
//      |""".stripMargin
//  }
//
//}
