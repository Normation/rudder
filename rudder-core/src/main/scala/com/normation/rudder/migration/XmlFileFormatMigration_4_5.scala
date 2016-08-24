/*
*************************************************************************************
* Copyright 2013 Normation SAS
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
trait Migration_4_5_Definition extends XmlFileFormatMigration {

  override val fromVersion = Constants.XML_FILE_FORMAT_4
  override val toVersion   = Constants.XML_FILE_FORMAT_5

}


class ControlXmlFileFormatMigration_4_5(
    override val migrationEventLogRepository: MigrationEventLogRepository
  , override val batchMigrators             : Seq[BatchElementMigration[_]]
  , override val previousMigrationController: Option[ControlXmlFileFormatMigration]
) extends ControlXmlFileFormatMigration with Migration_4_5_Definition

/**
 * The class that handle the processing of the list of all event logs
 * logic.
 * Each individual eventlog is processed in EventLogMigration_4_5
 *
 */
class EventLogsMigration_4_5(
    override val jdbcTemplate       : JdbcTemplate
  , override val individualMigration: EventLogMigration_4_5
  , val eventLogsMigration_3_4      : EventLogsMigration_3_4
  , override val batchSize          : Int = 1000
) extends EventLogsMigration with Migration_4_5_Definition


/**
 * Migrate an event log from fileFormat 4 to 5
 * Also take care of categories, etc.
 */
class EventLogMigration_4_5(
    xmlMigration:XmlMigration_4_5
) extends IndividualElementMigration[MigrationEventLog] with Migration_4_5_Definition {

  def migrate(eventLog:MigrationEventLog) : Box[MigrationEventLog] = {
    /*
     * We don't use values from
     * com.normation.rudder.domain.eventlog.*EventType
     * so that if they change in the future, the migration
     * from 2.3 to 2.5 is still OK.
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
                    //Need only to modify rule added and Rule deleted events
                    case "ruleadded"    => create(xmlMigration.rule(xml), "RuleAdded")
                    case "ruledeleted"  => create(xmlMigration.rule(xml), "RuleDeleted")

                    case _    => create(xmlMigration.other(xml), eventType)
                  }
    } yield {
      migrated
    }
  }
}

/**
 * That class handle migration of XML eventLog and change request
 * from format 4 to a 5.
 *
 * Hypothesis:
 * - only Rules have changed, need to add tag category to all rules
 * - So we need to update all rules contained in change request
 */
class XmlMigration_4_5 extends Migration_4_5_Definition {

  def changeRequest(xml:Elem) : Box[Elem] = {
    for {
      labelOK      <- TestLabel(xml, "changeRequest")
      fileFormatOK <- TestFileFormat(xml,fromVersion.toString())
      migrated     <-
                      TestIsElem(
                        ( // We need to get all 'rule' tags from each rule modified in a change request
                          "rule rule *+" #> <category>rootRuleCategory</category>&
                          "* fileFormat=4 [fileFormat]" #> toVersion
                        ).apply(xml)
                      )
    } yield {
      migrated
    }
  }

    def rule(xml:Elem) : Box[Elem] = {
    for {
      labelOK      <- TestLabel(xml, "rule")
      fileFormatOK <- TestFileFormat(xml, fromVersion.toString())
      migrated     <- TestIsElem(
                        ( "rule [fileFormat]" #> toVersion  &
                          "rule *+" #> <category>rootRuleCategory</category>
                        ) (xml)
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
                        "fileFormat=4 [fileFormat]" #> toVersion
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
class ChangeRequestsMigration_4_5(
    override val jdbcTemplate       : JdbcTemplate
  , override val individualMigration: ChangeRequestMigration_4_5
  , override val batchSize          : Int = 1000
) extends ChangeRequestsMigration with Migration_4_5_Definition

/**
 * Migrate change requests fileFormat 4 to 5
 */
class ChangeRequestMigration_4_5(
    xmlMigration:XmlMigration_4_5
) extends IndividualElementMigration[MigrationChangeRequest] with Migration_4_5_Definition {

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
