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

import cats.Show
import cats.data.*
import com.normation.NamedZioLogger
import com.normation.box.*
import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.db.json.implicits.*
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.*
import com.normation.rudder.domain.reports.JsonPostgresqlSerialization.JNodeStatusReport
import com.normation.utils.XmlSafe
import com.normation.zio.*
import doobie.*
import doobie.postgres.implicits.*
import doobie.util.log.ExecFailure
import doobie.util.log.LogEvent
import doobie.util.log.ProcessingFailure
import doobie.util.transactor
import io.scalaland.chimney.syntax.*
import java.sql.SQLXML
import javax.sql.DataSource
import net.liftweb.common.*
import org.joda.time.DateTime
import org.postgresql.util.PGobject
import scala.xml.Elem
import scala.xml.XML
import zio.*
import zio.interop.catz.*
import zio.json.*
import zio.json.ast.Json

/**
 *
 * That file contains Doobie connection
 *
 * Use it by importing doobie._
 */
class Doobie(datasource: DataSource) {

  val xa: transactor.Transactor.Aux[Task, DataSource] = (for {
    // zio.interop.catz._ provides a `zioContextShift`
    // our transaction EC: wait for aquire/release connections, must accept blocking operations
    te <- ZIO.blockingExecutor.map(_.asExecutionContext)
  } yield {
    Transactor.fromDataSource[Task](datasource, te, Some(Doobie.slf4jDoobieLogger))
  }).runNow

  def transactTask[T](query: Transactor[Task] => Task[T]): Task[T] = {
    query(xa)
  }

  def transactIOResult[T](errorMsg: String)(query: Transactor[Task] => Task[T]): IOResult[T] = {
    query(xa).mapError(ex => SystemError(errorMsg, ex))
  }

  def transactRunEither[T](query: Transactor[Task] => Task[T]): Either[Throwable, T] = {
    ZioRuntime.unsafeRun(transactTask(query).either)
  }

  def transactRunBox[T](q: Transactor[Task] => Task[T]): Box[T] = {
    transactIOResult("Error running doobie transaction")(q).toBox
  }
}

object Doobie {

  implicit val slf4jDoobieLogger: LogHandler[zio.Task] = {
    object DoobieLogger extends NamedZioLogger {
      lazy val loggerName = "sql"
    }

    new LogHandler[zio.Task] {
      def run(logEvent: LogEvent): zio.Task[Unit] = {
        logEvent match {

          // we could log only if exec duration is more than X ms, etc.
          case doobie.util.log.Success(s, a, l, e1, e2) =>
            val total = (e1 + e2).toMillis
            val msg   = {
              s"""Successful Statement Execution [${total} ms total (${e1.toMillis} ms exec + ${e2.toMillis} ms processing)]
                 |  ${s.linesIterator.dropWhile(_.trim.isEmpty).mkString("\n  ")}
                 | arguments = [${a.allParams.flatten.mkString(", ")}]
        """.stripMargin
            }
            if (total > 100) { // more than that => debug level, not trace
              DoobieLogger.debug(msg)
            } else {
              DoobieLogger.trace(msg)
            }

          case ProcessingFailure(s, a, l, e1, e2, t) =>
            DoobieLogger.debug(
              s"""Failed Resultset Processing [${(e1 + e2).toMillis} ms total (${e1.toMillis} ms exec + ${e2.toMillis} ms processing)]
                 |  ${s.linesIterator.dropWhile(_.trim.isEmpty).mkString("\n  ")}
                 | arguments = [${a.allParams.flatten.mkString(", ")}]
                 |   failure = ${t.getMessage}
        """.stripMargin
            )

          case ExecFailure(s, a, l, e1, t) =>
            DoobieLogger.debug(s"""Failed Statement Execution [${e1.toMillis} ms exec (failed)]
                                  |  ${s.linesIterator.dropWhile(_.trim.isEmpty).mkString("\n  ")}
                                  | arguments = [${a.allParams.flatten.mkString(", ")}]
                                  |   failure = ${t.getMessage}
        """.stripMargin)
        }
      }
    }
  }

  /**
   * Utility method that maps a either into a Lift Box.
   * Implicitly, because there is ton of it needed.
   */
  implicit def xorToBox[A](res: Either[Throwable, A]):         Box[A] = res match {
    case Left(e)  => Failure(e.getMessage, Full(e), Empty)
    case Right(a) => Full(a)
  }
  implicit class XorToBox[A](val res: Either[Throwable, A]) extends AnyVal {
    def box: Box[A] = xorToBox(res)
  }
  implicit def xorBoxToBox[A](res: Either[Throwable, Box[A]]): Box[A] = res match {
    case Left(e)  => Failure(e.getMessage, Full(e), Empty)
    case Right(a) => a
  }
  implicit class XorBoxToBox[A](val res: Either[Throwable, Box[A]]) extends AnyVal {
    def box: Box[A] = xorBoxToBox(res)
  }

