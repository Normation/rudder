/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.repository.jdbc

import java.sql.Timestamp
import org.joda.time.DateTime
import net.liftweb.common.Loggable
import javax.sql.DataSource
import slick.driver.PostgresDriver
import com.github.tminglei.slickpg._
import java.sql.BatchUpdateException
import com.normation.rudder.domain.reports.Reports


/*
 * That class defines mapping for Slick.
 * It also provides specific mapper for
 * special Postgresql types thanks to:
 * https://github.com/tminglei/slick-pg
 *
 */

/*
 * Speci
 */
trait MyPostgresDriver extends PostgresDriver
                          with PgArraySupport
                          with PgDateSupportJoda
                          with PgRangeSupport {

  override lazy val Implicit = new ImplicitsPlus {}
  override val simple = new SimpleQLPlus {}

  //////
  trait ImplicitsPlus extends Implicits
                        with ArrayImplicits
                        with DateTimeImplicits
                        with RangeImplicits

  trait SimpleQLPlus extends SimpleQL
                        with ImplicitsPlus
}

object MyPostgresDriver extends MyPostgresDriver


final case class SlickExpectedReports(
    pkId                      : Option[Int]
  , nodeJoinKey               : Int
  , ruleId                    : String
  , serial                    : Int
  , directiveId               : String
  , component                 : String
  , cardinality               : Int
  , componentsValues          : String
  , unexpandedComponentsValues: String
  , beginDate                 : DateTime
  , endDate                   : Option[DateTime]
)

final case class SlickExpectedReportsNodes(
    nodeJoinKey        : Int
  , nodeId             : String
  , nodeConfigVersions : List[String]
)

final case class SlickReports(
    id                 : Option[Long]
  , executionDate      : DateTime
  , nodeId             : String
  , directiveId        : String
  , ruleId             : String
  , serial             : Int
  , component          : String
  , keyValue           : String
  , executionTimestamp : DateTime
  , eventType          : String
  , policy             : String
  , msg                : String
)

/**
 *
 * That file contains Slick schema
 *
 */
class SlickSchema(datasource: DataSource) extends Loggable {

  import MyPostgresDriver.simple._

  val slickDB = Database.forDataSource(datasource)

  val expectedReportsTable = TableQuery[ExpectedReportsTable]
  val expectedReportsNodesTable = TableQuery[ExpectedReportsNodesTable]
  val reportsTable = TableQuery[ReportsTable]




  def slickExec[A](body: Session => A): A = {
    try {
      slickDB.withSession { s => body(s) }
    } catch {
      case e: BatchUpdateException =>
        logger.error("Error when inserting reports: " + e.getMessage)
        logger.error(e.getNextException)
        throw e
    }
  }



  class ExpectedReportsTable(tag: Tag) extends Table[SlickExpectedReports](tag, "expectedreports") {
    def pkId = column[Int]("pkid", O.PrimaryKey, O.AutoInc) // This is the primary key column
    def nodeJoinKey = column[Int]("nodejoinkey")
    def ruleId = column[String]("ruleid")
    def serial = column[Int]("serial")
    def directiveId = column[String]("directiveid")
    def component = column[String]("component")
    def cardinality = column[Int]("cardinality")
    def componentsValues = column[String]("componentsvalues")
    def unexpandedComponentsValues = column[String]("unexpandedcomponentsvalues")
    def beginDate = column[DateTime]("begindate")
    def endDate = column[DateTime]("enddate", O.Nullable)

    // Every table needs a * projection with the same type as the table's type parameter
    def * = (
        pkId.?, nodeJoinKey, ruleId, serial, directiveId, component,
        cardinality, componentsValues, unexpandedComponentsValues, beginDate, endDate.?
    ) <> (SlickExpectedReports.tupled, SlickExpectedReports.unapply)
  }

  class ExpectedReportsNodesTable(tag: Tag) extends Table[SlickExpectedReportsNodes](tag, "expectedreportsnodes") {
    def nodeJoinKey = column[Int]("nodejoinkey")
    def nodeId = column[String]("nodeid")
    def nodeConfigVersions = column[List[String]]("nodeconfigids")

    // Every table needs a * projection with the same type as the table's type parameter
    def * = (
        nodeJoinKey, nodeId, nodeConfigVersions
        ) <> (SlickExpectedReportsNodes.tupled, SlickExpectedReportsNodes.unapply)
    def pk = primaryKey("pk_expectedreportsnodes", (nodeJoinKey, nodeId))
  }


  /*
   * **************************** Reports table ****************************
   */



  class ReportsTable(tag: Tag) extends Table[SlickReports](tag, "ruddersysevents") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc) // This is the primary key column
    def executionDate = column[DateTime]("executiondate")
    def nodeId = column[String]("nodeid")
    def directiveId = column[String]("directiveid")
    def ruleId = column[String]("ruleid")
    def serial = column[Int]("serial")
    def component = column[String]("component")
    def keyValue = column[String]("keyvalue")
    def executionTimeStamp = column[DateTime]("executiontimestamp")
    def eventType = column[String]("eventtype")
    def policy = column[String]("policy")
    def msg = column[String]("msg")

    // Every table needs a * projection with the same type as the table's type parameter
    def * = (
        id.?, executionDate, nodeId, directiveId, ruleId, serial,
        component, keyValue, executionTimeStamp, eventType, policy, msg
    ) <> (SlickReports.tupled, SlickReports.unapply)
  }


  def toSlickReport(r:Reports): SlickReports = {
    SlickReports(None, r.executionDate, r.nodeId.value, r.directiveId.value, r.ruleId.value, r.serial
        , r.component, r.keyValue, r.executionTimestamp, r.severity, "policy", r.message)
  }

  def insertReports(reports: Seq[Reports]) = {
    val slickReports = reports.map(toSlickReport(_))

    slickExec { implicit s =>
      reportsTable ++= slickReports
    }
  }

}

