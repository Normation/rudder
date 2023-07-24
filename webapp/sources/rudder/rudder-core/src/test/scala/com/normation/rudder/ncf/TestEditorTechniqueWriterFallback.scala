/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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
import com.normation.errors._
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.Version
import com.normation.rudder.ncf.ParameterType.PlugableParameterTypeService
import com.normation.rudder.ncf.TechniqueCompilerApp._
import com.normation.rudder.repository.xml.RudderPrettyPrinter
import com.normation.rudder.services.nodes.PropertyEngineServiceImpl
import com.normation.rudder.services.policies.InterpolatedValueCompilerImpl
import com.normation.zio._
import com.softwaremill.quicklens._
import java.io.{File => JFile}
import net.liftweb.common.Loggable
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.matcher.ContentMatchers
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.BeforeAfterAll
import scala.annotation.nowarn
import zio.syntax._

/*
 * Test the fallback logic for the technique writer
 */
@nowarn("msg=a type was inferred to be `Any`; this may indicate a programming error.")
@RunWith(classOf[JUnitRunner])
class TestEditorTechniqueWriterFallback extends Specification with ContentMatchers with Loggable with BeforeAfterAll {
  sequential
  lazy val basePath = "/tmp/test-rudder-technique-writer-fallback-" + DateTime.now.toString()

  override def beforeAll(): Unit = {
    new JFile(basePath).mkdirs()
  }

  override def afterAll(): Unit = {
    if (java.lang.System.getProperty("tests.clean.tmp") != "false") {
      FileUtils.deleteDirectory(new JFile(basePath))
    }
  }

  val expectedPath = "src/test/resources/configuration-repository"

  /// check the state of one of the file produce by technique compilation
  sealed trait OutputFileStatus
  object OutputFileStatus {
    case object Missing          extends OutputFileStatus
    case object ExistsNonEmpty   extends OutputFileStatus // when you just want to check that the file exists (non empty)
    case object CreatedByRudderc extends OutputFileStatus
    case object CreatedByWebapp  extends OutputFileStatus
  }
  import OutputFileStatus._

  object Content {
    val ruddercMetadata = "I'm not really an XML"
    val ruddercCFE      = "the cfe content by rudderc"
    val ruddercPS1      = "the ps1 content by rudderc"

    val techniqueOK              = "techniqueOK"
    val techniqueNOK_user        = "techniqueNOK_user"
    val techniqueNOK_empty_pos   = "techniqueNOK_empty_pos"
    val techniqueNOK_empty_neg   = "techniqueNOK_empty_neg"
    val techniqueNOK_xml         = "techniqueNOK_xml"
    val techniqueNOK_xml_cfe     = "techniqueNOK_xml_cfe"
    val techniqueNOK_xml_cfe_ps1 = "techniqueNOK_xml_cfe_ps1"
  }

  /*
   * For test: it will fail on technique based on id value:
   * - techniqueOK => write all, return success
   * - techniqueNOK_user => write nothing, return an user error code (no fallback)
   * - techniqueNOK_empty => nothing written, fail
   * - techniqueNOK_xml => xml written, fail
   * - techniqueNOK_xml_cfe => xml, cfe written, fail
   * - techniqueNOK_xml_cfe_ps1 => xml, cfe, ps1 written, fail
   */
  object TestRudderc extends RuddercService {

    // technique path is supposed to be absolute and in the correct place in the temp directory.
    override def compile(techniqueDir: File, options: RuddercOptions): IOResult[RuddercResult] = {
      def writeXML = (techniqueDir / "metadata.xml").write(Content.ruddercMetadata)
      def writeCFE = (techniqueDir / "technique.cf").write(Content.ruddercCFE)
      def writePS1 = (techniqueDir / "technique.ps1").write(Content.ruddercPS1)

      techniqueDir.name match {
        case Content.techniqueOK              =>
          IOResult.attempt {
            writeXML
            writeCFE
            writePS1
            RuddercResult.Ok("ok", "stdout", "")
          }
        case Content.techniqueNOK_user        =>
          IOResult.attempt {
            RuddercResult.UserError(7, "nok:user", "stdout", "stderr")
          }
        case Content.techniqueNOK_empty_neg   =>
          IOResult.attempt {
            RuddercResult.Fail(-42, "nok:empty", "stdout", "stderr")
          }
        case Content.techniqueNOK_empty_pos   =>
          IOResult.attempt {
            RuddercResult.Fail(50, "nok:empty", "stdout", "stderr")
          }
        case Content.techniqueNOK_xml         =>
          IOResult.attempt {
            writeXML
            RuddercResult.Fail(60, "nok:xml", "stdout", "stderr")
          }
        case Content.techniqueNOK_xml_cfe     =>
          IOResult.attempt {
            writeXML
            writeCFE
            RuddercResult.Fail(70, "nok:cfe", "stdout", "stderr")
          }
        case Content.techniqueNOK_xml_cfe_ps1 =>
          IOResult.attempt {
            writeXML
            writeCFE
            writePS1
            RuddercResult.Fail(80, "nok:ps1", "stdout", "stderr")
          }
        // other case must not exists in test, error.
        case other                            => Unexpected(s"Asking for technique with id '${other}' which is not a test case - go see TestRudderc").fail
      }
    }
  }

