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

import com.normation.cfclerk.exceptions._
import scala.xml._
import org.joda.time._
import org.joda.time.format._
import com.normation.utils.XmlUtils._
import net.liftweb.common._
import com.normation.utils.Control.bestEffort
import com.normation.utils.HashcodeCaching

/* A SectionChild is either a Variable or a Section*/
sealed trait SectionChild

case class Section(val spec: SectionSpec) extends SectionChild with HashcodeCaching

/**
 *
 * Variable class, to describe what must be replaced in the template files
 * @author nicolas
 *
 */

sealed trait Variable extends Loggable {

  //define in sub classes
  type T <: VariableSpec

  val spec: T

  override def clone = Variable.matchCopy(this)

  def values: Seq[String]  // this is the internal representation of the data

  override def toString() = Variable.format(spec.name, values)

  /**
   * *********************************
   * new variable part
   */


  def getTypedValues(): Box[Seq[Any]] = {
    bestEffort(values) { x =>
      castValue(x)
    }
  }

  // the comments below contains implementations and should be reused in SelectVariable and SelectOne variable

  /**
   * Only deals with the first entry
   *
   * That method return the new values for the variables
   */
  protected def copyWithSavedValueResult(s: String): Seq[String] = {
    spec match {
      case vl: ValueLabelVariableSpec =>
          if (!(vl.valueslabels.map(x => x.value).contains(s)))
            throw new VariableException("Wrong value for variable " + vl.name + "  : " + s)
      case _ => //OK
    }

    if (s != null) {
      if (!this.spec.checked) {
        //set values(0) to s
        Seq(s) ++ values.tail
      } else if(Variable.checkValue(this, s)) {
        if (this.values.size > 0)
          Seq(s) ++ values.tail
        else
          Seq(s)
      } else {
        this.values
      }
    } else {
      this.values
    }

  }


  /**
   * Save the whole seq as value
   */
  def copyWithSavedValuesResult(seq: Seq[String]): Seq[String] = {
    spec match {
      case vl: ValueLabelVariableSpec =>
        if ((null != vl.valueslabels) && (vl.valueslabels.size > 0)) {
          for (item <- seq)
            if (!(vl.valueslabels.map(x => x.value).contains(item)))
              throw new VariableException("Wrong value for variable " + vl.name + "  : " + item)
        }
      case _ =>
    }

    if(seq != null) {
      if (!this.spec.checked) {
        seq
      } else if (!this.spec.multivalued && values.size > 1) {
        throw new VariableException("Wrong variable length for " + this.spec.name)
      } else if (values.map(x => Variable.checkValue(this, x)).contains(false)) {
        throw new VariableException("Wrong variable value for " + this.spec.name) // this should really not be thrown
      } else {
        seq
      }
    } else {
      //change nothing
      this.values
    }
  }

  /**
   * Append the seq to the values
   */
  protected def copyWithAppendedValuesResult(seq: Seq[String]): Seq[String] = {
    spec match {
      case vl: ValueLabelVariableSpec =>
        if ((null != vl.valueslabels) && (vl.valueslabels.size > 0)) {
          for (item <- seq)
            if (!(vl.valueslabels.map(x => x.value).contains(item)))
              throw new VariableException("Wrong value for variable " + vl.name + "  : " + item)
        }
      case _ =>
    }

    if (seq != null) {
      if (!this.spec.checked) {
        this.values ++ seq
      } else if (!this.spec.multivalued && (seq.size + this.values.size) > 1) {
        throw new VariableException("Wrong variable length for " + this.spec.name)
      } else if (values.map(x => Variable.checkValue(this, x)).contains(false)) {
        throw new VariableException("Wrong variable value for " + this.spec.name) // this should really not be thrown
      } else {
        this.values ++ seq
      }
    } else {
      this.values
    }
  }


  def copyWithSavedValue(s: String) : Variable
  def copyWithSavedValues(seq: Seq[String]): Variable
  def copyWithAppendedValues(seq: Seq[String]): Variable


