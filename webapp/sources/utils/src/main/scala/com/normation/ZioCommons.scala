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

import java.io.FileInputStream
import java.net.URL
import java.util

import scalaz.zio._
import scalaz.zio.syntax._
import com.normation.zio.ZioRuntime
import net.liftweb.common._
import cats._
import cats.data._
import cats.implicits._
import com.normation.errors.IOResult

/**
 * This is our based error for Rudder. Any method that can
 * error should return that RudderError type to allow
 * seemless interaction between modules.
 * None the less, all module should have its own domain error
 * for meaningful semantic intra-module.
 */
object errors {


  type PureResult[T] = Either[RudderError, T]
  type IOResult[T] = ZIO[Any, RudderError, T]

  object IOResult {
    def effect[A](error: String)(effect: => A): IOResult[A] = {
      IO.effect(effect).mapError(ex => SystemError(error, ex))
    }
    def effect[A](effect: => A): IOResult[A] = {
      this.effect("An error occured")(effect)
    }
    def effectM[A](error: String)(ioeffect: => IOResult[A]): IOResult[A] = {
      IO.effect(ioeffect).foldM(
        ex  => SystemError(error, ex).fail
      , res => res
      )
    }
    def effectM[A](ioeffect: => IOResult[A]): IOResult[A] = {
      effectM("An error occured")(ioeffect)
    }
  }

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

  // a generic error to tell "I wasn't expecting that value"
  final case class Unexpected(msg: String) extends RudderError

  // a generic error to tell "there is some (business logic related) unconsistancy"
  final case class Unconsistancy(msg: String) extends RudderError

  trait BaseChainError[E <: RudderError] extends RudderError {
    def cause: E
    def hint: String
    def msg = s"${hint}; cause was: ${cause.fullMsg}"
  }

  final case class Chained[E <: RudderError](hint: String, cause: E) extends BaseChainError[E] {
    override def fullMsg: String = msg
  }

  /*
   * Chain multiple error. You will loose the specificity of the
   * error type doing so.
   */
  implicit class IOChainError[R, E <: RudderError, A](res: ZIO[R, E, A]) {
    def chainError(hint: String): ZIO[R, RudderError, A] = res.mapError(err => Chained(hint, err))
  }

  implicit class PureChainError[R, E <: RudderError, A](res: Either[E, A]) {
    def chainError(hint: String): Either[RudderError, A] = res.leftMap(err => Chained(hint, err))
  }

  /*
   * A mapper from PureResult to IOResult
   */
  implicit class PureToIoResult[A](res: PureResult[A]) {
    def toIO: IOResult[A] = ZIO.fromEither(res)
  }

  // not optional - mandatory presence of an object
  implicit class OptionToIoResult[A](res: Option[A]) {
    def notOptional(error: String) = res match {
      case None    => Unconsistancy(error).fail
      case Some(x) => x.succeed
    }
  }

  // also with the flatmap included to avoid a combinator
  implicit class MandatoryOptionIO[R, E <: RudderError, A](res: ZIO[R, E, Option[A]]) {
    def notOptional(error: String) = res.flatMap( _.notOptional(error))
  }

  /**
   *  Some box compatibility methods
   */


  import net.liftweb.common.{Box, Empty, EmptyBox, Failure, Full}

  object BoxUtil {

    def fold[E <: RudderError, A, F[_]: cats.Monad](error: RudderError => F[A], success: A => F[A])(box: Box[A]): F[A] = {
      def toFail(f: Failure): RudderError = {
        (f.chain, f.exception) match {
          case (Full(parent), _) => Chained(f.msg, toFail(f))
          case (_, Full(ex))     => SystemError(f.messageChain, ex)
          case _                 => Unexpected(f.messageChain)
        }
      }

      box match {
        case Full(x)   => success(x)
        case Empty     => error(Unexpected("no context was provided"))

        // given how Failure are built in Lift/Rudder, we never have both an Full(exception) and
        // a Full(parent). If it happens nonetheless, we keep parent (which is likely to have ex)
        case f:Failure => error(toFail(f))
      }
    }
  }

  implicit class BoxToEither[E <: RudderError, A](res: Box[A]) {
    import cats.instances.either._
    def toPureResult: PureResult[A] = BoxUtil.fold[E, A, PureResult](
      err => Left(err)
    , suc => Right(suc)
    )(res)
  }

  implicit class BoxToIO[E <: RudderError, A](res: Box[A]) {
    import scalaz.zio.interop.catz._
    def toIO: IOResult[A] = BoxUtil.fold[E, A, IOResult](
      err => err.fail
    , suc => suc.succeed
    )(res)
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
  object ZioRuntime extends DefaultRuntime {
     def runNow[A](io: IOResult[A]): A = {
      ZioRuntime.unsafeRunSync(io).fold(cause => throw cause.squashWith(err => new RuntimeException(err.fullMsg)), a => a)
    }
  }

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

  /*
   * When porting a class is too hard
   */
  implicit class UnsafeRun[A](io: IOResult[A]) {
    def runNow: A = ZioRuntime.runNow(io)
  }

  /*
   * Opposite to "toIO"
   */
  implicit class IOToBox[A](io: IOResult[A]) {
    def toBox: Box[A] = try {
      Full(ZioRuntime.runNow(io))
    } catch {
      case ex => new Failure(ex.getMessage, Full(ex), Empty)
    }
  }

}

/*
 * A logger interface for logger with "fire and forget" semantic for effects
 */
trait ZioLogger {

  //the underlying logger
  def logEffect: Logger
  def logAndForgetResult[T](log: Logger => T): UIO[Unit] = ZIO.effect(log(logEffect)).run.void

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
trait NamedZioLogger extends ZioLogger {
  import org.slf4j.LoggerFactory
  import net.liftweb.common.Logger

  def loggerName: String

  lazy val logEffect = new Logger() {
    override protected def _logger = LoggerFactory.getLogger(loggerName)
  }
  // for compatibility with current Lift convention, use logger = this
  def logPure = this
}

object NamedZioLogger {
  def apply(name: String): NamedZioLogger = new NamedZioLogger(){val loggerName = name}
}

object TestSream {

  val log = NamedZioLogger("test-logger")


  def main(args: Array[String]): Unit = {
    val prog =
      log.error("wouhou") *>
      ZIO.bracket(Task.effect{
      val checkRelativePath = "file:///tmp/plop.txt"
      val url = new URL(checkRelativePath)
      url.openStream()
    })(is =>
      Task.effect(is.close).run // here, if I put `UIO.unit`, I can have the content
    )(is =>
      Task.effect(println(new String(is.readAllBytes(), "utf-8") ))
    ) <* log.warn("some plop plop")
    ZioRuntime.unsafeRun(prog)
    // A checked error was not handled:
    //  java.base/java.io.BufferedInputStream.getBufIfOpen(BufferedInputStream.java:176)
    // IOException("Stream closed");
  }

}

object TestLog {

  val log = NamedZioLogger("test-logger")

  def main(args: Array[String]): Unit = {


    def oups: IO[String, Int] = "oups error".fail

    val prog = oups.catchAll(e => log.error("I got an error!") *> e.fail) *> UIO.succeed(42)

    ZioRuntime.unsafeRun(prog)
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
