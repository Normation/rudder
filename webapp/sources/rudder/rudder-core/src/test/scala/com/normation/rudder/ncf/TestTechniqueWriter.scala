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

import java.io.File
import java.io.InputStream

import com.normation.cfclerk.domain
import com.normation.cfclerk.domain.RootTechniqueCategory
import com.normation.cfclerk.domain.TechniqueCategory
import com.normation.cfclerk.domain.TechniqueCategoryId
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueResourceId
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.cfclerk.services.TechniquesInfo
import com.normation.cfclerk.services.TechniquesLibraryUpdateNotification
import com.normation.cfclerk.services.TechniquesLibraryUpdateType
import com.normation.cfclerk.services.UpdateTechniqueLibrary
import com.normation.errors._
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.Version
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.rudder.domain.policies.ActiveTechniqueCategory
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import com.normation.rudder.domain.policies.ActiveTechniqueId
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.workflows.ChangeRequest
import com.normation.rudder.ncf.ParameterType.PlugableParameterTypeService
import com.normation.rudder.repository.CategoryWithActiveTechniques
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.xml.RudderPrettyPrinter
import com.normation.rudder.services.policies.InterpolatedValueCompilerImpl
import com.normation.rudder.services.workflows.DirectiveChangeRequest
import com.normation.rudder.services.workflows.GlobalParamChangeRequest
import com.normation.rudder.services.workflows.NodeGroupChangeRequest
import com.normation.rudder.services.workflows.RuleChangeRequest
import com.normation.rudder.services.workflows.WorkflowLevelService
import com.normation.rudder.services.workflows.WorkflowService
import com.normation.zio._
import net.liftweb.common.Box
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.matcher.ContentMatchers
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import zio._

import scala.collection.SortedMap
import scala.collection.SortedSet

@RunWith(classOf[JUnitRunner])
class TestTechniqueWriter extends Specification with ContentMatchers with Loggable {
  sequential
  val basePath = "/tmp/test-technique-writer" + DateTime.now.toString()

  val dir = new File(basePath)
  dir.deleteOnExit()
  dir.mkdirs()

  val expectedPath = "src/test/resources/configuration-repository"
  object TestTechniqueArchiver extends TechniqueArchiver {
    def commitTechnique(technique : Technique, gitPath : Seq[String], modId: ModificationId, commiter:  EventActor, msg : String) : IOResult[Unit] = UIO.unit
    def deleteTechnique(techniqueName: String, techniqueVersion: String, category : String, modId: ModificationId, commiter: EventActor, msg: String): IOResult[Unit] = UIO.unit
  }

  object TestLibUpdater extends UpdateTechniqueLibrary {
    def update(modId: ModificationId, actor:EventActor, reason: Option[String]) : Box[Map[TechniqueName, TechniquesLibraryUpdateType]] = Full(Map())
    def registerCallback(callback:TechniquesLibraryUpdateNotification) : Unit = ()
  }

