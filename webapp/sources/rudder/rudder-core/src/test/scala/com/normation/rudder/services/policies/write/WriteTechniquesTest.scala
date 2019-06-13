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

package com.normation.rudder.services.policies.write

import org.specs2.runner.JUnitRunner
import org.junit.runner.RunWith
import com.normation.BoxSpecMatcher
import com.normation.cfclerk.domain.TechniqueResourceId
import com.normation.cfclerk.domain.TechniqueTemplate
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.parameters.ParameterName
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.PolicyModeOverrides
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.repository.xml.LicenseRepositoryXML
import com.normation.rudder.services.policies.NodeConfigData
import com.normation.rudder.services.policies.NodeConfigData.root
import com.normation.rudder.services.policies.NodeConfigData.rootNodeConfig
import com.normation.rudder.services.policies.TestNodeConfiguration
import com.normation.rudder.services.policies.nodeconfig.NodeConfigurationLoggerImpl
import com.normation.templates.FillTemplatesService
import java.io.File

import net.liftweb.common.Loggable
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.joda.time.DateTime
import org.specs2.io.FileLinesContent
import org.specs2.matcher.ContentMatchers
import org.specs2.mutable.Specification
import org.specs2.specification.AfterAll
import org.specs2.text.LinesContent
import com.normation.rudder.services.policies.ParameterForConfiguration
import com.normation.rudder.services.policies.Policy
import java.nio.charset.StandardCharsets

import com.normation.rudder.services.policies.MergePolicyService
import com.normation.rudder.services.policies.BoundPolicyDraft
import com.normation.rudder.services.policies.Parallelism
import monix.eval.TaskSemaphore
import monix.execution.ExecutionModel
import monix.execution.Scheduler


/**
 * Details of tests executed in each instances of
 * the test.
 * To see values for gitRoot, ptLib, etc, see at the end
 * of that file.
 */
object TestSystemData {
  //make tests more similar than default rudder install
  val hookIgnore = """.swp, ~, .bak,
 .cfnew   , .cfsaved  , .cfedited, .cfdisabled, .cfmoved,
 .dpkg-old, .dpkg-dist, .dpkg-new, .dpkg-tmp,
 .disable , .disabled , _disable , _disabled,
 .ucf-old , .ucf-dist , .ucf-new ,
 .rpmnew  , .rpmsave  , .rpmorig""".split(",").map( _.trim).toList

  //////////// init ////////////
  val data = new TestNodeConfiguration()
  import data._

  val licenseRepo = new LicenseRepositoryXML("we_don_t_have_license")
  val logNodeConfig = new NodeConfigurationLoggerImpl(abstractRoot + "/lognodes")

  lazy val agentRegister = new AgentRegister()
  lazy val writeAllAgentSpecificFiles = new WriteAllAgentSpecificFiles(agentRegister)

  val prepareTemplateVariable = new PrepareTemplateVariablesImpl(
      techniqueRepository
    , systemVariableServiceSpec
    , new BuildBundleSequence(systemVariableServiceSpec, writeAllAgentSpecificFiles)
    , agentRegister
  )

  /*
   * We parametrize the output of file writing with a sub-directory name,
   * so that we can keep each write in it's own directory for debug.
   */
  def getPromiseWritter(label: String) = {

    //where the "/var/rudder/share" file is for tests:
    val SHARE = abstractRoot/s"share-${label}"

    val rootGeneratedPromisesDir = SHARE/"root"

    val pathComputer = new PathComputerImpl(
        SHARE.getParent + "/"
      , SHARE.getName
      , abstractRoot.getAbsolutePath + "/backup"
      , rootGeneratedPromisesDir.getAbsolutePath // community will go under our share
      , "/" // we don't want to use entreprise agent root path
    )

    val promiseWritter = new PolicyWriterServiceImpl(
        techniqueRepository
      , pathComputer
      , logNodeConfig
      , prepareTemplateVariable
      , new FillTemplatesService()
      , writeAllAgentSpecificFiles
      , "/we-don-t-want-hooks-here"
      , hookIgnore
    )

    (rootGeneratedPromisesDir, promiseWritter)
  }

  //////////// end init ////////////

  //an utility class for filtering file lines given a regex,
  //used in the file content matcher
  case class RegexFileContent(regex: List[String]) extends LinesContent[File] {
    val patterns = regex.map(_.r.pattern)

    override def lines(f: File): Seq[String] = {
      FileLinesContent.lines(f).filter { line => !patterns.exists { _.matcher(line).matches() } }
    }

    override def name(f: File) = FileLinesContent.name(f)
  }


  //////////////

