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
import com.normation.cfclerk.domain
import com.normation.cfclerk.domain.ReportingLogic
import com.normation.cfclerk.domain.RootTechniqueCategory
import com.normation.cfclerk.domain.TechniqueCategory
import com.normation.cfclerk.domain.TechniqueCategoryId
import com.normation.cfclerk.domain.TechniqueCategoryMetadata
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueResourceId
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.cfclerk.services.TechniquesInfo
import com.normation.cfclerk.services.TechniquesLibraryUpdateNotification
import com.normation.cfclerk.services.TechniquesLibraryUpdateType
import com.normation.cfclerk.services.UpdateTechniqueLibrary
import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.Version
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.rudder.domain.policies.ActiveTechniqueCategory
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import com.normation.rudder.domain.policies.ActiveTechniqueId
import com.normation.rudder.domain.policies.DeleteDirectiveDiff
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveSaveDiff
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.PolicyMode.Audit
import com.normation.rudder.domain.policies.PolicyMode.Enforce
import com.normation.rudder.domain.policies.PolicyTypes
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.workflows.ChangeRequest
import com.normation.rudder.hooks.CmdResult
import com.normation.rudder.ncf.ParameterType.PlugableParameterTypeService
import com.normation.rudder.ncf.TechniqueWriterImpl
import com.normation.rudder.repository.CategoryWithActiveTechniques
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.WoDirectiveRepository
import com.normation.rudder.repository.xml.TechniqueArchiver
import com.normation.rudder.services.nodes.PropertyEngineServiceImpl
import com.normation.rudder.services.policies.InterpolatedValueCompilerImpl
import com.normation.rudder.services.workflows.DirectiveChangeRequest
import com.normation.rudder.services.workflows.GlobalParamChangeRequest
import com.normation.rudder.services.workflows.NodeGroupChangeRequest
import com.normation.rudder.services.workflows.RuleChangeRequest
import com.normation.rudder.services.workflows.WorkflowLevelService
import com.normation.rudder.services.workflows.WorkflowService
import com.normation.zio.*
import java.io.File as JFile
import java.io.InputStream
import net.liftweb.common.Box
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.matcher.ContentMatchers
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.BeforeAfterAll
import scala.collection.SortedMap
import scala.collection.SortedSet
import zio.*
import zio.syntax.*

@RunWith(classOf[JUnitRunner])
class TestEditorTechniqueWriter extends Specification with ContentMatchers with Loggable with BeforeAfterAll {
  sequential
  lazy val basePath: String = "/tmp/test-technique-writer-" + DateTime.now.toString()

  override def beforeAll(): Unit = {
    new JFile(basePath).mkdirs()
  }

  override def afterAll(): Unit = {
    if (java.lang.System.getProperty("tests.clean.tmp") != "false") {
      FileUtils.deleteDirectory(new JFile(basePath))
    }
  }

  val expectedPath = "src/test/resources/configuration-repository"
  object TestTechniqueArchiver extends TechniqueArchiver {
    override def deleteTechnique(
        techniqueId: TechniqueId,
        categories:  Seq[String],
        modId:       ModificationId,
        committer:   EventActor,
        msg:         String
    ): IOResult[Unit] = ZIO.unit
    override def saveTechnique(
        techniqueId:     TechniqueId,
        categories:      Seq[String],
        resourcesStatus: Chunk[ResourceFile],
        modId:           ModificationId,
        committer:       EventActor,
        msg:             String
    ): IOResult[Unit] = ZIO.unit

    override def saveTechniqueCategory(
        categories: Seq[String],
        metadata:   TechniqueCategoryMetadata,
        modId:      ModificationId,
        committer:  EventActor,
        msg:        String
    ): IOResult[Unit] = ZIO.unit
  }

  object TestLibUpdater extends UpdateTechniqueLibrary {
    def update(
        modId:  ModificationId,
        actor:  EventActor,
        reason: Option[String]
    ): Box[Map[TechniqueName, TechniquesLibraryUpdateType]] = Full(Map())
    def registerCallback(callback: TechniquesLibraryUpdateNotification): Unit = ()
  }

