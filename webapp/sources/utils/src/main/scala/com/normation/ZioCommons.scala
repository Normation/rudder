/*
*************************************************************************************
* Copyright 2019 Normation SAS
*************************************************************************************
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
*************************************************************************************
*/


/*
 * This class provides common usage for Zio
 */

package com.normation

import cats.data.NonEmptyList
import scalaz.zio._
import cats.implicits._
import net.liftweb.common.Logger


object errors {
  trait RudderError {
    def msg: String
  }

  // we most likely want to track the class name / parent object name to
  // be able to contextualize what the message is about.
  // Ttypically, in: object UserError { final case class NoFound(user: User) }
  // You want to have "UserError.NotFound: paul was not found"

}

object zio {

  /*
   * Default ZIO Runtime used everywhere.
   */
  object ZioRuntime extends DefaultRuntime

  /**
   * Accumulate results of a ZIO execution in a ValidateNel
   */
  implicit class Accumulate[A](in: Iterable[A]) {
    def accumulate[R, E, B](f: A => ZIO[R, E, B]): ZIO[R, NonEmptyList[E], List[B]] = {
      ZIO.foreach(in){ x => f(x).either }.flatMap { list =>
        val accumulated = list.traverse( _.toValidatedNel)
        ZIO.fromEither(accumulated.toEither)
      }
    }
  }


}

/*
 * A logger interface for logger with "fire and forget" semantic for effects
 */
trait ZioLogger {

  //the underlying logger
  def internalLogger: Logger
  def logAndForgetResult[T](log: Logger => T): UIO[Unit] = ZIO.effect(log(internalLogger)).run.void

  def trace(msg: => AnyRef): UIO[Unit] = logAndForgetResult(_.trace(msg))
  def debug(msg: => AnyRef): UIO[Unit] = logAndForgetResult(_.debug(msg))
  def info (msg: => AnyRef): UIO[Unit] = logAndForgetResult(_.info (msg))
  def error(msg: => AnyRef): UIO[Unit] = logAndForgetResult(_.error(msg))
  def warn (msg: => AnyRef): UIO[Unit] = logAndForgetResult(_.warn (msg))

  def trace(msg: => AnyRef, t: Throwable): UIO[Unit] = logAndForgetResult(_.trace(msg, t))
  def debug(msg: => AnyRef, t: Throwable): UIO[Unit] = logAndForgetResult(_.debug(msg, t))
  def info (msg: => AnyRef, t: Throwable): UIO[Unit] = logAndForgetResult(_.info (msg, t))
  def warn (msg: => AnyRef, t: Throwable): UIO[Unit] = logAndForgetResult(_.warn (msg, t))
  def error(msg: => AnyRef, t: Throwable): UIO[Unit] = logAndForgetResult(_.error(msg, t))

}

// a default implementation that accepts a name for the logger.
// it will respect slf4j namespacing with ".".
abstract class NamedZioLogger(val loggerName: String) extends ZioLogger {
  import org.slf4j.LoggerFactory
  import net.liftweb.common.Logger
  val internalLogger = new Logger() {
    override protected def _logger = LoggerFactory.getLogger(loggerName)
  }
}


object Test {
  import zio._
  import scalaz.zio._
  import scalaz.zio.syntax._
  import cats.implicits._

  def main(args: Array[String]): Unit = {

    val l = List("ok", "ok", "booo", "ok", "plop")

    def f(s: String) = if(s == "ok") 1.succeed else s.fail

    val res = IO.foreach(l) { s => f(s).either }

    val res2 = for {
      ll <- res
    } yield {
      ll.traverse( _.toValidatedNel)
    }

    println(ZioRuntime.unsafeRun(res))
    println(ZioRuntime.unsafeRun(res2))
    println(ZioRuntime.unsafeRun(l.accumulate(f)))

  }

}

object Test2 {
  import zio._
  import scalaz.zio._
  import scalaz.zio.syntax._
  import cats.implicits._

  def main(args: Array[String]): Unit = {

    case class BusinessError(msg: String)

    val prog1 = Task.effect {
      val a = "plop"
      val b = throw new RuntimeException("foo bar bar")
      val c = "replop"
      a + c
    } mapError(e => BusinessError(e.getMessage))

    val prog2 = Task.effect {
      val a = "plop"
      val b = throw new Error("I'm an java.lang.Error!")
      val c = "replop"
      a + c
    } mapError(e => BusinessError(e.getMessage))

    //println(ZioRuntime.unsafeRun(prog1))
    println(ZioRuntime.unsafeRun(prog2))

  }


}
