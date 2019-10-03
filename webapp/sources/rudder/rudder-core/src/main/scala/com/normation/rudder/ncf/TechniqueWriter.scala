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

import net.liftweb.common.Loggable
import java.nio.file.Paths
import java.nio.file.Files
import scala.xml.NodeSeq
import java.nio.charset.StandardCharsets
import com.normation.inventory.domain.AgentType
import net.liftweb.common.Box
import com.normation.rudder.repository.xml.GitArchiverUtils
import com.normation.cfclerk.services.GitRepositoryProvider
import java.io.File
import com.normation.rudder.repository.xml.RudderPrettyPrinter
import com.normation.rudder.repository.GitModificationRepository
import com.normation.eventlog.ModificationId
import net.liftweb.common.Full
import com.normation.eventlog.EventActor
import com.normation.rudder.services.user.PersonIdentService
import scala.xml.{ Node => XmlNode }
import net.liftweb.common.EmptyBox
import com.normation.cfclerk.services.UpdateTechniqueLibrary
import net.liftweb.common.Failure
import net.liftweb.common.Empty
import com.normation.rudder.services.policies.InterpolatedValueCompiler
import scala.language.implicitConversions

trait NcfError {
  def message : String
  def exception : Option[Throwable]
}

object ResultHelper {
  type Result[T] = Either[NcfError,T]

  def execute[T](f : => T )( errorCatcher: Throwable => NcfError ) : Result[T] = {
    try {
      Right(f)
    } catch {
      case e: Throwable => Left(errorCatcher(e))
    }
  }

  def sequence[T,U](seq : Seq[T])( f : T => Result[U]) : Result[Seq[U]] = {
    seq.foldLeft((Right(Seq()) : Result[Seq[U]])) {
      case (e @ Left(_), _) => e
      case (Right(res), value) =>f(value).map( res :+ _)
    }
  }

  implicit def resultToBox[T] (res : Result[T]) : Box[T] = {
    res match {
      case Right(r) => Full(r)
      case Left(e) => Failure(e.message,e.exception,Empty)
    }
  }
}

final case class IOError(message : String, exception : Option[Throwable]) extends NcfError
final case class TechniqueUpdateError(message : String, exception : Option[Throwable]) extends NcfError
final case class MethodNotFound(message : String, exception : Option[Throwable]) extends NcfError