  /*
   * Doobie is missing a Query0 builder, so here is simple one.
   */
  def query[A](sql: String)(implicit a: Read[A]): Query0[A] = Query0(sql)

  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////////////// data structure mapping ///////////////////////////////////////////////////////
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  implicit val DateTimeMeta: Meta[DateTime] =
    Meta[java.sql.Timestamp].imap(ts => new DateTime(ts.getTime()))(dt => new java.sql.Timestamp(dt.getMillis))

  implicit val ReadRuleId: Get[RuleId] = {
    Get[String].map(r => {
      RuleId.parse(r) match {
        case Right(rid) => rid
        case Left(err)  =>
          throw new IllegalArgumentException(s"Error when unserializing a report from base: Can not parse rule ID: ${r}: ${err}.")
      }
    })
  }
  implicit val PutRuleId:  Put[RuleId] = {
    Put[String].contramap(_.serialize)
  }

  implicit val GetNodeId: Get[NodeId] = Get[String].tmap(NodeId.apply)
  implicit val PutNodeId: Put[NodeId] = Put[String].tcontramap(_.value)

  implicit val GetNodeConfigId: Get[NodeConfigId] = Get[String].tmap(NodeConfigId.apply)
  implicit val PutNodeConfigId: Put[NodeConfigId] = Put[String].tcontramap(_.value)

  implicit val GetModificationId: Get[ModificationId] = Get[String].tmap(ModificationId.apply)
  implicit val PutModificationId: Put[ModificationId] = Put[String].tcontramap(_.value)

  implicit val ReadDirectiveId:  Get[DirectiveId] = {
    Get[String].map(r => {
      DirectiveId.parse(r) match {
        case Right(rid) => rid
        case Left(err)  =>
          throw new IllegalArgumentException(
            s"Error when unserializing a report from base: Can not parse directive ID: ${r}: ${err}."
          )
      }
    })
  }
  implicit val WriteDirectiveId: Put[DirectiveId] = {
    Put[String].contramap(_.serialize)
  }

  implicit val ReportRead: Read[Reports] = {
    type R = (DateTime, RuleId, DirectiveId, NodeId, String, String, String, DateTime, String, String)
    Read[R].map((t: R) => Reports.factory(t._1, t._2, t._3, t._4, t._5, t._6, t._7, t._8, t._9, t._10))
  }

  implicit val NodesConfigVerionRead:  Read[NodeConfigVersions]  = {
    Read[(NodeId, Option[List[String]])].map(tuple =>
      NodeConfigVersions(tuple._1, tuple._2.getOrElse(Nil).map(NodeConfigId.apply))
    )
  }
  implicit val NodesConfigVerionWrite: Write[NodeConfigVersions] = {
    Write[(NodeId, Option[List[String]])].contramap(ncv => (ncv.nodeId, Some(ncv.versions.map(_.value))))
  }

  implicit val NodeConfigIdComposite: Meta[Vector[NodeConfigIdInfo]] = {
    Meta[String].imap(tuple => NodeConfigIdSerializer.unserialize(tuple))(obj => NodeConfigIdSerializer.serialize(obj))
  }

  /*
   * Do not use that one for extraction, only to save NodeStatusReport
   */
  implicit val SerializeNodeExpectedReportsWrite: Write[NodeExpectedReports] = {
    import ExpectedReportsSerialisation.*
    Write[(NodeId, NodeConfigId, DateTime, Option[DateTime], String)].contramap(ner =>
      (ner.nodeId, ner.nodeConfigId, ner.beginDate, ner.endDate, ner.toCompactJson)
    )
  }

  /*
   * As we have some json in NodeExpectedReports, it is expected to fail somewhen like
   * at each format update. We need to enforce that.
   */
  implicit val DeserializeNodeExpectedReportsRead: Read[Either[(NodeId, NodeConfigId, DateTime), NodeExpectedReports]] = {
    import ExpectedReportsSerialisation.*
    Read[(NodeId, NodeConfigId, DateTime, Option[DateTime], String)].map(tuple => {
      parseJsonNodeExpectedReports(tuple._5) match {
        case Full(x) =>
          Right(NodeExpectedReports(tuple._1, tuple._2, tuple._3, tuple._4, x.modes, x.ruleExpectedReports, x.overrides))
        case eb: EmptyBox =>
          Left((tuple._1, tuple._2, tuple._3))
      }
    })
  }

