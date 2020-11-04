package com.normation.rudder.services.healthcheck

import java.io
import java.lang.Runtime.getRuntime

import better.files.File.root
import com.normation.errors.IOResult
import com.normation.errors.Inconsistency
import com.normation.rudder.domain.logger.{HealthcheckLoggerPure => logger}
import com.normation.rudder.hooks.Cmd
import com.normation.rudder.hooks.RunNuCommand
import com.normation.rudder.services.healthcheck.HealthcheckResult.Critical
import com.normation.rudder.services.healthcheck.HealthcheckResult.Ok
import com.normation.rudder.services.healthcheck.HealthcheckResult.Warning
import com.normation.rudder.services.nodes.NodeInfoService
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
  def name: CheckName = CheckName("CPU cores")
  def run: IOResult[HealthcheckResult] = for {
    availableCores <- IOResult.effect(getRuntime.availableProcessors)
  } yield {
    availableCores match {
      // Should not happen, but not critical by itself
      case i if i <= 0 => Warning(name, "Could not detect CPU cores")
      case 1 => Warning(name, s"Only one core, recommended value is at least 2")
      case n => Ok(name, s"${n} cores")
    }
  }
}

final object CheckFreeSpace extends Check {
  def name: CheckName = CheckName("Free disk space")

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
              val msg = s"Missing free space:\n${listMsgSpace} available (<5%)"
              Critical(name, msg)
            case pr if pr < 10L =>
              val msg = s"Missing free space:\n${listMsgSpace} available (<10%)"
              Warning(name, msg)
            case _              =>
              val msg = s"Enough free space: \n${listMsgSpace} available"
              Ok(name, msg)
          }
        case Nil =>
          Warning(name, "No partition found on the system")
      }
    }
  }
}

final class CheckFileDescriptorLimit(val nodeInfoService: NodeInfoService) extends Check {
  def name: CheckName = CheckName("File descriptor limit")
  def run: IOResult[HealthcheckResult] = {
    // Check the soft limit.
    // That can be raise or lower by any user but cannot exceed the hard limit
    val cmd = Cmd("/usr/bin/prlimit", "-n" ::  "-o"  :: "SOFT" :: "--noheadings" :: Nil, Map.empty)
    for {
      fdLimitCmd <- RunNuCommand.run(cmd)
      res        <- fdLimitCmd.await

      _          <- ZIO.when(res.code != 0) {
                      Inconsistency(
                        s"An error occurred while getting file descriptor soft limit with command '${cmd.display}':\n code: ${res.code}\n stderr: ${res.stderr}\n stdout: ${res.stdout}"
                      ).fail
                    }
      limit      <- IOResult.effectNonBlocking(res.stdout.trim.toLong)
    } yield {
      val numNode = nodeInfoService.getNumberOfManagedNodes
      val minimalLimit = 100 * numNode
      limit match {
        case limit if limit <= 10_000 =>
          Critical(name, s"Current file descriptor limit is ${limit}. It should be > 10 000.")
        case limit if limit <= minimalLimit =>
          Warning(name, s"Current file descriptor limit is ${limit}. It should be > ${minimalLimit} for ${numNode} nodes")
        case _ =>
          Ok(name, s"Maximum number of file descriptors: ${limit}")
      }
    }
  }
}
