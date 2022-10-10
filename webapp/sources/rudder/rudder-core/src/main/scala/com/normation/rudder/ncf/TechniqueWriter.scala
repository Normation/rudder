/*
*************************************************************************************
* Copyright 2017 Normation SAS
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

package com.normation.rudder.ncf


import better.files.File
import cats.implicits._
import com.normation.box._
import com.normation.cfclerk.domain
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.cfclerk.services.UpdateTechniqueLibrary
import com.normation.errors.IOResult
import com.normation.errors.RudderError
import com.normation.errors._
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.AgentType
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.domain.logger.TechniqueWriterLoggerPure
import com.normation.rudder.domain.logger.TimingDebugLoggerPure
import com.normation.rudder.domain.policies.DeleteDirectiveDiff
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.workflows.ConfigurationChangeRequest
import com.normation.rudder.ncf.ParameterType.ParameterTypeService
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.WoDirectiveRepository
import com.normation.rudder.repository.xml.RudderPrettyPrinter
import com.normation.rudder.repository.xml.TechniqueArchiver
import com.normation.rudder.services.policies.InterpolatedValueCompiler
import com.normation.rudder.services.workflows.ChangeRequestService
import com.normation.rudder.services.workflows.WorkflowLevelService
import com.normation.utils.Control
import com.normation.zio.currentTimeMillis
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import zio._
import zio.syntax._

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import scala.xml.NodeSeq
import scala.xml.{Node => XmlNode}

sealed trait NcfError extends RudderError {
  def message : String
  def exception : Option[Throwable]
  def msg = message
}

final case class IOError(message : String, exception : Option[Throwable]) extends NcfError
final case class TechniqueUpdateError(message : String, exception : Option[Throwable]) extends NcfError
final case class MethodNotFound(message : String, exception : Option[Throwable]) extends NcfError

/*
 * This service is in charge of writing an editor technique and it's related files.
 * This is a higher level api, that works at EditorTechnique level, and is able to generate all the low level stuff (calling rudderc if needed) like
 * metadata.xml, agent related files, etc
 */
trait TechniqueWriter {

  def deleteTechnique(techniqueName: String, techniqueVersion: String, deleteDirective: Boolean, modId: ModificationId, committer: EventActor): IOResult[Unit]

  def writeTechniqueAndUpdateLib(technique: EditorTechnique, methods: Map[BundleName, GenericMethod], modId: ModificationId, committer: EventActor): IOResult[EditorTechnique]

  // Write and commit all techniques files
  def writeTechnique(technique: EditorTechnique, methods: Map[BundleName, GenericMethod], modId: ModificationId, committer: EventActor): IOResult[EditorTechnique]
}



