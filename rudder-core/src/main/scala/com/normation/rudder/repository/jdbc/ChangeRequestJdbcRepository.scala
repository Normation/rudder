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

package com.normation.rudder.repository.jdbc

import net.liftweb.common.Loggable
import com.normation.rudder.repository.RoChangeRequestRepository
import org.joda.time.DateTime
import net.liftweb.common._
import java.sql.ResultSet
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import org.springframework.jdbc.core.JdbcTemplate
import com.normation.rudder.domain.workflows.ChangeRequest
import org.springframework.jdbc.core.RowMapper
import com.normation.rudder.domain.workflows.ConfigurationChangeRequest
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.domain.workflows.ChangeRequestInfo
import scala.util.{Try, Failure => Catch, Success}
import scala.collection.JavaConversions._
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.repository.WoChangeRequestRepository
import com.normation.rudder.services.marshalling.ChangeRequestChangesSerialisation
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.jdbc.core.PreparedStatementCreator
import com.normation.eventlog.EventActor
import com.normation.rudder.services.marshalling.ChangeRequestChangesUnserialisation
import scala.xml.XML
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.workflows.DirectiveChanges
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.eventlog.ModificationId

class RoChangeRequestJdbcRepository(
    jdbcTemplate         : JdbcTemplate
  , changeRequestsMapper : ChangeRequestsMapper
) extends RoChangeRequestRepository with Loggable {

  val SELECT_SQL = "SELECT id, name, description, creationTime, content, modificationId FROM ChangeRequest"

  val SELECT_SQL_JOIN_WORKFLOW = "SELECT CR.id, name, description, creationTime, content, modificationId FROM  changeRequest CR LEFT JOIN workflow W on CR.id = W.id"
  def getAll() : Box[Seq[ChangeRequest]] = {
    Try {
      jdbcTemplate.query(SELECT_SQL, Array[AnyRef](), changeRequestsMapper).toSeq
    } match {
      case Success(x) =>
        Full(x.:\(Seq[ChangeRequest]()){(changeRequest,seq) => changeRequest match {
        case Full(cr) =>  seq :+ cr
        case eb:EmptyBox =>
          seq
        }
      } )

      case Catch(error) =>
      Failure(error.toString())
    }
  }

  def get(changeRequestId:ChangeRequestId) : Box[Option[ChangeRequest]] = {
    Try {
      jdbcTemplate.query(
          SELECT_SQL + " where id = ?"
        , Array[AnyRef](changeRequestId.value.asInstanceOf[AnyRef])
        , changeRequestsMapper).toSeq
    } match {
      case Success(x) => x match {
        case Seq() => Full(None)
        case Seq(res) => Full(res.headOption)
        case _ => Failure(s"Too many change request have the same id ${changeRequestId.value}")
      }
      case Catch(error) => Failure(error.toString())
    }
  }

  // Get every change request where a user add a change
  def getByContributor(actor:EventActor) : Box[Seq[ChangeRequest]] = {
    Try {

      jdbcTemplate.query(
        new PreparedStatementCreator() {
           def createPreparedStatement(connection : Connection) : PreparedStatement = {

             val query= s"${SELECT_SQL} where cast( xpath('//firstChange/change/actor/text()',content) as text[]) = ?"
             val ps = connection.prepareStatement(
                 query, Array[String]());
             ps.setArray(1, connection.createArrayOf("text", Seq(actor.name).toArray[AnyRef]) )

             // if with have eventtype filter, apply them
             ps
           }
         }, changeRequestsMapper)
    } match {
      case Success(x) =>
        Full(x.:\(Seq[ChangeRequest]()){(changeRequest,seq) => changeRequest match {
        case Full(cr) =>  seq :+ cr
        case eb:EmptyBox =>
          seq
        }
      } )
      case Catch(x) => ApplicationLogger.error(s"could not fetch change request for user ${actor}: ${x}")
      Failure(s"could not fetch change request for user ${actor}")
    }
  }
  def getByIds(changeRequestId:Seq[ChangeRequestId]) : Box[Seq[ChangeRequest]] = {
    val parameters = new MapSqlParameterSource();
    parameters.addValue("ids", changeRequestId.map(x => x.value))

    Try {
      jdbcTemplate.query(
          SELECT_SQL + " where id in (:ids)"
        , changeRequestsMapper
        , parameters).toSeq
    } match {
      case Success(x) => Full(x.:\(Seq[ChangeRequest]()){(changeRequest,seq) => changeRequest match {
        case Full(cr) =>  seq :+ cr
        case eb:EmptyBox =>
          seq
        }
      })
      case Catch(error) => Failure(error.toString())
    }
  }

  def getByDirective(id : DirectiveId, onlyPending:Boolean) : Box[Seq[ChangeRequest]] = {
    getChangeRequestsByXpathContent(
        "/changeRequest/directives/directive/@id"
      , id.value
      , s"could not fetch change request for directive with id ${id.value}"
      , onlyPending
    )
  }

  def getByNodeGroup(id : NodeGroupId, onlyPending:Boolean) : Box[Seq[ChangeRequest]] = {
    getChangeRequestsByXpathContent(
        "/changeRequest/groups/group/@id"
      , id.value
      , s"could not fetch change request for group with id ${id.value}"
      , onlyPending
    )
  }

  def getByRule(id : RuleId, onlyPending:Boolean) : Box[Seq[ChangeRequest]] = {
    getChangeRequestsByXpathContent(
        "/changeRequest/rules/rule/@id"
      , id.value
      , s"could not fetch change request for rule with id ${id.value}"
      , onlyPending
    )
  }

  /**
   * Retrieve a sequence of change request based on one XML
   * element value.
   * The xpath query must match only one element.
   * We want to be able to find only pending change request without having to request the state of each change request
   * Maybe this function should be in Workflow repository/service instead
   */
  private[this] def getChangeRequestsByXpathContent(xpath:String, shouldEquals:String, errorMessage:String, onlyPending:Boolean) = {
    try {
      Full(jdbcTemplate.query(
        new PreparedStatementCreator() {
           def createPreparedStatement(connection : Connection) : PreparedStatement = {
             val query =
               if (onlyPending) {
                 s"${SELECT_SQL_JOIN_WORKFLOW} where cast( xpath('${xpath}', content) as text[]) = ? and state like 'Pending%'"
               }
               else {
                 s"${SELECT_SQL} where cast( xpath('${xpath}', content) as text[]) = ?"
               }

             val ps = connection.prepareStatement(query, Array[String]());
             ps.setArray(1, connection.createArrayOf("text", Seq(shouldEquals).toArray[AnyRef]) )
             ps
           }
         }, changeRequestsMapper
      ).flatten)
    } catch {
      case ex: Exception =>
        val f = Failure(errorMessage, Full(ex), Empty)
        ApplicationLogger.error(f.messageChain)
        ApplicationLogger.error("Root exception was:", ex)
        f
    }
  }

}