  // Not used in test for now
  def readDirectives : RoDirectiveRepository = new RoDirectiveRepository {

    def getFullDirectiveLibrary(): IOResult[FullActiveTechniqueCategory] = ???

    def getDirective(directiveId: DirectiveId): IOResult[Option[Directive]] = ???

    def getDirectiveWithContext(directiveId: DirectiveId): IOResult[Option[(domain.Technique, ActiveTechnique, Directive)]] = ???

    def getActiveTechniqueAndDirective(id: DirectiveId): IOResult[Option[(ActiveTechnique, Directive)]] = ???

    def getDirectives(activeTechniqueId: ActiveTechniqueId, includeSystem: Boolean): IOResult[Seq[Directive]] = ???

    def getActiveTechniqueByCategory(includeSystem: Boolean): IOResult[SortedMap[List[ActiveTechniqueCategoryId], CategoryWithActiveTechniques]] = ???

    def getActiveTechniqueByActiveTechnique(id: ActiveTechniqueId): IOResult[Option[ActiveTechnique]] = ???

    def getActiveTechnique(techniqueName: TechniqueName): IOResult[Option[ActiveTechnique]] = ???

    def activeTechniqueBreadCrump(id: ActiveTechniqueId): IOResult[List[ActiveTechniqueCategory]] = ???

    def getActiveTechniqueLibrary: IOResult[ActiveTechniqueCategory] = ???

    def getAllActiveTechniqueCategories(includeSystem: Boolean): IOResult[Seq[ActiveTechniqueCategory]] = ???

    def getActiveTechniqueCategory(id: ActiveTechniqueCategoryId): IOResult[Option[ActiveTechniqueCategory]] = ???

    def getParentActiveTechniqueCategory(id: ActiveTechniqueCategoryId): IOResult[ActiveTechniqueCategory] = ???

    def getParentsForActiveTechniqueCategory(id: ActiveTechniqueCategoryId): IOResult[List[ActiveTechniqueCategory]] = ???

    def getParentsForActiveTechnique(id: ActiveTechniqueId): IOResult[ActiveTechniqueCategory] = ???

    def containsDirective(id: ActiveTechniqueCategoryId): UIO[Boolean] = ???
  }
  def workflowLevelService: WorkflowLevelService = new WorkflowLevelService {
    def workflowLevelAllowsEnable: Boolean = ???

    def workflowEnabled: Boolean = ???

    def name: String = ???

    def getWorkflowService(): WorkflowService = ???

    def getForRule(actor: EventActor, change: RuleChangeRequest): Box[WorkflowService] = ???

    def getForDirective(actor: EventActor, change: DirectiveChangeRequest): Box[WorkflowService] = ???

    def getForNodeGroup(actor: EventActor, change: NodeGroupChangeRequest): Box[WorkflowService] = ???

    def getForGlobalParam(actor: EventActor, change: GlobalParamChangeRequest): Box[WorkflowService] = ???

    def getByDirective(id: DirectiveId, onlyPending: Boolean): Box[Vector[ChangeRequest]] = ???

    def getByNodeGroup(id: NodeGroupId, onlyPending: Boolean): Box[Vector[ChangeRequest]] = ???

    def getByRule(id: RuleId, onlyPending: Boolean): Box[Vector[ChangeRequest]] = ???
  }

  def techRepo : TechniqueRepository = new TechniqueRepository {
    override def getMetadataContent[T](techniqueId: TechniqueId)(useIt: Option[InputStream] => T): T = ???
    override def getTemplateContent[T](techniqueResourceId: TechniqueResourceId)(useIt: Option[InputStream] => T): T = ???
    override def getFileContent[T](techniqueResourceId: TechniqueResourceId)(useIt: Option[InputStream] => T): T = ???
    override def getTechniquesInfo(): TechniquesInfo = ???
    override def getAll(): Map[TechniqueId, domain.Technique] = ???
    override def get(techniqueId: TechniqueId): Option[domain.Technique] = ???
    override def getLastTechniqueByName(techniqueName: TechniqueName): Option[domain.Technique] = ???
    override def getByIds(techniqueIds: Seq[TechniqueId]): Seq[domain.Technique] = ???
    override def getTechniqueVersions(name: TechniqueName): SortedSet[TechniqueVersion] = ???
    override def getByName(name: TechniqueName): Map[TechniqueVersion, domain.Technique] = ???
    override def getTechniqueLibrary: RootTechniqueCategory = ???
    override def getTechniqueCategory(id: TechniqueCategoryId): IOResult[TechniqueCategory] = ???
    override def getParentTechniqueCategory_forTechnique(id: TechniqueId): IOResult[TechniqueCategory] = ???
    override def getAllCategories: Map[TechniqueCategoryId, TechniqueCategory] = ???
  }

  val valueCompiler = new InterpolatedValueCompilerImpl
  val parameterTypeService : PlugableParameterTypeService = new PlugableParameterTypeService
  val writer = new TechniqueWriter(TestTechniqueArchiver,TestLibUpdater,valueCompiler, readDirectives, techRepo, workflowLevelService, new RudderPrettyPrinter(Int.MaxValue, 2), basePath, parameterTypeService)
  val dscWriter = new DSCTechniqueWriter(basePath, valueCompiler, new ParameterType.PlugableParameterTypeService)
  val classicWriter = new ClassicTechniqueWriter(basePath, new ParameterType.PlugableParameterTypeService)

