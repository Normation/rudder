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

import com.normation.NamedZioLogger
import com.normation.errors.PureResult
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.SoftwareUpdate
import com.normation.rudder.domain.reports.CompliancePercent
import zio.*
import zio.json.*
import zio.json.ast.Json

sealed trait ScoreValue {
  def value: String
}

object ScoreValue {
  case object A       extends ScoreValue { val value = "A" }
  case object B       extends ScoreValue { val value = "B" }
  case object C       extends ScoreValue { val value = "C" }
  case object D       extends ScoreValue { val value = "D" }
  case object E       extends ScoreValue { val value = "E" }
  case object NoScore extends ScoreValue { val value = "X" }

  val allValues: Set[ScoreValue] = ca.mrvisser.sealerate.values

  def fromString(s: String): Either[String, ScoreValue] = allValues.find(_.value == s.toUpperCase()) match {
    case None    => Left(s"${s} is not valid status value, accepted values are ${allValues.map(_.value).mkString(", ")}")
    case Some(v) => Right(v)
  }
}

case class NoDetailsScore(scoreId: String, value: ScoreValue, message: String)
case class Score(scoreId: String, value: ScoreValue, message: String, details: Json)

case class GlobalScore(value: ScoreValue, message: String, details: List[NoDetailsScore])

object GlobalScoreService {
  def computeGlobalScore(oldScore: List[NoDetailsScore], scores: List[Score]): GlobalScore = {

    val correctScores = scores.foldRight(oldScore) {
      case (newScore, acc) =>
        NoDetailsScore(newScore.scoreId, newScore.value, newScore.message) :: acc.filterNot(_.scoreId == newScore.scoreId)
    }
    import ScoreValue.*
    val score         = if (correctScores.exists(_.value == E)) { E }
    else if (correctScores.exists(_.value == D)) { D }
    else if (correctScores.exists(_.value == C)) {
      C
    } else if (correctScores.exists(_.value == B)) {
      B
    } else A
    GlobalScore(score, s"There is at least a Score with ${score.value}", correctScores)
  }
}

trait ScoreEvent

case class SystemUpdateScoreEvent(nodeId: NodeId, updates: List[SoftwareUpdate])      extends ScoreEvent
case class ComplianceScoreEvent(nodeId: NodeId, compliancePercent: CompliancePercent) extends ScoreEvent

trait ScoreEventHandler {
  def handle(event: ScoreEvent): PureResult[List[(NodeId, List[Score])]]

  /*
   * Init events must always succeed. It is done early in Rudder bootstrap sequence and
   * would lead to unknown state if it fails.
   */
  def initEvents: UIO[Chunk[ScoreEvent]]
  def initForScore(globalScore: GlobalScore): Boolean
}

object ScoreSerializer {
  implicit val scoreValueEncoder: JsonEncoder[ScoreValue] = JsonEncoder[String].contramap(_.value)
  implicit val scoreValueDecoder: JsonDecoder[ScoreValue] = JsonDecoder[String].mapOrFail(ScoreValue.fromString)

  implicit val noDetailsScoreEncoder: JsonEncoder[NoDetailsScore] = DeriveJsonEncoder.gen
  implicit val noDetailsScoreDecoder: JsonDecoder[NoDetailsScore] = DeriveJsonDecoder.gen

  implicit val globalScoreEncoder: JsonEncoder[GlobalScore] = DeriveJsonEncoder.gen
  implicit val globalScoreDecoder: JsonDecoder[GlobalScore] = DeriveJsonDecoder.gen

  implicit val jsonScoreEncoder: JsonEncoder[Score] = DeriveJsonEncoder.gen
  implicit val jsonScoreDecoder: JsonDecoder[Score] = DeriveJsonDecoder.gen
}

object ScoreLoggerPure extends NamedZioLogger {
  override def loggerName: String = "score"
}