class TechniqueWriterImpl (
    archiver            : TechniqueArchiver
  , techLibUpdate       : UpdateTechniqueLibrary
  , translater          : InterpolatedValueCompiler
  , readDirectives      : RoDirectiveRepository
  , writeDirectives     : WoDirectiveRepository
  , techniqueRepository : TechniqueRepository
  , workflowLevelService: WorkflowLevelService
  , xmlPrettyPrinter    : RudderPrettyPrinter
  , baseConfigRepoPath  : String // root of config repos
  , parameterTypeService: ParameterTypeService
  , techniqueSerializer : TechniqueSerializer
) extends TechniqueWriter {


  private[this] val cfengineTechniqueWriter = new ClassicTechniqueWriter(baseConfigRepoPath, parameterTypeService)
  private[this] val dscTechniqueWriter = new DSCTechniqueWriter(baseConfigRepoPath, translater, parameterTypeService)
  private[this] val agentSpecific = cfengineTechniqueWriter :: dscTechniqueWriter :: Nil

  // root of technique repository
  val techniquesDir = File(baseConfigRepoPath) / "techniques"

  override def deleteTechnique(techniqueName : String, techniqueVersion : String, deleteDirective : Boolean, modId : ModificationId, committer : EventActor) : IOResult[Unit] ={

    def createCr(directive : Directive, rootSection : SectionSpec ) ={
      val diff = DeleteDirectiveDiff(TechniqueName(techniqueName), directive)
      ChangeRequestService.createChangeRequestFromDirective(
        s"Deleting technique ${techniqueName}/${techniqueVersion}"
        , ""
        , TechniqueName(techniqueName)
        , Some(rootSection)
        , directive.id
        , Some(directive)
        , diff
        , committer
        , None
      )
    }

    def mergeCrs(cr1 : ConfigurationChangeRequest, cr2 : ConfigurationChangeRequest) = {
      cr1.copy(directives = cr1.directives ++ cr2.directives)
    }

    def removeTechnique(techniqueId: TechniqueId, technique: domain.Technique): IOResult[Unit] = {
      for {
        directives <- readDirectives.getFullDirectiveLibrary().map(_.allActiveTechniques.values.filter(_.techniqueName.value == techniqueId.name.value).flatMap(_.directives).filter(_.techniqueVersion == techniqueId.version))
        categories <- techniqueRepository.getTechniqueCategoriesBreadCrump(techniqueId)
        // Check if we have directives, and either, make an error, if we don't force deletion, or delete them all, creating a change request
        _          <- directives match {
                        case Nil => UIO.unit
                        case _ =>
                          if (deleteDirective) {
                            val wf = workflowLevelService.getWorkflowService()
                            for {
                              cr <- directives.map(createCr(_,technique.rootSection)).reduceOption(mergeCrs).notOptional(s"Could not create a change request to delete '${techniqueId.serialize}' directives")
                              _  <- wf.startWorkflow(cr, committer, Some(s"Deleting technique '${techniqueId.serialize}'")).toIO
                            } yield ()
                          } else
                              Unexpected(s"${directives.size} directives are defined for '${techniqueId.serialize}': please delete them, or force deletion").fail
                      }
        activeTech <- readDirectives.getActiveTechnique(techniqueId.name)
        _          <- activeTech match {
                        case None =>
                          // No active technique found, let's delete it
                          ().succeed
                        case Some(activeTechnique) =>
                          writeDirectives.deleteActiveTechnique(activeTechnique.id, modId, committer, Some(s"Deleting active technique '${techniqueId.name.value}'"))
                      }
        _          <- archiver.deleteTechnique(techniqueId, categories.map(_.id.name.value), modId, committer, s"Deleting technique '${techniqueId}'")
        _          <- techLibUpdate.update(modId, committer, Some(s"Update Technique library after deletion of technique '${technique.name}'")).toIO.chainError(
                        s"An error occurred during technique update after deletion of Technique ${technique.name}"
                      )
      } yield ()
    }

    def removeInvalidTechnique(techniquesDir: File, techniqueId: TechniqueId): IOResult[Unit] = {
      val unknownTechniquesDir = techniquesDir.listRecursively.filter(_.isDirectory).filter(_.name == techniqueId.name.value).toList
      unknownTechniquesDir.length match {
        case 0 =>
          ApplicationLogger.debug(s"No technique `${techniqueId.debugString}` found to delete").succeed
        case _ =>
          for {
            _ <- ZIO.foreach(unknownTechniquesDir) { f =>
              val cat = f.pathAsString.substring((techniquesDir.pathAsString + "/").length).split("/").filter(s => s != techniqueName && s != techniqueVersion).toList
              for {
                _ <- archiver.deleteTechnique(techniqueId, cat, modId, committer, s"Deleting invalid technique ${techniqueName}/${techniqueVersion}").chainError(
                       s"Error when trying to delete invalids techniques, you can manually delete them by running these commands in " +
                       s"${techniquesDir.pathAsString}: `rm -rf ${f.pathAsString} && git commit -m 'Deleting invalid technique ${f.pathAsString}' && reload-techniques"
                     )
                _ <- techLibUpdate.update(modId, committer, Some(s"Update Technique library after deletion of invalid Technique ${techniqueName}")).toIO.chainError(
                  s"An error occurred during technique update after deletion of Technique ${techniqueName}"
                )
              } yield ()
            }
          } yield ()
      }
    }

    for {
      techVersion <- TechniqueVersion.parse(techniqueVersion).toIO
      techniqueId =  TechniqueId(TechniqueName(techniqueName), techVersion)
      _           <- techniqueRepository.get(techniqueId) match {
                       case Some(technique) => removeTechnique(techniqueId, technique)
                       case None            => removeInvalidTechnique(techniquesDir, techniqueId)
                     }
    } yield ()
  }

  def techniqueMetadataContent(technique : EditorTechnique, methods : Map[BundleName, GenericMethod]) : PureResult[XmlNode] = {

    def reportingValuePerBlock (block : MethodBlock) : PureResult[NodeSeq] = {

      for {
            childs <- reportingSections(block.calls)
          } yield {
            if (childs.isEmpty) {
              NodeSeq.Empty
            } else {
              val reportingLogic = block.reportingLogic.value
              <SECTION component="true" multivalued="true" name={block.component} reporting={reportingLogic}>
                {childs}
              </SECTION>
            }
          }
    }

    def reportingValuePerMethod (call : MethodCall) : PureResult[Seq[XmlNode]] = {
      for {
        method <- methods.get(call.methodId) match {
              case None => Left(MethodNotFound(s"Cannot find method ${call.methodId.value} when writing a method call of Technique '${technique.bundleName.value}'", None))
              case Some(m) => Right(m)
            }
            class_param <- call.parameters.find(_._1 == method.classParameter) match {
              case None => Left(MethodNotFound(s"Cannot find call parameter of ${call.methodId.value} when writing a method call of Technique '${technique.bundleName.value}'", None))
              case Some(m) => Right(m._2)
            }

          } yield {

        <SECTION component="true" multivalued="true" id={call.id} name={call.component}>
          <REPORTKEYS>
            <VALUE id={call.id}>{class_param}</VALUE>
          </REPORTKEYS>
        </SECTION>

      }
    }

    def reportingSections(sections : List[MethodElem]) = {
      val expectedReportingMethodsWithValues = sections.collect{case m : MethodCall => m }.filterNot(m => m.disabledReporting || m.methodId.value.startsWith("_"))
      val expectedGroupReportingMethodsWithValues =sections.collect{case m : MethodBlock => m }

      for {
        uniqueSection <- expectedReportingMethodsWithValues.traverse(reportingValuePerMethod)
        groupSection <- expectedGroupReportingMethodsWithValues.traverse(reportingValuePerBlock)
      } yield {
        groupSection ::: uniqueSection
      }
    }
    def parameterSection(parameter : TechniqueParameter) : Seq[XmlNode] = {
      // Here we translate technique parameters into Rudder variables
      // ncf technique parameters ( having an id and a name, which is used inside the technique) were translated into Rudder variables spec
      // (having a name, which acts as an id, and allow to do templating on techniques, and a description which is presented to users) with the following Rule
      //  ncf Parameter | Rudder variable
      //      id        |      name
      //     name       |   description
      <INPUT>
        <NAME>{parameter.id.value.toUpperCase()}</NAME>
        <DESCRIPTION>{parameter.name.value}</DESCRIPTION>
        <LONGDESCRIPTION>{parameter.description}</LONGDESCRIPTION>
        <CONSTRAINT>
          <TYPE>textarea</TYPE>
          <MAYBEEMPTY>{parameter.mayBeEmpty}</MAYBEEMPTY>
        </CONSTRAINT>
      </INPUT>
    }
    // Regroup method calls from which we expect a reporting
    // We filter those starting by _, which are internal methods

    for {
      reportingSection <- reportingSections(technique.methodCalls.toList)
      agentSpecificSection <- agentSpecific.traverse(_.agentMetadata(technique, methods))
    } yield {
      <TECHNIQUE name={technique.name}>
        { if (technique.parameters.nonEmpty) {
            <POLICYGENERATION>separated-with-parameters</POLICYGENERATION>
            <MULTIINSTANCE>true</MULTIINSTANCE>
          }
        }
        <DESCRIPTION>{technique.description}</DESCRIPTION>
        <USEMETHODREPORTING>true</USEMETHODREPORTING>
        {agentSpecificSection}
        <SECTIONS>
          {reportingSection}
          { if (technique.parameters.nonEmpty) {
              <SECTION name="Technique parameters">
                { technique.parameters.map(parameterSection) }
              </SECTION>
            }

          }
        </SECTIONS>
      </TECHNIQUE>
    }
  }

  def writeTechniqueAndUpdateLib(technique : EditorTechnique, methods: Map[BundleName, GenericMethod], modId : ModificationId, committer : EventActor) : IOResult[EditorTechnique] = {
    for {
      updatedTechnique <- writeTechnique(technique,methods,modId,committer)
      libUpdate        <- techLibUpdate.update(modId, committer, Some(s"Update Technique library after creating files for ncf Technique ${technique.name}")).
        toIO.chainError(s"An error occurred during technique update after files were created for ncf Technique ${technique.name}")
    } yield {
      updatedTechnique
    }
  }

  // Write and commit all techniques files
  override def writeTechnique(technique : EditorTechnique, methods: Map[BundleName, GenericMethod], modId : ModificationId, committer : EventActor) : IOResult[EditorTechnique] = {
    for {
      time_0     <- currentTimeMillis
      _          <- TechniqueWriterLoggerPure.debug(s"Writing technique ${technique.name}")
      // Before writing down technique, set all resources to Untouched state, and remove Delete resources, was the cause of #17750
      updateResources              = technique.ressources.collect{case r if r.state != ResourceFileState.Deleted => r.copy(state = ResourceFileState.Untouched) }
      techniqueWithResourceUpdated = technique.copy(ressources = updateResources)

      json       <- writeJson(techniqueWithResourceUpdated, methods)
      time_1     <- currentTimeMillis
      _          <- TimingDebugLoggerPure.trace(s"writeTechnique: writing json for technique '${technique.name}' took ${time_1 - time_0}ms")
      _          <- writeAgentFiles(technique, methods, modId, committer)
      time_2     <- currentTimeMillis
      _          <- TimingDebugLoggerPure.trace(s"writeTechnique: writing agent files for technique '${technique.name}' took ${time_2 - time_1}ms")

      metadata   <- writeMetadata(technique, methods)
      time_3     <- currentTimeMillis
      _          <- TimingDebugLoggerPure.trace(s"writeTechnique: generating metadata for technique '${technique.name}' took ${time_3 - time_2}ms")
      id         <- TechniqueVersion.parse(technique.version.value).toIO.map(v =>  TechniqueId(TechniqueName(technique.bundleName.value), v))
                    // resources files are missing the the "resources/" prefix
      resources  =  technique.ressources.map(r => ResourceFile("resources/" + r.path, r.state))
      commit     <- archiver.saveTechnique(id, technique.category.split('/').toIndexedSeq, Chunk.fromIterable(resources), modId, committer, s"Committing technique ${technique.name}")
      time_4     <- currentTimeMillis
      _          <- TimingDebugLoggerPure.trace(s"writeTechnique: committing technique '${technique.name}' took ${time_4 - time_3}ms")
      _          <- TimingDebugLoggerPure.debug(s"writeTechnique: writing technique '${technique.name}' took ${time_4 - time_0}ms")

    } yield {
      techniqueWithResourceUpdated
    }
  }


  ///// utility methods /////

  def writeAgentFiles(technique: EditorTechnique, methods: Map[BundleName, GenericMethod], modId: ModificationId, committer: EventActor) : IOResult[Seq[String]] = {
    for {
      // Create/update agent files, filter None by flattening to list
      files  <- ZIO.foreach(agentSpecific)(_.writeAgentFiles(technique, methods)).map(_.flatten)
      _      <- TechniqueWriterLoggerPure.debug(s"writeAgentFiles for technique ${technique.name} is ${files.mkString("\n")}")
    } yield {
      files
    }
  }

  def writeMetadata(technique : EditorTechnique, methods: Map[BundleName, GenericMethod]) : IOResult[String] = {

    val metadataPath = s"techniques/${technique.category}/${technique.bundleName.value}/${technique.version.value}/metadata.xml"

    val path = s"${baseConfigRepoPath}/${metadataPath}"
    for {
      content <- techniqueMetadataContent(technique, methods).map(n => xmlPrettyPrinter.format(n)).toIO
      _       <- IOResult.effect(s"An error occurred while creating metadata file for Technique '${technique.name}'") {
        implicit val charSet = StandardCharsets.UTF_8
        val file = File (path).createFileIfNotExists (true)
        file.write (content)
      }
    } yield {
      metadataPath
    }
  }

  def writeJson(technique: EditorTechnique, methods: Map[BundleName, GenericMethod]): IOResult[String] = {
    val metadataPath = s"${technique.path}/technique.json"

    val path = s"${baseConfigRepoPath}/${metadataPath}"

    val content = techniqueSerializer.serializeTechniqueMetadata(technique, methods)
    for {
      _       <- IOResult.effect(s"An error occurred while creating json file for Technique '${technique.name}'") {
        implicit val charSet = StandardCharsets.UTF_8
        val file = File (path).createFileIfNotExists (true)
        file.write (net.liftweb.json.prettyRender(content))
      }
    } yield {
      metadataPath
    }
  }
}

