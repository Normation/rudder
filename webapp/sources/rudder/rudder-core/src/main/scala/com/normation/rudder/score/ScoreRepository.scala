/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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

package com.normation.rudder.score

import com.normation.errors.*
import com.normation.inventory.domain.NodeId
import com.normation.rudder.db.Doobie
import com.normation.rudder.db.Doobie.*
import com.normation.zio.*
import doobie.Fragments
import doobie.Meta
import doobie.Read
import doobie.Write
import doobie.implicits.*
import doobie.postgres.implicits.pgEnumString
import doobie.util.invariant.InvalidEnum
import doobie.util.update.Update
import zio.Ref
import zio.interop.catz.*
import zio.json.ast.Json

trait ScoreRepository {

  def getAll(): IOResult[Map[NodeId, List[Score]]]
  def getAllOneScore(scoreId: String): IOResult[Map[NodeId, Score]]
  def getScore(nodeId:        NodeId, scoreId:      Option[String]): IOResult[List[Score]]
  def getOneScore(nodeId:     NodeId, scoreId:      String):         IOResult[Score]
  def saveScore(nodeId:       NodeId, score:        Score):          IOResult[Unit]
  def deleteScore(nodeId:     Seq[NodeId], scoreId: Option[String]): IOResult[Unit]

}

/*
 * A score repository that does nothing, for tests
 */
class InMemoryScoreRepository extends ScoreRepository {
  private[this] val cache:                                                Ref[Map[NodeId, List[Score]]]      = Ref.make(Map[NodeId, List[Score]]()).runNow
  override def getAll():                                                  IOResult[Map[NodeId, List[Score]]] = cache.get
  override def getAllOneScore(scoreId: String):                           IOResult[Map[NodeId, Score]]       = {
    for {
      c <- cache.get
    } yield {
      c.flatMap { case (id, scores) => scores.find(_.scoreId == scoreId).map((id, _)) }
    }
  }
  override def getScore(nodeId: NodeId, scoreId: Option[String]):         IOResult[List[Score]]              = cache.get.map { c =>
    val nodeScore = c.get(nodeId).getOrElse(Nil)
    scoreId match {
      case None     => nodeScore
      case Some(id) => nodeScore.filter(_.scoreId == id)
    }
  }
  override def getOneScore(nodeId: NodeId, scoreId: String):              IOResult[Score]                    =
    getScore(nodeId, Some(scoreId)).flatMap(_.headOption.notOptional(""))
  override def saveScore(nodeId: NodeId, score: Score):                   IOResult[Unit]                     = cache.update(c => {
    c.updatedWith(nodeId) {
      case None         => Some(score :: Nil)
      case Some(values) => Some(score :: values.filterNot(_.scoreId == score.scoreId))
    }
  })
  override def deleteScore(nodeId: Seq[NodeId], scoreId: Option[String]): IOResult[Unit]                     = cache.update(c => {
    nodeId.foldLeft(c) {
      case (acc, n) =>
        acc.updatedWith(n) {
          case None         => None
          case Some(values) =>
            scoreId match {
              case None          => None
              case Some(scoreId) => Some(values.filterNot(_.scoreId == scoreId))
            }
        }
    }
  })
}

class ScoreRepositoryImpl(doobie: Doobie) extends ScoreRepository {

  import com.normation.rudder.db.json.implicits.*

  implicit val scoreMeta: Meta[ScoreValue] = {
    def getValue(value: String) = {
      ScoreValue.fromString(value) match {
        case Right(s)  => s
        case Left(err) => throw InvalidEnum[ScoreValue](value)
      }
    }
    pgEnumString("score", getValue, _.value)
  }

  // implicit val stateWrite: Meta[Score] = new Meta(pgDecoderGet, pgEncoderPut)

  implicit val scoreWrite: Write[(NodeId, Score)] = {
    Write[(String, String, ScoreValue, String, Json)].contramap {
      case (nodeId: NodeId, score: Score) =>
        (nodeId.value, score.scoreId, score.value, score.message, score.details)
    }
  }

