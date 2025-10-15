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
import com.normation.cfclerk.xmlparsers.CfclerkXmlConstants.*
import com.normation.cfclerk.xmlparsers.EmptyReportKeysValue
import com.normation.errors.PureResult
import com.normation.errors.Unexpected

/**
 * This file define the model for metadata of object
 * contained in SECTIONS.
 * A section may contain other section or variables.
 */

/**
 * Generic trait for object in a section.
 */
sealed trait SectionChildSpec {
  def name: String

  def getVariables: Seq[VariableSpec] = this match {
    case variable: SectionVariableSpec => Seq(variable)
    case section:  SectionSpec         =>
      section.children.flatMap { child =>
        child match {
          case v: VariableSpec => Seq(v)
          case _ => Seq()
        }
      }
  }

  def getAllSections: Seq[SectionSpec] = this match {
    case v: SectionVariableSpec => Seq()
    case s: SectionSpec         => s +: s.children.flatMap(_.getAllSections)
  }

  // get current variables and variables in sub section
  def getAllVariables: Seq[VariableSpec] = this match {
    case variable: SectionVariableSpec => Seq(variable)
    case section:  SectionSpec         =>
      section.children.flatMap(_.getAllVariables)
  }

  // get current variables and variables in sub section
  def getAllVariablesBySection(parents: List[SectionSpec]): Seq[(List[SectionSpec], VariableSpec)] = this match {
    case variable: SectionVariableSpec => Seq((parents, variable))
    case section:  SectionSpec         =>
      section.children.flatMap(_.getAllVariablesBySection(section :: parents))
  }

  def filterByName(name: String): Seq[SectionChildSpec] = {
    val root = if (this.name == name) Seq(this) else Seq()

    val others = this match {
      case section:  SectionSpec         =>
        section.children.flatMap(_.filterByName(name))
      case variable: SectionVariableSpec => Seq()
    }
    root ++ others
  }
}

/**
 * Metadata about a section object.
 */
final case class SectionSpec(
    name:            String,
    isMultivalued:   Boolean = false,
    isComponent:     Boolean = false,
    componentKey:    Option[String] = None,
    displayPriority: DisplayPriority = HighDisplayPriority,
    description:     String = "",
    children:        Seq[SectionChildSpec] = Seq(),
    reportingLogic:  Option[ReportingLogic] = None,
    id:              Option[String] = None
) extends SectionChildSpec {

  lazy val getDirectVariables: Seq[VariableSpec] = {
    children.collect { case v: VariableSpec => v }
  }

  lazy val getDirectSections: Seq[SectionSpec] = {
    children.collect { case s: SectionSpec => s }
  }

  def copyWithoutSystemVars: SectionSpec = {
    filterChildren {
      case _:        PredefinedValuesVariableSpec => false
      case variable: VariableSpec                 => !variable.isSystem
      case _ => true
    }
  }

  // do recursively a filter on each SectionChild
  def filterChildren(f: SectionChildSpec => Boolean): SectionSpec = {
    val kept = children.filter(f) map { child =>
      child match {
        case secSpec: SectionSpec         => secSpec.filterChildren(f)
        case varSpec: SectionVariableSpec => varSpec
      }
    }
    this.copy(children = kept)
  }

  def cloneVariablesInMultivalued: Either[LoadTechniqueError, SectionSpec] = {
    if (isMultivalued) recCloneMultivalued
    else {
      Left(
        LoadTechniqueError.Consistancy("Trying to clone multivariable value in a non multivariable variable. It's likely a bug.")
      )
    }
  }

  private def recCloneMultivalued: Either[LoadTechniqueError, SectionSpec] = {
    val multivaluedChildren = children.toList.traverse { child =>
      child match {
        case s: SectionSpec         =>
          s.recCloneMultivalued.toValidatedNel
        case v: SectionVariableSpec => v.cloneSetMultivalued.validNel
      }
    }.leftMap(errs => LoadTechniqueError.Accumulated(errs)).toEither

    for {
      x <- multivaluedChildren
    } yield {
      copy(children = x)
    }
  }
}

