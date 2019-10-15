/*
*************************************************************************************
* Copyright 2016 Normation SAS
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

package com.normation.rudder.hooks

import java.io.File

import com.normation.NamedZioLogger
import net.liftweb.common.Box
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.common.Logger
import net.liftweb.util.Helpers.tryo
import org.slf4j.LoggerFactory
import zio._
import zio.syntax._
import zio.duration._
import com.normation.errors._
import com.normation.zio._
import com.normation.zio.ZioRuntime

/*
 * The goal of that file is to give a simple abstraction to run hooks in
 * rudder.
 *
 * Hooks are stored in a directory. All hooks from a directory are
 * run sequentially, so that side effects from one hook can be used
 * in the following if the user want so.
 * A hook which fails stop the process and error from stderr are used
 * for the reason of the failing.
 * A failed hook is decided by the return code: 0 mean success, anything
 * else is a failure.
 *
 * Hooks are asynchronously executed by default, in a Future.
 */

/*
 * Hooks are group in "set". We run all the hooks
 * from the same set with the same set of envVariables.
 * The hooks are executed in the order of the list.
 */

final case class Hooks(basePath: String, hooksFile: List[String])
/**
 * Hook env are pairs of environment variable name <=> value
 */
final case class HookEnvPair(name: String, value: String) {
  def show = s"[${name}:${value}]"
}
final case class HookEnvPairs(values: List[HookEnvPair]) extends AnyVal {
  //shortcut to view envVariables as a Map[String, String]
  def toMap = values.map(p => (p.name, p.value)).toMap

  def add(other: HookEnvPairs) = HookEnvPairs(this.values ::: other.values)

  /**
   * Formatted string
   * [key1:val1][key2:val2]...
   */
  def show: String = values.map(_.show).mkString(" ")
}

object HookEnvPairs {
  def toListPairs(values: (String, String)*) = values.map( p => HookEnvPair(p._1, p._2)).toList

  def build( values: (String, String)*) = {
    HookEnvPairs(toListPairs(values:_*))
  }
}

/**
 * Loggger for hooks
 */
object HooksLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("hooks")

  object LongExecLogger extends Logger {
    override protected def _logger = LoggerFactory.getLogger("hooks.longexecution")
  }
}

object PureHooksLogger extends NamedZioLogger {
  override def loggerName: String = "hooks"

  object LongExecLogger extends NamedZioLogger {
   override def loggerName = "hooks.longexecution"
  }
}

sealed trait HookReturnCode {
  def code   : Int
  def stdout : String
  def stderr : String
  def msg    : String
}

object HookReturnCode {
  sealed trait Success extends HookReturnCode
  sealed trait Error   extends HookReturnCode


  //special return code
  final case class  Ok (stdout: String, stderr: String) extends Success {
    val code = 0
    val msg = ""
  }
  final case class  Warning(code: Int, stdout: String, stderr: String, msg: String) extends Success
  final case class  ScriptError(code: Int, stdout: String, stderr: String, msg: String) extends Error
  final case class  SystemError(msg: String) extends Error {
    val stderr = ""
    val stdout = ""
    val code = Int.MaxValue // special value out of bound 0-255, far in the "reserved" way
  }

  //special return code 100: it is a stop state, but in some case can lead to
  //an user message that is tailored to explain that it is not a hook error)
  final case class  Interrupt(msg: String, stdout: String, stderr: String) extends Error {
    val code = Interrupt.code
  }
  object Interrupt {
    val code = 100
  }
}

object HooksImplicits {
  import scala.language.implicitConversions
  implicit def hooksReturnCodeToBox(hrc: HookReturnCode): Box[Unit] = {
    hrc match {
      case _: HookReturnCode.Success => Full(())
      case x: HookReturnCode.Error   => Failure(s"${x.msg}\n stdout: ${x.stdout}\n stderr: '${x.stderr}'")
    }
  }

}

object RunHooks {

