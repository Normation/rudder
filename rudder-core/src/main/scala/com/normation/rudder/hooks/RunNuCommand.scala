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
import java.util.concurrent.TimeUnit

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.Duration

import com.zaxxer.nuprocess.NuProcessBuilder
import com.zaxxer.nuprocess.codec.NuAbstractCharsetHandler

import net.liftweb.common.Loggable
import scala.util.control.NonFatal

/*
 * The goal of that file is to give a simple abstraction to run hooks in
 * rudder.
 *
 * Hooks are stored in a directory. All hooks from a directory are
 * run sequentially, so that side effects from one hook can be used
 * in the following if the user want so.
 * A hook wich fails stop the process and error from stderr are used
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

object RunNuCommand extends Loggable {

  /*
   * An helper class that create a promise to handle the async termination of the NuProcess
   * and signal it to its derived future.
   * Exit code, stdout and sdterr content are accumalated in CmdResult data structure.
   */
  private[this] class CmdProcessHandler extends NuAbstractCharsetHandler(StandardCharsets.UTF_8) {
    val promise = Promise[CmdResult]()
    val stderr, stdout = new StringBuilder()

    override def onStderrChars(buffer: CharBuffer, closed: Boolean, coderResult: CoderResult): Unit = {
      while(buffer.hasRemaining) { stderr + buffer.get() }
    }
    override def onStdoutChars(buffer: CharBuffer, closed: Boolean, coderResult: CoderResult): Unit = {
      while(buffer.hasRemaining) { stdout + buffer.get() }
    }

    override def onExit(exitCode: Int): Unit = {
      promise.success(CmdResult(exitCode, stdout.toString, stderr.toString))
    }

    def run = promise.future
  }

  /**
   * Run a hook asynchronously.
   * The time limit should NEVER be set to 0, because it would cause process
   * to NEVER terminate if something not exepected happen, like if the process
   * is waiting for stdin input.
   */
  def run(cmd: Cmd, limit: Duration = Duration(30, TimeUnit.MINUTES)): Future[CmdResult] = {
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
     * Some intersting error code:
     * - Invocation of posix_spawn() failed, return code: 2, last error: 2
     *   => command not found
     * - Invocation of posix_spawn() failed, return code: 13, last error: 13
     *   => bad permission
     *
     *
     */
    try {
      if(limit.length <= 0) {
        logger.warn(s"No duration limit set for command '${cmd.cmdPath} ${cmd.parameters.mkString(" ")}'. " +
            "That can create a pill of zombies if termination of the command is not correct")
      }
      import scala.collection.JavaConverters._
      val handler = new CmdProcessHandler()
      val processBuilder = new NuProcessBuilder((cmd.cmdPath::cmd.parameters).asJava, cmd.environment.asJava)
      processBuilder.setProcessListener(handler)

      /*
       * The start process is nasty:
       * - it can return null is something goes wrong,
       * - even if everything goes OK, it can write stacktrace in our logs,
       *   see: https://github.com/brettwooldridge/NuProcess/issues/63
       */
      val process = processBuilder.start()
      if(process == null) {
        Future.failed(new RuntimeException(s"Error: unable to start native command '${cmd.cmdPath} ${cmd.parameters.mkString(" ")}'"))
      } else {
        //that class#method does not accept interactive mode
        process.closeStdin(true)
        process.waitFor(limit.length, limit.unit)

        handler.run
      }
    } catch {
      case NonFatal(ex) => Future.failed(ex)
    }
  }

}


