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

sealed trait HealthcheckResult {
  def msg:String
  def name:CheckName
  def index: Int
}
object HealthcheckResult {
  final case class Ok(val name: CheckName, val msg: String, val index: Int = 0) extends HealthcheckResult
  final case class Warning(val name: CheckName, val msg: String, val index: Int = 1) extends HealthcheckResult
  final case class Critical(val name: CheckName, val msg: String, val index: Int = 2) extends HealthcheckResult
}

object HealthcheckUtils {
  def compareCheck(c1: HealthcheckResult, c2: HealthcheckResult): Boolean = {
    (c1, c2) match {
      case (c1, c2) if(c1.index == c2.index) => c1.msg >= c2.msg
      case (c1, c2)  => c1.index >= c2.index
    }
  }
}

class HealthcheckService(checks: List[Check]) {

   def runAll: ZIO[Any, Nothing, List[HealthcheckResult]]  = ZIO.foreach(checks) { c =>
     for {
       res <- c.run.catchAll { err =>
                HealthcheckResult.Critical(
                    CheckName("All checks run")
                  , s"A fatal error was encountered when running check '${c.name.value}': ${err.fullMsg}"
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
      case i if i <= 0 => Critical(name, "No Core available")
      case 1 => Warning(name, s"Only one cores available")
      case n => Ok(name, s"${n} cores available")
    }
  }
}