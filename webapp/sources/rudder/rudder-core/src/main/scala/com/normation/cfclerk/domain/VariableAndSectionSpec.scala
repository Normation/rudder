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

import com.normation.cfclerk.xmlparsers.CfclerkXmlConstants._
import com.normation.utils.HashcodeCaching
import com.normation.cfclerk.xmlparsers.EmptyReportKeysValue

import cats._
import cats.data._
import cats.implicits._

/**
 * This file define the model for metadata of object
 * contained in SECTIONS.
 * A section may contains other section or variables.
 */


/**
 * Generic trait for object in a section.
 */
sealed trait SectionChildSpec {
  def name: String

  def getVariables: Seq[VariableSpec] = this match {
    case variable: SectionVariableSpec => Seq(variable)
    case section: SectionSpec => section.children.flatMap { child =>
      child match {
        case v: VariableSpec => Seq(v)
        case _ => Seq()
      }
    }
  }

  def getAllSections: Seq[SectionSpec] = this match {
    case v:SectionVariableSpec => Seq()
    case s:SectionSpec => s +: s.children.flatMap( _.getAllSections )
  }

  // get current variables and variables in sub section
  def getAllVariables: Seq[VariableSpec] = this match {
    case variable: SectionVariableSpec => Seq(variable)
    case section: SectionSpec =>
      section.children.flatMap(_.getAllVariables)
  }

  def filterByName(name: String): Seq[SectionChildSpec] = {
    val root = if (this.name == name) Seq(this) else Seq()

    val others = this match {
      case section: SectionSpec =>
        section.children.flatMap(_.filterByName(name))
      case variable: SectionVariableSpec => Seq()
    }
    root ++ others
  }
}


/**
 * Metadata about a section object.
 */
case class SectionSpec(
    name            : String
  , isMultivalued   : Boolean = false
  , isComponent     : Boolean = false
  , componentKey    : Option[String] = None
  , displayPriority : DisplayPriority = HighDisplayPriority
  , description     : String = ""
  , children        : Seq[SectionChildSpec] = Seq()
) extends SectionChildSpec with HashcodeCaching {

  lazy val getDirectVariables : Seq[VariableSpec] = {
    children.collect { case v:VariableSpec => v }
  }

  lazy val getDirectSections : Seq[SectionSpec] = {
    children.collect { case s:SectionSpec => s }
  }

  def copyWithoutSystemVars: SectionSpec =
    filterChildren {
      case _ : PredefinedValuesVariableSpec => false
      case variable: VariableSpec => !variable.isSystem
      case _ => true
    }

  // do recursively a filter on each SectionChild
  def filterChildren(f: SectionChildSpec => Boolean): SectionSpec = {
    val kept = children.filter(f) map { child =>
      child match {
        case secSpec: SectionSpec => secSpec.filterChildren(f)
        case varSpec: SectionVariableSpec => varSpec
      }
    }
    this.copy(children = kept)
  }

  def cloneVariablesInMultivalued: Either[LoadTechniqueError, SectionSpec] = {
    if(isMultivalued) recCloneMultivalued
    else Left(LoadTechniqueError.Consistancy("Trying to clone multivariable value in a non multivariable variable. It's likely a bug."))
  }

  private def recCloneMultivalued: Either[LoadTechniqueError, SectionSpec] = {
    val multivaluedChildren = children.toList.traverse { child => child match {
      case s: SectionSpec =>
        if (s.isMultivalued) LoadTechniqueError.Consistancy(
          "A multivalued section should not contain other multivalued sections." +
            " It may contain only imbricated sections or variables.").invalidNel
        else
          s.recCloneMultivalued.toValidatedNel
      case v: SectionVariableSpec => v.cloneSetMultivalued.validNel
    } }.leftMap(errs => LoadTechniqueError.Accumulated(errs)).toEither

    for {
      x <- multivaluedChildren
    } yield {
      copy(children = x)
    }
  }
}

object SectionSpec {
  def isSection(sectionName: String) = sectionName == "SECTION"
}

/**
 * Metadata about a Variable: name, description, type, etc
 * but no mutable data (no values)
 *
 */
sealed trait VariableSpec {
  type T <: VariableSpec
  type V <: Variable //type of variable linked to that variable spec

