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


package com.normation.rudder.migration

import java.sql._
import java.util.Calendar

import scala.Option.option2Iterable
import scala.collection.JavaConverters.asScalaBufferConverter
import scala.xml._

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.squeryl.annotations.Column
import org.squeryl.PrimitiveTypeMode._
import org.squeryl.KeyedEntity
import org.squeryl.Schema

import com.normation.rudder.domain.Constants
import com.normation.rudder.repository.jdbc.SquerylConnectionProvider
import com.normation.rudder.services.marshalling.TestFileFormat
import com.normation.utils.Control._
import com.normation.utils.XmlUtils

import LogMigrationEventLog_10_2.logger
import net.liftweb.common._
import net.liftweb.util.Helpers.strToCssBindPromoter
import net.liftweb.util.Helpers.tryo
import net.liftweb.util.IterableFunc.itNodeSeq
import net.liftweb.util.StringPromotable.intToStrPromo

/**
 * ////////////////////////////////////////
 * The logger to use for tracking that
 * migration
 * ////////////////////////////////////////
 */
object LogMigrationEventLog_10_2 {
  val logger = Logger("migration-2.3-2.4-eventlog-xml-format-1.0-2")
  
  val defaultErrorLogger : Failure => Unit = { f => 
    logger.error(f.messageChain)
    f.rootExceptionCause.foreach { ex => 
      logger.error("Root exception was:", ex)
    }
  }
  val defaultSuccessLogger : Seq[MigrationEventLog] => Unit = { seq =>
    if(logger.isDebugEnabled) {
      seq.foreach { log => 
        logger.debug("Migrating eventlog to format 2, id: " + log.id)
      }
    }
    logger.info("Successfully migrated %s eventlog to format 2".format(seq.size))
  }
}

/**
 * ////////////////////////////////////////
 * DataBase
 * ////////////////////////////////////////
 */
object MigrationEventLogTable extends Schema {

  val migrationEventLog = table[SerializedMigrationEventLog]("migrationeventlog")
    
  on(migrationEventLog)(t => declare(
      t.id.is(autoIncremented("migrationeventlogid"), primaryKey))
  )  
}

case class SerializedMigrationEventLog(
    @Column("detectiontime")       detectionTime      : Timestamp
  , @Column("detectedfileformat")  detectedFileFormat : Long
  , @Column("migrationstarttime")  migrationStartTime : Option[Timestamp]
  , @Column("migrationendtime")    migrationEndTime   : Option[Timestamp]
  , @Column("migrationfileformat") migrationFileFormat: Option[Long]
  , @Column("description")         description        : Option[String]
) extends KeyedEntity[Long] { 
  
  @Column("id") 
  val id = 0L 
  
  //for squery to know about option type
  def this() = this(new Timestamp(System.currentTimeMillis), 0, Some(new Timestamp(System.currentTimeMillis)), Some(new Timestamp(System.currentTimeMillis)), Some(0L), Some(""))
}

class MigrationEventLogRepository(squerylConnectionProvider : SquerylConnectionProvider) {

  /**
   * Retrieve the last version of the EventLog fileFormat as seen by the
   * the SQL script. 
   * If the database does not exist or no line are present, retrun none.
   */
  def getLastDetectionLine: Option[SerializedMigrationEventLog] = {
    squerylConnectionProvider.ourTransaction {
      val q = from(MigrationEventLogTable.migrationEventLog)(line => 
        select(line)
        orderBy(line.id desc)
      )
      
      q.page(0,1).toList.headOption
    }    
  }
  
  /**
   * Update the corresponding detection line with
   * the starting time of the migration (from Rudder)
   */
  def setMigrationStartTime(id: Long, startTime: Timestamp) : Int = {
    squerylConnectionProvider.ourTransaction {
      update(MigrationEventLogTable.migrationEventLog)(l =>
        where(l.id === id)
        set(l.migrationStartTime := Some(startTime))
      )
    }
  }
  
