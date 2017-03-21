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

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.reports._
import com.normation.rudder.repository.UpdateExpectedReportsRepository
import com.normation.rudder.repository.FindExpectedReportRepository
import com.normation.utils.HashcodeCaching
import com.normation.utils.Control.sequence
import net.liftweb.common._
import net.liftweb.json._
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import com.normation.rudder.domain.logger.TimingDebugLogger
import com.normation.rudder.db.Doobie
import com.normation.rudder.db.Doobie._
import scalaz.{Failure => _, _}, Scalaz._
import doobie.imports._
import scalaz.concurrent.Task
import doobie.postgres.pgtypes._
import com.normation.rudder.db.DB
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.domain.logger.PolicyLogger


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
    doobie    : Doobie
  , pgInClause: PostgresqlInClause
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

    nodeConfigIds.toList match { //"in" param can't be empty
      case Nil        => Full(Map())
      case nodeAndIds =>

        (for {
          configs <- query[\/[(NodeId, NodeConfigId, DateTime), NodeExpectedReports]](s"""
                       select nodeid, nodeconfigid, begindate, enddate, configuration
                       from nodeconfigurations
                       where (nodeid, nodeconfigid) in (${nodeAndIds.map(x => s"('${x.nodeId.value}','${x.version.value}')").mkString(",")} )
                     """).vector
        }yield {
          val configsMap = (configs.flatMap {
            case -\/((id, c, _)) =>
              logger.error(s"Error when deserializing JSON for expected node configuration ${id.value} (${c.value})")
              None
            case \/-(x) => Some((NodeAndConfigId(x.nodeId, x.nodeConfigId), x))
          }).toMap
          nodeConfigIds.map(id => (id, configsMap.get(id))).toMap
        }).attempt.transact(xa).run
    }
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
        val t0 = System.currentTimeMillis
        (for {
          configs <- (Fragment.const(s"with tempnodeid (id) as (values ${nodeIds.map(x => s"('${x.value}')").mkString(",")})") ++
                      fr"""
                       select nodeid, nodeconfigid, begindate, enddate, configuration
                       from nodeconfigurations
                       inner join tempnodeid on tempnodeid.id = nodeconfigurations.nodeid
                       where enddate is null"""
                     ).query[\/[(NodeId, NodeConfigId, DateTime), NodeExpectedReports]].vector
        } yield {
          val t1 =  System.currentTimeMillis
          TimingDebugLogger.trace(s"Compliance: query to get current expected reports: ${t1-t0}ms")
          val configsMap = (configs.flatMap {
            case -\/((id, c, _)) =>
              logger.error(s"Error when deserializing JSON for expected node configuration ${id.value} (${c.value})")
              None
            case \/-(x) => Some((x.nodeId, x))
          }).toMap
          nodeIds.map(id => (id, configsMap.get(id))).toMap
        }).attempt.transact(xa).run
    }
  }


  override def findCurrentNodeIds(ruleId : RuleId) : Box[Set[NodeId]] = {
    sql"""
      select distinct nodeid from nodeconfigurations
      where enddate is null and configuration like ${"%"+ruleId.value+"%"}
    """.query[NodeId].to[Set].attempt.transact(xa).run
  }


  /*
   * Retrieve the list of node config ids
   */
  override def getNodeConfigIdInfos(nodeIds: Set[NodeId]): Box[Map[NodeId, Option[Vector[NodeConfigIdInfo]]]] = {
    if(nodeIds.isEmpty) Full(Map.empty[NodeId, Option[Vector[NodeConfigIdInfo]]])
    else {
      (for {
        entries <-query[(NodeId, String)](s"""select node_id, config_ids from nodes_info
                                              where ${in("node_id", nodeIds.map(_.value))}""").vector
      } yield {
        val res = entries.map{ case(nodeId, config) => (nodeId, NodeConfigIdSerializer.unserialize(config)) }.toMap
        nodeIds.map(n => (n, res.get(n))).toMap
      }).attempt.transact(xa).run
    }
  }
}