  // Not used in test for now
  def readDirectives: RoDirectiveRepository = new RoDirectiveRepository {

    def getFullDirectiveLibrary(): IOResult[FullActiveTechniqueCategory] = ???

    def getDirective(directiveId: DirectiveUid): IOResult[Option[Directive]] = ???

    def getDirectiveWithContext(directiveId: DirectiveUid): IOResult[Option[(domain.Technique, ActiveTechnique, Directive)]] = ???

    def getActiveTechniqueAndDirective(id: DirectiveId): IOResult[Option[(ActiveTechnique, Directive)]] = ???

    def getDirectives(activeTechniqueId: ActiveTechniqueId, includeSystem: Boolean): IOResult[Seq[Directive]] = ???

    def getActiveTechniqueByCategory(
        includeSystem: Boolean
    ): IOResult[SortedMap[List[ActiveTechniqueCategoryId], CategoryWithActiveTechniques]] = ???

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

  def writeDirectives: WoDirectiveRepository = new WoDirectiveRepository {
    def saveDirective(
        inActiveTechniqueId: ActiveTechniqueId,
        directive:           Directive,
        modId:               ModificationId,
        actor:               EventActor,
        reason:              Option[String]
    ): IOResult[Option[DirectiveSaveDiff]] = ???
    def saveSystemDirective(
        inActiveTechniqueId: ActiveTechniqueId,
        directive:           Directive,
        modId:               ModificationId,
        actor:               EventActor,
        reason:              Option[String]
    ): IOResult[Option[DirectiveSaveDiff]] = ???
    def delete(
        id:     DirectiveUid,
        modId:  ModificationId,
        actor:  EventActor,
        reason: Option[String]
    ): IOResult[Option[DeleteDirectiveDiff]] = ???
    def addTechniqueInUserLibrary(
        categoryId:    ActiveTechniqueCategoryId,
        techniqueName: TechniqueName,
        versions:      Seq[TechniqueVersion],
        policyTypes:   PolicyTypes,
        modId:         ModificationId,
        actor:         EventActor,
        reason:        Option[String]
    ): IOResult[ActiveTechnique] = ???
    def move(
        id:            ActiveTechniqueId,
        newCategoryId: ActiveTechniqueCategoryId,
        modId:         ModificationId,
        actor:         EventActor,
        reason:        Option[String]
    ): IOResult[ActiveTechniqueId] = ???
    def changeStatus(
        id:     ActiveTechniqueId,
        status: Boolean,
        modId:  ModificationId,
        actor:  EventActor,
        reason: Option[String]
    ): IOResult[ActiveTechniqueId] = ???
    def setAcceptationDatetimes(
        id:        ActiveTechniqueId,
        datetimes: Map[TechniqueVersion, DateTime],
        modId:     ModificationId,
        actor:     EventActor,
        reason:    Option[String]
    ): IOResult[ActiveTechniqueId] = ???
    def deleteActiveTechnique(
        id:     ActiveTechniqueId,
        modId:  ModificationId,
        actor:  EventActor,
        reason: Option[String]
    ): IOResult[ActiveTechniqueId] = ???
    def addActiveTechniqueCategory(
        that:           ActiveTechniqueCategory,
        into:           ActiveTechniqueCategoryId,
        modificationId: ModificationId,
        actor:          EventActor,
        reason:         Option[String]
    ): IOResult[ActiveTechniqueCategory] = ???
    def saveActiveTechniqueCategory(
        category:       ActiveTechniqueCategory,
        modificationId: ModificationId,
        actor:          EventActor,
        reason:         Option[String]
    ): IOResult[ActiveTechniqueCategory] = ???
    def deleteCategory(
        id:             ActiveTechniqueCategoryId,
        modificationId: ModificationId,
        actor:          EventActor,
        reason:         Option[String],
        checkEmpty:     Boolean
    ): IOResult[ActiveTechniqueCategoryId] = ???
    def move(
        categoryId:     ActiveTechniqueCategoryId,
        intoParent:     ActiveTechniqueCategoryId,
        optionNewName:  Option[ActiveTechniqueCategoryId],
        modificationId: ModificationId,
        actor:          EventActor,
        reason:         Option[String]
    ): IOResult[ActiveTechniqueCategoryId] = ???
    override def deleteSystemDirective(
        id:     DirectiveUid,
        modId:  ModificationId,
        actor:  EventActor,
        reason: Option[String]
    ): IOResult[Option[DeleteDirectiveDiff]] = ???
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

    def getByDirective(id: DirectiveUid, onlyPending: Boolean): Box[Vector[ChangeRequest]] = ???

    def getByNodeGroup(id: NodeGroupId, onlyPending: Boolean): Box[Vector[ChangeRequest]] = ???

    def getByRule(id: RuleUid, onlyPending: Boolean): Box[Vector[ChangeRequest]] = ???
  }

  def techRepo: TechniqueRepository = new TechniqueRepository {
    override def getMetadataContent[T](techniqueId: TechniqueId)(useIt: Option[InputStream] => IOResult[T]): IOResult[T] = ???
    override def getTemplateContent[T](techniqueResourceId: TechniqueResourceId)(
        useIt: Option[InputStream] => IOResult[T]
    ): IOResult[T] = ???
    override def getFileContent[T](techniqueResourceId: TechniqueResourceId)(
        useIt: Option[InputStream] => IOResult[T]
    ): IOResult[T] = ???
    override def getTechniquesInfo(): TechniquesInfo = ???
    override def getAll(): Map[TechniqueId, domain.Technique] = ???
    override def get(techniqueId:                      TechniqueId):      Option[domain.Technique]                = ???
    override def getLastTechniqueByName(techniqueName: TechniqueName):    Option[domain.Technique]                = ???
    override def getByIds(techniqueIds:                Seq[TechniqueId]): Seq[domain.Technique]                   = ???
    override def getTechniqueVersions(name:            TechniqueName):    SortedSet[TechniqueVersion]             = ???
    override def getByName(name:                       TechniqueName):    Map[TechniqueVersion, domain.Technique] = ???
    override def getTechniqueLibrary: RootTechniqueCategory = ???
    override def getTechniqueCategory(id:                    TechniqueCategoryId): IOResult[TechniqueCategory] = ???
    override def getParentTechniqueCategory_forTechnique(id: TechniqueId):         IOResult[TechniqueCategory] = ???
    override def getAllCategories: Map[TechniqueCategoryId, TechniqueCategory] = ???
  }

  val propertyEngineService = new PropertyEngineServiceImpl(List.empty)
  val valueCompiler         = new InterpolatedValueCompilerImpl(propertyEngineService)
  val parameterTypeService: PlugableParameterTypeService = new PlugableParameterTypeService

  import ParameterType.*
  val defaultConstraint: List[Constraint.Constraint]    =
    Constraint.AllowEmpty(allow = false) :: Constraint.AllowWhiteSpace(allow = false) :: Constraint.MaxLength(16384) :: Nil
  val methods:           Map[BundleName, GenericMethod] = (GenericMethod(
    BundleName("package_install_version"),
    "Package install version",
    MethodParameter(ParameterId("package_name"), "", defaultConstraint, StringParameter) ::
    MethodParameter(ParameterId("package_version"), "", defaultConstraint, StringParameter) ::
    Nil,
    ParameterId("package_name"),
    "package_install_version",
    AgentType.CfeCommunity :: AgentType.CfeEnterprise :: AgentType.Dsc :: Nil,
    "",
    None,
    None,
    None,
    Seq()
  ) ::
    GenericMethod(
      BundleName("service_start"),
      "Service start",
      MethodParameter(ParameterId("service_name"), "", defaultConstraint, StringParameter) :: Nil,
      ParameterId("service_name"),
      "service_start",
      AgentType.CfeCommunity :: AgentType.CfeEnterprise :: AgentType.Dsc :: Nil,
      "Service start",
      None,
      None,
      None,
      Seq()
    ) ::
    GenericMethod(
      BundleName("package_install"),
      "Package install",
      MethodParameter(ParameterId("package_name"), "", defaultConstraint, StringParameter) :: Nil,
      ParameterId("package_name"),
      "package_install",
      AgentType.CfeCommunity :: AgentType.CfeEnterprise :: Nil,
      "Package install",
      None,
      None,
      None,
      Seq()
    ) ::
    GenericMethod(
      BundleName("command_execution"),
      "Command execution",
      MethodParameter(ParameterId("command"), "", defaultConstraint, StringParameter) :: Nil,
      ParameterId("command"),
      "command_execution",
      AgentType.CfeCommunity :: AgentType.CfeEnterprise :: AgentType.Dsc :: Nil,
      "",
      None,
      None,
      None,
      Seq()
    ) ::
    GenericMethod(
      BundleName("package_state_windows"),
      "Package state windows",
      MethodParameter(ParameterId("package_name"), "", defaultConstraint, StringParameter) :: Nil,
      ParameterId("package_name"),
      "package_state_windows",
      AgentType.Dsc :: Nil,
      "Package install",
      None,
      None,
      None,
      Seq()
    ) ::
    GenericMethod(
      BundleName("_logger"),
      "_logger",
      MethodParameter(ParameterId("message"), "", defaultConstraint, StringParameter) ::
      MethodParameter(ParameterId("old_class_prefix"), "", defaultConstraint, StringParameter) :: Nil,
      ParameterId("message"),
      "_logger",
      AgentType.CfeCommunity :: AgentType.CfeEnterprise :: Nil,
      "",
      None,
      None,
      None,
      Seq()
    ) ::
    GenericMethod(
      BundleName("package_state_windows"),
      "Package state windows",
      MethodParameter(ParameterId("package_name"), "", defaultConstraint, StringParameter) :: Nil,
      ParameterId("package_name"),
      "package_state_windows",
      AgentType.Dsc :: Nil,
      "Package install",
      None,
      None,
      None,
      Seq()
    ) ::
    Nil).map(m => (m.id, m)).toMap

  val technique: EditorTechnique = {
    EditorTechnique(
      BundleName("technique_by_Rudder"),
      new Version("1.0"),
      "Test Technique created through Rudder API",
      "ncf_techniques",
      MethodBlock(
        "id_method",
        "block component",
        ReportingLogic.WorstReportWeightedSum,
        "debian",
        MethodCall(
          BundleName("package_install_version"),
          "id1",
          Map(
            (ParameterId("package_name"), "${node.properties[apache_package_name]}"),
            (ParameterId("package_version"), "2.2.11")
          ),
          "any",
          "Customized component",
          disabledReporting = false,
          policyMode = None
        ) ::
        MethodCall(
          BundleName("command_execution"),
          "id2",
          Map((ParameterId("command"), "Write-Host \"testing special characters ` è &é 'à é \"")),
          "windows",
          "Command execution",
          disabledReporting = true,
          policyMode = Some(Enforce)
        ) :: Nil,
        Some(Audit)
      ) ::
      MethodCall(
        BundleName("service_start"),
        "id3",
        Map((ParameterId("service_name"), "${node.properties[apache_package_name]}")),
        "package_install_version_${node.properties[apache_package_name]}_repaired",
        "Customized component",
        disabledReporting = false,
        policyMode = Some(Audit)
      ) ::
      MethodCall(
        BundleName("package_install"),
        "id4",
        Map((ParameterId("package_name"), "openssh-server")),
        "redhat",
        "Package install",
        disabledReporting = false,
        policyMode = None
      ) ::
      MethodCall(
        BundleName("command_execution"),
        "id5",
        Map((ParameterId("command"), "/bin/echo \"testing special characters ` è &é 'à é \"\\")),
        "cfengine-community",
        "Command execution",
        disabledReporting = false,
        policyMode = Some(Audit)
      ) ::
      MethodCall(
        BundleName("package_state_windows"),
        "id6",
        Map((ParameterId("package_name"), "vim")),
        "dsc",
        "Package state windows",
        disabledReporting = false,
        policyMode = None
      ) ::
      MethodCall(
        BundleName("_logger"),
        "id7",
        Map((ParameterId("message"), "NA"), (ParameterId("old_class_prefix"), "NA")),
        "any",
        "Not sure we should test it ...",
        disabledReporting = false,
        policyMode = None
      ) :: Nil,
      "This Technique exists only to see if Rudder creates Technique correctly.",
      "",
      TechniqueParameter(
        ParameterId("1aaacd71-c2d5-482c-bcff-5eee6f8da9c2"),
        "technique_parameter",
        Some("technique parameter"),
        Some(" a long description, with line \n break within"),
        // we must ensure that it will lead to: [parameter(Mandatory=$false)]
        mayBeEmpty = true,
        constraints = None
      ) :: Nil,
      Nil,
      Map(),
      None
    )
  }

  val editorTechniqueReader: EditorTechniqueReader = new EditorTechniqueReader() {
    override def readTechniquesMetadataFile
        : IOResult[(List[EditorTechnique], Map[BundleName, GenericMethod], List[RudderError])] = {
      (List(technique), methods, Nil).succeed
    }

    override def getMethodsMetadata: IOResult[Map[BundleName, GenericMethod]] = methods.succeed

    override def updateMethodsMetadataFile: IOResult[CmdResult] = ???
  }

  val compiler = new RuddercTechniqueCompiler(
    new RuddercService {
      override def compile(techniqueDir: File, options: RuddercOptions): IOResult[RuddercResult] = {
        RuddercResult.Fail(42, Chunk.empty, "error:see implementation of test", "", "").succeed
      }
    },
    _.path,
    basePath
  )

  val yamlPath: String = s"techniques/ncf_techniques/${technique.id.value}/${technique.version.value}/technique.yml"

  s"Preparing files for technique ${technique.name}" should {
    "Should write yaml file without problem" in {
      TechniqueWriterImpl.writeYaml(technique)(basePath).either.runNow must beRight(yamlPath)
    }

    "Should generate expected yaml content for our technique" in {
      val expectedMetadataFile = new JFile(s"${expectedPath}/${yamlPath}")
      val resultMetadataFile   = new JFile(s"${basePath}/${yamlPath}")
      resultMetadataFile must haveSameLinesAs(expectedMetadataFile)
    }

  }

  val technique_any: EditorTechnique = {
    EditorTechnique(
      BundleName("technique_any"),
      new Version("1.0"),
      "Test Technique created through Rudder API",
      "ncf_techniques",
      MethodCall(
        BundleName("package_install_version"),
        "id",
        Map(
          (ParameterId("package_name"), "${node.properties[apache_package_name]}"),
          (ParameterId("package_version"), "2.2.11")
        ),
        "any",
        "Test component$&é)à\\'\"",
        disabledReporting = false,
        policyMode = Some(Audit)
      ) :: Nil,
      "This Technique exists only to see if Rudder creates Technique correctly.",
      "",
      TechniqueParameter(
        ParameterId("package_version"),
        "version",
        Some("package version"),
        Some("Package version to install"),
        mayBeEmpty = false,
        constraints = None
      ) :: Nil,
      Nil,
      Map(),
      None
    )
  }

  val techniquePath_yaml: String =
    s"techniques/ncf_techniques/${technique_any.id.value}/${technique_any.version.value}/technique.yml"

  s"Preparing files for technique ${technique.id.value}" should {

    "Should write yaml file without problem" in {
      TechniqueWriterImpl.writeYaml(technique_any)(basePath).either.runNow must beRight(techniquePath_yaml)
    }

    "Should generate expected yaml content for our technique" in {
      val expectedMetadataFile = new JFile(s"${expectedPath}/${techniquePath_yaml}")
      val resultMetadataFile   = new JFile(s"${basePath}/${techniquePath_yaml}")
      resultMetadataFile must haveSameLinesAs(expectedMetadataFile)
    }
  }

  val technique_var_cond: EditorTechnique = {
    EditorTechnique(
      BundleName("testing_variables_in_conditions"),
      new Version("1.0"),
      "Testing variables in conditions",
      "ncf_techniques",
      MethodCall(
        BundleName("command_execution"),
        "c0f1c227-0b8c-4219-ac3d-3c30fb4870ad",
        Map(
          (ParameterId("command"), "{return 1}")
        ),
        "${my_custom_condition}",
        "Command execution",
        disabledReporting = false,
        policyMode = None
      ) :: Nil,
      "",
      "",
      TechniqueParameter(
        ParameterId("40e3a5ab-0812-4a60-96f3-251be8cedf43"),
        "my_custom_condition",
        Some("my custom condition"),
        None,
        mayBeEmpty = false,
        constraints = None
      ) :: Nil,
      Nil,
      Map(),
      None
    )
  }

  val techniquePath_var_cond_yaml: String =
    s"${technique_var_cond.id.value}/${technique_var_cond.version.value}/technique.yml"

  s"Preparing files for technique ${technique.id.value}" should {
    "Should write metadata file without problem" in {
      TechniqueWriterImpl.writeYaml(technique_var_cond)(basePath).either.runNow must beRight(
        s"techniques/ncf_techniques/${techniquePath_var_cond_yaml}"
      )
    }
  }
}
