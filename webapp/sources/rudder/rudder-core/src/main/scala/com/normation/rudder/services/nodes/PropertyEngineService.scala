package com.normation.rudder.services.nodes

import better.files.File
import com.normation.errors._
import com.normation.rudder.domain.logger.NodePropertiesLoggerPure
import com.normation.rudder.domain.secret.Secret
import com.normation.zio._
import net.liftweb.json.JsonParser
import zio.Ref
import zio.UIO
import zio.syntax.ToZio

import java.nio.charset.StandardCharsets

final case class EngineOption(name: String, value: String)

trait RudderPropertyEngine {
  def name: String

  def process(namespace: List[String], opt: Option[List[EngineOption]]): IOResult[String]
}

trait PropertyEngineService {
  def process(engineName: String, namespace: List[String], opt: Option[List[EngineOption]]): IOResult[String]

  def addEngine(engine: RudderPropertyEngine): UIO[Unit]
}

class PropertyEngineServiceImpl(listOfEngine: List[RudderPropertyEngine]) extends PropertyEngineService {

  private[this] val engines: Ref[Map[String, RudderPropertyEngine]] = (for {
    v <- Ref.make[Map[String, RudderPropertyEngine]](listOfEngine.map(e => e.name.toLowerCase -> e).toMap)
  } yield v).runNow

  override def process(engine: String, namespace: List[String], opt: Option[List[EngineOption]]): IOResult[String] = {
    for {
      engineMap            <- engines.get
      engineProp           <- engineMap.get(engine.toLowerCase)
                                .notOptional(s"Engine '${engine}' not found. Can not be expanded")
      interpolatedValueRes <- engineProp.process(namespace, opt)
    } yield {
      interpolatedValueRes
    }
  }

  def addEngine(engine: RudderPropertyEngine): UIO[Unit] = {
    for {
      _ <- engines.update(_ + (engine.name.toLowerCase -> engine))
    } yield ()
  }
}

/*   Secret variable engine
 *   -----------------------------------
 *
 *   To use this engine, the private plugin `secret-management` should be installed
 *   or at least the configuration file should be present.
 *
 *   This engine check the in /var/rudder/configuration-repository/secrets/secrets.json
 *   to interpolated in a node property a format like :
 *        ${engine.secret[variable_name]}
 *   by the `variable_name` value from this file.
 *
 *   + This engine doesn't take any options
 *   + Only one namespace is allowed
 */
object SecretVariableEngine extends RudderPropertyEngine {
  def name = "secret"

  private [this] def verifyEntries(namespace: List[String], opt: Option[List[EngineOption]]): UIO[Unit] = {
    for {
      // Verify that there is no option provided with the secret engine
      _       <- opt match {
                   case Some(options) =>
                     val optuple = options.map(e => (e.name, e.value)).map{ case (name, value) => s"($name = $value)"}.mkString(", ")
                     NodePropertiesLoggerPure.error(s"Options has been given for engine `$name` but he don't accept any option : $optuple")
                   case None          =>
                     NodePropertiesLoggerPure.debug(s"Engine `$name` doesn't receive any options")
                 }
      // Verify that there is only one namespace provided
      _ <- if (namespace.length != 1) {
             NodePropertiesLoggerPure
               .error(s"Engine `$name` required only one namespace, the name of the secret variable, but found : " +
                 s"${namespace.mkString(",")}")
           } else {
             NodePropertiesLoggerPure.debug(s"Valid engine `$name` with namespace `${namespace.head}`")
           }
    } yield ()
  }

  def process(namespace: List[String], opt: Option[List[EngineOption]]): IOResult[String] = {
    implicit val formats = net.liftweb.json.DefaultFormats

    val secretsFile = File(s"/var/rudder/configuration-repository/secrets/secrets.json")
    (for {
      _       <- verifyEntries(namespace, opt)
      content <- IOResult.effect(s"File ${secretsFile.pathAsString} not found. Please make sure `secret-management` plugin is installed") {
                   secretsFile.contentAsString(StandardCharsets.UTF_8)
                 }
      json    <- IOResult.effect(JsonParser.parse(content)).chainError(s"Parsing JSON in $secretsFile have failed")
      secrets <- IOResult.effect((json \ "secrets").extract[List[Secret]])
                   .chainError(s"Unable to find parameter `secrets` in $secretsFile. Verify that the file is well formatted")

    } yield {
      secrets.find(_.name == namespace.head) match {
        case None            => Inconsistency(s"Unable to find secret variable : `${namespace.head}` in `${secretsFile}`").fail
        case Some(secretVal) => secretVal.value.succeed
      }
    }).flatten
  }
}
