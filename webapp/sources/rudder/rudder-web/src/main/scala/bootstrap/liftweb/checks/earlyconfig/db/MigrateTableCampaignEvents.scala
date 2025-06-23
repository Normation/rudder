/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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

import bootstrap.liftweb.*
import com.normation.errors.IOResult
import com.normation.rudder.db.Doobie
import com.normation.zio.*
import doobie.implicits.*
import zio.interop.catz.*

/*
 * In rudder 9.0, we split CampaignEvents table in two:
 *   - CampaignEvents is only about the runtime persistence of the workflow and does not keep data
 *     => `state` change into an enum
 *   - CampaignEventsStateHistory is about keeping a log of past state and corresponding results
 *     => it get messages and etc.
 *
 * So we are migrating things like:
 * table CampaignEvents
 * campaignId| eventId   |  name             |  state                                              | startDate              | endDate                | campaignType
 * 77d4c...  | ca021a... | 'testing time #2' | {"value": "skipped", "reason": "An error occurred"} | 2024-03-05 10:59:00+00 | 2024-03-05 11:59:00+00 | 'system-update '
 *
 * Into two tables:
 * CampaignEvents
 * campaignId| eventId   |  name             |  state    | startDate              | endDate                | campaignType
 * 77d4c...  | ca021a... | 'testing time #2' | 'skipped' | 2024-03-05 10:59:00+00 | 2024-03-05 11:59:00+00 | 'system-update '
 *
 * CampaignEventsStateHistory
 * eventId   |  state     | startDate              | endDate                | data
 * ca021a... |  'skipped' | 2024-03-05 10:59:00+00 | 2024-03-05 11:59:00+00 | {"reason": "An error occurred"}
 */
class MigrateTableCampaignEvents(doobie: Doobie) extends BootstrapChecks {
  import bootstrap.liftweb.checks.earlyconfig.db.MigrateTableCampaignEvents.*
  import doobie.*

  override def description: String = "Check if campaign events state history exist"

  def createScoreTables: IOResult[Unit] = {

    // General migration logic:
    // - step1:
    //   - create an enum for state
    // - step2 (only if state is not of type enum)
    //   - move column CampaignEvents->state to stateJson
    //   - create column CampaignEvents->state of type enum and copy state value from stateJson
    // - step3 (only if table CampaignEventsStateHistory doesn't exist)
    //   - create table CampaignEventsStateHistory
    //   - copy reason messages for skipped state from CampaignEvents
    //   - delete CampaignEvents->stateJson
    // - step4 : correct skipped that are actually failures
    //   - select all eventId in history that are skipped and reason like "An error occurred when processing event%"
    //   - update both CampaignEvents and CampaignEventStateHistory table with state = failure, new message

    // they must be done in sequence, an error interrupting following statements
    transactIOResult(s"Error when creating 'campaignEventState' enumeration")(xa => sql1.update.run.transact(xa)).unit *>
    transactIOResult(s"Error when changing column 'state' of 'CampaignEvents' to 'campaignEventState'")(xa =>
      sql2.update.run.transact(xa)
    ).unit *>
    transactIOResult(s"Error when creating table 'CampaignEventsStateHistory'")(xa => sql3.update.run.transact(xa)).unit *>
    transactIOResult(s"Error when updating skipped to failure event in 'CampaignEventsStateHistory'")(xa =>
      sql4.update.run.transact(xa)
    ).unit
  }

  override def checks(): Unit = {
    val prog = {
      for {
        _ <- createScoreTables
      } yield ()
    }

    // Actually run the migration async to avoid blocking for that.
    // There is no need to have it sync.
    prog
      .catchAll(err =>
        BootstrapLogger.Early.DB.error(s"Error when trying to migrate/create CampaignEventsStateHistory table: ${err.fullMsg}")
      )
      .forkDaemon
      .runNow
  }
}

object MigrateTableCampaignEvents {

  // create new enum for campaign event state
  val sql1 = {
    sql"""DO $$$$ BEGIN
                    CREATE TYPE campaignEventState AS enum (
                      'scheduled', 'prehooks', 'running', 'posthooks', 'finished', 'skipped', 'deleted', 'failure'
                    );
                  EXCEPTION
                    WHEN duplicate_object THEN null;
                  END $$$$;"""
  }

  // migrate state to the new enum for table CampaignEvents
  // (data_type changes from 'jsonb' to 'USER-DEFINED'
  val sql2 = {
    sql"""
    DO $$$$ BEGIN
      IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'campaignevents' AND column_name = 'state' AND data_type = 'jsonb'
      ) THEN
        ALTER TABLE campaignevents RENAME state TO stateJson;
        ALTER TABLE campaignevents ADD COLUMN state campaigneventstate;
        UPDATE campaignevents SET state = (stateJson ->> 'value')::campaigneventstate;
        ALTER TABLE campaignevents ALTER COLUMN state SET NOT NULL;
        DROP INDEX IF EXISTS event_state_index;
      END IF;
    END $$$$;"""
  }

  // create the new table and fill it with data from CampaignEvents, then delete old column
  // WARNING : in pg_tables, names are lower-case and case sensitive
  val sql3 = {
    sql"""
    DO $$$$ BEGIN
      IF NOT EXISTS (
        SELECT 1 FROM pg_tables WHERE tablename  = 'campaigneventsstatehistory'
      ) THEN
        CREATE TABLE CampaignEventsStateHistory (
          eventId   text references CampaignEvents(eventId) ON DELETE CASCADE
        , state     campaignEventState
        , startDate timestamp with time zone NOT NULL
        , endDate   timestamp with time zone
        , data      jsonb
        , PRIMARY KEY (eventId, state)
        );

        INSERT INTO campaigneventsstatehistory (eventid, state, startdate, enddate, data)
        SELECT
          c.eventid,
          c.state,
          c.startdate,
          c.enddate,
          (SELECT jsonb_build_object('reason', COALESCE(c.statejson ->> 'reason', ''))::json WHERE c.state = 'skipped')
        FROM campaignevents c;

        ALTER TABLE campaignevents DROP COLUMN IF EXISTS statejson;
      END IF;
    END $$$$;"""
  }

  // Update `skipped` cases that are actually `failure` : when the message looks like that:
  //       "- into campaign of type system-update version 1"
  // Then we need to update `CampaignEvents` state to failure and `CampaignEventStateHistory` to failure with new message.

  val sql4 = {
    sql"""
    DO $$$$ BEGIN
      IF EXISTS (
        SELECT 1 FROM pg_tables WHERE tablename  = 'campaigneventsstatehistory'
      ) THEN
          WITH updatedEvents AS (
            UPDATE campaigneventsstatehistory
            SET
              state = 'failure',
              data = jsonb_build_object('cause', 'An error occurred when processing event', 'message', data ->> 'reason')::json
            WHERE data ->> 'reason' like 'An error occurred when processing event%'
            RETURNING eventid
          )
          UPDATE campaignevents
          SET
            state = 'failure'
          WHERE eventid in (SELECT eventid FROM updatedEvents);
      END IF;
    END $$$$;"""
  }

}