  /**
   * Update the corresponding detection line with the new, 
   * up-to-date file format.
   */
  def setMigrationFileFormat(id: Long, fileFormat:Long, endTime:Timestamp) : Int = {
    squerylConnectionProvider.ourTransaction {
      update(MigrationEventLogTable.migrationEventLog)(l =>
        where(l.id === id)
        set(
            l.migrationEndTime := Some(endTime)
          , l.migrationFileFormat := Some(fileFormat)
        )
      )
    }    
  }
  
  /**
   * create a new status line with a timestamp of now and the given
   * detectedFileFormat.
   */
  def createNewStatusLine(fileFormat:Long, description:Option[String] = None) : SerializedMigrationEventLog = {
    squerylConnectionProvider.ourTransaction {
      MigrationEventLogTable.migrationEventLog.insert(
         SerializedMigrationEventLog(new Timestamp(Calendar.getInstance.getTime.getTime), fileFormat, None, None, None, description) 
      )
    }
  }
}

case class MigrationEventLog(
    id           : Long
  , eventType    : String
  , data         : Elem
) 

object MigrationEventLogMapper extends RowMapper[MigrationEventLog] {
  override def mapRow(rs : ResultSet, rowNum: Int) : MigrationEventLog = {
    MigrationEventLog(
        id          = rs.getLong("id")
      , eventType   = rs.getString("eventType")
      , data        = XML.load(rs.getSQLXML("data").getBinaryStream)
    )
  }
}

sealed trait MigrationStatus
final case object NoMigrationRequested extends MigrationStatus
final case object MigrationVersionNotHandledHere extends MigrationStatus
final case class MigrationSuccess(migrated:Int) extends MigrationStatus

/**
 * This class manage the hight level migration process: read if a
 * migration is required in the MigrationEventLog datatable, launch
 * the migration process, write migration result. 
 * The actual migration of event logs is delegated to EventLogsMigration_10_2
 */
class ControlEventLogsMigration_10_2(
    migrationEventLogRepository: MigrationEventLogRepository
  , eventLogsMigration_10_2    : EventLogsMigration_10_2

) {
  import LogMigrationEventLog_10_2.logger
  
  def migrate() : Box[MigrationStatus] = {
    /*
     * test is we have to migrate, and execute migration
     */
    migrationEventLogRepository.getLastDetectionLine match {
      case None => 
        logger.debug("No migration detected by migration script (table '%s' is empty or does not exists)".
            format(MigrationEventLogTable.migrationEventLog.name)
        )
        Full(NoMigrationRequested)
        
      /*
       * we only have to deal with the migration if:
       * - fileFormat is < 2 AND (
       *   - migrationEndTime is not set OR
       *   - migrationFileFormat < 2
       * )
       */
        
      //new migration
      case Some(status@SerializedMigrationEventLog(
          _
        , detectedFileFormat
        , migrationStartTime
        , migrationEndTime @ None
        , _
        , _
      )) if(detectedFileFormat < 2) =>
        /*
         * here, simply start a migration for the first time (if migrationStartTime is None)
         * or continue a previously started migration (but interrupted ?)
         */
        if(migrationStartTime.isEmpty) {
          migrationEventLogRepository.setMigrationStartTime(status.id, new Timestamp(Calendar.getInstance.getTime.getTime))
        }
        
        logger.info("Start migration of EventLog from format '1.0' to '2'")

        eventLogsMigration_10_2.processEventLogs() match {
          case Full(i) =>
            logger.info("Migration from EventLog fileFormat from '1.0' to '2' done, %s EventLogs migrated".format(i))
            migrationEventLogRepository.setMigrationFileFormat(status.id, 2, new Timestamp(Calendar.getInstance.getTime.getTime))
            Full(MigrationSuccess(i))
            
          case eb:EmptyBox => 
            val e = (eb ?~! "Could not correctly finish the migration from EventLog fileFormat from '1.0' to '2'. Check logs for errors. The process can be trigger by restarting the application")
            logger.error(e)
            e
        }

      //a past migration was done, but the final format is not the one we want
      case Some(x@SerializedMigrationEventLog(
          _
        , _
        , _
        , Some(endTime)
        , Some(migrationFileFormat)
        , _
      )) if(migrationFileFormat < 2) =>
        //create a new status line with detected format = migrationFileFormat,
        //and a description to say why we recurse
        migrationEventLogRepository.createNewStatusLine(migrationFileFormat, Some("Found a post-migration fileFormat='%s': update".format(migrationFileFormat)))
        this.migrate()
        
      //other case: does nothing
      case Some(x) => 
        logger.debug("Migration of EventLog from format '1.0' to '2': nothing to do")
        Full(MigrationVersionNotHandledHere)
    }
  }
  
}

