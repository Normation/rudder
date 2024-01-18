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
import com.normation.inventory.domain.SoftwareUpdateKind
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.facts.nodes.SelectFacts
import com.normation.rudder.facts.nodes.SelectNodeStatus
import com.normation.rudder.score.ScoreValue.A
import com.normation.rudder.score.ScoreValue.B
import com.normation.rudder.score.ScoreValue.C
import com.normation.rudder.score.ScoreValue.D
import com.normation.rudder.score.ScoreValue.E
import zio.*
import zio.json.*
import zio.syntax.*

object SystemUpdateScore {
  val scoreId = "system-updates"
}

case class SystemUpdateStats(
    nbPackages:  Int,
    security:    Option[Int],
    updates:     Option[Int],
    defect:      Option[Int],
    enhancement: Option[Int],
    other:       Option[Int]
)

class SystemUpdateScoreHandler(nodeFactRepository: NodeFactRepository) extends ScoreEventHandler {

  def handle(event: ScoreEvent): PureResult[List[(NodeId, List[Score])]] = {
    implicit val compliancePercentEncoder: JsonEncoder[SystemUpdateStats] = DeriveJsonEncoder.gen
    event match {
      case SystemUpdateScoreEvent(n, softwareUpdates) =>
        val sum         = softwareUpdates.size
        val security    = softwareUpdates.count(_.kind == SoftwareUpdateKind.Security)
        val patch       = softwareUpdates.count(_.kind == SoftwareUpdateKind.None)
        val defect      = softwareUpdates.count(_.kind == SoftwareUpdateKind.Defect)
        val enhancement = softwareUpdates.count(_.kind == SoftwareUpdateKind.Enhancement)
        val other       = softwareUpdates.count { s =>
          s.kind match {
            case SoftwareUpdateKind.Other(_) => true
            case _                           => false
          }
        }
        val s           = SystemUpdateStats(
          sum,
          if (security > 0) Some(security) else None,
          if (patch > 0) Some(patch) else None,
          if (defect > 0) Some(defect) else None,
          if (enhancement > 0) Some(enhancement) else None,
          if (other > 0) Some(other) else None
        )
        (for {
          stats <- s.toJsonAST
        } yield {
          import SystemUpdateScore.scoreId
          val score = if (security == 0 && sum < 50) {
            Score(scoreId, A, "Node has no security updates and less than 50 updates available", stats)
          } else if (security < 5) {
            Score(scoreId, B, s"Node has ${security} security updates available (less than 5)", stats)
          } else if (security < 20) {
            Score(scoreId, C, s"Node has ${security} security updates available (less than 20)", stats)
          } else if (security < 50) {
            Score(scoreId, D, s"Node has ${security} security updates available (less than 50)", stats)
          } else {
            Score(scoreId, E, s"Node has ${security} security updates available (more than 50)", stats)
          }
          ((n, score :: Nil) :: Nil)
        }) match {
          case Left(err) => Left(Inconsistency(err))
          case Right(r)  => Right(r)
        }
      case _                                          => Right(Nil)
    }
  }

  override def initEvents: UIO[Chunk[ScoreEvent]] = {
    nodeFactRepository
      .slowGetAll()(
        QueryContext.systemQC,
        SelectNodeStatus.Accepted,
        SelectFacts.none.copy(softwareUpdate = SelectFacts.none.softwareUpdate.toRetrieve)
      )
      .map(nf => SystemUpdateScoreEvent(nf.id, nf.softwareUpdate.toList))
      .runCollect
      .catchAll(err => ScoreLoggerPure.error(s"Error when initializing system update events: ${err.fullMsg}") *> Chunk().succeed)
  }

  override def initForScore(globalScore: GlobalScore): Boolean =
    globalScore.details.forall(_.scoreId != SystemUpdateScore.scoreId)
}
