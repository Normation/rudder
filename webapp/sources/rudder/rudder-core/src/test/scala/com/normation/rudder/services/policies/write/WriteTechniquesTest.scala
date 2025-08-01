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

package com.normation.rudder.services.policies.write

import com.normation.BoxMatchers
import com.normation.BoxSpecMatcher
import com.normation.GitVersion.Revision
import com.normation.cfclerk.domain.TechniqueResourceId
import com.normation.cfclerk.domain.TechniqueResourceIdByName
import com.normation.cfclerk.domain.TechniqueResourceIdByPath
import com.normation.cfclerk.domain.TechniqueTemplate
import com.normation.cfclerk.domain.Variable
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.logger.NodeConfigurationLoggerImpl
import com.normation.rudder.domain.logger.PolicyGenerationLogger
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.PolicyModeOverrides
import com.normation.rudder.domain.properties.GenericProperty.StringToConfigValue
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.domain.properties.Visibility.Hidden
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.services.policies.BoundPolicyDraft
import com.normation.rudder.services.policies.MergePolicyService
import com.normation.rudder.services.policies.NodeConfigData
import com.normation.rudder.services.policies.NodeConfigData.factRoot
import com.normation.rudder.services.policies.NodeConfigData.root
import com.normation.rudder.services.policies.NodeConfigData.rootNodeConfig
import com.normation.rudder.services.policies.NodeConfiguration
import com.normation.rudder.services.policies.ParameterForConfiguration
import com.normation.rudder.services.policies.Policy
import com.normation.rudder.services.policies.TestNodeConfiguration
import com.normation.rudder.services.policies.write.PolicyWriterServiceImpl.filepaths
import com.normation.templates.FillTemplatesService
import com.normation.zio.*
import com.softwaremill.quicklens.ModifyPimp
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.io.FileLinesContent
import org.specs2.matcher.ContentMatchers
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.AfterAll
import org.specs2.text.LinesContent
import zio.Chunk
import zio.syntax.*

/**
 * Details of tests executed in each instances of
 * the test.
 * To see values for gitRoot, ptLib, etc, see at the end
 * of that file.
 */
class TestSystemData {
  // make tests more similar than default rudder install
  val hookIgnore: List[String] = """.swp, ~, .bak,
 .cfnew   , .cfsaved  , .cfedited, .cfdisabled, .cfmoved,
 .dpkg-old, .dpkg-dist, .dpkg-new, .dpkg-tmp,
 .disable , .disabled , _disable , _disabled,
 .ucf-old , .ucf-dist , .ucf-new ,
 .rpmnew  , .rpmsave  , .rpmorig""".split(",").map(_.trim).toList

  //////////// init ////////////
  val data = new TestNodeConfiguration()
  import data.*

  val logNodeConfig = new NodeConfigurationLoggerImpl(abstractRoot.getPath + "/lognodes")

  lazy val agentRegister              = new AgentRegister()
  lazy val writeAllAgentSpecificFiles = new WriteAllAgentSpecificFiles(agentRegister)

  val prepareTemplateVariable = new PrepareTemplateVariablesImpl(
    techniqueRepository,
    systemVariableServiceSpec,
    new BuildBundleSequence(systemVariableServiceSpec, writeAllAgentSpecificFiles),
    agentRegister
  )

  /*
   * We parametrize the output of file writing with a sub-directory name,
   * so that we can keep each write in it's own directory for debug.
   */
  def getPromiseWriter(label: String): (File, PolicyWriterServiceImpl) = {

    // where the "/var/rudder/share" file is for tests:
    val SHARE = abstractRoot / s"share-${label}"

    val rootGeneratedPromisesDir = SHARE / "root"

    val pathComputer = new PathComputerImpl(
      SHARE.getParent + "/",
      SHARE.getName,
      Some(abstractRoot.getAbsolutePath + "/backup"),
      rootGeneratedPromisesDir.getAbsolutePath
    )

    val promiseWriter = new PolicyWriterServiceImpl(
      techniqueRepository,
      pathComputer,
      logNodeConfig,
      prepareTemplateVariable,
      new FillTemplatesService(),
      writeAllAgentSpecificFiles,
      "/we-don-t-want-hooks-here",
      hookIgnore,
      StandardCharsets.UTF_8,
      None
    )

    (rootGeneratedPromisesDir, promiseWriter)
  }