class WoChangeRequestJdbcRepository(
    jdbcTemplate : JdbcTemplate
  , crSerialiser : ChangeRequestChangesSerialisation
  , roRepo       : RoChangeRequestRepository
) extends WoChangeRequestRepository with Loggable {

  val INSERT_SQL = "insert into ChangeRequest (name, description, creationTime, content, modificationId) values (?, ?, ?, ?, ?)"

  val UPDATE_SQL = "update ChangeRequest set name = ?, description = ?, content = ? , modificationId = ? where id = ?"

  /**
   * Save a new change request in the back-end.
   * The id is ignored, and a new one will be attributed
   * to the change request.
   */
  def createChangeRequest(changeRequest:ChangeRequest, actor:EventActor, reason: Option[String]) : Box[ChangeRequest] = {
    val keyHolder = new GeneratedKeyHolder()

    Try {
      jdbcTemplate.update(
        new PreparedStatementCreator() {
           def createPreparedStatement(connection : Connection) : PreparedStatement = {
             val sqlXml = connection.createSQLXML()
             sqlXml.setString(crSerialiser.serialise(changeRequest).toString)

             val ps = connection.prepareStatement(
                 INSERT_SQL, Seq[String]("id").toArray[String]);

             ps.setString(1, changeRequest.info.name)
             ps.setString(2, changeRequest.info.description)
             ps.setTimestamp(3, new Timestamp(DateTime.now().getMillis()))
             ps.setSQLXML(4, sqlXml) // have a look at the SQLXML
             ps.setString(5, changeRequest.modId.map(_.value).getOrElse(""))
             ps
           }
         },
         keyHolder)
         roRepo.get(ChangeRequestId(keyHolder.getKey().intValue))
    } match {
      case Success(x) => x match {
        case Full(Some(entry)) =>
              logger.debug(s"Created change Request with id ${entry.id.value}")
              Full(entry)
        case Full(None) => Failure("Couldn't find newly created entry when saving Change Request")
        case e : Failure =>
              logger.error(s"Error when creating change request: ${e.msg}")
              e
        case Empty =>
              logger.error(s"Error when creating a change request: no reason given")
              Empty
      }
      case Catch(error) => Failure(error.toString())
    }
  }

  /**
   * Delete a change request.
   * (whatever the read/write mode is).
   */
  def deleteChangeRequest(changeRequestId:ChangeRequestId, actor:EventActor, reason: Option[String]) : Box[ChangeRequest] = {
    // we should update it rather, isn't it ?
    ???
  }

  /**
   * Update a change request. The change request must exists.
   */
  def updateChangeRequest(changeRequest:ChangeRequest, actor:EventActor, reason: Option[String]) : Box[ChangeRequest] = {
    // I will need a transaction if I need to change the status http://static.springsource.org/spring/docs/3.0.x/spring-framework-reference/html/transaction.html#transaction-programmatic
    Try {
      roRepo.get(changeRequest.id) match {
        case Full(None) =>
          logger.warn(s"Cannot update non-existant Change Request with id ${changeRequest.id.value}")
          Failure(s"Cannot update non-existant Change Request with id ${changeRequest.id.value}")
        case eb : EmptyBox => eb
        case Full(Some(entry)) => // ok
          // we don't change the creation date !
          jdbcTemplate.update(
              new PreparedStatementCreator() {
                 def createPreparedStatement(connection : Connection) : PreparedStatement = {
                   val sqlXml = connection.createSQLXML()
                   sqlXml.setString(crSerialiser.serialise(changeRequest).toString)

                   val ps = connection.prepareStatement(
                       UPDATE_SQL, Seq[String]("id").toArray[String]);

                   ps.setString(1, changeRequest.info.name)
                   ps.setString(2, changeRequest.info.description)
                   ps.setSQLXML(3, sqlXml)
                   ps.setString(4, changeRequest.modId.map(_.value).getOrElse(""))
                   ps.setInt(5, new java.lang.Integer(changeRequest.id.value))
                   ps
                 }
               }
           )
         roRepo.get(changeRequest.id)
      }
    } match {
        case Success(x) => x match {
            case Full(Some(entry)) => Full(entry)
            case Full(None) =>
              logger.error(s"Couldn't find the updated entry when updating Change Request ${changeRequest.id.value}")
              Failure("Couldn't find the updated entry when saving Change Request")
            case e : Failure =>
              logger.error(s"Error when updating change request ${changeRequest.id.value}: ${e.msg}")
              e
            case Empty =>
              logger.error(s"Error when updating change request ${changeRequest.id.value}: no reason given")
              Empty
          }
        case Catch(error) =>
          logger.error(s"Error when creating a Change Request : ${error.toString}")
          Failure(error.toString())
    }
  }
}



