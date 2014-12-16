/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

import java.sql.PreparedStatement

import scala.xml.Elem

import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate

import com.normation.rudder.domain.Constants
import com.normation.rudder.services.marshalling.TestFileFormat

import net.liftweb.common.Box
import net.liftweb.util.Helpers.strToCssBindPromoter
import net.liftweb.util.Helpers.tryo




/**
 * General information about that migration
 */
trait Migration_5_6_Definition extends XmlFileFormatMigration {

  override val fromVersion = Constants.XML_FILE_FORMAT_5
  override val toVersion   = Constants.XML_FILE_FORMAT_6

}


class ControlXmlFileFormatMigration_5_6(
    override val migrationEventLogRepository: MigrationEventLogRepository
  , override val batchMigrators             : Seq[BatchElementMigration[_]]
  , override val previousMigrationController: Option[ControlXmlFileFormatMigration]
) extends ControlXmlFileFormatMigration with Migration_5_6_Definition

/**
 * The class that handle the processing of the list of all event logs
 * logic.
 * Each individual eventlog is processed in EventLogMigration_5_6
 *
 */
class EventLogsMigration_5_6(
    override val jdbcTemplate       : JdbcTemplate
  , override val individualMigration: EventLogMigration_5_6
  , val eventLogsMigration_4_5      : EventLogsMigration_4_5
  , override val batchSize          : Int = 1000
) extends BatchElementMigration[MigrationEventLog] with Migration_5_6_Definition {

  override val elementName = "EventLog"
  override val rowMapper = MigrationEventLogMapper
  override val selectAllSqlRequest = "SELECT id, eventType, data FROM eventlog"

  override protected def save(logs:Seq[MigrationEventLog]) : Box[Seq[MigrationEventLog]] = {
    val UPDATE_SQL = "UPDATE EventLog set data = ? where id = ?"

    val ilogs = logs match {
      case x:IndexedSeq[_] => logs
      case seq => seq.toIndexedSeq
    }

    tryo { jdbcTemplate.batchUpdate(
               UPDATE_SQL
             , new BatchPreparedStatementSetter() {
                 override def setValues(ps: PreparedStatement, i: Int): Unit = {
                   val sqlXml = ps.getConnection.createSQLXML()
                   sqlXml.setString(ilogs(i).data.toString)
                   ps.setSQLXML(1, sqlXml)
                   ps.setLong(2, ilogs(i).id )
                 }

                 override def getBatchSize() = ilogs.size
               }
    ) }.map( _ => ilogs )
  }
}


/**
 * Migrate an event log from fileFormat 5 to 6
 * Also take care of categories, etc.
 */
class EventLogMigration_5_6(
    xmlMigration:XmlMigration_5_6
) extends IndividualElementMigration[MigrationEventLog] with Migration_5_6_Definition {

  def migrate(eventLog:MigrationEventLog) : Box[MigrationEventLog] = {
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
                    case _    => create(xmlMigration.other(xml), eventType)
                  }
    } yield {
      migrated
    }
  }
}

/**
 * That class handle migration of XML eventLog and change request
 * from format 5 to a 6.
 */
class XmlMigration_5_6 extends Migration_5_6_Definition {

  def changeRequest(xml:Elem) : Box[Elem] = {
    for {
      labelOK      <- TestLabel(xml, "changeRequest")
      fileFormatOK <- TestFileFormat(xml,fromVersion.toString())
      migrated     <-
                      TestIsElem(
                        ( // We need to get all 'rule' tags from each rule modified in a change request
                          "rule rule *+" #> <category>rootRuleCategory</category>&
                          "* fileFormat=5 [fileFormat]" #> toVersion
                        ).apply(xml)
                      )
    } yield {
      migrated
    }
  }

  def other(xml:Elem) : Box[Elem] = {
    for {
      fileFormatOK <- TestFileFormat(xml,fromVersion.toString())
      migrated     <- TestIsElem((
                        //here we use the hypothesis that no other element than the entity type has an attribute fileformat to 4
                        "fileFormat=5 [fileFormat]" #> toVersion
                      ).apply(xml))
    } yield {
      migrated
    }
  }

}




/**
 * The class that handle the processing of the list of all event logs
 * logic.
 * Each individual eventlog is processed in EventLogMigration_4_5
 *
 */
class ChangeRequestsMigration_5_6(
    override val jdbcTemplate       : JdbcTemplate
  , override val individualMigration: ChangeRequestMigration_5_6
  , override val batchSize          : Int = 1000
) extends BatchElementMigration[MigrationChangeRequest] with Migration_5_6_Definition {

  override val elementName = "ChangeRequest"
  override val rowMapper = MigrationChangeRequestMapper
  override val selectAllSqlRequest = "SELECT id, name, content FROM changerequest"


  override protected def save(logs:Seq[MigrationChangeRequest]) : Box[Seq[MigrationChangeRequest]] = {
    val UPDATE_SQL = "UPDATE changerequest set content = ? where id = ?"

    val ilogs = logs match {
      case x:IndexedSeq[_] => logs
      case seq => seq.toIndexedSeq
    }

    tryo { jdbcTemplate.batchUpdate(
               UPDATE_SQL
             , new BatchPreparedStatementSetter() {
                 override def setValues(ps: PreparedStatement, i: Int): Unit = {
                   val sqlXml = ps.getConnection.createSQLXML()
                   sqlXml.setString(ilogs(i).data.toString)
                   ps.setSQLXML(1, sqlXml)
                   ps.setLong(2, ilogs(i).id )
                 }

                 override def getBatchSize() = ilogs.size
               }
    ) }.map( _ => ilogs )
  }
}

/**
 * Migrate change requests fileFormat 4 to 5
 */
class ChangeRequestMigration_5_6(
    xmlMigration:XmlMigration_5_6
) extends IndividualElementMigration[MigrationChangeRequest] with Migration_5_6_Definition {

  def migrate(cr:MigrationChangeRequest) : Box[MigrationChangeRequest] = {
    /*
     * We don't use values from
     * com.normation.rudder.domain.eventlog.*EventType
     * so that if they change in the future, the migration
     * from 2.3 to 2.5 is still OK.
     */
    val MigrationChangeRequest(id,name, content) = cr


    //utility to factor common code
    def create(optElem:Box[Elem], name:String) = {
       optElem.map { xml => MigrationChangeRequest(id, name, xml) }
    }

    for {
      migrated <- create(xmlMigration.changeRequest(content), name)
    } yield {
      migrated
    }
  }
}