  //////////// end init ////////////

  // Allows override in policy mode, but default to audit
  val globalPolicyMode: GlobalPolicyMode = GlobalPolicyMode(PolicyMode.Audit, PolicyModeOverrides.Always)

  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // actual tests
  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def getSystemVars(
      nodeInfo:     CoreNodeFact,
      allNodeInfos: Map[NodeId, CoreNodeFact],
      allGroups:    FullNodeGroupCategory
  ): Map[String, Variable] = {
    systemVariableService
      .getSystemVariables(
        nodeInfo,
        allNodeInfos,
        allGroups.getTarget(nodeInfo).map(_._2).toList,
        globalSystemVariables,
        globalAgentRun,
        globalComplianceMode
      )
      .openOrThrowException("I should get system variable in tests")
  }

  def policies(nodeInfo: CoreNodeFact, drafts: List[BoundPolicyDraft]): List[Policy] = {
    MergePolicyService
      .buildPolicy(nodeInfo, globalPolicyMode, drafts) match {
      case Full(l) => l
      case eb: EmptyBox =>
        throw new RuntimeException(
          s"We must be able to build policies from draft in tests! details ${(eb ?~! "error when getting policy").messageChain}"
        )
    }
  }

  /// For root, we are using the same system variable and base root node config
  // the root node configuration
  val baseRootDrafts:                                 List[BoundPolicyDraft] = List(common(root.id, allNodesInfo_rootOnly)) ++ allRootPolicies
  def baseRootNodeConfig(props: Chunk[NodeProperty]): NodeConfiguration      = {
    val fr = factRoot.modify(_.properties).setTo(props)
    rootNodeConfig.copy(
      nodeInfo = fr,
      policies = policies(fr, baseRootDrafts),
      nodeContext = getSystemVars(fr, Map(fr.id -> fr), groupLib),
      parameters = Set(ParameterForConfiguration("rudder_file_edit_header", "### Managed by Rudder, edit with care ###"))
    )
  }

  val cfeNodeConfig: NodeConfiguration = NodeConfigData.node1NodeConfig.copy(
    nodeInfo = factCfe,
    parameters = Set(ParameterForConfiguration("rudder_file_edit_header", "### Managed by Rudder, edit with care ###"))
  )

  // A global list of files to ignore because variable content (like timestamp)
  // We must also ignore symlink, because they are copied as plain file by FileUtils, and that make unwanted errors
  def filterGeneratedFile(f: File): Boolean = {
    f.getName match {
      case "rudder_promises_generated" | "rudder-promises-generated" => false
      case "policy-server.pem"                                       => false
      case _                                                         => true
    }
  }

  // write a config

}

//an utility class for filtering file lines given a regex,
//used in the file content matcher
final private case class RegexFileContent(regex: List[String]) extends LinesContent[File] {
  val patterns: List[Pattern] = regex.map(_.r.pattern)

  override def lines(f: File): Seq[String] = {
    FileLinesContent.lines(f).filter(line => !patterns.exists(_.matcher(line).matches()))
  }

  override def name(f: File): String = FileLinesContent.name(f)
}

trait TechniquesTest extends Specification with Loggable with BoxSpecMatcher with ContentMatchers with AfterAll with BoxMatchers {

  val testSystemData = new TestSystemData()
  import testSystemData.*
  import testSystemData.data.*

  /*
   * put regex for line you don't want to be compared for difference
   */
  def ignoreSomeLinesMatcher(regex: List[String]): LinesPairComparisonMatcher[File, File] = {
    LinesPairComparisonMatcher[File, File]()(RegexFileContent(regex), RegexFileContent(regex))
  }

  //////////// set-up auto test cleaning ////////////
  override def afterAll(): Unit = {
    if (System.getProperty("tests.clean.tmp") != "false") {
      logger.info("Deleting directory " + data.abstractRoot.getAbsolutePath)
      FileUtils.deleteDirectory(abstractRoot)
    }
  }

  //////////// end set-up ////////////

  // utility to assert the content of a resource equals some string
  def assertResourceContent(id: TechniqueResourceId, isTemplate: Boolean, expectedContent: String): MatchResult[Any] = {
    val ext = if (isTemplate) Some(TechniqueTemplate.templateExtension) else None
    reader
      .getResourceContent(id, ext) {
        case None     => ko("Can not open an InputStream for " + id.displayPath).succeed
        case Some(is) => (IOUtils.toString(is, StandardCharsets.UTF_8) === expectedContent).succeed
      }
      .runNow
  }

