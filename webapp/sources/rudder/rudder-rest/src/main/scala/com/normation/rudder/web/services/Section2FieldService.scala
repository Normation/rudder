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

import com.normation.cfclerk.domain.*
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.web.model.*
import com.normation.utils.Control.traverse
import net.liftweb.common.*

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
      policy:      Technique,
      directiveId: DirectiveUid,
      vars:        Seq[Variable]
  ): Box[DirectiveEditor] = {

    val valuesByName  = vars.map(v => (v.spec.name, v.values)).toMap
    val variableSpecs = vars.map(v => (v.spec.name -> v.spec)).toMap
    val sections      = policy.rootSection.copyWithoutSystemVars

    // a policy is a new one if we don't have any saved values
    // Don't forget that we may have empty saved value.
    val isNewPolicy = valuesByName.size < 1 || valuesByName.forall { case (n, vals) => vals.size < 1 }
    logger.debug("Is it a new directive ? " + isNewPolicy)

    val bounds = vars.map { v =>
      val used = v.spec.constraint.usedFields
      if (used.isEmpty) {
        Option.empty[(String, Seq[String])]
      } else {
        Some((v.spec.name, used.toSeq))
      }
    }.flatten.toMap

    // create the fields "used" mapping
    val sectionField = createSectionField(sections, valuesByName, isNewPolicy, bounds)

    // check if any variable is not predefined
    val directiveEditable = variableSpecs.values.exists(x => {
      x match {
        case spec: PredefinedValuesVariableSpec => false
        case _ => true
      }
    })
    Full(DirectiveEditor(policy.id, directiveId, policy.name, policy.description, sectionField, variableSpecs, directiveEditable))
  }

  // bound used section fields to
  private def boundUsedFields(section: SectionField, bounds: Map[String, Seq[String]]): SectionField = {
    val allFields = section.getAllDirectVariables
    allFields.values.foreach { f =>
      bounds.get(f.id) match {
        case None       => Full("ok")
        case Some(used) =>
          (for {
            fields <- traverse(used) { id =>
                        Box(
                          allFields.get(id)
                        ) ?~! s"Variable '${id}' used by variable '${f.id}' was not found - are you sure all dependant fields are on the same section of the technique?"
                      }
          } yield {
            fields
          }) match {
            case e: EmptyBox =>
              logger.error((e ?~! "Error when binding dependant fields").messageChain)
            case Full(fields) =>
              f.usedFields = fields
          }
      }
    }
    section
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

  def createSectionField(
      section:      SectionSpec,
      valuesByName: Map[String, Seq[String]],
      isNewPolicy:  Boolean,
      usedFields:   Map[String, Seq[String]]
  ): SectionField = {
    val seqOfSectionMap = {
      if (isNewPolicy) Seq(createDefaultMap(section))
      else {
        val all = createMapForEachSubSection(section, valuesByName)
        if (all.size < 1) Seq(createDefaultMap(section)) else all
      }
    }

    val readOnlySection = section.children.collect { case a: PredefinedValuesVariableSpec => a }.size > 0
    if (section.isMultivalued) {
      val sectionFields = {
        for (sectionMap <- seqOfSectionMap)
          yield boundUsedFields(createSingleSectionFieldForMultisec(section, sectionMap, isNewPolicy, usedFields), usedFields)
      }
      MultivaluedSectionField(
        sectionFields,
        () => {
          // here, valuesByName is empty, we are creating a new map.
          boundUsedFields(
            createSingleSectionField(section, Map(), createDefaultMap(section), isNewPolicy = true, usedFields = usedFields),
            usedFields
          )
        },
        priorityToVisibility(section.displayPriority),
        readOnlySection
      )
    } else {
      boundUsedFields(createSingleSectionField(section, valuesByName, seqOfSectionMap.head, isNewPolicy, usedFields), usedFields)
    }
  }

  private def createVarField(varSpec: VariableSpec, valueOpt: Option[String]): (DirectiveField, (String, () => String)) = {
    val fieldKey = varSpec.name
    val field    = fieldFactory.forType(varSpec, fieldKey)

    val varMappings = translators.get(field.manifest) match {
      case None    => throw new IllegalArgumentException("No translator from type: " + field.manifest.toString)
      case Some(t) =>
        t.to.get("self") match {
          case None    =>
            throw new IllegalArgumentException(
              s"Missing 'self' translator property (from type '${field.manifest}' to a serialized string for Variable)"
            )
          case Some(c) => // close the returned function with f and store it into varMappings
            logger.trace("Add translator for variable '%s', get its value from field '%s.self'".format(fieldKey, fieldKey))
            valueOpt match {
              case None        =>
                varSpec.constraint.default foreach (setValueForField(_, field, t.from))
              case Some(value) =>
                setValueForField(value, field, t.from)
            }
            (fieldKey -> { () => c(field.get) })
        }
    }

    field.displayName = varSpec.description
    field.tooltip = varSpec.longDescription
    field.optional = varSpec.constraint.mayBeEmpty
    (field, varMappings)
  }

  private def createSingleSectionField(
      sectionSpec:  SectionSpec,
      valuesByName: Map[String, Seq[String]],
      sectionMap:   Map[String, Option[String]],
      isNewPolicy:  Boolean,
      usedFields:   Map[String, Seq[String]]
  ): SectionField = {
    // only variables of the current section
    var varMappings = Map[String, () => String]()

    val children = for (child <- sectionSpec.children) yield {
      child match {
        case varSpec:  SectionVariableSpec =>
          val (field, mapping) = createVarField(varSpec, sectionMap(varSpec.name))
          varMappings += mapping
          field
        case sectSpec: SectionSpec         =>
          boundUsedFields(createSectionField(sectSpec, valuesByName, isNewPolicy, usedFields), usedFields)
      }
    }

    // actually create the SectionField for createSingleSectionField
    boundUsedFields(
      SectionFieldImp(sectionSpec.name, children, priorityToVisibility(sectionSpec.displayPriority), varMappings),
      usedFields
    )
  }

  private def createSingleSectionFieldForMultisec(
      sectionSpec: SectionSpec,
      sectionMap:  Map[String, Option[String]],
      isNewPolicy: Boolean,
      usedFields:  Map[String, Seq[String]]
  ): SectionFieldImp = {
    // only variables of the current section
    var varMappings = Map[String, () => String]()

    val children = for (child <- sectionSpec.children) yield {
      child match {
        case varSpec:  SectionVariableSpec =>
          val (field, mapping) = createVarField(varSpec, sectionMap.getOrElse(varSpec.name, None))
          varMappings += mapping
          field
        case sectSpec: SectionSpec         =>
          val subSectionMap = if (isNewPolicy) createDefaultMap(sectSpec) else sectionMap
          boundUsedFields(createSingleSectionFieldForMultisec(sectSpec, subSectionMap, isNewPolicy, usedFields), usedFields)
      }
    }

    // actually create the SectionField for createSingleSectionField
    SectionFieldImp(sectionSpec.name, children, priorityToVisibility(sectionSpec.displayPriority), varMappings)
  }

  // transforms
  // Map(A -> Seq("A1", "A2"), B -> Seq("B1", "b2"))
  // to
  // Seq( Map((A -> "A1"), (B -> "B1")),
  //      Map((A -> "A2"), (B -> "B2")) )
  // If there is no value, a None is returned
  private def createMapForEachSubSection(
      section:      SectionSpec,
      valuesByName: Map[String, Seq[String]]
  ): Seq[Map[String, Option[String]]] = {
    // values represent all the values we have for the same name of variable
    final case class NameValuesVar(name: String, values: Seq[String])

    // seq of variable values with same name correctly ordered
    val seqOfNameValues: Seq[NameValuesVar] = {
      for {
        varSpec <- section.getAllVariables
      } yield {
        NameValuesVar(varSpec.name, valuesByName.getOrElse(varSpec.name, Seq[String]()))
      }
    }

    if (seqOfNameValues.isEmpty) {
      Seq(Map[String, Option[String]]())
    } else {
      for {
        // If head has an empty sequence as value, it does not iterate for other variables
        // To fix, we use the max size of of all variables (so those value can be used, missing will be set to None.
        i <- 0 until seqOfNameValues.map(_.values.size).max
      } yield {
        for {
          nameValues <- seqOfNameValues
        } yield {
          val valueOpt = {
            try Some(nameValues.values(i))
            catch { case e: Exception => None }
          }
          (nameValues.name, valueOpt)
        }
      }.toMap
    }
  }

  private def createDefaultMap(section: SectionSpec): Map[String, Option[String]] =
    section.getVariables.map(varSpec => (varSpec.name, varSpec.constraint.default)).toMap

  private def setValueForField(value: String, currentField: DirectiveField, unserializer: Unserializer[?]): Unit = {
    // if the var is not a GUI only var, just find the field unserializer and use it
    unserializer.get("self") match {
      case Some(unser) =>
        unser(value) match {
          case Full(fv) =>
            currentField.set(
              fv.asInstanceOf[currentField.ValueType]
            ) // should be ok since we found the unserializer thanks to the field manifest
          case _        =>
            // let field un-initialized, but log why
            logger.debug(
              "Can not init field %s, translator gave no result for 'self' with value '%s'".format(currentField.name, value)
            )
        }
      case None        => // can not init, no unserializer for it
        logger.debug("Can not init field %s, no translator found for property 'self'".format(currentField.name))
    }
  }

  /**
   * From a priority, returns the visibility of a section
   * For the moment, a naive approach is :
   * - Low priority => hidden
   * - High priority => displayed
   */
  private def priorityToVisibility(priority: DisplayPriority): Boolean = {
    priority match {
      case LowDisplayPriority  => false
      case HighDisplayPriority => true
    }
  }
}
