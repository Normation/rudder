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

import _root_.zio._
import _root_.zio.stream._
import _root_.zio.syntax._
import cats.data._
import cats.implicits._
import cats.kernel.Order
import com.normation.errors.Chained
import com.normation.errors.IOResult
import com.normation.errors.PureResult
import com.normation.errors.RudderError
import com.normation.errors.SystemError
import com.normation.errors.effectUioUnit
import java.util.concurrent.TimeUnit
import net.liftweb.common.{Logger => _, _}
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
  def effectUioUnit[A](effect: => A):                                UIO[Unit] = {
    def printError(t: Throwable): UIO[Unit] = {
      val print = (s: String) => ZIO.attempt(java.lang.System.err.println(s))
      // here, we must unit.orDie, because if it fails we can't do much more (and the app is certainly totally broken)
      (print(s"${t.getClass.getName}:${t.getMessage}") *> ZIO.foreach(t.getStackTrace.toList)(st =>
        print(st.toString)
      )).unit.orDie
    }
    effectUioUnit(printError(_))(effect)
  }
  def effectUioUnit[A](error: Throwable => UIO[Unit])(effect: => A): UIO[Unit] = {
    ZIO.attempt(effect).unit.catchAll(error)
  }

  /*
   * Our result types are isomorphic to a disjoint RudderError | A
   * one. We have two case: one for which we are sure that all
   * operation are pure and don't model effects (that can be fully
   * reified at compile time), and one for effect encapsulation (that need to
   * be ran to know the result).
   */
  type PureResult[A] = Either[RudderError, A]
  type IOResult[A]   = ZIO[Any, RudderError, A]
  type IOStream[A]   = ZStream[Any, RudderError, A]

  /*
   * An object that provides utility methods to import effectfull
   * methods into rudder IOResult type.
   * By default, we consider that all imports have blocking
   * effects, and need to be run on an according thread-pool.
   * If you want to import non blocking effects (but really, in
   * that case, you should just use `PureResult`), you can
   * use `IOResult.succeed`).
   */
  object IOResult {
    def attempt[A](error: String)(effect: => A):                IO[SystemError, A] = {
      // In ZIO 2 blocking is automagically managed - https://github.com/zio/zio/issues/1275
      ZIO.attempt(effect).mapError(ex => SystemError(error, ex))
    }
    def attempt[A](effect: => A):                               IO[SystemError, A] = {
      this.attempt("An error occurred")(effect)
    }
    def attemptZIO[A](error: String)(ioeffect: => IOResult[A]): IOResult[A]        = {
      ZIO
        .attempt(ioeffect)
        .foldZIO(
          ex => SystemError(error, ex).fail,
          res => res
        )
    }
    def attemptZIO[A](ioeffect: => IOResult[A]):                IOResult[A]        = {
      attemptZIO("An error occurred")(ioeffect)
    }
  }

  object PureResult {
    def attempt[A](error: String)(effect: => A):                Either[SystemError, A] = {
      try {
        Right(effect)
      } catch {
        case ex: Exception =>
          Left(SystemError(error, ex))
      }
    }
    def attempt[A](effect: => A):                               Either[SystemError, A] = {
      this.attempt("An error occurred")(effect)
    }
    def attemptZIO[A](error: String)(effect: => PureResult[A]): PureResult[A]          = {
      PureResult.attempt(error)(effect) match {
        case Left(err)  => Left(err)
        case Right(res) => res
      }
    }
    def attemptZIO[A](effect: => PureResult[A]):                PureResult[A]          = {
      this.attemptZIO("An error occurred")(effect)
    }
  }

  object RudderError {

    /*
     * Display information about an exception of interest for the developers without being
     * too nasty for users.
     */
    def formatException(cause: Throwable): String = {
      // display at max 3 stack trace from 'com.normation'. That should give plenty information for
      // dev, which are relevant to understand where the problem is, and without destroying logs
      val stack = cause.getStackTrace
        .filter(_.getClassName.startsWith("com.normation"))
        .take(100)
        .map(_.toString)
        .mkString("\n -> ", "\n -> ", "")
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

  // a common error for system error not specifically bound to
  // a domain context.
  final case class SystemError(msg: String, cause: Throwable) extends RudderError {
    override def fullMsg: String = super.fullMsg + s"; cause was: ${RudderError.formatException(cause)}"
  }

  // a generic error to tell "I wasn't expecting that value"
  final case class Unexpected(msg: String) extends RudderError

  // a generic error to tell "there is some (business logic related) Inconsistency"
  final case class Inconsistency(msg: String) extends RudderError

  trait BaseChainError[E <: RudderError] extends RudderError {
    def cause: E
    def hint:  String
    def msg = s"${hint}; cause was: ${cause.fullMsg}"
  }

  final case class Chained[E <: RudderError](hint: String, cause: E) extends BaseChainError[E] {
    override def fullMsg: String = msg
  }

  final case class Accumulated[E <: RudderError](all: NonEmptyList[E]) extends RudderError {
    implicit val ord: Order[E] = new Order[E]() {
      override def compare(x: E, y: E): Int = String.CASE_INSENSITIVE_ORDER.compare(x.fullMsg, y.fullMsg)
    }
    def msg = all.map(_.fullMsg).toList.mkString(" ; ")
    // only unique error
    def deduplicate = {
      Accumulated(all.distinct)
    }
  }

  /*
   * Chain multiple error. You will loose the specificity of the
   * error type doing so.
   */
  implicit class IOChainError[R, E <: RudderError, A](val res: ZIO[R, E, A]) extends AnyVal {
    def chainError(hint: => String): ZIO[R, RudderError, A] = res.mapError(err => Chained(hint, err))
  }

  implicit class PureChainError[R, E <: RudderError, A](val res: Either[E, A]) extends AnyVal {
    def chainError(hint: => String): Either[RudderError, A] = res.leftMap(err => Chained(hint, err))
  }

  /*
   * A mapper from PureResult to IOResult
   */
  implicit class PureToIoResult[A](val res: PureResult[A])       extends AnyVal {
    @inline
    def toIO: IOResult[A] = ZIO.fromEither(res)
  }
  /*
   * A mapper from Either[String, A] to IOResult. In that case, we assume the
   * left String is an error message.
   */
  implicit class EitherToIoResult[A](val res: Either[String, A]) extends AnyVal {
    @inline
    def toIO: IOResult[A] = ZIO.fromEither(res.left.map(Inconsistency(_)))
  }

  // not optional - mandatory presence of an object
  implicit class OptionToPureResult[A](val res: Option[A]) extends AnyVal {
    def notOptionalPure(error: => String) = res match {
      case None    => Left(Inconsistency(error))
      case Some(x) => Right(x)
    }
  }
  implicit class OptionToIoResult[A](val res: Option[A])   extends AnyVal {
    /*
     * Extract value or fail if the result is None.
     */
    def notOptional(error: => String):                                  IOResult[A]    = res match {
      case None    => Inconsistency(error).fail
      case Some(x) => x.succeed
    }
    /*
     * Check a predicate on an optional value, succeeding if the value is None.
     */
    def checkOptional(predicate: A => Boolean, msg: String => String):  IOResult[Unit] = {
      res match {
        case None    => ZIO.unit
        case Some(x) => if (predicate(x)) ZIO.unit else Inconsistency(msg(x.toString)).fail
      }
    }
    /*
     * Extract an optional value and then check a predicate on it, failing if predicate is not
     * met or if value is None.
     */
    def checkMandatory(predicate: A => Boolean, msg: String => String): IOResult[A]    = {
      res match {
        case None    => Inconsistency(msg("")).fail
        case Some(x) => if (predicate(x)) x.succeed else Inconsistency(msg(x.toString)).fail
      }
    }
  }

  // also with the flatmap included to avoid a combinator
  implicit class MandatoryOptionIO[R, E <: RudderError, A](val res: ZIO[R, E, Option[A]]) extends AnyVal {
    def notOptional(error: => String) = res.flatMap(_.notOptional(error))
  }

  /**
   * Accumulate results of a ZIO execution in a ValidateNel
   */
  implicit class AccumulateErrorsNEL[A, Col[+X] <: Iterable[X]](val in: Col[A]) extends AnyVal {

    private def toNEL[E, B](tuple: (Iterable[E], Iterable[B])): ZIO[Any, NonEmptyList[E], List[B]] = {
      (tuple._1.toList, tuple._2.toList) match {
        case ((h :: t), _) => NonEmptyList.of(h, t: _*).fail
        case (Nil, res)    => res.succeed
      }
    }

    /*
     * Execute sequentially and accumulate errors
     */
    def accumulateNEL[R, E, B](f: A => ZIO[R, E, B]): ZIO[R, NonEmptyList[E], List[B]] = {
      ZIO.partition(in)(f).flatMap(toNEL)
    }

    def accumulateNELPure[R, E, B](f: A => Either[E, B]): Either[NonEmptyList[E], List[B]] = {
      in.toList.traverse(x => f(x).toValidatedNel).toEither
    }

    /*
     * Execute in parallel, non ordered, and accumulate error, using at max N fibers
     */
    def accumulateParNELN[R, E, B](n: Int)(f: A => ZIO[R, E, B]): ZIO[R, NonEmptyList[E], List[B]] = {
      ZIO.partitionPar(in)(f).flatMap(toNEL).withParallelism(n)
    }
  }

  /*
   * And a version that translate it to RudderError Accumulated.
   */
  implicit class AccumulateErrors[A](val in: Iterable[A]) extends AnyVal {
    /*
     * Execute sequentially and accumulate errors
     */
    def accumulate[R, E <: RudderError, B](f: A => ZIO[R, E, B]): ZIO[R, Accumulated[E], List[B]] = {
      in.accumulateNEL(f).mapError(errors => Accumulated(errors))
    }

    def accumulatePure[E <: RudderError, B](f: A => Either[E, B]): Either[Accumulated[E], List[B]] = {
      in.accumulateNELPure(f) match {
        case Left(err)  => Left(Accumulated(err))
        case Right(res) => Right(res)
      }
    }

    /*
     * Execute in parallel, non ordered, and accumulate error, using at max N fibers
     */
    def accumulateParN[R, E <: RudderError, B](n: Int)(f: A => ZIO[R, E, B]): ZIO[R, Accumulated[E], List[B]] = {
      in.accumulateParNELN(n)(f).mapError(Accumulated(_))
    }
  }

  /*
   * Transform an Iterable[Either[IOResult, A]]] into it's accumulated PureResult[Iterable[A]]
   */
  implicit class AccumulatedEither[A](val in: Iterable[PureResult[A]]) extends AnyVal {
    def accumulateEither:        PureResult[List[A]] = in.toList.traverse(_.toValidatedNel).toEither match {
      case Left(err)  => Left(Accumulated(err))
      case Right(res) => Right(res)
    }
    def accumulateEitherDiscard: PureResult[Unit]    = in.collect { case Left(e) => e }.toList match {
      case err :: t => Left(Accumulated(NonEmptyList.of(err, t: _*)))
      case Nil      => Right(())
    }
  }

  /**
   *  Some box compatibility methods
   */

  import net.liftweb.common.Box
  import net.liftweb.common.Empty
  import net.liftweb.common.Failure
  import net.liftweb.common.Full

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
        case Full(x) => success(x)
        case Empty   => error(Unexpected("no context was provided"))

        // given how Failure are built in Lift/Rudder, we never have both an Full(exception) and
        // a Full(parent). If it happens nonetheless, we keep parent (which is likely to have ex)
        case f: Failure => error(toFail(f))
      }
    }
  }

  implicit class BoxToEither[E <: RudderError, A](val res: Box[A]) extends AnyVal {
    import cats.instances.either._
    def toPureResult: PureResult[A] = BoxUtil.fold[E, A, PureResult](
      err => Left(err),
      suc => Right(suc)
    )(res)
  }

  implicit class BoxToIO[E <: RudderError, A](res: => Box[A]) {
    import _root_.zio.interop.catz._
    def toIO: IOResult[A] = IOResult
      .attempt(res)
      .flatMap(x => {
        BoxUtil.fold[E, A, IOResult](
          err => err.fail,
          suc => suc.succeed
        )(x)
      })
  }
}

