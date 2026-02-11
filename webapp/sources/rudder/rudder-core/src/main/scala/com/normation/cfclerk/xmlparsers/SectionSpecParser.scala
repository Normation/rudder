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

package com.normation.cfclerk.xmlparsers

import cats.implicits.*
import com.normation.cfclerk.domain.*
import com.normation.cfclerk.xmlparsers.CfclerkXmlConstants.*
import net.liftweb.common.*
import scala.xml.*

class SectionSpecParser(variableParser: VariableSpecParser, validateReporting: Boolean = true) extends Loggable {

  def parseSectionsInPolicy(policy: Node, id: TechniqueId, policyName: String): Either[LoadTechniqueError, SectionSpec] = {
    val sections = policy \\ SECTIONS_ROOT

    (if (sections.size > 1) {
       val err = s"In ${id.debugString} -> ${policyName} : Only one <sections> marker is allowed in the entire file"
       logger.error(err)
       Left(LoadTechniqueError.Parsing(err).asInstanceOf[LoadTechniqueError])
     } else {
       Right("ok")
     }) *> {
      if (sections.isEmpty) {
        Right(SectionSpec(SECTION_ROOT_NAME))
      } else {
        parseChildren(SECTION_ROOT_NAME, sections.head, id, policyName).flatMap { children =>
          val root = SectionSpec(SECTION_ROOT_NAME, children = children)

          /*
           * check that all section names and all variable names are unique
           */
          val variableNames = root.getAllVariables.map(_.name)

          /*
           * Check that all section with defined component key reference existing
           * variables
           */
          val componentDefined = root.getAllSections.toList.traverse { section =>
            section.componentKey match {
              case None      => ().validNel
              case Some(key) =>
                if (variableNames.find(key == _).isEmpty) {
                  LoadTechniqueError
                    .Parsing(
                      s"Section '${section.name}' reference as component key variable '${key}' that was " +
                      s"not found. Know variables are: ${variableNames.mkString("[", ",", "]")}"
                    )
                    .invalidNel
                } else {
                  ().validNel
                }
            }
          }.map(_ => ())

          /*
           * check that root section only hold sub-section (and no variables)
           */
          val rootOnlyHasSection = root.children.toList.traverse { child =>
            child match {
              case v: SectionVariableSpec =>
                LoadTechniqueError.Consistancy(s"Variable declaration '${v.name}' is not allowed here").invalidNel
              case _ => ().validNel
            }
          }.leftMap(errs => LoadTechniqueError.Parsing(s"<${SECTIONS_ROOT}> must contain only <${SECTION}> children") :: errs)
            .map(_ => ())

          /*
           * Check that all "used" variable are (at least) defined elsewhere in the technique
           */
          val definedNotUsed   = {
            val varNames = root.getAllVariables.map(_.name).toSet
            val used     = root.getAllVariables.flatMap(_.constraint.usedFields).toSet
            used -- varNames
          }
          val usedButUndefined = if (definedNotUsed.nonEmpty) {
            LoadTechniqueError
              .Parsing(s"The following variables names are used in <${CONSTRAINT_PWD_AUTOSUBVARIABLES}>, but are not defined: '${definedNotUsed
                  .mkString("','")}'")
              .invalidNel
          } else {
            ().validNel
          }

          val check = rootOnlyHasSection |+| componentDefined |+| usedButUndefined

          val res: Either[LoadTechniqueError, SectionSpec] =
            check.fold(errs => Left(LoadTechniqueError.Accumulated(errs)), _ => Right(root))
          res
        }
      }
    }
  }

