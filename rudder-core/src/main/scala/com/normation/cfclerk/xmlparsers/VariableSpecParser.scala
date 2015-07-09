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

package com.normation.cfclerk.xmlparsers

import com.normation.cfclerk.domain._
import CfclerkXmlConstants._
import scala.xml._
import net.liftweb.common._
import com.normation.utils.XmlUtils._
import com.normation.cfclerk.exceptions._
import com.normation.exceptions.TechnicalException
import com.normation.utils.Control


case class EmptyReportKeysValue(sectionName: String) extends Exception(s"In '${sectionName}', the element ${REPORT_KEYS} must have a non empty list of provided values: <${REPORT_KEYS}><${REPORT_KEYS_VALUE}>val foo</${REPORT_KEYS_VALUE}><${REPORT_KEYS_VALUE}>...")

class VariableSpecParser {


  private[this] val reservedVariableName = DEFAULT_COMPONENT_KEY :: TRACKINGKEY :: Nil

  /*
     * check if the given value is the text token for
     * the given variable category.
     * Used in pattern guard
     */
  private def isA(value: String, categoryName: String): Boolean = categoryName.toLowerCase == value.toLowerCase

  def parseTrackerVariableSpec(node:Node): Box[TrackerVariableSpec] = {
    if(node.label != TRACKINGVAR) {
      Failure("Bad node: I was expecting a <%s> and got: %s".format(TRACKINGVAR, node))
    } else {
      Full(TrackerVariableSpec((node \ TRACKINGVAR_SIZE).headOption.map( _.text )))
    }
  }


  def parseSectionVariableSpec(parentSectionName: String, elt: Node): Box[SectionVariableSpec] = {

    val markerName = elt.label
    if (!SectionVariableSpec.isVariable(markerName)) {
      Failure("The node '%s' is not a variable specification node: it should be one of %s"
        .format(markerName, SectionVariableSpec.markerNames.mkString("<", ">, <", ">")))

    /*
     * here we have two cases:
     * - we have a REPORTKEYS. It's not a variable from the user point of view,
     *   it's the list of report keys for a given component.
     *   But internally, we use a variable, because it's the way Rudder handle reporting
     * - or we really have a variable, and in that case, well, it's a variable.
     *
     * So, we need to special case the REPORTKEYS to give them a name and a description,
     * and not allow the user to change other information about them.
     *
     */
    } else if(markerName == REPORT_KEYS) {
      //we only want the values, other tag are ignored

      val p = parseProvidedValues(elt)

      Full(SectionVariableSpec(
          varName = reportKeysVariableName(parentSectionName)
        , description = s"Expected Report key names for component ${parentSectionName}"
        , markerName = markerName
        , longDescription = ""
        , valueslabels = Nil
        , isUniqueVariable = false
        , multivalued = true
        , checked = true
        , constraint = Constraint()
        , p
      ))
    } else { //normal variable

      //name and description are mandatory
      (getUniqueNodeText(elt, VAR_NAME, ""),
        getUniqueNodeText(elt, VAR_DESCRIPTION, "")) match {
          case ("", _) => Failure("Name is mandatory but wasn't found for variable spec elt: " + elt)
          case (_, "") => Failure("Description is mandatory but wasn't found for variable spec elt: " + elt)

          case (name, desc) =>

            reservedVariableName.find(reserved => name == reserved).foreach { reserved =>
              throw new ParsingException("Name '%s' is reserved and can not be used for a variable".format(reserved))
            }

            val longDescription = getUniqueNodeText(elt, VAR_LONG_DESCRIPTION, "")

            val items = parseItems(elt)

            val isUniqueVariable = "true" == getUniqueNodeText(elt, VAR_IS_UNIQUE_VARIABLE, "false").toLowerCase

            val multiValued = getUniqueNodeText(elt, VAR_IS_MULTIVALUED, "false").toLowerCase match {
              case "true" => true
              case _ => false
            }

            val constraint: Constraint = (elt \ VAR_CONSTRAINT).toList match {
              case h :: Nil => parseConstraint(h)
              case Nil => Constraint()
              case _ => throw new TechniqueException("Only one <%s> it authorized".format(VAR_CONSTRAINT))
            }

            val checked = "true" == getUniqueNodeText(elt, VAR_IS_CHECKED, "true").toLowerCase


            Full(SectionVariableSpec(
              varName = name,
              description = desc,
              markerName = markerName,
              longDescription = longDescription,
              valueslabels = items,
              isUniqueVariable = isUniqueVariable,
              multivalued = multiValued,
              checked = checked,
              constraint = constraint,
              Nil
            ))
        }
    }
  }

