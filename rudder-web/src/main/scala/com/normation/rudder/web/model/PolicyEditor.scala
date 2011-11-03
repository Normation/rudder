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

package com.normation.rudder.web.model

import net.liftweb.util.Helpers
import com.normation.rudder.domain.policies.PolicyInstanceId
import net.liftweb.common.Box
import scala.collection.mutable.Buffer
import net.liftweb.util.BaseField
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import net.liftweb.common._
import org.joda.time.{ DateTime, LocalDate, LocalTime, Duration, Period }
import org.joda.time.format._
import com.normation.utils.Utils._

import java.util.Locale

import org.slf4j.LoggerFactory

import scala.xml._
import net.liftweb.http._
import js._
import JsCmds._
import JE._
import net.liftweb.util.Helpers._
import com.normation.cfclerk.domain.{ VariableSpec, PolicyPackageId, PolicyPackage }

import com.normation.exceptions.TechnicalException

/**
 * A displayable field has 2 methods :
 * -> toHtmlNodeSeq and toFormNodeSeq : to display the form
 */
trait DisplayableField extends {
  def toHtmlNodeSeq: NodeSeq

  def toFormNodeSeq: NodeSeq
}

sealed trait SectionChildField extends DisplayableField with Loggable {
  //retrieve the value as a client string
  def toClient: String

  // get current sections and variables in sub section
  def getAllSectionFields: List[SectionField] = this match {
    case variable: PolicyField => Nil
    case section: SectionField =>
      section :: section.childFields.flatMap(_.getAllSectionFields).toList
  }

  /*
   * Convention: displayHtml is a "read only" 
   * version of toFormNodeSeq 
   */
  def displayHtml: Text
}

trait PolicyField extends BaseField with SectionChildField {
  val id: String

  require(nonEmpty(id), "A field ID can not be null nor empty")

  def manifest: Manifest[ValueType]
  override def required_? = true

  @SuppressWarnings(Array("deprecation")) //ok we do know that
  def get = is

  /* parseClient / toClient : get and set value from/to
   * web ui. 
   * 
   * this.is should be invariant with
   * parseClient(toClient).
   * 
   */

  //Set value from a client value.
  //update list error accordingly
  def parseClient(s: String): Unit

  private var description: String = ""
  override def displayName = description
  def displayName_=(s: String): Unit = description = s

  //long description
  private var longDescription: String = ""
  def tooltip = longDescription
  def tooltip_=(s: String): Unit = longDescription = s

  private var mayBeEmpty: Boolean = false
  def optional = mayBeEmpty
  def optional_=(b: Boolean): Unit = mayBeEmpty = b

  //long description
  private var zone: Option[String] = None
  def section = zone
  def section_=(s: Option[String]): Unit = zone = s

  /**
   * Get possible values for that fields, if supported.
   * If not supported, return none.
   * filter may be given
   */
  def getPossibleValues(filters: (ValueType => Boolean)*): Option[Set[ValueType]]

  def getDefaultValue: ValueType

  override def displayHtml = Text(toClient)

  override def toFormNodeSeq = {
    toForm match {
      case Failure(m, _, _) =>
        logger.error("Can not map field %s to an input, error message: %s".format(displayName, m))
        NodeSeq.Empty
      case Empty =>
        logger.error("Can not map field %s to an input, form representation of the field was empty".format(displayName))
        NodeSeq.Empty
      case Full(form) if tooltip == "" =>
        <tr><td class="policyInstanceVarLabel">{ displayName + { if (optional) " (optional)" else "" } }:</td><td class="policyInstanceVarValue">{ form }</td></tr>
      case Full(form) =>
        <tr><td class="policyInstanceVarLabel"><span class="tooltip" title={ tooltip }>{ displayName + { if (optional) " (optional)" else "" } }:</span></td><td class="policyInstanceVarValue">{ form }</td></tr>
    }
  }

  def toHtmlNodeSeq = {
    if (tooltip == "") {
      <tr>
        <td class="policyInstanceVarLabel">{ displayName + { if (optional) " (optional)" else "" } }</td>
        <td class="policyInstanceVarValue">{ displayValue }</td>
      </tr>
    } else {
      <tr>
        <td class="policyInstanceVarLabel"><span class="tooltip" title={ tooltip }>{ displayName + { if (optional) " (optional)" else "" } }</span></td>
        <td class="policyInstanceVarValue">{ displayValue }</td>
      </tr>
    }
  }

