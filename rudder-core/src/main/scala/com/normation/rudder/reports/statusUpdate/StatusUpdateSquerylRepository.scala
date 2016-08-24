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

package com.normation.rudder.reports.statusUpdate

import org.squeryl.PrimitiveTypeMode._
import org.squeryl.Schema
import org.squeryl.annotations.Column
import net.liftweb.common._
import com.normation.rudder.repository.jdbc._
import java.sql.Timestamp
import org.squeryl.KeyedEntity
import org.joda.time.DateTime
import net.liftweb.util.Helpers.tryo

class StatusUpdateSquerylRepository (
    sessionProvider : SquerylConnectionProvider
) extends StatusUpdateRepository with Loggable {
  import StatusUpdate._


  def getExecutionStatus : Box[Option[(Long,DateTime)]] = {
    getValue(executionStatus)
  }

  private def getValue(key : String) : Box[Option[(Long,DateTime)]] = {
    try {
      sessionProvider.ourSession {
        val q = from(statusTable)(entry =>
          where(entry.key === key)
          select(entry)
        )
        val result = q.toList

        result match {
          case Nil => Full(None)
          case head :: Nil =>
            Full(Some((head.lastId,new DateTime(head.date))))
          case _ =>
            val msg = s"Too many entry matching ${key} in table StatusUpdate "
            logger.error(msg)
            Failure(msg)
        }
      }
    } catch {
     case e:Exception => Failure(s"Error while fetching ${key} in table StatusUpdate :$e")
    }

  }


  def setExecutionStatus(newId : Long, reportsDate : DateTime) : Box[UpdateEntry] = {
    setValue(executionStatus, newId, reportsDate)
  }

  private[this] def setValue(key : String, reportId : Long, reportsDate : DateTime) : Box[UpdateEntry] = {
    try {
      sessionProvider.ourTransaction {
        val timeStamp = new Timestamp(reportsDate.getMillis)
        val q = update(statusTable)(entry =>
          where(entry.key === key)
          set(entry.lastId := reportId, entry.date := timeStamp))
        val entry = new UpdateEntry(key, reportId, timeStamp)
        if (q ==0) // could not update
          Full(statusTable.insert(entry))
        else {
          Full(entry)
        }
      }
    } catch {
     case e:Exception =>
       val msg = s"Error while setting ${key} in table StatusUpdate cause is: ${e.getMessage()}"
       logger.error(msg)
       Failure(msg)
    }
  }

}

case class UpdateEntry(
    @Column("key")    key    : String,
    @Column("lastid") lastId : Long,
    @Column("date")   date   : Timestamp
) extends KeyedEntity[String]  {
  def id = key
}

object StatusUpdate extends Schema {
  val statusTable = table[UpdateEntry]("statusupdate")

  val executionStatus = "executionStatus"
}