  def compareWith(path: File, expectedPath: String, ignoreRegex: List[String] = Nil): MatchResult[File] = {
    /*
     * And compare them with expected, modulo the configId and the name
     * of the (temp) directory where we wrote them
     */
    path must haveSameFilesAs(EXPECTED_SHARE / expectedPath)
      .withFilter(filterGeneratedFile _)
      .withMatcher(
        ignoreSomeLinesMatcher(
          """.*rudder_node_config_id" string => .*"""
          :: """.*string => "/.*/configuration-repository.*"""
          :: """.*/test-rudder-config-repo-.*""" // config repos path with timestamp
          :: ignoreRegex
        )
      )
  }

}

@RunWith(classOf[JUnitRunner])
class WriteSystemTechniquesTest extends TechniquesTest {
  import testSystemData.*
  import testSystemData.data.*

  val parallelism: Int = Integer.max(1, java.lang.Runtime.getRuntime.availableProcessors() / 2)

  // uncomment to have timing information
  org.slf4j.LoggerFactory
    .getLogger("policy.generation")
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.INFO)
  // org.slf4j.LoggerFactory.getLogger("policy.generation.timing").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.TRACE)

  PolicyGenerationLogger.debug(s"Max parallelism: ${parallelism}")

  sequential

  "The test configuration-repository" should {
    "exists, and have a techniques/system sub-dir" in {
      val promiseFile = configurationRepositoryRoot / "techniques/system/common/1.0/promises.st"
      promiseFile.exists === true
    }
  }

  "A root node, with no node connected and an hidden property" should {
    def writeNodeConfigWithUserDirectives(
        promiseWriter: PolicyWriterService,
        props:         List[NodeProperty],
        userDrafts:    BoundPolicyDraft*
    ) = {
      val cfg = baseRootNodeConfig(Chunk(NodeProperty("hidden", "hiddenValue".toConfigValue, None, None).withVisibility(Hidden)))
      val rnc = cfg.copy(
        policies = policies(cfg.nodeInfo, baseRootDrafts ++ userDrafts), // testing escape of "

        parameters = cfg.parameters + ParameterForConfiguration("ntpserver", """pool."ntp".org""")
      )

      // Actually write the promise files for the root node
      promiseWriter.writeTemplate(
        root.id,
        Set(root.id),
        Map(root.id -> rnc),
        Map(root.id -> rnc.nodeInfo),
        Map(root.id -> NodeConfigId("root-cfg-id")),
        globalPolicyMode,
        DateTime.now,
        parallelism
      )
    }

    "correctly write the expected policies files with default installation" in {
      val (rootPath, writer) = getPromiseWriter("root-default-install")
      (writeNodeConfigWithUserDirectives(writer, Nil) must beFull) and
      compareWith(rootPath, "root-default-install")
    }

    "correctly write the expected policies files with default installation but `.new` files exists" in {

      def addCrap(path: String): Unit = {
        val f = better.files.File(path)
        f.parent.createDirectories()
        f.append("some text that should be overwritten during generation")
          .append(
            // this need to be longer than at least one file, else it's overwritten enterly without exposing the pb
            """
              |"common-root","audit","merged","false","common","1.0","true","Common"
              |"root-distributePolicy","audit","merged","false","distributePolicy","1.0","true","Distribute Policy"
              |"inventory-all","audit","merged","false","inventory","1.0","true","Inventory"
              |"server-roles-directive","audit","merged","false","server-roles","1.0","true","Server Roles"
              |""".stripMargin
          )
          .appendLines("some more crap")
      }

      val (rootPath, writer) = getPromiseWriter("root-default-install-crap")

      // add garbage in rootPath/root.new/promises.cf (for a template), rudder.json & rudder-directives.csv (b/c special),
      // /inventory/1.0/test-inventory.pl (pure file).

      val newPolicies = new File(rootPath.getParentFile, "root.new")
      newPolicies.mkdirs()

      List(
        "promises.cf",
        filepaths.SYSTEM_VARIABLE_JSON,
        filepaths.DIRECTIVE_RUN_CSV,
        filepaths.ROOT_SERVER_CERT,
        filepaths.POLICY_SERVER_CERT
      ).foreach(s => addCrap(newPolicies.getAbsolutePath + "/" + s))

      val inventory = new File(newPolicies, "inventory/1.0")
      inventory.mkdirs()
      addCrap(inventory.getAbsolutePath + "/test-inventory.pl")

      (writeNodeConfigWithUserDirectives(writer, Nil) must beFull) and
      compareWith(rootPath, "root-default-install")
    }

    "correctly write the expected policies files when 2 directives configured" in {
      val (rootPath, writer) = getPromiseWriter("root-with-two-directives")
      (writeNodeConfigWithUserDirectives(writer, Nil, clock, rpm) must beFull) and
      compareWith(
        rootPath,
        "root-with-two-directives",
        """.*rudder_common_report\("ntpConfiguration".*@@.*""" // clock reports
        :: """.*add:default:==:.*"""                           // rpm reports
        :: Nil
      )
    }

    "correctly write the expected policies files with a multi-policy configured, skipping fileTemplate3 from bundle order" in {
      val (rootPath, writer) = getPromiseWriter("root-with-one-multipolicy")
      (writeNodeConfigWithUserDirectives(writer, Nil, fileTemplate1, fileTemplate2, fileTemplate3) must beFull) and
      compareWith(
        rootPath,
        "root-with-one-multipolicy",
        """.*rudder_common_report\("ntpConfiguration".*@@.*""" // clock reports
        :: """.*add:default:==:.*"""                           // rpm reports
        :: Nil
      )
    }

  }

  "rudder-group.st template" should {

    def getRootNodeConfig(groupLib: FullNodeGroupCategory) = {
      // system variables for root
      val systemVariables = systemVariableService
        .getSystemVariables(
          factRoot,
          allNodeFacts_rootOnly,
          groupLib.getTarget(factRoot).map(_._2).toList,
          globalSystemVariables,
          globalAgentRun,
          globalComplianceMode
        )
        .openOrThrowException("I should get system variable in tests")

      // the root node configuration
      rootNodeConfig.copy(
        policies = policies(rootNodeConfig.nodeInfo, List(common(root.id, allNodesInfo_rootOnly)) ++ allRootPolicies),
        nodeContext = systemVariables,
        parameters = Set(ParameterForConfiguration("rudder_file_edit_header", "### Managed by Rudder, edit with care ###"))
      )
    }

    "correctly write nothing (no quotes) when no groups" in {
      val (rootPath, writer) = getPromiseWriter("group-1")

      // Actually write the promise files for the root node
      writer
        .writeTemplate(
          root.id,
          Set(root.id),
          Map(root.id -> getRootNodeConfig(emptyGroupLib)),
          Map(root.id -> getRootNodeConfig(emptyGroupLib).nodeInfo),
          Map(root.id -> NodeConfigId("root-cfg-id")),
          globalPolicyMode,
          DateTime.now,
          parallelism
        )
        .openOrThrowException("Can not write template!")

      rootPath / "common/1.0/rudder-groups.cf" must haveSameLinesAs(EXPECTED_SHARE / "test-rudder-groups/no-group.cf")
    }

    "correctly write the classes and by_uuid array when groups" in {

      // make a group lib without "all" target so that it's actually different than the full one,
      // so that we don't have false positive due to bad directory cleaning
      val groupLib2 = groupLib.copy(targetInfos = groupLib.targetInfos.take(groupLib.targetInfos.size - 1))
      val rnc       = getRootNodeConfig(groupLib2)

      // Actually write the promise files for the root node
      val (rootPath, writer) = getPromiseWriter("group-2")

      writer
        .writeTemplate(
          root.id,
          Set(root.id),
          Map(root.id -> rnc),
          Map(root.id -> rnc.nodeInfo),
          Map(root.id -> NodeConfigId("root-cfg-id")),
          globalPolicyMode,
          DateTime.now,
          parallelism
        )
        .openOrThrowException("Can not write template!")

      rootPath / "common/1.0/rudder-groups.cf" must haveSameLinesAs(EXPECTED_SHARE / "test-rudder-groups/some-groups.cf")
    }
  }

  "A CFEngine node, with two directives" should {

    val rnc = rootNodeConfig.copy(
      policies = policies(rootNodeConfig.nodeInfo, List(common(root.id, allNodesInfo_cfeNode)) ++ allRootPolicies),
      nodeContext = getSystemVars(factRoot, allNodeFacts_cfeNode, groupLib),
      parameters = Set(ParameterForConfiguration("rudder_file_edit_header", "### Managed by Rudder, edit with care ###"))
    )

    val p     = policies(cfeNodeConfig.nodeInfo, List(common(cfeNode.id, allNodesInfo_cfeNode), inventoryAll, pkg, ncf1))
    val cfeNC = cfeNodeConfig.copy(
      nodeInfo = factCfe,
      policies = p,
      nodeContext = getSystemVars(factCfe, allNodeFacts_cfeNode, groupLib),
      runHooks = MergePolicyService.mergeRunHooks(p.filter(!_.technique.policyTypes.isSystem), None, globalPolicyMode)
    )

    "correctly get the expected policy files" in {
      val (rootPath, writer) = getPromiseWriter("node-cfe-with-two-directives")
      // Actually write the promise files for the root node
      val written            = writer.writeTemplate(
        root.id,
        Set(root.id, cfeNode.id),
        Map(root.id -> rnc, cfeNode.id                         -> cfeNC),
        Map(root.id -> rnc.nodeInfo, cfeNode.id                -> cfeNC.nodeInfo),
        Map(root.id -> NodeConfigId("root-cfg-id"), cfeNode.id -> NodeConfigId("cfe-node-cfg-id")),
        globalPolicyMode,
        DateTime.now,
        parallelism
      )

      (written must beFull) and
      compareWith(
        rootPath.getParentFile / cfeNode.id.value,
        "node-cfe-with-two-directives",
        """.*rudder_common_report\("ntpConfiguration".*@@.*""" // clock reports
        :: """.*add:default:==:.*"""                           // rpm reports
        :: Nil
      )
    }
  }

  "We must ensure the override semantic of generic-variable-definition" should {

    val rnc = rootNodeConfig.copy(
      policies = policies(rootNodeConfig.nodeInfo, List(common(root.id, allNodesInfo_cfeNode)) ++ allRootPolicies),
      nodeContext = getSystemVars(factRoot, allNodeFacts_cfeNode, groupLib),
      parameters = Set(ParameterForConfiguration("rudder_file_edit_header", "### Managed by Rudder, edit with care ###"))
    )

    val cfeNC = cfeNodeConfig.copy(
      nodeInfo = factCfe,
      policies = policies(cfeNodeConfig.nodeInfo, List(common(cfeNode.id, allNodesInfo_cfeNode), inventoryAll, gvd1, gvd2)),
      nodeContext = getSystemVars(factCfe, allNodeFacts_cfeNode, groupLib)
    )

    "correctly get the expected policy files" in {
      val (rootPath, writer) = getPromiseWriter("node-gen-var-def-override")
      // Actually write the promise files for the root node
      val written            = writer.writeTemplate(
        root.id,
        Set(root.id, cfeNode.id),
        Map(root.id -> rnc, cfeNode.id                         -> cfeNC),
        Map(root.id -> rnc.nodeInfo, cfeNode.id                -> cfeNC.nodeInfo),
        Map(root.id -> NodeConfigId("root-cfg-id"), cfeNode.id -> NodeConfigId("cfe-node-cfg-id")),
        globalPolicyMode,
        DateTime.now,
        parallelism
      )

      (written must beFull) and
      compareWith(rootPath.getParentFile / cfeNode.id.value, "node-gen-var-def-override", Nil)
    }
  }
}

