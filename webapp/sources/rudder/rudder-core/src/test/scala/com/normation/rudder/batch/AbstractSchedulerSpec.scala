/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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
package com.normation.rudder.batch

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import net.liftweb.common.Box
import net.liftweb.common.Full
import org.junit.runner.RunWith
import org.slf4j.LoggerFactory
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters.CollectionHasAsScala
import zio.Scope
import zio.UIO
import zio.ZIO
import zio.test.*
import zio.test.Assertion.*
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class AbstractSchedulerSpec extends ZIOSpecDefault {

  def captureLogger(name: String, level: Level): ZIO[Scope, Nothing, LogCapture] = {
    val logger: Logger = LoggerFactory.getLogger(name).asInstanceOf[Logger]
    logger.setLevel(level)
    LogCapture().start(logger).withFinalizer(_.stop(logger))
  }

  class LogCapture  {
    val listAppender = new ListAppender[ILoggingEvent]();
    def logs:                  Iterable[String] = listAppender.list.asScala.map(_.toString)
    def start(logger: Logger): UIO[LogCapture]  = ZIO.succeed {
      listAppender.start()
      logger.addAppender(listAppender)
      this
    }
    def stop(logger: Logger):  UIO[Unit]        = ZIO.succeed {
      logger.detachAppender(listAppender)
      listAppender.stop()
    }
  }
  object LogCapture {
    def apply(): LogCapture = new LogCapture
  }

  case class TestScheduler(interval: Duration) extends AbstractScheduler {
    override type T = Int
    override def updateInterval: Duration         = interval
    override def executeTask:    Long => Box[Int] = _ => Full(12)
    override def displayName:    String           = "test scheduler"
    override def propertyName:   String           = "prop"
  }

  val spec = suite("AbstractSchedulerSpec")(
    test("should log scheduling period when instantiated with a value in the right range") {
      for {
        capture  <- captureLogger("scheduled.job", Level.INFO)
        scheduler = TestScheduler(interval = Duration.apply("5 seconds"))
      } yield {
        assert(capture.logs)(contains("[INFO] Starting [test scheduler] scheduler with a period of '5 seconds'"))
      }
    },
    test("should log simplified scheduling period when instantiated with a value in the right range") {
      for {
        capture  <- captureLogger("scheduled.job", Level.INFO)
        scheduler = TestScheduler(interval = Duration.apply("5000000000 nanoseconds"))
      } yield {
        assert(capture.logs)(contains("[INFO] Starting [test scheduler] scheduler with a period of '5 seconds'"))
      }
    },
    test("should log period is too high when instantiated with an out of range value") {
      for {
        capture  <- captureLogger("scheduled.job", Level.INFO)
        scheduler = TestScheduler(interval = Duration.apply("10 minutes"))
      } yield {
        assert(capture.logs)(
          contains("[WARN] Value '10 minutes' for prop is too big for [test scheduler] scheduler interval, using '5 minutes'")
        )
      }
    },
    test("should log simplified scheduling period when instantiated with an out of range value") {
      for {
        capture  <- captureLogger("scheduled.job", Level.INFO)
        scheduler = TestScheduler(interval = Duration.apply("600 seconds"))
      } yield {
        assert(capture.logs)(
          contains("[WARN] Value '10 minutes' for prop is too big for [test scheduler] scheduler interval, using '5 minutes'")
        )
      }
    },
    test("should log period is too low when instantiated with an out of range value") {
      for {
        capture  <- captureLogger("scheduled.job", Level.INFO)
        scheduler = TestScheduler(interval = Duration.apply("100 ms"))
      } yield {
        assert(capture.logs)(
          contains(
            "[WARN] Value '100 milliseconds' for prop is too small for [test scheduler] scheduler interval, using '1 second'"
          )
        )
      }
    },
    test("should log simplified scheduling period when instantiated with an low out of range value") {
      for {
        capture  <- captureLogger("scheduled.job", Level.INFO)
        scheduler = TestScheduler(interval = Duration.apply("100000000 nanoseconds"))
      } yield {
        assert(capture.logs)(
          contains(
            "[WARN] Value '100 milliseconds' for prop is too small for [test scheduler] scheduler interval, using '1 second'"
          )
        )
      }
    }
  ) @@ TestAspect.sequential // logger capture behaves differently when tests are run in parallel
}