class TechniqueWriter (
    archiver         : TechniqueArchiver
  , techLibUpdate    : UpdateTechniqueLibrary
  , translater       : InterpolatedValueCompiler
  , xmlPrettyPrinter : RudderPrettyPrinter
  , basePath         : String
) extends Loggable {

  import ResultHelper._
  private[this] var agentSpecific = new ClassicTechniqueWriter :: new DSCTechniqueWriter(basePath, translater) :: Nil

  def techniqueMetadataContent(technique : Technique, methods: Map[BundleName, GenericMethod]) : Result[XmlNode] = {

    def reportingValuePerMethod (component: String, calls :Seq[MethodCall]) : Result[Seq[XmlNode]] = {

      for {
        spec <- sequence(calls) { call: MethodCall =>
          for {
            method <- methods.get(call.methodId) match {
              case None => Left(MethodNotFound(s"Cannot find method ${call.methodId.value} when writing a method call of Technique '${technique.bundleName.value}'", None))
              case Some(m) => Right(m)
            }
            class_param <- call.parameters.get(method.classParameter) match {
              case None => Left(MethodNotFound(s"Cannot find call parameter of ${call.methodId.value} when writing a method call of Technique '${technique.bundleName.value}'", None))
              case Some(m) => Right(m)
            }

          } yield {
            <VALUE>{class_param}</VALUE>
          }

        }
      } yield {

        <SECTION component="true" multivalued="true" name={component}>
          <REPORTKEYS>
            {spec}
          </REPORTKEYS>
        </SECTION>

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
        <CONSTRAINT>
          <TYPE>textarea</TYPE>
          <MAYBEEMPTY>false</MAYBEEMPTY>
        </CONSTRAINT>
        <LONGDESCRIPTION></LONGDESCRIPTION>
      </INPUT>
    }
    // Regroup method calls from which we expect a reporting
    // We filter those starting by _, which are internal methods
    val expectedReportingMethodsWithValues =
      for {
        (component, methodCalls) <- technique.methodCalls.filterNot(_.methodId.value.startsWith("_")).groupBy(_.component).toList.sortBy(_._1)
      } yield {
        (component, methodCalls)
      }

    for {
      reportingSection     <- sequence(expectedReportingMethodsWithValues)((reportingValuePerMethod _).tupled)
      agentSpecificSection <- sequence(agentSpecific)(_.agentMetadata(technique, methods))
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

  def writeTechniqueAndUpdateLib(technique : Technique, methods: Map[BundleName, GenericMethod], modId : ModificationId, committer : EventActor) : Result[Seq[String]] = {
    for {
      agentFiles <- writeTechnique(technique,methods,modId,committer)
      libUpdate  <-
        techLibUpdate.update(modId, committer, Some(s"Update Technique library after creating files for ncf Technique ${technique.name}")) match {
          case Full(techniques) => Right(techniques)
          case eb: EmptyBox =>
            val fail = eb ?~! s"An error occured during technique update after files were created for ncf Technique ${technique.name}"
            Left(TechniqueUpdateError(fail.msg, fail.exception))
        }
     } yield {
      agentFiles
    }
  }
  // Write and commit all techniques files
  def writeTechnique(technique : Technique, methods: Map[BundleName, GenericMethod], modId : ModificationId, committer : EventActor) : Result[Seq[String]] = {
    for {
      agentFiles <- writeAgentFiles(technique, methods, modId, committer)
      metadata   <- writeMetadata(technique, methods, modId, committer)
    } yield {
      metadata +: agentFiles
    }
  }

  def writeAgentFiles(technique : Technique, methods: Map[BundleName, GenericMethod], modId : ModificationId, commiter : EventActor) : Result[Seq[String]] = {
    for {
      // Create/update agent files, filter None by flattenning to list
      files  <- sequence(agentSpecific)(_.writeAgentFile(technique, methods)).map(_.flatten)
      commit <- sequence(files)(archiver.commitFile(technique, _, modId, commiter , s"Commiting Technique '${technique.bundleName.value}' file for agent " ))
    } yield {
      files
    }
  }

  def writeMetadata(technique : Technique, methods: Map[BundleName, GenericMethod], modId : ModificationId, commiter : EventActor) : Result[String] = {

    val metadataPath = s"techniques/ncf_techniques/${technique.bundleName.value}/${technique.version.value}/metadata.xml"

    val path = s"${basePath}/${metadataPath}"
    for {
      content <- techniqueMetadataContent(technique, methods).map(n => xmlPrettyPrinter.format(n))
      file    <- execute {
                   val filePath = Paths.get(path)
                   if (!Files.exists(filePath)) {
                     Files.createDirectories(filePath.getParent)
                     Files.createFile(filePath)
                   }
                   Files.write(filePath, content.getBytes(StandardCharsets.UTF_8))
                 } {
                   case e =>
                     IOError(s"An error occured while creating metadata file for Technique '${technique.name}'",Some(e))
                 }
     commit   <- archiver.commitFile(technique, metadataPath, modId, commiter , s"Commiting Technique '${technique.bundleName.value}' metadata")

    } yield {
      metadataPath
    }
  }
}

trait AgentSpecificTechniqueWriter {

  import ResultHelper._
  def writeAgentFile( technique : Technique, methods : Map[BundleName, GenericMethod] ) : Result[Option[String]]

  def agentMetadata ( technique : Technique, methods : Map[BundleName, GenericMethod] ) : Result[NodeSeq]
}

class ClassicTechniqueWriter extends AgentSpecificTechniqueWriter {

  import ResultHelper._
  def writeAgentFile( technique : Technique, methods : Map[BundleName, GenericMethod] )  : Result[Option[String]] = Right(None)
  def agentMetadata ( technique : Technique, methods : Map[BundleName, GenericMethod] )  : Result[NodeSeq] = {
    // We need to add reporting bundle if there is a method call that does not support cfengine (agent support does not contains both cfe agent)
    val noAgentSupportReporting = technique.methodCalls.exists(  m =>
                         methods.get(m.methodId).exists( gm =>
                           ! (gm.agentSupport.contains(AgentType.CfeEnterprise) || (gm.agentSupport.contains(AgentType.CfeCommunity)))
                         )
                       )
    // We need to add a reporting bundle for this method to generate a na report for any method with a condition != any/cfengine (which ~= true
    val needReportingBundle = technique.methodCalls.exists(m => m.condition != "any" && m.condition != "cfengine-community" )
    val xml = <AGENT type="cfengine-community,cfengine-nova">
      <BUNDLES>
        <NAME>{technique.bundleName.value}</NAME>
        {if (noAgentSupportReporting || needReportingBundle) <NAME>{technique.bundleName.value}_rudder_reporting</NAME>}
      </BUNDLES>
      <FILES>
        <FILE name={s"RUDDER_CONFIGURATION_REPOSITORY/ncf/50_techniques/${technique.bundleName.value}/${technique.bundleName.value}.cf"}>
          <INCLUDED>true</INCLUDED>
        </FILE>
        { if (noAgentSupportReporting || needReportingBundle)
          <FILE name={s"RUDDER_CONFIGURATION_REPOSITORY/techniques/ncf_techniques/${technique.bundleName.value}/${technique.version.value}/rudder_reporting.cf"}>
            <INCLUDED>true</INCLUDED>
          </FILE>
        }
      </FILES>
    </AGENT>
    Right(xml)
  }

}

class DSCTechniqueWriter(
    basePath   : String
  , translater : InterpolatedValueCompiler
) extends AgentSpecificTechniqueWriter{

  import ResultHelper._
  val genericParams =
    "-reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly"

  def computeTechniqueFilePath(technique : Technique) =
    s"dsc/ncf/50_techniques/${technique.bundleName.value}/${technique.version.value}/${technique.bundleName.value}.ps1"

  def writeAgentFile(technique : Technique, methods : Map[BundleName, GenericMethod] ) = {

    def toDscFormat(call : MethodCall) : Result[String]= {

      val componentName = s"""-componentName "${call.component.replaceAll("\"", "`\"")}""""

      def naReport(method : GenericMethod, expectedReportingValue : String) =
        s"""_rudder_common_report_na ${componentName} -componentKey "${expectedReportingValue}" -message "Not applicable" ${genericParams}"""
      for {

        // First translate parameters to Dsc values
        params    <- ( sequence(call.parameters.toSeq) {
                        case (id, arg) =>
                          translater.translateToAgent(arg, AgentType.Dsc) match {
                            case Full(dscValue) => Right((id,dscValue))
                            case eb : EmptyBox =>
                              Left(IOError("",None))
                          }
                     }).map(_.toMap)

        // Translate condition
        condition <- translater.translateToAgent(call.condition, AgentType.Dsc) match {
                       case Full(c) => Right(c)
                       case eb : EmptyBox =>
                         Left(IOError("",None))
                     }

        methodParams =
          ( for {
            (id, arg) <- params
          } yield {
          s"""-${id.validDscName} "${arg.replaceAll("\"", "`\"")}""""
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
        classParameter <- params.get(method.classParameter).map(_.replaceAll("\"", "`\"")) match {
                            case Some(classParameter) =>
                              Right(classParameter)
                            case None =>
                              Left(MethodNotFound(s"Parameter '${method.classParameter.value}' for method '${method.id.value}' not found when writing dsc Technique '${technique.name}' methods calls",None))
                          }

      } yield {
       if (method.agentSupport.contains(AgentType.Dsc)) {
         if (condition == "any" ) {
           s"  ${effectiveCall}"
         } else{
           s"""|  $$class = "${condition}"
               |  if (Evaluate-Class $$class $$local_classes $$system_classes) {
               |    ${effectiveCall}
               |  } else {
               |    ${naReport(method,classParameter)}
               |  }""".stripMargin('|')
         }
       } else {
         s"  ${naReport(method,classParameter)}"
       }
      }
    }

    val filteredCalls = technique.methodCalls.filterNot(_.methodId.value.startsWith("_"))

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

      calls <- sequence(filteredCalls)(toDscFormat)

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
            |
            |${calls.mkString("\n\n")}
            |
            |}""".stripMargin('|')

      path  <-  execute (
                  Paths.get(s"${basePath}/${techniquePath}")
                ) ( e =>
                  IOError(s"Could not find dsc Technique '${technique.name}' in path ${basePath}/${techniquePath}", Some(e))
                )
      // Powershell files needs to have a BOM added at the beginning of all files when using UTF8 enoding
      // See https://docs.microsoft.com/en-us/windows/desktop/intl/using-byte-order-marks
      contentWithBom : List[Byte] =
        // Bom, three bytes: EF BB BF https://en.wikipedia.org/wiki/Byte_order_mark
        239.toByte :: 187.toByte :: 191.toByte  ::
        content.getBytes(StandardCharsets.UTF_8).toList
      files <-  execute {
                  Files.createDirectories(path.getParent)
                  Files.write(path, contentWithBom.toArray)
                } ( e =>
                  IOError(s"Could not write dsc Technique file '${technique.name}' in path ${basePath}/${techniquePath}",Some(e))
                )
    } yield {
      Some(techniquePath)
    }
  }

  def agentMetadata(technique : Technique, methods : Map[BundleName, GenericMethod] ) = {
    val xml = <AGENT type="dsc">
      <BUNDLES>
        <NAME>{technique.bundleName.validDscName}</NAME>
      </BUNDLES>
      <FILES>
        <FILE name={s"RUDDER_CONFIGURATION_REPOSITORY/${computeTechniqueFilePath(technique)}"}>
          <INCLUDED>true</INCLUDED>
        </FILE>
      </FILES>
    </AGENT>
    Right(xml)
  }

}

trait TechniqueArchiver {
  import ResultHelper._
  def commitFile(technique : Technique, gitPath : String, modId: ModificationId, commiter:  EventActor, msg : String) : Result[Unit]
}

class TechniqueArchiverImpl (
    override val gitRepo                   : GitRepositoryProvider
  , override val gitRootDirectory          : File
  , override val xmlPrettyPrinter          : RudderPrettyPrinter
  , override val relativePath              : String
  , override val gitModificationRepository : GitModificationRepository
  , personIdentservice : PersonIdentService
) extends
  Loggable with
  GitArchiverUtils with TechniqueArchiver{

  import ResultHelper._
  override val encoding : String = "UTF-8"

  def commitFile(technique : Technique, gitPath : String, modId: ModificationId, commiter:  EventActor, msg : String) : Result[Unit] = {
    (for {
      ident  <- personIdentservice.getPersonIdentOrDefault(commiter.name)
      commit <- commitAddFile(modId,ident, gitPath, msg)
    } yield {
      gitPath
    }) match {
      case Full(_) => Right(())
      case eb : EmptyBox =>
        val error = (eb ?~! s"error when commiting file ${gitPath} for Technique '${technique.name}")
        Left(IOError(error.msg,error.exception))
    }
  }

}
