package com.normation.rudder.services.healthcheck

import java.lang.Runtime.getRuntime

import com.normation.errors.IOResult
import com.normation.rudder.domain.logger.{HealthcheckLoggerPure => logger}
import com.normation.rudder.services.healthcheck.HealthcheckResult.Critical
import com.normation.rudder.services.healthcheck.HealthcheckResult.Warning
import com.normation.rudder.services.healthcheck.HealthcheckResult.Ok
import zio.UIO
import zio.ZIO
import zio.syntax.ToZio

final case class CheckName(value: String)

trait Check {
  def name: CheckName
  def run: IOResult[HealthcheckResult]
}

sealed trait HealthcheckResult { def msg:String }
object HealthcheckResult {
  final case class Ok(val msg: String) extends HealthcheckResult
  final case class Warning(val msg: String) extends HealthcheckResult
  final case class Critical(val msg: String) extends HealthcheckResult
}

class HealthcheckService(checks: List[Check]) {

   def runAll: ZIO[Any, Nothing, List[HealthcheckResult]]  = ZIO.foreach(checks) { c =>
     for {
       res <- c.run.catchAll { err =>
                HealthcheckResult.Critical(
                  s"A fatal error was encountered when running check '${c.name.value}': ${err.fullMsg}"
                ).succeed
              }
       _   <- logHealthcheck(c.name, res)
     } yield res
  }

  private[this] def logHealthcheck(name: CheckName, check: HealthcheckResult): UIO[Unit] = {
    val msg = s"${name.value}: ${check.msg}"
    check match {
      case _: Critical => logger.error(msg)
      case _: Warning  => logger.warn(msg)
      case _: Ok       => logger.debug(msg)
    }
  }
}

final object CheckCoreNumber extends Check {
  def name: CheckName = CheckName("CPU Cores available")
  def run: IOResult[HealthcheckResult] = for {
    availableCores <- IOResult.effect(getRuntime.availableProcessors)
  } yield {
    availableCores match {
      case i if i <= 0 => Critical("No Core available")
      case 1 => Warning(s"Only one cores available")
      case n => Ok(s"${n} cores available")
    }
  }
}