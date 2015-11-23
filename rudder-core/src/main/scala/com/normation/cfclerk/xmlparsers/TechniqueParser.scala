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

import CfclerkXmlConstants._
import com.normation.cfclerk.domain._
import com.normation.cfclerk.services.SystemVariableSpecService
import com.normation.cfclerk.exceptions.ParsingException
import com.normation.utils.Utils._
import com.normation.utils.Control.{sequence,bestEffort}
import scala.xml._
import net.liftweb.common._

/**
 * Parse a technique (metadata.xml file)
 */

class TechniqueParser(
    variableSpecParser           : VariableSpecParser
  , sectionSpecParser            : SectionSpecParser
  , systemVariableSpecService    : SystemVariableSpecService
) extends Loggable {

  def parseXml(node: Node, id: TechniqueId, expectedReportCsvExists: Boolean): Technique = {
    //check that node is <TECHNIQUE> and has a name attribute
    if (node.label.toUpperCase == TECHNIQUE_ROOT) {
      node.attribute(TECHNIQUE_NAME) match {
        case Some(nameAttr) if (TechniqueParser.isValidId(id.name.value) && nonEmpty(nameAttr.text)) =>

          val name = nameAttr.text
          val compatible = try Some(parseCompatibleTag((node \ COMPAT_TAG).head)) catch { case _:Exception => None }

          val rootSection = sectionSpecParser.parseSectionsInPolicy(node, id, name)

          val description = ??!((node \ TECHNIQUE_DESCRIPTION).text).getOrElse(name)

          val templates = (node \ PROMISE_TEMPLATES_ROOT \\ PROMISE_TEMPLATE).map(xml => parseTemplate(id, xml) )
          val files = (node \ FILES \\ FILE).map(xml => parseFile(id, xml) )

          val bundlesequence = (node \ BUNDLES_ROOT \\ BUNDLE_NAME).map(xml => Bundle(xml.text) )

          val trackerVariableSpec = parseTrackerVariableSpec(node)

          val systemVariableSpecs = parseSysvarSpecs(node,id)

          val isMultiInstance = ((node \ TECHNIQUE_IS_MULTIINSTANCE).text.equalsIgnoreCase("true") )

          val longDescription = ??!((node \ TECHNIQUE_LONG_DESCRIPTION).text).getOrElse("")

          val isSystem = ((node \ TECHNIQUE_IS_SYSTEM).text.equalsIgnoreCase("true"))

          //the technique provides its expected reports if at least one section has a variable of type REPORT_KEYS
          val providesExpectedReports = expectedReportCsvExists

          val deprecationInfo = parseDeprecrationInfo(node)

          val technique = Technique(
              id
            , name
            , description
            , templates
            , files
            , bundlesequence
            , trackerVariableSpec
            , rootSection
            , deprecationInfo
            , systemVariableSpecs
            , compatible
            , isMultiInstance
            , longDescription
            , isSystem
            , providesExpectedReports
          )

          /*
           * Check that if the policy info variable spec has a bounding variable, that
           * variable actually exists
           */
          technique.trackerVariableSpec.boundingVariable.foreach { bound =>
            if(
                technique.rootSection.getAllVariables.exists { v => v.name == bound } ||
                systemVariableSpecService.getAll.exists { v => v.name == bound }
            ) {
              //ok
            } else {
              throw new ParsingException("The bouding variable '%s' for policy info variable does not exists".format(bound))
            }
          }

          technique

        case _ => throw new ParsingException("Not a policy node, missing 'name' attribute: %s".format(node))
      }
    } else {
      throw new ParsingException("Not a policy node, bad node name. Was expecting <%s>, got: %s".format(TECHNIQUE_ROOT,node))
    }
  }

  private[this] def checkUniqueness(seq:Seq[String])(errorMsg:String) : Unit = {
    if(seq.distinct.size != seq.size) {
      throw new ParsingException(errorMsg + seq.groupBy(identity).collect {
                  case(k, x) if x.size > 1 => k
      }.mkString("[", "," , "]") )
    }
  }

  private[this] def parseTrackerVariableSpec(node: Node): TrackerVariableSpec = {
    val trackerVariableSpecs = (node \ TRACKINGVAR)
    if(trackerVariableSpecs.size == 0) { //default trackerVariable variable spec for that package
      TrackerVariableSpec()
    } else if(trackerVariableSpecs.size == 1) {
      variableSpecParser.parseTrackerVariableSpec(trackerVariableSpecs.head) match {
        case Full(p) => p
        case e:EmptyBox =>
          throw new ParsingException( (e ?~! "Error when parsing <%s> tag".format(TRACKINGVAR)).messageChain )
      }
    } else throw new ParsingException("Only one <%s> tag is allowed the the document, but found %s".format(TRACKINGVAR,trackerVariableSpecs.size))
  }

  private[this] def parseDeprecrationInfo(node: Node): Option[TechniqueDeprecationInfo] = {
    for {
      deprecationInfo <- (node \ TECHNIQUE_DEPRECATION_INFO).headOption
    } yield {
      val message = deprecationInfo.text
      if (message.size == 0) {
          val errorMsg = s"Error when parsing <${TECHNIQUE_DEPRECATION_INFO}> tag, text is empty and is mandatory"
          throw new ParsingException( errorMsg )
      } else {
        TechniqueDeprecationInfo(message)
      }
    }
  }

  /*
   * Parse the list of system vars used by that policy package.
   *
   */
  private[this] def parseSysvarSpecs(node: Node, id:TechniqueId) : Set[SystemVariableSpec] = {
    (node \ SYSTEMVARS_ROOT \ SYSTEMVAR_NAME).map{ x =>
      try {
        systemVariableSpecService.get(x.text)
      } catch {
        case ex:NoSuchElementException =>
          throw new ParsingException(s"The system variable ${x.text} is not defined: perhaps the metadata.xml for technique '${id.toString}' is not up to date")
      }
    }.toSet
  }

  /**
   * Parse a resource file tag in metadata.xml.
   *
   * The tag looks like:
   * <TML name="someIdentification">
   *   <OUTPATH>some_out_path_name</OUTPATH> (optional, default to "techniqueId/templateName.cf")
   *   <INCLUDED>true</INCLUDED> (optional, default to true)
   * </TML>
   * or for file:
   * <FILE name="someIdentification">
   *   <OUTPATH>some_out_path_name</OUTPATH> (optional, default to "techniqueId/templateName.cf")
   *   <INCLUDED>false</INCLUDED> (optional, default to false)
   * </FILE>
   *
   * if name content start with RUDDER_CONFIGURATION_REPOSITORY, the path must be considered relative
   * to root of configuration repository in place of relative to the technique.
   *
   */
  private[this] def parseResource(techniqueId: TechniqueId, node: Node, isTemplate:Boolean): (TechniqueResourceId, String) = {

    def fileToList(f: java.io.File): List[String] = {
      if(f == null) {
        Nil
      } else {
        fileToList(f.getParentFile) ::: f.getName :: Nil
      }
    }

    //the default out path for a template with name "name" is "techniqueName/techniqueVersion/name.cf
    def defaultOutPath(name: String) = s"${techniqueId.name.value}/${techniqueId.version.toString}/${name}${if(isTemplate) TechniqueTemplate.promiseExtension else ""}"

    val outPath = (node \ PROMISE_TEMPLATE_OUTPATH).text match {
      case "" => None
      case path => Some(path)
    }

    val id = node.attribute(PROMISE_TEMPLATE_NAME) match {
      case Some(attr) if (attr.size == 1) =>
        // some checking on name
        val n = attr.text.trim
        if(n.startsWith("/") || n.endsWith("/")) {
          throw new ParsingException(s"Error when parsing xml ${node}. Resource name must not start nor end with '/'")
        } else {

          if(n.startsWith(RUDDER_CONFIGURATION_REPOSITORY+"/")) {

            val path = new java.io.File(n.substring(RUDDER_CONFIGURATION_REPOSITORY.length + 1))
            val name = path.getName
            //here, getName can't be empty since n does not end by "/"
            TechniqueResourceIdByPath(fileToList(path.getParentFile), name)
          } else {
            if(n.startsWith(RUDDER_CONFIGURATION_REPOSITORY)) { //most likely an user error, issue a warning
              logger.warn(s"Resource named '${n}' for technique '${techniqueId}' starts with ${RUDDER_CONFIGURATION_REPOSITORY} which is not followed by a '/'. " +
                  "If you meant to use a relative path from configuration-repository directory for the resource, it is an error.")
            }
            TechniqueResourceIdByName(techniqueId, n)
          }
        }

      case _ => throw new ParsingException(s"Error when parsing xml ${node}. Resource name is not defined")
    }
    (id, outPath.getOrElse(defaultOutPath(id.name)))
  }

  /**
   * A file is almost exactly like a Template, safe the include that we don't care of.
   */
  def parseFile(techniqueId: TechniqueId, node: Node): TechniqueFile = {
    if(node.label != FILE) throw new ParsingException(s"Error: try to parse a <${FILE}> node, but actually get: ${node}")
    // Default value for FILE is false, so we should only check if the value is true and if it is empty it
    val included = (node \ PROMISE_TEMPLATE_INCLUDED).text == "true"
    val (id, out) = parseResource(techniqueId, node, false)
    TechniqueFile(id, out, included)
  }

  def parseTemplate(techniqueId: TechniqueId, node: Node): TechniqueTemplate = {
    if(node.label != PROMISE_TEMPLATE) throw new ParsingException(s"Error: try to parse a <${PROMISE_TEMPLATE}> node, but actually get: ${node}")
    val included = !((node \ PROMISE_TEMPLATE_INCLUDED).text == "false")
    val (id, out) = parseResource(techniqueId, node, true)
    TechniqueTemplate(id, out, included)
  }

  /**
   * Parse a <compatible> marker
   * @param node example :
   * <COMPATIBLE>
   * <OS>Ubuntu</OS>
   * <OS>debian-5</OS>
   * <AGENT version=">= 3.5">cfengine-community</AGENT>
   * </COMPATIBLE>
   * @return A compatible variable corresponding to the entry node
   */
  def parseCompatibleTag(node: Node): Compatible = {
    if(node.label != COMPAT_TAG) throw new ParsingException("CompatibleParser was expecting a <%s> node and get:\n%s".format(COMPAT_TAG, node))
    val os = node \ COMPAT_OS map (n =>
      OperatingSystem(n.text, (n \ "@version").text))
    val agents = node \ COMPAT_AGENT map (n =>
      Agent(n.text, (n \ "@version").text))
    Compatible(os, agents)
  }
}

object TechniqueParser {

  val authorizedCharInId = """([a-zA-Z0-9\-_]+)""".r

  def isValidId(s: String): Boolean = s match {
    case authorizedCharInId(_) => true
    case _ => false
  }

}
