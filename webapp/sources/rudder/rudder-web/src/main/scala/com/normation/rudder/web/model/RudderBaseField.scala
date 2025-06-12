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

import net.liftweb.common.*
import net.liftweb.http.SHtml
import net.liftweb.http.SHtml.*
import net.liftweb.http.SHtml.ElemAttr.*
import net.liftweb.util.BaseField
import net.liftweb.util.FieldContainer
import net.liftweb.util.FieldError
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers.*
import net.liftweb.util.StringValidators
import scala.xml.*

/**
 * A simple class that allows to register error information
 * about a form and its fields
 */
class FormTracker(private var _fields: List[RudderBaseField] = Nil) extends FieldContainer {

  def this(fields: RudderBaseField*) = this(fields.toList)

  type FormError = NodeSeq

  private var _formErrors = List.empty[FormError]

  override def allFields: List[RudderBaseField] = _fields.toList

  def register(field: RudderBaseField): Unit = _fields ::= field

  def fieldErrors: Map[RudderBaseField, List[FieldError]] = _fields.map(f => (f -> f.errors)).toMap

  def formErrors: List[FormError] = _formErrors

  def addFormError(error: FormError): Unit = _formErrors ::= error

  /**
   * A form has error if it has global error or one of the registered
   * fields has error
   */
  def hasErrors: Boolean = {
    _formErrors.nonEmpty || _fields.exists(_.hasErrors)
  }

  /**
   * Clean errors on the tracker and each fields
   */
  def cleanErrors: Unit = {
    _formErrors = Nil
    _fields.foreach(_.cleanErrors)
  }

  /**
   * Reinit the formTracker and all its registered field
   */
  def clean: Unit = {
    _formErrors = Nil
    _fields.foreach(_.cleanErrors)
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
  protected val defaultValue: ValueType
  protected var value = defaultValue
  override def get    = value
  override def set(in: ValueType): ValueType = {
    value = setFilter.foldLeft(in)((currentVal, currentFilter) => currentFilter(currentVal))
    validate
    value
  }

  def labelExtensions: NodeSeq = NodeSeq.Empty

  ///// fields errors //////
  protected var _errors: List[FieldError] = Nil
  def errors    = _errors
  def hasErrors = _errors.nonEmpty
  def cleanErrors: Unit = _errors = Nil

  // The human readable name for the field
  def name:       String
  // The actual input field - do not set its ID, it's given
  def inputField: Elem

  // Class used for the elements
  def subContainerClassName: String                = "col-xl-9 col-md-12 col-sm-12"
  // def className : String = "rudderBaseFieldClassName"
  def className:             String                = "rudderBaseFieldClassName form-control vresize col-xl-12 col-md-12"
  // def labelClassName : String = "threeCol"
  def containerClassName:    String                = ""
  def labelClassName:        String                = "col-xl-3 col-md-12 col-sm-12"
  def errorClassName:        String                = "col-xl-9 col-xl-offset-3 col-md-12 col-sm-12 col-sm-offset-0 col-md-offset-0"
  def inputAttributes:       Seq[(String, String)] = Seq.empty
  ///////// method to optionnaly override //////////

  // add some HTLM to help the user to fill that field
  override def helpAsHtml:      Box[NodeSeq]                        = Empty
  // override the field name look
  override def displayNameHtml: Box[NodeSeq]                        = {
    validations match {
      case Nil => Some(<span class="fw-normal">{displayName}</span>)
      case _   => Some(<span>{displayName}</span>)
    }
  }
  // optionnaly override validate to add validation functions
  override def validations:     List[ValueType => List[FieldError]] = Nil
  // override to add setFilter
  override def setFilter:       List[ValueType => ValueType]        = Nil

  ////// other method //////

  protected lazy val id = Helpers.nextFuncName
  override lazy val uniqueFieldId: Box[String]      = Full(id)
  override lazy val fieldId:       Option[NodeSeq]  = Some(Text(id))
  override def toString:           String           = "[%s:%s]".format(name, get.toString)
  override def validate:           List[FieldError] = {
    _errors = validations.flatMap(v => v(this.get))
    _errors
  }

  override def toForm: Full[NodeSeq] = Full(toForm_!)
  def toForm_! = {
    (
      "field-label" #> displayHtml
      & "field-input" #> (
        errors match {
          case Nil => inputAttributes.foldLeft(inputField % ("id" -> id) % ("class" -> className)) { case (a, b) => a % b }
          case l   =>
            val c = className + " errorInput"
            inputField % ("id" -> id) % ("class" -> c)
        }
      )
      & "field-infos" #> (helpAsHtml openOr NodeSeq.Empty)
      & "field-errors" #> (
        errors match {
          case Nil => NodeSeq.Empty
          case l   =>
            <span class={errorClassName}><ul>{
              l.map(e => <li class="text-danger">{e.msg}</li>)
            }</ul></span>
        }
      )
    )(
      <div class={s"row wbBaseField form-group ${containerClassName}"}>
      <label for={id} class={s"${labelClassName} wbBaseFieldLabel"}><field-label></field-label> {labelExtensions}</label>
      <div class={subContainerClassName}>
        <field-input></field-input>
        <field-infos></field-infos>
        <field-errors></field-errors>
      </div>
    </div>
    )
  }

  def readOnlyValue: Elem = {
    <div class="row wbBaseField form-group readonly-field">
      <label class={s"${labelClassName} wbBaseFieldLabel"}>{displayHtml}</label>
      <div>
        <div class={s"form-control ${subContainerClassName}"}>
          {defaultValue}
        </div>
      </div>
   </div>
  }
}