  /**
   * Runs a list of hooks. Each hook is run sequencially (so that
   * the user can expects one hook side effects to be used in the
   * next one), but the whole process is asynchronous.
   * If one hook fails, the whole list fails.
   *
   * The semantic of return codes is:
   * - < 0: success (we should never have a negative returned code, but java int are signed)
   * - 0: success
   * - 1-31: errors. These codes stop the hooks pipeline, and the generation is on error
   * - 32-63: warnings. These code log a warning message, but DON'T STOP the next hook processing
   * - 64-255: reserved. For now, they will be treat as "error", but that behaviour can change any-time
   *            without notice.
   * - > 255: should not happen, but treated as reserved.
   *
   */
  def asyncRun(hooks: Hooks, hookParameters: HookEnvPairs, envVariables: HookEnvPairs, warnAfterMillis: Duration = 5.minutes, killAfter: Duration = 1.hour): IOResult[HookReturnCode] = {

    import HookReturnCode._

    def logReturnCode(result: HookReturnCode): IOResult[Unit] = {
      for {
        _ <- PureHooksLogger.trace(s"  -> results: ${result.msg}")
        _ <- PureHooksLogger.trace(s"  -> stdout : ${result.stdout}")
        _ <- PureHooksLogger.trace(s"  -> stderr : ${result.stderr}")
        _ <- ZIO.when(result.code >= 32 && result.code <= 64) { // warning
               for {
                 _ <- PureHooksLogger.warn(result.msg)
                 _ <- ZIO.when(result.stdout.size > 0) { PureHooksLogger.warn(s"  -> stdout : ${result.stdout}") }
                 _ <- ZIO.when(result.stderr.size > 0) { PureHooksLogger.warn(s"  -> stderr : ${result.stderr}") }
               } yield ()
             }
      } yield ()
    }

    def translateReturnCode(path: String, result: CmdResult): HookReturnCode = {
      lazy val msg = {
        val specialCode = if(result.code == Int.MinValue) { // this is most commonly file not found or bad rights
          " (check that file exists and is executable)"
        } else ""
        s"Exit code=${result.code}${specialCode} for hook: '${path}'."
      }
      if(       result.code == 0 ) {
        Ok(result.stdout, result.stderr)
      } else if(result.code <  0 ) { // this should not happen, and/or is likely a system error (bad !#, etc)
        //using script error because we do have an error code and it can help in some case

        ScriptError(result.code, result.stdout, result.stderr, msg)
      } else if(result.code >= 1  && result.code <= 31 ) { // error
        ScriptError(result.code, result.stdout, result.stderr, msg)
      } else if(result.code >= 32 && result.code <= 64) { // warning
          Warning(result.code, result.stdout, result.stderr, msg)
      } else if(result.code == Interrupt.code) {
        Interrupt(msg, result.stdout, result.stderr)
      } else { //reserved - like error for now
        ScriptError(result.code, result.stdout, result.stderr, msg)
      }
    }

    /*
     * We can not use Future.fold, because it execute all scripts
     * in parallel and then combine their results. Our semantic
     * is execute script one after the other, combining at each
     * step.
     * But we still want the whole operation to be non-bloking.
     */
    val runAllSeq = ZIO.foldLeft(hooks.hooksFile)(Ok("",""):HookReturnCode) { case (previousCode, nextHookName) =>
      previousCode match {
        case x: Error   => x.succeed
        case x: Success => // run the next hook
          val path = hooks.basePath + File.separator + nextHookName
          val env = envVariables.add(hookParameters)
          for {
            _ <- PureHooksLogger.debug(s"Run hook: '${path}' with environment parameters: ${hookParameters.show}")
            _ <- PureHooksLogger.trace(s"System environment variables: ${envVariables.show}")
            p <- RunNuCommand.run(Cmd(path, Nil, env.toMap))
            r <- p.await
            c =  translateReturnCode(path, r)
            _ <- logReturnCode(c)
          } yield {
            c
          }
      }
    }

    val cmdInfo = s"'${hooks.basePath}' with environment parameters: [${hookParameters.show}]"
    (for {
      //cmdInfo is just for comments/log. We use "*" to synthetize
      _        <- PureHooksLogger.debug(s"Run hooks: ${cmdInfo}")
      _        <- PureHooksLogger.trace(s"Hook environment variables: ${envVariables.show}")
      time_0   <- UIO(System.currentTimeMillis)
      res      <- ZioRuntime.blocking(runAllSeq).timeout(killAfter).notOptional(s"Hook '${cmdInfo}' timed out after ${killAfter.asJava.toString}").provide(ZioRuntime.Environment)
      duration <- UIO(System.currentTimeMillis - time_0)
      _        <- ZIO.when(duration > warnAfterMillis.toMillis) {
                    PureHooksLogger.LongExecLogger.warn(s"Hooks in directory '${cmdInfo}' took more than configured expected max duration (${warnAfterMillis.toMillis}): ${duration} ms")
                  }
      _        <- PureHooksLogger.debug(s"Done in ${duration} ms: ${cmdInfo}") // keep that one in all cases if people want to do stats
    } yield {
      res
    }).chainError(s"Error when executing hooks in directory '${hooks.basePath}'.")
  }