  /*
   * Read/Write JNodeStatusReports as a minimized Json field with all data.
   * It's Put/Get since it will be hold on a single column
   */
  implicit val jNodeStatusReportGet: Get[JNodeStatusReport] = {
    import com.normation.rudder.domain.reports.JsonPostgresqlSerialization.*
    Get.Advanced.other[PGobject](NonEmptyList.of("json")).temap[JNodeStatusReport](o => o.getValue.fromJson[JNodeStatusReport])
  }

  implicit val jNodeStatusReportPut: Put[JNodeStatusReport] = {
    import com.normation.rudder.domain.reports.JsonPostgresqlSerialization.*
    Put.Advanced.other[PGobject](NonEmptyList.of("json")).tcontramap[JNodeStatusReport] { j =>
      val o = new PGobject
      o.setType("json")
      o.setValue(j.toJson)
      o
    }
  }

  /*
   * The 4 following ones are used in udder-core/src/main/scala/com/normation/rudder/repository/jdbc/ComplianceRepository.scala
   */
  implicit val CompliancePercentWrite: Write[CompliancePercent] = {
    Write[String].contramap(x => x.transformInto[ComplianceSerializable].toJson)
  }

  implicit val ComplianceRunInfoComposite: Write[(RunAnalysis, RunComplianceInfo)] = {
    import NodeStatusReportSerialization.*
    Write[String].contramap(runToJson)
  }

  implicit val AggregatedStatusReportComposite: Write[AggregatedStatusReport] = {
    import NodeStatusReportSerialization.*
    Write[String].contramap(_.toCompactJson)
  }

  implicit val SetRuleNodeStatusReportComposite: Write[Set[RuleNodeStatusReport]] = {
    import NodeStatusReportSerialization.*
    Write[String].contramap(ruleNodeStatusReportToJson)
  }

  import doobie.enumerated.JdbcType.SqlXml
  implicit val XmlMeta: Meta[Elem] = {
    Meta.Advanced.many[Elem](
      NonEmptyList.of(SqlXml),
      NonEmptyList.of("xml"),
      (rs, n) => XmlSafe.load(rs.getObject(n).asInstanceOf[SQLXML].getBinaryStream),
      (ps, n, e) => {
        val sqlXml = ps.getConnection.createSQLXML
        val osw    = new java.io.OutputStreamWriter(sqlXml.setBinaryStream())
        XML.write(osw, e, "UTF-8", xmlDecl = false, doctype = null)
        osw.close()
        ps.setObject(n, sqlXml)
      },
      (_, _, _) => sys.error("update not supported, sorry")
    )
  }

  implicit val eventActorCompositeRead:  Read[EventActor]  = Read[String].map(s => EventActor(s))
  implicit val eventActorCompositeWrite: Write[EventActor] = Write[String].contramap(_.name)

}

// Same as doobie-circe , but for zio-json, did not find someone who already have done it, should be in a dependency
package object json {
  object implicits extends JsonInstances
}

trait JsonInstances {

  import doobie.util.*
  import zio.json.DecoderOps
  import zio.json.EncoderOps

  // for debugging. 250 is the default documented val in
  // https://tpolecat.github.io/doobie/docs/12-Custom-Mappings.html#defining-get-and-put-for-exotic-types
  implicit val showPGobject: Show[PGobject] = Show.show(_.getValue.take(250))

  implicit val jsonPut: Put[Json] = {
    Put.Advanced
      .other[PGobject](
        NonEmptyList.of("jsonb")
      )
      .tcontramap { a =>
        val o = new PGobject
        o.setType("jsonb")
        o.setValue(a.toString())
        o
      }
  }

  implicit val jsonGet: Get[Json] = {
    import Json.decoder
    Get.Advanced
      .other[PGobject](
        NonEmptyList.of("jsonb")
      )
      .temap(a => a.getValue.fromJson)
  }

  // for structured Json encoder/decoder, we don't want to rely on intermediate JSON:
  // - it means one more step that is useless since we have the structured result
  // - it ignores special mapping from @jsonField etc, and so it is inconsistent with other JSON serializations
  def pgEncoderPut[A](implicit e: JsonEncoder[A]): Put[A] = {
    Put.Advanced
      .other[PGobject](
        NonEmptyList.of("jsonb")
      )
      .tcontramap[A] { a =>
        val o = new PGobject
        o.setType("jsonb")
        o.setValue(a.toJson)
        o
      }
  }

  def pgDecoderGet[A: JsonDecoder]: Get[A] = {
    Get.Advanced
      .other[PGobject](
        NonEmptyList.of("jsonb")
      )
      .temap[A](a => a.getValue.fromJson)
  }

}
