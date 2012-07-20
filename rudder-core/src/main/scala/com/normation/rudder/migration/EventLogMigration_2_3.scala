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
object LogMigrationEventLog_2_3 {
  val logger = Logger("migration-2.3-2.4-eventlog-xml-format-2-3")
  
  val defaultErrorLogger : Failure => Unit = { f => 
    logger.error(f.messageChain)
    f.rootExceptionCause.foreach { ex => 
      logger.error("Root exception was:", ex)
    }
  }
  val defaultSuccessLogger : Seq[MigrationEventLog] => Unit = { seq =>
    if(logger.isDebugEnabled) {
      seq.foreach { log => 
        logger.debug("Migrating eventlog to format 3, id: " + log.id)
      }
    }
    logger.info("Successfully migrated %s eventlog to format 3".format(seq.size))
  }
}

/**
 * This class manage the hight level migration process: read if a
 * migration is required in the MigrationEventLog datatable, launch
 * the migration process, write migration result. 
 * The actual migration of event logs is delegated to EventLogsMigration_10_2
 */
class ControlEventLogsMigration_2_3(
    migrationEventLogRepository: MigrationEventLogRepository
  , eventLogsMigration_2_3    : EventLogsMigration_2_3

) {
  import LogMigrationEventLog_2_3.logger
  
  val parent = new ControlEventLogsMigration_10_2(migrationEventLogRepository,eventLogsMigration_2_3.parent)
  def migrate() : Box[MigrationStatus] = {
    /*
     * test is we have to migrate, and execute migration
     */
    migrationEventLogRepository.getLastDetectionLine match {
      case None => 
        logger.info("No migration detected by migration script (table '%s' is empty or does not exists)".
            format(MigrationEventLogTable.migrationEventLog.name)
        )
        Full(NoMigrationRequested)
        
      /*
       * we only have to deal with the migration if:
       * - fileFormat is == 2 AND (
       *   - migrationEndTime is not set OR
       *   - migrationFileFormat == 2
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
      )) if(detectedFileFormat == 2) => 
        /*
         * here, simply start a migration for the first time (if migrationStartTime is None)
         * or continue a previously started migration (but interrupted ?)
         */
        if(migrationStartTime.isEmpty) {
          migrationEventLogRepository.setMigrationStartTime(status.id, new Timestamp(Calendar.getInstance.getTime.getTime))
        }
        
        logger.info("Start migration of EventLog from format '2' to '3'")

        eventLogsMigration_2_3.processEventLogs() match {
          case Full(i) =>
            logger.info("Migration from EventLog fileFormat from '2' to '3' done, %s EventLogs migrated".format(i))
            migrationEventLogRepository.setMigrationFileFormat(status.id, 3, new Timestamp(Calendar.getInstance.getTime.getTime))
            Full(MigrationSuccess(i))
            
          case eb:EmptyBox => 
            val e = (eb ?~! "Could not correctly finish the migration from EventLog fileFormat from '2' to '3'. Check logs for errors. The process can be trigger by restarting the application")
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
      )) if(migrationFileFormat == 2) =>
        //create a new status line with detected format = migrationFileFormat,
        //and a description to say why we recurse
        migrationEventLogRepository.createNewStatusLine(migrationFileFormat, Some("Found a post-migration fileFormat='%s': update".format(migrationFileFormat)))
        this.migrate()
      
          // lower file format found, send to parent)
      case Some(status@SerializedMigrationEventLog(
          _
        , detectedFileFormat
        , migrationStartTime
        , migrationEndTime @ None
        , _
        , _
      )) if(detectedFileFormat < 2) =>

        
        logger.info("Found and older migration to do")
        parent.migrate() match{
        case Full(MigrationSuccess(i)) =>
            logger.info("Older migration completed, relaunch migration")
            this.migrate()
        case eb:EmptyBox => 
            val e = (eb ?~! "Older migration failed, Could not correctly finish the migration from EventLog fileFormat from '2' to '3'. Check logs for errors. The process can be trigger by restarting the application")
            logger.error(e)
            e 
        case _ =>
            logger.info("Older migration completed, relaunch migration")
            this.migrate()
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
        parent.migrate()
        this.migrate()
          
          
            
         
        
        
      //other case: does nothing
      case Some(x) => 
        logger.debug("Migration of EventLog from format '2' to '3': nothing to do")
        Full(MigrationVersionNotHandledHere)
    }
  }
  
}

/**
 * The class that handle the processing of the list of all event logs
 * logic.
 * Each individual eventlog is processed in EventLogMigration_2_3
 * 
 */
