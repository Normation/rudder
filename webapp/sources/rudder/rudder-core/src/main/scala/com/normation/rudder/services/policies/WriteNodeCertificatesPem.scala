/*
 *************************************************************************************
 * Copyright 2019 Normation SAS
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

package com.normation.rudder.services.policies

import better.files.File
import com.normation.NamedZioLogger
import com.normation.errors.*
import com.normation.inventory.domain.NodeId
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.hooks.Cmd
import com.normation.rudder.hooks.RunNuCommand
import com.normation.zio.ZioRuntime
import java.nio.charset.StandardCharsets
import zio.*
import zio.syntax.*

/**
 * This class will generate a file containing all nodes certificates concatenated.
 * The file can be used for Apache config for example.
 */
trait WriteNodeCertificatesPem {

  /*
   * Write certificates for nodes in a given, implementation dependant, location.
   * File should not be overwritten until the replacement is ready.
   */
  def writeCertificates(file: File, allNodeInfos: Map[NodeId, CoreNodeFact]): IOResult[Unit]

  /*
   * Same but async.
   */
  def writeCerticatesAsync(file: File, allNodeInfos: Map[NodeId, CoreNodeFact]): Unit
}

/*
 * In a default Rudder app, the file path is: /var/rudder/lib/ssl/allnodescerts.pem
 * After file is written, a reload hook can be executed if `reloadScriptPath` is not empty
 */
class WriteNodeCertificatesPemImpl(reloadScriptPath: Option[String]) extends WriteNodeCertificatesPem {

  val logger: NamedZioLogger = NamedZioLogger(this.getClass.getName)

  override def writeCerticatesAsync(file: File, allNodeInfos: Map[NodeId, CoreNodeFact]): Unit = {
    ZioRuntime.runNow(writeCertificates(file, allNodeInfos).catchAll(e => logger.error(e.fullMsg)).forkDaemon)
  }

  override def writeCertificates(file: File, allNodeInfos: Map[NodeId, CoreNodeFact]): IOResult[Unit] = {
    val allCertsNew = File(file.pathAsString + ".new")

    for {
      _    <- checkParentDirOK(file)
      certs = allNodeInfos.map { case (_, node) => node.rudderAgent.securityToken.key }
      _    <- writeCertificatesToNew(allCertsNew, certs)
      _    <- IOResult.attempt(allCertsNew.moveTo(file)(File.CopyOptions(overwrite = true)))
      _    <- execHook(reloadScriptPath)
    } yield ()
  }

  /*
   * write certificates in allCertsFile.new (if exists, delete)
   * once written
   */
  def writeCertificatesToNew(file: File, certs: Iterable[String]): IOResult[Unit] = {
    implicit val charset     = StandardCharsets.UTF_8
    implicit val writeAppend = File.OpenOptions.append

    for {
      _ <- ZIO.when(file.exists)(IOResult.attempt(file.delete()))
      _ <- ZIO.foreach(certs)(cert => IOResult.attempt(file.writeText(cert + "\n")))
      // Ensure that file exists even if no certificate exists
      _ <- ZIO.when(file.notExists)(IOResult.attempt(file.createFileIfNotExists()))
    } yield ()
  }

  // check that parent directory exists and is writable or try to create it.
  def checkParentDirOK(file: File): IOResult[Unit] = {
    val parent = file.parent

    for {
      _ <-
        IOResult.attemptZIO(s"Error when trying to create parent directory for node certificate file: ${parent.pathAsString}") {
          parent.createDirectoryIfNotExists(true)
          ZIO.unit
        }
      _ <- IOResult.attemptZIO {
             if (parent.isDirectory) ZIO.unit else Unexpected(s"Error: path '${parent.pathAsString}' must be a directory").fail
           }
      _ <- IOResult.attemptZIO {
             if (parent.isWritable) ZIO.unit
             else Unexpected(s"Error: path '${parent.pathAsString}' must be a writable directory").fail
           }
    } yield ()
  }

  def execHook(path: Option[String]): IOResult[Unit] = {
    path match {
      case None      => ZIO.unit
      case Some(cmd) =>
        cmd.split("""\s""").toList match {
          case Nil             => ZIO.unit
          // for the cmd, first arg is "cmd path", other are "parameters", and they must be splits
          case cmdPath :: args =>
            for {
              promise <-
                RunNuCommand.run(Cmd(cmdPath, args, Map(), None), Duration.fromNanos(5L * 60 * 1000 * 1000)) // error after 5 mins
              result  <- promise.await
              _       <- ZIO.when(result.code != 0) {
                           Unexpected(
                             s"Error when executing reload command '${cmd}' after writing node certificates file. Command " +
                             s"output: code: ${result.code}\nstdout: ${result.stdout}\nstderr: ${result.stderr}"
                           ).fail
                         }
            } yield ()
        }
    }
  }
}