  implicit val scoreRead:       Read[Score]           = {
    Read[(String, ScoreValue, String, Json)].map((d: (String, ScoreValue, String, Json)) => Score(d._1, d._2, d._3, d._4))
  }
  implicit val scoreWithIdRead: Read[(NodeId, Score)] = {
    Read[(String, String, ScoreValue, String, Json)].map { (d: (String, String, ScoreValue, String, Json)) =>
      (NodeId(d._1), Score(d._2, d._3, d._4, d._5))
    }
  }

  import doobie.*
  override def getAll(): IOResult[Map[NodeId, List[Score]]] = {
    val q = sql"select nodeId, scoreId, score, message, details from scoredetails "
    transactIOResult(s"error when getting scores for node")(xa => q.query[(NodeId, Score)].to[List].transact(xa))
      .map(_.groupMap(_._1)(_._2))
  }

  override def getAllOneScore(scoreId: String): IOResult[Map[NodeId, Score]] = {

    val whereName = fr"scoreId = ${scoreId}"
    val where     = Fragments.whereAnd(whereName)
    val q         = sql"select nodeid, scoreId, score, message, details from scoredetails " ++ where
    transactIOResult(s"error when getting one score for all nodes")(xa => q.query[(NodeId, Score)].toMap.transact(xa))
  }

  override def getScore(nodeId: NodeId, scoreId: Option[String]): IOResult[List[Score]] = {

    val whereNode = Some(fr"nodeId = ${nodeId.value}")
    val whereName = scoreId.map(n => fr"scoreId = ${n}")
    val where     = Fragments.whereAndOpt(whereNode, whereName)
    val q         = sql"select scoreId, score, message, details from scoredetails " ++ where
    transactIOResult(s"error when getting scores for node ${nodeId} ")(xa => q.query[Score].to[List].transact(xa))
  }

  override def getOneScore(nodeId: NodeId, scoreId: String): IOResult[Score] = {
    val whereNode = fr"nodeId = ${nodeId.value}"
    val whereName = fr"scoreId = ${scoreId}"
    val where     = Fragments.whereAnd(whereNode, whereName)
    val q         = sql"select scoreId, score, message, details from scoredetails " ++ where
    transactIOResult(s"error when getting scores for node")(xa => q.query[Score].unique.transact(xa))
  }

  override def saveScore(nodeId: NodeId, score: Score): IOResult[Unit] = {

    val query = {
      sql"""insert into scoredetails (nodeId, scoreId, score, message, details) values (${(nodeId, score)})
           |  ON CONFLICT (nodeId, scoreId) DO UPDATE
           |  SET score = ${score.value}, message = ${score.message}, details = ${score.details} ; """.stripMargin
    }

    transactIOResult(s"error when inserting global score for node '${nodeId.value}''")(xa => query.update.run.transact(xa)).unit

  }

  override def deleteScore(nodeId: Seq[NodeId], scoreId: Option[String]): IOResult[Unit] = {
    val whereNode = Some(fr"nodeId = ?")
    val whereName = scoreId.map(n => fr"scoreId = ?")
    val where     = Fragments.whereAndOpt(whereNode, whereName)
    val query     = (sql"delete from scoredetails " ++ where).internals.sql
    // query is different wether we have a score id or not
    scoreId match {
      case Some(value) =>
        // A score id is defined, query will be
        // delete from scoreDetails where nodeid = ? and scoreId = ?
        val q = Update[(NodeId, String)](query)
        transactIOResult(s"error when deleting score '${value}' for nodes ${nodeId.map(_.value).mkString(",")}")(xa =>
          q.updateMany(nodeId.map((_, value))).transact(xa).unit
        )
      case None        =>
        // A score id is not defined, query will be
        // delete from scoreDetails where nodeid = ?
        val q = Update[(NodeId)](query)
        transactIOResult(s"error when deleting all score for nodes ${nodeId.map(_.value).mkString(",")}")(xa =>
          q.updateMany(nodeId).transact(xa).unit
        )
    }
  }
}
