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

import com.normation.cfclerk.domain._
import CfclerkXmlConstants._
import scala.xml._
import net.liftweb.common._
import com.normation.utils.Control
import com.normation.cfclerk.domain.HashAlgoConstraint.DerivedPasswordType

object Utils {
  /**
   * Retrieve exactly one element of the given name in
   * the given node children.
   * If subtree is set to true (default to false), look
   * for all tree elements, not only direct children.
   */
  def getUniqueNode(root:Node, nodeName:String, subtree:Boolean = false) : Either[LoadTechniqueError, Node] = {
    def checkCardinality(nodes:NodeSeq) : Either[LoadTechniqueError, Node] = {
      if(nodes.size < 1) Left(LoadTechniqueError.Consistancy(s"No node found for name ${nodeName} in ${root} children with scope ${if(subtree) "subtree" else "one level"}"))
      else if(nodes.size > 1 ) Left(LoadTechniqueError.Consistancy(s"More than one node found for name ${nodeName} in ${root} children with scope ${if(subtree) "subtree" else "one level"}"))
      else Right(nodes.head)
    }

    if(subtree) {
      checkCardinality((root.child:NodeSeq) \\ nodeName)
    } else {
      checkCardinality(root \ nodeName)
    }
  }

  //text version of XmlUtils.getUniqueNode with a default value
  //The default value is also applied if node exists but its text is empty
  def getUniqueNodeText(root: Node, nodeName: String, default: String) =
    getUniqueNode(root, nodeName).map(_.text.trim) match {
      case Left(_) => default
      case Right(x) => x match {
        case "" | null => default
        case text => text
      }
  }

  def getAttributeText(node: Node, name: String, default: String) = {
    val seq = node \ ("@" + name)
    if (seq.isEmpty) default else seq.head.text
  }
}

import Utils._

case class EmptyReportKeysValue(sectionName: String) extends Exception(s"In '${sectionName}', the element ${REPORT_KEYS} must have a non empty list of provided values: <${REPORT_KEYS}><${REPORT_KEYS_VALUE}>val foo</${REPORT_KEYS_VALUE}><${REPORT_KEYS_VALUE}>...")

class VariableSpecParser extends Loggable {


  private[this] val reservedVariableName = DEFAULT_COMPONENT_KEY :: TRACKINGKEY :: Nil

  def parseTrackerVariableSpec(node:Node): Either[LoadTechniqueError, TrackerVariableSpec] = {
    if(node.label != TRACKINGVAR) {
      Left(LoadTechniqueError.Consistancy(s"Bad node: I was expecting a <${TRACKINGVAR}> and got: ${node}"))
    } else {
      Right(TrackerVariableSpec((node \ TRACKINGVAR_SIZE).headOption.map( _.text )))
    }
  }


