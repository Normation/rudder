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
import doobie.enum.jdbctype.Other
import doobie.postgres.pgtypes._
import scalaz.{Failure => _, _}
import scalaz.concurrent.Task
import org.joda.time.DateTime
import scala.xml.XML
import java.sql.SQLXML
import scala.xml.Elem
import com.normation.rudder.domain.reports._
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.inventory.domain.NodeId
import net.liftweb.common._
import scala.language.implicitConversions
import com.normation.rudder.services.reports.RunAndConfigInfo
import org.slf4j.LoggerFactory
import doobie.util.log.ExecFailure
import doobie.util.log.ProcessingFailure

/**
 *
 * That file contains Doobie connection
 *
 * Use it by importing doobie._
 */
class Doobie(datasource: DataSource) {

  val xa = DataSourceTransactor[Task](datasource)

}

object Doobie {

  implicit val slf4jDoobieLogger: LogHandler = {
    object DoobieLogger extends Logger {
      override protected def _logger = LoggerFactory.getLogger("sql")
    }


    LogHandler {
      // we could log only if exec duration is more than X ms, etc.
      case doobie.util.log.Success(s, a, e1, e2) =>
        val total = (e1 + e2).toMillis
        val msg = s"""Successful Statement Execution [${total} ms total (${e1.toMillis} ms exec + ${e2.toMillis} ms processing)]
          |  ${s.linesIterator.dropWhile(_.trim.isEmpty).mkString("\n  ")}
          | arguments = [${a.mkString(", ")}]
        """.stripMargin
        if(total > 100) { //more than that => debug level, not trace
          DoobieLogger.debug(msg)
        } else {
          DoobieLogger.trace(msg)
        }

      case ProcessingFailure(s, a, e1, e2, t) =>
        DoobieLogger.debug(s"""Failed Resultset Processing [${(e1 + e2).toMillis} ms total (${e1.toMillis} ms exec + ${e2.toMillis} ms processing)]
          |  ${s.linesIterator.dropWhile(_.trim.isEmpty).mkString("\n  ")}
          | arguments = [${a.mkString(", ")}]
          |   failure = ${t.getMessage}
        """.stripMargin)

      case ExecFailure(s, a, e1, t) =>
        DoobieLogger.debug(s"""Failed Statement Execution [${e1.toMillis} ms exec (failed)]
          |  ${s.linesIterator.dropWhile(_.trim.isEmpty).mkString("\n  ")}
          | arguments = [${a.mkString(", ")}]
          |   failure = ${t.getMessage}
        """.stripMargin)
    }
  }


  /**
   * Utility method that maps a scalaz either into a Lift Box.
   * Implicitly, because there is ton of it needed.
   */
  implicit def xorToBox[A](res: \/[Throwable, A]): Box[A] = res match {
    case -\/(e) => Failure(e.getMessage, Full(e), Empty)
    case \/-(a) => Full(a)
  }
  implicit class XorToBox[A](res: \/[Throwable, A]) {
    def box = res match {
      case -\/(e) => Failure(e.getMessage, Full(e), Empty)
      case \/-(a) => Full(a)
    }
  }

  /*
   * Doobie is missing a Query0 builder, so here is simple one.
   */
  def query[A](sql: String)(implicit a: Composite[A]): Query0[A] = Query[Unit, A](sql, None)(implicitly[Composite[Unit]], a).toQuery0(())

  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////////////// data structure mapping ///////////////////////////////////////////////////////
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  implicit val DateTimeMeta: Meta[DateTime] =
    Meta[java.sql.Timestamp].nxmap(
        ts => new DateTime(ts.getTime())
      , dt => new java.sql.Timestamp(dt.getMillis)
  )

  implicit val ReportComposite: Composite[Reports] = {
    type R = (DateTime, RuleId, DirectiveId, NodeId, Int, String, String, DateTime, String, String)
    Composite[R].xmap(
        (t: R      ) => Reports.factory(t._1,t._2,t._3,t._4,t._5,t._6,t._7,t._8,t._9,t._10)
     ,  (r: Reports) => Reports.unapply(r).get
    )
  }

