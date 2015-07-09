package com.normation.cfclerk.xmlwriters

import net.liftweb.common._
import com.normation.cfclerk.domain._
import com.normation.cfclerk.xmlparsers.CfclerkXmlConstants._
import scala.xml._


trait SectionSpecWriter {

  def serialize(rootSection:SectionSpec):Box[NodeSeq]

}

class SectionSpecWriterImpl extends SectionSpecWriter {


  private[this] def createXmlTextNode(label : String, content : String) : Elem =
    <tag>{Text(content)}</tag>.copy(label = label)

  private[this] def createXmlNode(label : String, children : Seq[Node]) : Elem =
    <tag>{children}</tag>.copy(label = label)

  def serialize(rootSection:SectionSpec):Box[NodeSeq] = {
    if (rootSection.name != SECTION_ROOT_NAME)
      Failure(s"root section name should be equals to ${SECTION_ROOT_NAME} but is ${rootSection.name}")
    else {
      val children = (rootSection.children.flatMap( serializeChild(_))/:NodeSeq.Empty)((a,b) => a ++ b)
      val xml = createXmlNode(SECTIONS_ROOT,children)
      Full(xml)
    }
  }

  private[this] def serializeChild(section:SectionChildSpec):NodeSeq = {
    section match {
      case s:SectionSpec         => serializeSection(s)
      case v:SectionVariableSpec => serializeVariable(v)
    }
  }

  private[this] def serializeSection(section:SectionSpec):NodeSeq = {
    val children = (section.children.flatMap( serializeChild(_))/:NodeSeq.Empty)((a,b) => a ++ b)
    val xml = (   createXmlNode(SECTION,children)
                % Attribute(SECTION_NAME,           Text(section.name),Null)
                % Attribute(SECTION_IS_MULTIVALUED, Text(section.isMultivalued.toString),Null)
                % Attribute(SECTION_IS_COMPONENT,   Text(section.isComponent.toString),Null)
              )

    // add ComponentKey attribute
    section.componentKey.map{key =>
      xml % Attribute(SECTION_COMPONENT_KEY,Text(key),Null)
      }.getOrElse(xml)

  }
  private[this] def serializeVariable(variable:SectionVariableSpec):NodeSeq = {

    val (label,valueLabels) = variable match {
      case input:InputVariableSpec => (INPUT,Seq())
        // Need to pattern match ValueLabel or compiler complains about missing patterns
      case predefined: PredefinedValuesVariableSpec => (REPORT_KEYS, Seq())
        // Need to pattern match PredefinedValuesVariableSpec or compiler complains about missing patterns
      case valueLabel:ValueLabelVariableSpec =>
        val label = valueLabel match {
          case select:SelectVariableSpec => SELECT
          case selectOne:SelectOneVariableSpec => SELECT1
        }

        (label, valueLabel.valueslabels)
    }

    // if we have a predefined values variables, we need to serialize it
    val predefinedValues = variable match {
      case predefined: PredefinedValuesVariableSpec =>
        predefined.nelOfProvidedValues.map(x => <VALUE>{x}</VALUE>)
      case _ => NodeSeq.Empty
    }
    val name            = createXmlTextNode(VAR_NAME,               variable.name)
    val description     = createXmlTextNode(VAR_DESCRIPTION,        variable.description)
    val longDescription = createXmlTextNode(VAR_LONG_DESCRIPTION,   variable.longDescription)
    val isUnique        = createXmlTextNode(VAR_IS_UNIQUE_VARIABLE, variable.isUniqueVariable.toString)
    val isMultiValued   = createXmlTextNode(VAR_IS_MULTIVALUED,     variable.multivalued.toString)
    val checked         = createXmlTextNode(VAR_IS_CHECKED,         variable.checked.toString)
    val items           = (valueLabels.map(serializeItem(_))/:NodeSeq.Empty)((a,b) => a ++ b)
    val constraint      = serializeConstraint(variable.constraint)

    val children = (  name
                   ++ description
                   ++ longDescription
                   ++ isUnique
                   ++ isMultiValued
                   ++ checked
                   ++ items
                   ++ constraint
                   ++ predefinedValues
                   ).flatten
    createXmlNode(label,children)
  }

  private[this] def serializeItem(item:ValueLabel):NodeSeq = {
    val value = createXmlTextNode(CONSTRAINT_ITEM_VALUE,item.value)
    val label = createXmlTextNode(CONSTRAINT_ITEM_LABEL,item.label)
    val child = Seq(value,label)

    createXmlNode(CONSTRAINT_ITEM,child)
  }

  private[this] def serializeConstraint(constraint:Constraint): NodeSeq = {
    val constraintType = createXmlTextNode(CONSTRAINT_TYPE,       constraint.typeName.name)
    val empty          = createXmlTextNode(CONSTRAINT_MAYBEEMPTY, constraint.mayBeEmpty.toString)
    val regexp         = constraint.typeName match {
                           // Do not add regexp if this is a fixed regexp type
                           // The parser will not accept a 'fixed regexp' with a regexp field
                           case fixedRegexpType : FixedRegexVType =>
                             NodeSeq.Empty

                           case otherType =>
                             VTypeConstraint.getRegexConstraint(otherType).map(regex =>
                               createXmlTextNode(CONSTRAINT_REGEX,regex.pattern)
                             ) getOrElse(NodeSeq.Empty)
                         }
    val dflt           = constraint.default.map(createXmlTextNode(CONSTRAINT_DEFAULT,_)).getOrElse(NodeSeq.Empty)
    val hashAlgos      = createXmlTextNode(
                             CONSTRAINT_PASSWORD_HASH
                           , VTypeConstraint.getPasswordHash(constraint.typeName).map(_.prefix).mkString(",")
                         )

    val children       = Seq(constraintType,dflt,empty,regexp,hashAlgos).flatten

    createXmlNode(VAR_CONSTRAINT,children)
  }

}