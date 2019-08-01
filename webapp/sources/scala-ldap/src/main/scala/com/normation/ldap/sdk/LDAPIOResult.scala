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

package com.normation.ldap.sdk

import cats.data.NonEmptyList
import com.normation.errors.IOResult
import com.normation.errors.RudderError
import zio._
import zio.syntax._
import com.normation.ldap.sdk.syntax._

sealed trait LDAPRudderError extends RudderError {
  def msg: String
}

object LDAPRudderError {
  // errors due to some LDAPException
  final case class BackendException(msg: String, cause: Throwable)  extends LDAPRudderError {
    override def fullMsg: String = super.fullMsg + s"; cause was: ${RudderError.formatException(cause)}"
  }

  // errors where there is a result, but result is not SUCCESS
  final case class FailureResult(msg: String, result: LDAPResult)   extends LDAPRudderError {
    override def fullMsg: String = s"${msg}; LDAP result was: ${result.getResultString}"
  }

  final case class Consistancy(msg: String)                         extends LDAPRudderError

  // accumulated errors from multiple independent action
  final case class Accumulated(errors: NonEmptyList[RudderError])   extends LDAPRudderError {
    def msg = s"Several errors encountered: ${errors.toList.map(_.fullMsg).mkString("; ")}"
  }
}

object LDAPIOResult{
  type LDAPIOResult[T] = IO[LDAPRudderError, T]


  def effect[A](effect: => A): IO[LDAPRudderError.BackendException, A] = {
    IOResult.effect(effect).mapError(err =>
      LDAPRudderError.BackendException(err.msg, err.cause)
    )
  }
  def effectNonBlocking[A](effect: => A): IO[LDAPRudderError.BackendException, A] = {
    IOResult.effectNonBlocking(effect).mapError(err =>
      LDAPRudderError.BackendException(err.msg, err.cause)
    )
  }

  // transform an Option[T] into an error
  implicit class StrictOption[T](opt: LDAPIOResult[Option[T]]) {
    def notOptional(msg: String) = IO.require[LDAPRudderError, T](LDAPRudderError.Consistancy(msg))(opt)
  }

  // same than above for a Rudder error from a string
  implicit class ToFailureMsg(e: String) {
    def fail = IO.fail(LDAPRudderError.Consistancy(e))
  }

  implicit class ValidatedToLdapError[T](res: ZIO[Any, NonEmptyList[LDAPRudderError], List[T]]) {
    def toLdapResult: LDAPIOResult[List[T]] = res.mapError(errors => LDAPRudderError.Accumulated(errors))
  }

  implicit class EitherToLdapError[T](res: Either[RudderError, T]) {
    def toLdapResult: LDAPIOResult[T] = {
      res match {
        case Left(error) => LDAPRudderError.Consistancy(error.msg).fail
        case Right(x)    => x.succeed
      }
    }
  }

}
