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

import cats.implicits.*
import com.normation.errors.Accumulated
import com.normation.errors.PureResult
import scala.xml.*

/* A SectionChild is either a Variable or a Section*/
sealed trait SectionChild

final case class Section(spec: SectionSpec) extends SectionChild

/**
 *
 * Variable class, to describe what must be replaced in the template files
 *
 */

sealed trait Variable {

  // define in sub classes
  type T <: VariableSpec

  val spec: T

  override def clone: Variable = Variable.matchCopy(this)

  def values: Seq[String] // this is the internal representation of the data

  override def toString(): String = Variable.format(spec.name, values)

  /**
   * *********************************
   * new variable part
   */

  def getValidatedValue(escape: String => String): PureResult[Seq[Any]] = {
    import cats.implicits.*

    val accumulated = values.toList.traverse(x => castValue(x, escape).toValidatedNel)
    accumulated.fold(
      err => Left(Accumulated(err)),
      res => Right(res)
    )
  }

  // the comments below contains implementations and should be reused in SelectVariable and SelectOne variable

  private def checkValueForVariable(seq: Seq[String]): Either[LoadTechniqueError, Seq[String]] = {
    (spec match {
      case vl: ValueLabelVariableSpec =>
        if ((null != vl.valueslabels) && (vl.valueslabels.size > 0)) {
          seq.toList.traverse { item =>
            if (!(vl.valueslabels.map(x => x.value).contains(item)))
              LoadTechniqueError.Variable("Wrong value for variable " + vl.name + "  : " + item).invalidNel
            else item.validNel
          }
        } else { // it seems that it used to not be an error, by why ?
          LoadTechniqueError
            .Variable("Wrong value for variable " + vl.name + "  : that variable does not have any value label")
            .invalidNel
        }
      case _ => seq.validNel
    }).fold(errs => Left(LoadTechniqueError.Accumulated(errs)), x => Right(x))
  }