/**
 * The class that handle the processing of the list of all event logs
 * logic.
 * Each individual eventlog is processed in EventLogMigration_10_2
 * 
 */
class EventLogsMigration_10_2(
    jdbcTemplate               : JdbcTemplate
  , eventLogMigration          : EventLogMigration_10_2
  , errorLogger                : Failure => Unit
  , successLogger              : Seq[MigrationEventLog] => Unit
  , batchSize                  : Int = 1000
) {
  import LogMigrationEventLog_10_2.logger
  
 
  /**
   * retrieve all event log to migrate. 
   */
  def findAllEventLogs : Box[Seq[MigrationEventLog]] = {
    
    //check if the event must be migrated
    def needMigration(xml:NodeSeq) : Boolean = (
         (xml \ "addPending" ).size > 0 
      || (try {
           (xml \\ "@fileFormat" exists { _.text.toFloat == 1 })
         } catch {
           case e:NumberFormatException => false
         })
    )
    
    val SELECT_SQL_ALL_EVENTLOGS = 
      """
      |SELECT id, eventType, data FROM eventlog
      |""".stripMargin
      
    tryo(
        jdbcTemplate.query(SELECT_SQL_ALL_EVENTLOGS, MigrationEventLogMapper).asScala
       .filter(log => needMigration(log.data))
    )
  }
  
  private[this] def saveEventLogs(logs:Seq[MigrationEventLog]) : Box[Seq[MigrationEventLog]] = {
    val UPDATE_SQL = "UPDATE EventLog set eventType = ?, data = ? where id = ?"
      
    val ilogs = logs match {
      case x:IndexedSeq[_] => logs
      case seq => seq.toIndexedSeq
    }
      
    tryo { jdbcTemplate.batchUpdate(
               UPDATE_SQL
             , new BatchPreparedStatementSetter() {
                 override def setValues(ps: PreparedStatement, i: Int): Unit = {
                   ps.setString(1, ilogs(i).eventType )
                   val sqlXml = ps.getConnection.createSQLXML()
                   sqlXml.setString(ilogs(i).data.toString)
                   ps.setSQLXML(2, sqlXml)
                   ps.setLong(3, ilogs(i).id )
                 }

                 override def getBatchSize() = ilogs.size
               }
    ) }.map( _ => ilogs )
  }
  
  
    
    
  /**
   * General algorithm: get all event logs to migrate, 
   * then process and save them.
   * Return the number of event log migrated
   */
  def processEventLogs() : Box[Int] = {    
    for {
      logs     <- findAllEventLogs
      migrated <- saveResults(
                    logs = migrate(logs, errorLogger)
                  , saveLogs = saveEventLogs
                  , successLogger = successLogger
                  , batchSize = batchSize
                  )
    } yield {
      migrated
    }
  }
  

  private[this] def migrate(
      logs         : Seq[MigrationEventLog]
    , errorLogger  : Failure => Unit
  ) : Seq[MigrationEventLog] = {
    logs.flatMap { log =>
      eventLogMigration.migrate(log) match {
        case eb:EmptyBox => errorLogger(eb ?~! "Error when trying to migrate event log with id '%s'".format(log.id)); None
        case Full(m)     => Some(m)
      }
    }
  }

  /**
   * Actually save the logs in DB by batch of batchSize. 
   * The final result is a failure if any batch were in failure. 
   */
  private[this] def saveResults(
      logs          : Seq[MigrationEventLog]
    , saveLogs      : Seq[MigrationEventLog] => Box[Seq[MigrationEventLog]]
    , successLogger : Seq[MigrationEventLog] => Unit
    , batchSize     : Int
  ) : Box[Int] = {
    (bestEffort(logs.grouped(batchSize).toSeq) { seq =>
      val res = saveLogs(seq) ?~! "Error when saving logs (ids: %s)".format(seq.map( _.id).sorted.mkString(","))
      res.foreach { seq => successLogger(seq) }
      res
    }).map( _.flatten ).map( _.size ) //flatten else we have Box[Seq[Seq]]]
  }
}



