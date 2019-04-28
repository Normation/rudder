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

import java.net.URL

import scalaz.zio._
import scalaz.zio.syntax._
import com.normation.zio.ZioRuntime
import net.liftweb.common._
import cats.data._
import cats.implicits._
import com.normation.errors.IOResult
import com.normation.errors.RudderError
import scalaz.zio._
import scalaz.zio.internal.PlatformLive.ExecutorUtil

import scala.util.control.NonFatal

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

    def effectRunUnit[A](effect: => A): UIO[Unit] = {
      def printError(t: Throwable): UIO[Unit] = {
        val print = (s:String) => IO.effect(System.err.println(s))
        //here, we must run.void, because if it fails we can't do much more (and the app is certainly totally broken)
        (print(s"${t.getClass.getName}:${t.getMessage}") *> IO.foreach(t.getStackTrace)(st => print(st.toString))).run.unit
      }
      IO.effect(effect).unit.catchAll(printError)
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
  final case class SystemError(msg: String, cause: Throwable) extends RudderError {
    override def fullMsg: String = super.fullMsg + s"; cause was: ${cause.getMessage}"
  }

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

  final case class Accumulated[E <: RudderError](all: NonEmptyList[E]) extends RudderError {
    def msg = all.map(_.fullMsg).toList.mkString(" ; ")
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
   * Accumulate results of a ZIO execution in a ValidateNel
   */
  implicit class AccumulateErrorsNEL[A](in: Iterable[A]) {
    def accumulateNEL[R, E, B](f: A => ZIO[R, E, B]): ZIO[R, NonEmptyList[E], List[B]] = {
      ZIO.foreach(in){ x => f(x).either }.flatMap { list =>
        val accumulated = list.traverse( _.toValidatedNel)
        ZIO.fromEither(accumulated.toEither)
      }
    }
  }

  /*
   * And a version that translate it to RudderError Accumulated.
   */
  implicit class AccumulateErrors[A](in: Iterable[A]) {
    def accumulate[R, E <: RudderError, B](f: A => ZIO[R, E, B]): ZIO[R, Accumulated[E], List[B]] = {
      in.accumulateNEL(f).mapError(errors => Accumulated(errors))
    }
  }

  /**
   *  Some box compatibility methods
   */


  import net.liftweb.common.{Box, Empty, Failure, Full}

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

  implicit class BoxToIO[E <: RudderError, A](res: => Box[A]) {
    import scalaz.zio.interop.catz._
    def toIO: IOResult[A] = IOResult.effect(res).flatMap(x => BoxUtil.fold[E, A, IOResult](
      err => err.fail
    , suc => suc.succeed
    )(x))
  }
}

object zio {

  /*
   * Default ZIO Runtime used everywhere.
   */
  object ZioRuntime extends DefaultRuntime {
    import java.util.{ WeakHashMap, Map => JMap }
    import scalaz.zio.internal._
    import scalaz.zio.Exit._
    import scalaz.zio.Exit.Cause._
    /*
     * We need to overide the plateform to define "reportFailure" and get
     * somthing usefull when an uncaught error happens.
     */
    override val Platform: Platform = new Platform {
      val executor: Executor                   = ExecutorUtil.makeDefault()
      def fatal(t: Throwable): Boolean         = t.isInstanceOf[VirtualMachineError]
      def reportFailure(cause: Cause[_]): Unit = {
        cause match {
          case Interrupt   => () // nothgin to print
          case f @ Fail(_) => println(f)
          case Die(ex)     => ex.printStackTrace()
          case Both(l, r)  => reportFailure(l); reportFailure(r)
          case Then(l, r)  => reportFailure(l); reportFailure(r)
        }
      }
      def newWeakHashMap[A, B](): JMap[A, B]   = new WeakHashMap[A, B]()
    }

    def runNow[A](io: IOResult[A]): A = {
      this.unsafeRunSync(io).fold(cause => throw cause.squashWith(err => new RuntimeException(err.fullMsg)), a => a)
    }
  }



  /*
   * When porting a class is too hard
   */
  implicit class UnsafeRun[A](io: IOResult[A]) {
    def runNow: A = ZioRuntime.runNow(io)
  }

}

/*
 * Implicit classes to change IO and Either TOWARDS box
 */
object box {

  import com.normation.zio.ZioRuntime

  /*
   * Opposite to "toIO"
   */
  implicit class IOToBox[A](io: IOResult[A]) {
    def toBox: Box[A] = try {
      Full(ZioRuntime.runNow(io))
    } catch {
      case NonFatal(ex) => new Failure(ex.getMessage, Full(ex), Empty)
    }
  }

  /*
   * The same for either - not sure it should go there, but
   * we are likely to use both "toBox" in the same files
   */
  implicit class EitherToBox[E <: RudderError, A](either: Either[E, A]) {
    def toBox: Box[A] = either match {
      case Left(err) => Failure(err.fullMsg)
      case Right(x)  => Full(x)
    }
  }
}

/*
 * A logger interface for logger with "fire and forget" semantic for effects
 */
trait ZioLogger {

  //the underlying logger
  def logEffect: Logger

  /*
   * We don't want that errors happening during log be propagated to the app error channel,
   * because it doesn't have anything to do with app logic and an user won't know what to
   * do with them.
   * Nonetheless, we want to log these errors, because we must have a trace somewhere
   * that something went badly. Obviously, we won't use the logger for that.
   */
  final def logAndForgetResult[T](log: Logger => T): UIO[Unit] = {
    def printError(t: Throwable): UIO[Unit] = {
      val print = (s:String) => IO.effect(System.err.println(s))
      //here, we must run.void, because if it fails we can't do much more (and the app is certainly totally broken)
      (print(s"${t.getClass.getName}:${t.getMessage}") *> UIO.foreach(t.getStackTrace)(st => print(st.toString).run.unit)).run.unit
    }
    IO.effect(log(logEffect)).unit.catchAll(printError)
  }

  final def trace(msg: => AnyRef): UIO[Unit] = logAndForgetResult(_.trace(msg))
  final def debug(msg: => AnyRef): UIO[Unit] = logAndForgetResult(_.debug(msg))
  final def info (msg: => AnyRef): UIO[Unit] = logAndForgetResult(_.info (msg))
  final def error(msg: => AnyRef): UIO[Unit] = logAndForgetResult(_.error(msg))
  final def warn (msg: => AnyRef): UIO[Unit] = logAndForgetResult(_.warn (msg))

  final def trace(msg: => AnyRef, t: Throwable): UIO[Unit] = logAndForgetResult(_.trace(msg, t))
  final def debug(msg: => AnyRef, t: Throwable): UIO[Unit] = logAndForgetResult(_.debug(msg, t))
  final def info (msg: => AnyRef, t: Throwable): UIO[Unit] = logAndForgetResult(_.info (msg, t))
  final def warn (msg: => AnyRef, t: Throwable): UIO[Unit] = logAndForgetResult(_.warn (msg, t))
  final def error(msg: => AnyRef, t: Throwable): UIO[Unit] = logAndForgetResult(_.error(msg, t))

  final def ifTraceEnabled[T](action: UIO[T]): UIO[Unit] = logAndForgetResult(logger => if(logger.isTraceEnabled) action else () )
  final def ifDebugEnabled[T](action: UIO[T]): UIO[Unit] = logAndForgetResult(logger => if(logger.isDebugEnabled) action else () )
  final def ifInfoEnabled [T](action: UIO[T]): UIO[Unit] = logAndForgetResult(logger => if(logger.isInfoEnabled ) action else () )
  final def ifWarnEnabled [T](action: UIO[T]): UIO[Unit] = logAndForgetResult(logger => if(logger.isWarnEnabled ) action else () )
  final def ifErrorEnabled[T](action: UIO[T]): UIO[Unit] = logAndForgetResult(logger => if(logger.isErrorEnabled) action else () )
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