class EventLogsMigration_2_3(
    jdbcTemplate               : JdbcTemplate
  , eventLogMigration          : EventLogMigration_2_3
  , errorLogger                : Failure => Unit
  , successLogger              : Seq[MigrationEventLog] => Unit
  , batchSize                  : Int = 1000
) {
  import LogMigrationEventLog_2_3.logger
  
 val parent = new EventLogsMigration_10_2(jdbcTemplate,new EventLogMigration_10_2(new XmlMigration_10_2()),LogMigrationEventLog_10_2.defaultErrorLogger
    ,LogMigrationEventLog_10_2.defaultSuccessLogger,batchSize)
  /**
   * retrieve all event log to migrate. 
   */
  def findAllEventLogs : Box[Seq[MigrationEventLog]] = {
    
    //check if the event must be migrated
    def needMigration(xml:NodeSeq) : Boolean = (
    try {
           (xml \\ "@fileFormat" exists { _.text.toInt == 2 })
         } catch {
           case e:NumberFormatException => false
         }
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
 * Migrate an event log from fileFormat 2 to 3
 * Also take care of categories, etc. 
 */
class EventLogMigration_2_3(xmlMigration:XmlMigration_2_3) {
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
      
      case "ruleadded"    => create(xmlMigration.rule, "RuleAdded")
      case "ruledeleted"  => create(xmlMigration.rule, "RuleDeleted")
      case "rulemodified" => create(xmlMigration.rule, "RuleModified")

      case "nodegroupadded"    => create(xmlMigration.nodeGroup, "NodeGroupAdded")
      case "nodegroupdeleted"  => create(xmlMigration.nodeGroup, "NodeGroupDeleted")
      case "nodegroupmodified" => create(xmlMigration.nodeGroup, "NodeGroupModified")

      case "directiveadded"    => create(xmlMigration.directive, "DirectiveAdded")
      case "directivedeleted"  => create(xmlMigration.directive, "DirectiveDeleted")
      case "directivemodified" => create(xmlMigration.directive, "DirectiveModified")
        
      //migrate all start deployment to automatic one
      case "automaticstartdeployement" =>  create(xmlMigration.addPendingDeployment, "AutomaticStartDeployement")
        
      /*
       * nothing to do for these ones, they don't have a fileformat value:
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
 * from format 2 to a 3. 
 */
class XmlMigration_2_3 extends XmlMigration {
  
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
      labelOK      <- TestLabel(isEntryChild, "rule")
      fileFormatOK <- TestFileFormat(isEntryChild, Constants.XML_FILE_FORMAT_2.toString())
      migrated     <- isElem((
                        "rule [fileFormat]" #> Constants.XML_FILE_FORMAT_3  &
                     
                        "target " #> Encapsulate("targets")
                      
                      )(xml)) 
    } yield {
      migrated
    }
  }
  
  def directive(xml:Elem) : Box[Elem] = {
    for {
      isEntryChild <- TestIsEntry(xml)
      labelOK      <- TestLabel(isEntryChild, "directive")
      fileFormatOK <- TestFileFormat(isEntryChild, Constants.XML_FILE_FORMAT_2.toString())
      migrated     <- isElem((
                        "directive [fileFormat]" #> Constants.XML_FILE_FORMAT_3
                      )(xml)) 
    } yield {
      migrated
    }
  }
  
  def nodeGroup(xml:Elem) : Box[Elem] = {
    for {
      isEntryChild <- TestIsEntry(xml)
      labelOK      <- TestLabel(isEntryChild, "nodeGroup")
      fileFormatOK <- TestFileFormat(isEntryChild, Constants.XML_FILE_FORMAT_2.toString())
      migrated     <- isElem((
                        "nodeGroup [fileFormat]" #> Constants.XML_FILE_FORMAT_3
                      )(xml)) 
    } yield {
      migrated
    }
  }
  

  def addPendingDeployment(xml:Elem) : Box[Elem] = {
    for {
      isEntryChild <- TestIsEntry(xml)
      labelOK      <- TestLabel(isEntryChild, "addPendingDeployement")
      fileFormatOK <- TestFileFormat(isEntryChild, Constants.XML_FILE_FORMAT_2.toString())
      migrated     <- isElem((
                        "addPendingDeployement [fileFormat]" #> Constants.XML_FILE_FORMAT_3
                      )(xml)) 
    } yield {
      migrated
    }
  }
  

  def node(xml:Elem) : Box[Elem] = {
    for {
      isEntryChild <- TestIsEntry(xml)
      labelOK      <- TestLabel(isEntryChild, "node")
      fileFormatOK <- TestFileFormat(isEntryChild, Constants.XML_FILE_FORMAT_2.toString())
      migrated     <- isElem((
                        "node [fileFormat]" #> Constants.XML_FILE_FORMAT_3
                      )(xml)) 
    } yield {
      migrated
    }
  }
}