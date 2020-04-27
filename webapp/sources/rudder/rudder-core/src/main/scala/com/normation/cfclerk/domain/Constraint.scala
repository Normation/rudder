/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.cfclerk.domain

import com.normation.errors.Inconsistency
import com.normation.errors.PureResult
import com.normation.rudder.services.policies.PropertyParser
import com.normation.rudder.services.policies.PropertyParserTokens
import org.joda.time.format.ISODateTimeFormat

class ConstraintException(val msg: String) extends Exception(msg)

/**
 * A constraint about the type of a variable
 */
sealed trait VTypeConstraint extends Any {
  def name: String

  /* type of the element to use in string template.
   * we only support "string" (almost everything), and "boolean" which
   * are used in &IF( expressions.
   */
  type STTYPE

  /*
   * This method does three things:
   * - it checks if the input value is correct reguarding the field constraint
   * - it formats the string given its internal representation.
   * - it uses the provided escape method to escape need chars in value.
   *
   * The escape function is a parameter because it can change from one agent to the next.
   */
  def getFormatedValidated(value: String, forField: String, escapeString: String => String) : PureResult[STTYPE]
}

/*
 * When we want to have a string put in string template
 */
sealed trait STString extends Any with VTypeConstraint {
  override type STTYPE = String
  override def getFormatedValidated(value: String, forField: String, escapeString: String => String): PureResult[String] = Right(escapeString(value))
}

sealed trait VTypeWithRegex extends Any with STString {
  def regex:Option[RegexConstraint]
}

/*
 * When we want to have a boolean put in string template
 */
sealed trait STBoolean extends VTypeConstraint {
  override type STTYPE = Boolean
  override def getFormatedValidated(value: String, forField: String, escapeString: String => String): PureResult[Boolean] = {
    try {
      Right(value.toBoolean)
    } catch {
      case _:Exception => Left(Inconsistency(s"Wrong value ${value} for field '${forField}': expecting a boolean"))
    }
  }
}

object VTypeConstraint {
  val sizeTypes: List[VTypeConstraint] =
    SizebVType() :: SizekbVType() :: SizembVType() :: SizegbVType() :: SizetbVType() :: Nil
  val regexTypes: List[VTypeConstraint] =
    MailVType :: IpVType :: Ipv4VType :: Ipv6VType :: Nil
  def stringTypes(r: Option[RegexConstraint]): List[VTypeConstraint] =
    DateVType(r) :: DateTimeVType(r) :: TimeVType(r) ::
    IntegerVType(r) :: BasicStringVType(r) :: TextareaVType(r) ::
    Nil ::: regexTypes ::: sizeTypes
  def validTypes(r: Option[RegexConstraint], algos:Seq[HashAlgoConstraint]) : List[VTypeConstraint] =
    PermVType :: PasswordVType(algos) :: AixDerivedPasswordVType :: LinuxDerivedPasswordVType :: MasterPasswordVType(algos) ::
    UploadedFileVType :: DestinationPathVType :: SharedFileVType ::
    BooleanVType :: RawVType :: Nil ::: stringTypes(r)

  def getRegexConstraint(vType : VTypeConstraint) : Option[RegexConstraint] =
    vType match {
    case withRegex:VTypeWithRegex => withRegex.regex
    case _ => None
    }

  def getPasswordHash(vType : VTypeConstraint) : Seq[HashAlgoConstraint] = {
    vType match {
      case PasswordVType(hashes) => hashes
      case _ => Seq()
    }
  }
  def fromString(s:String, r: Option[RegexConstraint], algos:Seq[HashAlgoConstraint]) : Option[VTypeConstraint] = {
    validTypes(r,algos).find(t => t.name == s)
  }

  val allTypeNames = validTypes(None,Seq()).map( _.name ).mkString(", ")

  def checkIsProperty(value: String, forField: String)(elsePart: String => PureResult[Unit]): PureResult[Unit] = {
    PropertyParser.parse(value) match {
      case Left(err) => //likely an error with property name
        Left(Inconsistency(s"Wrong value ${value} for field '${forField}': check property syntax"))
      case Right(tokens) => // we need to check that there's at least some var, not just a pure string
        if(PropertyParserTokens.containsVariable(tokens)) {
          Right(())
        } else { //pure string
          elsePart(value)
        }
    }

  }
}

sealed trait StringVType extends VTypeConstraint with VTypeWithRegex with STString {
  def regex: Option[RegexConstraint]

  override def getFormatedValidated(value:String, forField:String, escapeString: String => String) : PureResult[String] = regex match {
    case None => Right(escapeString(value))
    case Some(regex) => regex.check(value, forField).map(escapeString(_))
  }
}
final case class BasicStringVType(regex: Option[RegexConstraint] = None) extends StringVType { override val name = "string" }
final case class TextareaVType(regex: Option[RegexConstraint] = None)  extends StringVType { override val name = "textarea" }