  import ParameterType._
  val defaultConstraint = Constraint.NonEmpty :: Constraint.NoWhiteSpace :: Constraint.MaxLength(16384) :: Nil
  val methods = ( GenericMethod(
      BundleName("package_install_version")
    , "Package install version"
    , MethodParameter(ParameterId("package_name"),"",
      defaultConstraint, StringParameter) ::
      MethodParameter(ParameterId("package_version"),"", defaultConstraint, StringParameter) ::
      Nil
    , ParameterId("package_name")
    , "package_install_version"
    , AgentType.CfeCommunity :: AgentType.CfeEnterprise :: AgentType.Dsc :: Nil
    , ""
  ) ::
  GenericMethod(
      BundleName("service_start")
    , "Service start"
    , MethodParameter(ParameterId("service_name"),"", defaultConstraint, StringParameter) :: Nil
    , ParameterId("service_name")
    , "service_start"
    , AgentType.CfeCommunity :: AgentType.CfeEnterprise :: AgentType.Dsc :: Nil
    , "Service start"
  ) ::
  GenericMethod(
      BundleName("package_install")
    , "Package install"
    , MethodParameter(ParameterId("package_name"),"", defaultConstraint, StringParameter) :: Nil
    , ParameterId("package_name")
    , "package_install"
    , AgentType.CfeCommunity :: AgentType.CfeEnterprise :: Nil
    , "Package install"
  ) ::
  GenericMethod(
      BundleName("command_execution")
    , "Command execution"
    , MethodParameter(ParameterId("command"),"", defaultConstraint, StringParameter) :: Nil
    , ParameterId("command")
    , "command_execution"
    , AgentType.CfeCommunity :: AgentType.CfeEnterprise :: AgentType.Dsc :: Nil
    , ""
  ) ::
  GenericMethod(
      BundleName("_logger")
    , "_logger"
    , MethodParameter(ParameterId("message"),"", defaultConstraint, StringParameter) ::
      MethodParameter(ParameterId("old_class_prefix"),"", defaultConstraint, StringParameter) :: Nil
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
      , "ncf_techniques"
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
          , Map((ParameterId("command"),"/bin/echo \"testing special characters ` è &é 'à é \"\\"))
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
      , Nil
    )

  val expectedMetadataPath = s"techniques/ncf_techniques/${technique.bundleName.value}/${technique.version.value}/metadata.xml"
  val dscTechniquePath     = s"techniques/ncf_techniques/${technique.bundleName.value}/${technique.version.value}/technique.ps1"
  val techniquePath = s"techniques/ncf_techniques/${technique.bundleName.value}/${technique.version.value}/technique.cf"
  val reportingPath = s"techniques/ncf_techniques/${technique.bundleName.value}/${technique.version.value}/rudder_reporting.cf"

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
      dscWriter.writeAgentFiles(technique, methods).either.runNow must beRight(Seq(dscTechniquePath)   )
    }

    "Should generate expected dsc technique content for our technique" in {
      val expectedDscFile = new File(s"${expectedPath}/${dscTechniquePath}")
      val resultDscFile = new File(s"${basePath}/${dscTechniquePath}")
      resultDscFile must haveSameLinesAs (expectedDscFile)
    }

    "Should write classic technique files without problem" in {
      classicWriter.writeAgentFiles(technique, methods).either.runNow must beRight(Seq(techniquePath, reportingPath)   )
    }

    "Should generate expected classic technique content for our technique" in {
      val expectedFile = new File(s"${expectedPath}/${techniquePath}")
      val resultFile = new File(s"${basePath}/${techniquePath}")
      resultFile must haveSameLinesAs (expectedFile)
    }