object zio {

  val currentTimeMillis = ZIO.clockWith(_.currentTime(TimeUnit.MILLISECONDS))
//    ZIO.access[_root_.zio.clock.Clock](_.get.currentTime(TimeUnit.MILLISECONDS))
  val currentTimeNanos  = ZIO.clockWith(_.nanoTime)
//  ZIO.accessM[_root_.zio.clock.Clock](_.get.nanoTime)

  /*
   * Default ZIO Runtime used everywhere.
   */
  object ZioRuntime {
    import _root_.zio.internal._

    /*
     * Create a signal handler for signal USR2 that dumps Fibers on log. Can be
     * handy to understand what happened when a service stopped working for ex.
     */
    private val installedSignals = new java.util.concurrent.atomic.AtomicBoolean(false)
    if (!installedSignals.getAndSet(true)) {

      val dumpFibers = { () =>
        Unsafe.unsafe(implicit unsafe => Runtime.default.unsafe.run(Fiber.dumpAll).getOrThrowFiberFailure())
      }

      Unsafe.unsafe(implicit unsafe => Platform.addSignalHandler("USR2", dumpFibers))
    }

    /*
     * Internal runtime. You should not access it within rudder.
     * If you need to use it for "unsafeRun", you should always pin the
     * IO into an async thread pool to avoid deadlock in case of
     * a hierarchy of calls.
     */
    val internal = Runtime.default
//    def platform: RuntimeConfig = internal.runtimeConfig
    def layers:      ZLayer[Any, Nothing, Any] = ZLayer.fromZIOEnvironment(internal.environment.succeed)
    def environment: ZEnvironment[Any]         = internal.environment

