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
import com.normation.rudder.repository.NodeConfigIdInfo
import com.normation.rudder.repository.RoNodeConfigIdInfoRepository
import com.normation.rudder.repository.WoNodeConfigIdInfoRepository
import com.normation.rudder.repository.NodeConfigIdInfo
import com.normation.rudder.repository.NodeConfigIdInfo
import com.normation.rudder.domain.logger.TimingDebugLogger
import com.normation.rudder.db.Doobie
import com.normation.rudder.db.Doobie._
import scalaz.{Failure => _, _}, Scalaz._
import doobie.imports._
import scalaz.concurrent.Task
import doobie.contrib.postgresql.pgtypes._
import com.normation.rudder.db.DB


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
) extends FindExpectedReportRepository with Loggable with RoNodeConfigIdInfoRepository {

  import pgInClause._
  import doobie._

  /*
   * Retrieve the list of node config ids
   */
  override def getNodeConfigIdInfos(nodeIds: Set[NodeId]): Box[Map[NodeId, Option[Seq[NodeConfigIdInfo]]]] = {
    getNodeConfigIdInfosIO(nodeIds).attempt.transact(xa).run
  }


  /**
   * This is the implementation of getNodeConfigIdInfos but without commiting the
   * transaction so that it can be used in a sequence of queries.
   */
  def getNodeConfigIdInfosIO(nodeIds: Set[NodeId]): ConnectionIO[Map[NodeId, Option[Seq[NodeConfigIdInfo]]]] = {
    if(nodeIds.isEmpty) Map.empty[NodeId, Option[Seq[NodeConfigIdInfo]]].point[ConnectionIO]
    else {
      (for {
        entries <-query[(NodeId, String)](s"""select node_id, config_ids from nodes_info
                                              where ${in("node_id", nodeIds.map(_.value))}""").vector
      } yield {
        val res = entries.map{ case(nodeId, config) => (nodeId, NodeConfigIdSerializer.unserialize(config)) }.toMap
        nodeIds.map(n => (n, res.get(n))).toMap
      })
    }
  }


  /*
   * Retrieve the expected reports by config version of the nodes
   *
   * The current version seems highly inefficient.
   *
   */
  override def getExpectedReports(nodeConfigIds: Set[NodeAndConfigId], filterByRules: Set[RuleId]): Box[Map[NodeId, Map[NodeConfigId, Map[SerialedRuleId, RuleNodeExpectedReports]]]] = {
    if(nodeConfigIds.isEmpty) Full(Map())
    else {

      val rulePredicate = if(filterByRules.isEmpty) "" else " where " + in("ruleid", filterByRules.map(_.value))

      val query_subscript_configids = query[(Long, NodeId, Option[List[String]])](s"""
        select E.pkid, NNN.nodeid, NNN.nodeconfigids
        from expectedreports E
        inner join (
            select N.nodejoinkey, N.nodeid, N.nodeconfigids
            from expectedreportsnodes N
            where (N.nodeconfigids && '{ ${nodeConfigIds.map("\""+ _.version.value + "\"").mkString(", ") } }')
        ) as NNN
        on E.nodejoinkey = NNN.nodejoinkey
        ${rulePredicate}
      """)

      def getReportsByIds(reportIds: Set[Long]): ConnectionIO[Vector[DB.ExpectedReports[Long]]] = {
        if(reportIds.isEmpty) {
          Vector.empty[DB.ExpectedReports[Long]].point[ConnectionIO]
        } else {
          query[DB.ExpectedReports[Long]](s"""
            select E.pkid, E.nodejoinkey, E.ruleid, E.serial, E.directiveid, E.component, E.cardinality
                 , E.componentsvalues, E.unexpandedComponentsValues, E.begindate, E.enddate
            from expectedreports E
            where ${inNumber("E.pkid", reportIds) }
          """).vector
        }
      }

      val t0 = System.currentTimeMillis()
      val action = for {
        reportsAndConfigIds <- query_subscript_configids.vector
        t1                  =  System.currentTimeMillis
        _                   =  TimingDebugLogger.debug(s"GetExpectedReports: configIds: ${t1-t0}ms")
        reportIds           =  reportsAndConfigIds.map( _._1 ).toSet
        t2                  =  System.currentTimeMillis
        expectedReports     <- getReportsByIds(reportIds)
        t3                  =  System.currentTimeMillis
        _                   =  TimingDebugLogger.debug(s"GetExpectedReports: expectedreports: ${t3-t2}ms")
      } yield {

        val t4 =  System.currentTimeMillis

        val res= toNodeExpectedReports(nodeConfigIds, reportsAndConfigIds, expectedReports)

        val t5 =  System.currentTimeMillis
        TimingDebugLogger.debug(s"GetExpectedReports: toNodeExpectedReports: ${t5-t4}ms")

        res
      }
      action.attempt.transact(xa).run
    }
  }

  /**
   * Build RuleNodeExpectedReports from a list of rows from DB.
   */
  private[this] def toNodeExpectedReports(
      nodeConfigIds      : Set[NodeAndConfigId]
    , reportsAndConfigIds: Vector[(Long, NodeId, Option[List[String]])]
    , expectedReports    : Vector[DB.ExpectedReports[Long]]
  ) : Map[NodeId, Map[NodeConfigId, Map[SerialedRuleId, RuleNodeExpectedReports]]] = {
    /*
     * It's a simple grouping by nodeId, then by nodeConfigId, then by
     * ruleId/seria
     */

    //it's several order of magnitude quicker to build groupBy map
    //than to filter in the loop
    val t0 = System.currentTimeMillis
    val nodeAndConfigIds = nodeConfigIds.groupBy { _.nodeId }.mapValues { _.toSeq.map(_.version) }
    val reports = expectedReports.map(x => (x.pkId, x)).toMap
    val reportsForNode = reportsAndConfigIds.map { case (pkId, nodeId, versions) =>
      val r = reports(pkId) //that works because
      ReportAndNodeMapping(
         r.pkId
       , r.nodeJoinKey
       , r.ruleId
       , r.serial
       , r.directiveId
       , r.component
       , r.cardinality
       , ComponentsValuesSerialiser.unserializeComponents(r.componentsValues)
       , ComponentsValuesSerialiser.unserializeComponents(r.unexpandedComponentsValues)
       , r.beginDate
       , r.endDate
       , nodeId
       , versions match {
           case None       => Nil
           case Some(list) => list.map(x => NodeConfigId(x))
         }
      )}.groupBy { _.nodeId }

    val t1 = System.currentTimeMillis
    TimingDebugLogger.debug(s"GetExpectedReports: toNodeExpectedReports: groupBy: ${t1-t0}ms")

    var t_byConfigId = 0l
    var n = 0

    val res = nodeAndConfigIds.map { case (nodeId, configIds) =>
      val tx_0 = System.currentTimeMillis

      val byConfigId = configIds.map { configId =>
        val reportsForId = reportsForNode.getOrElse(nodeId, Seq()).filter { _.nodeConfigVersions.exists { _ == configId } }
        val rnExpectedReports = reportsForId.groupBy( r => SerialedRuleId(r.ruleId, r.serial)).map { case (ruleId, dirReports) =>
          val directives = dirReports.groupBy(x => x.directiveId).map { case (directiveId, lines) =>
            // here I am on the directiveId level, all lines that have the same RuleId, Serial, NodeJoinKey, DirectiveId are
            // for the same directive, and must be put together
            DirectiveExpectedReports(directiveId, lines.map( x =>
              ComponentExpectedReport(x.component, x.cardinality, x.componentsValues, x.unexpandedCptsValues)
            ).distinct /* because we have the cardinality to deals with mutltiplicity */ )
          }
          ( ruleId ->
            RuleNodeExpectedReports(
                ruleId.ruleId
              , ruleId.serial
              , directives.toSeq
              , dirReports.head.beginDate // ".head" ok because of groupBy
              , dirReports.head.endDate
            )
          )
        }
        (configId, rnExpectedReports)
      }.toMap

      if(TimingDebugLogger.isDebugEnabled) {
        val tx_1 = System.currentTimeMillis
        t_byConfigId = t_byConfigId + tx_1 - tx_0
        n += 1
      }
      (nodeId, byConfigId)
    }
    TimingDebugLogger.debug(s"GetExpectedReports: toNodeExpectedReports: loop: ${t_byConfigId}ms for ${n} iterations ${if(n==0) "" else s"(mean: ${t_byConfigId/n})"}")
    res
  }


  override def findCurrentNodeIds(ruleId : RuleId) : Box[Set[NodeId]] = {
    sql"""
      select distinct N.nodeid from expectedreports E
      inner join expectedreportsnodes N
      on E.nodejoinkey = N.nodejoinkey
      where E.enddate is null and E.ruleid = ${ruleId.value}
    """.query[NodeId].to[Set].attempt.transact(xa).run
  }

  /**
   * Return current expected reports (the one still pending) for this Rule
   *
   * Only used in UpdateExpectedReportsJdbcRepository
   */
  private[jdbc] def findCurrentExpectedReports(ruleId : RuleId) : ConnectionIO[Option[RuleExpectedReports]] = {
    for {
      seq <- getRuleExpectedReports("where enddate is null and ruleid = ?", ruleId.value)
    } yield {
      seq.size match {
          case 0 => None
          case 1 => Some(seq.head)
          case n => throw new IllegalArgumentException(s"Inconsistency in the database: several (${n}) expected reports were found for rule '${ruleId.value}'")
      }
    }
  }

  /**
   * Return all the expected reports between the two dates
   */
  override def findExpectedReports(beginDate : DateTime, endDate : DateTime) : Box[Seq[RuleExpectedReports]] = {
    getRuleExpectedReports("where beginDate < ? and coalesce(endDate, ?) >= ? ", (endDate, beginDate, beginDate)).attempt.transact(xa).run
  }

  private[this] def getRuleExpectedReports[T](whereClause: String, param: T)(implicit comp: Composite[T]): ConnectionIO[Vector[RuleExpectedReports]] = {
    type EXNR = (DB.ExpectedReports[Long], NodeId, Option[List[String]])
    val expectedReportsQuery ="""select
          E.pkid, E.nodejoinkey, E.ruleid, E.serial, E.directiveid, E.component, E.cardinality
        , E.componentsvalues, E.unexpandedComponentsValues, E.begindate, E.enddate
        , N.nodeid, N.nodeconfigids
      from expectedreports E
      inner join expectedreportsnodes N
      on E.nodejoinkey = N.nodejoinkey """ + whereClause

    (for {
      entries <- Query[T, EXNR](expectedReportsQuery, None).toQuery0(param).vector
    } yield {
      toExpectedReports(entries)
    })
  }

  private[this] def toExpectedReports(entries: Vector[(DB.ExpectedReports[Long], NodeId, Option[List[String]])]) : Vector[RuleExpectedReports] = {
    //just an alias
    val toseq =  ComponentsValuesSerialiser.unserializeComponents _

    entries.groupBy( entry => SerialedRuleId(entry._1.ruleId, entry._1.serial)).map { case (key, seq) =>
      // now we need to group elements of the seq together,  based on nodeJoinKey
      val directivesOnNode = seq.groupBy(x => x._1.nodeJoinKey).map { case (nodeJoinKey, mappedEntries) =>
        // need to convert to group everything by directiveId, the convert to DirectiveExpectedReports
        val directiveExpectedReports = mappedEntries.groupBy(x => x._1.directiveId).map { case (directiveId, lines) =>
          // here I am on the directiveId level, all lines that have the same RuleId, Serial, NodeJoinKey, DirectiveId are
          // for the same directive, and must be put together
          DirectiveExpectedReports(directiveId, lines.map{ case(x, _, _)  =>
            ComponentExpectedReport(x.component, x.cardinality, toseq(x.componentsValues), toseq(x.unexpandedComponentsValues))
          }.distinct /* because we have the cardinality for that */ )
        }
        val nodeConfigurationIds = mappedEntries.groupBy( _._2).mapValues { lines =>
          //we should have only one line at that level, but else, merger versions
          lines.map(x => (x._1.nodeJoinKey, x._3.getOrElse(Nil))).reduce { (current, next) =>
            if(current._1 >= next._1) {
              (current._1, current._2 ::: next._2)
            } else {
              (current._1, next._2 ::: current._2)
            }
          }._2.headOption.map(x => NodeConfigId(x))
        }

        DirectivesOnNodes(nodeJoinKey, nodeConfigurationIds, directiveExpectedReports.toSeq)
      }
      RuleExpectedReports(
          key.ruleId
        , key.serial
        , directivesOnNode.toSeq
        , seq.head._1.beginDate
        , seq.head._1.endDate
      )
    }.toVector
  }
}