  def name: String
  def description: String
  def longDescription: String

  def multivalued: Boolean

  // if true, check that the value set match the type
  // Some value shouldn't be checked : when we set their value, we don't check anything
  def checked: Boolean

  //create a new variable from that spec
  def toVariable(values: Seq[String] = Seq()): V

  /*
   * children classes have to override that method
   * which make a clone of the spec, setting the
   * cloned to multivalued = true.
   * It's needed to handle multi instance in TemplateDependencies
   */
  def cloneSetMultivalued: T

  // it is a system variable only if the class extending this trait is
  //  a SystemVariableSpec or a TrackerVariableSpec
  def isSystem: Boolean = {
    this match {
      case _: SystemVariableSpec | _: TrackerVariableSpec => true
      case _ => false
    }
  }

  def constraint: Constraint
}

// A SystemVariable is automatically filled by Rudder
// It has the RAW constraint, meaning it is *NOT* escaped
case class SystemVariableSpec(
  override val name: String,
  val description: String,
  val longDescription: String = "",
  val valueslabels: Seq[ValueLabel] = Seq(),
  val multivalued: Boolean = false,

  // we expect that by default the variable will be checked
  val checked: Boolean = true,

  // A system variable is always of the "raw" type, meaning it won't be escaped
  val constraint: Constraint = Constraint(RawVType)

) extends VariableSpec with HashcodeCaching {

  override type T = SystemVariableSpec
  override type V = SystemVariable
  override def cloneSetMultivalued: SystemVariableSpec = this.copy(multivalued = true)
  def toVariable(values: Seq[String] = Seq()): SystemVariable = SystemVariable(this, values)
}

/**
 * A special variable used to embed information
 * about identification of other variable.
 * Typically, in a Rudder context, that variable will
 * keep track of directive and rule ids.
 */
case class TrackerVariableSpec(
  val boundingVariable: Option[String] = None
) extends VariableSpec with HashcodeCaching {

  override type T = TrackerVariableSpec
  override type V = TrackerVariable

  override val name: String = TRACKINGKEY
  override val description: String = "Variable which kept information about the policy"

  override val checked: Boolean = false

  val constraint: Constraint = Constraint()

  override val multivalued = true
  override val longDescription = ""
  override def cloneSetMultivalued: TrackerVariableSpec = this.copy()
  def toVariable(values: Seq[String] = constraint.default.toSeq): TrackerVariable = TrackerVariable(this, values)
}

/**
 * Here we have all the variable that can be declared in sections
 * (all but system vars).
 */
sealed trait SectionVariableSpec extends SectionChildSpec with VariableSpec {
  override type T <: SectionVariableSpec
}

case class ValueLabel(value: String, label: String) extends HashcodeCaching  {
  def tuple = (value, label)
  def reverse = ValueLabel(label, value)
}

trait ValueLabelVariableSpec extends SectionVariableSpec {
  val valueslabels: Seq[ValueLabel]
}

/**
 * A "list of checkbox" kind of select
 */
case class SelectVariableSpec(
  override val name: String,
  val description: String,
  val longDescription: String = "",
  val valueslabels: Seq[ValueLabel] = Seq(),
  val multivalued: Boolean = false,

  // we expect that by default the variable will be checked
  val checked: Boolean = true,

  val constraint: Constraint = Constraint()

) extends ValueLabelVariableSpec with HashcodeCaching {

  override type T = SelectVariableSpec
  override type V = SelectVariable
  override def cloneSetMultivalued: SelectVariableSpec = this.copy(multivalued = true)
  def toVariable(values: Seq[String] = constraint.default.toSeq): SelectVariable = SelectVariable(this, values)
}

/**
 * A button-like or dropdown kind of select
 */
case class SelectOneVariableSpec(
  override val name: String,
  val description: String,
  val longDescription: String = "",
  val valueslabels: Seq[ValueLabel] = Seq(),
  val multivalued: Boolean = false,

  // we expect that by default the variable will be checked
  val checked: Boolean = true,

  val constraint: Constraint = Constraint()

) extends ValueLabelVariableSpec with HashcodeCaching {

  override type T = SelectOneVariableSpec
  override type V = SelectOneVariable
  override def cloneSetMultivalued: SelectOneVariableSpec = this.copy(multivalued = true)
  def toVariable(values: Seq[String] = constraint.default.toSeq): SelectOneVariable = SelectOneVariable(this, values)
}

