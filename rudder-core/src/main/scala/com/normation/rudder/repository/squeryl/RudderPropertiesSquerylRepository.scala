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

package com.normation.rudder.repository.squeryl


import com.normation.rudder.repository.jdbc._
import com.normation.rudder.repository._
import org.squeryl._
import org.squeryl.annotations._
import org.squeryl.PrimitiveTypeMode._
import net.liftweb.common._

object RudderPropertiesTable extends Schema {

  val rudderProperties = table[RudderProperty]("rudderproperties")

}

case class RudderProperty (
    @Column("name")  id    : String
  , @Column("value") value : String

) extends KeyedEntity[String]

class RudderPropertiesSquerylRepository(
      squerylConnectionProvider : SquerylConnectionProvider, 
    reportsRepository: ReportsRepository
    ) extends RudderPropertiesRepository with Loggable {

  import RudderPropertiesTable._
  /**
   * Get the last report id processed by the non compliant report Logger.
   * If there was no id processed, get the last report id in reports database
   * and add it to the database.
   */
  def getReportLoggerLastId: Box[Int] = {
    try {
    squerylConnectionProvider.ourTransaction {
      rudderProperties.lookup("reportLoggerLastId").map(_.value.toInt)
      }
    } catch {
      case e : Exception => logger.error("could not fetch last id from the database, cause is %s".format(e.getMessage()))
      Failure("could not fetch last id from the database, cause is %s".format(e.getMessage()))
    }
  }

  /**
   * Update the last id processed by the non compliant report logger
   * If not present insert it
   */
   def updateReportLoggerLastId(newId: Int) : Box[Int] = {
    try{
    val updated = squerylConnectionProvider.ourTransaction {
      update(rudderProperties)(l =>
        where(l.id === "reportLoggerLastId")
        set(l.value := newId.toString)
      )
    }
    if (updated == 0){
      logger.warn("last id not present in database, create it with value %d".format(newId))
      createReportLoggerLastId(newId).map(_.value.toInt)
    } else
      Full(newId)
    } catch {
      case e : Exception => logger.error("could not update lastId from database, cause is %s".format(e.getMessage()))
      Failure("could not update lastId from database, cause is %s".format(e.getMessage()))
    }
  }

  /**
   * add a new line containing the last id processed by the report logger
   */
  private[this] def createReportLoggerLastId(currentId : Int) : Box[RudderProperty] =
    try{
      Full(squerylConnectionProvider.ourTransaction {
        rudderProperties.insert(RudderProperty("reportLoggerLastId",currentId.toString))
    } )
    } catch {
      case e : Exception => logger.error("could not create last id: cause is %s".format(e.getMessage()))
      Failure("could not create last id cause is %s".format(e.getMessage()))
    }
}