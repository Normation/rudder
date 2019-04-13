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

import com.normation.eventlog.ModificationId
import com.normation.eventlog.EventActor
import net.liftweb.common.Full
import com.normation.cfclerk.services.UpdateTechniqueLibrary
import com.normation.rudder.repository.xml.RudderPrettyPrinter
import net.liftweb.common.Box
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.services.TechniquesLibraryUpdateType
import com.normation.cfclerk.services.TechniquesLibraryUpdateNotification
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.Version
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import org.specs2.mutable.Specification
import org.joda.time.DateTime
import org.specs2.matcher.ContentMatchers
import java.io.File
import net.liftweb.common.Loggable
import com.normation.rudder.services.policies.InterpolatedValueCompilerImpl

import com.normation.errors._
import scalaz.zio._
import scalaz.zio.syntax._
import com.normation.zio._

@RunWith(classOf[JUnitRunner])
class TestTechniqueWriter extends Specification with ContentMatchers with Loggable {
  sequential
  val basePath = "/tmp/test-technique-writer" + DateTime.now.toString()
  new File(basePath).mkdirs()

  val expectedPath = "src/test/resources/configuration-repository"
  object TestTechniqueArchiver extends TechniqueArchiver {
    def commitFile(technique : Technique, gitPath : String, modId: ModificationId, commiter:  EventActor, msg : String) : IOResult[Unit] = UIO.unit
  }

  object TestLibUpdater extends UpdateTechniqueLibrary {
    def update(modId: ModificationId, actor:EventActor, reason: Option[String]) : Box[Map[TechniqueName, TechniquesLibraryUpdateType]] = Full(Map())
    def registerCallback(callback:TechniquesLibraryUpdateNotification) : Unit = ()
  }

  val valueCompiler = new InterpolatedValueCompilerImpl
  val writer = new TechniqueWriter(TestTechniqueArchiver,TestLibUpdater,valueCompiler, new RudderPrettyPrinter(Int.MaxValue, 2), basePath)
  val dscWriter = new DSCTechniqueWriter(basePath, valueCompiler)

  val methods = ( GenericMethod(
      BundleName("package_install_version")
    , "Package install version"
    , MethodParameter(ParameterId("package_name"),"") :: MethodParameter(ParameterId("package_version"),"") :: Nil
    , ParameterId("package_name")
    , "package_install_version"
    , AgentType.CfeCommunity :: AgentType.CfeEnterprise :: AgentType.Dsc :: Nil
    , ""
  ) ::
  GenericMethod(
      BundleName("service_start")
    , "Service start"
    , MethodParameter(ParameterId("service_name"),"") :: Nil
    , ParameterId("service_name")
    , "service_start"
    , AgentType.CfeCommunity :: AgentType.CfeEnterprise :: AgentType.Dsc :: Nil
    , "Service start"
  ) ::
  GenericMethod(
      BundleName("package_install")
    , "Package install"
    , MethodParameter(ParameterId("package_name"),"") :: Nil
    , ParameterId("package_name")
    , "package_install"
    , AgentType.CfeCommunity :: AgentType.CfeEnterprise :: Nil
    , "Package install"
  ) ::
  GenericMethod(
      BundleName("command_execution")
    , "Command execution"
    , MethodParameter(ParameterId("command"),"") :: Nil
    , ParameterId("command")
    , "command_execution"
    , AgentType.CfeCommunity :: AgentType.CfeEnterprise :: AgentType.Dsc :: Nil
    , ""
  ) ::
  GenericMethod(
      BundleName("_logger")
    , "_logger"
    , MethodParameter(ParameterId("message"),"") :: MethodParameter(ParameterId("old_class_prefix"),"") :: Nil
    , ParameterId("message")
    , "_logger"
    , AgentType.CfeCommunity :: AgentType.CfeEnterprise :: Nil
    , ""
  ) ::
  Nil ).map(m => (m.id,m)).toMap

