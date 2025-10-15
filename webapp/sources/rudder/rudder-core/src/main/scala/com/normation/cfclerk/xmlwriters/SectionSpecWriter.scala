package com.normation.cfclerk.xmlwriters

import com.normation.cfclerk.domain.*
import com.normation.cfclerk.xmlparsers.CfclerkXmlConstants.*
import net.liftweb.common.*
import scala.xml.*

trait SectionSpecWriter {

  // we serialize an option, because Technique may not be present anymore
  // rather than changing all calls to this method with boilerplate,
  // it's better to have the handling here
  // If we have a None, we return an Full(NodeSeq.empty))
  def serialize(rootSection: Option[SectionSpec]): Box[NodeSeq]

}

class SectionSpecWriterImpl extends SectionSpecWriter {

  private def createXmlTextNode(label: String, content: String): Elem =
    <tag>{Text(content)}</tag>.copy(label = label)

  private def createXmlNode(label: String, children: Seq[Node]): Elem =
    <tag>{children}</tag>.copy(label = label)

  def serialize(rootSection: Option[SectionSpec]): Box[NodeSeq] = {
    rootSection match {
      case None          => Full(NodeSeq.Empty)
      case Some(section) =>
        if (section.name != SECTION_ROOT_NAME) {
          Failure(s"root section name should be equals to ${SECTION_ROOT_NAME} but is ${section.name}")
        } else {
          val children = section.children.flatMap(serializeChild(_)).foldLeft(NodeSeq.Empty)((a, b) => a ++ b)
          val xml      = createXmlNode(SECTIONS_ROOT, children)
          Full(xml)
        }
    }

  }

  private def serializeChild(section: SectionChildSpec): NodeSeq = {
    section match {
      case s: SectionSpec         => serializeSection(s)
      case v: SectionVariableSpec => serializeVariable(v)
    }
  }

  private def serializeSection(section: SectionSpec): NodeSeq = {
    val children = section.children.flatMap(serializeChild(_)).foldLeft(NodeSeq.Empty)((a, b) => a ++ b)
    val xml      = (createXmlNode(SECTION, children)
      % Attribute(SECTION_NAME, Text(section.name), Null)
      % Attribute(SECTION_IS_MULTIVALUED, Text(section.isMultivalued.toString), Null)
      % Attribute(SECTION_IS_COMPONENT, Text(section.isComponent.toString), Null))

    // add ComponentKey attribute
    section.componentKey.map(key => xml % Attribute(SECTION_COMPONENT_KEY, Text(key), Null)).getOrElse(xml)

  }
  private def serializeVariable(variable: SectionVariableSpec): NodeSeq = {
    // special case for derived password that are invisible
    variable.constraint.typeName match {
      case _: DerivedPasswordVType => NodeSeq.Empty
      case _ =>
        val (label, valueLabels) = variable match {
          case input:      InputVariableSpec            => (INPUT, Seq())
          // Need to pattern match ValueLabel or compiler complains about missing patterns
          case predefined: PredefinedValuesVariableSpec => (REPORT_KEYS, Seq())
          // Need to pattern match PredefinedValuesVariableSpec or compiler complains about missing patterns
          case valueLabel: ValueLabelVariableSpec       =>
            val label = valueLabel match {
              case select:    SelectVariableSpec    => SELECT
              case selectOne: SelectOneVariableSpec => SELECT1
            }

            (label, valueLabel.valueslabels)
        }

        // if we have a predefined values variables, we need to serialize it
        val predefinedValues = variable match {
          case predefined: PredefinedValuesVariableSpec =>
            predefined.nelOfProvidedValues.map(x => <VALUE>{x}</VALUE>)
          case _ => NodeSeq.Empty
        }
        val name             = createXmlTextNode(VAR_NAME, variable.name)
        val description      = createXmlTextNode(VAR_DESCRIPTION, variable.description)
        val longDescription  = createXmlTextNode(VAR_LONG_DESCRIPTION, variable.longDescription)
        val isMultiValued    = createXmlTextNode(VAR_IS_MULTIVALUED, variable.multivalued.toString)
        val checked          = createXmlTextNode(VAR_IS_CHECKED, variable.checked.toString)
        val items            = valueLabels.map(serializeItem(_)).foldLeft(NodeSeq.Empty)((a, b) => a ++ b)
        val constraint       = serializeConstraint(variable.constraint)

        val children = (name
          ++ description
          ++ longDescription
          ++ isMultiValued
          ++ checked
          ++ items
          ++ constraint
          ++ predefinedValues).flatten
        createXmlNode(label, children)
    }
  }

  private def serializeItem(item: ValueLabel): NodeSeq = {
    val value = createXmlTextNode(CONSTRAINT_ITEM_VALUE, item.value)
    val label = createXmlTextNode(CONSTRAINT_ITEM_LABEL, item.label)
    val child = Seq(value, label)

    createXmlNode(CONSTRAINT_ITEM, child)
  }

  private def serializeConstraint(constraint: Constraint): NodeSeq = {
    val constraintType = createXmlTextNode(CONSTRAINT_TYPE, constraint.typeName.name)
    val empty          = createXmlTextNode(CONSTRAINT_MAYBEEMPTY, constraint.mayBeEmpty.toString)
    val regexp         = constraint.typeName match {
      // Do not add regexp if this is a fixed regexp type
      // The parser will not accept a 'fixed regexp' with a regexp field
      case fixedRegexpType: FixedRegexVType =>
        NodeSeq.Empty

      case otherType =>
        VTypeConstraint
          .getRegexConstraint(otherType)
          .map(regex => createXmlTextNode(CONSTRAINT_REGEX, regex.pattern)) getOrElse (NodeSeq.Empty)
    }
    val dflt           = constraint.default.map(createXmlTextNode(CONSTRAINT_DEFAULT, _)).getOrElse(NodeSeq.Empty)
    val hashAlgos      = createXmlTextNode(
      CONSTRAINT_PASSWORD_HASH,
      VTypeConstraint.getPasswordHash(constraint.typeName).map(_.prefix).mkString(",")
    )
    val derived        = {
      val types = constraint.usedFields.flatMap { x =>
        val y = x.toUpperCase
        if (y.endsWith("_AIX")) {
          Some("AIX")
        } else if (y.endsWith("_LINUX")) {
          Some("LINUX")
        } else {
          None
        }
      }
      if (types.isEmpty) {
        NodeSeq.Empty
      } else {
        createXmlTextNode(
          CONSTRAINT_PWD_AUTOSUBVARIABLES,
          types.mkString(",")
        )
      }
    }

    val children = Seq(constraintType, dflt, empty, regexp, hashAlgos, derived).flatten

    createXmlNode(VAR_CONSTRAINT, children)
  }

}
