/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation

import cats.data.NonEmptyList
import cats.data.ValidatedNel
import net.liftweb.common._
import Result._
import cats.implicits._

/**
 *
 * A Result trait and a general error classes for composition.
 * Implicits for transition from Bow.
 *
 */
object Result extends ResultImplicits with BoxCompat {

  /*
   * Our Result.Error type.
   * Error always hold a message.
   */
  trait Error {
    def msg: String
  }

  type Result[T] = Either[Error, T]


  /**
   * Standard errors. You are encouraged to build you own
   * ADT of errors for each business domain.
   */
  final object Error {

    // errors due to some LDAPException
    final case class Exception(msg: String, cause: Throwable)        extends Result.Error
    // errors linked to some logic of our lib
    final case class Consistancy(msg: String)                        extends Result.Error
    // trace
    final case class Chained(hint: String, cause: Result.Error)      extends Result.Error {
      def msg = hint +" <- " + cause.msg
    }
    // accumulated errors from multiple independent action
    final case class Accumulated(errors: NonEmptyList[Result.Error]) extends Result.Error {
      def msg = s"Several errors encountered: ${errors.toList.map(_.msg).mkString("; ")}"
    }
  }
}

trait ErrorADT {

  trait Error extends Result.Error

  // errors due to some LDAPException
  final case class Exception(msg: String, cause: Throwable) extends Error
  // errors linked to some logic of our lib
  final case class Consistancy(msg: String)                 extends Error
  // trace
  final case class Chained(hint: String, cause: Error)      extends Error {
    def msg = hint +" <- " + cause.msg
  }
  // accumulated errors from multiple independent action
  final case class Accumulated(errors: NonEmptyList[Error]) extends Error {
    def msg = s"Several errors encountered: ${errors.toList.map(_.msg).mkString("; ")}"
  }
}


/*
 * Template for error ADT with the standard translation to/from box.
 */
trait ResultADT[S, E <: ErrorADT, R <: Either[E#Error, S]] {



  // transform an Option[T] into a Result
  implicit class StrictOption[T](opt: Result[Option[T]]) {
    def notOptional(msg: String) = opt.flatMap {
      case None    => Left(E.Consistancy(msg))
      case Some(x) => Right(x)
    }
  }

  implicit class ToSuccess[T](t: T) {
    def success = Right(t)
  }
  implicit class ToFailure[T <: Result.Error](e: T) {
    def failure = Left(e)
  }
  // same than above for a Rudder error from a string
  implicit class ToFailureMsg(e: String) {
    def failure = Left(Result.Error.Consistancy(e))
  }
  implicit class ChainError[T<: Result.Error](e: T) {
    def logError(msg: String) = Left(Result.Error.Chained(msg, e))
    //for compat with Box
    def ?~!(msg: String) = logError(msg)
  }
  implicit class ChainErrorRes[T](res: Result[T]) {
    def logError(msg: String) = res.leftMap(_.logError(msg))
    def ?~!(msg: String) = logError(msg)
  }

  implicit class ValidatedToError[T](res: ValidatedNel[Result.Error, T]) {
    def toResult: Result[T] = res.fold(errors => Result.Error.Accumulated(errors).failure , x => x.success)
  }

}

trait BoxCompat {

  implicit class ToBox[T](res: Result[T]) {
    import net.liftweb.common._
    def toBox: Box[T] = res.fold(e => Failure(e.msg), s => Full(s))
  }

  implicit class ToResultError(res: EmptyBox) {
    def toResult : Result.Error = res match {
      case Empty                        => Result.Error.Consistancy("Unknow error happened")
      case Failure(msg, _, Full(cause)) => Result.Error.Chained(msg, cause.toResult)
      case Failure(msg, Full(ex), _)    => Result.Error.Exception(msg, ex)
      case Failure(msg, _, _)           => Result.Error.Consistancy(msg)
    }
  }

  implicit class ToResult[T](res: Box[T]) {
    def toResult: Result[T] = res match {
      case Full(x)     => x.success
      case eb:EmptyBox => eb.toResult.failure
    }
  }

}


