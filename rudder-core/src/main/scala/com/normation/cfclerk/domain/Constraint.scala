/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.cfclerk.domain

import com.normation.utils.Utils
import org.joda.time.format.ISODateTimeFormat
import com.normation.utils.HashcodeCaching
import com.normation.utils.Control
import net.liftweb.common._

class ConstraintException(val msg: String) extends Exception(msg)


trait VTypeWithRegex {
  def regex:Option[RegexConstraint]
}
/**
 * A constraint about the type of a variable
 */
sealed trait VTypeConstraint {
  def name: String

  // Escape a String to be CFengine compliant
  // a \ will be escaped to \\
  // a " will be escaped to \"
  // The parameter may be null (for some legacy reason), and it should be checked
  def escapeString(x : String) : String = {
    if (x == null)
      x
    else
      x.replaceAll("""\\""", """\\\\""").replaceAll(""""""","""\\"""")
  }

  //check if the value is compatible with that type constrain.
  //return a Failure on error, the checked value on success.
  def getTypedValue(value:String, forField:String) : Box[Any] = Full(escapeString(value))
}

object VTypeConstraint {
  val sizeTypes: List[VTypeConstraint] =
    SizebVType() :: SizekbVType() :: SizembVType() :: SizegbVType() :: SizetbVType() :: Nil
  val regexTypes: List[VTypeConstraint] =
    MailVType :: IpVType :: Nil
  def stringTypes(r: Option[RegexConstraint]): List[VTypeConstraint] =
    DateVType(r) :: DateTimeVType(r) :: TimeVType(r) ::
    IntegerVType(r) :: BasicStringVType(r) :: TextareaVType(r) ::
    Nil ::: regexTypes ::: sizeTypes
  def validTypes(r: Option[RegexConstraint], algos:Seq[HashAlgoConstraint]) : List[VTypeConstraint] =
    PermVType :: PasswordVType(algos) :: UploadedFileVType :: DestinationPathVType ::
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
  def fromString(s:String, r: Option[RegexConstraint], algos:Seq[HashAlgoConstraint]) : Option[VTypeConstraint] = validTypes(r,algos).find(t => t.name == s)

  val allTypeNames = validTypes(None,Seq()).map( _.name ).mkString(", ")
}

sealed trait StringVType extends VTypeConstraint with VTypeWithRegex {
  def regex: Option[RegexConstraint]

  override def getTypedValue(value:String, forField:String) : Box[Any] = regex match {
    case None => Full(escapeString(value))
    case Some(regex) => regex.check(value, forField).map(escapeString(_))
  }
}
case class BasicStringVType(regex: Option[RegexConstraint] = None) extends StringVType { override val name = "string" }
case class TextareaVType(regex: Option[RegexConstraint] = None)  extends StringVType { override val name = "textarea" }

sealed trait FixedRegexVType extends StringVType
object IpVType extends FixedRegexVType {
  override val name = "ip"
  override val regex = Some(IpRegex)
}
object MailVType extends FixedRegexVType {
  override val name = "mail"
  override val regex = Some(MailRegex)
}

case class IntegerVType(regex: Option[RegexConstraint] = None) extends VTypeConstraint with VTypeWithRegex  {
  override val name = "integer"
  override def getTypedValue(value:String, forField:String) : Box[Any] = {
    super.getTypedValue(value, forField).flatMap( _ =>
      try {
        Full(value.toInt)
      } catch {
        case _:Exception => Failure(s"Wrong value ${value} for field '${forField}': expecting an integer.")
      }
    )
  }
}

sealed trait SizeVType extends StringVType
case class SizebVType(regex: Option[RegexConstraint] = None) extends SizeVType { override val name = "size-b" }
case class SizekbVType(regex: Option[RegexConstraint] = None) extends SizeVType { override val name = "size-kb" }
case class SizembVType(regex: Option[RegexConstraint] = None) extends SizeVType { override val name = "size-mb" }
case class SizegbVType(regex: Option[RegexConstraint] = None) extends SizeVType { override val name = "size-gb" }
case class SizetbVType(regex: Option[RegexConstraint] = None) extends SizeVType { override val name = "size-tb" }

case class DateTimeVType(regex: Option[RegexConstraint] = None) extends VTypeConstraint with VTypeWithRegex {
  override val name = "datetime"
  override def getTypedValue(value:String, forField:String) : Box[Any] = {
    super.getTypedValue(value, forField).flatMap( _ =>
      try
        Full(ISODateTimeFormat.dateTimeParser.parseDateTime(value))
      catch {
        case _:Exception => Failure(s"Wrong value ${value} for field '${forField}': expecting a datetime in ISO 8601 standard.")
      }
    )
  }
}
case class DateVType(regex: Option[RegexConstraint] = None) extends VTypeConstraint with VTypeWithRegex { override val name = "date" }
case class TimeVType(regex: Option[RegexConstraint] = None) extends VTypeConstraint with VTypeWithRegex { override val name = "time" }


//other types

//password: the list of hashes must be non-empty
case class PasswordVType(authorizedHash:Seq[HashAlgoConstraint]) extends VTypeConstraint {
  override val name = "password"
  override def getTypedValue(value:String, forField:String) : Box[Any] = {
    HashAlgoConstraint.unserialize(value).map( _._2 ).map(escapeString(_))
  }
}
case object BooleanVType extends VTypeConstraint {
  override val name = "boolean"
  override def getTypedValue(value:String, forField:String) : Box[Any] = {
      try {
        Full(value.toBoolean)
      } catch {
        case _:Exception => Failure(s"Wrong value ${value} for field '${forField}': expecting a boolean")
      }
  }
}
case object UploadedFileVType extends VTypeConstraint { override val name = "uploadedfile" }
case object DestinationPathVType extends VTypeConstraint { override val name = "destinationfullpath" }
case object PermVType extends VTypeConstraint { override val name = "perm" }
case object RawVType extends VTypeConstraint {
  override val name = "raw"
   // no escaping for raw types
  override def getTypedValue(value:String, forField:String) : Box[Any] = Full(value)
}

case class Constraint(
    typeName: VTypeConstraint = BasicStringVType()
  , default: Option[String] = None
  , mayBeEmpty: Boolean = false
) extends HashcodeCaching {

  def check(varValue: String, varName: String) : Unit = {
    //only check for non-empty variable
    if(varValue == null || varValue.length < 1) {
      if(mayBeEmpty) {
        //OK
      } else {
        throw new ConstraintException("'%s' field must not be empty".format(varName))
      }
    } else {
      typeName.getTypedValue(varValue, varName) match {
        case Full(_) => //OK
        case f:Failure => throw new ConstraintException(f.messageChain)
        //we don't want that to happen
        case Empty => throw new ConstraintException(s"An unknown error occured when checking type constaint of value '${varValue}' for field '${varName}'.")
      }
    }
  }
}
