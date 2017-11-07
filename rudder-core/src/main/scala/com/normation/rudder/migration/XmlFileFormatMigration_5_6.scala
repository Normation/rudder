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

import scala.xml.Elem


import com.normation.rudder.domain.Constants
import com.normation.rudder.services.marshalling.TestFileFormat

import net.liftweb.common.Box
import net.liftweb.util.Helpers.StringToCssBindPromoter
import com.normation.rudder.db.Doobie

/**
 * General information about that migration
 */
trait Migration_5_6_Definition extends XmlFileFormatMigration {

  override val fromVersion = Constants.XML_FILE_FORMAT_5
  override val toVersion   = Constants.XML_FILE_FORMAT_6

}


/**
 * That class handle migration of XML eventLog and change request
 * from format 5 to a 6.
 * All the remaining is not very intersting and just plumbing
 */
object XmlMigration_5_6 extends Migration_5_6_Definition {

  def changeRequest(xml:Elem) : Box[Elem] = {
    for {
      labelOK      <- TestLabel(xml, "changeRequest")
      fileFormatOK <- TestFileFormat(xml,fromVersion.toString())
      migrated     <-
                      TestIsElem(
                        ( // We need to get all 'rule' tags from each rule modified in a change request
                          "rule fileFormat=5 *+" #> <category>rootRuleCategory</category> andThen
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

class ControlXmlFileFormatMigration_5_6(
    override val migrationEventLogRepository: MigrationEventLogRepository
  ,          val doobie                     : Doobie
  , override val previousMigrationController: Option[ControlXmlFileFormatMigration]
  ,          val batchSize                  : Int = 1000
) extends ControlXmlFileFormatMigration with Migration_5_6_Definition {
  override val batchMigrators = (
       new EventLogsMigration_5_6(doobie, batchSize)
    :: new ChangeRequestsMigration_5_6(doobie, batchSize)
    :: Nil
  )
}

/**
 * The class that handle the processing of the list of all event logs
 * logic.
 * Each individual eventlog is processed in EventLogMigration_5_6
 *
 */
class EventLogsMigration_5_6(
    override val doobie      : Doobie
  , override val batchSize   : Int = 1000
) extends EventLogsMigration with Migration_5_6_Definition {
  override val individualMigration = new IndividualElementMigration[MigrationEventLog] with Migration_5_6_Definition {

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
                      case _    => create(XmlMigration_5_6.other(xml), eventType)
                    }
      } yield {
        migrated
      }
    }
  }
}


/**
 * The class that handle the processing of the list of all event logs
 * logic.
 * Each individual eventlog is processed in EventLogMigration_5_6
 *
 */
class ChangeRequestsMigration_5_6(
    override val doobie      : Doobie
  , override val batchSize   : Int = 1000
) extends ChangeRequestsMigration with Migration_5_6_Definition {
  override val individualMigration = new IndividualElementMigration[MigrationChangeRequest] with Migration_5_6_Definition {
    def migrate(cr:MigrationChangeRequest) : Box[MigrationChangeRequest] = {

      val MigrationChangeRequest(id,name, content) = cr

      //utility to factor common code
      def create(optElem:Box[Elem], name:String) = {
         optElem.map { xml => MigrationChangeRequest(id, name, xml) }
      }

      for {
        migrated <- create(XmlMigration_5_6.changeRequest(content), name)
      } yield {
        migrated
      }
    }
  }
}


