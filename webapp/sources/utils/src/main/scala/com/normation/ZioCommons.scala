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
import com.normation.zio.ZioRuntime
import net.liftweb.common.Logger

/**
 * This is our based error for Rudder. Any method that can
 * error should return that RudderError type to allow
 * seemless interaction between modules.
 * None the less, all module should have its own domain error
 * for meaningful semantic intra-module.
 */
object errors {
  trait RudderError {
    // All error have a message which explains what cause the error.
    def msg: String

    // All error can have their message printed with the class name for
    // for context.
    def fullMsg = this.getClass.getSimpleName + ": " + msg
  }

  // a common error for system error not specificaly bound to
  // a domain context.
  final case class SystemError(msg: String, cause: Throwable) extends RudderError

  trait BaseChainError[E <: RudderError] extends RudderError {
    def cause: E
    def hint: String
    def msg = s"${hint}; cause was: ${cause.fullMsg}"
  }

  final case class Chained[E <: RudderError](hint: String, cause: E) extends BaseChainError[E]

  /*
   * Chain multiple error. You will loose the specificity of the
   * error type doing so.
   */
  implicit class ChainError[R, E <: RudderError, A](res: ZIO[R, E, A]) {
    def chainError(hint: String): ZIO[R, RudderError, A] = res.mapError(err => Chained(hint, err))
  }
}

object TestImplicits {
  import scalaz.zio._
  import scalaz.zio.syntax._

  object module1 {
    import com.normation.errors._
    sealed trait M_1_Error extends RudderError
    object M_1_Error {
      final case class Some(msg: String) extends M_1_Error
      final case class Chained[E <: RudderError](hint: String, cause: E) extends M_1_Error with BaseChainError[E]
    }

    object M_1_Result {
    }

    object service1 {
      def doStuff(param: String): IO[RudderError, String] = param.succeed
    }
  }

  object module2 {
    import com.normation.errors._
    sealed trait M_2_Error extends RudderError
    object M_2_Error {
      final case class Some(msg: String) extends M_2_Error
      final case class Chained[E <: RudderError](hint: String, cause: E) extends M_2_Error with BaseChainError[E]
    }
    object M_2_Result {
    }

    final case class TestError(msg: String) extends RudderError

    object service2 {
      def doStuff(param: Int): IO[RudderError, Int] = TestError("ah ah ah I'm failing").fail
    }
  }

  object testModule {
    import module1._
    import module2._

    import com.normation.errors._
    sealed trait M_3_Error extends RudderError
    object M_3_Error {
      final case class Some(msg: String) extends M_3_Error
      final case class Chained[E <: RudderError](hint: String, cause: E) extends M_3_Error with BaseChainError[E]
    }
    object M_3_Result {
      // implicits ?
    }

    import module1.M_1_Result._
    import module2.M_2_Result._
    import M_3_Result._

    /*
     * I would like all of that to be possible, with the minimum boilerplate,
     * and the maximum homogeneity between modules
     */
    object service {

      def trace(msg: => AnyRef): UIO[Unit] = ZIO.effect(println(msg)).run.void

      def test0(a: String): IO[RudderError, String] = service1.doStuff(a)

      def test1(a: Int): IO[RudderError, Int] = service2.doStuff(a)

      def test2(a: String, b: Int): IO[RudderError, (String, Int)] = {
        (for {
          x <- service1.doStuff(a)
          y <- service2.doStuff(b).chainError("Oups, I did it again")
        } yield {
          (x, y)
        })
      }

      def prog = test2("plop", 42) catchAll( err => trace(err.fullMsg) *> ("success", 0).succeed)
    }
  }


  import testModule.service.prog
  def main(args: Array[String]): Unit = {
    println(ZioRuntime.unsafeRun(prog))
  }
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

//
//object Test {
//  import zio._
//  import scalaz.zio._
//  import scalaz.zio.syntax._
//  import cats.implicits._
//
//  def main(args: Array[String]): Unit = {
//
//    val l = List("ok", "ok", "booo", "ok", "plop")
//
//    def f(s: String) = if(s == "ok") 1.succeed else s.fail
//
//    val res = IO.foreach(l) { s => f(s).either }
//
//    val res2 = for {
//      ll <- res
//    } yield {
//      ll.traverse( _.toValidatedNel)
//    }
//
//    println(ZioRuntime.unsafeRun(res))
//    println(ZioRuntime.unsafeRun(res2))
//    println(ZioRuntime.unsafeRun(l.accumulate(f)))
//
//  }
//
//}
//
//object Test2 {
//  import zio._
//  import scalaz.zio._
//  import scalaz.zio.syntax._
//  import cats.implicits._
//
//  def main(args: Array[String]): Unit = {
//
//    case class BusinessError(msg: String)
//
//    val prog1 = Task.effect {
//      val a = "plop"
//      val b = throw new RuntimeException("foo bar bar")
//      val c = "replop"
//      a + c
//    } mapError(e => BusinessError(e.getMessage))
//
//    val prog2 = Task.effect {
//      val a = "plop"
//      val b = throw new Error("I'm an java.lang.Error!")
//      val c = "replop"
//      a + c
//    } mapError(e => BusinessError(e.getMessage))
//
//    //println(ZioRuntime.unsafeRun(prog1))
//    println(ZioRuntime.unsafeRun(prog2))
//
//  }
//
//}
