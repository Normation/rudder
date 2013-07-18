/*
*************************************************************************************
* Copyright 2012-2013 Normation SAS
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



/**
 * General information about that migration
 */
trait Migration_2_3_Definition extends XmlFileFormatMigration {

  override val fromVersion = Constants.XML_FILE_FORMAT_2
  override val toVersion   = Constants.XML_FILE_FORMAT_3

}

/**
 * This class manage the hight level migration process: read if a
 * migration is required in the MigrationEventLog datatable, launch
 * the migration process, write migration result.
 * The actual migration of event logs is delegated to EventLogsMigration_10_2
 */
class ControlEventLogsMigration_2_3(
    override val migrationEventLogRepository: MigrationEventLogRepository
  , override val batchMigrators             : Seq[BatchElementMigration[MigrationEventLog]]
) extends ControlXmlFileFormatMigration with Migration_2_3_Definition {
  override val previousMigrationController = None
}


/**
 * The class that handle the processing of the list of all event logs
 * logic.
 * Each individual eventlog is processed in EventLogMigration_2_3
 *
 */
class EventLogsMigration_2_3(
    override val jdbcTemplate       : JdbcTemplate
  , override val individualMigration: EventLogMigration_2_3
  , override val batchSize          : Int = 1000
) extends BatchElementMigration[MigrationEventLog] with Migration_2_3_Definition {


  override val elementName = "EventLog"
  override val rowMapper = MigrationEventLogMapper
  override val selectAllSqlRequest = "SELECT id, eventType, data FROM eventlog"

  override protected def save(logs:Seq[MigrationEventLog]) : Box[Seq[MigrationEventLog]] = {
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

}


/**
 * Migrate an event log from fileFormat 2 to 3
 * Also take care of categories, etc.
 */
class EventLogMigration_2_3(
    xmlMigration:XmlMigration_2_3
) extends IndividualElementMigration[MigrationEventLog] with Migration_2_3_Definition {

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
class XmlMigration_2_3 extends Migration_2_3_Definition {

  def rule(xml:Elem) : Box[Elem] = {
    for {
      labelOK      <- TestLabel(xml, "rule")
      fileFormatOK <- TestFileFormat(xml, fromVersion.toString())
      migrated     <-

                    if (xml.attribute("changeType").map(_.text) == Some("modify"))
                      TestIsElem(
                        (
                        "rule [fileFormat]" #> toVersion  &
                        "target " #>  ChangeLabel("targets", logger) andThen
                        "targets *" #> ("none " #>  NodeSeq.Empty andThen
                        "to"  #> EncapsulateChild("target", logger) andThen
                        "from" #> EncapsulateChild("target", logger))

                      )(xml))
                    else //handle add/deletion
                      TestIsElem(
                        (
                        "rule [fileFormat]" #> toVersion  &

                        "target " #> ChangeLabel("targets", logger) andThen
                        "none "   #>  NodeSeq.Empty andThen
                        "targets" #> EncapsulateChild("target", logger)

                      )(xml))
    } yield {
      migrated
    }
  }

  def other(xml:Elem) : Box[Elem] = {
    for {
      fileFormatOK <- TestFileFormat(xml, fromVersion.toString())
      migrated     <- TestIsElem((
                        //here we use the hypothesis that no other element than the entity type has an attribute fileformat to 2
                        "fileFormat=2 [fileFormat]" #> toVersion
                      ).apply(xml))
    } yield {
      migrated
    }
  }

}