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

import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.collection.JavaConverters.asScalaBufferConverter
import java.sql.Timestamp
import java.sql.ResultSet
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.reports._
import com.normation.rudder.repository.UpdateExpectedReportsRepository
import com.normation.rudder.repository.FindExpectedReportRepository
import com.normation.utils.HashcodeCaching
import com.normation.utils.Control.sequence
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionCallback
import net.liftweb.common._
import net.liftweb.json._
import net.liftweb.util.Helpers.tryo
import org.joda.time.DateTime
import org.springframework.jdbc.core.PreparedStatementCreator
import java.sql.PreparedStatement
import java.sql.Connection
import org.joda.time.format.ISODateTimeFormat
import com.normation.rudder.repository.NodeConfigIdInfo
import com.normation.rudder.repository.RoNodeConfigIdInfoRepository
import com.normation.rudder.repository.WoNodeConfigIdInfoRepository
import com.normation.rudder.repository.NodeConfigIdInfo
import com.normation.rudder.repository.NodeConfigIdInfo
import com.normation.rudder.domain.logger.TimingDebugLogger



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
    jdbcTemplate: JdbcTemplate
  , pgInClause  : PostgresqlInClause
) extends FindExpectedReportRepository with Loggable with RoNodeConfigIdInfoRepository {

  import pgInClause._

  /*
   * Retrieve the list of node config ids
   */
  override def getNodeConfigIdInfos(nodeIds: Set[NodeId]): Box[Map[NodeId, Option[Seq[NodeConfigIdInfo]]]] = {
    if(nodeIds.isEmpty) Full(Map())
    else {
      val query = s"""select node_id, config_ids from nodes_info
          where ${in("node_id", nodeIds.map(_.value))}"""

      for {
        entries <- tryo ( jdbcTemplate.query(query, NodeConfigIdMapper).asScala )
      } yield {
        val res = entries.map{ case(nodeId, config) => (nodeId, NodeConfigIdSerializer.unserialize(config)) }.toMap
        nodeIds.map(n => (n, res.get(n))).toMap
      }
    }
  }

  /*
   * Retrieve all node config ids
   */
  override def getAllNodeConfigIdInfos(): Box[Map[NodeId, Seq[NodeConfigIdInfo]]] = {
    val query = s"""select node_id, config_ids from nodes_info"""

    for {
      entries <- tryo ( jdbcTemplate.query(query, NodeConfigIdMapper).asScala )
    } yield {
      entries.map{ case(nodeId, config) => (nodeId, NodeConfigIdSerializer.unserialize(config)) }.toMap
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

      val query_subscript_configids = s"""select
            E.pkid, NNN.nodeid, NNN.nodeconfigids
        from expectedreports E
        inner join (
          select NN.nodejoinkey, NN.nodeid, NN.nodeconfigids
          from (
            select N.nodejoinkey, N.nodeid, N.nodeconfigids, generate_subscripts(N.nodeconfigids,1) as v
            from expectedreportsnodes N
          ) as NN
          where ${in("NN.nodeconfigids[v]", nodeConfigIds.map(_.version.value))}
        ) as NNN
        on E.nodejoinkey = NNN.nodejoinkey
      """ + rulePredicate

      def getReportsByIds(reportIds: Set[Int]): Box[Seq[ReportMapping]] = {
        if(reportIds.isEmpty) {
          Full(Seq())
        } else {

          val query_subscript_reports = s"""select
                E.pkid, E.nodejoinkey, E.ruleid, E.directiveid, E.serial, E.component, E.componentsvalues
              , E.unexpandedComponentsValues, E.cardinality, E.begindate, E.enddate
            from expectedreports E
            where ${inNumber("E.pkid", reportIds) }
          """
          tryo(  jdbcTemplate.query(query_subscript_reports, ReportMapper).asScala )
        }
      }

      val t0 = System.currentTimeMillis()

      val res = for {
        reportsAndConfigIds <- tryo ( jdbcTemplate.query(query_subscript_configids, ReportAndConfigIdMapper).asScala )
        t1                  =  System.currentTimeMillis
        _                   =  TimingDebugLogger.debug(s"GetExpectedReports: configIds: ${t1-t0}ms")
        reportIds           =  Set[Int]() ++ reportsAndConfigIds.map( _._1)
        t2                  =  System.currentTimeMillis
        expectecReports     <- getReportsByIds(reportIds)
        t3                  =  System.currentTimeMillis
        _                   =  TimingDebugLogger.debug(s"GetExpectedReports: expectedreports: ${t3-t2}ms")
      } yield {

        val reports = expectecReports.map(x => (x.pkId, x)).toMap

        val mapped = reportsAndConfigIds.map { case (pkId, nodeId, versions) =>
          val r = reports(pkId)
          ReportAndNodeMapping(
             r.pkId
           , r.nodeJoinKey
           , r.ruleId
           , r.serial
           , r.directiveId
           , r.component
           , r.cardinality
           , r.componentsValues
           , r.unexpandedCptsValues
           , r.beginDate
           , r.endDate
           , nodeId
           , versions
          )}

        val t4 =  System.currentTimeMillis
        TimingDebugLogger.debug(s"GetExpectedReports: reportsAndNodeMapping: ${t4-t3}ms")

        val res= toNodeExpectedReports(nodeConfigIds, mapped)

        val t5 =  System.currentTimeMillis
        TimingDebugLogger.debug(s"GetExpectedReports: toNodeExpectedReports: ${t5-t4}ms")

        res
      }
      res
    }
  }

  object ReportAndConfigIdMapper extends RowMapper[(Int, NodeId, List[NodeConfigId])] {
    def mapRow(rs : ResultSet, rowNum: Int) : (Int, NodeId, List[NodeConfigId]) = {
      def notNullS(name: String): String = {
        rs.getString(name) match {
          case null => throw new IllegalArgumentException(s"Column '${name}' has a null value (illegal value)")
          case x => x
        }
      }

      (
          rs.getInt("pkid")
        , NodeId(notNullS("nodeid"))
        , NodeConfigVersionsSerializer.unserialize(rs.getArray("nodeconfigids"))
      )
    }
  }

  /**
   * Return current expected reports (the one still pending) for this Rule
   *
   * Only used in UpdateExpectedReportsJdbcRepository
   */
  private[jdbc] def findCurrentExpectedReports(ruleId : RuleId) : Box[Option[RuleExpectedReports]] = {
    getRuleExpectedReports("where enddate is null and ruleid = ?", Array[AnyRef](ruleId.value))  match {
        case Empty => Empty
        case f:Failure =>
          logger.error(s"Error when getting expected report: ${f.messageChain}")
          f
        case Full(seq) =>
          seq.size match {
            case 0 => Full(None)
            case 1 => Full(Some(seq.head))
            case _ => Failure(s"Inconsistency in the database, too many expected reports for a rule")
          }
      }
  }

  override def findCurrentNodeIds(ruleId : RuleId) : Box[Set[NodeId]] = {
     val expectedReportsQuery ="""select distinct N.nodeid from expectedreports E inner join expectedreportsnodes N
      | on E.nodejoinkey = N.nodejoinkey where E.enddate is null and E.ruleid = ?""".stripMargin

    for {
       entries <- tryo { jdbcTemplate.query(expectedReportsQuery, Array[AnyRef](ruleId.value), NodeMapper).asScala }
    } yield {
      entries.toSet
    }
  }


  /**
   * Return all the expected reports between the two dates
   */
  override def findExpectedReports(beginDate : DateTime, endDate : DateTime) : Box[Seq[RuleExpectedReports]] = {
    val params = Array[AnyRef](new Timestamp(endDate.getMillis), new Timestamp(beginDate.getMillis), new Timestamp(beginDate.getMillis))

    getRuleExpectedReports("where beginDate < ? and coalesce(endDate, ?) >= ? ", params)
  }

  private[this] def getRuleExpectedReports[T](whereClause: String, params: Array[AnyRef], mapEntries: Seq[ReportAndNodeMapping] => T = toExpectedReports _) : Box[T] = {
    val expectedReportsQuery ="""select
          E.pkid, E.nodejoinkey, E.ruleid, E.directiveid, E.serial, E.component, E.componentsvalues
        , E.unexpandedComponentsValues, E.cardinality, E.begindate, E.enddate
        , N.nodeid, N.nodeconfigids
      from expectedreports E
      inner join expectedreportsnodes N
      on E.nodejoinkey = N.nodejoinkey """ + whereClause

    for {
      entries <- tryo { if(params.isEmpty) {
                   jdbcTemplate.query(expectedReportsQuery, ReportAndNodeMapper).asScala
                 } else {
                   jdbcTemplate.query(expectedReportsQuery, params, ReportAndNodeMapper).asScala
                 } }
    } yield {
      mapEntries(entries)
    }
  }


  /**
   * Build RuleNodeExpectedReports from a list of rows from DB.
   */
  private[this] def toNodeExpectedReports(nodeConfigIds: Set[NodeAndConfigId], entries:Seq[ReportAndNodeMapping]) : Map[NodeId, Map[NodeConfigId, Map[SerialedRuleId, RuleNodeExpectedReports]]] = {
    /*
     * It's a simple grouping by nodeId, then by nodeConfigId, then by
     * ruleId/seria
     */

    //it's several order of magnitude quicker to build groupBy map
    //than to filter in the loop
    val t0 = System.currentTimeMillis
    val nodeAndConfigIds = nodeConfigIds.groupBy { _.nodeId }.mapValues { _.toSeq.map(_.version) }
    val reportsForNode = entries.groupBy { _.nodeId }

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

  private[this] def toExpectedReports(entries:Seq[ReportAndNodeMapping]) : Seq[RuleExpectedReports] = {
    entries.groupBy( entry => SerialedRuleId(entry.ruleId, entry.serial)).map { case (key, seq) =>
      // now we need to group elements of the seq together,  based on nodeJoinKey
      val directivesOnNode = seq.groupBy(x => x.nodeJoinKey).map { case (nodeJoinKey, mappedEntries) =>
        // need to convert to group everything by directiveId, the convert to DirectiveExpectedReports
        val directiveExpectedReports = mappedEntries.groupBy(x => x.directiveId).map { case (directiveId, lines) =>
          // here I am on the directiveId level, all lines that have the same RuleId, Serial, NodeJoinKey, DirectiveId are
          // for the same directive, and must be put together
          DirectiveExpectedReports(directiveId, lines.map( x =>
            ComponentExpectedReport(x.component, x.cardinality, x.componentsValues, x.unexpandedCptsValues)
          ).distinct /* because we have the cardinality for that */ )
        }
        val nodeConfigurationIds = mappedEntries.groupBy( _.nodeId).mapValues { lines =>
          //we should have only one line at that level, but else, merger versions
          lines.reduce { (current, next) =>
            if(current.nodeJoinKey >= next.nodeJoinKey) {
              current.copy(nodeConfigVersions = current.nodeConfigVersions ::: next.nodeConfigVersions)
            } else {
              current.copy(nodeConfigVersions = next.nodeConfigVersions ::: current.nodeConfigVersions)
            }
          }.nodeConfigVersions.headOption
        }

        DirectivesOnNodes(nodeJoinKey, nodeConfigurationIds, directiveExpectedReports.toSeq)
      }
      RuleExpectedReports(
          key.ruleId
        , key.serial
        , directivesOnNode.toSeq
        , seq.head.beginDate
        , seq.head.endDate
      )
    }.toSeq
  }

}


class UpdateExpectedReportsJdbcRepository(
    jdbcTemplate      : JdbcTemplate
  , transactionManager: PlatformTransactionManager
  , findReports       : FindExpectedReportsJdbcRepository
  , findNodeConfig    : RoNodeConfigIdInfoRepository
) extends UpdateExpectedReportsRepository with Loggable with WoNodeConfigIdInfoRepository {

  /**
   * We need to create transaction for the insertion of expected reports
   * otherwise race conditions may occur
   * We are clearly pushing the complexity of jdbcTemplate, and will need to move to
   * higher lever abstraction for this
   */
  val transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager)

  /**
   * Delete all expected reports closed before a date
   */
  override def deleteExpectedReports(date: DateTime) : Box[Int] = {
    // Find all nodejoinkey that have expected reports closed more before date
    transactionTemplate.execute(new TransactionCallback[Box[Int]]() {
        def doInTransaction(status: TransactionStatus): Box[Int] = {
          tryo {
            val deletedExpectedReports = jdbcTemplate.update("DELETE FROM expectedreports where coalesce(endDate, ?) < ?"
                                , new Timestamp(date.getMillis), new Timestamp(date.getMillis)
                              )

            if (deletedExpectedReports == 0) {
              0
            } else {
              logger.debug(s"Deleted ${deletedExpectedReports} expected reports closed before ${date}")
              val deletedExpectedReportsNode = jdbcTemplate.update("delete from expectedreportsnodes where nodejoinkey not in (select nodejoinkey from expectedreports)")
              logger.debug(s"Deleted ${deletedExpectedReportsNode} expected reports node")
              deletedExpectedReports
            }
          }
        }
    })
  }

  private[jdbc] def getNodes(nodeJoinKeys : Set[Int]) : Box[Map[Int, Map[NodeId, List[NodeConfigId]]]] = {
    if(nodeJoinKeys.isEmpty) Full(Map())
    else tryo {
      val x = jdbcTemplate.query(
          s"select nodejoinkey, nodeid, nodeconfigids from expectedreportsnodes where nodejoinkey in ${nodeJoinKeys.mkString("(", ",", ")")}"
        , NodeJoinKeyConfigMapper
      ).asScala.groupBy(_._2.nodeId).mapValues { seq => //seq cannot be empty due to groupBy
        //merge version together based on nodejoin values
        (seq.reduce[(Int, NodeConfigVersions)] { case ( (maxK, versions), (newK, newConfigVersions) ) =>
          if(maxK >= newK) {
            (maxK, versions.copy(versions = versions.versions ::: newConfigVersions.versions))
          } else {
            (newK, versions.copy(versions = newConfigVersions.versions ::: versions.versions))
          }
        })
      }

      x.values.groupBy(_._1).mapValues(_.map{case(_, NodeConfigVersions(id,v)) => (id,v)}.toMap)
    }
  }

  private[jdbc] def getNodesByNodesId(nodeIds : Set[NodeId]) :Box[Seq[(Int, NodeConfigVersions)]] = {
    if(nodeIds.isEmpty) Full(Seq())
    else tryo {
      val params: Array[AnyRef] = nodeIds.map(_.value).toArray[AnyRef]
      jdbcTemplate.query(
          s"select nodejoinkey, nodeid, nodeconfigids from expectedreportsnodes where nodeid in ${nodeIds.toSeq.map(_ => "?").mkString("(", ",", ")")}"
        , params
        , NodeJoinKeyConfigMapper
      ).asScala

    }
  }

  /**
   * From a map of Node -> RemovedNodeConfigId, remove all those config id from the expected reports
   */
  private[jdbc] def purgeNodeConfigId(nodeConfigIdToRemove : Map[NodeId, Seq[NodeConfigId]]) = {
    // extract all the config to remove
    val allConfigToRemove = nodeConfigIdToRemove.values.flatten.toSeq
    for {
      // fetch all the nodejoinkey -> configIdversion in the expected reports table
      currentNodeConfigId <- getNodesByNodesId(nodeConfigIdToRemove.keySet)

      // list all those that contains version that must be removed
      nodesToClean = currentNodeConfigId.filter{ case (nodeJoinKey, nodeConfigVersion) =>
                                                    nodeConfigVersion.versions.exists(x => allConfigToRemove.contains(x))
                                                }
      // filter out all the version to remove
      cleanedReports = nodesToClean.map { case (nodeJoinKey, nodeConfigVersion) =>
        (nodeJoinKey, nodeConfigVersion.copy(versions = nodeConfigVersion.versions.filterNot(x =>  allConfigToRemove.contains(x))) )
      }

      // we update these config
      cleanedExpectedReports <- updateNodeConfigVersion(cleanedReports)
    } yield {
      cleanedExpectedReports
    }

  }
  override def findAllCurrentExpectedReportsWithNodesAndSerial(): Map[RuleId, (Int, Int, Map[NodeId, NodeConfigVersions])] = {
    val composite = jdbcTemplate.query("select distinct ruleid, serial, nodejoinkey from expectedreports where enddate is null", RuleIdSerialNodeJoinKeyMapper)

    (for {
      (ruleId, serial, nodeJoin) <- composite.asScala
      nodeList                   <- getNodes(Set(nodeJoin))
    } yield {
      (ruleId, (serial, nodeJoin, nodeList(nodeJoin).map{case(nodeId, versions) => (nodeId, NodeConfigVersions(nodeId, versions))}.toMap ))
    }).toMap

  }


  /**
   * Simply set the endDate for the expected report for this conf rule
   * @param ruleId
   */
  override def closeExpectedReport(ruleId : RuleId, generationTime: DateTime) : Box[Unit] = {
    logger.debug(s"Closing expected report for rules '${ruleId.value}'")
    findReports.findCurrentExpectedReports(ruleId) match {
      case e:EmptyBox => e
      case Full(None) =>
            logger.warn(s"Cannot close a non existing entry '${ruleId.value}'")
            Full({})
      case Full(Some(entry)) =>
        jdbcTemplate.update("update expectedreports  set enddate = ? where serial = ? and ruleId = ?",
          new Timestamp(DateTime.now().getMillis), new java.lang.Integer(entry.serial), entry.ruleId.value
        )
        Full({}) // unit is expected
    }
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
     findReports.findCurrentExpectedReports(ruleId) match {
       case e: EmptyBox => e
       case Full(Some(x)) =>
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
             Failure(msg)

           case _ => // Ok
             createExpectedReports(ruleId, serial, generationTime, directiveExpectedReports, nodeConfigIds)
         }

      case Full(None) =>
          createExpectedReports(ruleId, serial, generationTime, directiveExpectedReports, nodeConfigIds)
     }
  }

  private[this] def createExpectedReports(
      ruleId                  : RuleId
    , serial                  : Int
    , generationTime          : DateTime
    , directiveExpectedReports: Seq[DirectiveExpectedReports]
    , nodeConfigIds           : Seq[NodeAndConfigId]
  ) : Box[RuleExpectedReports] = {

    val generationTimestamp = new Timestamp(generationTime.getMillis)

    transactionTemplate.execute(new TransactionCallback[Box[RuleExpectedReports]]() {
      def doInTransaction(status: TransactionStatus): Box[RuleExpectedReports] = {
        // Compute first the version id
        val nodeJoinKey = jdbcTemplate.queryForInt("SELECT nextval('ruleVersionId')")


        // Create the lines for the mapping
        val list = for {
          policy    <- directiveExpectedReports
          component <- policy.components
        } yield {
          jdbcTemplate.update("""insert into expectedreports (
                nodejoinkey, ruleid, serial, directiveid, component, cardinality, componentsValues, unexpandedComponentsValues, begindate
              ) values (?,?,?,?,?,?,?,?,?)"""
            , new java.lang.Integer(nodeJoinKey), ruleId.value, new java.lang.Integer(serial), policy.directiveId.value
            , component.componentName,  new java.lang.Integer(component.cardinality), ComponentsValuesSerialiser.serializeComponents(component.componentsValues)
            , ComponentsValuesSerialiser.serializeComponents(component.unexpandedComponentsValues), generationTimestamp
          )
        }

        // save new nodeconfiguration - no need to check for existing version for them
        for (config <- nodeConfigIds) {
          jdbcTemplate.update(new PreparedStatementCreator() {
            override def createPreparedStatement(con: Connection): PreparedStatement = {
              val ps = con.prepareStatement("insert into expectedreportsnodes ( nodejoinkey, nodeid, nodeconfigids) values (?,?,?)")
              ps.setInt(1, nodeJoinKey)
              ps.setString(2, config.nodeId.value)
              ps.setArray(3, con.createArrayOf("text", NodeConfigVersionsSerializer.serialize(List(config.version))))
              ps
            }
          })
        }

        findReports.findCurrentExpectedReports(ruleId) match {
          case Full(Some(x)) => Full(x)
          case Full(None)    => Failure("Could not fetch the freshly saved expected report for rule %s".format(ruleId.value))
          case eb: EmptyBox  => eb
        }
      }
    })

  }

  /**
   * Update the set of nodes to have the given node ConfigVersion.
   * As we don't have other information, we will update "last"
   * (i.e row with the biggest nodeJoin key).
   */
  override def updateNodeConfigVersion(toUpdate: Seq[(Int, NodeConfigVersions)]): Box[Seq[(Int,NodeConfigVersions)]] = {

    object NodeConfigVersionsMapper extends RowMapper[(Int,NodeConfigVersions)] {
      def mapRow(rs : ResultSet, rowNum: Int) : (Int,NodeConfigVersions) = {
        val k = rs.getInt("nodejoinkey")
        val n = NodeConfigVersions(NodeId(rs.getString("nodeid")), NodeConfigVersionsSerializer.unserialize(rs.getArray("nodeconfigids")))
        (k,n)
      }
    }

    if(toUpdate.isEmpty) Full(Seq())
    else {

      for {
        updates <- sequence(toUpdate) { case(nodeJoinKey, config) =>
                     updateNodeConfig(nodeJoinKey, config)
                   }
      } yield {
        toUpdate
      }
    }
  }

  private[this] def updateNodeConfig(nodeJoinKey: Int, config: NodeConfigVersions) = {
    tryo(jdbcTemplate.update(new PreparedStatementCreator() {
       override def createPreparedStatement(con: Connection) = {
         val ps = con.prepareStatement("update expectedreportsnodes set nodeconfigids = ? where nodejoinkey = ? and nodeid = ?")
         ps.setArray(1, con.createArrayOf("text", config.versions.map(_.value).toArray[AnyRef]))
         ps.setInt(2, nodeJoinKey)
         ps.setString(3, config.nodeId.value)
         ps
       }
     }))
  }
  /*
   * Handle node config id update
   */

  //hyper specilialised utilitary method - take care of the order of config_ids THEN node_id
  //in the Array and so in the query
  private[this] def execBatchUpdateQuery(query: String, configInfos: Map[NodeId, Seq[NodeConfigIdInfo]]): Box[Set[NodeId]] = {
    if(configInfos.isEmpty) {
      Full(Set())
    } else {
      val params = configInfos.map { case (node, configs) =>
        Array[AnyRef](NodeConfigIdSerializer.serialize(configs), node.value)
      }.toSeq.asJava

      for {
        _ <- tryo(jdbcTemplate.batchUpdate(query, params))
      } yield {
        configInfos.keySet
      }
    }
  }

  override def createNodeConfigIdInfo(configInfos: Map[NodeId, Seq[NodeConfigIdInfo]]): Box[Set[NodeId]] = {
    execBatchUpdateQuery("insert into nodes_info (config_ids, node_id) values (?, ?)", configInfos)
  }

  override def updateNodeConfigIdInfo(configInfos: Map[NodeId, Seq[NodeConfigIdInfo]]): Box[Set[NodeId]] = {
    execBatchUpdateQuery("update nodes_info set config_ids = ? where node_id = ?", configInfos)
  }

  override def addNodeConfigIdInfo(updatedNodeConfigs: Map[NodeId, NodeConfigId], generationTime: DateTime): Box[Set[NodeId]] = {
    transactionTemplate.execute(new TransactionCallback[Box[Set[NodeId]]]() {
      def doInTransaction(status: TransactionStatus): Box[Set[NodeId]] = {
        for {
          configs  <- findNodeConfig.getNodeConfigIdInfos(updatedNodeConfigs.keySet)
          olds     =  configs.collect { case (id, Some(seq)) =>
                        val next = updatedNodeConfigs(id) //can't fail because updatedNodeConfigs#keySet is a superset of configs#keySet
                        (id, seq :+ NodeConfigIdInfo(next, generationTime, None))
                      }.toMap
          savedOld <- updateNodeConfigIdInfo(olds)
          news     =  (configs.keySet--olds.keySet).map { id => (id, Seq(NodeConfigIdInfo(updatedNodeConfigs(id), generationTime, None)))}.toMap
          savedNew <- createNodeConfigIdInfo(news)
        } yield {
          savedOld ++ savedNew
        }
      }
    })
  }

  /**
   * Delete all NodeConfigId that finished before date (meaning: all NodeConfigId that have one created before date)
   * This must be transactionnal to avoid conflict with other potential updates
   */
  override def deleteNodeConfigIdInfo(date:DateTime) : Box[Int] = {
    transactionTemplate.execute(new TransactionCallback[Box[Int]]() {
      def doInTransaction(status: TransactionStatus): Box[Int] = {
        for {
          allNodeConfigId       <- findNodeConfig.getAllNodeConfigIdInfos()
          mapOfBeforeAfter      = allNodeConfigId.map {
                                    case (nodeId, nodeConfigIds) => (nodeId, nodeConfigIds.partition(config => config.endOfLife.map( x => x.isBefore(date)).getOrElse(false)))
                                }
          update                <- updateNodeConfigIdInfo(mapOfBeforeAfter.map{ case (nodeId, (old, current)) => (nodeId, current)})
          purgedExpectedReports <- purgeNodeConfigId(mapOfBeforeAfter.map{ case (nodeId, (old, current)) => (nodeId, old.map(x => x.configId))})
          } yield {
            update.size
          }
        }
      })
  }
}