  // This is only used when showing a PT, hence the values are the default values
  def displayValue: NodeSeq = {
    displayHtml match {
      case value: NodeSeq if value.toString.trim == "" => <span></span>
      case value: NodeSeq => Text("(defaults to ") ++ value ++ Text(")")
    }
  }
}

import org.slf4j.LoggerFactory
object PolicyField {
  val logger = LoggerFactory.getLogger(classOf[PolicyField])
}

trait SectionField extends SectionChildField {
  def name: String
  def childFields: Seq[SectionChildField]
  def values: Map[String, () => String]
  def mapValueSeq: Map[String, Seq[String]]

  def displayHtml = Text(toClient)

  def isMultivalued = this match {
    case _: MultivaluedSectionField => true
    case _ => false
  }
}

case class SectionFieldImp(
  val name: String,
  val childFields: Seq[SectionChildField],
  // Only variables of the current section have entries in the values map
  // the key of type String is the id (variable name), 
  // the value is a function which should be called at validation time
  val values: Map[String, () => String]) extends SectionField {

  def copy(): Nothing = throw new TechnicalException("Can't copy PolicyFieldGroup, it contains mutable datas")
  def toClient = childFields.mkString

  def mapValueSeq: Map[String, Seq[String]] = values.map { case (k, v) => (k, Seq(v())) }

  // If a section is empty, we want to hide it. 
  
  override def toFormNodeSeq: NodeSeq = {
    val childrenXml = childFields map (f => f.toFormNodeSeq)
    if(childrenXml.isEmpty) NodeSeq.Empty
    else 
      <tr><td colspan="2">
        <fieldset class="sectionFieldset">
        <legend>Section: { name }</legend>
          <table class="policyInstanceSectionDef">
            <tbody>
              { childrenXml }
            </tbody>
          </table>
        </fieldset>
      </td></tr>
  }

  override def toHtmlNodeSeq = {
    val childrenXml = childFields map (f => f.toHtmlNodeSeq)
    if(childrenXml.isEmpty) NodeSeq.Empty
    else 
      <tr><td colspan="2">
        <fieldset>
        <legend>Section: { name }</legend>
          <table class="policyInstanceSectionDisplay">
            <tbody>
              { childrenXml }
            </tbody>
          </table>
        </fieldset>
      </td></tr>
  }
}