  implicit val NodesConfigVerionComposite: Composite[NodeConfigVersions] = {
    Composite[(NodeId, Option[List[String]])].xmap(
        tuple => NodeConfigVersions(tuple._1, tuple._2.getOrElse(Nil).map(NodeConfigId))
     ,  ncv   => (ncv.nodeId, Some(ncv.versions.map(_.value)))
    )
  }

  implicit val NodeConfigIdComposite: Atom[Vector[NodeConfigIdInfo]] = {
    Atom[String].xmap(
        tuple => NodeConfigIdSerializer.unserialize(tuple)
      , obj   => NodeConfigIdSerializer.serialize(obj)
    )
  }

  /*
   * Do not use that one for extraction, only to save NodeExpectedReports
   */
  implicit val SerializeNodeExpectedReportsComposite: Composite[NodeExpectedReports] = {
    import ExpectedReportsSerialisation._
    Composite[(NodeId, NodeConfigId, DateTime, Option[DateTime], String)].xmap(
        tuple => throw new RuntimeException(s"Error: that method should not be used to deserialize NodeExpectedReports")
      , ner   => (ner.nodeId, ner.nodeConfigId, ner.beginDate, ner.endDate, ner.toJson)
    )
  }

  /*
   * As we have some json in NodeExpectedReports, it is expected to fail somewhen like
   * at each format update. We need to enforce that.
   */
  implicit val DeserializeNodeExpectedReportsComposite: Composite[\/[(NodeId, NodeConfigId, DateTime), NodeExpectedReports]] = {
    import ExpectedReportsSerialisation._
    Composite[(NodeId, NodeConfigId, DateTime, Option[DateTime], String)].xmap(
        tuple => {
              parseJsonNodeExpectedReports(tuple._5) match {
                case Full(x)      =>
                  \/-(NodeExpectedReports(tuple._1, tuple._2, tuple._3, tuple._4, x.modes, x.ruleExpectedReports, x.overrides))
                case eb: EmptyBox =>
                  -\/((tuple._1, tuple._2, tuple._3))
              }
          }
      , ner   => throw new RuntimeException(s"Error: that method should not be used to serialize NodeExpectedReports")
    )
  }

  implicit val CompliancePercentComposite: Composite[CompliancePercent] = {
    import ComplianceLevelSerialisation._
    import net.liftweb.json._
    Composite[String].xmap(
        json => parsePercent(parse(json))
      , x    => compactRender(x.toJson)
    )
  }

  implicit val ComplianceRunInfoComposite: Composite[(RunAndConfigInfo, RunComplianceInfo)] = {
    import NodeStatusReportSerialization._
    Composite[String].xmap(
        json => throw new RuntimeException(s"You can deserialize run compliance info for now")
      , x    => x.toJson
    )
  }

  implicit val AggregatedStatusReportComposite: Composite[AggregatedStatusReport] = {
    import NodeStatusReportSerialization._
    Composite[String].xmap(
        json => throw new RuntimeException(s"You can deserialize aggredatedStatusReport for now")
      , x    => x.toJson
    )
  }



  implicit val XmlMeta: Meta[Elem] =
    Meta.advanced[Elem](
        NonEmptyList(Other)
      , NonEmptyList("xml")
      , (rs, n) => XML.load(rs.getObject(n).asInstanceOf[SQLXML].getBinaryStream),
        (n,  e) => FPS.raw { ps =>
          val sqlXml = ps.getConnection.createSQLXML
          val osw = new java.io.OutputStreamWriter(sqlXml.setBinaryStream)
          XML.write(osw, e, "UTF-8", false, null)
          osw.close
          ps.setObject(n, sqlXml)
        }
      , (_,  _) => sys.error("update not supported, sorry")
    )
}