class UpdateExpectedReportsJdbcRepository(
    doobie     : Doobie
  , pgInClause: PostgresqlInClause
) extends UpdateExpectedReportsRepository with Loggable {

  import doobie._
  import pgInClause._
  import Doobie._

  override def closeNodeConfigurations(nodeId: NodeId): Box[NodeId] = {
    sql"""
      update nodeconfigurations set enddate = ${DateTime.now} where nodeid = ${nodeId} and enddate is null
    """.update.run.attempt.transact(xa).run.map( _ => nodeId )
  }


  def saveNodeExpectedReports(configs: List[NodeExpectedReports]): Box[List[NodeExpectedReports]] = {

    PolicyLogger.expectedReports.debug(s"Saving ${configs.size} nodes expected reports")
    configs.map(x => s"('${x.nodeId.value}')") match {
      case Nil     => Full(Nil)
      case nodeIds =>

        val withFrag = Fragment.const(s"with tempnodeid (id) as (values ${nodeIds.mkString(",")})")

        type A = \/[(NodeId, NodeConfigId, DateTime), NodeExpectedReports]
        val getConfigs: \/[Throwable, List[A]] = (
                        withFrag ++ fr"""
                           select nodeid, nodeconfigid, begindate, enddate, configuration
                           from nodeconfigurations
                           inner join tempnodeid on tempnodeid.id = nodeconfigurations.nodeid
                           where enddate is NULL"""
                        ).query[A].list.attempt.transact(xa).run

        type B = (NodeId, Vector[NodeConfigIdInfo])
        val getInfos: \/[Throwable, List[B]] = (
                            withFrag ++ fr"""
                              select node_id, config_ids from nodes_info
                              inner join tempnodeid on tempnodeid.id = nodes_info.node_id
                            """
                          ).query[B].list.attempt.transact(xa).run

        // common part: find old configs and node config info for all config to update
        val time_0 = System.currentTimeMillis
        for {
          oldConfigs  <- getConfigs
          time_1      =  System.currentTimeMillis
          _           =  TimingDebugLogger.debug(s"saveNodeExpectedReports: find old nodes config in ${time_1-time_0}ms")
          configInfos <- getInfos
          time_2      =  System.currentTimeMillis
          _           =  TimingDebugLogger.debug(s"saveNodeExpectedReports: find old config info ${time_2-time_1}ms")
          updated     <- doUpdateNodeExpectedReports(configs, oldConfigs, configInfos.toMap)
          time_3      =  System.currentTimeMillis
          _           =  TimingDebugLogger.debug(s"saveNodeExpectedReports: save configs etc in ${time_3-time_2}ms")
        } yield {
          configs
        }
    }
  }

  /*
   * Save a nodeExpectedReport, closing the previous one is
   * opened for that node (and a different nodeConfigId)
   */
  def doUpdateNodeExpectedReports(
      configs    : List[NodeExpectedReports]
    , oldConfigs : List[\/[(NodeId, NodeConfigId, DateTime), NodeExpectedReports]]
    , configInfos: Map[NodeId, Vector[NodeConfigIdInfo]]
  ): \/[Throwable, List[NodeExpectedReports]] = {

    import com.normation.rudder.domain.reports.ExpectedReportsSerialisation._
    import Doobie._
    import doobie.xa

    val currentConfigs = configs.map( c => (c.nodeId, (c.nodeConfigId, c.beginDate)) ).toMap


    // we want to close all error nodeexpectedreports, and all non current
    // we keep valid current identified by nodeId, we won't have to save them afterward.
    val (toClose, okConfigs) = ( (List[(DateTime, NodeId, NodeConfigId, DateTime)](), List[NodeId]() ) /: oldConfigs) { case ((nok, ok), next) =>
      next match {
        case -\/(n) => ((currentConfigs(n._1)._2, n._1, n._2, n._3)::nok, ok)
        case \/-(r) =>
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
    val (toAdd, toUpdate, okInfos) = ( (List[T](), List[T](), List[NodeId]() ) /: configs ) { case ((add, update, ok), next) =>
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

    PolicyLogger.expectedReports.trace(s"Nodes with up-to-date expected configuration: [${okConfigs.sortBy(_.value).map(r => s"${r.value}").mkString("][")}]")
    PolicyLogger.expectedReports.debug(s"Closing out of date expected node's configuration: [${toClose.sortBy(_._2.value).map(r => s"${r._2.value} : ${r._3.value}").mkString("][")}]")
    PolicyLogger.expectedReports.debug(s"Saving new expected node's configuration: [${toSave.sortBy(_.nodeId.value).map(r => s"${r.nodeId.value} : ${r.nodeConfigId.value}]").mkString("][")}]")
    PolicyLogger.expectedReports.trace(s"Adding node configuration timeline info: [${toAdd.sortBy(_._2.value).map{ case(i,n) => s"${n.value}:${i.map(_.configId.value).sorted.mkString(",")}"}.mkString("][")}]")
    PolicyLogger.expectedReports.trace(s"Updating node configuration timeline info: [${toUpdate.sortBy(_._2.value).map{ case(i,n) => s"${n.value}(${i.size} entries):${i.map(_.configId.value).sorted.mkString(",")}"}.mkString("][")}]")

    //now, mass update
    (for {
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
    }).attempt.transact(xa).run
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
       i <- (copy :: delete :: Nil).traverse(q => Update0(q, None).run).attempt.transact(xa).run
    } yield {
       i
    }) match {
      case -\/(ex) =>
        val msg ="Could not archive NodeConfigurations in the database, cause is " + ex.getMessage()
        logger.error(msg)
        Failure(msg, Full(ex), Empty)
      case \/-(i)  => Full(i.sum)
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
      i <- (d1 :: d2 :: Nil).traverse(q => Update0(q, None).run).attempt.transact(xa).run
    } yield {
      i
    }) match  {
      case -\/(ex) =>
        val msg ="Could not delete NodeConfigurations in the database, cause is " + ex.getMessage()
        logger.error(msg)
        Failure(msg, Full(ex), Empty)
      case \/-(i)  => Full(i.sum)
    }
  }

  /**
   * Delete all NodeConfigId that finished before date (meaning: all NodeConfigId that have one created before date)
   * This must be transactionnal to avoid conflict with other potential updates
   */
  override def deleteNodeConfigIdInfo(date:DateTime) : Box[Int] = {
    (for {
      allNodeConfigId       <- sql"""select node_id, config_ids from nodes_info""".query[(NodeId, String)].vector
      mapOfBeforeAfter      = allNodeConfigId.map { case (nodeId, nodeConfigIds) =>
                                ( nodeId
                                , NodeConfigIdSerializer.unserialize(nodeConfigIds).partition(config =>
                                   config.endOfLife.map( x => x.isBefore(date)).getOrElse(false))
                                )
                               }.toMap
      update                <- updateNodeConfigIdInfo(mapOfBeforeAfter.map{ case (nodeId, (old, current)) => (nodeId, current)})
    } yield {
      update.size
    }).attempt.transact(xa).run
  }


  private[this] def updateNodeConfigIdInfo(configInfos: Map[NodeId, Vector[NodeConfigIdInfo]]): ConnectionIO[Set[NodeId]] = {
    if(configInfos.isEmpty) {
      Set.empty[NodeId].point[ConnectionIO]
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