object SectionSpec {
  def isSection(sectionName: String): Boolean = sectionName == "SECTION"
}

/**
 * Metadata about a Variable: name, description, type, etc
 * but no mutable data (no values)
 *
 */
sealed trait VariableSpec {
  type T <: VariableSpec
  type V <: Variable // type of variable linked to that variable spec

  def name:            String
  def variableName:    Option[String]
  def description:     String
  def longDescription: String

  def multivalued: Boolean

  // if true, check that the value set match the type
  // Some value shouldn't be checked : when we set their value, we don't check anything
  def checked: Boolean

  // create a new variable from that spec
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
      case _                                              => false
    }
  }

  def constraint: Constraint

  def id: Option[String]
}

// A SystemVariable is automatically filled by Rudder
// It has the RAW constraint, meaning it is *NOT* escaped
final case class SystemVariableSpec(
    override val name:   String,
    val description:     String,
    variableName:        Option[String] = None,
    val longDescription: String = "",
    val valueslabels:    Seq[ValueLabel] = Seq(),
    val multivalued:     Boolean, // we expect that by default the variable will be checked

    val checked: Boolean = true, // A system variable is always of the "raw" type, meaning it won't be escaped

    val constraint: Constraint = Constraint(RawVType),
    val id:         Option[String] = None
) extends VariableSpec {

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
final case class TrackerVariableSpec(
    val boundingVariable: Option[String] = None,
    val id:               Option[String]
) extends VariableSpec {

  override type T = TrackerVariableSpec
  override type V = TrackerVariable

  override val name:         String         = TRACKINGKEY
  override val description:  String         = "Variable which kept information about the policy"
  override val variableName: Option[String] = None
  override val checked:      Boolean        = false

  val constraint: Constraint = Constraint()

  override val multivalued     = true
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

final case class ValueLabel(value: String, label: String) {
  def tuple:   (String, String) = (value, label)
  def reverse: ValueLabel       = ValueLabel(label, value)
}

sealed trait ValueLabelVariableSpec extends SectionVariableSpec {
  val valueslabels: Seq[ValueLabel]
}

/**
 * A "list of checkbox" kind of select
 */
final case class SelectVariableSpec(
    override val name:   String,
    val description:     String,
    variableName:        Option[String],
    val longDescription: String = "",
    val valueslabels:    Seq[ValueLabel] = Seq(),
    val multivalued:     Boolean = false, // we expect that by default the variable will be checked

    val checked:    Boolean = true,
    val constraint: Constraint = Constraint(),
    val id:         Option[String]
) extends ValueLabelVariableSpec {

  override type T = SelectVariableSpec
  override type V = SelectVariable
  override def cloneSetMultivalued: SelectVariableSpec = this.copy(multivalued = true)
  def toVariable(values: Seq[String] = constraint.default.toSeq): SelectVariable = SelectVariable(this, values)
}

/**
 * A button-like or dropdown kind of select
 */
final case class SelectOneVariableSpec(
    override val name:   String,
    val description:     String,
    variableName:        Option[String],
    val longDescription: String = "",
    val valueslabels:    Seq[ValueLabel] = Seq(),
    val multivalued:     Boolean = false, // we expect that by default the variable will be checked

    val checked:    Boolean = true,
    val constraint: Constraint = Constraint(),
    val id:         Option[String]
) extends ValueLabelVariableSpec {

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
final case class PredefinedValuesVariableSpec(
    override val name:   String,
    val description:     String,         // The list of predefined values, provided
    // directly in the variable spec
    // that list can not be empty (but Scala does not have a NonEmptyList type)
    // Values are ordered.

    variableName:        Option[String],
    val providedValues:  (String, Seq[String]),
    val longDescription: String = "",
    val multivalued:     Boolean = true, // we expect that by default the variable will be checked

    val checked:    Boolean = true,
    val constraint: Constraint = Constraint(),
    val id:         Option[String]
) extends SectionVariableSpec {

  def nelOfProvidedValues: List[String] = providedValues._1 :: providedValues._2.toList

  override type T = PredefinedValuesVariableSpec
  override type V = PredefinedValuesVariable
  override def cloneSetMultivalued: PredefinedValuesVariableSpec = this.copy(multivalued = true)

  // to create the variable from that spec, just use the provided values.
  def toVariable(values: Seq[String] = nelOfProvidedValues): PredefinedValuesVariable = PredefinedValuesVariable(this, values)
}

/**
 * Standard, unique input (text field)
 */
final case class InputVariableSpec(
    override val name:   String,
    val description:     String,
    variableName:        Option[String],
    val longDescription: String = "",
    val multivalued:     Boolean = false, // we expect that by default the variable will be checked

    val checked:    Boolean = true,
    val constraint: Constraint = Constraint(),
    val id:         Option[String]
) extends SectionVariableSpec {

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
  def markerNames: List[String] = List(INPUT, SELECT1, SELECT, REPORT_KEYS)

  def isVariable(variableName: String): Boolean = markerNames contains variableName

  /**
   * Default variable implementation
   * Some of the arguments are not used by all implementations of Variable.
   */
  def apply(
      varName:         String,
      description:     String,
      markerName:      String,
      variableName:    Option[String],
      longDescription: String = "",
      valueslabels:    Seq[ValueLabel],
      multivalued:     Boolean = false,
      checked:         Boolean = true,
      constraint:      Constraint = Constraint(),
      providedValues:  Seq[String],
      id:              Option[String]
  ): SectionVariableSpec = {

    markerName match {
      case INPUT       =>
        InputVariableSpec(varName, description, variableName, longDescription, multivalued, checked, constraint, id)
      case SELECT      =>
        SelectVariableSpec(
          varName,
          description,
          variableName,
          longDescription,
          valueslabels,
          multivalued,
          checked,
          constraint,
          id
        )
      case SELECT1     =>
        SelectOneVariableSpec(
          varName,
          description,
          variableName,
          longDescription,
          valueslabels,
          multivalued,
          checked,
          constraint,
          id
        )
      case REPORT_KEYS =>
        if (providedValues.isEmpty) {
          throw EmptyReportKeysValue(varName)
        } else {
          PredefinedValuesVariableSpec(
            varName,
            description,
            variableName,
            (providedValues.head, providedValues.tail),
            longDescription,
            multivalued,
            checked,
            constraint,
            id
          )
        }
      case x           => throw new IllegalArgumentException("Unknown variable kind: " + x)
    }
  }
}

/**
 * A trait to describe the display priority, which represents when the
 * section will be shown (when we are high priority ? low ?), with a default
 * to high
 */
sealed trait DisplayPriority {
  def priority: String
}

case object HighDisplayPriority extends DisplayPriority {
  val priority = "high"
}

case object LowDisplayPriority extends DisplayPriority {
  val priority = "low"
}

object DisplayPriority {
  def apply(s: String): Option[DisplayPriority] = {
    s.toLowerCase match {
      case HighDisplayPriority.priority => Some(HighDisplayPriority)
      case LowDisplayPriority.priority  => Some(LowDisplayPriority)
      case _                            => None
    }
  }
}

/*
 * Compliance reporting logic, especially for block components. We have 4 kind of computation available:
 * - `focus`:
 *    reporting will be the exact same as the compliance of one of the direct subelements.
 *    If the element is missing, and error will be reported, weighted 1.
 * - `weighted`
 *    compliance value will be the weighted contribution of all direct sub-elements
 * - `worst case (weight = 1)`
 *    compliance value will have the same level as the worst case in all elements in the sub-tree, and will have weight 1
 * - `worst case (weight = sum all)`
 *    compliance value will have the same level as the worst case in all elements in the sub-tree, and will have weight equals
 *    to the sum of weight of all sub components.
 * - `focus worst`
 *    compliance value will have the same level as the worst case with the least compliance percent of direct subelements
 *
 * Illustration :
 *
 *
 * BLOCK                                          FOCUS ON C1                        WEIGHTED                     WORST-1 on B1                    WORST-SUM on B1               FOCUS-WORST on BLOCK
 * |                                                     |                               |                                |                                |                                |
 * +-- B1                                                |                               |                    +-----------------------+        +-----------------------+        +-----------------------+
 * |   |                                                 |                               |                    | B1 worst      = 1E    |        | B1 worst      = 1E    |        | B1 weighted   = 2S-1E |
 * |   +-- b1c1 = Error                                  |                             <-✗                    | B1 weight     = 1     |        | B1 weight     = 3     |        | B1 compliance = 66%   |
 * |   +-- b1c2 = Success                                |                             <-✗                    +-----------------------+        +-----------------------+        +-----------------------+
 * |   +-- b1c3 = Success                                |                             <-✓                                |                                |                                |
 * |                                                     |                               |                                |                                |                                |
 * +-- B2                                                |                               |                    +-----------------------+        +-----------------------+        +-----------------------+
 * |   |                                                 |                               |                    | B2 weighted   = 1S-1U |        | B2 weighted   = 1S-1U |        | B2 weighted   = 1S-1U |
 * |   +-- b2c1 = Success                                |                             <-✓                    | B2 weight     = 2     |        | B2 weight     = 2     |        | B2 compliance = 50%   |
 * |   +-- b2c2 = Unexpected                             |                             <-✗                    +-----------------------+        +-----------------------+        +-----------------------+
 * |                                        +-----------------------+                    |                                |                                |                                |
 * +-- C1       = Error                     |        1 Error        |                  <-✓                       WEIGHTED on BLOCK                WEIGHTED on BLOCK                         |
 *                                          | BLOCK compliance = 0% |       +------------------------+        +------------------------+       +------------------------+       +------------------------+
 *                                          +-----------------------+       |     3 Success          |        |     1 Success          |       |     1 Success          |       |     1 Error (C1)       |
 *                                                                          |     2 Error            |        |     2 Errors           |       |     4 Errors           |       | BLOCK compliance = 33% |
 *                                                                          |     1 Unexpected       |        |     1 Unexpected       |       |     1 Unexpected       |       +------------------------+
 *                                                                          | BLOCK compliance = 50% |        | BLOCK compliance = 25% |       | BLOCK compliance = 17% |       | IF C1 was not there :  |
 *                                                                          +------------------------+        +------------------------+       +------------------------+       |     1 Success          |
 *                                                                                                                                                                              |     1 Unexpected       |
 *                                                                                                                                                                              | BLOCK compliance = 50% |
 *                                                                                                                                                                              +------------------------+
 */
sealed trait ReportingLogic {
  def value: String
}

sealed trait WorstReportReportingLogic extends ReportingLogic

object ReportingLogic {

  object FocusReport {
    val key = "focus"
  }
  final case class FocusReport(component: String) extends ReportingLogic {
    val value: String = s"${FocusReport.key}:${component}"
  }

  sealed trait WorstReportWeightedReportingLogic extends WorstReportReportingLogic
  case object WorstReportWeightedOne             extends WorstReportWeightedReportingLogic {
    val value = "worst-case-weighted-one"
  }
  case object WorstReportWeightedSum             extends WorstReportWeightedReportingLogic {
    val value = "worst-case-weighted-sum"
  }
  case object FocusWorst                         extends WorstReportReportingLogic         {
    val value = "focus-worst"
  }

  case object WeightedReport extends ReportingLogic {
    val value = "weighted"
  }

  def parse(value: String, defaultFocusKey: String = ""): PureResult[ReportingLogic] = {
    value.toLowerCase match {
      case WorstReportWeightedOne.value => Right(WorstReportWeightedOne)
      case WorstReportWeightedSum.value => Right(WorstReportWeightedSum)
      case FocusWorst.value             => Right(FocusWorst)
      case WeightedReport.value         => Right(WeightedReport)
      case s"${FocusReport.key}:${a}"   => Right(FocusReport(a))
      case FocusReport.key              => Right(FocusReport(defaultFocusKey))
      case _                            => Left(Unexpected(s"Value '${value}' is not a valid reporting composition rule."))
    }
  }

}