/**
 * ////////////////////////////////////////
 * XML 
 * ////////////////////////////////////////
 */

//test the label of an xml node
object TestLabel {
  def apply(xml:Node, label:String) : Box[Node] = {
    if(xml.label == label) Full(xml)
    else Failure("Entry type is not a '%s' : %s".format(label, xml) )
  }
}

//test that the node is an entry and that it has EXACTLY one child
//do not use to test empty entry
object TestIsEntry {
  def apply(xml:Elem) : Box[Node] = {
    val trimed = XmlUtils.trim(xml)
    if(trimed.label.toLowerCase == "entry" && trimed.child.size == 1) Full(trimed.child.head)
    else Failure("Given XML data has not an 'entry' root element and exactly one child: " + trimed)
  }
}

/**
 * Change labels of a list of Elem
 */
case class ChangeLabel(label:String) extends Function1[NodeSeq, Option[Elem]] {
  import LogMigrationEventLog_10_2.logger

  override def apply(nodes:NodeSeq) = nodes match {
    case e:Elem => Some(e.copy(label = label))
    case x => //ignore other type of nodes
      logger.debug("Can not change the label to '%s' of a NodeSeq other than elem in a CssSel: '%s'".format(label, x))
      None
  }
}

/**
 * Migrate an event log from fileFormat 1.0 to 2
 * Also take care of categories, etc. 
 */
class EventLogMigration_10_2(xmlMigration:XmlMigration_10_2) {
  import LogMigrationEventLog_10_2.logger
  
  def migrate(eventLog:MigrationEventLog) : Box[MigrationEventLog] = {
    /*
     * We don't use values from 
     * com.normation.rudder.domain.eventlog.*EventType
     * so that if they change in the future, the migration
     * from 2.3 to 2.4 is still OK. 
     */
    val MigrationEventLog(id,eventType,data) = eventLog
    
    //utility to factor common code
    def create(xmlFn:Elem => Box[Elem], name:String) = {
      xmlFn(data).map { xml => MigrationEventLog(id, name, xml) }
    }
    
    eventType.toLowerCase match {
      case "acceptnode" => create(xmlMigration.node, "AcceptNode")
      case "refusenode" => create(xmlMigration.node, "RefuseNode")
      
      case "configurationruleadded"    => create(xmlMigration.rule, "RuleAdded")
      case "configurationruledeleted"  => create(xmlMigration.rule, "RuleDeleted")
      case "configurationrulemodified" => create(xmlMigration.rule, "RuleModified")

      case "nodegroupadded"    => create(xmlMigration.nodeGroup, "NodeGroupAdded")
      case "nodegroupdeleted"  => create(xmlMigration.nodeGroup, "NodeGroupDeleted")
      case "nodegroupmodified" => create(xmlMigration.nodeGroup, "NodeGroupModified")

      case "policyinstanceadded"    => create(xmlMigration.directive, "DirectiveAdded")
      case "policyinstancedeleted"  => create(xmlMigration.directive, "DirectiveDeleted")
      case "policyinstancemodified" => create(xmlMigration.directive, "DirectiveModified")
        
      //migrate all start deployment to automatic one
      case "startdeployement" =>  create(xmlMigration.addPendingDeployment, "AutomaticStartDeployement")
        
      /*
       * nothing to do for these ones:
       * - ApplicationStarted
       * - ActivateRedButton, ReleaseRedButton
       * - UserLogin, BadCredentials, UserLogout
       */ 
        
      case _ => 
        val msg = "Not migrating eventLog with [id: %s] [type: %s]: no handler for that type.".format(eventLog.id, eventLog.eventType)
        Failure(msg)
    }
  }
}