  protected def castValue(x: String) : Box[Any] = {
    //we don't want to check constraint on empty value
    // when the variable is optionnal
    if(this.spec.constraint.mayBeEmpty && x.length < 1) Full("")
    else spec.constraint.typeName.getTypedValue(x,spec.name)
  }
}

case class SystemVariable(
    override val spec: SystemVariableSpec
  , override val values: Seq[String]
) extends Variable with HashcodeCaching {
  type T = SystemVariableSpec
  override def copyWithAppendedValues(seq: Seq[String]): SystemVariable = this.copy(values = this.copyWithAppendedValuesResult(seq))
  override def copyWithSavedValue(s: String): SystemVariable = this.copy(values = this.copyWithSavedValueResult(s))
  override def copyWithSavedValues(seq: Seq[String]): SystemVariable = this.copy(values = this.copyWithSavedValuesResult(seq))
}

case class TrackerVariable(
    override val spec: TrackerVariableSpec
  , override val values: Seq[String]
) extends Variable with HashcodeCaching {
  type T = TrackerVariableSpec
  override def copyWithAppendedValues(seq: Seq[String]): TrackerVariable = this.copy(values = this.copyWithAppendedValuesResult(seq))
  override def copyWithSavedValue(s: String): TrackerVariable = this.copy(values = this.copyWithSavedValueResult(s))
  override def copyWithSavedValues(seq: Seq[String]): TrackerVariable = this.copy(values = this.copyWithSavedValuesResult(seq))
}

trait SectionVariable extends Variable with SectionChild

case class InputVariable(
    override val spec: InputVariableSpec
  , override val values: Seq[String]
) extends SectionVariable with HashcodeCaching {
  type T = InputVariableSpec
  override def copyWithAppendedValues(seq: Seq[String]): InputVariable = this.copy(values = this.copyWithAppendedValuesResult(seq))
  override def copyWithSavedValue(s: String): InputVariable = this.copy(values = this.copyWithSavedValueResult(s))
  override def copyWithSavedValues(seq: Seq[String]): InputVariable = this.copy(values = this.copyWithSavedValuesResult(seq))
}

case class SelectVariable(
    override val spec: SelectVariableSpec
  , override val values: Seq[String]
) extends SectionVariable with HashcodeCaching {
  type T = SelectVariableSpec
  override def copyWithAppendedValues(seq: Seq[String]): SelectVariable = this.copy(values = this.copyWithAppendedValuesResult(seq))
  override def copyWithSavedValue(s: String): SelectVariable = this.copy(values = this.copyWithSavedValueResult(s))
  override def copyWithSavedValues(seq: Seq[String]): SelectVariable = this.copy(values = this.copyWithSavedValuesResult(seq))
}

case class SelectOneVariable(
    override val spec: SelectOneVariableSpec
  , override val values: Seq[String]
) extends SectionVariable with HashcodeCaching {
  type T = SelectOneVariableSpec
  override def copyWithAppendedValues(seq: Seq[String]): SelectOneVariable = this.copy(values = this.copyWithAppendedValuesResult(seq))
  override def copyWithSavedValue(s: String): SelectOneVariable = this.copy(values = this.copyWithSavedValueResult(s))
  override def copyWithSavedValues(seq: Seq[String]): SelectOneVariable = this.copy(values = this.copyWithSavedValuesResult(seq))
}

case class PredefinedValuesVariable(
    override val spec: PredefinedValuesVariableSpec
  , override val values: Seq[String]
) extends SectionVariable with HashcodeCaching {
  type T = PredefinedValuesVariableSpec
  override def copyWithAppendedValues(seq: Seq[String]): PredefinedValuesVariable = this.copy(values = this.copyWithAppendedValuesResult(seq))
  override def copyWithSavedValue(s: String): PredefinedValuesVariable = this.copy(values = this.copyWithSavedValueResult(s))
  override def copyWithSavedValues(seq: Seq[String]): PredefinedValuesVariable = this.copy(values = this.copyWithSavedValuesResult(seq))
}

object Variable {