class ChangeRequestsMapper(
    changeRequestChangesUnserialisation : ChangeRequestChangesUnserialisation
) extends RowMapper[Box[ChangeRequest]] with Loggable {
  def mapRow(rs : ResultSet, rowNum: Int) : Box[ChangeRequest] = {

    // unserialize the XML.
    // If it fails, produce a failure
    // directives map is boxed because some Exception could be launched
    changeRequestChangesUnserialisation.unserialise(XML.load(rs.getSQLXML("content").getBinaryStream() )) match {
      case Full((directivesMaps, nodesMaps, ruleMaps)) =>
        val id = ChangeRequestId(rs.getInt("id"))
        val modId = {
          val modId = rs.getString("modificationId")
          if (modId != null && modId.size > 0)
            Some(ModificationId(modId))
          else
            None
        }
        directivesMaps match {
          case Full(map) => Full(ConfigurationChangeRequest(
            id
          , modId
          , ChangeRequestInfo(
                rs.getString("name")
              , rs.getString("description")
            )
          , map
          , nodesMaps
          , ruleMaps
        ) )
          case eb:EmptyBox => val fail = eb ?~! s"could not deserialize directive change of change request #${id} cause is: ${eb}"
          ApplicationLogger.error(fail)
          fail
        }

      case eb:EmptyBox => val fail = eb ?~! s"Error when trying to get the content of the change request ${rs.getInt("id")} : ${eb}"
        logger.error(fail.msg)
        fail
    }
  }

}