case class ReportAndNodeMapping(
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

object ReportAndNodeMapper extends RowMapper[ReportAndNodeMapping] {

  //handler to raise exception on null: we DON'T have better means here,
  //so be sure to handle the exception above


  def mapRow(rs : ResultSet, rowNum: Int) : ReportAndNodeMapping = {
    def notNull[T](f: String => T)(name: String): T = f(name) match {
      case null => throw new IllegalArgumentException(s"Column '${name}' has a null value (illegal value)")
      case x => x
    }

    val notNullS = notNull(rs.getString) _
    val notNullT = notNull(rs.getTimestamp) _

    new ReportAndNodeMapping(
        rs.getInt("pkid")
      , rs.getInt("nodejoinkey")
      , new RuleId(notNullS("ruleid"))
      , rs.getInt("serial")
      , DirectiveId(notNullS("directiveid"))
      , notNullS("component")
      , rs.getInt("cardinality")
      , ComponentsValuesSerialiser.unserializeComponents(rs.getString("componentsvalues"))
      , ComponentsValuesSerialiser.unserializeComponents(rs.getString("unexpandedcomponentsvalues"))
      , new DateTime(notNullT("begindate"))
      , rs.getTimestamp("enddate") match {
          case null => None
          case x    => Some(new DateTime(x))
        }
      , NodeId(notNullS("nodeid"))
      , NodeConfigVersionsSerializer.unserialize(rs.getArray("nodeconfigids"))
    )
  }
}
object ReportMapper extends RowMapper[ReportMapping] {

  //handler to raise exception on null: we DON'T have better means here,
  //so be sure to handle the exception above


  def mapRow(rs : ResultSet, rowNum: Int) : ReportMapping = {
    def notNull[T](f: String => T)(name: String): T = f(name) match {
      case null => throw new IllegalArgumentException(s"Column '${name}' has a null value (illegal value)")
      case x => x
    }

    val notNullS = notNull(rs.getString) _
    val notNullT = notNull(rs.getTimestamp) _

    new ReportMapping(
        rs.getInt("pkid")
      , rs.getInt("nodejoinkey")
      , new RuleId(notNullS("ruleid"))
      , rs.getInt("serial")
      , DirectiveId(notNullS("directiveid"))
      , notNullS("component")
      , rs.getInt("cardinality")
      , ComponentsValuesSerialiser.unserializeComponents(rs.getString("componentsvalues"))
      , ComponentsValuesSerialiser.unserializeComponents(rs.getString("unexpandedcomponentsvalues"))
      , new DateTime(notNullT("begindate"))
      , rs.getTimestamp("enddate") match {
          case null => None
          case x    => Some(new DateTime(x))
        }
    )
  }
}

object RuleIdSerialNodeJoinKeyMapper extends RowMapper[(RuleId, Int, Int)] {
  def mapRow(rs : ResultSet, rowNum: Int) : (RuleId, Int, Int) = {
    (new RuleId(rs.getString("ruleid")) , rs.getInt("serial"), rs.getInt("nodejoinkey"))
  }
}

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

object NodeMapper extends RowMapper[NodeId] {
  def mapRow(rs : ResultSet, rowNum: Int) : NodeId = {
    NodeId(rs.getString("nodeid"))
  }
}


object NodeConfigIdMapper extends RowMapper[(NodeId, String)] {
  def mapRow(rs : ResultSet, rowNum: Int) : (NodeId, String) = {
    (NodeId(rs.getString("node_id")) , rs.getString("config_ids"))
  }
}

object NodeJoinKeyConfigMapper extends RowMapper[(Int, NodeConfigVersions)] {
  def mapRow(rs : ResultSet, rowNum: Int) : (Int, NodeConfigVersions) = {
    val nodeId = new NodeId(rs.getString("nodeid"))
    val versions = NodeConfigVersionsSerializer.unserialize(rs.getArray("nodeconfigids"))
    (rs.getInt("nodejoinkey"), NodeConfigVersions(nodeId, versions))
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
        case Nil => Seq()
        case x::Nil => Seq(NodeConfigIdInfo(x._1, x._2, None))
        case t => t.sliding(2).map {
            //we know the size of the list is 2
            case x::Nil => throw new IllegalArgumentException("An impossible state was reached, please contact the dev about it!")
            case x::y::t => NodeConfigIdInfo(x._1, x._2, Some(y._2))
          }.toVector :+ {
            val x = t.last
            NodeConfigIdInfo(x._1, x._2, None)
          }
      }
    }
 }
}


