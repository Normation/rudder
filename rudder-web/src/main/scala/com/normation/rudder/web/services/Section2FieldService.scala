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

package com.normation.rudder.web.services

import bootstrap.liftweb._
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.web.model._
import scala.xml._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.util.Helpers
import scala.collection.mutable.{ Map => MutMap }
import com.normation.cfclerk.domain._
import com.normation.exceptions.TechnicalException
import org.slf4j.LoggerFactory
import com.normation.utils.HashcodeCaching

/**
 * Create web representation of Directive in the goal
 * to configure them.
 *
 * This service does not ask anything on the backend, and
 * so all information have to be given.
 *
 */
class Section2FieldService(val fieldFactory: DirectiveFieldFactory, val translators: Translators) extends Loggable {

  /**
   * Fully initialize a DirectiveEditor from a list of variables
   */
  def initDirectiveEditor(
      policy          : Technique
    , directiveId     : DirectiveId
    , vars            : Seq[Variable]
  ): Box[DirectiveEditor] = {

    val valuesByName = vars.map(v => (v.spec.name, v.values)).toMap
    val variableSpecs = vars.map(v => (v.spec.name -> v.spec)).toMap
    val sections = policy.rootSection.copyWithoutSystemVars

    val providesExpectedReports = policy.providesExpectedReports

    //a policy is a new one if we don't have any saved values
    //Don't forget that we may have empty saved value.
    val isNewPolicy = valuesByName.size < 1 || valuesByName.forall { case (n,vals) => vals.size < 1 }
    logger.debug("Is it a new directive ? " + isNewPolicy)

    val sectionField = createSectionField(sections, valuesByName, isNewPolicy)

    Full(DirectiveEditor(policy.id, directiveId, policy.name, policy.description, sectionField, variableSpecs, providesExpectedReports))
  }

  // --------------------------------------------
  // description of the state machine
  // --------------------------------------------

  /*
   *
   *
   *          ----<--root----->--------
   *   ___   /        |           ___  \
   *  | variable  sectionType1  |   multiSection
   *  |               | `--->--'    |      |       ____
   *   `-----<-----<------'-----<---'      |     /     |
   *                            \         sectionType2 |
   *                             `----<------'   `-->--'
   *
   * sectionType1: a section that may have a multi-section for children
   * sectionType2: a section that may only have simple sub section
   */

  // --------------------------------------------
  // implementation : TODO: implement above state
  // machine for real, not with a copy&paste for
  // createSingleSectionFieldForMultisec
  // --------------------------------------------

  def createSectionField(section: SectionSpec, valuesByName:Map[String,Seq[String]], isNewPolicy:Boolean): SectionField = {
    val seqOfSectionMap = {
      if (isNewPolicy) Seq(createDefaultMap(section))
      else {
        val all = createMapForEachSubSection(section, valuesByName)
        if(all.size < 1) Seq(createDefaultMap(section)) else all
      }
    }

    val readOnlySection = section.children.collect{ case x:PredefinedValuesVariableSpec => x}.size > 0
    if (section.isMultivalued) {
      val sectionFields = for (sectionMap <- seqOfSectionMap) yield createSingleSectionFieldForMultisec(section,sectionMap, isNewPolicy)
      MultivaluedSectionField(sectionFields, () => {
          //here, valuesByName is empty, we are creating a new map.
          createSingleSectionField(section,Map(),createDefaultMap(section), true)
        }
        , priorityToVisibility(section.displayPriority)
        , readOnlySection
      )
    } else {
      createSingleSectionField(section, valuesByName, seqOfSectionMap.head, isNewPolicy)
    }
  }

  private[this] def createSingleSectionField(sectionSpec:SectionSpec, valuesByName:Map[String,Seq[String]], sectionMap: Map[String, Option[String]], isNewPolicy:Boolean): SectionFieldImp = {
    // only variables of the current section
    var varMappings = Map[String, () => String]()

    def createVarField(varSpec: VariableSpec, valueOpt: Option[String]): DirectiveField = {
      val fieldKey = varSpec.name
      val field = fieldFactory.forType(varSpec, fieldKey)

      translators.get(field.manifest) match {
        case None => throw new TechnicalException("No translator from type: " + field.manifest.toString)
        case Some(t) =>
          t.to.get("self") match {
            case None => throw new TechnicalException("Missing 'self' translator property (from type %s to a serialized string for Variable)".format(field.manifest))
            case Some(c) => //close the returned function with f and store it into varMappings
              logger.trace("Add translator for variable '%s', get its value from field '%s.self'".format(fieldKey, fieldKey))
              varMappings += (fieldKey -> { () => c(field.get) })

              valueOpt match {
                case None =>
                case Some(value) =>
                  setValueForField(value, field, t.from)
              }
          }
      }

      field.displayName = varSpec.description
      field.tooltip = varSpec.longDescription
      field.optional = varSpec.constraint.mayBeEmpty
      field
    }

    val children = for (child <- sectionSpec.children) yield {
      child match {
        case varSpec: SectionVariableSpec => createVarField(varSpec, sectionMap(varSpec.name))
        case sectSpec: SectionSpec => createSectionField(sectSpec, valuesByName, isNewPolicy)
      }
    }

    //actually create the SectionField for createSingleSectionField
    SectionFieldImp(sectionSpec.name, children, priorityToVisibility(sectionSpec.displayPriority), varMappings)
  }

