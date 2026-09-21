/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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
import com.normation.rudder.MockNodes
import com.normation.rudder.tenants.QueryContext
import org.junit.runner.RunWith
import zio.*
import zio.syntax.*
import zio.test.*
import zio.test.junit.ZTestJUnitRunner

/*
 * Registering a score handler replays its init events, which for most handlers means one event
 * per node, each one writing a score row and a global score row.
 * That is pure waste once every node has the score so it is guarded by `needsScoreInit`.
 * Check that the guard works in both direction (at least one, no more than one).
 * See https://issues.rudder.io/issues/29781.
 */
@RunWith(classOf[ZTestJUnitRunner])
class ScoreInitGuardTest extends ZIOSpecDefault {

  val mockNodes = new MockNodes()

  val globalScoreRepo = new InMemoryGlobalScoreRepository()
  val scoreRepo       = new InMemoryScoreRepository()
  val scoreService    = new ScoreServiceImpl(globalScoreRepo, scoreRepo, mockNodes.nodeFactRepo)

  val handledScoreId = "handled-score"

  /*
   * A handler that considers a node initialized once it has a score with its own id.
   */
  object TestHandler extends ScoreEventHandler {
    override def handle(event: ScoreEvent): com.normation.errors.PureResult[List[(NodeId, List[Score])]] = Right(Nil)
    override def initEvents:                             UIO[Chunk[ScoreEvent]] = Chunk.empty.succeed
    override def initForScore(globalScore: GlobalScore): Boolean                = {
      globalScore.details.forall(_.scoreId != handledScoreId)
    }
  }

  def scoreOf(scoreIds: String*): GlobalScore = {
    GlobalScore(
      ScoreValue.A,
      "",
      scoreIds.toList.map(id => NoDetailsScore(id, ScoreValue.A, ""))
    )
  }

  def givenGlobalScores(scores: (NodeId, GlobalScore)*): IOResult[Unit] = {
    ZIO.foreachDiscard(scores) { case (id, score) => globalScoreRepo.save(id, score).unit } *>
    scoreService.initGlobalScores()
  }

  def spec: Spec[Any, Any] = {
    suite("Deciding whether a score handler needs to replay its init events")(
      test("it does when no node has any score at all") {
        for {
          needsInit <- scoreService.needsScoreInit(TestHandler)
        } yield assertTrue(needsInit)
      },
      test("it does when a node has scores but not the one of that handler") {
        for {
          _         <- scoreService.registerScore(handledScoreId, "Handled score")
          _         <- givenGlobalScores(MockNodes.rootId -> scoreOf("some-other-score"))
          needsInit <- scoreService.needsScoreInit(TestHandler)
          // showing the trap: through `getAll`, that node looks like it has the handled score
          viaGetAll <- scoreService.getAll()(using QueryContext.systemQC)
        } yield {
          assertTrue(needsInit) &&
          assertTrue(viaGetAll.get(MockNodes.rootId).exists(_.details.exists(_.scoreId == handledScoreId)))
        }
      },
      test("it does not when every node has the score of that handler") {
        for {
          nodeIds   <- mockNodes.nodeFactRepo.getAll()(using QueryContext.systemQC).map(_.keySet)
          _         <- givenGlobalScores(nodeIds.toSeq.map(id => (id, scoreOf(handledScoreId)))*)
          needsInit <- scoreService.needsScoreInit(TestHandler)
        } yield assertTrue(!needsInit)
      },
      test("it does again as soon as one single node loses it") {
        for {
          nodeIds   <- mockNodes.nodeFactRepo.getAll()(using QueryContext.systemQC).map(_.keySet)
          _         <- givenGlobalScores(nodeIds.toSeq.map(id => (id, scoreOf(handledScoreId)))*)
          // one node keeps a score, but not that one
          _         <- givenGlobalScores(nodeIds.head -> scoreOf("some-other-score"))
          needsInit <- scoreService.needsScoreInit(TestHandler)
        } yield assertTrue(needsInit)
      }
    ) @@ TestAspect.sequential
  }
}