  // Allows override in policy mode, but default to audit
  val globalPolicyMode = GlobalPolicyMode(PolicyMode.Audit, PolicyModeOverrides.Always)

  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // actual tests
  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def getSystemVars(nodeInfo: NodeInfo, allNodeInfos: Map[NodeId, NodeInfo], allGroups: FullNodeGroupCategory) = {
    systemVariableService.getSystemVariables(
                         nodeInfo, allNodeInfos, allGroups, noLicense
                       , globalSystemVariables, globalAgentRun, globalComplianceMode
                     ).openOrThrowException("I should get system variable in tests")
  }

  def policies(nodeInfo: NodeInfo, drafts: List[BoundPolicyDraft]): List[Policy] = {
    MergePolicyService.buildPolicy(nodeInfo, globalPolicyMode, drafts).getOrElse(throw new RuntimeException("We must be able to build policies from draft in tests!"))
  }

  /// For root, we are using the same system variable and base root node config
  // the root node configuration
  val baseRootDrafts = List(common(root.id, allNodesInfo_rootOnly), serverRole, distributePolicy, inventoryAll)
  val baseRootNodeConfig = rootNodeConfig.copy(
      policies    = policies(rootNodeConfig.nodeInfo, baseRootDrafts)
    , nodeContext = getSystemVars(root, allNodesInfo_rootOnly, groupLib)
    , parameters  = Set(ParameterForConfiguration(ParameterName("rudder_file_edit_header"), "### Managed by Rudder, edit with care ###"))
  )

  val cfeNodeConfig = NodeConfigData.node1NodeConfig.copy(
      nodeInfo   = cfeNode
    , parameters = Set(ParameterForConfiguration(ParameterName("rudder_file_edit_header"), "### Managed by Rudder, edit with care ###"))
  )


  // A global list of files to ignore because variable content (like timestamp)
  def filterGeneratedFile(f: File): Boolean = {
    f.getName match {
        case "rudder_promises_generated" | "rudder-promises-generated" => false
        case _                                                         => true
    }
  }

  //write a config

}

trait TechniquesTest extends Specification with Loggable with BoxSpecMatcher with ContentMatchers with AfterAll {

  import TestSystemData._
  import data._

   /*
   * put regex for line you don't want to be compared for difference
   */
  def ignoreSomeLinesMatcher(regex: List[String]): LinesPairComparisonMatcher[File, File] = {
    LinesPairComparisonMatcher[File, File]()(RegexFileContent(regex), RegexFileContent(regex))
  }

  //////////// set-up auto test cleaning ////////////
  override def afterAll(): Unit = {
    if(System.getProperty("tests.clean.tmp") != "false") {
      logger.info("Deleting directory " + data.abstractRoot.getAbsolutePath)
      FileUtils.deleteDirectory(abstractRoot)
    }
  }

  //////////// end set-up ////////////

  //utility to assert the content of a resource equals some string
  def assertResourceContent(id: TechniqueResourceId, isTemplate: Boolean, expectedContent: String) = {
    val ext = if(isTemplate) Some(TechniqueTemplate.templateExtension) else None
    reader.getResourceContent(id, ext) {
        case None     => ko("Can not open an InputStream for " + id.toString)
        case Some(is) => IOUtils.toString(is, StandardCharsets.UTF_8) === expectedContent
      }
  }

  def compareWith(path: File, expectedPath: String, ignoreRegex: List[String] = Nil) = {
    /*
     * And compare them with expected, modulo the configId and the name
     * of the (temp) directory where we wrote them
     */
    path must haveSameFilesAs(EXPECTED_SHARE/expectedPath)
      .withFilter ( filterGeneratedFile _ )
      .withMatcher(ignoreSomeLinesMatcher(
           """.*rudder_node_config_id" string => .*"""
        :: """.*string => "/.*/configuration-repository.*"""
        :: """.*/test-rudder-config-repo-.*""" //config repos path with timestamp
        :: ignoreRegex
      ))
  }

}

@RunWith(classOf[JUnitRunner])
class WriteSystemTechniquesTest extends TechniquesTest{
  import TestSystemData._
  import data._

  implicit val parallelism = Parallelism(1, Scheduler.io(executionModel = ExecutionModel.AlwaysAsyncExecution), TaskSemaphore(maxParallelism = 1))

  sequential

  "The test configuration-repository" should {
    "exists, and have a techniques/system sub-dir" in {
      val promiseFile = configurationRepositoryRoot/"techniques/system/common/1.0/promises.st"
      promiseFile.exists === true
    }
  }