@RunWith(classOf[JUnitRunner])
class WriteSystemTechniques500Test extends TechniquesTest {
  import testSystemData.*
  import testSystemData.data.*

  val parallelism: Int = Integer.max(1, java.lang.Runtime.getRuntime.availableProcessors() / 2)

  // uncomment to have timing information
//  org.slf4j.LoggerFactory.getLogger("policy.generation").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.DEBUG)
//  org.slf4j.LoggerFactory.getLogger("policy.generation.timing").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.TRACE)

  PolicyGenerationLogger.debug(s"Max parallelism: ${parallelism}")

  sequential

  "We must ensure that boolean system value is correctly interpreted as a boolean" should {

    // we check that technique test_18205 only contains the part that should be written when sys variable is false

    def forceBooleanToFalse(systemVars: Map[String, Variable]) = {
      systemVars("DENYBADCLOCKS").copyWithSavedValue("false") match {
        case Right(variable) => systemVars + ("DENYBADCLOCKS" -> variable)
        case _               => systemVars
      }
    }

    val rnc = rootNodeConfig.copy(
      policies =
        policies(rootNodeConfig.nodeInfo, List(common(root.id, allNodesInfo_cfeNode)) ++ allRootPolicies ++ List(test18205)),
      nodeContext = forceBooleanToFalse(getSystemVars(factRoot, allNodeFacts_cfeNode, groupLib)),
      parameters = Set(ParameterForConfiguration("rudder_file_edit_header", "### Managed by Rudder, edit with care ###"))
    )

    "correctly get the expected policy files" in {
      val (rootPath, writer) = getPromiseWriter("root-sys-var-false")
      // Actually write the promise files for the root node
      val written            = writer.writeTemplate(
        root.id,
        Set(root.id, cfeNode.id),
        Map(root.id -> rnc),
        Map(root.id -> rnc.nodeInfo),
        Map(root.id -> NodeConfigId("root-cfg-id"), cfeNode.id -> NodeConfigId("cfe-node-sys-bool-false-cfg-id")),
        globalPolicyMode,
        DateTime.now,
        parallelism
      )

      (written must beFull) and
      compareWith(rootPath.getParentFile / root.id.value, "root-sys-var-false", Nil)
    }
  }

