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
import com.normation.rudder.domain.logger._

import net.liftweb.common._
import net.liftweb.util.Helpers._
import net.liftweb.util.IterableFunc.itNodeSeq
import net.liftweb.util.StringPromotable.intToStrPromo

/*
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
*/
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
        orderBy(line.id.desc)
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


object TestIsElem {

  private[this] def failBadElemType(xml:NodeSeq) = {
    Failure("Not expected type of NodeSeq (wish it was an Elem): " + xml)
  }

  def apply(xml:NodeSeq) : Box[Elem] = {
    xml match {
      case seq if(seq.size == 1) => seq.head match {
        case e:Elem => Full(e)
        case x => failBadElemType(x)
      }
      case x => failBadElemType(x)
    }
  }
}


//test that the node is an entry and that it has EXACTLY one child
//do not use to test empty entry
//return the child
object TestIsEntry {
  def apply(xml:Elem) : Box[Elem] = {
    val trimed = XmlUtils.trim(xml)
    if(trimed.label.toLowerCase == "entry" && trimed.child.size == 1) TestIsElem(trimed.child.head)
    else Failure("Given XML data has not an 'entry' root element and exactly one child: " + trimed)
  }
}

/**
 * Change labels of a list of Elem
 */
case class ChangeLabel(label:String) extends Function1[NodeSeq, Option[Elem]] {
   def logger = MigrationLogger(2)

  override def apply(nodes:NodeSeq) = nodes match {
    case e:Elem => Some(e.copy(label = label))
    case x => //ignore other type of nodes
      logger.debug("Can not change the label to '%s' of a NodeSeq other than elem in a CssSel: '%s'".format(label, x))
      None
  }
}
/**
 * Change labels of a list of Elem
 */
case class EncapsulateChild(label:String) extends Function1[NodeSeq, Option[NodeSeq]] {
 def logger = MigrationLogger(2)

  override def apply(nodes:NodeSeq) = nodes match {
    case e:Elem => Some(e.copy(child = Encapsulate(label).apply(e.child).getOrElse(NodeSeq.Empty)))
    case x => //ignore other type of nodes
      logger.debug("Can not change the label to '%s' of a NodeSeq other than elem in a CssSel: '%s'".format(label, x))
      None
  }
}
/**
 * Change labels of a list of Elem
 */
case class Encapsulate(label:String) extends Function1[NodeSeq, Option[NodeSeq]] {
   def logger = MigrationLogger(2)

  override def apply(nodes:NodeSeq) = nodes match {
    case e:Elem => Some(e.copy(label=label,child=e))
    case nodeseq:NodeSeq if (nodeseq.size == 1) => Some(<test>{nodeseq.head}</test>.copy(label = label) )
    case nodeseq:NodeSeq if (nodeseq == NodeSeq.Empty) => Some(nodeseq)
    case x => //ignore other type of nodes
      logger.debug("Can not change the label to '%s' of a NodeSeq other than elem in a CssSel: '%s'".format(label, x))
      None
  }
}
