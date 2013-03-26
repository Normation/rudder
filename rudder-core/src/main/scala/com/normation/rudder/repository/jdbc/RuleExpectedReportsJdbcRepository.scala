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

import com.normation.rudder.domain.policies.DirectiveId
import com.normation.inventory.domain.NodeId
import net.liftweb.common._
import com.normation.rudder.domain.policies._
import com.normation.rudder.repository.RuleExpectedReportsRepository
import com.normation.rudder.domain.reports.RuleExpectedReports
import com.normation.rudder.domain.reports._
import scala.collection._
import org.joda.time._
import org.slf4j.{Logger,LoggerFactory}
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core._
import java.sql.ResultSet
import java.sql.Timestamp
import scala.collection.JavaConversions._
import net.liftweb.json._
import com.normation.utils.HashcodeCaching
import net.liftweb.util.Helpers.tryo
import com.normation.utils.Control._

class RuleExpectedReportsJdbcRepository(jdbcTemplate : JdbcTemplate)
    extends RuleExpectedReportsRepository {

  val logger = LoggerFactory.getLogger(classOf[RuleExpectedReportsJdbcRepository])

  val TABLE_NAME = "expectedreports"
  val NODE_TABLE_NAME = "expectedreportsnodes"

  val baseQuery = "select pkid, nodejoinkey, ruleid, serial, directiveid, component, cardinality, componentsvalues, unexpandedComponentsValues, begindate, enddate  from " + TABLE_NAME + "  where 1=1 ";

  def findAllCurrentExpectedReports(): scala.collection.Set[RuleId] = {
    jdbcTemplate.query("select distinct ruleid from "+ TABLE_NAME +
        "      where enddate is null",
          RuleIdMapper).toSet;
  }


  def findAllCurrentExpectedReportsAndSerial(): scala.collection.Map[RuleId, Int] = {
    jdbcTemplate.query("select distinct ruleid, serial from "+ TABLE_NAME +
        "      where enddate is null",
          RuleIdAndSerialMapper).toMap;
  }

  /**
   * Return current expectedreports (the one still pending) for this Rule
   * @param rule
   * @return
   */
  def findCurrentExpectedReports(ruleId : RuleId) : Box[Option[RuleExpectedReports]] = {
    tryo {
      toRuleExpectedReports(jdbcTemplate.query(baseQuery +
          "      and enddate is null and ruleid = ?",
            Array[AnyRef](ruleId.value),
            RuleExpectedReportsMapper).toSeq) match {
        case Empty => Empty
        case e:Failure => logger.error("Error when expected reports for Rule %s : %s".format(ruleId.value, e.messageChain)); e
        case Full(seq) =>
          seq.size match {
            case 0 => None
            case 1 => Some(seq.head)
            case _ => Failure("Inconsistency in the database, too many entries infor rule id %s".format(ruleId))
          }
      }
    }
  }

  /**
   * Simply set the endDate for the expected report for this conf rule
   * @param ruleId
   */
  def closeExpectedReport(ruleId : RuleId) : Box[Unit] = {
    logger.info("Closing report {}", ruleId)
    findCurrentExpectedReports(ruleId) match {
      case e:EmptyBox => e
      case Full(None) =>
            logger.warn("Cannot close a non existing entry %s".format(ruleId.value))
            Full(Unit)
      case Full(Some(entry)) =>
        jdbcTemplate.update("update "+ TABLE_NAME +"  set enddate = ? where serial = ? and ruleId = ?",
          new Timestamp(DateTime.now().getMillis), new java.lang.Integer(entry.serial), entry.ruleId.value
        )
        Full(Unit) // unit is expected
    }
  }

  /**
   * TODO: change this API !
   * Save an expected reports.
   *
   */
  def saveExpectedReports(
      ruleId                : RuleId
    , serial                : Int
    , policyExpectedReports : Seq[DirectiveExpectedReports]
    , nodes                 : Seq[NodeId]
  ) : Box[RuleExpectedReports] = {
     logger.info("Saving expected report for rule {}", ruleId.value)
// TODO : store also the unexpanded
     findCurrentExpectedReports(ruleId) match {
       case e: EmptyBox => e
       case Full(Some(x)) =>
         // I need to check I'm not having duplicates
         // easiest way : unfold all, and check intersect
         val toInsert = policyExpectedReports.flatMap { case DirectiveExpectedReports(dir, comp) => 
             comp.map(x => (dir, x.componentName))
           }.flatMap { case (dir, compName) =>
             nodes.map(node => Comparator(node, dir, compName))}

         val comparator = x.directivesOnNodes.flatMap { case DirectivesOnNodes(_, nodes, dirExp) =>
           dirExp.flatMap { case DirectiveExpectedReports(dir, comp) =>
             comp.map(x => (dir, x.componentName))
           }.flatMap { case (dir, compName) =>
             nodes.map(node => Comparator(node, dir, compName))}
         }

         toInsert.intersect(comparator) match {
           case seq if seq.size > 0 =>
             logger.error("Inconsistency in the database : cannot save an already existing expected report for %s".format(ruleId.value))
             logger.debug("Intersecting values are " + seq)
             Failure("cannot save an already existing expected report")
           case _ =>
             // Ok
             createExpectedReports(ruleId, serial, policyExpectedReports, nodes)
         }

      case Full(None) =>
          createExpectedReports(ruleId, serial, policyExpectedReports, nodes)
     }
  }

  private[this] def createExpectedReports(
      ruleId                : RuleId
    , serial                : Int
    , policyExpectedReports : Seq[DirectiveExpectedReports]
    , nodes                 : Seq[NodeId]
  ) : Box[RuleExpectedReports] = {
    // Compute first the version id
    val nodeJoinKey = getNextVersionId

    // Create the lines for the mapping
    val list = for {
            policy <- policyExpectedReports
            component <- policy.components

    } yield {
            new ExpectedConfRuleMapping(0, nodeJoinKey, ruleId, serial,
                  policy.directiveId, component.componentName, component.cardinality, component.componentsValues, component.unexpandedComponentsValues, DateTime.now(), None)
    }
    "select pkid, nodejoinkey, ruleid, serial, directiveid, component, componentsvalues, cardinality, begindate, enddate  from "+ TABLE_NAME + "  where 1=1 ";

    list.foreach(entry =>
                jdbcTemplate.update("insert into "+ TABLE_NAME +" ( nodejoinkey, ruleid, serial, directiveid, component, cardinality, componentsValues, unexpandedComponentsValues, begindate) " +
                    " values (?,?,?,?,?,?,?,?,?)",
                  new java.lang.Integer(entry.nodeJoinKey), ruleId.value, new java.lang.Integer(entry.serial), entry.policyExpectedReport.value,
                  entry.component,  new java.lang.Integer(entry.cardinality), ComponentsValuesSerialiser.serializeComponents(entry.componentsValues), ComponentsValuesSerialiser.serializeComponents(entry.unexpandedComponentsValues), new Timestamp(entry.beginDate.getMillis)
                )
    )
    saveNode(nodes, nodeJoinKey )
    findCurrentExpectedReports(ruleId) match {
       case Full(Some(x)) => Full(x)
       case Full(None) => Failure("Could not fetch the freshly saved expected report for rule %s".format(ruleId.value))
       case e:EmptyBox => e
    }
  }



  /**
   * Return all the expected reports for this policyinstance between the two date
   * @param directiveId
   * @return
   */
  def findExpectedReports(ruleId : RuleId, beginDate : Option[DateTime], endDate : Option[DateTime]) : Box[Seq[RuleExpectedReports]] = {
    var query = baseQuery + " and ruleId = ? "
    var array = mutable.Buffer[AnyRef](ruleId.value)

    beginDate match {
      case None =>
      case Some(date) => query = query + " and coalesce(endDate, ?) >= ?"; array += new Timestamp(date.getMillis);array += new Timestamp(date.getMillis)
    }

    endDate match {
      case None =>
      case Some(date) => query = query + " and beginDate < ?"; array += new Timestamp(date.getMillis)
    }

    toRuleExpectedReports(jdbcTemplate.query(query,
          array.toArray[AnyRef],
          RuleExpectedReportsMapper).toSeq)


  }

  /**
   * Return all the expected reports between the two dates
   * @return
   */
  def findExpectedReports(beginDate : DateTime, endDate : DateTime) : Box[Seq[RuleExpectedReports]] = {
    var query = baseQuery + " and beginDate < ? and coalesce(endDate, ?) >= ? "
    var array = mutable.Buffer[AnyRef](new Timestamp(endDate.getMillis), new Timestamp(beginDate.getMillis), new Timestamp(beginDate.getMillis))

    toRuleExpectedReports(jdbcTemplate.query(query,
          array.toArray[AnyRef],
          RuleExpectedReportsMapper).toSeq)
  }

  /**
   * Return all the expected reports for this server between the two date
   * @param directiveId
   * @return
   */
  def findExpectedReportsByNode(nodeId : NodeId, beginDate : Option[DateTime], endDate : Option[DateTime]) : Box[Seq[RuleExpectedReports]] = {
    var joinQuery = "select pkid, "+ TABLE_NAME +".nodejoinkey, ruleid, directiveid, serial, component, componentsvalues, unexpandedComponentsValues, cardinality, begindate, enddate  from "+ TABLE_NAME +
        " join "+ NODE_TABLE_NAME +" on "+ NODE_TABLE_NAME +".nodejoinkey = "+ TABLE_NAME +".nodejoinkey " +
        " where nodeid = ? "

    var array = mutable.Buffer[AnyRef](nodeId.value)

    beginDate match {
      case None =>
      case Some(date) => joinQuery = joinQuery + " and coalesce(endDate, ?) >= ?"; array += new Timestamp(date.getMillis);array += new Timestamp(date.getMillis)
    }

    endDate match {
      case None =>
      case Some(date) => joinQuery = joinQuery + " and beginDate < ?"; array += new Timestamp(date.getMillis)
    }

    toRuleExpectedReports(jdbcTemplate.query(joinQuery,
          array.toArray[AnyRef],
          RuleExpectedReportsMapper).toSeq)
  }



  /**
   * Return currents expectedreports (the one still pending) for this server
   * @param directiveId
   * @return
   */
  def findCurrentExpectedReportsByNode(nodeId : NodeId) : Box[Seq[RuleExpectedReports]] = {
    val joinQuery = "select pkid, "+ TABLE_NAME +".nodejoinkey, ruleid,directiveid, serial, component, componentsvalues, unexpandedComponentsValues, cardinality, begindate, enddate  from "+ TABLE_NAME +
        " join "+ NODE_TABLE_NAME +" on "+ NODE_TABLE_NAME +".nodejoinkey = "+ TABLE_NAME +".nodejoinkey " +
        "      where enddate is null and  "+ NODE_TABLE_NAME +".nodeId = ?";

    toRuleExpectedReports(jdbcTemplate.query(joinQuery,
          Array[AnyRef](nodeId.value),
          RuleExpectedReportsMapper).toSeq)

  }
  
  private[this] def getNodes(nodeJoinKey : Int) : Box[Seq[NodeId]] = {
    tryo {
      jdbcTemplate.queryForList("select nodeId from "+ NODE_TABLE_NAME +" where nodeJoinKey = ?",
        Array[AnyRef](new java.lang.Integer(nodeJoinKey)),
        classOf[ String ]).map(NodeId(_))
    }
  }

  /**
   * Save the server list in the database
   */
  private def saveNode(servers : Seq[NodeId], nodeJoinKey : Int) = {
    for (server <- servers) {
      jdbcTemplate.update("insert into "+ NODE_TABLE_NAME +" ( nodejoinkey, nodeid) values (?,?)",
        new java.lang.Integer(nodeJoinKey), server.value
      )
    }
  }

  private def getNextVersionId() : Int = {
    jdbcTemplate.queryForInt("SELECT nextval('ruleVersionId')")
  }


  /**
   * Effectively convert lines from the DB to RuleExpectedReports (and does also fill the nodes, opposite to what
   * was previously done)
   */
  def toRuleExpectedReports(entries : Seq[ExpectedConfRuleMapping]) : Box[Seq[RuleExpectedReports]] = {
    // first, we fetch all the nodes, so that it's done once and for all
    val nodes = entries.map(_.nodeJoinKey).distinct.map { nodeJoinKey => (nodeJoinKey -> getNodes(nodeJoinKey)) }.toMap

    nodes.values.filter (x => !x.isDefined).headOption match {
      case Some(e:Failure) => Failure("Some nodes could not be fetched for expected reports, cause " + e.messageChain)
      case Some(_) => Failure("Some nodes could not be fetched for expected reports")
      case _ => // we don't have illegal values, we will be able to open the box later
        // group entries by Rule/serial
        Full(entries.groupBy( entry=> SerialedRuleId(entry.ruleId, entry.serial)).map { case (key, seq) =>
          // now we need to group elements of the seq together,  based on nodeJoinKey
          val directivesOnNode = seq.groupBy(x => x.nodeJoinKey).map { case (nodeJoinKey, mappedEntries) =>
            // need to convert to group everything by directiveId, the convert to DirectiveExpectedReports
            val directiveExpectedReports = mappedEntries.groupBy(x=>x.policyExpectedReport).map { case (directiveId, lines) =>
              // here I am on the directiveId level, all lines that have the same RuleId, Serial, NodeJoinKey, DirectiveId are 
              // for the same directive, and must be put together
              DirectiveExpectedReports(directiveId, lines.map( x => ReportComponent(x.component, x.cardinality, x.componentsValues, x.unexpandedComponentsValues)))
            }
            // I can open the box, for it is checked earlier that it is safe
            DirectivesOnNodes(nodeJoinKey, nodes(nodeJoinKey).openTheBox, directiveExpectedReports.toSeq)
          }
          RuleExpectedReports(
              key.ruleId
            , key.serial
            , directivesOnNode.toSeq
            , seq.head.beginDate
            , seq.head.endDate
          )
        }.toSeq)
    }
  }

}

