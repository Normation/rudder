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
import org.joda.time.DateTime
import scala.xml.XML
import java.sql.SQLXML
import scala.xml.Elem
import com.normation.rudder.domain.reports._
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.inventory.domain.NodeId
import net.liftweb.common._
import com.normation.rudder.services.reports.RunAndConfigInfo
import org.slf4j.LoggerFactory
import doobie.util.log.ExecFailure
import doobie.util.log.ProcessingFailure
import scala.language.implicitConversions
import doobie.postgres.implicits._ // it is necessary whatever intellij/scalac tells
import doobie._
import cats.data._
import cats.effect._
import cats.implicits._
import doobie._

import scalaz.zio.Task
import scalaz.zio.interop.catz._
import com.normation.errors._

/**
 *
 * That file contains Doobie connection
 *
 * Use it by importing doobie._
 */
class Doobie(datasource: DataSource) {

  def transact[T](query: Transactor[IO] => IO[T]): IO[T] = {
    implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
    val xa: Resource[IO, DataSourceTransactor[IO]] = {
      for {
        ce <- ExecutionContexts.fixedThreadPool[IO](32) // our connect EC
        te <- ExecutionContexts.cachedThreadPool[IO]    // our transaction EC
      } yield {
        Transactor.fromDataSource[IO](datasource, ce, te)
      }
    }
    xa.use(xa => query(xa))
  }

  def transactTask[T](query: Transactor[IO] => IO[T]): Task[T] = {
    transact(query).to[Task]
  }

  def transactIOResult[T](errorMsg: String)(query: Transactor[IO] => IO[T]): IOResult[T] = {
    transact(query).to[Task].mapError(ex => SystemError(errorMsg, ex))
  }

  def transactRun[T](query: Transactor[IO] => IO[T]): T = {
    transact(query).unsafeRunSync()
  }

  def transactRunBox[T](q: Transactor[IO] => IO[T]): Box[T] = {
    import Doobie._
    transact(q).attempt.unsafeRunSync()
  }
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
   * Utility method that maps a either into a Lift Box.
   * Implicitly, because there is ton of it needed.
   */
  implicit def xorToBox[A](res: Either[Throwable, A]): Box[A] = res match {
    case Left(e) => Failure(e.getMessage, Full(e), Empty)
    case Right(a) => Full(a)
  }
  implicit class XorToBox[A](res: Either[Throwable, A]) {
    def box = xorToBox(res)
  }
  implicit def xorBoxToBox[A](res: Either[Throwable, Box[A]]): Box[A] = res match {
    case Left(e) => Failure(e.getMessage, Full(e), Empty)
    case Right(a) => a
  }
  implicit class XorBoxToBox[A](res: Either[Throwable, Box[A]]) {
    def box = xorBoxToBox(res)
  }

  /*
   * Doobie is missing a Query0 builder, so here is simple one.
   */
  def query[A](sql: String)(implicit a: Read[A]): Query0[A] = Query0(sql)

  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////////////// data structure mapping ///////////////////////////////////////////////////////
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  implicit val DateTimeMeta: Meta[DateTime] =
    Meta[java.sql.Timestamp].imap(
       ts => new DateTime(ts.getTime())
    )( dt => new java.sql.Timestamp(dt.getMillis)
  )

  implicit val ReportRead: Read[Reports] = {
    type R = (DateTime, RuleId, DirectiveId, NodeId, Int, String, String, DateTime, String, String)
    Read[R].map(
        (t: R      ) => Reports.factory(t._1,t._2,t._3,t._4,t._5,t._6,t._7,t._8,t._9,t._10))
  }
  implicit val ReportWrite: Write[Reports] = {
    type R = (DateTime, RuleId, DirectiveId, NodeId, Int, String, String, DateTime, String, String)
    Write[R].contramap(
        (r: Reports) => Reports.unapply(r).get
    )
  }

  implicit val NodesConfigVerionRead: Read[NodeConfigVersions] = {
    Read[(NodeId, Option[List[String]])].map(
        tuple => NodeConfigVersions(tuple._1, tuple._2.getOrElse(Nil).map(NodeConfigId)))
  }
  implicit val NodesConfigVerionWrite: Write[NodeConfigVersions] = {
    Write[(NodeId, Option[List[String]])].contramap(
        ncv   => (ncv.nodeId, Some(ncv.versions.map(_.value)))
    )
  }

  implicit val NodeConfigIdComposite: Meta[Vector[NodeConfigIdInfo]] = {
    Meta[String].imap(
       tuple => NodeConfigIdSerializer.unserialize(tuple)
    )( obj   => NodeConfigIdSerializer.serialize(obj)
    )
  }

  /*
   * Do not use that one for extraction, only to save NodeExpectedReports
   */
  implicit val SerializeNodeExpectedReportsWrite: Write[NodeExpectedReports] = {
    import ExpectedReportsSerialisation._
    Write[(NodeId, NodeConfigId, DateTime, Option[DateTime], String)].contramap(
      ner   => (ner.nodeId, ner.nodeConfigId, ner.beginDate, ner.endDate, ner.toCompactJson)
    )
  }

  /*
   * As we have some json in NodeExpectedReports, it is expected to fail somewhen like
   * at each format update. We need to enforce that.
   */
  implicit val DeserializeNodeExpectedReportsRead: Read[Either[(NodeId, NodeConfigId, DateTime), NodeExpectedReports]] = {
    import ExpectedReportsSerialisation._
    Read[(NodeId, NodeConfigId, DateTime, Option[DateTime], String)].map( tuple =>
      parseJsonNodeExpectedReports(tuple._5) match {
        case Full(x)      =>
          Right(NodeExpectedReports(tuple._1, tuple._2, tuple._3, tuple._4, x.modes, x.ruleExpectedReports, x.overrides))
        case eb: EmptyBox =>
          Left((tuple._1, tuple._2, tuple._3))
      }
    )
  }

  implicit val CompliancePercentRead: Read[CompliancePercent] = {
    import ComplianceLevelSerialisation._
    import net.liftweb.json._
    Read[String].map(json => parsePercent(parse(json)))
  }
  implicit val CompliancePercentWrite: Write[CompliancePercent] = {
    import ComplianceLevelSerialisation._
    import net.liftweb.json._
    Write[String].contramap(x => compactRender(x.toJson))
  }

  implicit val ComplianceRunInfoComposite: Write[(RunAndConfigInfo, RunComplianceInfo)] = {
    import NodeStatusReportSerialization._
    Write[String].contramap(_.toCompactJson)
  }

  implicit val AggregatedStatusReportComposite: Write[AggregatedStatusReport] = {
    import NodeStatusReportSerialization._
    Write[String].contramap(_.toCompactJson)
  }


  import doobie.enum.JdbcType.Other
  implicit val XmlMeta: Meta[Elem] =
    Meta.Advanced.many[Elem](
      NonEmptyList.of(Other),
      NonEmptyList.of("xml"),
      (rs, n) => XML.load(rs.getObject(n).asInstanceOf[SQLXML].getBinaryStream),
      (ps, n,  e) => {
        val sqlXml = ps.getConnection.createSQLXML
        val osw = new java.io.OutputStreamWriter(sqlXml.setBinaryStream)
        XML.write(osw, e, "UTF-8", false, null)
        osw.close
        ps.setObject(n, sqlXml)
      },
      (_, _,  _) => sys.error("update not supported, sorry")
    )
}
