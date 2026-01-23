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
 *
 *************************************************************************************
 */

package com.normation.rudder.score

import com.normation.errors.PureResult
import com.normation.inventory.domain.InventoryError.Inconsistency
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.SoftwareUpdateKind
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.SelectFacts
import com.normation.rudder.facts.nodes.SelectNodeStatus
import com.normation.rudder.score.ScoreValue.A
import com.normation.rudder.score.ScoreValue.B
import com.normation.rudder.score.ScoreValue.C
import com.normation.rudder.score.ScoreValue.D
import com.normation.rudder.score.ScoreValue.E
import com.normation.rudder.score.ScoreValue.F
import com.normation.rudder.tenants.QueryContext
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
            val securityMessage = "No security update."
            val updateMessage   = s"${sum} updates available (less than 50)."
            Score(scoreId, A, s"${securityMessage}\n${updateMessage}", stats)
          } else if (security < 5 && sum < 75) {
            val securityMessage = if (security < 5) s"${security} security updates available (less than 5)." else ""
            val updateMessage   = if (sum < 75) s"${sum} updates available (between 50 and 75)." else ""
            Score(scoreId, B, s"${securityMessage}\n${updateMessage}", stats)
          } else if (security < 20 && sum < 125) {
            val securityMessage = if (security < 20) s"${security} security updates available (between 5 and 20)." else ""
            val updateMessage   = if (sum < 125) s"${sum} updates available (between 75 and 125)." else ""
            Score(scoreId, C, s"${securityMessage}\n${updateMessage}", stats)
          } else if (security < 50 && sum < 175) {
            val securityMessage = if (security < 50) s"${security} security updates available (between 20 and 50)." else ""
            val updateMessage   = if (sum < 175) s"${sum} updates available (between 125 and 175)." else ""
            Score(scoreId, D, s"${securityMessage}\n${updateMessage}", stats)
          } else if (security < 80 && sum < 250) {
            val securityMessage = if (security < 80) s"${security} security updates available (between 50 and 80)." else ""
            val updateMessage   = if (sum < 250) s"${sum} updates available (between 175 and 250)." else ""
            Score(scoreId, E, s"${securityMessage}\n${updateMessage}", stats)
          } else {
            val securityMessage = if (security >= 80) s"${security} security updates available (more than 80)." else ""
            val updateMessage   = if (sum >= 250) s"${sum} updates available (More than 250)." else ""
            Score(scoreId, F, s"${securityMessage}\n${updateMessage}", stats)
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
      .slowGetAll()(using
        QueryContext.systemQC,
        SelectNodeStatus.Accepted,
        SelectFacts.none
      )
      .map(nf => SystemUpdateScoreEvent(nf.id, nf.softwareUpdate.toList))
      .runCollect
      .catchAll(err => ScoreLoggerPure.error(s"Error when initializing system update events: ${err.fullMsg}") *> Chunk().succeed)
  }

  override def initForScore(globalScore: GlobalScore): Boolean =
    globalScore.details.forall(_.scoreId != SystemUpdateScore.scoreId)
}