  val valueCompiler        = new InterpolatedValueCompilerImpl(new PropertyEngineServiceImpl(List.empty))
  val parameterTypeService = new PlugableParameterTypeService

  def newCompilerWithBase(baseDir: String, defaultCompiler: TechniqueCompilerApp = Rudderc) = new TechniqueCompilerWithFallback(
    valueCompiler,
    new RudderPrettyPrinter(Int.MaxValue, 2),
    parameterTypeService,
    TestRudderc,
    defaultCompiler,
    _.id.value, // remove useless /technique/categories/techId/1.0/ and keep only techId
    baseDir
  )

  import ParameterType._
  val defaultConstraint = Constraint.AllowEmpty(false) :: Constraint.AllowWhiteSpace(false) :: Constraint.MaxLength(16384) :: Nil
  val methods           = (GenericMethod(
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
    Nil).map(m => (m.id, m)).toMap

  val techniqueTemplate = {
    EditorTechnique(
      BundleName("technique_by_Rudder"),
      new Version("1.0"),
      "Test Technique created with love",
      "ncf_techniques",
      MethodCall(
        BundleName("package_install"),
        "id4",
        Map((ParameterId("package_name"), "openssh-server")),
        "redhat",
        "Package install",
        false
      ) :: Nil,
      "",
      "",
      Nil,
      Nil,
      Map(),
      None
    )
  }

  // File object for the configuration-config.yml for given compiler/technique
  def compilationConfigFile(implicit compiler: TechniqueCompilerWithFallback, technique: EditorTechnique) = File(
    compiler.baseConfigRepoPath + "/" + technique.id.value + "/" + compiler.compilationConfigFilename
  )

  // File object for the configuration-output.yml for given compiler/technique
  def compilationOutputFile(implicit compiler: TechniqueCompilerWithFallback, technique: EditorTechnique) = File(
    compiler.baseConfigRepoPath + "/" + technique.id.value + "/" + compiler.compilationOutputFilename
  )

  // check validity of file content written by rudderc
  def checkOutput(
      compiler:    TechniqueCompilerWithFallback,
      filename:    String,
      techniqueId: String,
      expected:    String,
      status:      OutputFileStatus
  ) = {
    val f = File(compiler.baseConfigRepoPath + "/" + techniqueId + "/" + filename)

    status match {
      case Missing          => f.exists must beFalse
      case ExistsNonEmpty   => (f.exists must beTrue) and (f.nonEmpty must beTrue)
      case CreatedByRudderc => f.toJava must haveSameLinesAs(Seq(expected))
      case CreatedByWebapp  => f.toJava must not(haveSameLinesAs(Seq(expected)))
    }
  }

  // check validity of xml file written by rudderc
  def checkXML(status: OutputFileStatus)(implicit compiler: TechniqueCompilerWithFallback, technique: EditorTechnique) = {
    checkOutput(compiler, "metadata.xml", technique.id.value, Content.ruddercMetadata, status)
  }

  // check validity of .cf file written by rudderc
  def checkCFE(status: OutputFileStatus)(implicit compiler: TechniqueCompilerWithFallback, technique: EditorTechnique) = {
    checkOutput(compiler, "technique.cf", technique.id.value, Content.ruddercCFE, status)
  }

  // check validity of .ps1 file written by rudderc
  def checkPS1(status: OutputFileStatus)(implicit compiler: TechniqueCompilerWithFallback, technique: EditorTechnique) = {
    checkOutput(compiler, "technique.ps1", technique.id.value, Content.ruddercPS1, status)
  }

  // it is done elsewhere in rudder
  def initDirectories(compiler: TechniqueCompilerWithFallback, technique: EditorTechnique) = {
    (compiler.gitDir / technique.id.value).createDirectories()
  }

  s"Compiler without local compilation override" should {
    val rootPath          = basePath + "/no-override"
    implicit val compiler = newCompilerWithBase(rootPath)

    "Use the default compiler app and no fallback on success" in {
      implicit val technique = techniqueTemplate.modify(_.id.value).setTo(Content.techniqueOK)
      initDirectories(compiler, technique)
      val res                = compiler.compileTechnique(technique, methods).runNow

      (res must beEqualTo(TechniqueCompilationOutput(Rudderc, false, 0, "ok", "stdout", ""))) and
      (compilationConfigFile.exists must beFalse) and
      (compilationOutputFile.exists must beFalse) and
      checkXML(CreatedByRudderc) and
      checkCFE(CreatedByRudderc) and
      checkPS1(CreatedByRudderc)
    }

    "Use the default compiler app and no fallback on user error" in {
      implicit val technique = techniqueTemplate.modify(_.id.value).setTo(Content.techniqueNOK_user)
      initDirectories(compiler, technique)
      val res                = compiler.compileTechnique(technique, methods).runNow

      (res must beEqualTo(TechniqueCompilationOutput(Rudderc, false, 7, "nok:user", "stdout", "stderr"))) and
      (compilationConfigFile.exists must beFalse) and
      (compilationOutputFile.exists must beTrue) and
      checkXML(Missing) and
      checkCFE(Missing) and
      checkPS1(Missing)
    }

    "Fallback to webapp with compilation-output written when failing with code < 0" in {
      implicit val technique = techniqueTemplate.modify(_.id.value).setTo(Content.techniqueNOK_empty_neg)
      initDirectories(compiler, technique)
      val res                = compiler.compileTechnique(technique, methods).runNow

      (res must beEqualTo(TechniqueCompilationOutput(Webapp, true, -42, "nok:empty", "stdout", "stderr"))) and
      (compilationConfigFile.exists must beFalse) and
      (compilationOutputFile.exists must beTrue) and
      checkXML(CreatedByWebapp) and
      checkCFE(CreatedByWebapp) and
      checkPS1(CreatedByWebapp)
    }

    "Fallback to webapp with compilation-output written when failing with code > limit code on xml" in {
      implicit val technique = techniqueTemplate.modify(_.id.value).setTo(Content.techniqueNOK_empty_pos)
      initDirectories(compiler, technique)
      val res                = compiler.compileTechnique(technique, methods).runNow

      (res must beEqualTo(TechniqueCompilationOutput(Webapp, true, 50, "nok:empty", "stdout", "stderr"))) and
      (compilationConfigFile.exists must beFalse) and
      (compilationOutputFile.exists must beTrue) and
      checkXML(CreatedByWebapp) and
      checkCFE(CreatedByWebapp) and
      checkPS1(CreatedByWebapp)
    }

    "Fallback to webapp with compilation-output written when failing on cfe and rewrite XML written by rudderc" in {
      implicit val technique = techniqueTemplate.modify(_.id.value).setTo(Content.techniqueNOK_xml)
      initDirectories(compiler, technique)
      val res                = compiler.compileTechnique(technique, methods).runNow

      (res must beEqualTo(TechniqueCompilationOutput(Webapp, true, 60, "nok:xml", "stdout", "stderr"))) and
      (compilationConfigFile.exists must beFalse) and
      (compilationOutputFile.exists must beTrue) and
      checkXML(CreatedByWebapp) and
      checkCFE(CreatedByWebapp) and
      checkPS1(CreatedByWebapp)
    }

    "Fallback to webapp with compilation-output written when failing on ps1 and rewrite XML,cf written by rudderc" in {
      implicit val technique = techniqueTemplate.modify(_.id.value).setTo(Content.techniqueNOK_xml_cfe)
      initDirectories(compiler, technique)
      val res                = compiler.compileTechnique(technique, methods).runNow

      (res must beEqualTo(TechniqueCompilationOutput(Webapp, true, 70, "nok:cfe", "stdout", "stderr"))) and
      (compilationConfigFile.exists must beFalse) and
      (compilationOutputFile.exists must beTrue) and
      checkXML(CreatedByWebapp) and
      checkCFE(CreatedByWebapp) and
      checkPS1(CreatedByWebapp)
    }

    "Fallback to webapp with compilation-output written when failing on the end and rewrite XML,cf,ps1 written by rudderc" in {
      implicit val technique = techniqueTemplate.modify(_.id.value).setTo(Content.techniqueNOK_xml_cfe_ps1)
      initDirectories(compiler, technique)
      val res                = compiler.compileTechnique(technique, methods).runNow

      (res must beEqualTo(TechniqueCompilationOutput(Webapp, true, 80, "nok:ps1", "stdout", "stderr"))) and
      (compilationConfigFile.exists must beFalse) and
      (compilationOutputFile.exists must beTrue) and
      checkXML(CreatedByWebapp) and
      checkCFE(CreatedByWebapp) and
      checkPS1(CreatedByWebapp)
    }
  }

  s"Compiler WITH local compilation override" should {
    import zio.json.yaml._
    import TechniqueCompilationIO._

    val rootPath                                         = basePath + "/override"
    implicit val compiler                                = newCompilerWithBase(rootPath)
    val overrideConfig                                   = TechniqueCompilationConfig(Some(Webapp))
    def writeConfig(implicit technique: EditorTechnique) = {
      compiler
        .getCompilationConfigFile(technique)
        .write(overrideConfig.toYaml().getOrElse(throw new IllegalArgumentException(s"Error in test")))
    }

    "Use the default compiler app and no fallback on success" in {
      implicit val technique = techniqueTemplate.modify(_.id.value).setTo(Content.techniqueOK)
      initDirectories(compiler, technique)
      writeConfig
      val res                = compiler.compileTechnique(technique, methods).runNow

      (res must beEqualTo(TechniqueCompilationOutput(Webapp, false, 0, "Technique 'techniqueOK' written by webapp", "", ""))) and
      (compilationConfigFile.exists must beTrue) and
      (compilationOutputFile.exists must beFalse) and // it's not an override, there's no error: no output file
      checkXML(CreatedByWebapp) and
      checkCFE(CreatedByWebapp) and
      checkPS1(CreatedByWebapp)
    }

  }

}