  /**
   * @param constraint ex :
   * <ITEM><LABEL>IPv4 only</LABEL><VALUE>inet</VALUE></ITEM>
   * <ITEM label="IPv6 only" value="inet6"/>
   * <ITEM label="do not change" value="dontchange">
   *   <LABEL>Don't change</LABEL>
   * </ITEM>
   *
   * In the above example, for the last ENTRY, the LABEL will be "Don't change"
   * and not "do not change" because we have chosen to give a higher priority
   * to the inner markers over the attributes.
   */
  def parseItems(elt: NodeSeq): Seq[ValueLabel] = {
    for (entry <- elt \ CONSTRAINT_ITEM) yield {
      val (v, l) = (entry \ CONSTRAINT_ITEM_VALUE, entry \ CONSTRAINT_ITEM_LABEL)
      val value = if (!v.isEmpty) v else entry \ "@value"
      val label = if (!l.isEmpty) l else entry \ "@label"
      ValueLabel(value.text, label.text)
    }
  }


  /**
   * Parse provided values, they are of the form:
   * <REPORTKEYS><VALUE>val1</VALUE>...</REPORTKEYS>
   */
  def parseProvidedValues(elt: Node): Seq[String] = {
    (for {
        value <- elt \ REPORT_KEYS_VALUE
     } yield {
      value.text
     }).map( _.trim ).filter( _.nonEmpty )
  }

  def parseConstraint(elt: Node): Constraint = {

    val passwordHashes = getUniqueNodeText(elt, CONSTRAINT_PASSWORD_HASH, "")

    val regexConstraint = getUniqueNode(elt, CONSTRAINT_REGEX) match {
      case x: EmptyBox => None
      case Full(x) => Some(RegexConstraint(x.text.trim, (x \ "@error").text))
    }


    val e = getUniqueNodeText(elt, CONSTRAINT_TYPE, "string")

    def regexError(vt:VTypeConstraint) = throw new ConstraintException(s"type '${vt.name}' already has a predifined regex (you can't define a regex with these types : ${VTypeConstraint.regexTypes.map( _.name).mkString(",")}).")
    val typeName: VTypeConstraint = VTypeConstraint.fromString(e, regexConstraint, parseAlgoList(passwordHashes)) match {
      case None =>  throw new ConstraintException(s"'${e}' is an invalid type.\n A type may be one of the next list : ${VTypeConstraint.allTypeNames}")
      case Some(r:FixedRegexVType) => if(regexConstraint.isDefined) regexError(r) else r
      case Some(t) => t
    }


    val mayBeEmpty = "true" == getUniqueNodeText(elt, CONSTRAINT_MAYBEEMPTY, "false").toLowerCase

    val defaultValue: Option[String] = (elt \ CONSTRAINT_DEFAULT) match {
      case NodeSeq.Empty => None
      case s: NodeSeq => Some(s.text)
    }


    Constraint(typeName, defaultValue, mayBeEmpty)
  }

  private[this] def parseAlgoList(algos:String) : Seq[HashAlgoConstraint] = {
    if(algos.trim.isEmpty) HashAlgoConstraint.algorithmes
    else {
      Control.sequence(algos.split(",")) { algo =>
        HashAlgoConstraint.fromString( algo.trim )
      } match {
        case Full(seq) => seq
        case eb:EmptyBox => throw new ConstraintException( (eb ?~! s"Error when parsing the list of password hash: '${algos}'").messageChain )
      }
    }
  }

}