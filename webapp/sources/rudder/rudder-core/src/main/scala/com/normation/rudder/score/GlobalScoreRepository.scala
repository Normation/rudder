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

import com.normation.errors.IOResult
import com.normation.inventory.domain.NodeId
import com.normation.rudder.db.Doobie
import com.normation.zio.*
import doobie.implicits.*
import doobie.postgres.implicits.pgEnumString
import doobie.util.invariant.InvalidEnum
import zio.Ref
import zio.interop.catz.*

trait GlobalScoreRepository {
  def getAll(): IOResult[Map[NodeId, GlobalScore]]
  def get(id:      NodeId): IOResult[Option[GlobalScore]]
  def delete(id:   NodeId): IOResult[Unit]
  def save(nodeId: NodeId, globalScore: GlobalScore): IOResult[(NodeId, GlobalScore)]
}

/*
 * a repository that doesn't do anything, for tests
 */
class InMemoryGlobalScoreRepository extends GlobalScoreRepository {
  private val cache:     Ref[Map[NodeId, GlobalScore]]      = Ref.make(Map[NodeId, GlobalScore]()).runNow
  override def getAll(): IOResult[Map[NodeId, GlobalScore]] = cache.get
  override def get(id:    NodeId): IOResult[Option[GlobalScore]] = cache.get.map(_.get(id))
  override def delete(id: NodeId): IOResult[Unit]                = cache.update(_.removed(id))
  override def save(nodeId: NodeId, globalScore: GlobalScore): IOResult[(NodeId, GlobalScore)] =
    cache.update(_.updated(nodeId, globalScore)).map(_ => (nodeId, globalScore))
}

object GlobalScoreRepositoryImpl {

  import com.normation.rudder.db.json.implicits.*
  import com.normation.rudder.score.ScoreSerializer.*
  import doobie.*

  implicit val scoreMeta: Meta[ScoreValue] = {
    def getValue(value: String) = {
      ScoreValue.fromString(value) match {
        case Right(s)  => s
        case Left(err) => throw InvalidEnum[ScoreValue](value)
      }
    }

    pgEnumString("score", getValue, _.value)
  }

  implicit val stateWrite: Meta[List[NoDetailsScore]] = new Meta(pgDecoderGet, pgEncoderPut)

  implicit val globalScoreWrite: Write[(NodeId, GlobalScore)] = {
    Write[(String, ScoreValue, String, List[NoDetailsScore])].contramap {
      case (nodeId: NodeId, score: GlobalScore) =>
        (nodeId.value, score.value, score.message, score.details)
    }
  }

  implicit val globalScoreRead:       Read[GlobalScore]           = {
    Read[(ScoreValue, String, List[NoDetailsScore])].map { (d: (ScoreValue, String, List[NoDetailsScore])) =>
      GlobalScore(d._1, d._2, d._3)
    }
  }
  implicit val globalScoreWithIdRead: Read[(NodeId, GlobalScore)] = {
    Read[(String, ScoreValue, String, List[NoDetailsScore])].map { (d: (String, ScoreValue, String, List[NoDetailsScore])) =>
      (NodeId(d._1), GlobalScore(d._2, d._3, d._4))
    }
  }

}

class GlobalScoreRepositoryImpl(doobie: Doobie) extends GlobalScoreRepository {
  import GlobalScoreRepositoryImpl.*
  import doobie.*

  def save(nodeId: NodeId, globalScore: GlobalScore): IOResult[(NodeId, GlobalScore)] = {
    val query = {
      sql"""insert into GlobalScore (nodeId, score, message, details) values (${(nodeId, globalScore)})
           |  ON CONFLICT (nodeId) DO UPDATE
           |  SET score = ${globalScore.value}, message = ${globalScore.message}, details = ${globalScore.details} ; """.stripMargin
    }

    transactIOResult(s"error when inserting global score for node '${nodeId.value}''")(xa => query.update.run.transact(xa)).map(
      _ => (nodeId, globalScore)
    )
  }

  override def getAll(): IOResult[Map[NodeId, GlobalScore]] = {
    val q = sql"select nodeId, score, message, details from GlobalScore"
    transactIOResult(s"error when getting global scores for node")(xa => q.query[(NodeId, GlobalScore)].to[List].transact(xa))
      .map(_.groupMapReduce(_._1)(_._2) { case (_, res) => res })
  }

  override def get(id: NodeId): IOResult[Option[GlobalScore]] = {
    val q = sql"select score, message, details from GlobalScore where nodeId = ${id.value}"
    transactIOResult(s"error when getting global score for node ${id.value}")(xa => q.query[GlobalScore].option.transact(xa))
  }

  override def delete(id: NodeId): IOResult[Unit] = {
    val q = sql"delete from GlobalScore where nodeId = ${id.value}"
    transactIOResult(s"error when deleting global score for node ${id.value}")(xa => q.update.run.transact(xa).unit)
  }
}