  "A root node, with no node connected" should {
    def writeNodeConfigWithUserDirectives(promiseWritter: PolicyWriterService, userDrafts: BoundPolicyDraft*) = {
      val rnc = baseRootNodeConfig.copy(
          policies = policies(baseRootNodeConfig.nodeInfo, baseRootDrafts ++ userDrafts)
          //testing escape of "
        , parameters = baseRootNodeConfig.parameters + ParameterForConfiguration(ParameterName("ntpserver"), """pool."ntp".org""")
     )

      // Actually write the promise files for the root node
      promiseWritter.writeTemplate(root.id, Set(root.id), Map(root.id -> rnc), Map(root.id -> NodeConfigId("root-cfg-id")), Map(), globalPolicyMode, DateTime.now, parallelism)
    }

    "correctly write the expected promises files with defauls installation" in {
      val (rootPath, writter) = getPromiseWritter("root-default")
      (writeNodeConfigWithUserDirectives(writter) mustFull) and
      compareWith(rootPath, "root-default-install")
    }

    "correctly write the expected promises files when 2 directives configured" in {
      val (rootPath, writter) = getPromiseWritter("root-2-directives")
      (writeNodeConfigWithUserDirectives(writter, clock, rpm) mustFull) and
      compareWith(rootPath, "root-with-two-directives",
           """.*rudder_common_report\("ntpConfiguration".*@@.*"""  //clock reports
        :: """.*add:default:==:.*"""                               //rpm reports
        :: Nil
      )
    }

    "correctly write the expected promises files with a multi-policy configured, skipping fileTemplate3 from bundle order" in {
      val (rootPath, writter) = getPromiseWritter("root-1-multipolicy")
      (writeNodeConfigWithUserDirectives(writter, fileTemplate1, fileTemplate2, fileTemplate3) mustFull) and
      compareWith(rootPath, "root-with-one-multipolicy",
           """.*rudder_common_report\("ntpConfiguration".*@@.*"""  //clock reports
        :: """.*add:default:==:.*"""                               //rpm reports
        :: Nil
      )
    }
  }

  "rudder-group.st template" should {

    def getRootNodeConfig(groupLib: FullNodeGroupCategory) = {
      // system variables for root
      val systemVariables = systemVariableService.getSystemVariables(
          root, allNodesInfo_rootOnly, groupLib, noLicense
        , globalSystemVariables, globalAgentRun, globalComplianceMode
      ).openOrThrowException("I should get system variable in tests")

      // the root node configuration
      rootNodeConfig.copy(
          policies    = policies(rootNodeConfig.nodeInfo, List(common(root.id, allNodesInfo_rootOnly), serverRole, distributePolicy, inventoryAll))
        , nodeContext = systemVariables
        , parameters  = Set(ParameterForConfiguration(ParameterName("rudder_file_edit_header"), "### Managed by Rudder, edit with care ###"))
      )
    }

    "correctly write nothing (no quotes) when no groups" in {
      val (rootPath, writter) = getPromiseWritter("group-1")

      // Actually write the promise files for the root node
      writter.writeTemplate(
          root.id
        , Set(root.id)
        , Map(root.id -> getRootNodeConfig(emptyGroupLib))
        , Map(root.id -> NodeConfigId("root-cfg-id"))
        , Map()
        , globalPolicyMode
        , DateTime.now, parallelism
      ).openOrThrowException("Can not write template!")

      rootPath/"common/1.0/rudder-groups.cf" must haveSameLinesAs(EXPECTED_SHARE/"test-rudder-groups/no-group.cf")
    }

    "correctly write the classes and by_uuid array when groups" in {

      // make a group lib without "all" target so that it's actually different than the full one,
      // so that we don't have false positive due to bad directory cleaning
      val groupLib2 = groupLib.copy(targetInfos = groupLib.targetInfos.take(groupLib.targetInfos.size - 1))
      val rnc = getRootNodeConfig(groupLib2)

      // Actually write the promise files for the root node
      val (rootPath, writter) = getPromiseWritter("group-2")

      writter.writeTemplate(root.id, Set(root.id), Map(root.id -> rnc), Map(root.id -> NodeConfigId("root-cfg-id")), Map(), globalPolicyMode, DateTime.now, parallelism).openOrThrowException("Can not write template!")

      rootPath/"common/1.0/rudder-groups.cf" must haveSameLinesAs(EXPECTED_SHARE/"test-rudder-groups/some-groups.cf")
    }
  }

