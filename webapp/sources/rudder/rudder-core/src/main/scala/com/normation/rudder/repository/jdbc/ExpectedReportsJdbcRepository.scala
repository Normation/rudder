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

import cats.free.Free
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.reports._
import com.normation.rudder.repository.UpdateExpectedReportsRepository
import com.normation.rudder.repository.FindExpectedReportRepository
import com.normation.utils.HashcodeCaching
import net.liftweb.common._
import net.liftweb.json._
import org.joda.time.DateTime
import com.normation.rudder.domain.logger.TimingDebugLogger
import com.normation.rudder.db.Doobie
import com.normation.rudder.db.Doobie._
import doobie._
import doobie.implicits._
import cats.implicits._
import com.normation.rudder.domain.logger.PolicyLogger
import com.normation.rudder.domain.logger.ReportLogger
import com.normation.utils.Control.sequence
import doobie.free.connection

class PostgresqlInClause(
    //max number of element to switch from in (...) to in(values(...)) clause
    // Postgres guru seems to indicate 70 is a good value
    // see link below
    val inClauseMaxNbElt: Int
) {
  /*
   * Build a clause to match if the given attribute is in the given list of values.
   *
   * Try to be as efficient as possible for postgres. TODO: check VALUES; ARRAY
   *
   * http://postgres.cz/wiki/PostgreSQL_SQL_Tricks_I#Predicate_IN_optimalization
   *
   * Does not build anything is the list of values is empty
   *
   *
   * NOTE: do not use that on arrays with "generate_subscripts", this is highly inefficient.
   * See http://www.rudder-project.org/redmine/issues/8057 for details.
   *
   */
  def in(attribute: String, values: Iterable[String]): String = {
    //with values, we need more ()
    if(values.isEmpty) ""
    else if(values.size < inClauseMaxNbElt) s"${attribute} IN (${values.mkString("'", "','", "'")})"
    //use IN ( VALUES (), (), ... )
    else s"${attribute} IN(VALUES ${values.mkString("('","'),('","')")})"
  }

  def inNumber(attribute: String, values: Iterable[AnyVal]): String = {
    //with values, we need more ()
    if(values.isEmpty) ""
    else if(values.size < inClauseMaxNbElt) s"${attribute} IN (${values.mkString("", ",", "")})"
    //use IN ( VALUES (), (), ... )
    else s"${attribute} IN(VALUES ${values.mkString("(","),(",")")})"
  }
}


