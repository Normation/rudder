/*
 *************************************************************************************
 * Copyright 2022 Normation SAS
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
import com.normation.inventory.domain.InventoryError.Inconsistency
import com.normation.inventory.domain.NodeId
import com.normation.zio.*
import zio.*
import zio.syntax.ToZio

trait ScoreService {
  def getAll(): IOResult[Map[NodeId, GlobalScore]]
  def getGlobalScore(nodeId:    NodeId): IOResult[GlobalScore]
  def getScoreDetails(nodeId:   NodeId): IOResult[List[Score]]
  def cleanScore(name:          String): IOResult[Unit]
  def update(newScores:         Map[NodeId, List[Score]]): IOResult[Unit]
  def registerScore(newScoreId: String, displayName: String): IOResult[Unit]
  def getAvailableScore(): IOResult[List[(String, String)]]
  def init():              IOResult[Unit]
}

class ScoreServiceImpl(globalScoreRepository: GlobalScoreRepository, scoreRepository: ScoreRepository) extends ScoreService {
  private val cache:      Ref[Map[NodeId, GlobalScore]] = Ref.make(Map.empty[NodeId, GlobalScore]).runNow
  private val scoreCache: Ref[Map[NodeId, List[Score]]] = Ref.make(Map.empty[NodeId, List[Score]]).runNow

  private val availableScore: Ref[List[(String, String)]] =
    Ref.make((ComplianceScore.scoreId, "Compliance") :: (SystemUpdateScore.scoreId, "System updates") :: Nil).runNow

  def init(): IOResult[Unit] = {
    for {
      globalScores <- globalScoreRepository.getAll()
      _            <- cache.set(globalScores)
      scores       <- scoreRepository.getAll()
      _            <- scoreCache.set(scores)
    } yield ()
  }

  def fillWithNoScore(globalScore: GlobalScore): IOResult[GlobalScore] = {
    for {
      scoreIds <- availableScore.get
    } yield {
      scoreIds.foldLeft(globalScore) {
        case (score, id) =>
          if (score.details.exists(_.scoreId == id._1)) score
          else score.copy(details = score.details :+ (NoDetailsScore(id._1, ScoreValue.NoScore, "")))

      }
    }
  }

  def getAll(): IOResult[Map[NodeId, GlobalScore]] = cache.get.flatMap(ZIO.foreach(_) {
    case (key, v) => fillWithNoScore(v).map((key, _))
  })

  def getGlobalScore(nodeId: NodeId): IOResult[GlobalScore] = {
    for {
      c           <- cache.get
      res         <-
        c.get(nodeId) match {
          case Some(g) => g.succeed
          case None    => Inconsistency(s"No global score for node ${nodeId.value}").fail
        }
      withNoScore <- fillWithNoScore(res)
    } yield {
      withNoScore
    }
  }

  def getScoreDetails(nodeId: NodeId): IOResult[List[Score]] = {
    for {
      c   <- scoreCache.get
      res <-
        c.get(nodeId) match {
          case Some(g) => g.succeed
          case None    => Inconsistency(s"No score for node ${nodeId.value}").fail
        }
    } yield {
      res
    }
  }

  def cleanScore(name: String): IOResult[Unit] = {
    for {
      _ <- cache.update(_.map { case (id, gscore) => (id, gscore.copy(details = gscore.details.filterNot(_.scoreId == name))) })
    } yield {}
  }

  def update(newScores: Map[NodeId, List[Score]]): IOResult[Unit] = {
    for {
      c           <- cache.get
      updatedValue = (for {
                       (nodeId, newScores) <- newScores
                     } yield {
                       val oldScores = c.get(nodeId) match {
                         case None           => Nil
                         case Some(oldScore) => oldScore.details
                       }
                       (nodeId, GlobalScoreService.computeGlobalScore(oldScores, newScores))
                     })

      updateScoreCache <- ZIO.foreach(newScores.toList) {
                            case (nodeId, scores) =>
                              ZIO.foreach(scores)(score => {
                                scoreRepository.saveScore(nodeId, score).catchAll(err => ScoreLoggerPure.info(err.fullMsg)) *>
                                scoreCache.update(sc =>
                                  sc + ((nodeId, score :: sc.get(nodeId).getOrElse(Nil).filter(_.scoreId != score.scoreId)))
                                )
                              })

                          }
      updatedCache     <- ZIO.foreach(updatedValue.toList) {
                            case (nodeId, score) => globalScoreRepository.save(nodeId, score) *> cache.update(_.+((nodeId, score)))
                          }
    } yield {}

  }

  def registerScore(newScoreId: String, displayName: String): IOResult[Unit] = {
    availableScore.update(_ :+ (newScoreId, displayName))
  }

  def getAvailableScore(): IOResult[List[(String, String)]] = {
    availableScore.get
  }
}

class ScoreServiceManager(readScore: ScoreService) {

  val handlers: Ref[List[ScoreEventHandler]] =
    Ref.make(ComplianceScoreEventHandler :: List.empty[ScoreEventHandler]).runNow

  def registerHandler(handler: ScoreEventHandler): UIO[Unit] = {
    handlers.update(handler :: _) *>
    (for {
      s <- readScore
             .getAll()
             .map(_.exists(g => handler.initForScore(g._2)))
             .catchAll(err => ScoreLoggerPure.error(s"Error when getting available scores for initialization") *> false.succeed)
      _ <- handler.initEvents.flatMap(ZIO.foreach(_)(handleEvent(_))).unit
    } yield ())
  }

  /*
   * The handler must catch any error
   */
  def handleEvent(scoreEvent: ScoreEvent): UIO[Unit] = {
    (for {
      h       <- handlers.get
      _       <- ScoreLoggerPure.debug(s"Received new score event ${scoreEvent.getClass}")
      _       <- ScoreLoggerPure.trace(s"event details: ${scoreEvent}")
      handled <- ZIO.foreach(h)(_.handle(scoreEvent).toIO)
      newScore = handled.flatMap(_.groupMapReduce(_._1)(_._2)(_ ++ _)).toMap
      _       <- ScoreLoggerPure.debug(s"${newScore.size} score for event")
      _       <- ScoreLoggerPure.ifTraceEnabled(ZIO.foreach(newScore.toList)(s => ScoreLoggerPure.trace(s"${s}")))
      _       <- readScore.update(newScore)
    } yield {}).catchAll(err =>
      ScoreLoggerPure.error(s"An error occurred while treating score event of type '${scoreEvent.getClass}': ${err.fullMsg}")
    )
  }

}
