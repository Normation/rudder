package com.normation.rudder.score

import com.normation.errors.PureResult
import com.normation.inventory.domain.NodeId
import com.normation.rudder.MockNodes
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.score.ScoreValue.A
import com.normation.rudder.score.ScoreValue.B
import com.normation.rudder.score.ScoreValue.D
import com.normation.rudder.score.ScoreValue.E
import com.normation.rudder.score.ScoreValue.NoScore
import com.normation.zio.*
import org.specs2.mutable.Specification
import zio.Chunk
import zio.UIO
import zio.json.ast.Json.Str
import zio.syntax.*

class ScoreServiceTest extends Specification {

  val mock    = new MockNodes()
  import MockNodes.*
  import mock.*
  val scoreId = "testScore"
  case class TestScoreEvent(nodeId: NodeId) extends ScoreEvent

  object TestScoreEventHandler extends ScoreEventHandler {
    def handle(event: ScoreEvent): PureResult[List[(NodeId, List[Score])]] = {

      Right(event match {
        case TestScoreEvent(r @ MockNodes.rootId) =>
          (r, Score(scoreId, A, "Root is A !", Str(r.value)) :: Nil) :: Nil
        case TestScoreEvent(r @ MockNodes.id1)    =>
          (r, Score(scoreId, B, "node 1 is B !", Str(r.value)) :: Nil) :: Nil
        case TestScoreEvent(r @ MockNodes.id2)    =>
          (r, Score(scoreId, D, "node 2 is D !", Str(r.value)) :: Nil) :: Nil
        case TestScoreEvent(r @ dscNode1.id)      =>
          (r, Score(scoreId, E, "dsc node is E !", Str(r.value)) :: Nil) :: Nil
        case TestScoreEvent(r)                    =>
          (r, Score(scoreId, NoScore, s"${r.value} node is No score !", Str(r.value)) :: Nil) :: Nil
        case _                                    => Nil
      })
    }
    def initEvents: UIO[Chunk[ScoreEvent]] = Chunk.empty.succeed
    def initForScore(globalScore: GlobalScore): Boolean = false
  }

  scoreManager.registerHandler(TestScoreEventHandler).runNow

  scoreManager.handleEvent(TestScoreEvent(rootId)).runNow
  scoreManager.handleEvent(TestScoreEvent(id2)).runNow

  "Score service" should {

    "Should contain score for initialised nodes" in {
      val scores = scoreService.getAll()(using QueryContext.testQC).runNow
      scores.map(c => (c._1, c._2.value)) must havePairs(rootId -> A, id2 -> D)
    }
    "Should not contain score of non existing nodes (maybe pending nodes ?)" in {
      scoreManager.handleEvent(TestScoreEvent(NodeId("non-existing-node"))).runNow
      val scores = scoreService.getAll()(using QueryContext.testQC).runNow
      scores.map(c => (c._1, c._2.value)) must havePairs(rootId -> A, id2 -> D)
    }
    "Should not contain score of non existing nodes (maybe pending nodes ?)" in {
      val scores = scoreRepo.getAll().runNow
      println(scores)
      scores.map(c => (c._1, c._2.filter(_.scoreId == scoreId).headOption.map(_.value))) must havePairs(
        rootId -> Some(A),
        id2    -> Some(D)
      )
    }
  }
}
