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


import net.liftweb.common.{Logger => _, _}
import cats.data._
import cats.implicits._
import cats.kernel.Order
import com.normation.errors.Chained
import com.normation.errors.IOResult
import com.normation.errors.RudderError
import com.normation.errors.SystemError
import _root_.zio._
import _root_.zio.syntax._
import com.normation.zio.ZioRuntime
import org.slf4j.Logger

/**
 * This is our based error for Rudder. Any method that can
 * error should return that RudderError type to allow
 * seemless interaction between modules.
 * None the less, all module should have its own domain error
 * for meaningful semantic intra-module.
 */
object errors {

  /*
   * Two methods which helps to transform effect to `UIO[Unit]` (ie don't care
   * about the result type, and manage all errors in some way).
   * This is particularly needed in `Bracket` construction where the finalizer
   * must be of that type.
   */
  def effectUioUnit[A](effect: => A): UIO[Unit] = {
    def printError(t: Throwable): UIO[Unit] = {
      val print = (s:String) => IO.effect(System.err.println(s))
      //here, we must run.unit, because if it fails we can't do much more (and the app is certainly totally broken)
      (print(s"${t.getClass.getName}:${t.getMessage}") *> IO.foreach(t.getStackTrace)(st => print(st.toString))).run.unit
    }
    effectUioUnit(printError(_))(effect)
  }
  def effectUioUnit[A](error: Throwable => UIO[Unit])(effect: => A): UIO[Unit] = {
    ZioRuntime.effectBlocking(effect).unit.catchAll(error)
  }

  /*
   * Our result types are isomorphique to a disjoint RudderError | A
   * one. We have two case: one for which we are sure that all
   * operation are pure and don't modelize effects (that can be fully
   * reiffied at compule time), and one for effect encapsulation (that need to
   * be runned to know the result).
   */
  type PureResult[A] = Either[RudderError, A]
  type IOResult[A] = ZIO[Any, RudderError, A]

