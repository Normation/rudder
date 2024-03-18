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

import com.normation.errors.PureResult
import com.normation.inventory.domain.InventoryError.Inconsistency
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.reports.ComplianceSerializable
import com.normation.rudder.score.ScoreValue.A
import com.normation.rudder.score.ScoreValue.B
import com.normation.rudder.score.ScoreValue.C
import com.normation.rudder.score.ScoreValue.D
import com.normation.rudder.score.ScoreValue.E
import zio.*
import zio.json.*
import zio.syntax.*

object ComplianceScore {
  val scoreId = "compliance"
}

object ComplianceScoreEventHandler extends ScoreEventHandler {
  implicit val compliancePercentEncoder: JsonEncoder[ComplianceSerializable]     = DeriveJsonEncoder.gen
  def handle(event: ScoreEvent):         PureResult[List[(NodeId, List[Score])]] = {

    event match {
      case ComplianceScoreEvent(n, percent) =>
        (for {
          p <- ComplianceSerializable.fromPercent(percent).toJsonAST
        } yield {
          import ComplianceScore.scoreId
          val score = if (percent.compliance >= 100) {
            Score(scoreId, A, "Node is compliant at 100%", p)
          } else if (percent.compliance >= 75) {
            Score(scoreId, B, "Node is compliant at least at 75%", p)
          } else if (percent.compliance >= 50) {
            ComplianceSerializable.fromPercent(percent)
            Score(scoreId, C, "Node is compliant at least at 50%", p)
          } else if (percent.compliance >= 25) {
            Score(scoreId, D, "Node is compliant at least at 25%", p)
          } else {
            Score(scoreId, E, "Node is compliant at less then 25%", p)
          }
          ((n, score :: Nil) :: Nil)
        }) match {
          case Left(err) => Left(Inconsistency(err))
          case Right(r)  => Right(r)
        }
      case _                                => Right(Nil)
    }
  }

  override def initEvents: UIO[Chunk[ScoreEvent]] = Chunk.empty.succeed

  override def initForScore(globalScore: GlobalScore): Boolean = false
}