  // method that actually parse a <SECTIONS> or <SECTION> tag
  private def parseSection(root: Node, id: TechniqueId, policyName: String): Either[LoadTechniqueError, SectionSpec] = {

    val optName = {
      val n = Utils.getAttributeText(root, "name", "")
      if (root.label == SECTIONS_ROOT) {
        if (n.nonEmpty) Left(LoadTechniqueError.Parsing(s"<${SECTIONS_ROOT}> can not have a 'name' attribute."))
        else Right(SECTION_ROOT_NAME)
      } else {
        if (n.nonEmpty) Right(n)
        else Left(LoadTechniqueError.Parsing("Section must have name. Missing name for: " + root))
      }
    }

    // The defaut priority is "high"
    val displayPriority =
      DisplayPriority(Utils.getAttributeText(root, SECTION_DISPLAYPRIORITY, "")).getOrElse(HighDisplayPriority)

    val description = Utils.getUniqueNodeText(root, SECTION_DESCRIPTION, "")

    val componentKey = (root \ ("@" + SECTION_COMPONENT_KEY)).headOption.map(_.text) match {
      case null | Some("") => None
      case x               => x
    }

    val cid = (root \ ("@id")).headOption.map(_.text)

    val reporting = (root \ ("@reporting")).headOption.map(_.text) match {
      case null | Some("") | None => None
      case Some(x)                =>
        ReportingLogic.parse(x) match {
          case Right(x)    => Some(x)
          case Left(error) =>
            logger.error(
              s"There was an error when parsing reporting logic in technique ${id.name.value}/${id.version.debugString}, error is : ${error.msg} for element ${root}"
            )
            None
        }
    }
    // Checking if we have predefined values
    for {
      name                      <- optName
      children                  <- parseChildren(name, root, id, policyName)
      expectedReportComponentKey = (children.collect { case x: PredefinedValuesVariableSpec => x }) match {
                                     case seq if seq.isEmpty               => None
                                     case seq if seq.lengthCompare(1) == 0 => Some(seq.head.name)
                                     case seq                              =>
                                       logger.error(
                                         s"There are too many predefined reports keys for given section ${name} : keys are : ${seq.map(_.name).mkString(",")}"
                                       )
                                       None
                                   }
      effectiveComponentKey     <- (componentKey, expectedReportComponentKey) match {
                                     case (Some(cp), Some(excp)) if cp == excp =>
                                       Right(Some(cp))
                                     case (Some(cp), Some(excp)) if cp != excp =>
                                       Left(
                                         LoadTechniqueError.Parsing(
                                           s"Section '${name}' has a defined component key and defined reports key elements."
                                         )
                                       )
                                     case (Some(cp), _)                        => Right(Some(cp))
                                     case (_, Some(excp))                      => Right(Some(excp))
                                     case _                                    => Right(None)
                                   }

      // sanity check or derived values from what is before, or the descriptor itself
      isMultivalued              = ("true" == Utils
                                     .getAttributeText(root, SECTION_IS_MULTIVALUED, "false")
                                     .toLowerCase || expectedReportComponentKey.isDefined)
      isComponent                = ("true" == Utils
                                     .getAttributeText(root, SECTION_IS_COMPONENT, "false")
                                     .toLowerCase || expectedReportComponentKey.isDefined)
      /*
       * A key must be defined if and only if we are in a multivalued, component section.
       * This is historically a constraint on reporting, but since 7.0 with reporting by block,
       * we may bypass checking reporting attributes for change requests of directives :
       *  - system/old techniques have component keys
       *  - editor techniques have mandatory reporting defined on blocks, but were never written by SectionSpecWriter (bug)
       * See https://issues.rudder.io/issues/27974
       */
      isInvalidReportingState    = {
        val isMultivaluedComponent = isMultivalued && isComponent
        val isInvalidReporting     = effectiveComponentKey.isEmpty && reporting.isEmpty
        validateReporting && isMultivaluedComponent && isInvalidReporting
      }
      _                         <- if (isInvalidReportingState) {
                                     Left(
                                       LoadTechniqueError.Parsing(
                                         "Section '%s' is multivalued and is component. A componentKey attribute must be specified".format(name)
                                       )
                                     )
                                   } else Right("ok")
      sectionSpec                = SectionSpec(
                                     name,
                                     isMultivalued,
                                     isComponent,
                                     effectiveComponentKey,
                                     displayPriority,
                                     description,
                                     children,
                                     reporting,
                                     cid
                                   )
      res                       <- if (isMultivalued) sectionSpec.cloneVariablesInMultivalued
                                   else Right(sectionSpec)
    } yield {
      res
    }
  }

  private def parseChildren(
      sectionName: String,
      node:        Node,
      id:          TechniqueId,
      policyName:  String
  ): Either[LoadTechniqueError, Seq[SectionChildSpec]] = {
    assert(node.label == SECTIONS_ROOT || node.label == SECTION)

    def parseOneVariable(
        sectionName: String,
        node:        Node
    ): Either[LoadTechniqueError, (SectionChildSpec, List[SectionChildSpec])] = {
      variableParser
        .parseSectionVariableSpec(sectionName, node)
        .leftMap(err =>
          LoadTechniqueError.Chained(s"In ${id.debugString} -> ${policyName}, couldn't parse variable ${node}", err)
        )
    }

    def parseOneSection(node: Node, id: TechniqueId, policyName: String): Either[LoadTechniqueError, SectionSpec] = {
      parseSection(node, id, policyName).leftMap(err =>
        LoadTechniqueError.Chained(s"Couldn't parse Section in ${id.debugString} -> ${policyName} for XML: ${node}", err)
      )
    }

    (node.child
      .filter(child => !child.isEmpty && child.label != "#PCDATA")
      .toList
      .traverse { child =>
        child.label match {
          case v if SectionVariableSpec.isVariable(v) => parseOneVariable(sectionName, child).map { case (a, b) => a :: b }
          case s if SectionSpec.isSection(s)          => parseOneSection(child, id, policyName).map(s => s :: Nil)
          case x                                      =>
            Left(LoadTechniqueError.Parsing(s"Unexpected <${SECTIONS_ROOT}> child element in technique ${id.debugString}: ${x}"))
        }
      })
      .map(_.flatten)
  }
}