  /*
   * An object that provides utility methods to import effectful
   * methods into rudder IOResult type.
   * By default, we consider that all imports have blocking
   * effects, and need to be run on an according threadpool.
   * If you want to import non blocking effects (but really, in
   * that case, you should just use `PureResult`), you can
   * use `IOResult.effectTotal`).
   */
  object IOResult {
    def effectNonBlocking[A](error: String)(effect: => A): IO[SystemError, A] = {
      IO.effect(effect).mapError(ex => SystemError(error, ex))
    }
    def effectNonBlocking[A](effect: => A): IO[SystemError, A] = {
      this.effectNonBlocking("An error occured")(effect)
    }
    def effect[A](error: String)(effect: => A): IO[SystemError, A] = {
      ZioRuntime.effectBlocking(effect).mapError(ex => SystemError(error, ex))
    }
    def effect[A](effect: => A): IO[SystemError, A] = {
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

  object RudderError {

    /*
     * Display information about an exception of interest for the developpers without being
     * too nasty for users.
     */
    def formatException(cause: Throwable): String = {
      // display at max 3 stack trace from 'com.normation'. That should give plenty information for
      // dev, which are relevant to understand where the problem is, and without destroying logs
      val stack = cause.getStackTrace.filter(_.getClassName.startsWith("com.normation")).take(3).map(_.toString).mkString("\n -> ", "\n -> ", "")
      s"${cause.getClass.getName}: ${cause.getMessage} ${stack}"
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
    override def fullMsg: String = super.fullMsg + s"; cause was: ${RudderError.formatException(cause)}"
  }

  // a generic error to tell "I wasn't expecting that value"
  final case class Unexpected(msg: String) extends RudderError

  // a generic error to tell "there is some (business logic related) inconsistancy"
  final case class Inconsistancy(msg: String) extends RudderError

  trait BaseChainError[E <: RudderError] extends RudderError {
    def cause: E
    def hint: String
    def msg = s"${hint}; cause was: ${cause.fullMsg}"
  }

  final case class Chained[E <: RudderError](hint: String, cause: E) extends BaseChainError[E] {
    override def fullMsg: String = msg
  }

  final case class Accumulated[E <: RudderError](all: NonEmptyList[E]) extends RudderError {
    implicit val ord = new Order[E]() {
      override def compare(x: E, y: E): Int = String.CASE_INSENSITIVE_ORDER.compare(x.fullMsg, y.fullMsg)
    }
    def msg = all.map(_.fullMsg).toList.mkString(" ; ")
    // only uniq error
    def deduplicate = {
      Accumulated(all.distinct)
    }
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
      case None    => Inconsistancy(error).fail
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
    private def transform[R, E, B](seq: ZIO[R, Nothing, List[Either[E, B]]]) = {
      seq.flatMap { list =>
        val accumulated = list.traverse( _.toValidatedNel)
        ZIO.fromEither(accumulated.toEither)
      }
    }

    /*
     * Execute sequentially and accumulate errors
     */
    def accumulateNEL[R, E, B](f: A => ZIO[R, E, B]): ZIO[R, NonEmptyList[E], List[B]] = {
      transform(ZIO.foreach(in){ x => f(x).either })
    }

    /*
     * Execute in parallel, non ordered, and accumulate error, using at max N fibers
     */
    def accumulateParNELN[R, E, B](n: Long)(f: A => ZIO[R, E, B]): ZIO[R, NonEmptyList[E], List[B]] = {
      transform(ZIO.foreachParN(n)(in){ x => f(x).either })
    }
  }

  /*
   * And a version that translate it to RudderError Accumulated.
   */
  implicit class AccumulateErrors[A](in: Iterable[A]) {
    /*
     * Execute sequentially and accumulate errors
     */
    def accumulate[R, E <: RudderError, B](f: A => ZIO[R, E, B]): ZIO[R, Accumulated[E], List[B]] = {
      in.accumulateNEL(f).mapError(errors => Accumulated(errors))
    }
    /*
     * Execute in parallel, non ordered, and accumulate error, using at max N fibers
     */
    def accumulateParN[R, E <: RudderError, B](n: Long)(f: A => ZIO[R, E, B]): ZIO[R, Accumulated[E], List[B]] = {
      in.accumulateParNELN(n)(f).mapError(errors => Accumulated(errors))
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
          case (Full(parent), _) => Chained(f.msg, toFail(parent))
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
    import _root_.zio.interop.catz._
    def toIO: IOResult[A] = IOResult.effect(res).flatMap(x => BoxUtil.fold[E, A, IOResult](
      err => err.fail
    , suc => suc.succeed
    )(x))
  }
}

object zio {

  val currentTimeMillis = UIO.effectTotal(System.currentTimeMillis())

  /*
   * Default ZIO Runtime used everywhere.
   */
  object ZioRuntime {
    /*
     * Internal runtime. You should not access it within rudder.
     * If you need to use it for "unsafeRun", you should alway pin the
     * IO into an async thread pool to avoid deadlock in case of
     * a hierarchy of calls.
     */
    val internal = new DefaultRuntime(){}

    /*
     * use the blocking thread pool provided by that runtime.
     */
    def blocking[E,A](io: ZIO[Any,E,A]): ZIO[Any,E,A] = {
      _root_.zio.blocking.blocking(io).provide(internal.Environment)
    }

    def effectBlocking[A](effect: => A): ZIO[Any, Throwable, A] = {
      _root_.zio.blocking.effectBlocking(effect).provide(internal.Environment)
    }

    def runNow[A](io: IOResult[A]): A = {
      internal.unsafeRunSync(blocking(io)).fold(cause => throw cause.squashWith(err => new RuntimeException(err.fullMsg)), a => a)
    }

    /*
     * An unsafe run that is always started on a growing threadpool and its
     * effect marked as blocking.
     */
    def unsafeRun[E, A](zio: => ZIO[Any, E, A]): A = internal.unsafeRun(blocking(zio))

    def Environment = internal.Environment
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
    def errToFailure(err: RudderError): Failure = {
      err match {
        case Chained(msg, cause ) => new Failure(msg, Empty, Full(errToFailure(cause)))
        case SystemError(msg, ex) => new Failure(msg, Full(ex), Empty)
        case other                => new Failure(other.fullMsg, Empty, Empty)
      }
    }
    def toBox: Box[A] = ZioRuntime.runNow(io.either) match {
      case Right(x)  => Full(x)
      case Left(err) => errToFailure(err)
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
    com.normation.errors.effectUioUnit(log(logEffect))
  }

  final def trace(msg: => String): UIO[Unit] = logAndForgetResult(_.trace(msg))
  final def debug(msg: => String): UIO[Unit] = logAndForgetResult(_.debug(msg))
  final def info (msg: => String): UIO[Unit] = logAndForgetResult(_.info (msg))
  final def error(msg: => String): UIO[Unit] = logAndForgetResult(_.error(msg))
  final def warn (msg: => String): UIO[Unit] = logAndForgetResult(_.warn (msg))

  final def trace(msg: => String, t: Throwable): UIO[Unit] = logAndForgetResult(_.trace(msg, t))
  final def debug(msg: => String, t: Throwable): UIO[Unit] = logAndForgetResult(_.debug(msg, t))
  final def info (msg: => String, t: Throwable): UIO[Unit] = logAndForgetResult(_.info (msg, t))
  final def warn (msg: => String, t: Throwable): UIO[Unit] = logAndForgetResult(_.warn (msg, t))
  final def error(msg: => String, t: Throwable): UIO[Unit] = logAndForgetResult(_.error(msg, t))

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

  // ensure that children use def or lazy val - val leads to UnitializedFieldError.
  def loggerName: String

  final val logEffect = LoggerFactory.getLogger(loggerName)

  // for compatibility with current Lift convention, use logger = this
  def logPure = this
}

object NamedZioLogger {
  def apply(name: String): NamedZioLogger = new NamedZioLogger(){def loggerName = name}
}