/**
 * That class handle migration of XML eventLog file
 * from a 1.0 format to a 2 one. 
 */
class XmlMigration_10_2 {
  
  private[this] def failBadElemType(xml:NodeSeq) = { 
    Failure("Not expected type of NodeSeq (wish it was an Elem): " + xml)
  }
  
  private[this] def isElem(xml:NodeSeq) = {
    xml match {
      case seq if(seq.size == 1) => seq.head match {
        case e:Elem => Full(e)
        case x => failBadElemType(x)
      }
      case x =>
        val y = x
        failBadElemType(x)
    }
  }
  
  def rule(xml:Elem) : Box[Elem] = {
    for {
      isEntryChild <- TestIsEntry(xml)
      labelOK      <- TestLabel(isEntryChild, "configurationRule")
      fileFormatOK <- TestFileFormat(isEntryChild, Constants.XML_FILE_FORMAT_1_0)
      migrated     <- isElem((
                        "configurationRule" #> ChangeLabel("rule") andThen
                        "rule [fileFormat]" #> Constants.XML_FILE_FORMAT_2 andThen
                        "policyInstanceIds" #> ChangeLabel("directiveIds") &
                        "isActivated" #> ChangeLabel("isEnabled")
                      )(xml)) 
    } yield {
      migrated
    }
  }
  
  def directive(xml:Elem) : Box[Elem] = {
    for {
      isEntryChild <- TestIsEntry(xml)
      labelOK      <- TestLabel(isEntryChild, "policyInstance")
      fileFormatOK <- TestFileFormat(isEntryChild, Constants.XML_FILE_FORMAT_1_0)
      migrated     <- isElem((
                        "policyInstance" #> ChangeLabel("directive") andThen
                        "directive [fileFormat]" #> Constants.XML_FILE_FORMAT_2 andThen
                        "policyTemplateName" #> ChangeLabel("techniqueName") &
                        "policyTemplateVersion" #> ChangeLabel("techniqueVersion") &
                        "isActivated" #> ChangeLabel("isEnabled")
                      )(xml)) 
    } yield {
      migrated
    }
  }
  
  def nodeGroup(xml:Elem) : Box[Elem] = {
    for {
      isEntryChild <- TestIsEntry(xml)
      labelOK      <- TestLabel(isEntryChild, "nodeGroup")
      fileFormatOK <- TestFileFormat(isEntryChild, Constants.XML_FILE_FORMAT_1_0)
      migrated     <- isElem((
                        "nodeGroup [fileFormat]" #> Constants.XML_FILE_FORMAT_2 andThen
                        "isActivated" #> ChangeLabel("isEnabled")
                      )(xml)) 
    } yield {
      migrated
    }
  }
  

  def addPendingDeployment(xml:Elem) : Box[Elem] = {
    for {
      isEntryChild <- TestIsEntry(xml)
      labelOK      <- TestLabel(isEntryChild, "addPending")
      fileFormatOK <- if(isEntryChild.attribute("fileFormat").isEmpty) Full("OK") else Failure("Bad file format, expecting none: " + xml)
      migrated     <- isElem((
                        "addPending" #> ChangeLabel("addPendingDeployement") andThen
                        "addPendingDeployement [fileFormat]" #> Constants.XML_FILE_FORMAT_2
                      )(xml)) 
    } yield {
      migrated
    }
  }
  

  def node(xml:Elem) : Box[Elem] = {
    for {
      isEntryChild <- TestIsEntry(xml)
      labelOK      <- TestLabel(isEntryChild, "node")
      fileFormatOK <- TestFileFormat(isEntryChild, Constants.XML_FILE_FORMAT_1_0)
      migrated     <- isElem((
                        "node [fileFormat]" #> Constants.XML_FILE_FORMAT_2
                      )(xml)) 
    } yield {
      migrated
    }
  }
}