trait AgentSpecificTechniqueWriter {

  def writeAgentFiles( technique : EditorTechnique, methods : Map[BundleName, GenericMethod] ) : IOResult[Seq[String]]

  def agentMetadata ( technique : EditorTechnique, methods : Map[BundleName, GenericMethod] ) : PureResult[NodeSeq]
}

class ClassicTechniqueWriter(basePath : String, parameterTypeService: ParameterTypeService) extends AgentSpecificTechniqueWriter {

  // We need to add a reporting bundle for this method to generate a na report for any method with a condition != any/cfengine (which ~= true
  def truthyCondition(condition : String) = condition.isEmpty || condition == "any" || condition == "cfengine-community"
  def methodCallNeedReporting(methods : Map[BundleName, GenericMethod], parentBlock : List[MethodBlock])(call : MethodCall) : Boolean = {
    val condition =formatCondition(call, parentBlock)
    methods.get(call.methodId).map(m =>  ! m.agentSupport.contains(AgentType.CfeCommunity) || ! truthyCondition(condition)).getOrElse(true)
  }


  def elemNeedReportingBundle(methods : Map[BundleName, GenericMethod], parentBlock : List[MethodBlock])(elem : MethodElem): Boolean = {
    elem match {
      case c: MethodCall => methodCallNeedReporting(methods, parentBlock)(c)
      case b: MethodBlock => !truthyCondition(b.condition) || b.calls.exists(elemNeedReportingBundle(methods, b :: parentBlock))
    }
  }

