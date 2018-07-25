package com.normation.rudder.services

import java.nio.file.{Files, Paths}
import java.util.concurrent.TimeUnit

import com.normation.rudder.hooks._
import net.liftweb.common.{Box, Failure, Full, Loggable}

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import scala.util.{Success, Try, Failure => TryFailure}

trait SupportScriptService {
  def launch() : Box[Array[Byte]]
}

class ScriptLauncher(
    private val pathToScript: String
  , private val pathToResult: String
  , private val timeOut: Duration = Duration(30, TimeUnit.SECONDS)
  , private val environment: Map[String, String] = System.getenv.asScala.toMap

) extends SupportScriptService with Loggable {

  private[this] def execScript() : Box[CmdResult] = {

    val cmd = Cmd(pathToScript, Nil, environment)
    // Since the API is blocking, we want to wait for the result.
    try {
      Full(Await.result(RunNuCommand.run(cmd, timeOut), timeOut))
    }
    catch {
      case NonFatal(exception) => Failure(s"An Error has occured when launching the script: ${exception.getMessage}")
    }
  }

  // The support script generates an archive in the /tmp folder.
  // We want to get its binary representation into an Array of byte
  // In order for the API to build an InMemoryResponse

  private[this] def getScriptResult() : Box[Array[Byte]] = {

    val path = "/tmp/support-info-server.tar.gz"
    Try(Paths.get(path)) match {
      case Success(supportInfo) => Full(Files.readAllBytes(supportInfo))
      case TryFailure(exception) => Failure(s"Could not get the file ${path}: cause is ${exception.getMessage}")
    }
  }

  override def launch() : Box[Array[Byte]] = {
    val getZip = for {
      cmdResult <- execScript()
      zip       <- if (cmdResult.code == 0) {
        getScriptResult()
      }
      else {
        Failure(s"An Error has occured when launching the script : Exit code=${cmdResult.code}")
      }
    } yield {
      zip
    }
    getZip
  }
}
