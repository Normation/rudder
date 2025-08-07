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

import com.normation.rudder.db.DBCommon
import com.normation.rudder.db.Doobie
import com.normation.zio.UnsafeRun
import doobie.Transactor
import doobie.specs2.analysisspec.IOChecker
import doobie.syntax.all.*
import doobie.util.fragment
import doobie.util.fragments.and
import doobie.util.fragments.whereOr
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import org.specs2.specification.core.Fragments
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import zio.*
import zio.interop.catz.*

@RunWith(classOf[JUnitRunner])
class TestMigrateChangeValidationEnforceSchema extends DBCommon with IOChecker {
  import TestMigrateChangeValidationEnforceSchema.*

  private lazy val migrateChangeValidation = new MigrateChangeValidationEnforceSchemaTempTable(doobie)

  override def transactor: Transactor[cats.effect.IO] = doobie.xaio

  // The previous schema, with the renamed table for this test
  // We need to know for sure the initial state and the final state of the migrated table,
  // so we define and use the previous schema without conflicting with the current one (with all values renamed)
  private val previousSchemaDDL = sql"""
    CREATE SEQUENCE IF NOT EXISTS ChangeRequestId_temp start 1;

    CREATE TABLE ${tempCRTable} (
      id             integer PRIMARY KEY DEFAULT nextval('ChangeRequestId_temp')
    , name           text CHECK (name <> '')
    , description    text
    , creationTime   timestamp with time zone
    , content        xml
    , modificationId text
    );

    CREATE TABLE ${tempWorkflowTable} (
      id    integer REFERENCES ${tempCRTable} (id)
    , state text
    );
  """

  override def initDb(): Unit = {
    super.initDb()
    doobie.transactRunEither(previousSchemaDDL.update.run.transact(_)) match {
      case Right(_) => ()
      case Left(ex) => throw ex
    }
  }

  sequential

  "MigrateChangeValidationEnforceSchema" should {

    "type-check queries" in {
      if (doDatabaseConnection) {
        check(migrateChangeValidation.alterTableStatement(tempCRTable, fr"content"))
        check(migrateChangeValidation.alterTableStatement(tempWorkflowTable, fr"id"))
        check(migrateChangeValidation.alterTableStatement(tempWorkflowTable, fr"state"))
      } else Fragments.empty
    }

    "migrate all columns successfully" in {
      migrateChangeValidation.migrateAsync.runNow.join.runNow

      doobie.transactRunEither(
        (sql"""
          SELECT DISTINCT column_name, is_nullable
          FROM INFORMATION_SCHEMA.COLUMNS
        """ ++ whereOr(
          and(fr"table_name = ${tempCRTableName}", fr"column_name = 'content'"),
          and(fr"table_name = ${tempWorkflowTableName}", fr"column_name = 'id'"),
          and(fr"table_name = ${tempWorkflowTableName}", fr"column_name = 'state'")
        ))
          .query[(String, String)]
          .to[List]
          .transact(_)
      ) match {
        case Right(res) =>
          res must containTheSameElementsAs(
            List(
              ("content", "NO"),
              ("id", "NO"),
              ("state", "NO")
            )
          )
        case Left(ex)   =>
          ko(
            s"The migration of 'ChangeRequest' and 'Workflow' tables does not add NOT NULL constraint to all columns with error : ${ex.getMessage}"
          )
      }
    }

    "catch all migration errors" in {
      lazy val migrateChangeValidation =
        new MigrateChangeValidationEnforceSchemaTempTable(doobie, Some(ZIO.fail(new Exception("some database error"))))

      Try(migrateChangeValidation.migrateAsync.runNow.join.runNow) match {
        case Failure(_)  => ko("The parent program should not fail even if the fiber failed")
        case Success(()) => ok("The parent program continued to run despite the migration fiber error")
      }

    }
  }
}

object TestMigrateChangeValidationEnforceSchema {
  private val tempCRTableName       = "changerequest_migratetest"
  private val tempCRTable           = fragment.Fragment.const(tempCRTableName)
  private val tempWorkflowTableName = "workflow_migratetest"
  private val tempWorkflowTable     = fragment.Fragment.const(tempWorkflowTableName)

  private class MigrateChangeValidationEnforceSchemaTempTable(doobie: Doobie, overrideEffect: Option[Task[Unit]] = None)
      extends MigrateChangeValidationEnforceSchema(doobie) {
    override def changeRequestTableName:                         String     = tempCRTableName
    override def workflowTableName:                              String     = tempWorkflowTableName
    override def migrationEffect(implicit xa: Transactor[Task]): Task[Unit] = {
      val default = super.migrationEffect
      overrideEffect.getOrElse(default)
    }
  }

}