  val technique =
    Technique(
        BundleName("technique_by_Rudder")
      , "Test Technique created through Rudder API"
      , MethodCall(
            BundleName("package_install_version")
          , Map((ParameterId("package_name"),"${node.properties[apache_package_name]}"),(ParameterId("package_version"),"2.2.11"))
          , "any"
          , "Customized component"
        ) ::
        MethodCall(
            BundleName("command_execution")
          , Map((ParameterId("command"),"Write-Host \"testing special characters ` è &é 'à é \""))
          , "windows"
          , "Command execution"
        ) ::
        MethodCall(
            BundleName("service_start")
          , Map((ParameterId("service_name"),"${node.properties[apache_package_name]}"))
          , "package_install_version_${node.properties[apache_package_name]}_repaired"
          , "Customized component"
        ) ::
        MethodCall(
            BundleName("package_install")
          , Map((ParameterId("package_name"),"openssh-server"))
          , "redhat"
          , "Package install"
        ) ::
        MethodCall(
            BundleName("command_execution")
          , Map((ParameterId("command"),"/bin/echo \"testing special characters ` è &é 'à é \""))
          , "cfengine-community"
          , "Command execution"
        ) ::
        MethodCall(
            BundleName("_logger")
          , Map((ParameterId("message"),"NA"),(ParameterId("old_class_prefix"),"NA"))
          , "any"
          , "Not sure we should test it ..."
        ) :: Nil
      , new Version("1.0")
      , "This Technique exists only to see if Rudder creates Technique correctly."
      , TechniqueParameter(ParameterId("1aaacd71-c2d5-482c-bcff-5eee6f8da9c2"), ParameterId("technique_parameter")) :: Nil
    )

  val expectedMetadataPath = s"techniques/ncf_techniques/${technique.bundleName.value}/${technique.version.value}/metadata.xml"
  val dscTechniquePath     = s"dsc/ncf/50_techniques/${technique.bundleName.value}/${technique.version.value}/${technique.bundleName.value}.ps1"

  s"Preparing files for technique ${technique.name}" should {

    "Should write metadata file without problem" in {
      writer.writeMetadata(technique, methods, ModificationId("test"), EventActor("test")).either.runNow must beRight( expectedMetadataPath )
    }

    "Should generate expected metadata content for our technique" in {
      val expectedMetadataFile = new File(s"${expectedPath}/${expectedMetadataPath}")
      val resultMetadataFile = new File(s"${basePath}/${expectedMetadataPath}")
      resultMetadataFile must haveSameLinesAs (expectedMetadataFile)
    }

    "Should write dsc technique file without problem" in {
      dscWriter.writeAgentFile(technique, methods).either.runNow must beRight( beSome (dscTechniquePath ))
    }

    "Should generate expected dsc technique content for our technique" in {
      val expectedDscFile = new File(s"${expectedPath}/${dscTechniquePath}")
      val resultDscFile = new File(s"${basePath}/${dscTechniquePath}")
      resultDscFile must haveSameLinesAs (expectedDscFile)
    }

  }

  val technique_any =
    Technique(
        BundleName("technique_any")
      , "Test Technique created through Rudder API"
      , MethodCall(
            BundleName("package_install_version")
          , Map((ParameterId("package_name"),"${node.properties[apache_package_name]}"),(ParameterId("package_version"),"2.2.11"))
          , "any"
          , "Test component$&é)à\\'\""
        ) :: Nil
      , new Version("1.0")
      , "This Technique exists only to see if Rudder creates Technique correctly."
      , TechniqueParameter(ParameterId("package_version"),ParameterId("version")) :: Nil
    )

  val expectedMetadataPath_any = s"techniques/ncf_techniques/${technique_any.bundleName.value}/${technique_any.version.value}/metadata.xml"
  val dscTechniquePath_any     = s"dsc/ncf/50_techniques/${technique_any.bundleName.value}/${technique_any.version.value}/${technique_any.bundleName.value}.ps1"

  s"Preparing files for technique ${technique.bundleName.value}" should {

    "Should write metadata file without problem" in {
      writer.writeMetadata(technique_any, methods, ModificationId("test"), EventActor("test")).either.runNow must beRight( expectedMetadataPath_any )
    }

    "Should generate expected metadata content for our technique" in {
      val expectedMetadataFile = new File(s"${expectedPath}/${expectedMetadataPath_any}")
      val resultMetadataFile = new File(s"${basePath}/${expectedMetadataPath_any}")
      resultMetadataFile must haveSameLinesAs (expectedMetadataFile)
    }

    "Should write dsc technique file without problem" in {
      dscWriter.writeAgentFile(technique_any, methods).either.runNow must beRight( beSome (dscTechniquePath_any ))
    }

    "Should generate expected dsc technique content for our technique" in {
      val expectedDscFile = new File(s"${expectedPath}/${dscTechniquePath_any}")
      val resultDscFile = new File(s"${basePath}/${dscTechniquePath_any}")
      resultDscFile must haveSameLinesAs (expectedDscFile)
    }

  }

}