  "A CFEngine node, with 500 directives based on the same technique" should {

    val techniqueList: Seq[BoundPolicyDraft] = (1 to 500).map(copyGitFileDirectives(_)).toList

    // create the 500 directives files
    createCopyGitFileDirectories("node-cfe-with-500-directives", (1 to 500))

    val rnc = rootNodeConfig.copy(
      policies =
        policies(rootNodeConfig.nodeInfo, List(common(root.id, allNodesInfo_cfeNode)) ++ allRootPolicies ++ techniqueList),
      nodeContext = getSystemVars(factRoot, allNodeFacts_cfeNode, groupLib),
      parameters = Set(ParameterForConfiguration("rudder_file_edit_header", "### Managed by Rudder, edit with care ###"))
    )

    val p     = policies(cfeNodeConfig.nodeInfo, List(common(cfeNode.id, allNodesInfo_cfeNode), inventoryAll) ++ techniqueList)
    val cfeNC = cfeNodeConfig.copy(
      nodeInfo = factCfe,
      policies = p,
      nodeContext = getSystemVars(factCfe, allNodeFacts_cfeNode, groupLib),
      runHooks = MergePolicyService.mergeRunHooks(p.filter(!_.technique.policyTypes.isSystem), None, globalPolicyMode)
    )

    "correctly get the expected policy files" in {
      val (rootPath, writer) = getPromiseWriter("node-cfe-with-500-directives")
      // Actually write the promise files for the root node
      val written            = writer.writeTemplate(
        root.id,
        Set(root.id, cfeNode.id),
        Map(root.id -> rnc, cfeNode.id                         -> cfeNC),
        Map(root.id -> rnc.nodeInfo, cfeNode.id                -> cfeNC.nodeInfo),
        Map(root.id -> NodeConfigId("root-cfg-id"), cfeNode.id -> NodeConfigId("cfe-node-cfg-id-500")),
        globalPolicyMode,
        DateTime.now,
        parallelism
      )

      (written must beFull) and
      compareWith(rootPath.getParentFile / cfeNode.id.value, "node-cfe-with-500-directives", Nil)
    }
  }

}

