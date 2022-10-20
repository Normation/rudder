package com.normation.rudder.services.nodes

import com.normation.errors._
import com.normation.zio._
import zio.Ref

final case class EngineOption(name: String, value: String)

trait RudderPropertyEngine {
  def name: String

  def process(namespace: List[String], opt: Option[List[EngineOption]]): IOResult[String]
}

trait PropertyEngineService {
  def process(engineName: String, namespace: List[String], opt: Option[List[EngineOption]]): IOResult[String]

  def addEngine(engine: RudderPropertyEngine): zio.UIO[Unit]
}

class PropertyEngineServiceImpl(listOfEngine: List[RudderPropertyEngine]) extends PropertyEngineService {

  private[this] val engines: Ref[Map[String, RudderPropertyEngine]] = (for {
    v <- Ref.make[Map[String, RudderPropertyEngine]](listOfEngine.map(e => e.name.toLowerCase -> e).toMap)
  } yield v).runNow

  override def process(engine: String, namespace: List[String], opt: Option[List[EngineOption]]): IOResult[String] = {
    for {
      engineMap            <- engines.get
      engineProp           <- engineMap
                                .get(engine.toLowerCase)
                                .notOptional(s"Engine '${engine}' not found. Can not be expanded")
      interpolatedValueRes <- engineProp.process(namespace, opt)
    } yield {
      interpolatedValueRes
    }
  }

  def addEngine(engine: RudderPropertyEngine): zio.UIO[Unit] = {
    for {
      _ <- engines.update(_ + (engine.name.toLowerCase -> engine))
    } yield ()
  }
}
