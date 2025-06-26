package com.normation.rudder.services.policies.write

import com.normation.errors.IOResult
import com.normation.rudder.domain.logger.PolicyGenerationLoggerPure
import com.normation.rudder.facts.nodes.NodeFact.IterableToChunk
import com.normation.rudder.services.policies.RuleVal
import com.normation.zio.*
import zio.*
import zio.syntax.ToZio

trait RuleValGeneratedHook {
  def name: String
  def run(chunk: Chunk[RuleVal]): IOResult[Unit]
}

class RuleValGeneratedHookService {

  private val hooksRef: Ref[Chunk[RuleValGeneratedHook]] = Ref.make(Chunk.empty[RuleValGeneratedHook]).runNow

  def addHook(hook: RuleValGeneratedHook): UIO[Unit] = {
    hooksRef.update(_ :+ hook)
  }

  def runHooks(ruleVals: Seq[RuleVal]): UIO[Unit] = {
    for {
      hooks <- hooksRef.get
      _     <- PolicyGenerationLoggerPure.debug(s"Start running Rule val generation post hook")
      _      = hooks.map { hook =>
                 hook
                   .run(ruleVals.toChunk)
                   .timed
                   .flatMap { s =>
                     PolicyGenerationLoggerPure.timing.info(s"hook '${hook.name}' has run in ${s._1.render}") *>
                     s._2.succeed
                   }
                   .catchAll(err => {
                     PolicyGenerationLoggerPure.warn(
                       s"An error occurred while running hook '${hook.name}', policy generation continues, error: ${err.fullMsg}"
                     )
                   })
                   .forkDaemon
                   .runNow
               }
    } yield {}
  }
}
