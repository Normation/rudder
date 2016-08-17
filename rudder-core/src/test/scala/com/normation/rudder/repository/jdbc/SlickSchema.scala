/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.rudder.repository.jdbc

import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration

import com.github.tminglei.slickpg._
import com.normation.rudder.domain.reports.Reports

import org.joda.time.DateTime

import javax.sql.DataSource

/*
 * That class defines mapping for Slick.
 * It also provides specific mapper for
 * special Postgresql types thanks to:
 * https://github.com/tminglei/slick-pg
 *
 */

trait MyPostgresDriver extends ExPostgresDriver
                          with PgArraySupport
                          with PgRangeSupport
                          with PgHStoreSupport
                          with PgSearchSupport
                          with PgNetSupport
                          with PgLTreeSupport
                          with PgDateSupportJoda
{

  override val api = MyAPI

  object MyAPI extends API with ArrayImplicits
                           with NetImplicits
                           with LTreeImplicits
                           with RangeImplicits
                           with HStoreImplicits
                           with SearchImplicits
                           with SearchAssistants
                           with JodaDateTimeImplicits
  {
    implicit val strListTypeMapper = new SimpleArrayJdbcType[String]("text").to(_.toList)
  }
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
class SlickSchema(datasource: DataSource) {

  import MyPostgresDriver.api._

  val slickDB = Database.forDataSource(datasource)

  val expectedReportsTable: TableQuery[ExpectedReportsTable] = TableQuery[ExpectedReportsTable]
  val expectedReportsNodesTable = TableQuery[ExpectedReportsNodesTable]
  val reportsTable = TableQuery[ReportsTable]

  /**
   * A simple exec that is synchronized.
   */
  def slickExec[A](body: DBIOAction[A, NoStream, Nothing]): A = {
    val f: Future[A] = slickDB.run(body)
    Await.result(f, Duration(5, TimeUnit.SECONDS))
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
    def endDate = column[Option[DateTime]]("enddate")

    // Every table needs a * projection with the same type as the table's type parameter
    def * = (
        pkId.?, nodeJoinKey, ruleId, serial, directiveId, component,
        cardinality, componentsValues, unexpandedComponentsValues, beginDate, endDate
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

    slickExec {
      reportsTable ++= slickReports
    }
  }

}

