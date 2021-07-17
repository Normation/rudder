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


import cats.implicits._
import com.normation.errors._
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId

import java.nio.charset.StandardCharsets
import com.normation.inventory.domain.AgentType
import com.normation.rudder.repository.GitModificationRepository
import com.normation.rudder.repository.xml.GitArchiverUtils
import com.normation.cfclerk.services.GitRepositoryProvider

import java.io.{File => JFile}
import java.nio.file.Files
import java.nio.file.Paths
import better.files.File
import better.files.File.root
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.cfclerk.services.UpdateTechniqueLibrary
import com.normation.errors.IOResult
import com.normation.rudder.domain.policies.DeleteDirectiveDiff
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.workflows.ConfigurationChangeRequest
import com.normation.rudder.ncf.ParameterType.ParameterTypeService
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.xml.RudderPrettyPrinter
import com.normation.rudder.services.user.PersonIdentService
import net.liftweb.common.Full
import zio._
import zio.syntax._

import scala.xml.NodeSeq
import scala.xml.{Node => XmlNode}
import com.normation.rudder.services.policies.InterpolatedValueCompiler
import com.normation.rudder.services.workflows.ChangeRequestService
import com.normation.rudder.services.workflows.WorkflowLevelService
import com.normation.utils.Control
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import com.normation.rudder.hooks.Cmd
import com.normation.rudder.hooks.RunNuCommand
import com.normation.errors.RudderError
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.repository.WoDirectiveRepository
import org.joda.time.DateTime

trait NcfError extends RudderError {
  def message : String
  def exception : Option[Throwable]
  def msg = message
}

final case class IOError(message : String, exception : Option[Throwable]) extends NcfError
final case class TechniqueUpdateError(message : String, exception : Option[Throwable]) extends NcfError
final case class MethodNotFound(message : String, exception : Option[Throwable]) extends NcfError

