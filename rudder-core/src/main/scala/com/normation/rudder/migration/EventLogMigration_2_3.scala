/*
*************************************************************************************
* Copyright 2012 Normation SAS
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

import com.normation.rudder.domain.logger._
import com.normation.rudder.domain.Constants
import com.normation.rudder.repository.jdbc.SquerylConnectionProvider
import com.normation.rudder.services.marshalling.TestFileFormat
import com.normation.utils.Control._
import com.normation.utils.XmlUtils

import net.liftweb.common._
import net.liftweb.util.Helpers.strToCssBindPromoter
import net.liftweb.util.Helpers.tryo
import net.liftweb.util.IterableFunc.itNodeSeq
import net.liftweb.util.StringPromotable.intToStrPromo

/*
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
*/
/**
 * This class manage the hight level migration process: read if a
 * migration is required in the MigrationEventLog datatable, launch
 * the migration process, write migration result.
 * The actual migration of event logs is delegated to EventLogsMigration_10_2
 */
class ControlEventLogsMigration_2_3(
    migrationEventLogRepository   : MigrationEventLogRepository
  , eventLogsMigration_2_3        : EventLogsMigration_2_3
) {

  def logger = MigrationLogger(3)

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
        , _
        , _
        , _
        , _
      )) if(detectedFileFormat < 2) =>


        logger.error(s"The file format ${detectedFileFormat} is no more supported. The last version to support it is Rudder 2.6.x")
        Full(MigrationVersionNotHandledHere)


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
  def logger = MigrationLogger(3)


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
  def logger = MigrationLogger(3)

  def migrate(eventLog:MigrationEventLog) : Box[MigrationEventLog] = {
    /*
     * We don't use values from
     * com.normation.rudder.domain.eventlog.*EventType
     * so that if they change in the future, the migration
     * from 2.3 to 2.4 is still OK.
     */
    val MigrationEventLog(id,eventType,data) = eventLog


    /*
     * -- Important--
     * The <entry></entry> part is tested here, then removed
     * for migration, then added back in create.
     * That is to have XmlMigration rule be independant of
     * <entry>.
     */



    //utility to factor common code
    //notice the addition of <entry> tag in the result
    def create(optElem:Box[Elem], name:String) = {
       optElem.map { xml => MigrationEventLog(id, name, <entry>{xml}</entry>) }
    }

    for {
      xml      <- TestIsEntry(data)
      migrated <- eventType.toLowerCase match {
                    case "ruleadded"    => create(xmlMigration.rule(xml), "RuleAdded")
                    case "ruledeleted"  => create(xmlMigration.rule(xml), "RuleDeleted")
                    case "rulemodified" => create(xmlMigration.rule(xml), "RuleModified")

                    /*
                     * When migrating from 2 to 3, no eventType name change,
                     * so we can just pass it.
                     */
                    case _    => create(xmlMigration.other(xml), eventType)
                  }
    } yield {
      migrated
    }
  }
}

/**
 * That class handle migration of XML eventLog file
 * from format 2 to a 3.
 *
 * Hypothesis:
 * - only rule was change, and only "target" was change
 *   (now we can have several targets, so we have <targets><target></target>...</targets>
 * - all other elements are well formed, and have a file format attribute, and it's 2
 *   (because we filtered them to be so)
 * - only the entity tag (<group ...>, <directive ...>, etc has a fileformat="2" attribute
 */
class XmlMigration_2_3 {

  def rule(xml:Elem) : Box[Elem] = {
    for {
      labelOK      <- TestLabel(xml, "rule")
      fileFormatOK <- TestFileFormat(xml, Constants.XML_FILE_FORMAT_2.toString())
      migrated     <-

                    if (xml.attribute("changeType").map(_.text) == Some("modify"))
                      TestIsElem(
                        (
                        "rule [fileFormat]" #> Constants.XML_FILE_FORMAT_3  &
                        "target " #>  ChangeLabel("targets") andThen
                        "targets *" #> ("none " #>  NodeSeq.Empty andThen
                        "to"  #> EncapsulateChild("target") andThen
                        "from" #> EncapsulateChild("target"))

                      )(xml))
                    else //handle add/deletion
                      TestIsElem(
                        (
                        "rule [fileFormat]" #> Constants.XML_FILE_FORMAT_3  &

                        "target " #> ChangeLabel("targets") andThen
                        "none "   #>  NodeSeq.Empty andThen
                        "targets" #> EncapsulateChild("target")

                      )(xml))
    } yield {
      migrated
    }
  }

  def other(xml:Elem) : Box[Elem] = {
    for {
      fileFormatOK <- TestFileFormat(xml, Constants.XML_FILE_FORMAT_2.toString())
      migrated     <- TestIsElem((
                        //here we use the hypothesis that no other element than the entity type has an attribute fileformat to 2
                        "fileFormat=2 [fileFormat]" #> Constants.XML_FILE_FORMAT_3
                      ).apply(xml))
    } yield {
      migrated
    }
  }

}