    def runNow[A](io: IOResult[A]): A = {
      unsafeRun[RudderError, A](io)
    }

    /*
     * Run now, discard result, log error if any
     */
    def runNowLogError[A](logger: RudderError => Unit)(io: IOResult[A]): Unit = {
      runNow(io.unit.either).swap.foreach(err => logger(err))
    }

    /*
     * An unsafe run that is always started on a growing thread-pool and its
     * effect marked as blocking.
     */
    def unsafeRun[E, A](zio: => ZIO[Any, E, A]): A = {
      // unsafeRun will display a formatted fiber trace in case there is an error, which likely what we wants:
      // here, error were not prevented before run, so it's a defect that should be corrected.
      Unsafe.unsafe(implicit unsafe => internal.unsafe.run(zio).getOrThrowFiberFailure())
    }

  }

  /*
   * When porting a class is too hard
   */
  implicit class UnsafeRun[A](io: IOResult[A]) {
    def runNow:                                      A    = ZioRuntime.runNow(io)
    def runNowLogError(logger: RudderError => Unit): Unit = ZioRuntime.runNowLogError(logger)(io)
    def runOrDie(throwEx: RudderError => Throwable): A    = ZioRuntime.runNow(io.either) match {
      case Right(a)  => a
      case Left(err) => throw throwEx(err)
    }
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
        case Chained(msg, cause)  => new Failure(msg, Empty, Full(errToFailure(cause)))
        case SystemError(msg, ex) =>
          new Failure(s"${msg}. Cause was: ${ex.getClass.getSimpleName}: ${ex.getMessage()}", Full(ex), Empty)
        case other                => new Failure(other.fullMsg, Empty, Empty)
      }
    }
    def toBox:                          Box[A]  = {
      ZioRuntime.runNow(io.either) match {
        case Right(x)  => Full(x)
        case Left(err) => errToFailure(err)
      }
    }
  }

  /*
   * The same for either - not sure it should go there, but
   * we are likely to use both "toBox" in the same files
   */
  implicit class EitherToBox[A](val res: Either[String, A]) extends AnyVal {
    def toBox: Box[A] = res match {
      case Left(err) => Failure(err)
      case Right(v)  => Full(v)
    }
  }

  implicit class PureResultToBox[E <: RudderError, A](val res: PureResult[A]) extends AnyVal {
    def toBox: Box[A] = res.fold(
      err => Failure(err.fullMsg),
      suc => Full(suc)
    )
  }

  /**
   * A utility alias type / methods to create ZIO `Managed[RudderError, A]`
   */
  type IOManaged[A] = ZIO[Any with Scope, RudderError, A]
  object IOManaged {
    def make[A](acquire: => A)(release: A => Unit): IOManaged[A] =
      ZIO.acquireRelease(IOResult.attempt(acquire))(a => effectUioUnit(release(a)))

    def makeM[A](acquire: IOResult[A])(release: A => Unit): IOManaged[A] =
      ZIO.acquireRelease(acquire)(a => effectUioUnit(release(a)))
  }
}

