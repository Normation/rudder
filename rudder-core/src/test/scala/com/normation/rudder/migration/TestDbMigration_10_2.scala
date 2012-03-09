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

import com.normation.rudder.repository.jdbc.SquerylConnectionProvider
import java.sql.Driver
import java.sql.DriverManager
import java.io.FileInputStream
import java.util.Properties
import java.sql.Connection
import java.sql.ResultSet
import Migration_10_2_DATA_Other._
import Migration_10_2_DATA_Group._
import Migration_10_2_DATA_Directive._
import Migration_10_2_DATA_Rule._
import net.liftweb.common._
import net.liftweb.util.Helpers
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import org.apache.commons.dbcp.BasicDataSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import scala.xml.XML
import scala.collection.JavaConverters._
import scala.xml.Elem
import org.specs2.specification.Fragments
import org.specs2.specification.Step

case class MigEx102(msg:String) extends Exception(msg)

/**
 * Test how the migration run with a Database context
 * 
 * Prerequise: A postgres database must be available, 
 * with parameters defined in src/test/resources/database.properties. 
 * That database should be empty to avoid table name collision. 
 */
@RunWith(classOf[JUnitRunner])
class TestDbMigration_10_2 extends DBCommon {
  
  val sqlClean = """
DROP TABLE IF EXISTS EventLog;
DROP SEQUENCE IF EXISTS eventLogIdSeq;
"""    
  
  val sqlInit = """
CREATE SEQUENCE eventLogIdSeq START 1;

CREATE TABLE EventLog (
  id integer PRIMARY KEY  DEFAULT nextval('eventLogIdSeq')
, creationDate timestamp with time zone NOT NULL DEFAULT 'now'
, severity integer
, causeId integer
, principal varchar(64)
, eventType varchar(64)
, data xml
);
    """  
  
  var logs10WithId : Map[String,TestLog] = null //init in initDn
  var logs2WithId : Seq[TestLog] = null
  
  override def initDb = {
    super.initDb
    
    // init datas, get the map of ids 
    logs10WithId = withConnection[Map[String,TestLog]] { c =>
      (Migration_10_2_DATA_EventLogs.data_10.map { case (k,log) =>
        val id = log.insertSql(c)
        logger.debug("Inserting %s, id: %s".format(k,id))
        
        (k,log.copy( id = Some(id) ))
      }).toMap
    }

    logs2WithId = (Migration_10_2_DATA_EventLogs.data_2.map { case (k,log) =>
      log.copy( id = Some(logs10WithId(k).id.get ) ) //actually get so that an exception is throw if there is no ID set
    }).toSeq
  }
  
  
  //actual tests
  "Event Logs" should {
    
    "be all found" in {          
      val logs = migration.findAllEventLogs.open_!
      
      logs.size must beEqualTo(logs10WithId.size) and
      forallWhen(logs) {
        case MigrationEventLog(id, eventType, data) => 
          val l = logs10WithId.values.find(x => x.id.get == id).get
          
          l.data must be_==/(data) and
          l.eventType === eventType
      }
    }
    
    "be correctly migrated" in {
      
      migration.processEventLogs
      
      val logs = jdbcTemplate.query("select * from eventlog", testLogRowMapper).asScala
      
      logs.size must beEqualTo(logs2WithId.size) and
      forallWhen(logs) {
        case TestLog(Some(id), eventType, timestamp, principal, cause, severity, data) => 
          val l = logs2WithId.find(x => x.id.get == id).get
          
          (l.eventType === eventType) and
          (l.timestamp === timestamp) and
          (l.principal === principal) and
          (l.cause === cause) and
          (l.severity == severity must beTrue) and
          (l.data must be_==/(data))
        
        case x => failure("Bad TestLog (no id): " + x)
      }
    }
    
  }
  
  
}