@RunWith(classOf[JUnitRunner])
class WriteSystemTechniqueWithRevisionTest extends TechniquesTest {
  import testSystemData.*
  import testSystemData.data.*

  val parallelism: Int               = Integer.max(1, java.lang.Runtime.getRuntime.availableProcessors() / 2)
  val rnc:         NodeConfiguration = rootNodeConfig.copy(
    policies = policies(rootNodeConfig.nodeInfo, List(common(root.id, allNodesInfo_cfeNode)) ++ allRootPolicies),
    nodeContext = getSystemVars(factRoot, allNodeFacts_cfeNode, groupLib),
    parameters = Set(ParameterForConfiguration("rudder_file_edit_header", "### Managed by Rudder, edit with care ###"))
  )

  val cfeNC: NodeConfiguration = cfeNodeConfig.copy(
    nodeInfo = factCfe,
    policies = policies(cfeNodeConfig.nodeInfo, List(common(cfeNode.id, allNodesInfo_cfeNode), inventoryAll, gvd1, gvd2)),
    nodeContext = getSystemVars(factCfe, allNodeFacts_cfeNode, groupLib)
  )

  // uncomment to have timing information
//  org.slf4j.LoggerFactory.getLogger("policy.generation").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.TRACE)
//  org.slf4j.LoggerFactory.getLogger("policy.generation.timing").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.TRACE)