class FindExpectedReportsJdbcRepository(
    doobie          : Doobie
  , pgInClause      : PostgresqlInClause
  , jdbcMaxBatchSize: Int
) extends FindExpectedReportRepository with Loggable {

  import pgInClause._
  import doobie._


  /*
   * Retrieve the expected reports by config version of the nodes.
   *
   * The property "returnedMap.keySet == nodeConfigIds" holds.
   */
  override def getExpectedReports(
      nodeConfigIds: Set[NodeAndConfigId]
  ): Box[Map[NodeAndConfigId, Option[NodeExpectedReports]]] = {

    val batchedNodeConfigIds = nodeConfigIds.grouped(jdbcMaxBatchSize).toSeq
    sequence(batchedNodeConfigIds) { ids: Set[NodeAndConfigId] =>
      ids.toList match { //"in" param can't be empty
        case Nil        => Full(Seq())
        case nodeAndIds =>
          (for {
            configs <- query[Either[(NodeId, NodeConfigId, DateTime), NodeExpectedReports]](s"""
                       select nodeid, nodeconfigid, begindate, enddate, configuration
                       from nodeconfigurations
                       where (nodeid, nodeconfigid) in (${nodeAndIds.map(x => s"('${x.nodeId.value}','${x.version.value}')").mkString(",")} )
                     """).to[Vector]
          }yield {
            val configsMap = (configs.flatMap {
              case Left((id, c, _)) =>
                logger.error(s"Error when deserializing JSON for expected node configuration ${id.value} (${c.value})")
                None
              case Right(x) => Some((NodeAndConfigId(x.nodeId, x.nodeConfigId), x))
            }).toMap
            ids.map(id => (id, configsMap.get(id)))
          }).transact(xa).attempt.unsafeRunSync
      }
    }.map( x => x.flatten.map{ case (id, option) => (id -> option)}.toMap )

  }

  /*
   * Retrieve the current expected report for the list of nodes.
   *
   * The property "returnedMam.keySet = nodeIds" holds.
   */
  override def getCurrentExpectedsReports(nodeIds: Set[NodeId]): Box[Map[NodeId, Option[NodeExpectedReports]]] = {
    if(nodeIds.isEmpty) {
      Full(Map())
    } else {
        var queryTiming = 0L
        val ids = nodeIds.grouped(jdbcMaxBatchSize).toSeq
        val result = sequence(ids) {  batchedIds: Set[NodeId] =>
          val t0_0 = System.currentTimeMillis
          (for {
            configs <- (Fragment.const(s"with tempnodeid (id) as (values ${batchedIds.map(x => s"('${x.value}')").mkString(",")})") ++
              fr"""
                       select nodeid, nodeconfigid, begindate, enddate, configuration
                       from nodeconfigurations
                       inner join tempnodeid on tempnodeid.id = nodeconfigurations.nodeid
                       where enddate is null"""
              ).query[Either[(NodeId, NodeConfigId, DateTime), NodeExpectedReports]].to[Vector]
          } yield {
            val t1_1 =  System.currentTimeMillis
            queryTiming += t1_1 - t0_0
            TimingDebugLogger.trace(s"Compliance: query to get current expected reports within batch: ${t1_1 - t0_0}ms")
            val configsMap = (configs.flatMap {
              case Left((id, c, _)) =>
                logger.error(s"Error when deserializing JSON for expected node configuration ${id.value} (${c.value})")
                None
              case Right(x) => Some((x.nodeId, x))
            }).toMap
            batchedIds.map(id => (id, configsMap.get(id))).toSeq
          }).transact(xa).attempt.unsafeRunSync
        }
        TimingDebugLogger.trace(s"Compliance: query to get current expected reports: ${queryTiming}ms")
        result.map(_.flatten.toMap)
    }
  }


  override def findCurrentNodeIds(ruleId : RuleId) : Box[Set[NodeId]] = {
    sql"""
      select distinct nodeid from nodeconfigurations
      where enddate is null and configuration like ${"%"+ruleId.value+"%"}
    """.query[NodeId].to[Set].transact(xa).attempt.unsafeRunSync
  }


  /*
   * Retrieve the list of node config ids
   */
  override def getNodeConfigIdInfos(nodeIds: Set[NodeId]): Box[Map[NodeId, Option[Vector[NodeConfigIdInfo]]]] = {
    if(nodeIds.isEmpty) Full(Map.empty[NodeId, Option[Vector[NodeConfigIdInfo]]])
    else {
      val batchedNodesId = nodeIds.grouped(jdbcMaxBatchSize).toSeq
      sequence(batchedNodesId) { ids: Set[NodeId] =>
        (for {
          entries <- query[(NodeId, String)](
            s"""select node_id, config_ids from nodes_info
                                              where ${in("node_id", ids.map(_.value))}""").to[Vector]
        } yield {
          val res = entries.map { case (nodeId, config) => (nodeId, NodeConfigIdSerializer.unserialize(config)) }.toMap
          ids.map(n => (n, res.get(n)))
        }).transact(xa).attempt.unsafeRunSync
      }.map(_.flatten.toMap)
    }
  }
}