/*
 * A logger interface for logger with "fire and forget" semantic for effects
 */
trait ZioLogger {

  // the underlying logger
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

  final def trace(msg: => String): UIO[Unit] = ZIO.when(logEffect.isTraceEnabled())(logAndForgetResult(_.trace(msg))).unit
  final def debug(msg: => String): UIO[Unit] = ZIO.when(logEffect.isDebugEnabled())(logAndForgetResult(_.debug(msg))).unit
  final def info(msg: => String):  UIO[Unit] = ZIO.when(logEffect.isInfoEnabled())(logAndForgetResult(_.info(msg))).unit
  final def error(msg: => String): UIO[Unit] = ZIO.when(logEffect.isErrorEnabled())(logAndForgetResult(_.error(msg))).unit
  final def warn(msg: => String):  UIO[Unit] = ZIO.when(logEffect.isWarnEnabled())(logAndForgetResult(_.warn(msg))).unit

  final def trace(msg: => String, t: Throwable): UIO[Unit] =
    ZIO.when(logEffect.isTraceEnabled())(logAndForgetResult(_.trace(msg, t))).unit
  final def debug(msg: => String, t: Throwable): UIO[Unit] =
    ZIO.when(logEffect.isDebugEnabled())(logAndForgetResult(_.debug(msg, t))).unit
  final def info(msg: => String, t: Throwable):  UIO[Unit] =
    ZIO.when(logEffect.isInfoEnabled())(logAndForgetResult(_.info(msg, t))).unit
  final def warn(msg: => String, t: Throwable):  UIO[Unit] =
    ZIO.when(logEffect.isErrorEnabled())(logAndForgetResult(_.warn(msg, t))).unit
  final def error(msg: => String, t: Throwable): UIO[Unit] =
    ZIO.when(logEffect.isWarnEnabled())(logAndForgetResult(_.error(msg, t))).unit

