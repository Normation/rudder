/*
*************************************************************************************
* Copyright 2016 Normation SAS
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

package com.normation.rudder.db

import javax.sql.DataSource
import doobie.imports._
import scalaz._, Scalaz._
import scalaz.concurrent.Task
import org.joda.time.DateTime
import doobie.contrib.postgresql.pgtypes._
import com.normation.rudder.domain.reports.Reports

/**
 *
 * That file contains Doobie connection
 *
 * Use it by importing boobie._
 *
 *
 */
class Doobie(datasource: DataSource) {

  val xa = DataSourceTransactor[Task](datasource)

  implicit val DateTimeMeta: Meta[DateTime] =
    Meta[java.sql.Timestamp].nxmap(
        ts => new DateTime(ts.getTime())
      , dt => new java.sql.Timestamp(dt.getMillis)
  )

  /*
   * Some common queries
   */
  def insertExpectedReports(reports: List[DB.ExpectedReports[Unit]]): ConnectionIO[Int] = {
    Update[DB.ExpectedReports[Unit]]("""
      insert into expectedreports (nodejoinkey, ruleid, serial, directiveid, component, cardinality,
                                   componentsvalues, unexpandedcomponentsvalues, begindate, enddate)
      values (?,?,?, ?,?,?, ?,?,?, ?)
      """).updateMany(reports)
  }

  def getExpectedReports() = {
    sql"""
      select pkid, nodejoinkey, ruleid, serial, directiveid, component, cardinality
           , componentsvalues, unexpandedcomponentsvalues, begindate, enddate
      from expectedreports
    """.query[DB.ExpectedReports[Long]].list
  }

  def insertExpectedReportsNode(nodes: List[DB.ExpectedReportsNodes]): ConnectionIO[Int] = {
    Update[DB.ExpectedReportsNodes]("""
      insert into expectedreportsnodes (nodejoinkey, nodeid, nodeconfigids)
      values (?,?,?)
    """).updateMany(nodes)
  }

  def getExpectedReportsNode() = {
    sql"""
      select nodejoinkey, nodeid, nodeconfigids from expectedreportsnodes
    """.query[DB.ExpectedReportsNodes].list
  }

  def insertReports(reports: List[Reports]): ConnectionIO[Int] = {
    val dbreports = reports.map { r =>
      DB.Reports[Unit]((), r.executionDate, r.nodeId.value, r.directiveId.value, r.ruleId.value, r.serial
                      , r.component, r.keyValue, r.executionTimestamp, r.severity, "policy", r.message)
    }

    Update[DB.Reports[Unit]]("""
      insert into ruddersysevents
        (executiondate, nodeid, directiveid, ruleid, serial, component, keyvalue, executiontimestamp, eventtype, policy, msg)
      values (?,?,?, ?,?,?, ?,?,?, ?,?)
    """).updateMany(dbreports)
  }
}