  /**
   * Only deals with the first entry
   *
   * That method return the new values for the variables
   */
  protected def copyWithSavedValueResult(s: String): Either[LoadTechniqueError, Seq[String]] = {
    for {
      _ <- checkValueForVariable(s :: Nil)
    } yield {
      if (s != null) {
        if (!this.spec.checked) {
          // set values(0) to s
          Seq(s) ++ values.tail
        } else if (Variable.checkValue(this, s)) {
          if (this.values.nonEmpty)
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
  }

  /**
   * Save the whole seq as value
   */
  def copyWithSavedValuesResult(seq: Seq[String]): Either[LoadTechniqueError, Seq[String]] = {
    for {
      _   <- checkValueForVariable(seq)
      res <- if (seq != null) {
               if (!this.spec.checked) {
                 Right(seq)
               } else if (!this.spec.multivalued && seq.lengthCompare(1) > 0) {
                 Left(LoadTechniqueError.Variable("Wrong variable length for " + this.spec.name))
               } else if (seq.map(x => Variable.checkValue(this, x)).contains(false)) {
                 Left(
                   LoadTechniqueError.Variable("Wrong variable value for " + this.spec.name)
                 ) // this should really not be thrown
               } else {
                 Right(seq)
               }
             } else {
               // change nothing
               Right(this.values)
             }
    } yield {
      res
    }
  }

  /**
   * Append the seq to the values
   */
  protected def copyWithAppendedValuesResult(seq: Seq[String]): Either[LoadTechniqueError, Seq[String]] = {
    for {
      _   <- checkValueForVariable(seq)
      res <- if (seq != null) {
               if (!this.spec.checked) {
                 Right(this.values ++ seq)
               } else if (!this.spec.multivalued && (seq.size + this.values.size) > 1) {
                 Left(LoadTechniqueError.Variable("Wrong variable length for " + this.spec.name))
               } else if (values.map(x => Variable.checkValue(this, x)).contains(false)) {
                 Left(
                   LoadTechniqueError.Variable("Wrong variable value for " + this.spec.name)
                 ) // this should really not be thrown
               } else {
                 Right(this.values ++ seq)
               }
             } else {
               Right(this.values)
             }
    } yield {
      res
    }
  }

  def copyWithSavedValue(s:       String):      Either[LoadTechniqueError, Variable]
  def copyWithSavedValues(seq:    Seq[String]): Either[LoadTechniqueError, Variable]
  def copyWithAppendedValues(seq: Seq[String]): Either[LoadTechniqueError, Variable]

  protected def castValue(x: String, escape: String => String): PureResult[Any] = {
    // we don't want to check constraint on empty value
    // when the variable is optional.
    // But I'm not sure if I understand what is happening with a an optional
    // boolean, since we are returning a string in that case :/
    if (this.spec.constraint.mayBeEmpty && (x == null || x.isEmpty)) Right("")
    else spec.constraint.typeName.getFormatedValidated(x, spec.name, escape)
  }
}

final case class SystemVariable(
    override val spec:   SystemVariableSpec,
    override val values: Seq[String]
) extends Variable {
  type T = SystemVariableSpec

  override def copyWithAppendedValues(seq: Seq[String]): Either[LoadTechniqueError, SystemVariable] = {
    this
      .copyWithAppendedValuesResult(
        seq
      )
      .map(x => this.copy(values = x))
  }

  override def copyWithSavedValue(s: String): Either[LoadTechniqueError, SystemVariable] = {
    this
      .copyWithSavedValueResult(
        s
      )
      .map(x => this.copy(values = x))
  }

  override def copyWithSavedValues(seq: Seq[String]): Either[LoadTechniqueError, SystemVariable] = {
    this
      .copyWithSavedValuesResult(
        seq
      )
      .map(x => this.copy(values = x))
  }
}

final case class TrackerVariable(
    override val spec:   TrackerVariableSpec,
    override val values: Seq[String]
) extends Variable {
  type T = TrackerVariableSpec

  override def copyWithAppendedValues(seq: Seq[String]): Either[LoadTechniqueError, TrackerVariable] = {
    this
      .copyWithAppendedValuesResult(
        seq
      )
      .map(x => this.copy(values = x))
  }

  override def copyWithSavedValue(s: String): Either[LoadTechniqueError, TrackerVariable] = {
    this
      .copyWithSavedValueResult(
        s
      )
      .map(x => this.copy(values = x))
  }

  override def copyWithSavedValues(seq: Seq[String]): Either[LoadTechniqueError, TrackerVariable] = {
    this
      .copyWithSavedValuesResult(
        seq
      )
      .map(x => this.copy(values = x))
  }
}

sealed trait SectionVariable extends Variable with SectionChild

final case class InputVariable(
    override val spec:   InputVariableSpec,
    override val values: Seq[String]
) extends SectionVariable {
  type T = InputVariableSpec

  override def copyWithAppendedValues(seq: Seq[String]): Either[LoadTechniqueError, InputVariable] = {
    this
      .copyWithAppendedValuesResult(
        seq
      )
      .map(x => this.copy(values = x))
  }

  override def copyWithSavedValue(s: String): Either[LoadTechniqueError, InputVariable] = {
    this
      .copyWithSavedValueResult(
        s
      )
      .map(x => this.copy(values = x))
  }

  override def copyWithSavedValues(seq: Seq[String]): Either[LoadTechniqueError, InputVariable] = {
    this
      .copyWithSavedValuesResult(
        seq
      )
      .map(x => this.copy(values = x))
  }
}

final case class SelectVariable(
    override val spec:   SelectVariableSpec,
    override val values: Seq[String]
) extends SectionVariable {
  type T = SelectVariableSpec

  override def copyWithAppendedValues(seq: Seq[String]): Either[LoadTechniqueError, SelectVariable] = {
    this
      .copyWithAppendedValuesResult(
        seq
      )
      .map(x => this.copy(values = x))
  }

  override def copyWithSavedValue(s: String): Either[LoadTechniqueError, SelectVariable] = {
    this
      .copyWithSavedValueResult(
        s
      )
      .map(x => this.copy(values = x))
  }

  override def copyWithSavedValues(seq: Seq[String]): Either[LoadTechniqueError, SelectVariable] = {
    this
      .copyWithSavedValuesResult(
        seq
      )
      .map(x => this.copy(values = x))
  }
}

final case class SelectOneVariable(
    override val spec:   SelectOneVariableSpec,
    override val values: Seq[String]
) extends SectionVariable {
  type T = SelectOneVariableSpec

  override def copyWithAppendedValues(seq: Seq[String]): Either[LoadTechniqueError, SelectOneVariable] = {
    this
      .copyWithAppendedValuesResult(
        seq
      )
      .map(x => this.copy(values = x))
  }

  override def copyWithSavedValue(s: String): Either[LoadTechniqueError, SelectOneVariable] = {
    this
      .copyWithSavedValueResult(
        s
      )
      .map(x => this.copy(values = x))
  }

  override def copyWithSavedValues(seq: Seq[String]): Either[LoadTechniqueError, SelectOneVariable] = {
    this
      .copyWithSavedValuesResult(
        seq
      )
      .map(x => this.copy(values = x))
  }
}

final case class PredefinedValuesVariable(
    override val spec:   PredefinedValuesVariableSpec,
    override val values: Seq[String]
) extends SectionVariable {
  type T = PredefinedValuesVariableSpec

  override def copyWithAppendedValues(seq: Seq[String]): Either[LoadTechniqueError, PredefinedValuesVariable] = {
    this
      .copyWithAppendedValuesResult(
        seq
      )
      .map(x => this.copy(values = x))
  }

  override def copyWithSavedValue(s: String): Either[LoadTechniqueError, PredefinedValuesVariable] = {
    this
      .copyWithSavedValueResult(
        s
      )
      .map(x => this.copy(values = x))
  }

  override def copyWithSavedValues(seq: Seq[String]): Either[LoadTechniqueError, PredefinedValuesVariable] = {
    this
      .copyWithSavedValuesResult(
        seq
      )
      .map(x => this.copy(values = x))
  }
}

object Variable {

  def format(name: String, values: Seq[String]): String = {
    // we only want to see the values if:
    // - they start with a ${}, because it's a replacement
    // - else, only 'limit' chars at most, with "..." if longer
    val limit = 20
    val vs    = values
      .map(v => {
        if (v.startsWith("${")) v
        else if (v.lengthCompare(limit) < 0) v
        else v.take(limit) + "..."
      })
      .mkString("[", ", ", "]")
    s"${name}: ${vs}"
  }

  // define our own alternatives of matchCopy because we want v.values to be the default
  // values
  def matchCopy(v: Variable): Variable = matchCopy(v, setMultivalued = false)
  def matchCopy(v: Variable, setMultivalued: Boolean): Variable = matchCopy(v, v.values, setMultivalued)

  def matchCopy(v: Variable, values: Seq[String], setMultivalued: Boolean = false): Variable = {

    v match {
      case iv:        InputVariable     =>
        val newSpec = if (setMultivalued) iv.spec.cloneSetMultivalued else iv.spec
        iv.copy(values = values, spec = newSpec)
      case sv:        SelectVariable    =>
        val newSpec = if (setMultivalued) sv.spec.cloneSetMultivalued else sv.spec
        sv.copy(values = values, spec = newSpec)
      case s1v:       SelectOneVariable =>
        val newSpec = if (setMultivalued) s1v.spec.cloneSetMultivalued else s1v.spec
        s1v.copy(values = values, spec = newSpec)
      case systemV:   SystemVariable    =>
        val newSpec = if (setMultivalued) systemV.spec.cloneSetMultivalued else systemV.spec
        systemV.copy(values = values, spec = newSpec)
      case directive: TrackerVariable   =>
        val newSpec = if (setMultivalued) directive.spec.cloneSetMultivalued else directive.spec
        directive.copy(values = values, spec = newSpec)
      case x:         SectionVariable   => x
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
    returnedValue.toSeq
  }

  /**
   * Check the value we intend to put in the variable
   */
  def checkValue(variable: Variable, value: String): Boolean = {
    variable.castValue(value, identity).isRight // here we are not interested in the escape part
  }
}