class RudderCRunner (
    configFilePath : String
  , rudderCPath    : String
  , outputPath     : String
)  {
  def compileTechnique(technique : EditorTechnique) = {
    for {
      r <- RunNuCommand.run(Cmd(rudderCPath, "save" :: "-j" :: "-i" :: s""""${outputPath}/${technique.path}/technique.json"""" :: s"--config-file=${configFilePath}" :: Nil, Map.empty))
      res <- r.await
      _ <- ZIO.when(res.code != 0) {
        Inconsistency(
          s"An error occurred when translating technique.json file into Rudder language\n code: ${res.code}\n stderr: ${res.stderr}\n stdout: ${res.stdout}"
        ).fail
      }
      r <- RunNuCommand.run(Cmd(rudderCPath, "compile" :: "-j" :: "-f" :: """"cf"""" :: "-i" :: s""""${outputPath}/${technique.path}/technique.rl"""" :: s"--config-file=${configFilePath}" :: Nil, Map.empty))
      res <- r.await
      _ <- ZIO.when(res.code != 0) {
        Inconsistency(
          s"An error occurred when compiling technique.rl file into cfengine\n code: ${res.code}\n stderr: ${res.stderr}\n stdout: ${res.stdout}"
        ).fail
      }
      r <- RunNuCommand.run(Cmd(rudderCPath, "compile" :: "-j" :: "-f" :: """"dsc"""" :: "-i" :: s""""${outputPath}/${technique.path}/technique.rl"""" :: s"--config-file=${configFilePath}" :: Nil, Map.empty))
      res <- r.await
      _ <- ZIO.when(res.code != 0) {
        Inconsistency(
          s"An error occurred when compiling technique.rl file into dsc\n code: ${res.code}\n stderr: ${res.stderr}\n stdout: ${res.stdout}"
        ).fail
      }
    } yield {
      ()
    }
  }
}

class TechniqueWriter (
    archiver            : TechniqueArchiver
  , techLibUpdate       : UpdateTechniqueLibrary
  , translater          : InterpolatedValueCompiler
  , readDirectives      : RoDirectiveRepository
  , writeDirectives     : WoDirectiveRepository
  , techniqueRepository : TechniqueRepository
  , workflowLevelService: WorkflowLevelService
  , xmlPrettyPrinter    : RudderPrettyPrinter
  , basePath            : String
  , parameterTypeService: ParameterTypeService
  , techniqueSerializer : TechniqueSerializer
  , compiler            : RudderCRunner
  , errorLogPath        : String
) {

  private[this] val agentSpecific = new ClassicTechniqueWriter(basePath, parameterTypeService) :: new DSCTechniqueWriter(basePath, translater, parameterTypeService) :: Nil

  def deleteTechnique(techniqueName : String, techniqueVersion : String, deleteDirective : Boolean, modId : ModificationId, committer : EventActor) : IOResult[Unit] ={
    val techniqueId = TechniqueId(TechniqueName(techniqueName), TechniqueVersion(techniqueVersion))

    def createCr(directive : Directive, rootSection : SectionSpec ) ={
        val diff = DeleteDirectiveDiff(techniqueId.name, directive)
        ChangeRequestService.createChangeRequestFromDirective(
          s"Deleting technique ${techniqueName}/${techniqueVersion}"
          , ""
          , techniqueId.name
          , rootSection
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

    for {
      directives <- readDirectives.getFullDirectiveLibrary().map(_.allActiveTechniques.values.filter(_.techniqueName.value == techniqueId.name.value).flatMap(_.directives).filter(_.techniqueVersion == techniqueId.version))

      technique  <- techniqueRepository.get(techniqueId).notOptional(s"No Technique with ID '${techniqueId.toString()}' found in reference library.")
      category   <- techniqueRepository.getParentTechniqueCategory_forTechnique(techniqueId)

      // Check if we have directives, and either, make an error, if we don't force deletion, or delete them all, creating a change request
      _          <-  directives match {
                       case Nil => UIO.unit
                       case _ =>
                         if (deleteDirective) {
                           val wf = workflowLevelService.getWorkflowService()
                           for {
                             cr <- directives.map(createCr(_,technique.rootSection)).reduceOption(mergeCrs).notOptional(s"Could not create a change request to delete ${techniqueName}/${techniqueVersion} directives")
                             _  <- wf.startWorkflow(cr, committer, Some(s"Deleting technique ${techniqueName}/${techniqueVersion}")).toIO
                            } yield {
                             ()
                            }
                         } else
                           Unexpected(s"${directives.size} directives are defined for ${techniqueName}/${techniqueVersion} please delete them, or force deletion").fail
                     }

      activeTech <- readDirectives.getActiveTechnique(TechniqueName(technique.name))
      _          <- activeTech match {
                      case None =>
                        // No active technique found, let's delete it
                        ().succeed
                      case Some(activeTechnique) =>
                        writeDirectives.deleteActiveTechnique(activeTechnique.id, modId, committer, Some(s"Deleting active technique ${techniqueName}"))
                    }
      _          <- archiver.deleteTechnique(techniqueName,techniqueVersion, category.id.name.value, modId,committer, s"Deleting technique ${techniqueName}/${techniqueVersion}")

      _          <- techLibUpdate.update(modId, committer, Some(s"Update Technique library after deletion of Technique ${techniqueName}")).toIO.chainError(
                      s"An error occurred during technique update after deletion of Technique ${techniqueName}"
                    )
    } yield {
      ()
    }
  }

  def techniqueMetadataContent(technique : EditorTechnique, methods: Map[BundleName, GenericMethod]) : PureResult[XmlNode] = {

    def reportingValuePerBlock (component: String, calls :Seq[MethodBlock]) : PureResult[Seq[XmlNode]] = {

      for {
        res <- calls.toList.traverse(block =>
          for {
            childs <- reportingSections(block.calls)
          } yield {
            val reportingLogic = block.reportingLogic.value
            <SECTION component="true" multivalued="true" name={component} reporting={reportingLogic}>
                {childs}
            </SECTION>
          })
      } yield {
        res
      }
    }

    def reportingValuePerMethod (component: String, calls :Seq[MethodCall]) : PureResult[Seq[XmlNode]] = {
      for {
        spec <- calls.toList.traverse ( call =>
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
            <VALUE>{class_param}</VALUE>
          }

        )
      } yield {

        <SECTION component="true" multivalued="true" name={component}>
          <REPORTKEYS>
            {spec}
          </REPORTKEYS>
        </SECTION>

      }
    }

    def reportingSections(sections : List[MethodElem]) = {
      val expectedReportingMethodsWithValues =
        for {
          (component, methodCalls) <- sections.collect{case m : MethodCall => m }.filterNot(_.methodId.value.startsWith("_")).groupBy(_.component).toList.sortBy(_._1)
        } yield {
          (component, methodCalls)
        }
      val expectedGroupReportingMethodsWithValues =
        for {
          (component, methodCalls) <- sections.collect{case m : MethodBlock => m }.groupBy(_.component).toList.sortBy(_._1)
        } yield {
          (component, methodCalls)
        }

      for {
        uniqueSection <- expectedReportingMethodsWithValues.traverse((reportingValuePerMethod _).tupled)
        groupSection <- expectedGroupReportingMethodsWithValues.traverse((reportingValuePerBlock _).tupled)
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
      libUpdate  <- techLibUpdate.update(modId, committer, Some(s"Update Technique library after creating files for ncf Technique ${technique.name}")).
                      toIO.chainError(s"An error occured during technique update after files were created for ncf Technique ${technique.name}")
    } yield {
      updatedTechnique
    }
  }

  // Write and commit all techniques files
  def writeTechnique(technique : EditorTechnique, methods: Map[BundleName, GenericMethod], modId : ModificationId, committer : EventActor) : IOResult[EditorTechnique] = {
    for {
      metadata   <- writeMetadata(technique, methods, modId, committer)
      // Before writing down technique, set all resources to Untouched state, and remove Delete resources, was the cause of #17750
      updateResources = technique.ressources.collect{case r if r.state != ResourceFileState.Deleted => r.copy(state = ResourceFileState.Untouched) }
                         techniqueWithResourceUpdated = technique.copy(ressources = updateResources)
      json       <- writeJson(techniqueWithResourceUpdated)
      agentFiles <- compiler.compileTechnique(technique).catchAll { e =>
        val errorPath : File = root / errorLogPath /  "rudderc" / "failures" / s"${DateTime.now()}_${technique.bundleName.value}.log"
        for {
          _ <- ApplicationLoggerPure.error(s"An error occurred when compiling technique '${technique.name}' (id : '${technique.bundleName}') with rudderc, error details in ${errorPath}, falling back to old saving process")
          _ <- IOResult.effect {
                errorPath.createFileIfNotExists(true)
                errorPath.write(
                  s"""
                  |error =>
                  |  ${e.fullMsg}
                  |technique data =>
                  |  ${net.liftweb.json.prettyRender(techniqueSerializer.serializeTechniqueMetadata(technique))}""".stripMargin)
               }.catchAll(e2 =>
                   ApplicationLoggerPure.error(s"Error when writing error log of '${technique.name}' (id : '${technique.bundleName}') in in ${errorPath}: ${e2.fullMsg}") *>
                   ApplicationLoggerPure.error(s"Error when compiling '${technique.name}' (id : '${technique.bundleName}') with rudderc was: ${e.fullMsg}")
               )
          _   <- writeAgentFiles(technique, methods, modId, committer)
        } yield {
          ()
        }
      }

      commit     <- archiver.commitTechnique(technique, modId, committer, s"Committing technique ${technique.name}")
    } yield {
      techniqueWithResourceUpdated
    }
  }

  def writeAgentFiles(technique : EditorTechnique, methods: Map[BundleName, GenericMethod], modId : ModificationId, commiter : EventActor) : IOResult[Seq[String]] = {
    for {
      // Create/update agent files, filter None by flattening to list
      files  <- ZIO.foreach(agentSpecific)(_.writeAgentFiles(technique, methods)).map(_.flatten)
    } yield {
      files
    }
  }

  def writeMetadata(technique : EditorTechnique, methods: Map[BundleName, GenericMethod], modId : ModificationId, commiter : EventActor) : IOResult[String] = {

    val metadataPath = s"techniques/${technique.category}/${technique.bundleName.value}/${technique.version.value}/metadata.xml"

    val path = s"${basePath}/${metadataPath}"
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

  def writeJson(technique: EditorTechnique) = {
    val metadataPath = s"${technique.path}/technique.json"

    val path = s"${basePath}/${metadataPath}"

    val content = techniqueSerializer.serializeTechniqueMetadata(technique)
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
  def escapeCFEngineString(value : String ) = value.replaceAll("""(?<!\\)"""", """\\"""").replaceAll("""(?<!\\)\\(?!(\\|"|'))""","""\\\\""")
  def reportingContext(methodCall: MethodCall, classParameterValue: String ) = {
    val component  = escapeCFEngineString(methodCall.component)
    val value = escapeCFEngineString(classParameterValue)
    s"""_method_reporting_context("${component}", "${value}")"""
  }



  def writeAgentFiles( technique : EditorTechnique, methods : Map[BundleName, GenericMethod] )  : IOResult[Seq[String]] = {

    val bundleParams = if (technique.parameters.nonEmpty) technique.parameters.map(_.name.canonify).mkString("(",",",")") else ""

    def bundleMethodCall( parentBlocks : List[MethodBlock])(method : MethodElem) : List[String] = {
      method match {
        case call : MethodCall =>
          (for {
            method_info <- methods.get(call.methodId)
            (_, classParameterValue) <- call.parameters.find( _._1 == method_info.classParameter)

            params <- Control.sequence(method_info.parameters) {
              p =>
                import  com.normation.box.EitherToBox
                for {
                  (_,value) <- Box(call.parameters.find(_._1 == p.id))
                  escaped   <- parameterTypeService.translate(value, p.parameterType, AgentType.CfeCommunity).toBox
                } yield {
                  escaped
                }
            }
          }  yield {
            val condition = canonifyCondition(call, parentBlocks)
            val promiser = call.id
            // Check constraint and missing value
            val args = params.mkString(", ")

            s"""    "${promiser}" usebundle => ${reportingContext(call, classParameterValue)},
               |     ${promiser.map(_ => ' ')}         if => concat("${condition}");
               |    "${promiser}" usebundle => ${call.methodId.value}(${args}),
               |     ${promiser.map(_ => ' ')}         if => concat("${condition}");
               |""".stripMargin('|')

          }).toList
        case block : MethodBlock =>
          block.calls.flatMap(bundleMethodCall(block :: parentBlocks))
      }
    }
    val methodCalls = technique.methodCalls.flatMap(bundleMethodCall(Nil)).mkString("")

    val content = {
      import net.liftweb.json._
      import net.liftweb.json.JsonDSL._

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
         |  methods:
         |${methodCalls}
         |}""".stripMargin('|')
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

      def bundleMethodCall( parentBlocks : List[MethodBlock])(method : MethodElem) : List[String] = {
        method match {
          case call : MethodCall =>
            (for {
              method_info <- methods.get(call.methodId)
              // Skip that method if name starts with _
              if ! call.methodId.value.startsWith("_")
              (_, classParameterValue) <- call.parameters.find( _._1 == method_info.classParameter)

              escapedClassParameterValue = escapeCFEngineString(classParameterValue)
              classPrefix = s"$${class_prefix}_${method_info.classPrefix}_${escapedClassParameterValue}"

            }  yield {
              val promiser = call.id
              def naReport(condition : String, message : String) = {
                s"""    "${promiser}" usebundle => ${reportingContext(call, classParameterValue)},
                   |     ${promiser.map(_ => ' ')}     unless => ${condition};
                   |    "${promiser}" usebundle => log_na_rudder("${message}", "${escapedClassParameterValue}", "${classPrefix}", @{args}),
                   |     ${promiser.map(_ => ' ')}     unless => ${condition};""".stripMargin('|')
              }


              // Write report if the method does not support CFEngine ...
              (if (! method_info.agentSupport.contains(AgentType.CfeCommunity)) {
                val message = s"""'${method_info.name}' method is not available on classic Rudder agent, skip"""
                val condition = "\"false\""
                Some((condition,message))
              } else {
                // ... or if the condition needs rudder_reporting
                if (methodCallNeedReporting(methods, parentBlocks)(call)) {
                  val message = s"""Skipping method '${method_info.name}' with key parameter '${escapedClassParameterValue}' since condition '${call.condition}' is not reached"""
                  val condition = s"""concat("${canonifyCondition(call, parentBlocks)}")"""
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
      val methodsReporting = technique.methodCalls.flatMap(bundleMethodCall(Nil)).mkString("\n")
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
           |${methodsReporting}
           |}"""

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

    val needReporting = needReportingBundle(technique, methods)
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

import ParameterType.ParameterTypeService
class DSCTechniqueWriter(
    basePath   : String
  , translater : InterpolatedValueCompiler
  , parameterTypeService : ParameterTypeService
) extends AgentSpecificTechniqueWriter{

  val genericParams =
    "-reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly"

  def computeTechniqueFilePath(technique : EditorTechnique) =
    s"techniques/${technique.category}/${technique.bundleName.value}/${technique.version.value}/technique.ps1"

  def writeAgentFiles(technique : EditorTechnique, methods : Map[BundleName, GenericMethod] ): IOResult[Seq[String]] = {

    def toDscFormat(parentBlocks: List[MethodBlock])(method : MethodElem) : PureResult[List[String]] = {
      method match {
        case call : MethodCall =>
          if (call.methodId.value.startsWith("_")) {
            Right(Nil)
          } else {
            val componentName = s"""-componentName "${call.component.replaceAll("\"", "`\"")}""""


            def canonifyCondition(methodCall: MethodCall) = {
               methodCall.condition.replaceAll("""(\$\{[^\}]*})""","""" + \$(Canonify-Class $1) + """")
            }

            def naReport(method : GenericMethod, expectedReportingValue : String) =
              s"""_rudder_common_report_na ${componentName} -componentKey ${expectedReportingValue} -message "Not applicable" ${genericParams}"""
            for {

              // First translate parameters to Dsc values
              params    <- ((call.parameters.toList).traverse {
                              case (id, arg) =>
                                translater.translateToAgent(arg, AgentType.Dsc) match {
                                  case Full(dscValue) =>
                                    parameterTypeService.translate(dscValue, methods.get(call.methodId).flatMap(_.parameters.find(_.id == id)).map(_.parameterType).getOrElse(ParameterType.StringParameter), AgentType.Dsc).map(dscValue => (id,dscValue))

                                  case eb : EmptyBox =>
                                    val fail = eb ?~! s"Error when translating parameter '${arg}' of technique of method ${call.methodId} of technique ${technique.name}"
                                    Left(IOError(fail.messageChain,None))
                                }
                           }).map(_.toMap)

              // Translate condition
              condition <- translater.translateToAgent(canonifyCondition(call), AgentType.Dsc) match {
                             case Full(c) => Right(c)
                             case eb : EmptyBox =>
                               val fail = eb ?~! s"Error when translating condition '${call.condition}' of technique of method ${call.methodId} of technique ${technique.name}"
                               Left(IOError(fail.messageChain,None))
                           }

              methodParams =
                ( for {
                  (id, arg) <- params
                } yield {
                s"""-${id.validDscName} ${arg}"""
                }).mkString(" ")

              effectiveCall =
                s"""$$local_classes = Merge-ClassContext $$local_classes $$(${call.methodId.validDscName} ${methodParams} ${componentName} ${genericParams}).get_item("classes")"""

              // Check if method exists
              method <- methods.get(call.methodId) match {
                          case Some(method) =>
                            Right(method)
                          case None =>
                            Left(MethodNotFound(s"Method '${call.methodId.value}' not found when writing dsc Technique '${technique.name}' methods calls", None))
                        }
              // Check if class parameter is correctly defined
              classParameter <- params.get(method.classParameter) match {
                                  case Some(classParameter) =>
                                    Right(classParameter)
                                  case None =>
                                    Left(MethodNotFound(s"Parameter '${method.classParameter.value}' for method '${method.id.value}' not found when writing dsc Technique '${technique.name}' methods calls",None))
                                }

            } yield {
              if (method.agentSupport.contains(AgentType.Dsc)) {
                if (condition == "any" ) {
                  s"  ${effectiveCall}" :: Nil
                } else {
                  s"""|  $$class = "${condition}"
                      |  if (Evaluate-Class $$class $$local_classes $$system_classes) {
                      |    ${effectiveCall}
                      |  } else {
                      |    ${naReport(method,classParameter)}
                      |  }""".stripMargin('|') :: Nil
                }
              } else {
                s"  ${naReport(method,classParameter)}" :: Nil
              }
           }
         }

      case block : MethodBlock =>
        block.calls.flatTraverse(toDscFormat(block :: parentBlocks))
      }
    }


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

      calls <- technique.methodCalls.toList.flatTraverse(toDscFormat(Nil)).toIO

      content =
        s"""|function ${technique.bundleName.validDscName} {
            |  [CmdletBinding()]
            |  param (
            |      [parameter(Mandatory=$$true)]
            |      [string]$$reportId,
            |      [parameter(Mandatory=$$true)]
            |      [string]$$techniqueName,${parameters}
            |      [switch]$$auditOnly
            |  )
            |
            |  $$local_classes = New-ClassContext
            |  $$resources_dir = $$PSScriptRoot + "\\resources"
            |
            |${calls.mkString("\n\n")}
            |
            |}""".stripMargin('|')

      path  <-  IOResult.effect(s"Could not find dsc Technique '${technique.name}' in path ${basePath}/${techniquePath}") (
                  Paths.get(s"${basePath}/${techniquePath}")
                )
      // Powershell files needs to have a BOM added at the beginning of all files when using UTF8 enoding
      // See https://docs.microsoft.com/en-us/windows/desktop/intl/using-byte-order-marks
      contentWithBom : List[Byte] =
        // Bom, three bytes: EF BB BF https://en.wikipedia.org/wiki/Byte_order_mark
        239.toByte :: 187.toByte :: 191.toByte  ::
        content.getBytes(StandardCharsets.UTF_8).toList

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

trait TechniqueArchiver {
  def deleteTechnique(techniqueName : String, techniqueVersion : String, category : String, modId: ModificationId, commiter:  EventActor, msg : String) : IOResult[Unit]
  def commitTechnique(technique : EditorTechnique, modId: ModificationId, commiter:  EventActor, msg : String) : IOResult[Unit]
}

class TechniqueArchiverImpl (
    override val gitRepo                   : GitRepositoryProvider
  , override val gitRootDirectory          : JFile
  , override val xmlPrettyPrinter          : RudderPrettyPrinter
  , override val relativePath              : String
  , override val gitModificationRepository : GitModificationRepository
  , personIdentservice                     : PersonIdentService
  , override val groupOwner                : String
) extends GitArchiverUtils with TechniqueArchiver {

  override val encoding : String = "UTF-8"

  def deleteTechnique(techniqueName : String, techniqueVersion : String, category : String,  modId: ModificationId, commiter:  EventActor, msg : String) : IOResult[Unit] = {
    (for {
      ident   <- personIdentservice.getPersonIdentOrDefault(commiter.name)
      rm      <- IOResult.effect(gitRepo.git.rm.addFilepattern(s"techniques/${category}/${techniqueName}/${techniqueVersion}").call())

      commit  <- IOResult.effect(gitRepo.git.commit.setCommitter(ident).setMessage(msg).call())
    } yield {
      s"techniques/${category}/${techniqueName}/${techniqueVersion}"
    }).chainError(s"error when deleting and committing Technique '${techniqueName}/${techniqueVersion}").unit
  }

  def commitTechnique(technique : EditorTechnique, modId: ModificationId, commiter:  EventActor, msg : String) : IOResult[Unit] = {

    val techniqueGitPath = s"techniques/${technique.category}/${technique.bundleName.value}/${technique.version.value}"
    val filesToAdd = (
      "metadata.xml" +:
      "rudder_reporting.cf" +:
      "technique.cf" +:
      "technique.ps1" +:
      "technique.json" +:
      "technique.rl" +:
      technique.ressources.collect {
      case ResourceFile(path, action) if action == ResourceFileState.New | action == ResourceFileState.Modified =>
        s"resources/${path}"
      }
    ).map(file =>  s"${techniqueGitPath}/$file")

    // appart resources, additionnal files to delete are for migration purpose.
    val filesToDelete =
      s"ncf/50_techniques/${technique.bundleName.value}" +:
      s"dsc/ncf/50_techniques/${technique.bundleName.value}" +:
      technique.ressources.collect {
        case ResourceFile(path, ResourceFileState.Deleted) =>
          s"${techniqueGitPath}/resources/${path}"
      }
    (for {
      ident   <- personIdentservice.getPersonIdentOrDefault(commiter.name)

      added <- ZIO.foreach(filesToAdd) { f =>
        IOResult.effect(gitRepo.git.add.addFilepattern(f).call())
      }
      removed <- ZIO.foreach(filesToDelete) { f =>
        IOResult.effect(gitRepo.git.rm.addFilepattern(f).call())
      }
      commit  <- IOResult.effect(gitRepo.git.commit.setCommitter(ident).setMessage(msg).call())
    } yield ()).chainError(s"error when committing Technique '${technique.name}/${technique.version}").unit
  }

}