  /*
   * Run hooks in given directory, synchronously.
   *
   * Only the files with prefix ".hook" are selected as hooks, all
   * other files will be ignored.
   *
   * The hooks will be run in lexigraphically order, so that the
   * "standard" ordering of unix hooks (or init.d) with numbers
   * works as expected:
   *
   * 01-first.hook
   * 20-second.hook
   * 30-third.hook
   * etc
   *
   * You can get a warning if the hook took more than a given duration (in millis)
   */
  def syncRun(hooks: Hooks, hookParameters: HookEnvPairs, envVariables: HookEnvPairs, warnAfterMillis: Duration = 5.minutes, killAfter: Duration = 1.hour): HookReturnCode = {
    asyncRun(hooks, hookParameters, envVariables, warnAfterMillis, killAfter).either.runNow match {
      case Right(x)  => x
      case Left(err) => HookReturnCode.SystemError(s"Error when executing hooks in directory '${hooks.basePath}'. Error message is: ${err.fullMsg}")
    }
  }

  /**
   * Get the hooks set for the given directory path.
   * Hooks must be executable and not ends with one of the
   * non-executable extensions.
   */
   def getHooks(basePath: String, ignoreSuffixes: List[String]): Box[Hooks] = {
     tryo {
       val dir = new File(basePath)
       // Check that dir exists before looking in it
       if (dir.exists) {
         HooksLogger.debug(s"Looking for hooks in directory '${basePath}', ignoring files with suffix: '${ignoreSuffixes.mkString("','")}'")
         // only keep executable files
         val files = dir.listFiles().toList.flatMap { file =>
           file match {
             case f if (f.isDirectory) => None
             case f =>
               if(f.canExecute) {
                 val name = f.getName
                 //compare ignore case (that's why it's a regienMatches) extension and name
                 ignoreSuffixes.find(suffix => name.regionMatches(true, name.length - suffix.length, suffix, 0, suffix.length)) match {
                   case Some(suffix) =>
                     PureHooksLogger.debug(s"Ignoring hook '${f.getAbsolutePath}' because suffix '${suffix}' is in the ignore list")
                     None
                   case None      =>
                     Some(f.getName)
                 }
               } else {
                 PureHooksLogger.debug(s"Ignoring hook '${f.getAbsolutePath}' because it is not executable. Check permission if not expected behavior.")
                 None
               }
           }
         }.sorted // sort them alphanumericaly
         Hooks(basePath, files)
       } else {
         PureHooksLogger.debug(s"Ignoring hook directory '${dir.getAbsolutePath}' because path does not exists")
         // return an empty Hook
         Hooks(basePath, List[String]())
       }
     }
   }

}
