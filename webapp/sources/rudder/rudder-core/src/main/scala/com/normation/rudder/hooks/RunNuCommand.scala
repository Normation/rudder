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

import java.nio.CharBuffer
import java.nio.charset.CoderResult
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util

import com.normation.NamedZioLogger
import com.normation.errors._
import com.normation.zio._
import com.zaxxer.nuprocess.NuProcess
import com.zaxxer.nuprocess.NuProcessBuilder
import com.zaxxer.nuprocess.codec.NuAbstractCharsetHandler
import com.zaxxer.nuprocess.internal.BasePosixProcess
import zio._
import zio.duration.Duration.Infinity
import zio.duration._
import zio.syntax._


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
 * Information to run a set of commands.
 * All commands of a given set get the same parameters.
 * Parameters is an order list of strings, that can be empty.
 * It correspond to anything separated by a white space in an
 * unix command line.
 */
final case class Cmd(cmdPath: String, parameters: List[String], environment: Map[String, String])
final case class CmdResult(code: Int, stdout: String, stderr: String)

object RunNuCommand {

  val logger = NamedZioLogger("command-runner")

  // we don't want NuCommand to log with its format which is totally broken along our, so we redefined it.
  object SilentLogger extends BasePosixProcess(null) {
    import java.util.logging._
    override def start(command: util.List[String], environment: Array[String], cwd: Path): NuProcess = {null}
    def silent() = {
      BasePosixProcess.LOGGER.setLevel(java.util.logging.Level.WARNING)
      BasePosixProcess.LOGGER.setUseParentHandlers(false)
      val h = new ConsoleHandler()
      h.setFormatter(new SimpleFormatter() {
        private val format = "%1$tFT%1$tT%1$tz %2$-7s %3$s: %4$s"
        override def format(lr: LogRecord): String = {
          val msg = if(lr.getMessage == "Failed to start process") {
            "Failed to execute shell command from Rudder"
          } else lr.getMessage
          String.format(format, new java.util.Date(lr.getMillis), lr.getLevel.getName, msg, lr.getThrown.getMessage)
        }
      })
      BasePosixProcess.LOGGER.addHandler(h)
    }
  }
  SilentLogger.silent()

  /*
   * An helper class that creates a promise to handle the async termination of the NuProcess
   * command and signal the completion to the caller.
   * Exit code, stdout and stderr content are accumulated in CmdResult data structure.
   */
  private[this] class CmdProcessHandler(promise: Promise[Nothing, CmdResult]) extends NuAbstractCharsetHandler(StandardCharsets.UTF_8) {
    val stderr = new java.lang.StringBuilder()
    val stdout = new java.lang.StringBuilder()

    override def onStderrChars(buffer: CharBuffer, closed: Boolean, coderResult: CoderResult): Unit = {
      while(!closed && buffer.hasRemaining) { stderr.append(buffer.get()) }
    }
    override def onStdoutChars(buffer: CharBuffer, closed: Boolean, coderResult: CoderResult): Unit = {
      while(!closed && buffer.hasRemaining) { stdout.append(buffer.get()) }
    }

    override def onExit(exitCode: Int): Unit = {
      ZioRuntime.internal.runtime.unsafeRun(promise.succeed(CmdResult(exitCode, stdout.toString, stderr.toString)).untraced)
    }

    def run = promise
  }

  /**
   * Run a hook asynchronously.
   * The time limit should NEVER be set to 0 or Infinity, because it would cause process
   * to NEVER terminate if something not exepected happen, like if the process
   * is waiting for stdin input.
   *
   * All that run method is non blocking (but still I/O, there's a linux process
   * creation under the hood).
   * Waiting on the promise completion can be long, of course, depending of the
   * command executed.
   */
  def run(cmd: Cmd, limit: Duration = 30.minute): IOResult[Promise[Nothing, CmdResult]] = {
    /*
     * Some information about NuProcess command line: what NuProcess call "commands" is
     * actually the command (first item in the array) and its parameters (following items).
     * So typically, to execute "/bin/ls /tmp/foo", you can't do:
     * - new NuProcessBuilder("/bin/ls /tmp/foo") => will fail silently
     * But you need to say:
     * - new NuProcessBuilder("/bin/ls", "/tmp/foo")
     * And if you want to pass more arguments:
     * - new NuProcessBuilder("/bin/ls", "-la", "/tmp/foo")
     * This fails:
     * - new NuProcessBuilder("/bin/ls", "-la /tmp/foo") => fails with: /bin/ls : invalid option -- ' '
     *
     * If no time limit is given, something like
     * - new NuProcessBuilder("/bin/cat")
     * Will stall forever.
     *
     * Some interesting error code:
     * - Invocation of posix_spawn() failed, return code: 2, last error: 2
     *   => command not found
     * - Invocation of posix_spawn() failed, return code: 13, last error: 13
     *   => bad permission
     *
     *
     */
    import scala.jdk.CollectionConverters._
    val cmdInfo =  s"'${cmd.cmdPath} ${cmd.parameters.mkString(" ")}'"
    val errorMsg = s"Error when executing command ${cmdInfo}"


    (for {
      _              <- ZIO.when(limit == Infinity|| limit.toMillis <= 0) {
                          logger.warn(s"No duration limit set for command '${cmd.cmdPath} ${cmd.parameters.mkString(" ")}'. " +
                              "That can create a pill of zombies if termination of the command is not correct")
                        }
      promise        <- Promise.make[Nothing, CmdResult]
      handler        =  new CmdProcessHandler(promise)
      processBuilder =  new NuProcessBuilder((cmd.cmdPath::cmd.parameters).asJava, cmd.environment.asJava)
      _              <- IOResult.effectNonBlocking(errorMsg)(processBuilder.setProcessListener(handler))

      /*
       * The start process is nasty:
       * - it can return null if something goes wrong,
       * - even if everything goes OK, it can write stacktrace in our logs,
       *   see: https://github.com/brettwooldridge/NuProcess/issues/63
       * In the meantime, we silent the logger, and we check for return code of min value with
       * commons use cases:
       *
       * It is non blocking though, as the blocking part is done in the spwaned thread.
       */
      process        <- IOResult.effectNonBlocking(errorMsg)(processBuilder.start())
      _              <- if(process == null) {
                          Unexpected(s"Error: unable to start native command ${cmdInfo}").fail
                        } else {
                          // that class#method does not accept interactive mode
                          // this part can block, waiting for things to complete
                          IOResult.effect(errorMsg) {
                            process.closeStdin(true)
                            process.waitFor(limit.toMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
                          }.fork
                        }
    } yield {
      promise
    }).untraced
  }

}