case class MultivaluedSectionField(
  val sections: Seq[SectionField],
  private val newSection: () => SectionField) extends SectionField {
  require(!sections.isEmpty)
  
  val name: String = sections.head.name
  
  def childFields: Seq[SectionChildField] = allSections.foldLeft(Seq[SectionChildField]())((seq, section) => seq ++ section.childFields)
  def values: Map[String, () => String] = allSections.foldLeft(Map[String, () => String]())((map, child) => map ++ child.values)
  
  private val htmlId = Helpers.nextFuncName

  private def logError(box: Box[_]): Unit = box match {
    case Failure(m, _, _) => logger.error(m)
    case Empty => logger.error("Empty value was returned")
    case _ => //ok
  }

  private val allSections = sections.toBuffer

  def toClient: String = childFields.mkString

  def add(section: SectionField = newSection()): Int = {
    synchronized {
      allSections += section
      allSections.size - 1
    }
  }

  /**
   * Remove list group with index "index"
   * @param index
   * @return the new size of the otherSections, or an error
   */
  def delete(index: Int): Box[Int] = {
    synchronized {
      if (index < 0) {
        Failure("Index must be a positive integer")
      } else if (index >= allSections.size) {
        Failure("Index must be lesser than number of sections (%s)".format(allSections.size))
      } else {
        allSections remove index
        Full(allSections.size)
      }
    }
  }

  def size = synchronized { allSections.size }
  def iterator = synchronized { allSections.iterator }

  /**
   * Return the Map of (variable name -> seq of values)
   * with values ordered by listname index:
   * for each variable name "key", values(key)(i) belongs
   * to the same iteration of listname.
   */
  def mapValueSeq: Map[String, Seq[String]] = {
    import scala.collection.mutable.{ Buffer, Map }
    val map = Map[String, Buffer[String]]()
    for {
      sect <- allSections
      (name, values) <- sect.mapValueSeq
    } {
      if (!map.isDefinedAt(name))
        map(name) = Buffer[String]()
      map(name) ++= values
    }
    map.toMap
  }
  /**
   * Simple form presentation: each section is iterated, and a
   * delete button is added to them.
   * A add button is added at the bottom.
   * @return
   */
  def toFormNodeSeq: NodeSeq = {
    <tr id={ htmlId }>{ content }</tr>
  }

  private def content: NodeSeq = {
    <td colspan="2">
      <div class="policyInstanceGroup">{
        (allSections.zipWithIndex.map {
          case (section, i) =>
            <fieldset class="groupFieldset">
              <legend>{ "%s #%s".format(name, i + 1) }</legend>
              { showFormEntry(section, i) }
              { // showAddAnother under the last element
                if ((i + 1) == size) {
                  showAddAnother()
                } else {
                  NodeSeq.Empty
                }
              }
            </fieldset>
        })
      }</div>
    </td>
  }

  private def showAddAnother(): NodeSeq = {
    <div class="policyInstanceAddGroup">{
      SHtml.ajaxSubmit("Add another", { () =>
        add()
        //refresh UI - all item of that group
        SetHtml(htmlId, this.content) & JsRaw("""correctButtons(); """)
      })
    }</div>
  }

  private def showFormEntry(section: SectionField, i: Int): NodeSeq = {
    <table class="policyInstanceGroupDef">
      <tbody>
        { section.childFields map (f => f.toFormNodeSeq) }
      </tbody>
    </table>
    <div style="text-align:right" class="policyInstanceDeleteGroup">{
      val attr = if (size > 1) ("" -> "") else ("disabled" -> "true")
      SHtml.ajaxSubmit("Delete", { () =>
        logError(delete(i))
        //refresh UI - all item of that group
        SetHtml(htmlId, this.content) & JsRaw(""" correctButtons(); """)
      },
        attr)
    }</div>
  }

  override def toHtmlNodeSeq = {
    <tr><td colspan="2">
          <div class="policyInstanceGroup">{
            (allSections.map { sect =>
              <fieldset class="groupFieldset">
                <legend>{ "%s".format(name) }</legend>
                <table class="policyInstanceGroupDisplay">
                  <tbody>
                    { sect.toHtmlNodeSeq }
                  </tbody>
                </table>
              </fieldset>
            })
          }</div>
        </td></tr>
  }
}

/**
 * A stateful class that maintains information about
 * a policy and every things needed in the web part to
 * configure it (fields, etc).
 *
 * @parameter Policy
 *   policy: the policy for witch this editor is build
 */
case class PolicyEditor(
  //       policyTemplateId / policyInstanceId here.
  val policyTemplateId: PolicyPackageId,
  val policyInstanceId: PolicyInstanceId,
  val name: String,
  val description: String,
  val sectionField: SectionField,
  val variableSpecs: Map[String, VariableSpec]) {

  /**
   * Get the map of (varname, list(values)),
   * as awaited by LDAPConfigurationRuleID
   */
  def mapValueSeq: Map[String, Seq[String]] =
    sectionField.getAllSectionFields.foldLeft(Map[String, Seq[String]]()) { (map, sect) =>
      sect.mapValueSeq ++ map
    }

  def toFormNodeSeq: NodeSeq = {
    <div class="variableDefinition">
      <table class="policyInstanceVarDef">
        { sectionField.childFields.flatMap(_.toFormNodeSeq) }
      </table>
    </div>
  }

  def toHtmlNodeSeq: NodeSeq = {
    <div class="policyDisplay">
      <div class="variableDefinition">
        <br/>
        <div>Variables to be defined for this policy template</div>
        <table class="policyInstanceVarDisplay">
          { sectionField.childFields.flatMap(_.toHtmlNodeSeq) }
        </table>
      </div>
    </div>
  }
}