  def formatCondition(methodCall: MethodCall, parentBlock : List[MethodBlock]) =  {
    (parentBlock.map(_.condition).filterNot(truthyCondition), truthyCondition(methodCall.condition)) match {
      case (Nil, true) => "any"
      case (list, true) => list.mkString("(",").(",")")
      case (Nil, false) => methodCall.condition
      case (list, false) => list.mkString("(", ").(", s".${methodCall.condition})")
    }
  }

  def needReportingBundle(technique : EditorTechnique, methods : Map[BundleName, GenericMethod]) = technique.methodCalls.exists(elemNeedReportingBundle(methods, Nil))

  def canonifyCondition(methodCall: MethodCall, parentBlock : List[MethodBlock]) = {
    formatCondition(methodCall,parentBlock).replaceAll("""(\$\{[^\}]*})""","""",canonify("$1"),"""")
  }

  // regex to match double quote characters not preceded by a backslash, and backslash not preceded by backslash or not followed by a backslash or a quote (simple or double)
  def escapeCFEngineString(value : String ) = value.replaceAll("""\\""", """\\\\""").replaceAll(""""""" , """\\"""" )
  def reportingContext(methodCall: MethodCall, classParameterValue: String ) = {
    val component  = escapeCFEngineString(methodCall.component)
    val value = escapeCFEngineString(classParameterValue)
    s"""_method_reporting_context_v4("${component}", "${value}","${methodCall.id}")"""
  }

  def reportingContextInBundle(args: Seq[String]) = {
    s"_method_reporting_context_v4(${convertArgsToBundleCall(args)})"
  }


  def convertArgsToBundleCall(args:Seq[String]) : String = {
    args.map(escapeCFEngineString(_)).map(""""${""" + _ + """}"""").mkString(",")
  }


  def writeAgentFiles( technique : EditorTechnique, methods : Map[BundleName, GenericMethod] )  : IOResult[Seq[String]] = {

    // increment of the bundle number in the technique, used by createCallingBundle
    var bundleIncrement = 0

    val bundleParams = if (technique.parameters.nonEmpty) technique.parameters.map(_.name.canonify).mkString("(",",",")") else ""

    // generate a bundle which encapsulate the method_reporting_context + the actual method call
    // and the method to call this bundle
    // Params are:
    // * condition when to call this bundle
    // * the methodCall itself from the technique editor
    // * the class parameter _value_
    // * all the parameters already converted to cfengine format
    // * if it's for the NAReports bundle (reverse the condition from if to unless)
    // Returns the bundle agent, and the "promised_" usebundle => bundle_created
    def createCallingBundle(condition          : String
                          , call               : MethodCall
                          , classParameterValue: String
                          , params             : Seq[String]
                          , forNaReport        : Boolean) = {
      val promiser = call.id + "_${report_data.directive_id}"

      val filterOnMethod = forNaReport match {
        case false => "if"
        case true  => "unless"
      }


      // Reporting argument
      val reportingValues = escapeCFEngineString(call.component) ::
                          escapeCFEngineString(classParameterValue) ::
                          call.id :: Nil
      // create the bundle arguments:
      // there are 3 arguments corresponding to the reportingValues, (need to be quoted)
      // the rest is for the methodArgs.
      val allValues = reportingValues.map( x  => s""""${x}"""")  ::: params.toList

      val method = methods.get(call.methodId)

      // Get all bundle argument names
      val bundleName = (technique.bundleName.value + "_gm_" + bundleIncrement).replaceAll("-", "_")
      bundleIncrement = bundleIncrement + 1

      val reportingArgs = "c_name" :: "c_key" :: "report_id" :: Nil

      val (bundleArgs, bundleNameAndArg) = forNaReport match {
        case false =>
          val args = params.toList.zipWithIndex.map {
          case (_, id) =>
            method.flatMap(_.parameters.get(id.toLong).map(_.id.value)).getOrElse("arg_" + id)
          }
          (args, s"""${call.methodId.value}(${convertArgsToBundleCall(args)});""")

        case true =>
          // special case for log_na_rudder; args muse be called with @
          val args = "message" :: "class_parameter" :: "unique_prefix" :: "args" :: Nil
          (args, """log_na_rudder("${message}","${class_parameter}","${unique_prefix}",@{args});""")
      }

      val allArgs = reportingArgs ::: bundleArgs

      // The bundle that will effectively act
      val bundleActing = {
        val bundleCall =
          s"""    "${promiser}" usebundle => ${reportingContextInBundle(reportingArgs)};
             |    "${promiser}" usebundle => ${bundleNameAndArg}""".stripMargin('|')

        val bundleCallWithReportOption =
          if (call.disabledReporting) {
            s"""    "${promiser}" usebundle => disable_reporting;
               |${bundleCall}
               |    "${promiser}" usebundle => enable_reporting;""".stripMargin('|')
          } else {
            bundleCall
          }


        s"""bundle agent ${bundleName}(${allArgs.mkString(", ")}) {
             |  methods:
             |${bundleCallWithReportOption}
             |}
             |""".stripMargin('|')
      }

      // the call to the bundle
      val callBundle = {
          s"""    "${promiser}" usebundle => ${bundleName}(${allValues.mkString(", ")}),
             |     ${promiser.map(_ => ' ')}         ${filterOnMethod} => concat("${condition}");
             |""".stripMargin('|')


      }
      (bundleActing, callBundle)
    }

    // returns the bundle acting, and the method to call the bundle
    def bundleMethodCall( parentBlocks : List[MethodBlock])(method : MethodElem) : List[(String, String)] = {
      method match {
        case call : MethodCall =>
          (for {
            method_info <- methods.get(call.methodId)
            (_, classParameterValue) <- call.parameters.find( _._1 == method_info.classParameter)

            params <- Control.sequence(method_info.parameters) {
              p =>
                for {
                  (_,value) <- Box(call.parameters.find(_._1 == p.id))
                  escaped   <- parameterTypeService.translate(value, p.parameterType, AgentType.CfeCommunity).toBox
                } yield {
                  escaped
                }
            }
          }  yield {
            val condition = canonifyCondition(call, parentBlocks)
            createCallingBundle(condition, call, classParameterValue, params, false)
          }).toList
        case block : MethodBlock =>
          block.calls.flatMap(bundleMethodCall(block :: parentBlocks))
      }
    }
    val bundleAndMethodCallsList = technique.methodCalls.flatMap(bundleMethodCall(Nil))

    val bundleActings = bundleAndMethodCallsList.map(_._1).mkString("")
    val methodsCalls = bundleAndMethodCallsList.map(_._2).mkString("")



    val content = {
      import net.liftweb.json.JsonDSL._
      import net.liftweb.json._

      s"""# @name ${technique.name}
         |# @description ${technique.description.replaceAll("\\R", "\n# ")}
         |# @version ${technique.version.value}
         |${technique.parameters.map { p =>
            val param = ("name" -> p.name.value) ~ ("id" -> p.id.value) ~ ("description" -> p.description.replaceAll("\\R", "£# ") )
            // for historical reason, we want to have real \n in the string, and not the char \n (because of how liftweb print them)
            s"""# @parameter ${compactRender(param).replaceAll("£#","\n#")}""" }.mkString("\n")}
         |
         |bundle agent ${technique.bundleName.value}${bundleParams}
         |{
         |  vars:
         |    "resources_dir" string => "$${this.promise_dirname}/resources";
         |  classes:
         |    "pass3" expression => "pass2";
         |    "pass2" expression => "pass1";
         |    "pass1" expression => "any";
         |  methods:
         |${methodsCalls}
         |}
         |
         |${bundleActings}""".stripMargin('|')
    }

    implicit val charset = StandardCharsets.UTF_8
    val techFile = File(basePath) / "techniques"/ technique.category / technique.bundleName.value / technique.version.value / "technique.cf"
    val t = IOResult.effect(s"Could not write na reporting Technique file '${technique.name}' in path ${techFile.path.toString}") {
      techFile.createFileIfNotExists(true).write(content.stripMargin('|'))
      File(basePath).relativize(techFile.path).toString
    }



    val t2 = if ( ! needReportingBundle(technique, methods)) {
      ZIO.succeed(Nil)
    } else {

      val bundleParams = if (technique.parameters.nonEmpty) technique.parameters.map(_.name.canonify).mkString("(",",",")") else ""
      val args = technique.parameters.map(p => s"$${${p.name.canonify}}").mkString(", ")

      def bundleMethodCall( parentBlocks : List[MethodBlock])(method : MethodElem) : List[(String, String)] = {
        method match {
          case c : MethodCall =>
            val call = MethodCall.renameParams(c,methods).copy(methodId = BundleName("log_na_rudder"))
            (for {
              method_info <- methods.get(c.methodId)
              // Skip that method if name starts with _
              if ! c.methodId.value.startsWith("_")
              (_, classParameterValue) <- call.parameters.find( _._1 == method_info.classParameter)

              escapedClassParameterValue = escapeCFEngineString(classParameterValue)
              classPrefix = s"$${class_prefix}_${method_info.classPrefix}_${escapedClassParameterValue}"

            }  yield {
              def naReport(condition : String, message : String) = {

                val params = s""""${message}"""" :: s""""${escapedClassParameterValue}"""" :: s""""${classPrefix}"""" :: "@{args}" :: Nil

                createCallingBundle(condition, call, classParameterValue, params, true)
              }

              // Write report if the method does not support CFEngine ...
              (if (! method_info.agentSupport.contains(AgentType.CfeCommunity)) {
                val message = s"""'${method_info.name}' method is not available on Linux Rudder agent, skip"""
                val condition = "false"
                Some((condition,message))
              } else {
                // ... or if the condition needs rudder_reporting
                if (methodCallNeedReporting(methods, parentBlocks)(c)) {
                  val message = s"""Skipping method '${method_info.name}' with key parameter '${escapedClassParameterValue}' since condition '${call.condition}' is not reached"""
                  val condition = s"${canonifyCondition(call, parentBlocks)}"
                  Some((condition, message))
                } else {
                  None
                }
              }).map((naReport _).tupled)
            }).toList.flatten
          case block : MethodBlock =>
            block.calls.flatMap(bundleMethodCall(block :: parentBlocks))
        }
      }
      val bundleAndMethodCallsList = technique.methodCalls.flatMap(bundleMethodCall(Nil))

      val bundleActings = bundleAndMethodCallsList.map(_._1).mkString("")
      val methodsCalls = bundleAndMethodCallsList.map(_._2).mkString("")

      val content =
        s"""bundle agent ${technique.bundleName.value}_rudder_reporting${bundleParams}
           |{
           |  vars:
           |    "args"               slist => { ${args} };
           |    "report_param"      string => join("_", args);
           |    "full_class_prefix" string => canonify("${technique.bundleName.value}_rudder_reporting_$${report_param}");
           |    "class_prefix"      string => string_head("$${full_class_prefix}", "1000");
           |
           |  methods:
           |${methodsCalls}
           |}
           |
           |${bundleActings}"""

      val reportingFile = File(basePath) / "techniques"/ technique.category / technique.bundleName.value / technique.version.value / "rudder_reporting.cf"
      IOResult.effect(s"Could not write na reporting Technique file '${technique.name}' in path ${reportingFile.path.toString}") {
        reportingFile.createFileIfNotExists(true).write(content.stripMargin('|'))
        Seq(File(basePath).relativize(reportingFile.path).toString)
      }
    }

    for {
      tech <- t
      repo <- t2
    } yield {
      tech +: repo
    }
  }

  def agentMetadata ( technique : EditorTechnique, methods : Map[BundleName, GenericMethod] )  : PureResult[NodeSeq] = {

    val needReporting =  needReportingBundle(technique, methods)
    val xml = <AGENT type="cfengine-community,cfengine-nova">
      <BUNDLES>
        <NAME>{technique.bundleName.value}</NAME>
        {if (needReporting) <NAME>{technique.bundleName.value}_rudder_reporting</NAME>}
      </BUNDLES>
      <FILES>
        <FILE name={s"RUDDER_CONFIGURATION_REPOSITORY/techniques/${technique.category}/${technique.bundleName.value}/${technique.version.value}/technique.cf"}>
          <INCLUDED>true</INCLUDED>
        </FILE>
        { if (needReporting)
          <FILE name={s"RUDDER_CONFIGURATION_REPOSITORY/techniques/${technique.category}/${technique.bundleName.value}/${technique.version.value}/rudder_reporting.cf"}>
            <INCLUDED>true</INCLUDED>
          </FILE>
        }
        { for {
            resource <- technique.ressources
            if resource.state != ResourceFileState.Deleted
          } yield {
            <FILE name={s"RUDDER_CONFIGURATION_REPOSITORY/techniques/${technique.category}/${technique.bundleName.value}/${technique.version.value}/resources/${resource.path}"}>
              <INCLUDED>false</INCLUDED>
              <OUTPATH>{technique.bundleName.value}/{technique.version.value}/resources/{resource.path}</OUTPATH>
            </FILE>
          }
        }
      </FILES>
    </AGENT>
    Right(xml)
  }

}


class DSCTechniqueWriter(
    basePath   : String
  , translater : InterpolatedValueCompiler
  , parameterTypeService : ParameterTypeService
) extends AgentSpecificTechniqueWriter{
  implicit class IndentString(s: String) {
    // indent all lines EXCLUDING THE FIRST by the given number of spaces
    def indentNextLines(spaces:Int) = s.linesIterator.mkString("\n" + " " * spaces)
  }

  // we use the same class prefix construction as for CFEngine.
  // If it's really the same thing, it should either be given by technique editor or common to both
  def computeClassPrefix(gm: GenericMethod, classParameter: String): String = {
    // the canonification must be done commonly with other canon
    s"""[Rudder.Condition]::canonify("${gm.classPrefix}_" + ${classParameter})"""
  }

  // WARNING: this is extremely likely false, it MUST be done in the technique editor or
  // via a full fledge parser of conditions
  def canonifyCondition(methodCall: MethodCall, parentBlocks: List[MethodBlock]) = {
    formatCondition(methodCall, parentBlocks).replaceAll("""(\$\{[^\}]*})""", """" + ([Rudder.Condition]::canonify(\$componentKey)) + """")
  }

  def truthyCondition(condition: String) = condition.isEmpty || condition == "any"

  def formatCondition(methodCall: MethodCall, parentBlock: List[MethodBlock]) = {
    (parentBlock.map(_.condition).filterNot(truthyCondition), truthyCondition(methodCall.condition)) match {
      case (Nil, true)   => "any"
      case (list, true)  => list.mkString("(", ").(", ")")
      case (Nil, false)  => methodCall.condition
      case (list, false) => list.mkString("(", ").(", s".${methodCall.condition})")
    }
  }

  def computeTechniqueFilePath(technique : EditorTechnique) =
    s"techniques/${technique.category}/${technique.bundleName.value}/${technique.version.value}/technique.ps1"

  def formatDscMethodBlock(techniqueName: String, methods : Map[BundleName, GenericMethod], parentBlocks: List[MethodBlock])(method: MethodElem): PureResult[List[String]] = {
    method match {
      case c : MethodCall =>
        val call = MethodCall.renameParams(c,methods)
        if (call.methodId.value.startsWith("_")) {
          Right(Nil)
        } else {
          val componentName = call.component.replaceAll("\"", "`\"")
          val disableReporting = if (call.disabledReporting) { "true" } else { "false" }



          (for {

            // First translate parameters to Dsc values
            params    <- ((call.parameters.toList).traverse {
                            case (id, arg) =>
                              translater.translateToAgent(arg, AgentType.Dsc) match {
                                case Full(dscValue) =>
                                  parameterTypeService.translate(dscValue, methods.get(call.methodId).flatMap(_.parameters.find(_.id == id)).map(_.parameterType).getOrElse(ParameterType.StringParameter), AgentType.Dsc).map(dscValue => (id,dscValue))

                                case eb : EmptyBox =>
                                  val fail = eb ?~! s"Error when translating parameter '${arg}' of technique of method ${call.methodId} of technique ${techniqueName}"
                                  Left(IOError(fail.messageChain,None))
                              }
                         }).map(_.toMap)

            // Translate condition
            condition <- translater.translateToAgent(canonifyCondition(call, parentBlocks), AgentType.Dsc) match {
                           case Full(c) => Right(c)
                           case eb : EmptyBox =>
                             val fail = eb ?~! s"Error when translating condition '${call.condition}' of technique of method ${call.methodId} of technique ${techniqueName}"
                             Left(IOError(fail.messageChain,None))
                         }

            // Check if method exists
            method <- methods.get(call.methodId) match {
                        case Some(method) =>
                          Right(method)
                        case None =>
                          Left(MethodNotFound(s"Method '${call.methodId.value}' not found when writing dsc Technique '${techniqueName}' methods calls", None))
                      }
            // Check if class parameter is correctly defined
            classParameter <- params.get(method.classParameter) match {
                                case Some(classParameter) =>
                                  Right(classParameter)
                                case None =>
                                  Left(MethodNotFound(s"Parameter '${method.classParameter.value}' for method '${method.id.value}' not found when writing dsc Technique '${techniqueName}' methods calls",None))
                              }

            methodParams = params.map { case(id, arg) => s"""-${id.validDscName} ${arg}""" }.mkString(" ")
            effectiveCall = s"""$$call = ${call.methodId.validDscName} ${methodParams} -PolicyMode $$policyMode""" // methodParams can be multiline text
                                                                                                                   // so we should never indent it
            methodContext = s"""$$methodContext = Compute-Method-Call @reportParams -MethodCall $$call
                 |$$localContext.merge($$methodContext)
                 |""".stripMargin


          } yield {
            val methodCall = if (method.agentSupport.contains(AgentType.Dsc)) {
              if (condition == "any" ) {
                s"""  ${effectiveCall}
                   |  ${methodContext.indentNextLines(2)}""".stripMargin
              } else {
                s"""  $$class = "${condition}"
                   |  if ($$localContext.Evaluate($$class)) {
                   |    ${effectiveCall}
                   |    ${methodContext.indentNextLines(4)}
                   |  } else {
                   |    Rudder-Report-NA @reportParams
                   |  }""".stripMargin('|')
              }
            } else {
              s"  Rudder-Report-NA @reportParams"
            }

            s"""|  $$reportId=$$reportIdBase+"${call.id}"
                |  $$componentKey = ${classParameter}
                |  $$reportParams = @{
                |    ClassPrefix = ([Rudder.Condition]::canonify(("${method.classPrefix}_" + $$componentKey)))
                |    ComponentKey = $$componentKey
                |    ComponentName = "${componentName}"
                |    PolicyMode = $$policyMode
                |    ReportId = $$reportId
                |    DisableReporting = $$${disableReporting}
                |    TechniqueName = $$techniqueName
                |  }
                |${methodCall}""".stripMargin('|') :: Nil

          })
       }

    case block : MethodBlock =>
      block.calls.flatTraverse(formatDscMethodBlock(techniqueName, methods, block :: parentBlocks))
    }
  }

  def writeAgentFiles(technique : EditorTechnique, methods : Map[BundleName, GenericMethod]): IOResult[Seq[String]] = {

    val parameters = technique.parameters match {
      case Nil => ""
      case params =>
        params.map( p =>
          s"""      [parameter(Mandatory=$$true)]
             |      [string]$$${p.name.validDscName},"""
        ).mkString("\n","\n","").stripMargin('|')
    }

    val techniquePath = computeTechniqueFilePath(technique)

    for {

      calls <- technique.methodCalls.toList.flatTraverse(formatDscMethodBlock(technique.name, methods, Nil)).toIO

      content =
        s"""|function ${technique.bundleName.validDscName} {
            |  [CmdletBinding()]
            |  param (
            |      [parameter(Mandatory=$$true)]
            |      [string]$$reportId,
            |      [parameter(Mandatory=$$true)]
            |      [string]$$techniqueName,${parameters}
            |      [Rudder.PolicyMode]$$policyMode
            |  )
            |  BeginTechniqueCall -Name $$techniqueName
            |  $$reportIdBase = $$reportId.Substring(0,$$reportId.Length-1)
            |  $$localContext = [Rudder.Context]::new($$techniqueName)
            |  $$localContext.Merge($$system_classes)
            |  $$resources_dir = $$PSScriptRoot + "\\resources"
            |
            |${calls.mkString("\n\n")}
            |  EndTechniqueCall -Name $$techniqueName
            |}""".stripMargin('|')

      path  <-  IOResult.effect(s"Could not find dsc Technique '${technique.name}' in path ${basePath}/${techniquePath}") (
                  Paths.get(s"${basePath}/${techniquePath}")
                )
      // Powershell files needs to have a BOM added at the beginning of all files when using UTF8 enoding
      // See https://docs.microsoft.com/en-us/windows/desktop/intl/using-byte-order-marks
      // Bom, three bytes: EF BB BF https://en.wikipedia.org/wiki/Byte_order_mark
      contentWithBom = Array(239.toByte, 187.toByte, 191.toByte) ++ content.getBytes(StandardCharsets.UTF_8)

      files <-  IOResult.effect(s"Could not write dsc Technique file '${technique.name}' in path ${basePath}/${techniquePath}") {
                  Files.createDirectories(path.getParent)
                  Files.write(path, contentWithBom.toArray)
                }
    } yield {
      techniquePath :: Nil
    }
  }

  def agentMetadata(technique : EditorTechnique, methods : Map[BundleName, GenericMethod] ) = {
    val xml = <AGENT type="dsc">
      <BUNDLES>
        <NAME>{technique.bundleName.validDscName}</NAME>
      </BUNDLES>
      <FILES>
        <FILE name={s"RUDDER_CONFIGURATION_REPOSITORY/${computeTechniqueFilePath(technique)}"}>
          <INCLUDED>true</INCLUDED>
        </FILE> {
          for {
            resource <- technique.ressources
            if resource.state != ResourceFileState.Deleted
          } yield {
            <FILE name={s"RUDDER_CONFIGURATION_REPOSITORY/techniques/${technique.category}/${technique.bundleName.value}/${technique.version.value}/resources/${resource.path}"}>
              <INCLUDED>false</INCLUDED>
              <OUTPATH>{technique.bundleName.value}/{technique.version.value}/resources/{resource.path}</OUTPATH>
            </FILE>
          }
        }
      </FILES>
    </AGENT>
    Right(xml)
  }

}


