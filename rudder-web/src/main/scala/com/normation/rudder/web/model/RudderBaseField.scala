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

package com.normation.rudder.web.model

import net.liftweb.util.{
  FieldError,BaseField,FieldContainer,Bindable,Helpers,StringValidators
}
import scala.xml._
import net.liftweb.common._
import net.liftweb.http.SHtml
import net.liftweb.http.SHtml._
import net.liftweb.http.SHtml.ElemAttr._
import net.liftweb.util.Helpers._
import net.liftweb.http.js._
import JsCmds._


/**
 * A simple class that allows to register error information
 * about a form and its fields
 */
class FormTracker(private[this] var _fields : List[RudderBaseField] = Nil) extends FieldContainer {

  def this(fields:RudderBaseField*) = this(fields.toList)

  type FormError = NodeSeq

  private[this] var _formErrors = List.empty[FormError]

  override def allFields = _fields.toList

  def register(field:RudderBaseField) : Unit = _fields ::= field

  def fieldErrors : Map[RudderBaseField, List[FieldError]] = _fields.map { f => (f -> f.errors) }.toMap

  def formErrors : List[FormError] = _formErrors

  def addFormError(error:FormError) = _formErrors ::= error

  /**
   * A form has error if it has global error or one of the registered
   * fields has error
   */
  def hasErrors = {
    _formErrors.nonEmpty || _fields.exists( _.hasErrors )
  }

  /**
   * Clean errors on the tracker and each fields
   */
  def cleanErrors : Unit = {
    _formErrors = Nil
    _fields.foreach { _.cleanErrors }
  }

  /**
   * Reinit the formTracker and all its registered field
   */
  def clean : Unit = {
    _formErrors = Nil
    _fields.foreach { _.cleanErrors }
  }

}


/**
 * An abstract field that allows to generate form elements and
 * manage/display errors for them.
 *
 * Concrete field instance must gives:
 * - a ValueType
 * - a name (display name) for the field
 * - the actual XHTML code for the field
 * - a default value
 *   If not, it makes value/clean more complicate, or to implement in each concrete type)
 *
 *  May give :
 * - a className for the inputField
 * - a labelClassName : for the label
 * - an errorClassName : for the error message
 *
 */
abstract class RudderBaseField extends BaseField {

  /* method/type to implement */
  type ValueType

  ////// field content and getter / setter //////
  protected val defaultValue : ValueType
  protected var value = defaultValue
  override def is = value
  override def set(in:ValueType) : ValueType = {
    value = (in /: setFilter)( (currentVal,currentFilter) => currentFilter(currentVal) )
    validate
    value
  }

  ///// fields errors //////
  protected var _errors = List.empty[FieldError]
  def errors = _errors
  def hasErrors = _errors.nonEmpty
  def cleanErrors : Unit = _errors = Nil

  //The human readable name for the field
  def name : String
  //The actual input field - do not set its ID, it's given
  def inputField : Elem

  // Class used for the elements
  def subContainerClassName : String = "twoCol"
  def className : String = "rudderBaseFieldClassName"
  def labelClassName : String = "threeCol"
  def errorClassName : String = "threeColErrors"
  ///////// method to optionnaly override //////////

  // add some HTLM to help the user to fill that field
  override def helpAsHtml: Box[NodeSeq] = Empty
  // override the field name look
  override def displayNameHtml: Box[NodeSeq] = {
    validations match {
      case Nil => Some(<b>{displayName}:</b>)
      case _ => Some(<b>{displayName}: *</b>)
    }
  }
  //optionnaly override validate to add validation functions
  override def validations = List.empty[ValueType => List[FieldError]]
  // override to add setFilter
  override def setFilter = List.empty[ValueType => ValueType]

  ////// other method //////

  protected lazy val id = Helpers.nextFuncName
  override lazy val uniqueFieldId: Box[String] = Full(id)
  override lazy val fieldId : Option[NodeSeq] = Some(Text(id))
  override def toString = "[%s:%s]".format(name, is.toString)
  override def validate = {
    _errors = validations.flatMap( v => v(this.is) )
    _errors
  }
  override def get = is

  override def toForm = Full(toForm_!)

