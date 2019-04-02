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

import CfclerkXmlConstants._
import com.normation.NamedZioLogger
import com.normation.cfclerk.domain._
import com.normation.cfclerk.services.SystemVariableSpecService

import scala.xml._
import com.normation.inventory.domain.AgentType
import com.normation.cfclerk.domain.implicits._
import cats._
import cats.data._
import cats.implicits._


/**
 * Parse a technique (metadata.xml file)
 */

class TechniqueParser(
    variableSpecParser           : VariableSpecParser
  , sectionSpecParser            : SectionSpecParser
  , systemVariableSpecService    : SystemVariableSpecService
) extends NamedZioLogger {

  val loggerName = "technique-parser"

  def parseXml(xml: Node, id: TechniqueId): Either[LoadTechniqueError, Technique] = {
    def nonEmpty(s: String) = if(null == s || s == "") None else Some(s)

    //check that xml is <TECHNIQUE> and has a name attribute
    if (xml.label.toUpperCase == TECHNIQUE_ROOT) {
      xml.attribute(TECHNIQUE_NAME) match {
        case Some(nameAttr) if (TechniqueParser.isValidId(id.name.value) && nameAttr.text != null && nameAttr.text != "") =>

          val name = nameAttr.text
          for {
            compatible <- (xml \ COMPAT_TAG).headOption match {
                            case None       => Right(None)
                            case Some(elem) => parseCompatibleTag(elem).map(x => Some(x))
                          }
            rootSection <- sectionSpecParser.parseSectionsInPolicy(xml, id, name)

            description = nonEmpty((xml \ TECHNIQUE_DESCRIPTION).text).getOrElse(name)

            trackerVariableSpec <- parseTrackerVariableSpec(xml)

            systemVariableSpecs <- parseSysvarSpecs(xml,id)

            isMultiInstance = ((xml \ TECHNIQUE_IS_MULTIINSTANCE).text.equalsIgnoreCase("true") )

            longDescription = nonEmpty((xml \ TECHNIQUE_LONG_DESCRIPTION).text).getOrElse("")

            isSystem = ((xml \ TECHNIQUE_IS_SYSTEM).text.equalsIgnoreCase("true"))

            // This parameter defines if the technique reporting is made by generic methods called by the technique
            // or if the technique should compute the reporting by itself
            // By default Techniques will not use reporting based on methods, since almost all core techniques have custom reporting
            // ncf/technique editor Techniques will define that parameter to true, since they rely on method reporting
            useMethodReporting = nonEmpty((xml \ TECHNIQUE_USE_METHOD_REPORTING).text).map(_.equalsIgnoreCase("true")).getOrElse(false)


            deprecationInfo <- parseDeprecrationInfo(xml)

            //for compability reason, we cheat and make the template/file/bundlesequence under root
            //and not in an <AGENT> sub-element be considered as being in <AGENT type="cfengine-community,cfengine-enterprise">
            compatibilityAgent <- {
                                    val forCompatibilityAgent = <AGENT type={s"${AgentType.CfeCommunity.toString},${AgentType.CfeEnterprise.toString}"}>
                                      {(xml \ PROMISE_TEMPLATES_ROOT)}
                                      {(xml \ FILES)}
                                      {(xml \ BUNDLES_ROOT)}
                                      {(xml \ RUN_HOOKS)}
                                    </AGENT>

                                    parseAgentConfig(id, forCompatibilityAgent)
                                  }
            otherAgents  <- (xml \ "AGENT" ).toList.traverse(agent => parseAgentConfig(id, agent)).map(_.flatten)
            agentConfigs = compatibilityAgent ++ otherAgents

            // System technique should not have run hooks, this is not supported:
            _ = if(isSystem && agentConfigs.exists( a => a.runHooks.nonEmpty ) ) {
                  logEffect.warn(s"System Technique with ID '${id.toString()}' has agent run hooks defined. This is not supported on system technique.")
                }

            // 4.3: does the technique support generation without directive merge (i.e mutli directive)
            generationMode = nonEmpty((xml \ TECHNIQUE_GENERATION_MODE).text).flatMap(TechniqueGenerationMode.parse).getOrElse(TechniqueGenerationMode.MergeDirectives)

            technique = Technique(
                id
              , name
              , description
              , agentConfigs
              , trackerVariableSpec
              , rootSection
              , deprecationInfo
              , systemVariableSpecs
              , compatible
              , isMultiInstance
              , longDescription
              , isSystem
              , generationMode
              , useMethodReporting
            )

            /*
             * Check that if the policy info variable spec has a bounding variable, that
             * variable actually exists
             */
            _ <- technique.trackerVariableSpec.boundingVariable.toList.traverse { bound =>
                   if(
                       technique.rootSection.getAllVariables.exists { v => v.name == bound } ||
                       systemVariableSpecService.getAll.exists { v => v.name == bound }
                   ) {
                     Right("ok")
                   } else {
                     Left(LoadTechniqueError.Parsing("The bounding variable '%s' for policy info variable does not exist".format(bound)))
                   }
                 }
          } yield {
            technique
          }

        case _ => Left(LoadTechniqueError.Parsing("Not a policy xml, missing 'name' attribute: %s".format(xml)))
      }
    } else {
      Left(LoadTechniqueError.Parsing("Not a policy xml, bad xml name. Was expecting <%s>, got: %s".format(TECHNIQUE_ROOT,xml)))
    }
  }

  /*
   * Here, we are parsing <AGENT> xml, that contains the list of templates/files/bundles
   * defined for the given agent.
   *
   * id is for reporting
   */
  private[this] def parseAgentConfig(id: TechniqueId, xml: Node): Either[LoadTechniqueError, List[AgentConfig]] = {
    //start to parse agent types for that config. It's a comma separated list
    import scala.language.postfixOps

    if(xml.label != "AGENT") {
      Right(Nil)
    } else {

      // we want to ignore without failing unknow agent.
      val agentTypes = (xml \ "@type" text).split(",").map { name =>
        AgentType.fromValue(name) match {
          case Right(agentType) => Some(agentType)
          case Left(err)        =>
            val msg = s"Error when parsing technique with id '${id.toString}', agent type='${name}' is not known and the corresponding config will be ignored: ${err.fullMsg}"
            logEffect.warn(msg)
            None
        }
      }.flatten.toList


      for {
        // create a map of template per agent type
        templatesPerAgent <- agentTypes.traverse { agentType =>
                               for {
                                 templates <- (xml \ PROMISE_TEMPLATES_ROOT \\ PROMISE_TEMPLATE).toList.traverse { xml2 => parseTemplate(id, xml2, agentType) }
                               } yield {
                                 (agentType, templates)
                               }
                             }
        files          <- (xml \ FILES \\ FILE).toList.traverse(xml => parseFile(id, xml) )
        bundlesequence =  (xml \ BUNDLES_ROOT \\ BUNDLE_NAME).map(xml2 => BundleName(xml2.text) )
        hooks          <- (xml \ RUN_HOOKS).toList.traverse(parseRunHooks(id, _)).map( _.flatten )
      } yield {
        val templatesMap = templatesPerAgent.toMap
        agentTypes.map( agentType => AgentConfig(agentType, templatesMap.getOrElse(agentType, Nil), files.toList, bundlesequence.toList, hooks.toList))
      }
    }
  }

  private[this] def parseTrackerVariableSpec(xml: Node): Either[LoadTechniqueError, TrackerVariableSpec] = {
    val trackerVariableSpecs = (xml \ TRACKINGVAR)
    if(trackerVariableSpecs.size == 0) { //default trackerVariable variable spec for that package
      Right(TrackerVariableSpec())
    } else if(trackerVariableSpecs.size == 1) {
        variableSpecParser.parseTrackerVariableSpec(trackerVariableSpecs.head).chain(s"Error when parsing <${TRACKINGVAR}> tag")
    } else Left(LoadTechniqueError.Parsing(s"Only one <${TRACKINGVAR}> tag is allowed the the document, but found '${trackerVariableSpecs.size}'"))
  }

  private[this] def parseDeprecrationInfo(xml: Node): Either[LoadTechniqueError, Option[TechniqueDeprecationInfo]] = {
    (xml \ TECHNIQUE_DEPRECATION_INFO).headOption match {
      case Some(deprecationInfo) if(deprecationInfo.text.size == 0) =>
        Left(LoadTechniqueError.Parsing(s"Error when parsing <${TECHNIQUE_DEPRECATION_INFO}> tag, text is empty and is mandatory"))
      case x =>
        Right(x.map(n => TechniqueDeprecationInfo(n.text)))
    }
  }

  /*
   * Parse the list of system vars used by that policy package.
   *
   */
  private[this] def parseSysvarSpecs(xml: Node, id:TechniqueId) : Either[LoadTechniqueError, Set[SystemVariableSpec]] = {
    (xml \ SYSTEMVARS_ROOT \ SYSTEMVAR_NAME).toList.traverse { x =>
      systemVariableSpecService.get(x.text).toValidNel(LoadTechniqueError.Parsing(s"The system variable ${x.text} is not defined: perhaps the metadata.xml for technique '${id.toString}' is not up to date"))
    }.fold(errs => Left(LoadTechniqueError.Accumulated(errs)), x => Right(x.toSet))
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
   * TODO: pass the AgentType here
   */
  private[this] def parseResource(techniqueId: TechniqueId, xml: Node, isTemplate:Boolean, agentType:Option[AgentType]): Either[LoadTechniqueError, (TechniqueResourceId, String)] = {

    def fileToList(f: java.io.File): List[String] = {
      if(f == null) {
        Nil
      } else {
        fileToList(f.getParentFile) ::: f.getName :: Nil
      }
    }

    //the default out path for a template with name "name" is "techniqueName/techniqueVersion/name".defaultAgentExtension
    //note: by convention, the template name for DSC agent already contains the .ps1
    def defaultOutPath(name: String) = s"${techniqueId.name.value}/${techniqueId.version.toString}/${name}${if(isTemplate) agentType.map(_.defaultPolicyExtension).getOrElse("") else ""}"

    val outPath = (xml \ PROMISE_TEMPLATE_OUTPATH).text match {
      case "" => None
      case path => Some(path)
    }

    for {
      id <- xml.attribute(PROMISE_TEMPLATE_NAME) match {
        case Some(attr) if (attr.size == 1) =>
          // some checking on name
          val n = attr.text.trim
          if(n.startsWith("/") || n.endsWith("/")) {
            Left(LoadTechniqueError.Parsing(s"Error when parsing xml ${xml}. Resource name must not start nor end with '/'"))
          } else {
            if(n.startsWith(RUDDER_CONFIGURATION_REPOSITORY+"/")) {

              val path = new java.io.File(n.substring(RUDDER_CONFIGURATION_REPOSITORY.length + 1))
              val name = path.getName
              //here, getName can't be empty since n does not end by "/"
              Right(TechniqueResourceIdByPath(fileToList(path.getParentFile), name))
            } else {
              if(n.startsWith(RUDDER_CONFIGURATION_REPOSITORY)) { //most likely an user error, issue a warning
                logEffect.warn(s"Resource named '${n}' for technique '${techniqueId}' starts with ${RUDDER_CONFIGURATION_REPOSITORY} which is not followed by a '/'. " +
                    "If you meant to use a relative path from configuration-repository directory for the resource, it is an error.")
              }
              Right(TechniqueResourceIdByName(techniqueId, n))
            }
          }

        case _ => Left(LoadTechniqueError.Parsing(s"Error when parsing xml ${xml}. Resource name is not defined"))
      }
    } yield {
      (id, outPath.getOrElse(defaultOutPath(id.name)))
    }
  }

  /**
   * A file is almost exactly like a Template, safe the include that we don't care of.
   */
  def parseFile(techniqueId: TechniqueId, xml: Node): Either[LoadTechniqueError, TechniqueFile] = {
    if(xml.label != FILE) Left(LoadTechniqueError.Parsing(s"Error: try to parse a <${FILE}> xml, but actually got: ${xml}"))
    else {
      // Default value for FILE is false, so we should only check if the value is true and if it is empty it
      val included = (xml \ PROMISE_TEMPLATE_INCLUDED).text == "true"
      for {
        parsed <- parseResource(techniqueId, xml, false, None)
      } yield {
        TechniqueFile(parsed._1, parsed._2, included)
      }
    }
  }

  def parseTemplate(techniqueId: TechniqueId, xml: Node, agentType: AgentType): Either[LoadTechniqueError, TechniqueTemplate] = {
    if(xml.label != PROMISE_TEMPLATE) Left(LoadTechniqueError.Parsing(s"Error: try to parse a <${PROMISE_TEMPLATE}> xml, but actually got: ${xml}"))
    else {
      val included = !((xml \ PROMISE_TEMPLATE_INCLUDED).text == "false")
      for {
        parsed <- parseResource(techniqueId, xml, true, Some(agentType))
      } yield {
        TechniqueTemplate(parsed._1, parsed._2, included)
      }
    }
  }
  /**
   * Parse a <compatible> marker
   * @param xml example :
   * <COMPATIBLE>
   * <OS>Ubuntu</OS>
   * <OS>debian-5</OS>
   * <AGENT version=">= 3.5">cfengine-community</AGENT>
   * </COMPATIBLE>
   * @return A compatible variable corresponding to the entry xml
   */
  def parseCompatibleTag(xml: Node): Either[LoadTechniqueError, Compatible] = {
    if(xml.label != COMPAT_TAG) Left(LoadTechniqueError.Parsing("CompatibleParser was expecting a <%s> xml and got:\n%s".format(COMPAT_TAG, xml)))
    else {
      val os = xml \ COMPAT_OS map (n =>
        OperatingSystem(n.text, (n \ "@version").text))
      val agents = xml \ COMPAT_AGENT map (n =>
        Agent(n.text, (n \ "@version").text))
      Right(Compatible(os, agents))
    }
  }

  /* parse RUNHOOKS xml node, which look like that:
    <RUNHOOKS>
      <PRE bundle="runhook_package" >
        <REPORT name="check_visudo_installed" value="ok"/> // value optionnal, if missing => "None"
        <PARAMETER name="package" value="visudo"/>
        <PARAMETER name="condition" value="debian"/>
        ... more parameters ...
      </PRE>
      <POST bundle="servive">
        <REPORT name="something"/>
        <PARAMETER name="service" value="some value"/>
        <PARAMETER name="a post command">/something/that/is/complicated "with" 'all sort of quote'</PARAMETER>
      </POST>
    </RUNHOOKS>
  */
  def parseRunHooks(id: TechniqueId, xml: Node): Either[LoadTechniqueError, List[RunHook]] = {
    def parseOneHook(xml: Node, kind: RunHook.Kind): Either[LoadTechniqueError, RunHook] = {
      def opt(s: String) = if(s == null || s == "") None else Some(s)

      (for {
        bundle <- Either.fromOption(opt((xml \ "@bundle").text), LoadTechniqueError.Parsing(s"attribute 'bundle' is missing in: ${xml}"))
        report <- Either.fromOption((xml \\ "REPORT").toList.flatMap(r =>
                    for {
                      rname <- opt((r \ "@name").text)
                    } yield {
                      RunHook.Report(rname, opt((r \ "@value").text))
                    }
                  ).headOption, LoadTechniqueError.Parsing(s"child node 'REPORT' is missing in: ${xml}"))
      } yield {
        RunHook(bundle, kind, report, (xml \\ "PARAMETER").toList.flatMap(p =>
          for {
            pname  <- opt((p \ "@name" ).text)
                      // for value, look first for <PARAMETER ... value="pvalue"/> and then <PARAMETER>pvalue</PARAMETER>
            pvalue <- opt((p \ "@value").text) orElse opt(p.text)
          } yield {
            RunHook.Parameter(pname, pvalue)
          }
         ))
      }).leftMap(err => LoadTechniqueError.Parsing(s"Error: in technique '${id.toString()}', tried to parse a <${RUN_HOOKS}> xml, but XML is invalid: "+ err.fullMsg))
    }

    if(xml.label != RUN_HOOKS) {
      Left(LoadTechniqueError.Parsing(s"Error in techni in technique '${id.toString()}', this is not a valid <${RUN_HOOKS}>: ${xml}"))
    } else {

      // parse each direct children, but only proceed with PRE and POST.
      xml.child.toList.traverse( c => c.label match {
        case "PRE"  => parseOneHook(c, RunHook.Kind.Pre).map(x => Some(x))
        case "POST" => parseOneHook(c, RunHook.Kind.Post).map(x => Some(x))
        case _      => Right(None)
      } ).map( _.flatten )
    }
  }

}

object TechniqueParser {

  val authorizedCharInId = """([a-zA-Z0-9\-_]+)""".r

  def isValidId(s: String): Boolean = s match {
    case authorizedCharInId(_) => true
    case _ => false
  }

}