  PolicyGenerationLogger.debug(s"Max parallelism: ${parallelism}")

  sequential

  /*
   * this test modify some things in techinques stored in config-repo and check that we do have a different output
   * with different revision (ie that we don't have the modified thing in template, but the original one).
   * It can't check for directive revision since we don't store them here.
   */
  "Check that revision work for techniques" should {
    import com.softwaremill.quicklens.*
    val APPENED_TEXT = "# the file is modified on HEAD\n"

    import scala.jdk.CollectionConverters.*
    def getCurrentCommitId = (repo.git
      .log()
      .setMaxCount(1)
      .call()
      .asScala
      .headOption
      .getOrElse(throw new RuntimeException("Error in test assumption: can not get current commit"))
      .getId)
    // change content of configuration-repository/techniques/systemSettings/misc/clockConfiguration/3.0/clockConfiguration.st
    // and commit it
    def updateTemplate(rootPath: File): Unit = {
      val clockTemplate =
        new File(rootPath, "configuration-repository/techniques/systemSettings/misc/clockConfiguration/3.0/clockConfiguration.st")
      better.files.File(clockTemplate.getAbsolutePath).append(APPENED_TEXT)(StandardCharsets.UTF_8)
      // commit
      repo.git.commit().setAll(true).setMessage("Update template file").call()
    }

    def writeNodeConfigWithUserDirectives(promiseWriter: PolicyWriterService, userDrafts: BoundPolicyDraft*) = {
      val rnc = baseRootNodeConfig(Chunk.empty).copy(
        policies = policies(baseRootNodeConfig(Chunk.empty).nodeInfo, baseRootDrafts ++ userDrafts), // testing escape of "

        parameters = baseRootNodeConfig(Chunk.empty).parameters + ParameterForConfiguration("ntpserver", """pool."ntp".org""")
      )

      // Actually write the promise files for the root node
      promiseWriter.writeTemplate(
        root.id,
        Set(root.id),
        Map(root.id -> rnc),
        Map(root.id -> rnc.nodeInfo),
        Map(root.id -> NodeConfigId("root-cfg-id")),
        globalPolicyMode,
        DateTime.now,
        parallelism
      )
    }

    def changeRev(draft: BoundPolicyDraft, rev: Revision): BoundPolicyDraft = {
      // need to change in technique and policyId and all resources - usually, both depend from one other each other.
      draft
        .modify(_.id.techniqueVersion)
        .using(_.withRevision(rev))
        .modify(_.technique.id.version)
        .using(_.withRevision(rev))
        .modify(_.technique.agentConfigs.each.templates.each.id)
        .using {
          case TechniqueResourceIdByName(id, name)         =>
            TechniqueResourceIdByName(id.modify(_.version).using(_.withRevision(rev)), name)
          case TechniqueResourceIdByPath(parents, _, name) =>
            TechniqueResourceIdByPath(parents, rev, name)
        }
    }

    "for a directive with a revision specified for technique" in {
      val (rootPath, writer) = getPromiseWriter("technique-and-revisions")
      val initCommit         = getCurrentCommitId.getName
      updateTemplate(abstractRoot)
      val head               = getCurrentCommitId.getName
      // specify technique revision to use in policy
      val revisedClock       = changeRev(clock, Revision(initCommit))
      (initCommit must be_!==(head)) and
      (writeNodeConfigWithUserDirectives(writer, revisedClock) must beFull) and
      compareWith(
        rootPath,
        "technique-and-revisions",
        """.*rudder_common_report\("ntpConfiguration".*@@.*"""              // clock reports
        :: s""".*directive1.*enforce.*merged.*3.0.*Clock Configuration.*""" // in rudder-directives.csv
        :: Nil
      )
    }

  }

}