  "A CFEngine node, with two directives" should {

    val rnc = rootNodeConfig.copy(
        policies    = policies(rootNodeConfig.nodeInfo, List(common(root.id, allNodesInfo_cfeNode), serverRole, distributePolicy, inventoryAll))
      , nodeContext = getSystemVars(root, allNodesInfo_cfeNode, groupLib)
      , parameters  = Set(ParameterForConfiguration(ParameterName("rudder_file_edit_header"), "### Managed by Rudder, edit with care ###"))
    )

    val p = policies(cfeNodeConfig.nodeInfo, List(common(cfeNode.id, allNodesInfo_cfeNode), inventoryAll, pkg, ncf1))
    val cfeNC = cfeNodeConfig.copy(
        nodeInfo    = cfeNode
      , policies    = p
      , nodeContext = getSystemVars(cfeNode, allNodesInfo_cfeNode, groupLib)
      , runHooks    = MergePolicyService.mergeRunHooks(p.filter( ! _.technique.isSystem), None, globalPolicyMode)
    )

    "correctly get the expected policy files" in {
      val (rootPath, writter) = getPromiseWritter("cfe-node")
      // Actually write the promise files for the root node
      val written = writter.writeTemplate(
            root.id
          , Set(root.id, cfeNode.id)
          , Map(root.id -> rnc, cfeNode.id -> cfeNC)
          , Map(root.id -> NodeConfigId("root-cfg-id"), cfeNode.id -> NodeConfigId("cfe-node-cfg-id"))
          , Map(), globalPolicyMode, DateTime.now, parallelism
      )

      (written mustFull) and
      compareWith(rootPath.getParentFile/cfeNode.id.value, "node-cfe-with-two-directives",
           """.*rudder_common_report\("ntpConfiguration".*@@.*"""  //clock reports
        :: """.*add:default:==:.*"""                               //rpm reports
        :: Nil
      )
    }
  }

  "We must ensure the override semantic of generic-variable-definition" should {

    val rnc = rootNodeConfig.copy(
        policies    = policies(rootNodeConfig.nodeInfo, List(common(root.id, allNodesInfo_cfeNode), serverRole, distributePolicy, inventoryAll))
      , nodeContext = getSystemVars(root, allNodesInfo_cfeNode, groupLib)
      , parameters  = Set(ParameterForConfiguration(ParameterName("rudder_file_edit_header"), "### Managed by Rudder, edit with care ###"))
    )

    val cfeNC = cfeNodeConfig.copy(
        nodeInfo    = cfeNode
      , policies    = policies(cfeNodeConfig.nodeInfo, List(common(cfeNode.id, allNodesInfo_cfeNode), inventoryAll, gvd1, gvd2))
      , nodeContext = getSystemVars(cfeNode, allNodesInfo_cfeNode, groupLib)
    )

    "correctly get the expected policy files" in {
      val (rootPath, writter) = getPromiseWritter("cfe-node-gen-var-def")
      // Actually write the promise files for the root node
      val writen = writter.writeTemplate(
            root.id
          , Set(root.id, cfeNode.id)
          , Map(root.id -> rnc, cfeNode.id -> cfeNC)
          , Map(root.id -> NodeConfigId("root-cfg-id"), cfeNode.id -> NodeConfigId("cfe-node-cfg-id"))
          , Map(), globalPolicyMode, DateTime.now, parallelism
      )

      (writen mustFull) and
      compareWith(rootPath.getParentFile/cfeNode.id.value, "node-gen-var-def-override", Nil)
    }
  }


  "A CFEngine node, with 500 directives based on the same technique" should {

    val techniqueList: Seq[BoundPolicyDraft] = (1 to 500).map(copyGirFileDirectives(_)).toList

    // create the 500 directives files
    createCopyGitFileDirectories("node-cfe-with-500-directives",  (1 to 500))

    val rnc = rootNodeConfig.copy(
      policies    = policies(rootNodeConfig.nodeInfo, List(common(root.id, allNodesInfo_cfeNode), serverRole, distributePolicy, inventoryAll) ++ techniqueList)
      , nodeContext = getSystemVars(root, allNodesInfo_cfeNode, groupLib)
      , parameters  = Set(ParameterForConfiguration(ParameterName("rudder_file_edit_header"), "### Managed by Rudder, edit with care ###"))
    )


    val p = policies(cfeNodeConfig.nodeInfo, List(common(cfeNode.id, allNodesInfo_cfeNode), inventoryAll) ++ techniqueList)
    val cfeNC = cfeNodeConfig.copy(
        nodeInfo    = cfeNode
      , policies    = p
      , nodeContext = getSystemVars(cfeNode, allNodesInfo_cfeNode, groupLib)
      , runHooks    = MergePolicyService.mergeRunHooks(p.filter( ! _.technique.isSystem), None, globalPolicyMode)
    )

    "correctly get the expected policy files" in {
      val (rootPath, writter) = getPromiseWritter("cfe-node-500")
      // Actually write the promise files for the root node
      val writen = writter.writeTemplate(
        root.id
        , Set(root.id, cfeNode.id)
        , Map(root.id -> rnc, cfeNode.id -> cfeNC)
        , Map(root.id -> NodeConfigId("root-cfg-id-500"), cfeNode.id -> NodeConfigId("cfe-node-cfg-id-500"))
        , Map(), globalPolicyMode, DateTime.now, parallelism
      )

      (writen mustFull) and
        compareWith(rootPath.getParentFile/cfeNode.id.value, "node-cfe-with-500-directives", Nil)
    }
  }

}