class WBTextField(override val name: String, override val defaultValue: String = "")
    extends RudderBaseField with StringValidators {
  type ValueType = String

  def inputField: Elem = SHtml.text(value, set)

  protected def valueTypeToBoxString(in: ValueType):   Box[String] = Full(in)
  protected def boxStrToValType(in:      Box[String]): ValueType   = in openOr ("")

  def maxLen: Int = 50
}

class WBTextAreaField(override val name: String, override val defaultValue: String = "")
    extends RudderBaseField with StringValidators {
  type ValueType = String

  def inputField: Elem = SHtml.textarea(value, set)

  protected def valueTypeToBoxString(in: ValueType):   Box[String] = Full(in)
  protected def boxStrToValType(in:      Box[String]): ValueType   = in openOr ("")

  def maxLen: Int = 150
}

class WBCheckboxField(
    override val name:         String,
    override val defaultValue: Boolean = false,
    val attrs:                 Seq[(String, String)] = Seq()
) extends RudderBaseField {
  type ValueType = Boolean

  def inputField: Elem = <span>{SHtml.checkbox(value, set, (attrs: Seq[ElemAttr])*)}</span>
}

class WBSelectField(
    override val name:         String,
    val opts:                  Seq[(String, String)],
    override val defaultValue: String = "",
    val attrs:                 Seq[(String, String)] = Seq()
) extends RudderBaseField with StringValidators {
  type ValueType = String

  def defaultVal: Box[String] = {
    if (opts.filter(x => x._1 == value).size > 0) {
      Full(value)
    } else {
      Empty
    }
  }

  def inputField: Elem = SHtml.select(opts, defaultVal, set, attrs*)

  protected def valueTypeToBoxString(in: ValueType):   Box[String] = Full(in)
  protected def boxStrToValType(in:      Box[String]): ValueType   = in openOr ("")
  def maxLen: Int = 150

}

class WBSelectObjField[T](
    override val name:         String,
    val opts:                  Seq[(T, String)],
    override val defaultValue: T,
    val attrs:                 Seq[(String, String)] = Seq()
) extends RudderBaseField {
  type ValueType = T

  def defaultVal: Box[T] = {
    if (opts.filter(x => x._1 == value).size > 0)
      Full(value)
    else
      Empty
  }

  def inputField: Elem = SHtml.selectObj[T](opts, defaultVal, set, attrs*)
}

class WBRadioField(
    override val name:         String,
    opts:                      Seq[String],
    override val defaultValue: String = "",
    displayChoiceLabel:        String => NodeSeq = Text(_),
    tabindex:                  Option[Int] = None
) extends RudderBaseField with StringValidators {
  type ValueType = String

  def defaultVal: Box[String]            = {
    if (opts.filter(x => (x == defaultValue)).size > 0)
      Full(defaultValue)
    else
      Empty
  }
  val parameters: List[(String, String)] = ("class", "radio") :: {
    tabindex match {
      case Some(i) => ("tabindex", i.toString) :: Nil
      case None    => Nil
    }
  }

  def choiceHolder: ChoiceHolder[String] = SHtml.radio(opts, Full(value), set, parameters*)

  def inputField: Elem = {
    <div>
    {
      choiceHolder.flatMap { c =>
        <label class="radio-inline">
            {c.xhtml}
            <span class="radioTextLabel">{displayChoiceLabel(c.key)}</span>
        </label>
      }
    }
  </div>
  }

  protected def valueTypeToBoxString(in: ValueType):   Box[String] = Full(in)
  protected def boxStrToValType(in:      Box[String]): ValueType   = in openOr ("")
  def maxLen: Int = 50
}