class UpdateExpectedReportsJdbcRepository(
    doobie     : Doobie
  , pgInClause: PostgresqlInClause
  , jdbcMaxBatchSize: Int
) extends UpdateExpectedReportsRepository {

  import doobie._
  import Doobie._

  val logger = ReportLogger

  override def closeNodeConfigurations(nodeId: NodeId): Box[NodeId] = {
    sql"""
      update nodeconfigurations set enddate = ${DateTime.now} where nodeid = ${nodeId} and enddate is null
    """.update.run.transact(xa).attempt.unsafeRunSync.map( _ => nodeId )
  }


  def saveNodeExpectedReports(configs: List[NodeExpectedReports]): Box[Seq[NodeExpectedReports]]  = {
    import cats.implicits._
    PolicyLogger.expectedReports.debug(s"Saving ${configs.size} nodes expected reports")
    configs match {
      case Nil       => Full(Nil)
      case neConfigs =>

        var timingFindOldNodesConfig = 0L
        var timingFindOldConfigInfo = 0L
        var timingSaveConfig = 0L

        val batchedConfigs: List[List[NodeExpectedReports]] = neConfigs.grouped(jdbcMaxBatchSize).toList
        val resultingNodeExpectedReports: Either[Throwable, List[Free[connection.ConnectionOp, List[NodeExpectedReports]]]] = batchedConfigs.traverse { conf: Seq[NodeExpectedReports] =>
          val confString = conf.map(x => s"('${x.nodeId.value}')")

          val withFrag = Fragment.const(s"with tempnodeid (id) as (values ${confString.mkString(",")})")
          type A = Either[(NodeId, NodeConfigId, DateTime), NodeExpectedReports]
          val getConfigs: Either[Throwable, List[A]] = (
            withFrag ++ fr"""
                           select nodeid, nodeconfigid, begindate, enddate, configuration
                           from nodeconfigurations
                           inner join tempnodeid on tempnodeid.id = nodeconfigurations.nodeid
                           where enddate is NULL"""
            ).query[A].to[List].transact(xa).attempt.unsafeRunSync

          type B = (NodeId, Vector[NodeConfigIdInfo])
          val getInfos: Either[Throwable, List[B]] = (
            withFrag ++ fr"""
                              select node_id, config_ids from nodes_info
                              inner join tempnodeid on tempnodeid.id = nodes_info.node_id
                            """
            ).query[B].to[List].transact(xa).attempt.unsafeRunSync

          val time_0 = System.currentTimeMillis
          for {
            oldConfigs  <- getConfigs
            time_1      =  System.currentTimeMillis
            _           =  (timingFindOldNodesConfig += (time_1 - time_0))
            _           =  TimingDebugLogger.debug(s"saveNodeExpectedReports: batched find old nodes config in ${time_1-time_0}ms")
            configInfos <- getInfos
            time_2      =  System.currentTimeMillis
            _           =  (timingFindOldConfigInfo += (time_2 - time_1))
            _           =  TimingDebugLogger.debug(s"saveNodeExpectedReports: batched find old config info ${time_2-time_1}ms")
            updated     <- doUpdateNodeExpectedReports(conf.toList, oldConfigs, configInfos.toMap)
            time_3      =  System.currentTimeMillis
            _           =  (timingSaveConfig += (time_3 - time_2))
            _           =  TimingDebugLogger.debug(s"saveNodeExpectedReports: batched save configs etc in ${time_3-time_2}ms")
          } yield {
            updated
          }

        }

        TimingDebugLogger.debug(s"saveNodeExpectedReports: find old nodes config in ${timingFindOldNodesConfig}ms")
        TimingDebugLogger.debug(s"saveNodeExpectedReports: find old config info ${timingFindOldConfigInfo}ms")
        TimingDebugLogger.debug(s"saveNodeExpectedReports: save configs etc in ${timingSaveConfig}ms")

        // Do the actual change
        resultingNodeExpectedReports.map(_.traverse( x => x )).map(_.transact(xa).attempt.unsafeRunSync) match {
          case Left(throwable) =>
            val msg = s"There were an error while updating the expected reports, cause is ${throwable.getMessage}"
            logger.error("msg")
            Failure(msg, Full(throwable), Empty)
          case Right(transactResult) =>
            transactResult match {
              case Left(commitError) =>
                val msg = s"There were an error while updating the expected reports, cause is ${commitError.getMessage}"
                Failure(msg, Full(commitError), Empty)
              case Right(nodesExpectedReports) => Full(nodesExpectedReports.flatten)
            }
        }
    }
  }

  /*
   * Save a nodeExpectedReport, closing the previous one is
   * opened for that node (and a different nodeConfigId)
   */
  def doUpdateNodeExpectedReports(
      configs    : List[NodeExpectedReports]
    , oldConfigs : List[Either[(NodeId, NodeConfigId, DateTime), NodeExpectedReports]]
    , configInfos: Map[NodeId, Vector[NodeConfigIdInfo]]
  ): Either[Throwable, Free[connection.ConnectionOp, List[NodeExpectedReports]]] = {

    import Doobie._

    val currentConfigs = configs.map( c => (c.nodeId, (c.nodeConfigId, c.beginDate)) ).toMap


    // we want to close all error nodeexpectedreports, and all non current
    // we keep valid current identified by nodeId, we won't have to save them afterward.
    val (toClose, okConfigs) = oldConfigs.foldLeft((List[(DateTime, NodeId, NodeConfigId, DateTime)](), List[NodeId]() )) { case ((nok, ok), next) =>
      next match {
        case Left(n) => ((currentConfigs(n._1)._2, n._1, n._2, n._3)::nok, ok)
        case Right(r) =>
          if(currentConfigs(r.nodeId)._1 == r.nodeConfigId) { //config didn't change
            (nok, r.nodeId :: ok)
          } else { // config changed, we need to close it
            ( (currentConfigs(r.nodeId)._2, r.nodeId, r.nodeConfigId, r.beginDate) :: nok, ok)
          }
      }
    }

    //same reasoning for config info: only update the ones for witch the last id is not the correct one
    //we use configs because we must know if a nodeInfo is completly missing and add it
    type T = (Vector[NodeConfigIdInfo], NodeId)
    val (toAdd, toUpdate, okInfos) = configs.foldLeft((List[T](), List[T](), List[NodeId]() )) { case ((add, update, ok), next) =>
      configInfos.get(next.nodeId) match {
        case None => // add it
          ( (Vector(NodeConfigIdInfo(next.nodeConfigId, next.beginDate, None)), next.nodeId) :: add, update, ok)
        case Some(infos) =>
          infos.find(i => i.configId == next.nodeConfigId && i.endOfLife.isEmpty) match {
            case Some(info) => //no update
              (add, update, next.nodeId :: ok)
            case None => // update
              (add, (NodeConfigIdInfo(next.nodeConfigId, next.beginDate, None)+: infos, next.nodeId) :: update, ok)
          }
      }
    }


    // filter out node expected reports up to date
    val toSave = configs.filterNot(c => okConfigs.contains(c.nodeId))

    if (PolicyLogger.expectedReports.isTraceEnabled) {
      PolicyLogger.expectedReports.trace(s"Nodes with up-to-date expected configuration: [${okConfigs.sortBy(_.value).map(r => s"${r.value}").mkString("][")}]")
    }
    if (PolicyLogger.expectedReports.isDebugEnabled) {
      PolicyLogger.expectedReports.debug(s"Closing out of date expected node's configuration: [${toClose.sortBy(_._2.value).map(r => s"${r._2.value} : ${r._3.value}").mkString("][")}]")
      PolicyLogger.expectedReports.debug(s"Saving new expected node's configuration: [${toSave.sortBy(_.nodeId.value).map(r => s"${r.nodeId.value} : ${r.nodeConfigId.value}]").mkString("][")}]")
    }
    if (PolicyLogger.expectedReports.isTraceEnabled) {
      PolicyLogger.expectedReports.trace(s"Adding node configuration timeline info: [${toAdd.sortBy(_._2.value).map { case (i, n) => s"${n.value}:${i.map(_.configId.value).sorted.mkString(",")}" }.mkString("][")}]")
      PolicyLogger.expectedReports.trace(s"Updating node configuration timeline info: [${toUpdate.sortBy(_._2.value).map { case (i, n) => s"${n.value}(${i.size} entries):${i.map(_.configId.value).sorted.mkString(",")}" }.mkString("][")}]")
    }
    //now, mass update
    Right(for {
      closedConfigs <- Update[(DateTime, NodeId, NodeConfigId, DateTime)]("""
                          update nodeconfigurations set enddate = ?
                          where nodeid = ? and nodeconfigid = ? and begindate = ?
                       """).updateMany(toClose)
      savedConfigs  <- Update[NodeExpectedReports]("""
                         insert into nodeconfigurations (nodeid, nodeconfigid, begindate, enddate, configuration)
                         values ( ?, ?, ?, ?, ? )
                       """).updateMany(toSave)
      updatedInfo   <- Update[(Vector[NodeConfigIdInfo], NodeId)]("""
                         update nodes_info set config_ids = ? where node_id = ?
                       """).updateMany(toUpdate)
      addedInfo     <- Update[(Vector[NodeConfigIdInfo], NodeId)]("""
                         insert into nodes_info (config_ids, node_id) values (?, ?)
                       """).updateMany(toAdd)
    } yield {
      configs
    })
  }


  override def archiveNodeConfigurations(date:DateTime) : Box[Int] = {
    val dateAt_0000 = date.toString("yyyy-MM-dd")
    val copy = s"""
      insert into archivednodeconfigurations
        (nodeid, nodeconfigid, begindate, enddate, configuration)
        (select nodeid, nodeconfigid, begindate, enddate, configuration
           from nodeconfigurations
           where coalesce(endDate, '${dateAt_0000}') < '${dateAt_0000}'
        )
        """
    val delete = s"""
      delete from nodeconfigurations where coalesce(endDate, '${dateAt_0000}') < '${dateAt_0000}'
    """

    logger.debug(s"""Archiving NodeConfigurations with SQL query: [[
                 | ${copy}
                 |]] and: [[
                 | ${delete}
                 |]]""".stripMargin)

    (for {
       i <- (copy :: delete :: Nil).traverse(q => Update0(q, None).run).transact(xa).attempt.unsafeRunSync
    } yield {
       i
    }) match {
      case Left(ex) =>
        val msg ="Could not archive NodeConfigurations in the database, cause is " + ex.getMessage()
        logger.error(msg)
        Failure(msg, Full(ex), Empty)
      case Right(i)  =>
        logger.debug(s"Successfully archived ${i.sum} nodeconfigurations before ${dateAt_0000}")
        Full(i.sum)
     }
  }

  /**
   * Delete all NodeConfigurations closed before a date
   */
  override def deleteNodeConfigurations(date: DateTime) : Box[Int] = {

    val dateAt_0000 = date.toString("yyyy-MM-dd")
    val d1 = s"delete from archivednodeconfigurations where coalesce(endDate, '${dateAt_0000}') < '${dateAt_0000}'"
    val d2 = s"delete from nodeconfigurations where coalesce(endDate, '${dateAt_0000}') < '${dateAt_0000}'"

    logger.debug(s"""Deleting NodeConfigurations with SQL query: [[
                   | ${d1}
                   |]] and: [[
                   | ${d2}
                   |]]""".stripMargin)

    (for {
      i <- (d1 :: d2 :: Nil).traverse(q => Update0(q, None).run).transact(xa).attempt.unsafeRunSync
    } yield {
      i
    }) match  {
      case Left(ex) =>
        val msg ="Could not delete NodeConfigurations in the database, cause is " + ex.getMessage()
        logger.error(msg)
        Failure(msg, Full(ex), Empty)
      case Right(i)  =>
        logger.debug(s"Successfully deleted ${i.sum} nodeconfigurations before ${dateAt_0000}")
        Full(i.sum)
    }
  }

  /*
   * Archive nodecompliance whose run timestamp are before the given date
   */
  override def archiveNodeCompliances(date:DateTime) : Box[Int] = {
    val dateAt_0000 = date.toString("yyyy-MM-dd")
    val copy = s"""
      insert into archivednodecompliance
        (nodeid, runtimestamp, endoflife, runanalysis, summary, details)
        (select nodeid, runtimestamp, endoflife, runanalysis, summary, details
           from nodecompliance
           where coalesce(runtimestamp, '${dateAt_0000}') < '${dateAt_0000}'
        )
        """
    val delete = s"""
      delete from nodecompliance where coalesce(runtimestamp, '${dateAt_0000}') < '${dateAt_0000}'
    """

    logger.debug(s"""Archiving NodeCompliance with SQL query: [[
                 | ${copy}
                 |]] and: [[
                 | ${delete}
                 |]]""".stripMargin)

    (for {
       i <- (copy :: delete :: Nil).traverse(q => Update0(q, None).run).transact(xa).attempt.unsafeRunSync
    } yield {
       i
    }) match {
      case Left(ex) =>
        val msg ="Could not archive NodeCompliance in the database, cause is " + ex.getMessage()
        logger.error(msg)
        Failure(msg, Full(ex), Empty)
      case Right(i)  =>
        logger.debug(s"Successfully archived ${i.sum} Node Compliances before ${dateAt_0000}")
        Full(i.sum)
     }
  }

  /**
   * Delete all node compliance whose run timestamp is before a date
   */
  override def deleteNodeCompliances(date: DateTime) : Box[Int] = {

    val dateAt_0000 = date.toString("yyyy-MM-dd")
    val d1 = s"delete from archivednodecompliance where coalesce(runtimestamp, '${dateAt_0000}') < '${dateAt_0000}'"
    val d2 = s"delete from nodecompliance where coalesce(runtimestamp, '${dateAt_0000}') < '${dateAt_0000}'"

    logger.debug(s"""Deleting NodeCompliance with SQL query: [[
                   | ${d1}
                   |]] and: [[
                   | ${d2}
                   |]]""".stripMargin)

    (for {
      i <- (d1 :: d2 :: Nil).traverse(q => Update0(q, None).run).transact(xa).attempt.unsafeRunSync
    } yield {
      i
    }) match  {
      case Left(ex) =>
        val msg ="Could not delete NodeCompliance in the database, cause is " + ex.getMessage()
        logger.error(msg)
        Failure(msg, Full(ex), Empty)
      case Right(i)  =>
        logger.debug(s"Successfully deleted ${i.sum} Node Compliances before ${dateAt_0000}")
        Full(i.sum)
    }
  }

  /**
   * Delete all node compliance levels whose run timestamp is before a date
   */
  override def deleteNodeComplianceLevels(date: DateTime) : Box[Int] = {

    val dateAt_0000 = date.toString("yyyy-MM-dd")
    val d1 = s"delete from nodecompliancelevels where coalesce(runtimestamp, '${dateAt_0000}') < '${dateAt_0000}'"

    logger.debug(s"""Deleting NodeCompliance with SQL query: [[
                   | ${d1}
                   |]]""".stripMargin)

    (for {
      i <- Update0(d1, None).run.transact(xa).attempt.unsafeRunSync()
    } yield {
      i
    }) match  {
      case Left(ex) =>
        val msg ="Could not delete NodeCompliance in the database, cause is " + ex.getMessage()
        logger.error(msg)
        Failure(msg, Full(ex), Empty)
      case Right(i) =>
        logger.debug(s"Successfully deleted ${i} Node Compliances levels before ${dateAt_0000}")
        Full(i)
    }
  }

  /**
   * Delete all NodeConfigId that finished before date (meaning: all NodeConfigId that have one created before date)
   * This must be transactionnal to avoid conflict with other potential updates
   */
  override def deleteNodeConfigIdInfo(date:DateTime) : Box[Int] = {
    (for {
      allNodeConfigId       <- sql"""select node_id, config_ids from nodes_info""".query[(NodeId, String)].to[Vector]
      mapOfBeforeAfter      = allNodeConfigId.map { case (nodeId, nodeConfigIds) =>
                                ( nodeId
                                , NodeConfigIdSerializer.unserialize(nodeConfigIds).partition(config =>
                                   config.endOfLife.map( x => x.isBefore(date)).getOrElse(false))
                                )
                               }.toMap
      update                <- updateNodeConfigIdInfo(mapOfBeforeAfter.map{ case (nodeId, (old, current)) => (nodeId, current)})
    } yield {
      update.size
    }).transact(xa).attempt.unsafeRunSync
  }


  private[this] def updateNodeConfigIdInfo(configInfos: Map[NodeId, Vector[NodeConfigIdInfo]]): ConnectionIO[Set[NodeId]] = {
    if(configInfos.isEmpty) {
      Set.empty[NodeId].pure[ConnectionIO]
    } else {
      val params = configInfos.map { case (node, configs) =>
        (NodeConfigIdSerializer.serialize(configs), node.value)
      }.toList

      Update[(String, String)]("""
        update nodes_info set config_ids = ? where node_id = ?
      """).updateMany(params).map(_ => configInfos.keySet)
    }
  }

}