  private[this] def createSingleSectionFieldForMultisec(sectionSpec:SectionSpec, sectionMap: Map[String, Option[String]], isNewPolicy:Boolean): SectionFieldImp = {
    // only variables of the current section
    var varMappings = Map[String, () => String]()

    def createVarField(varSpec: SectionVariableSpec, valueOpt: Option[String]): DirectiveField = {
      val fieldKey = varSpec.name
      val field = fieldFactory.forType(varSpec, fieldKey)

      translators.get(field.manifest) match {
        case None => throw new TechnicalException("No translator from type: " + field.manifest.toString)
        case Some(t) =>
          t.to.get("self") match {
            case None => throw new TechnicalException("Missing 'self' translator property (from type %s to a serialized string for Variable)".format(field.manifest))
            case Some(c) => //close the returned function with f and store it into varMappings
              logger.trace("Add translator for variable '%s', get its value from field '%s.self'".format(fieldKey, fieldKey))
              varMappings += (fieldKey -> { () => c(field.get) })

              valueOpt match {
                case None =>
                case Some(value) =>
                  setValueForField(value, field, t.from)
              }
          }
      }

      field.displayName = varSpec.description
      field.tooltip = varSpec.longDescription
      field.optional = varSpec.constraint.mayBeEmpty
      field
    }

    val children = for (child <- sectionSpec.children) yield {
      child match {
        case varSpec: SectionVariableSpec => createVarField(varSpec, sectionMap.getOrElse(varSpec.name,None))
        case sectSpec: SectionSpec =>
          val subSectionMap = if(isNewPolicy) createDefaultMap(sectSpec) else sectionMap
          createSingleSectionFieldForMultisec(sectSpec, subSectionMap, isNewPolicy)
      }
    }

    //actually create the SectionField for createSingleSectionField
    SectionFieldImp(sectionSpec.name, children, priorityToVisibility(sectionSpec.displayPriority), varMappings)
  }

  // Transforms map of all values by Variable Name
  // Into the Sequence of all values by section
  // A section will be defined as a Map of value by variable name
  //   * ie: Map(A -> Seq("A1", "A2"), B -> Seq("B1", "B2"))  => Seq( Map((A -> "A1"), (B -> "B1"))
  //                                                                , Map((A -> "A2"), (B -> "B2")) )
  // If there is no value, a Section with default value is returned is returned
  private def createMapForEachSubSection(section: SectionSpec, valuesByName:Map[String,Seq[String]]): Seq[Map[String, Option[String]]] = {

    // Variable with max value determine the actual number of section
    // If a varaible has 6 values, We need to have 6 sections at the end
    val numberMaxofSections = valuesByName.map(_._2.size).max

    for {
      // For each section
      sectionIndex <- 0 until numberMaxofSections

    } yield {
      ( for {
        varSpec <- section.getAllVariables
        } yield {
          val value = valuesByName.get(varSpec.name) match {
            // No value defined for the variable, get default value
            case None => varSpec.constraint.default
            // Some value for this variable, look if defined for this section
            case Some(values) => values.lift(sectionIndex) match {
              // No value defined, get default value
              case None => varSpec.constraint.default
              // Get value
              case value => value
            }
          }
          (varSpec.name,value)
        }
      ).toMap
    }
  }

  private def createDefaultMap(section: SectionSpec): Map[String, Option[String]] =
    section.getVariables.map(varSpec => (varSpec.name, varSpec.constraint.default)).toMap

  private def setValueForField(
    value: String,
    currentField: DirectiveField,
    unserializer: Unserializer[_]): Unit = {
    //if the var is not a GUI only var, just find the field unserializer and use it
    unserializer.get("self") match {
      case Some(unser) => unser(value) match {
        case Full(fv) => currentField.set(fv.asInstanceOf[currentField.ValueType]) //should be ok since we found the unserializer thanks to the field manifest
        case _ =>
          //let field un-initialized, but log why
          logger.debug("Can not init field %s, translator gave no result for 'self' with value '%s'".
            format(currentField.name, value))
      }
      case None => // can not init, no unserializer for it
        logger.debug("Can not init field %s, no translator found for property 'self'".format(currentField.name))
    }
  }

  /**
   * From a priority, returns the visibility of a section
   * For the moment, a naive approach is :
   * - Low priority => hidden
   * - High priority => displayed
   */
  private[this] def priorityToVisibility(priority : DisplayPriority) : Boolean = {
    priority match {
      case LowDisplayPriority => false
      case HighDisplayPriority => true
      case _ => true
    }
  }
}