  final def ifTraceEnabled[T](action: UIO[T]): UIO[Unit] = ZIO.when(logEffect.isTraceEnabled())(action).unit
  final def ifDebugEnabled[T](action: UIO[T]): UIO[Unit] = ZIO.when(logEffect.isDebugEnabled())(action).unit
  final def ifInfoEnabled[T](action: UIO[T]):  UIO[Unit] = ZIO.when(logEffect.isInfoEnabled())(action).unit
  final def ifWarnEnabled[T](action: UIO[T]):  UIO[Unit] = ZIO.when(logEffect.isErrorEnabled())(action).unit
  final def ifErrorEnabled[T](action: UIO[T]): UIO[Unit] = ZIO.when(logEffect.isWarnEnabled())(action).unit
}

// a default implementation that accepts a name for the logger.
// it will respect slf4j namespacing with ".".
trait NamedZioLogger extends ZioLogger {
  import org.slf4j.LoggerFactory

  // ensure that children use def or lazy val - val leads to UninitializedFieldError.
  def loggerName: String

  final val logEffect = LoggerFactory.getLogger(loggerName)

  // for compatibility with current Lift convention, use logger = this
  def logPure = this
}

object NamedZioLogger {
  def apply(name: String): NamedZioLogger = new NamedZioLogger() { def loggerName = name }
}

/*
 * Translation between lift JSON and ZIO json.
 */
object json {

  /*
   * For now, we don't have the possibility to change representation at the AST level,
   * so we need to go through the string representation each time, which is extremely
   * costly. Avoid translation if possible.
   */

  import _root_.zio.json._
  import _root_.zio.json.ast._
  import _root_.zio.json.ast.Json._
  import net.liftweb.json._

  def zioToLift(json: Json): JValue = {
    json match {
      case Json.Obj(fields)   => JObject(fields.map { case (k, j) => JField(k, zioToLift(j)) }: _*)
      case Json.Arr(elements) => JArray(elements.map(zioToLift(_)).toList)
      case Json.Bool(value)   => JBool(value)
      case Json.Str(value)    => JString(value)
      case Json.Num(value)    =>
        try {
          JInt(value.intValueExact())
        } catch {
          case _: ArithmeticException => JDouble(value.doubleValue())
        }
      case Json.Null          => JNull
    }
  }

  def liftToZio(json: JValue): Json = {
    json match {
      case JsonAST.JNothing     => Null
      case JsonAST.JNull        => Null
      case JsonAST.JString(s)   => Str(s)
      case JsonAST.JDouble(num) => Num(num)
      case JsonAST.JInt(num)    => Num(BigDecimal(num))
      case JsonAST.JBool(value) => Bool(value)
      case JsonAST.JObject(obj) => Obj(obj.map(jf => (jf.name, liftToZio(jf.value))): _*)
      case JsonAST.JArray(arr)  => Arr(arr.map(v => liftToZio(v)): _*)
    }
  }

  implicit class ZioToLift[A](a: A) {
    def toLiftJson(implicit enc: JsonEncoder[A]): Either[String, JValue] = {
      a.toJsonAST.map(zioToLift(_))
    }
  }

  implicit class LiftToZio[A](json: JValue) {
    def toZioJson(implicit dec: JsonDecoder[A]): Either[String, A] = {
      dec.fromJsonAST(liftToZio(json))
    }
  }
}