  def toForm_! = bind("field",
    <div class="wbBaseField">
      <label for={id} class={labelClassName + " wbBaseFieldLabel textright"}><field:label /></label>
      <div class={subContainerClassName}>
        <field:input />
        <field:infos />
        <field:errors />
      </div>
    </div>,
    "label" -> displayHtml,
    "input" -> {
      errors match {
        case Nil => inputField % ( "id" -> id) % ("class" -> className)
        case l =>
          val c = className + " errorInput"
          inputField % ( "id" -> id) % ("class" -> c)
      }
    },
    "infos" -> (helpAsHtml openOr NodeSeq.Empty),
    "errors" -> {
      errors match {
        case Nil => NodeSeq.Empty
        case l =>
          <span class={errorClassName}><ul class="field_errors paddscala">{
            l.map(e => <li class="field_error lopaddscala">{e.msg}</li>)
          }</ul></span>
      }
    }
  )

  def readOnlyValue =
   <div class="wbBaseField">
      <label class={labelClassName + " wbBaseFieldLabel textright"}>{displayHtml}</label>
      <div class={subContainerClassName}>
        {defaultValue}
      </div>
   </div>
}

class WBTextField(override val name:String, override val defaultValue:String = "") extends RudderBaseField with StringValidators {
  type ValueType = String

  def inputField : Elem = SHtml.text(value, set _)

  protected def valueTypeToBoxString(in: ValueType): Box[String] = Full(in)
  protected def boxStrToValType(in: Box[String]): ValueType = in openOr("")

  def maxLen: Int= 50
}

class WBTextAreaField(override val name:String, override val defaultValue:String = "") extends RudderBaseField with StringValidators {
  type ValueType = String

  def inputField : Elem = SHtml.textarea(value, set _)

  protected def valueTypeToBoxString(in: ValueType): Box[String] = Full(in)
  protected def boxStrToValType(in: Box[String]): ValueType = in openOr("")

  def maxLen: Int= 150
}

class WBCheckboxField(override val name:String, override val defaultValue:Boolean = false, val attrs : Seq[(String, String)] = Seq()) extends RudderBaseField {
  type ValueType = Boolean

  def inputField : Elem = <span>{SHtml.checkbox(value, set _, (attrs: Seq[ElemAttr]) :_*)}</span>
}


class WBSelectField(override val name:String, val opts : Seq[(String, String)], override val defaultValue:String = "", val attrs : Seq[(String, String)] = Seq()) extends RudderBaseField{
  type ValueType = String

  def defaultVal : Box[String] = {
    if (opts.filter(x => x._1 == value).size>0) {
      Full(value)
    } else  {
      Empty
    }
  }

  def inputField : Elem = SHtml.select(opts, defaultVal, set _, attrs:_*)

//  protected def valueTypeToBoxString(in: ValueType): Box[String] = Full(in)
//  protected def boxStrToValType(in: Box[String]): ValueType = in openOr("")

}

class WBSelectObjField[T](override val name:String, val opts : Seq[(T, String)], override val defaultValue:T, val attrs : Seq[(String, String)] = Seq()) extends RudderBaseField {
  type ValueType = T

  def defaultVal : Box[T] = {
    if (opts.filter(x => x._1 == value).size>0)
      Full(value)
    else
      Empty
  }

  def inputField : Elem = SHtml.selectObj[T](opts, defaultVal, set _, attrs:_*)
}

class WBRadioField(
    override val name:String,
    val opts : Seq[String],
    override val defaultValue:String = "",
    val displayChoiceLabel: String => NodeSeq = {label =>Text(label) },
    val tabindex : Option[Int] = None
) extends RudderBaseField with StringValidators {
  type ValueType = String

  def defaultVal : Box[String] = {
    if (opts.filter(x => (x == defaultValue)).size > 0)
      Full(defaultValue)
    else
      Empty
  }
  val parameters = ("class", "radio") :: { tabindex match {
    case Some(i) => ("tabindex" , i.toString) :: Nil
    case None    => Nil
  } }

  def choiceHolder: ChoiceHolder[String] = SHtml.radio(opts, Full(value),  set _ , parameters:_*)

  def inputField : Elem = {
    <div>
      {choiceHolder.flatMap { c =>
       <span>
         <label>{c.xhtml}<span class="radioTextLabel">{displayChoiceLabel(c.key)}</span></label>
       </span>
      }}
    </div>
  }

  protected def valueTypeToBoxString(in: ValueType): Box[String] = Full(in)
  protected def boxStrToValType(in: Box[String]): ValueType = in openOr("")

  def maxLen: Int= 50
}