  def format(name: String, values:Seq[String]) = {
    //we only want to see the values if:
    //- they start with a ${}, because it's a replacement
    //- else, only 'limit' chars at most, with "..." if longer
    val limit = 20
    val vs = values.map( v =>
      if(v.startsWith("${")) v
      else if(v.size < limit) v
      else v.take(limit) + "..."
    ).mkString("[", ", ", "]")
    s"${name}: ${vs}"
  }


  // define our own alternatives of matchCopy because we want v.values to be the default
  // values
  def matchCopy(v: Variable): Variable = matchCopy(v, false)
  def matchCopy(v: Variable, setMultivalued: Boolean): Variable = matchCopy(v, v.values, setMultivalued)

  def matchCopy(v: Variable, values: Seq[String], setMultivalued: Boolean = false): Variable = {

    v match {
      case iv: InputVariable =>
        val newSpec = if (setMultivalued) iv.spec.cloneSetMultivalued else iv.spec
        iv.copy(values = values, spec = newSpec)
      case sv: SelectVariable =>
        val newSpec = if (setMultivalued) sv.spec.cloneSetMultivalued else sv.spec
        sv.copy(values = values, spec = newSpec)
      case s1v: SelectOneVariable =>
        val newSpec = if (setMultivalued) s1v.spec.cloneSetMultivalued else s1v.spec
        s1v.copy(values = values, spec = newSpec)
      case systemV: SystemVariable =>
        val newSpec = if (setMultivalued) systemV.spec.cloneSetMultivalued else systemV.spec
        systemV.copy(values = values, spec = newSpec)
      case directive: TrackerVariable =>
        val newSpec = if (setMultivalued) directive.spec.cloneSetMultivalued else directive.spec
        directive.copy(values = values, spec = newSpec)
      case x:SectionVariable => x
    }
  }

  def variableParsing(variable: Variable, elt: Node): Seq[String] = {
    variable.values ++ valuesParsing((elt \ "internalValues"))
  }

  private def valuesParsing(elt: NodeSeq): Seq[String] = {
    val returnedValue = collection.mutable.Buffer[String]()
    for (value <- elt \ "value") {
      returnedValue += value.text
    }
    returnedValue
  }

  /**
   * Set the first value
   */
//  def setUniqueValue(variable: Variable, value: String): Unit = {
//    if (value != null) {
//
//      if (!variable.spec.checked) {
//        variable.internalValues(0) = value
//      } else if (checkValue(variable, value)) {
//        if (variable.internalValues.size > 0)
//          variable.internalValues(0) = value
//        else
//          variable.internalValues += value
//      }
//    }
//  }

  /**
   * Replace all values with the ones in argument
   */
//  def setValues(variable: Variable, values: Seq[String]): Unit = {
//    if (values != null) {
//      if (!variable.spec.checked) {
//        variable.internalValues.clear
//        variable.internalValues ++= values
//      } else if (!variable.spec.multivalued && values.size > 1) {
//        throw new VariableException("Wrong variable length for " + variable.spec.name)
//      } else if (values.map(x => checkValue(variable, x)).contains(false)) {
//        throw new VariableException("Wrong variable value for " + variable.spec.name) // this should really not be thrown
//      } else {
//        variable.internalValues.clear
//        variable.internalValues ++= values
//      }
//    }
//  }

  /**
   * Append values in argument to the value list
   */
//  def copyWithAppendedValues(variable: Variable, values: Seq[String]): Unit = {
//    if (values != null) {
//      if (!variable.spec.checked) {
//        variable.internalValues ++= values
//      } else if (!variable.spec.multivalued && (values.size + variable.internalValues.size) > 1) {
//        throw new VariableException("Wrong variable length for " + variable.spec.name)
//      } else if (values.map(x => checkValue(variable, x)).contains(false)) {
//        throw new VariableException("Wrong variable value for " + variable.spec.name) // this should really not be thrown
//      } else {
//        variable.internalValues ++= values
//      }
//    }
//  }

  /**
   * Check the value we intend to put in the variable
   */
  def checkValue(variable: Variable, value: String): Boolean = {
    variable.castValue(value).isDefined
  }
}

