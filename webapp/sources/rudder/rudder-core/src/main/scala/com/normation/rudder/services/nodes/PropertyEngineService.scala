package com.normation.rudder.services.nodes

import com.normation.errors._
import com.typesafe.config.ConfigValue
import zio.Ref
import zio.syntax.ToZio
import com.normation.zio._
import zio.UIO


trait RudderPropertyEngine {
  def name: String

  def process(namespace: List[String], parameters: ConfigValue): IOResult[String]
}

trait PropertyEngineService {
  def process(engineName: String, namespace: List[String], parameters: ConfigValue): IOResult[String]

  def addEngine(engine: RudderPropertyEngine): UIO[Unit]
}

class PropertyEngineServiceImpl(listOfEngine: List[RudderPropertyEngine]) extends PropertyEngineService {

  private[this] val engines: Ref[Map[String, RudderPropertyEngine]] = (for {
    v <- Ref.make[Map[String, RudderPropertyEngine]](listOfEngine.map(e => e.name.toLowerCase -> e).toMap)
  } yield v).runNow

  override def process(engine: String, namespace: List[String], parameters: ConfigValue): IOResult[String] = {
    for {
      engineMap            <- engines.get
      engineProp           <- engineMap.get(engine.toLowerCase)
                                .notOptional(s"Engine '${engine}' not found. Parameter can not be expanded: ${parameters}")
      interpolatedValueRes <- engineProp.process(namespace, parameters)
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

class SecretEngine extends RudderPropertyEngine {
  override def name: String = "secret"

  override def process(namespace: List[String], parameters: ConfigValue): IOResult[String] = "encrypted-string-test" .succeed //TODO add the logic here

}