case class ReportAndNodeMapping(
    val pkId                : Long
  , val nodeJoinKey         : Long
  , val ruleId              : RuleId
  , val serial              : Int
  , val directiveId         : DirectiveId
  , val component           : String
  , val cardinality         : Int
  , val componentsValues    : Seq[String]
  , val unexpandedCptsValues: Seq[String]
  , val beginDate           : DateTime = DateTime.now()
  , val endDate             : Option[DateTime] = None
  , val nodeId              : NodeId
  , val nodeConfigVersions  : List[NodeConfigId]
) extends HashcodeCaching

case class ReportMapping(
    val pkId                : Int
  , val nodeJoinKey         : Int
  , val ruleId              : RuleId
  , val serial              : Int
  , val directiveId         : DirectiveId
  , val component           : String
  , val cardinality         : Int
  , val componentsValues    : Seq[String]
  , val unexpandedCptsValues: Seq[String]
  , val beginDate           : DateTime = DateTime.now()
  , val endDate             : Option[DateTime] = None
) extends HashcodeCaching

object ComponentsValuesSerialiser {

  def serializeComponents(ids:Seq[String]) : String = {
    implicit val formats = Serialization.formats(NoTypeHints)
    Serialization.write(ids)
  }
  /*
   * from a JSON array: [ "id1", "id2", ...], get the list of
   * components values Ids.
   * Never fails, but returned an empty list.
   */
  def unserializeComponents(ids:String) : List[String] = {
    if(null == ids || ids.trim == "") List()
    else {
      implicit val formats = DefaultFormats
      parse(ids).extract[List[String]]
    }
 }
}

object NodeConfigVersionsSerializer {

  def serialize(versions: List[NodeConfigId]): Array[Object] = {
      versions.map(_.value.trim).toArray
  }

  def unserialize(versions: java.sql.Array): List[NodeConfigId] = {
    if(null == versions) Nil
    else {
      versions.getArray.asInstanceOf[Array[String]].toList.map(NodeConfigId(_))
    }
  }
}