object RuleExpectedReportsMapper extends RowMapper[ExpectedConfRuleMapping] {
  def mapRow(rs : ResultSet, rowNum: Int) : ExpectedConfRuleMapping = {
    // unexpandedcomponentsvalues may be null, as it was not defined before 2.6
    val unexpandedcomponentsvalues = rs.getString("unexpandedcomponentsvalues") match {
      case null => ""
      case value => value
    }
    new ExpectedConfRuleMapping(
      rs.getInt("pkid"),
      rs.getInt("nodejoinkey"),
      new RuleId(rs.getString("ruleid")),
      rs.getInt("serial"),
      DirectiveId(rs.getString("directiveid")),
      rs.getString("component"),
      rs.getInt("cardinality"),
      ComponentsValuesSerialiser.unserializeComponents(rs.getString("componentsvalues")),
      ComponentsValuesSerialiser.unserializeComponents(unexpandedcomponentsvalues),
      new DateTime(rs.getTimestamp("begindate")),
      if(rs.getTimestamp("enddate")!=null) {
        Some(new DateTime(rs.getTimestamp("endDate")))
      } else None
    )
  }
}

object RuleIdMapper extends RowMapper[RuleId] {
  def mapRow(rs : ResultSet, rowNum: Int) : RuleId = {
    new RuleId(rs.getString("ruleid"))
  }
}
object RuleIdAndSerialMapper extends RowMapper[(RuleId, Int)] {
  def mapRow(rs : ResultSet, rowNum: Int) : (RuleId, Int) = {
    (new RuleId(rs.getString("ruleid")) , rs.getInt("serial"))
  }
}

/**
 * Just a plain mapping of the database
 */
case class ExpectedConfRuleMapping(
    val pkId : Int,
    val nodeJoinKey : Int,
    val ruleId : RuleId,
    val serial : Int,
    val policyExpectedReport : DirectiveId,
    val component : String,
    val cardinality : Int,
    val componentsValues : Seq[String],
    val unexpandedComponentsValues : Seq[String],
    // the period where the configuration is applied to the servers
    val beginDate : DateTime = DateTime.now(),
    val endDate : Option[DateTime] = None
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
    implicit val formats = DefaultFormats
    parse(ids).extract[List[String]]
 }

}