  def parseSectionVariableSpec(parentSectionName: String, elt: Node): Either[LoadTechniqueError, (SectionVariableSpec, List[SectionVariableSpec])] = {

    val markerName = elt.label
    if (!SectionVariableSpec.isVariable(markerName)) {
      Left(LoadTechniqueError.Consistancy(s"The node '${markerName}' is not a variable specification node: it should be one of ${SectionVariableSpec.markerNames.mkString("<", ">, <", ">")}"))

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

      Right((SectionVariableSpec(
          varName = reportKeysVariableName(parentSectionName)
        , description = s"Expected Report key names for component ${parentSectionName}"
        , markerName = markerName
        , longDescription = ""
        , valueslabels = Nil
        , multivalued = true
        , checked = true
        , constraint = Constraint()
        , p
      ), Nil))
    } else { //normal variable

      //name and description are mandatory
      (getUniqueNodeText(elt, VAR_NAME, ""),
        getUniqueNodeText(elt, VAR_DESCRIPTION, "")) match {
          case ("", _) => Left(LoadTechniqueError.Consistancy("Name is mandatory but wasn't found for variable spec elt: " + elt))
          case (_, "") => Left(LoadTechniqueError.Consistancy("Description is mandatory but wasn't found for variable spec elt: " + elt))

          case (name, desc) =>

            for {
              _ <-            reservedVariableName.find(reserved => name == reserved) match {
                                case None    => Right("ok")
                                case Some(x) => Left(LoadTechniqueError.Consistancy(s"Name '${x}' is reserved and can not be used for a variable"))
                              }
              longDescription = getUniqueNodeText(elt, VAR_LONG_DESCRIPTION, "")
              items           = parseItems(elt)
              _               =   // we use to have a "uniqueVariable" field that is not supported anymore in Rudder 4.3
                                  // => warn if it is used in the the technique.
                                  if(getUniqueNode(elt, "UNIQUEVARIABLE").isRight) {
                                    logger.warn(s"Since Rudder 4.3, a variable can not be marked as 'UNIQUEVARIABLE' anymore and that attribute will be ignored. In place, " +
                                        "you should use a Rudder parameter to denote an unique value, or a Node Property for a value unique for a given node. To denote an " +
                                        "action unique to all directive derived from the same technique, you should use pre- or post-agent-run hooks")
                                  }
              multiValued     = getUniqueNodeText(elt, VAR_IS_MULTIVALUED, "false").toLowerCase match {
                                  case "true" => true
                                  case _ => false
                                }
              varPair        <- (elt \ VAR_CONSTRAINT).toList match {
                                              case h :: Nil => parseConstraint(name, h)
                                              case Nil      => Right((Constraint(), Nil))
                                              case _        => Left(LoadTechniqueError.Consistancy(s"Only one <${VAR_CONSTRAINT}> is authorized"))
                                            }
              checked         = "true" == getUniqueNodeText(elt, VAR_IS_CHECKED, "true").toLowerCase
            } yield {
              (SectionVariableSpec(
                  varName         = name
                , description     = desc
                , markerName      = markerName
                , longDescription = longDescription
                , valueslabels    = items
                , multivalued     = multiValued
                , checked         = checked
                , constraint      = varPair._1
                , Nil
              ), varPair._2)
            }
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

  def parseConstraint(varName: String, elt: Node): Either[LoadTechniqueError, (Constraint, List[SectionVariableSpec])] = {

    val passwordHashes = getUniqueNodeText(elt, CONSTRAINT_PASSWORD_HASH, "")

    val regexConstraint = getUniqueNode(elt, CONSTRAINT_REGEX) match {
      case Left(_)  => None
      case Right(x) => Some(RegexConstraint(x.text.trim, (x \ "@error").text))
    }


    val e = getUniqueNodeText(elt, CONSTRAINT_TYPE, "string")

    def regexError(vt:VTypeConstraint) = Left(LoadTechniqueError.Constraint(s"type '${vt.name}' already has a predifined regex (you can't define a regex with these types : ${VTypeConstraint.regexTypes.map( _.name).mkString(",")})."))

    val resTypeName: Either[LoadTechniqueError, VTypeConstraint] = VTypeConstraint.fromString(e, regexConstraint, parseAlgoList(passwordHashes)) match {
      case None =>  Left(LoadTechniqueError.Constraint(s"'${e}' is an invalid type.\n A type may be one of the next list : ${VTypeConstraint.allTypeNames}"))
      case Some(r:FixedRegexVType) => if(regexConstraint.isDefined) regexError(r) else Right(r)
      case Some(t) => Right(t)
    }


    val mayBeEmpty = "true" == getUniqueNodeText(elt, CONSTRAINT_MAYBEEMPTY, "false").toLowerCase

    val defaultValue: Option[String] = (elt \ CONSTRAINT_DEFAULT) match {
      case NodeSeq.Empty => None
      case s: NodeSeq => Some(s.text)
    }

    //other fields needed used by that field - for now, we have only a working case
    //for masterPassword, so we want to ensure that only that one is parsed
    //First, an utility method to build a derivedPassword field from a name and type
    def derivedPasswordVar(
        parentName  : String
      , tpe         : DerivedPasswordType
      , mayBeEmpty  : Boolean
      , defaultValue: Option[String]
    ): SectionVariableSpec = {
      val (postfix, vtype) = tpe match {
        case DerivedPasswordType.AIX   => ("AIX"  , AixDerivedPasswordVType  )
        case DerivedPasswordType.Linux => ("LINUX", LinuxDerivedPasswordVType)
      }

      SectionVariableSpec(
          varName        = s"${parentName}_${postfix}"
        , description    = s"This password field value is derived from input value from ${parentName}"
        , markerName     = INPUT
        , constraint     = Constraint(vtype, defaultValue, mayBeEmpty, Set())
        , valueslabels   = Nil
        , providedValues = Nil
      )
    }

    //then, the actual match
    for {
      typeName <- resTypeName
    } yield {
      val derivedVars = typeName match {
        case MasterPasswordVType(_) => getUniqueNodeText(elt, CONSTRAINT_PWD_AUTOSUBVARIABLES, "").split(",").flatMap { s =>
            s.toLowerCase.trim match {
              case "aix"  =>
                Some(derivedPasswordVar(varName, DerivedPasswordType.AIX,   mayBeEmpty, defaultValue))

              case "linux" =>
                Some(derivedPasswordVar(varName, DerivedPasswordType.Linux, mayBeEmpty, defaultValue))

              case _ => None
            }
          }.toList
        case _ => Nil
      }

      (Constraint(typeName, defaultValue, mayBeEmpty, derivedVars.map(_.name).toSet), derivedVars)
    }
  }

  private[this] def parseAlgoList(algos:String) : Seq[HashAlgoConstraint] = {
    if(algos.trim.isEmpty) HashAlgoConstraint.sort(HashAlgoConstraint.algorithms)
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
