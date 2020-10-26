package com.normation.rudder.services.healthcheck

import java.io
import java.lang.Runtime.getRuntime
import java.io.File

import better.files.File
import better.files.File.root
import com.normation.errors.IOResult
import com.normation.rudder.domain.logger.{HealthcheckLoggerPure => logger}
import com.normation.rudder.hooks.Cmd
import com.normation.rudder.hooks.RunNuCommand
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

final object CheckFreeSpace extends Check {
  def name: CheckName = CheckName("Disk free space available")

  final case class SpaceInfo(val path: String, val free: Long, val available: Long){
    def percent: Long =
      if (available != 0) {
        (free * 100 / available)
      } else {
        0
      }
  }

  def run: IOResult[HealthcheckResult] = {
    val file          = root / "proc" / "mounts"
    val mountsContent = file.lines.map(x => x.split(" ")(1)).toList

    // We want to check `/var/*` if none exist take `/`
    val partitionToCheck = {
      val isParititionVar = mountsContent.filter(_.regionMatches(true, 0, "/var", 0, 4))
      if (isParititionVar.isEmpty) List("/") else isParititionVar
    }

    for {
      paritionSpaceInfos <- IOResult.effect {
        partitionToCheck.map { x =>
          val file = new io.File(x)
          SpaceInfo(x, file.getUsableSpace, file.getTotalSpace)
        }
      }
    } yield {
      val pcSpaceLeft = paritionSpaceInfos.map(x => (x.path, x.percent)).sortBy(-_._2)
      pcSpaceLeft match {
        case h :: _ =>
          val listMsgSpace = pcSpaceLeft.map(s => s"- ${s._1} -> ${s._2}%").mkString("\n")
          h._2 match {
            case pr if pr < 5L  =>
              val msg = s"Some space is under :\n${listMsgSpace} available"
              Critical(name, msg)
            case pr if pr < 10L =>
              val msg = s"Some space partition is under a warning level:\n${listMsgSpace} available"
              Warning(name, msg)
            case _                =>
              val msg = s"Space available is ok: \n${listMsgSpace} available"
              Ok(name, msg)
          }
        case Nil =>
          Critical(name, "No partition found on the system")
      }
    }
  }
}