sealed trait FixedRegexVType extends StringVType
object IpVType extends FixedRegexVType {
  override val name = "ip"
  override val regex = Some(IpRegex)
}
object Ipv4VType extends FixedRegexVType {
  override val name = "ipv4"
  override val regex = Some(Ipv4Regex)
}
object Ipv6VType extends FixedRegexVType {
  override val name = "ipv6"
  override val regex = Some(Ipv6Regex)
}
object MailVType extends FixedRegexVType {
  override val name = "mail"
  override val regex = Some(MailRegex)
}

final case class IntegerVType(regex: Option[RegexConstraint] = None) extends VTypeConstraint with VTypeWithRegex  {
  override val name = "integer"
  override def getFormatedValidated(value:String, forField:String, escapeString: String => String) : PureResult[String] = {
    super.getFormatedValidated(value, forField, escapeString).flatMap( escaped =>
      VTypeConstraint.checkIsProperty(value, forField) { v =>
        try {
          v.toInt
          Right(())
        } catch {
          case _:NumberFormatException => Left(Inconsistency(s"Wrong value ${v} for field '${forField}': expecting an integer."))
        }
      }.map(_ => escaped)
    )
  }
}

sealed trait SizeVType extends StringVType
final case class SizebVType (regex: Option[RegexConstraint] = None) extends SizeVType { override val name = "size-b" }
final case class SizekbVType(regex: Option[RegexConstraint] = None) extends SizeVType { override val name = "size-kb" }
final case class SizembVType(regex: Option[RegexConstraint] = None) extends SizeVType { override val name = "size-mb" }
final case class SizegbVType(regex: Option[RegexConstraint] = None) extends SizeVType { override val name = "size-gb" }
final case class SizetbVType(regex: Option[RegexConstraint] = None) extends SizeVType { override val name = "size-tb" }

final case class DateTimeVType(regex: Option[RegexConstraint] = None) extends VTypeConstraint with VTypeWithRegex {
  override val name = "datetime"
  override def getFormatedValidated(value:String, forField:String, escapeString: String => String) : PureResult[String] = {
    super.getFormatedValidated(value, forField, escapeString).flatMap( escaped =>
      VTypeConstraint.checkIsProperty(value, forField) { v =>
        try {
          ISODateTimeFormat.dateTimeParser.parseDateTime(v)
          Right(())
        } catch {
          case _:Exception => Left(Inconsistency(s"Wrong value ${v} for field '${forField}': expecting a datetime in ISO 8601 standard."))
        }
      }.map(_ => escaped)
    )
  }
}
final case class DateVType(regex: Option[RegexConstraint] = None) extends VTypeConstraint with VTypeWithRegex { override val name = "date" }
final case class TimeVType(regex: Option[RegexConstraint] = None) extends VTypeConstraint with VTypeWithRegex { override val name = "time" }

//other types

// passwords
sealed trait AbstactPassword extends VTypeConstraint with STString {
  override final def getFormatedValidated(value:String, forField:String, escapeString: String => String) : PureResult[String] = {
    HashAlgoConstraint.unserialize(value).map( _._2 ).map(escapeString(_))
  }
}

//simple password: the list of hashes must be non-empty
final case class PasswordVType(authorizedHash:Seq[HashAlgoConstraint]) extends AbstactPassword {
  override val name = "password"
}
final case class MasterPasswordVType(authorizedHash:Seq[HashAlgoConstraint]) extends AbstactPassword {
  override val name = s"masterPassword"
}

sealed trait DerivedPasswordVType extends AbstactPassword {
  def tpe: HashAlgoConstraint.DerivedPasswordType
  override lazy val name = s"derivedPassword:${tpe.name}"
}

final case object AixDerivedPasswordVType   extends DerivedPasswordVType { override val tpe = HashAlgoConstraint.DerivedPasswordType.AIX   }
final case object LinuxDerivedPasswordVType extends DerivedPasswordVType { override val tpe = HashAlgoConstraint.DerivedPasswordType.Linux }

final case object BooleanVType extends VTypeConstraint with STBoolean {
  override val name = "boolean"
}

final case object UploadedFileVType    extends VTypeConstraint with STString { override val name = "uploadedfile" }
final case object SharedFileVType      extends VTypeConstraint with STString { override val name = "sharedfile" }
final case object DestinationPathVType extends VTypeConstraint with STString { override val name = "destinationfullpath" }
final case object PermVType            extends VTypeConstraint with STString { override val name = "perm" }
final case object RawVType             extends VTypeConstraint with STString {
  override val name = "raw"
   // no escaping for raw types
  override def getFormatedValidated(value:String, forField:String, escapeString: String => String) : PureResult[String] = Right(value)
}

final case class Constraint(
    typeName  : VTypeConstraint = BasicStringVType()
  , default   : Option[String] = None
  , mayBeEmpty: Boolean = false
  , usedFields: Set[String] = Set()
) {

  def check(varValue: String, varName: String) : Unit = {
    //only check for non-empty variable
    if(varValue == null || varValue.isEmpty) {
      if(mayBeEmpty) {
        //OK
      } else {
        throw new ConstraintException("'%s' field must not be empty".format(varName))
      }
    } else {
      typeName.getFormatedValidated(varValue, varName, identity) match { // here, escaping is not important
        case Right(_)  => //OK
        case Left(err) => throw new ConstraintException(err.fullMsg)
      }
    }
  }
}
