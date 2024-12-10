package com.normation.rudder.score

import com.normation.errors.PureResult
import com.normation.inventory.domain.NodeId
import com.normation.rudder.MockNodes
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.score.ScoreValue.{A, B, D, E, NoScore}
import com.normation.zio.*
import org.specs2.mutable.Specification
import zio.json.ast.Json.Str
import zio.syntax.*
import zio.{Chunk, UIO}

class ScoreServiceTest extends Specification{

  val mock = new MockNodes()
  import mock.*
  import MockNodes.*
  val scoreId = "testScore"
  case class TestScoreEvent(nodeId: NodeId) extends ScoreEvent

  object TestScoreEventHandler extends ScoreEventHandler {
    def handle(event: ScoreEvent): PureResult[List[(NodeId, List[Score])]] = {

      Right(event match {
        case TestScoreEvent(r @ MockNodes.rootId) =>
          (r, Score(scoreId, A, "Root is A !", Str(r.value)) :: Nil) :: Nil
          case TestScoreEvent(r @ MockNodes.id1) =>
            (r, Score(scoreId, B, "node 1 is B !", Str(r.value)) :: Nil) :: Nil
            case TestScoreEvent(r @ MockNodes.id2) =>
            (r, Score(scoreId, D, "node 2 is D !", Str(r.value)) :: Nil) :: Nil
            case TestScoreEvent(r @ dscNode1.id) =>
            (r, Score(scoreId, E, "dsc node is E !", Str(r.value)) :: Nil) :: Nil
        case _                                => Nil
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
      scoreService.getAll()(QueryContext.testQC).runNow === Map( rootId -> GlobalScore(A,"Worst score for this node is A",List(NoDetailsScore(scoreId,A,"Root is A !"), NoDetailsScore("compliance",NoScore,""), NoDetailsScore("system-updates",NoScore,""))), id2 -> GlobalScore(D,"Worst score for this node is D",List(NoDetailsScore(scoreId,D,"node 2 is D !"), NoDetailsScore("compliance",NoScore,""),NoDetailsScore("system-updates",NoScore,""))))
    }
    "Should not contain score of non existing nodes" in {

      scoreManager.handleEvent(TestScoreEvent(NodeId("non-existing-node"))).runNow
      scoreService.getAll()(QueryContext.testQC).runNow === Map( rootId -> GlobalScore(A,"Worst score for this node is A",List(NoDetailsScore(scoreId,A,"Root is A !"), NoDetailsScore("compliance",NoScore,""), NoDetailsScore("system-updates",NoScore,""))), id2 -> GlobalScore(D,"Worst score for this node is D",List(NoDetailsScore(scoreId,D,"node 2 is D !"), NoDetailsScore("compliance",NoScore,""),NoDetailsScore("system-updates",NoScore,""))))
    }
  }
}