/**
 * A variable specification that allows
 * to give a set of values to use, and the user
 * won't be able to change them.
 */
case class PredefinedValuesVariableSpec(
    override val name: String
  , val description: String
    //The list of predefined values, provided
    //directly in the variable spec
    //that list can not be empty (but Scala does not have a NonEmptyList type)
    //Values are ordered.
  , val providedValues: (String, Seq[String])
  , val longDescription: String = ""
  , val multivalued: Boolean = true

    // we expect that by default the variable will be checked
  , val checked: Boolean = true

  , val constraint: Constraint = Constraint()
) extends SectionVariableSpec with HashcodeCaching {

  def nelOfProvidedValues = providedValues._1 :: providedValues._2.toList

  override type T = PredefinedValuesVariableSpec
  override type V = PredefinedValuesVariable
  override def cloneSetMultivalued: PredefinedValuesVariableSpec = this.copy(multivalued = true)

  //to create the variable from that spec, just use the provided values.
  def toVariable(values: Seq[String] = nelOfProvidedValues): PredefinedValuesVariable = PredefinedValuesVariable(this, values)
}


/**
 * Standard, unique input (text field)
 */
case class InputVariableSpec(
  override val name: String,
  val description: String,
  val longDescription: String = "",
  val multivalued: Boolean = false,

  // we expect that by default the variable will be checked
  val checked: Boolean = true,

  val constraint: Constraint = Constraint()

) extends SectionVariableSpec with HashcodeCaching {

  override type T = InputVariableSpec
  override type V = InputVariable
  override def cloneSetMultivalued: InputVariableSpec = this.copy(multivalued = true)
  def toVariable(values: Seq[String] = constraint.default.toSeq): InputVariable = InputVariable(this, values)
}

/**
 * This object is the central parser for VariableSpec, so
 * it has to know all possible VariableSpec type.
 * The pattern matching means that it won't be easily extended.
 * A more plugable architecture (partial function, pipeline...)
 * will have to be set-up to achieve such a goal.
 */
object SectionVariableSpec {
  def markerNames = List(INPUT, SELECT1, SELECT, REPORT_KEYS)

  def isVariable(variableName: String) = markerNames contains variableName

  /**
   * Default variable implementation
   * Some of the arguments are not used by all implementations of Variable.
   */
  def apply(
    varName: String,
    description: String,
    markerName: String,
    longDescription: String = "",
    valueslabels: Seq[ValueLabel],
    multivalued: Boolean = false,
    checked: Boolean = true,
    constraint: Constraint = Constraint(),
    providedValues: Seq[String]
  ): SectionVariableSpec = {

    markerName match {
      case INPUT => InputVariableSpec(varName, description, longDescription,
        multivalued, checked, constraint)
      case SELECT => SelectVariableSpec(varName, description, longDescription,
        valueslabels, multivalued, checked, constraint)
      case SELECT1 => SelectOneVariableSpec(varName, description, longDescription,
        valueslabels, multivalued, checked, constraint)
      case REPORT_KEYS =>
        if(providedValues.isEmpty) throw EmptyReportKeysValue(varName)
        else PredefinedValuesVariableSpec(varName, description, (providedValues.head, providedValues.tail),
            longDescription, multivalued, checked, constraint)

      case x => throw new IllegalArgumentException("Unknown variable kind: " + x)
    }
  }
}

/**
 * A trait to describe the display priority, which represents when the
 * section will be shown (when we are high priority ? low ?), with a default
 * to high
 */
sealed trait DisplayPriority {
  def priority : String
}

final case object HighDisplayPriority extends DisplayPriority {
  val priority = "high"
}

final case object LowDisplayPriority extends DisplayPriority {
  val priority = "low"
}


object DisplayPriority {
  def apply(s: String) : Option[DisplayPriority] = {
    s.toLowerCase match {
      case HighDisplayPriority.priority => Some(HighDisplayPriority)
      case LowDisplayPriority.priority  => Some(LowDisplayPriority)
      case _                            => None
    }
  }
}