    "Should generate expected additional rudder reporting content for our technique" in {
      val expectedFile = new File(s"${expectedPath}/${reportingPath}")
      val resultFile = new File(s"${basePath}/${reportingPath}")
      resultFile must haveSameLinesAs (expectedFile)
    }

  }

  val technique_any =
    Technique(
        BundleName("technique_any")
      , "Test Technique created through Rudder API"
      , "ncf_techniques"
      , MethodCall(
            BundleName("package_install_version")
          , Map((ParameterId("package_name"),"${node.properties[apache_package_name]}"),(ParameterId("package_version"),"2.2.11"))
          , "any"
          , "Test component$&é)à\\'\""
        ) :: Nil
      , new Version("1.0")
      , "This Technique exists only to see if Rudder creates Technique correctly."
      , TechniqueParameter(ParameterId("package_version"),ParameterId("version")) :: Nil
      , Nil
    )

  val expectedMetadataPath_any = s"techniques/ncf_techniques/${technique_any.bundleName.value}/${technique_any.version.value}/metadata.xml"
  val dscTechniquePath_any     = s"techniques/ncf_techniques/${technique_any.bundleName.value}/${technique_any.version.value}/technique.ps1"
  val techniquePath_any = s"techniques/ncf_techniques/${technique_any.bundleName.value}/${technique_any.version.value}/technique.cf"
  val reportingPath_any = s"techniques/ncf_techniques/${technique_any.bundleName.value}/${technique_any.version.value}/rudder_reporting.cf"

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
      dscWriter.writeAgentFiles(technique_any, methods).either.runNow must beRight(Seq(dscTechniquePath_any))
    }

    "Should generate expected dsc technique content for our technique" in {
      val expectedDscFile = new File(s"${expectedPath}/${dscTechniquePath_any}")
      val resultDscFile = new File(s"${basePath}/${dscTechniquePath_any}")
      resultDscFile must haveSameLinesAs (expectedDscFile)
    }

    "Should write classic technique files without problem" in {
      classicWriter.writeAgentFiles(technique_any, methods).either.runNow must beRight(Seq(techniquePath_any)   )
    }

    "Should generate expected classic technique content for our technique" in {
      val expectedFile = new File(s"${expectedPath}/${techniquePath_any}")
      val resultFile = new File(s"${basePath}/${techniquePath_any}")
      resultFile must haveSameLinesAs (expectedFile)
    }

    "Should not generate expected additional rudder reporting content for our technique" in {
      val resultFile = new File(s"${basePath}/${reportingPath_any}")
      resultFile must not exist
    }
  }

  "Constraints should" should {
    "Correctly accept non whitespace text" in {
      val value1 = "Some text"
      val value2 =
        """Some
          |text""".stripMargin
      val value3 = "S"
      val value4 = "ééé ```"
      val value5 = "sdfsqdfsqfsdf sfhdskjhdfs jkhsdkfjhksqdhf"
      val value6 = ""

      Constraint.NoWhiteSpace.check(value1) must equalTo(Constraint.OK)
      Constraint.NoWhiteSpace.check(value2) must equalTo(Constraint.OK)
      Constraint.NoWhiteSpace.check(value3) must equalTo(Constraint.OK)
      Constraint.NoWhiteSpace.check(value4) must equalTo(Constraint.OK)
      Constraint.NoWhiteSpace.check(value5) must equalTo(Constraint.OK)
      Constraint.NoWhiteSpace.check(value6) must equalTo(Constraint.OK)
    }

    "Correctly refuse text starting or ending with withspace" in {
      val value1 = " Some text"
      val value2 =
        """ Some
          |text""".stripMargin
      val value3 = " "
      val value4 = "sdfsqdfsqfsdf sfhdskjhdfs jkhsdkfjhksqdhf "

      Constraint.NoWhiteSpace.check(value1) must haveClass[Constraint.NOK]
      Constraint.NoWhiteSpace.check(value2) must haveClass[Constraint.NOK]
      Constraint.NoWhiteSpace.check(value3) must haveClass[Constraint.NOK]
      Constraint.NoWhiteSpace.check(value4) must haveClass[Constraint.NOK]
    }
  }

}