class UpdateExpectedReportsJdbcRepository(
    doobie     : Doobie
  , findReports: FindExpectedReportsJdbcRepository
) extends UpdateExpectedReportsRepository with Loggable with WoNodeConfigIdInfoRepository {

  import doobie._

  /**
   * Delete all expected reports closed before a date
   */
  override def deleteExpectedReports(date: DateTime) : Box[Int] = {
    // Find all nodejoinkey that have expected reports closed more before date
    (for {
      d1 <- sql"delete from expectedreports where coalesce(endDate, ${date}) < ${date}".update.run
      d2 <- if(d1 == 0) {
              0.point[ConnectionIO]
            } else {
              logger.debug(s"Deleted ${d1} expected reports closed before ${date}")
              sql"""
                delete from expectedreportsnodes where nodejoinkey not in (select nodejoinkey from expectedreports)
              """.update.run
            }
    } yield {
      logger.debug(s"Deleted ${d2} expected reports node")
      d1
    }).attempt.transact(xa).run
  }


  override def findAllCurrentExpectedReportsWithNodesAndSerial(): Map[RuleId, (Int, Int, Map[NodeId, NodeConfigVersions])] = {
    (for {
      entries  <- sql"""
                    select distinct ruleid, serial, nodejoinkey
                    from expectedreports where enddate is null
                  """.query[(RuleId, Int, Int)].vector
      nodeList <- entries.toNel match {
                      case None      => List.empty[(Int, NodeConfigVersions)].point[ConnectionIO]
                      case Some(nel) =>
                        val nodeJoin = nel.map(_._3)
                        implicit val nodeJoinParam = Param.many(nodeJoin)
                        sql"""
                          select nodejoinkey, nodeid, nodeconfigids
                          from expectedreportsnodes
                          where nodejoinkey in (${nodeJoin: nodeJoin.type})
                        """.query[(Int, NodeConfigVersions)].vector
                  }
    } yield {
      val nodeMap = nodeList.groupBy(_._2.nodeId).mapValues { seq => //seq cannot be empty due to groupBy
        //merge version together based on nodejoin values
        (seq.reduce[(Int, NodeConfigVersions)] { case ( (maxK, versions), (newK, newConfigVersions) ) =>
          if(maxK >= newK) {
            (maxK, versions.copy(versions = versions.versions ::: newConfigVersions.versions))
          } else {
            (newK, versions.copy(versions = newConfigVersions.versions ::: versions.versions))
          }
        })
      }.values.groupBy(_._1).mapValues(_.map{case(_, NodeConfigVersions(id,v)) => (id,v)}.toMap)

      entries.map { case(ruleId, serial, nodeJoin) =>
        (ruleId, (serial, nodeJoin, nodeMap(nodeJoin).map{ case(nodeId, versions) => (nodeId, NodeConfigVersions(nodeId, versions))}.toMap ))
      }.toMap
    }).transact(xa).run //nobox ??
  }


  /**
   * Simply set the endDate for the expected report for this conf rule
   * @param ruleId
   */
  override def closeExpectedReport(ruleId : RuleId, generationTime: DateTime) : Box[Unit] = {
    logger.debug(s"Closing expected report for rules '${ruleId.value}'")
    (for {
      optExpected <- findReports.findCurrentExpectedReports(ruleId)
      results     <- optExpected match {
                       case None =>
                         logger.warn(s"Cannot close a non existing entry '${ruleId.value}'")
                         0.point[ConnectionIO]
                       case Some(x) =>
                         sql"""
                           update expectedreports  set enddate = ${DateTime.now}
                           where serial = ${x.serial} and ruleId = ${ruleId.value}
                         """.update.run
                     }
    } yield {
      ()
    }).attempt.transact(xa).run
  }

  /**
   * This utilitary class is used only to compare what is already saved in the
   * DB and compare it with what is to be saved
   */
  private[this] final case class Comparator(
      nodeConfigId : (NodeId, Option[NodeConfigId])
    , directiveId  : DirectiveId
    , componentName: String
  )

  /**
   * Insert new expectedReports in base.
   *
   * We need to check that we are not querying over and over the same rules.
   * Andperhaps more query/save could be did in one pass in place of
   * sequence(..) { saveExpectedReports(...) }
   */
  override def saveExpectedReports(
      ruleId                  : RuleId
    , serial                  : Int
    , generationTime          : DateTime
    , directiveExpectedReports: Seq[DirectiveExpectedReports]
    , nodeConfigIds           : Seq[NodeAndConfigId]
  ) : Box[RuleExpectedReports] = {
     logger.debug(s"Saving expected report for rule '${ruleId.value}'")
// TODO : store also the unexpanded
     findReports.findCurrentExpectedReports(ruleId).flatMap {
       case Some(x) =>
         // I need to check I'm not having duplicates
         // easiest way : unfold all, and check intersect
         val toInsert = directiveExpectedReports.flatMap { case DirectiveExpectedReports(dir, comp) =>
           comp.map(x => (dir, x.componentName))
         }.flatMap { case (dir, compName) =>
           nodeConfigIds.map(id => Comparator((id.nodeId, Some(id.version)), dir, compName))
         }

         val comparator = x.directivesOnNodes.flatMap { case DirectivesOnNodes(_, configs, dirExp) =>
           dirExp.flatMap { case DirectiveExpectedReports(dir, comp) =>
             comp.map(x => (dir, x.componentName))
           }.flatMap { case (dir, compName) =>
             configs.map(id => Comparator(id, dir, compName))
           }
         }

         toInsert.intersect(comparator) match {
           case seq if seq.size > 0 =>
             val msg = s"Inconsistency in the database : cannot save an already existing expected report for rule '${ruleId.value}'"
             logger.error(msg)
             logger.debug("Intersecting values are " + seq)
             throw new RuntimeException(msg)

           case _ => // Ok
             createExpectedReports(ruleId, serial, generationTime, directiveExpectedReports, nodeConfigIds)
         }

      case None =>
          createExpectedReports(ruleId, serial, generationTime, directiveExpectedReports, nodeConfigIds)
     }.attempt.transact(xa).run
  }

  private[this] def createExpectedReports(
      ruleId                  : RuleId
    , serial                  : Int
    , generationTime          : DateTime
    , directiveExpectedReports: Seq[DirectiveExpectedReports]
    , nodeConfigIds           : Seq[NodeAndConfigId]
  ) : ConnectionIO[RuleExpectedReports] = {

    //all expectedReports
    val expectedReports = (nodeJoinKey: Int) => (for {
      policy    <- directiveExpectedReports
      component <- policy.components
    } yield {
      DB.ExpectedReports[Unit](
          (),nodeJoinKey, ruleId, serial, policy.directiveId, component.componentName
        , component.cardinality, ComponentsValuesSerialiser.serializeComponents(component.componentsValues)
        , ComponentsValuesSerialiser.serializeComponents(component.unexpandedComponentsValues)
        , generationTime, None
      )
    }).toList

    //all expectedReportsNodes
    val expectedReportsNodes = (nodeJoinKey: Int) => (for {
      config <- nodeConfigIds
    } yield {
      DB.ExpectedReportsNodes(nodeJoinKey, config.nodeId.value, List(config.version.value.trim) )
    }).toList

    for {
      //get the new nodeJoinKey to use in both expectedReports and expectedReportsNodes
      nodeJoinKey <- sql"select nextval('ruleVersionId')".query[Int].unique
      // Create the lines for the mapping
      expReports  <- DB.insertExpectedReports(expectedReports(nodeJoinKey))
      // save new nodeconfiguration - no need to check for existing version for them
      expNodes    <- DB.insertExpectedReportsNode(expectedReportsNodes(nodeJoinKey))
      // check for the consistancy of the insert, and return the build expected report for use
      ruleReport  <- findReports.findCurrentExpectedReports(ruleId)
    } yield {
      ruleReport match {
          case Some(x) => x
          case None    => throw new RuntimeException(s"Could not fetch the freshly saved expected report for rule '${ruleId.value}'")
      }
    }
  }

  override def addNodeConfigIdInfo(updatedNodeConfigs: Map[NodeId, NodeConfigId], generationTime: DateTime): Box[Set[NodeId]] = {
    (for {
      configs  <- findReports.getNodeConfigIdInfosIO(updatedNodeConfigs.keySet)
      olds     =  configs.collect { case (id, Some(seq)) =>
                    val next = updatedNodeConfigs(id) //can't fail because updatedNodeConfigs#keySet is a superset of configs#keySet
                    (id, seq :+ NodeConfigIdInfo(next, generationTime, None))
                  }.toMap
      savedOld <- updateNodeConfigIdInfo(olds)
      news     =  (configs.keySet--olds.keySet).map { id => (id, Seq(NodeConfigIdInfo(updatedNodeConfigs(id), generationTime, None)))}.toMap
      savedNew <- createNodeConfigIdInfo(news)
    } yield {
      savedOld ++ savedNew
    }).attempt.transact(xa).run
  }

  /*
   * Handle node config id update
   */

  private[this] def createNodeConfigIdInfo(configInfos: Map[NodeId, Seq[NodeConfigIdInfo]]): ConnectionIO[Set[NodeId]] = {
    if(configInfos.isEmpty) {
      Set.empty[NodeId].point[ConnectionIO]
    } else {
      val params = configInfos.map { case (node, configs) =>
        (node.value, NodeConfigIdSerializer.serialize(configs))
      }.toList

      Update[(String, String)]("""
        insert into nodes_info (node_id, config_ids) values (?, ?)
      """).updateMany(params).map(_ => configInfos.keySet)
    }
  }

  private[this] def updateNodeConfigIdInfo(configInfos: Map[NodeId, Seq[NodeConfigIdInfo]]): ConnectionIO[Set[NodeId]] = {
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
      purgedExpectedReports <- purgeNodeConfigId(mapOfBeforeAfter.map{ case (nodeId, (old, current)) => (nodeId, old.map(x => x.configId))})
    } yield {
      update.size
    }).attempt.transact(xa).run
  }

  /**
   * Update the set of nodes to have the given node ConfigVersion.
   * As we don't have other information, we will update "last"
   * (i.e row with the biggest nodeJoin key).
   */
  override def updateNodeConfigVersion(toUpdate: Seq[(Int, NodeConfigVersions)]): Box[Seq[(Int,NodeConfigVersions)]] = {
    updateNodeConfigVersionIO(toUpdate).attempt.transact(xa).run
  }

  private[this] def updateNodeConfigVersionIO(toUpdate: Seq[(Int, NodeConfigVersions)]): ConnectionIO[Seq[(Int,NodeConfigVersions)]] = {
    val params = toUpdate.map { case (nodeJoinKey, config) =>
      (config.versions.map(_.value), nodeJoinKey, config.nodeId.value)
    }.toList

    Update[(List[String], Int, String)]("""
      update expectedreportsnodes set nodeconfigids = ? where nodejoinkey = ? and nodeid = ?
    """).updateMany(params).map( _ => toUpdate)
  }

  /**
   * From a map of Node -> RemovedNodeConfigId, remove all those config id from the expected reports
   */
  private[jdbc] def purgeNodeConfigId(nodeConfigIdToRemove : Map[NodeId, Seq[NodeConfigId]]) = {


    // extract all the config to remove
    val nodeConfigIdToRemoveList = nodeConfigIdToRemove.values.flatten.toList

    nodeConfigIdToRemoveList.toNel match {
      case None => //abort
        Map.empty[NodeId, Seq[NodeConfigId]].point[ConnectionIO]

      case Some(allConfigToRemove) =>
        implicit val nodesParam = Param.many(allConfigToRemove)

        for {
          // fetch all the nodejoinkey -> configIdversion in the expected reports table
          currentNodeConfigId    <- sql"""
                                      select nodejoinkey, nodeid, nodeconfigids
                                      from expectedreportsnodes where nodeid in (${allConfigToRemove: allConfigToRemove.type})
                                    """.query[(Int, NodeConfigVersions)].vector

          // list all those that contains version that must be removed
          nodesToClean           =  currentNodeConfigId.filter{ case (nodeJoinKey, nodeConfigVersion) =>
                                      nodeConfigVersion.versions.exists(x => nodeConfigIdToRemoveList.contains(x))
                                    }
          // filter out all the version to remove
          cleanedReports         =  nodesToClean.map { case (nodeJoinKey, nodeConfigVersion) =>
                                      (nodeJoinKey, nodeConfigVersion.copy(versions = nodeConfigVersion.versions.filterNot(x =>  nodeConfigIdToRemoveList.contains(x))) )
                                    }

          // we update these config
          cleanedExpectedReports <- updateNodeConfigVersionIO(cleanedReports)
        } yield {
          cleanedExpectedReports
        }
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
  def unserializeComponents(ids:String) : Seq[String] = {
    if(null == ids || ids.trim == "") Seq()
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

object NodeConfigIdSerializer {

  //date are ISO format
  private[this] val isoDateTime = ISODateTimeFormat.dateTime

  /*
   * In the database, we only keep creation time.
   * Interval are build with the previous/next.
   *
   * The format is :
   * { "configId1":"creationDate1", "configId2":"creationDate2", ... }
   */

  def serialize(ids:Seq[NodeConfigIdInfo]) : String = {
    implicit val formats = Serialization.formats(NoTypeHints)

    val m = ids.map { case NodeConfigIdInfo(NodeConfigId(id), creation, _) =>
      (id, creation.toString(isoDateTime))
    }.toMap

    Serialization.write(m)
  }

  /*
   * from a JSON object: { "id1":"date1", "id2":"date2", ...}, get the list of
   * components values Ids.
   * May return an empty object
   */
  def unserialize(ids:String) : Seq[NodeConfigIdInfo] = {

    if(null == ids || ids.trim == "") Seq()
    else {
      implicit val formats = DefaultFormats
      val configs = parse(ids).extract[Map[String, String]].toList.flatMap { case (id, date) =>
        try {
          Some((NodeConfigId(id), isoDateTime.parseDateTime(date)))
        } catch {
          case e:Exception => None
        }
      }.sortBy( _._2.getMillis )

      //build interval
      configs match {
        case Nil    => Seq()
        case x::Nil => Seq(NodeConfigIdInfo(x._1, x._2, None))
        case t      => t.sliding(2).map {
            //we know the size of the list is 2
            case _::Nil | Nil => throw new IllegalArgumentException("An impossible state was reached, please contact the dev about it!")
            case x::y::t      => NodeConfigIdInfo(x._1, x._2, Some(y._2))
          }.toVector :+ {
            val x = t.last
            NodeConfigIdInfo(x._1, x._2, None)
          }
      }
    }
 }
}
