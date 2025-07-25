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

package com.normation.cfclerk.services

import com.normation.GitVersion
import com.normation.cfclerk.domain.*
import com.normation.cfclerk.services.impl.GitTechniqueReader
import com.normation.cfclerk.services.impl.SystemVariableSpecServiceImpl
import com.normation.cfclerk.xmlparsers.SectionSpecParser
import com.normation.cfclerk.xmlparsers.TechniqueParser
import com.normation.cfclerk.xmlparsers.VariableSpecParser
import com.normation.rudder.git.GitRepositoryProviderImpl
import com.normation.rudder.git.SimpleGitRevisionProvider
import com.normation.zio.*
import java.io.File
import java.nio.charset.StandardCharsets
import net.liftweb.common.Loggable
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.eclipse.jgit.api.Git
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.junit.runner.RunWith
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.AfterAll
import zio.syntax.*

/**
 * Details of tests executed in each instances of
 * the test.
 * To see values for gitRoot, ptLib, etc, see at the end
 * of that file.
 */
trait JGitPackageReaderSpec extends Specification with Loggable with AfterAll {

  // Set sequential execution
  sequential

  def gitRoot:         File
  def ptLib:           File
  def relativePathArg: Option[String]

  /**
   * Add a switch to be able to see tmp files (not clean themps) with
   * -Dtests.clean.tmp=false
   */
  override def afterAll(): Unit = {
    if (System.getProperty("tests.clean.tmp") != "false") {
      logger.info("Deleting directory " + gitRoot.getAbsolutePath)
      FileUtils.deleteDirectory(gitRoot)
    }
  }

  // hook to allows to make some more initialisation
  def postInitHook(): Unit

  val variableSpecParser = new VariableSpecParser
  val policyParser: TechniqueParser = new TechniqueParser(
    variableSpecParser,
    new SectionSpecParser(variableSpecParser),
    new SystemVariableSpecServiceImpl
  )

  // copy the directory with testing policy templates lib in some temp place
  // we use a different directory for git repos and ptlib

  if (true == ptLib.mkdirs) {
    logger.info("Created a new directory to store the technique library: " + ptLib.getPath)
    logger.info("Git repository will be in: " + gitRoot.getPath)
  } else sys.error("Can not create directory: " + ptLib.getPath)

  FileUtils.copyDirectory(new File("src/test/resources/techniquesRoot"), ptLib)

  /*
   * to check template outside of techniques lib, create a new one at the same level as gitRoot,
   * and an other into a sub dir:
   *
   * - gitRoot
   * -- template.st
   * -- libdir
   *      --- template2.st
   */
  val template = new File(gitRoot, "template.st")
  val templateId: TechniqueResourceIdByPath = TechniqueResourceIdByPath(Nil, GitVersion.DEFAULT_REV, "template")
  val templateContent = "this is some template content"
  template.getParentFile.mkdirs
  FileUtils.writeStringToFile(template, templateContent, StandardCharsets.UTF_8)
  val template2       = new File(new File(gitRoot, "libdir"), "template2.st")
  val template2Id: TechniqueResourceIdByPath = TechniqueResourceIdByPath(List("libdir"), GitVersion.DEFAULT_REV, "template2")
  val template2Content = "this is template2 content"
  template2.getParentFile.mkdirs
  FileUtils.writeStringToFile(template2, template2Content, StandardCharsets.UTF_8)

  val f1        = new File(new File(gitRoot, "libdir"), "file1.txt")
  val f1Content = "this is the content of file 1"
  val file1: TechniqueResourceIdByPath = TechniqueResourceIdByPath(List("libdir"), GitVersion.DEFAULT_REV, f1.getName)
  FileUtils.writeStringToFile(f1, f1Content, StandardCharsets.UTF_8)

  val file2: TechniqueResourceIdByName =
    TechniqueResourceIdByName(TechniqueId(TechniqueName("p1_1"), TechniqueVersionHelper("1.0")), "file2.txt")

  val repo: GitRepositoryProviderImpl = GitRepositoryProviderImpl.make(gitRoot.getAbsolutePath).runNow

  // post init hook
  postInitHook()

  lazy val reader = new GitTechniqueReader(
    policyParser,
    new SimpleGitRevisionProvider("refs/heads/master", repo),
    repo,
    "metadata.xml",
    "category.xml",
    relativePathArg,
    "default-directive-names.conf"
  )

  val infos = reader.readTechniques
  val R     = RootTechniqueCategoryId

  // utility to assert the content of a ressource equals some string
  def assertResourceContent(id: TechniqueResourceId, isTemplate: Boolean, expectedContent: String): MatchResult[Any] = {
    val ext = if (isTemplate) Some(TechniqueTemplate.templateExtension) else None
    reader
      .getResourceContent(id, ext) {
        case None     => ko("Can not open an InputStream for " + id.displayPath).succeed
        case Some(is) => (IOUtils.toString(is, StandardCharsets.UTF_8) === expectedContent).succeed
      }
      .runNow
  }

  "The test lib" should {
    "have 3 categories" in infos.subCategories.size === 3
  }

  "The root category" should {
    val rootCat = infos.rootCategory
    "be named 'Root category'" in rootCat.name === "Root category"
    "has no description" in rootCat.description === ""
    "has one policy technique..." in rootCat.techniqueIds.size === 1
    "...with name p_root_1" in rootCat.techniqueIds.head.name.value === "p_root_1"
    "...with version 1.0" in rootCat.techniqueIds.head.version.debugString === "1.0"
    "has 1 valid subcategory (because cat2 has no category.xml descriptor)" in rootCat.subCategoryIds.size === 1
    "...with name cat1" in rootCat.subCategoryIds.head === rootCat.id / "cat1"
  }

  "cat1 sub category" should {
    val cat1       = infos.subCategories(R / "cat1")
    val techniques = cat1.techniqueIds.toSeq
    val tmlId      = TechniqueResourceIdByName(techniques(0), "theTemplate")
    "be named 'cat1'" in cat1.name === "cat1"
    "has no description" in cat1.description === ""
    "has two techniques..." in cat1.techniqueIds.size === 2
    "...with the same name p1_1" in cat1.techniqueIds.forall(id => "p1_1" === id.name.value)
    "...and version 1.0" in techniques(0).version === TechniqueVersionHelper("1.0")
    "...and version 2.0" in techniques(1).version === TechniqueVersionHelper("2.0")
    "...with 3 templates" in {
      infos.techniques(techniques(0).name)(techniques(0).version).agentConfigs(0).templates.toSet === Set(
        TechniqueTemplate(tmlId, s"p1_1/1.0/${tmlId.name}.cf", included = true),
        TechniqueTemplate(templateId, s"bob.txt", included = false),
        TechniqueTemplate(template2Id, s"p1_1/1.0/${template2Id.name}.cf", included = true)
      )
    }
    "...with a template from which we can read 'The template content\\non two lines.'" in {
      assertResourceContent(tmlId, isTemplate = true, expectedContent = "The template content\non two lines.")
    }
    "...with an absolute template from which we can read " in {
      assertResourceContent(templateId, isTemplate = true, expectedContent = templateContent)
    }
    "...with an absolute template2 from which we can read " in {
      assertResourceContent(template2Id, isTemplate = true, expectedContent = template2Content)
    }
    "...with 2 files" in {
      infos.techniques(techniques(0).name)(techniques(0).version).agentConfigs(0).files.toSet === Set(
        TechniqueFile(file1, s"p1_1/1.0/${file1.name}", included = false),
        TechniqueFile(file2, s"file2", included = false)
      )
    }
    "...with a file1 from which we can read" in {
      assertResourceContent(file1, isTemplate = false, expectedContent = f1Content)
    }
    "...with a file2 from which we can read" in {
      assertResourceContent(file2, isTemplate = false, expectedContent = "This is the content of file 2")
    }
  }

  "cat1/cat1_1 sub category" should {
    val cat1_1 = infos.subCategories(R / "cat1" / "cat1_1")
    "be named 'Category 1.1 name'" in cat1_1.name === "Category 1.1 name"
    "has description 'Category 1.1 description'" in cat1_1.description === "Category 1.1 description"
    "has 0 technique " in cat1_1.techniqueIds.size === 0
  }

  "cat1/cat1_1/cat1_1_1 sub category" should {
    val cat1_1_1 = infos.subCategories(R / "cat1" / "cat1_1" / "cat1_1_1")
    "be named 'Category 1.1 name'" in cat1_1_1.name === "cat1_1_1"
    "has no description" in cat1_1_1.description === ""
    "has two techniques..." in cat1_1_1.techniqueIds.size === 2
    "...with name p1_1_1_1 and p1_1_1_2" in {
      Seq("p1_1_1_1", "p1_1_1_2").forall(name => cat1_1_1.techniqueIds.exists(id => id.name.value == name)) === true
    }
    "...and the same version 1.0" in {
      cat1_1_1.techniqueIds.forall(id => id.version === TechniqueVersionHelper("1.0"))
    }
  }

  "if we modify policy cat1/p1_1/2.0, it" should {
    "have update technique" in {
      reader.readTechniques // be sure there is no current modification
      val newPath = reader.canonizedRelativePath.map(_ + "/").getOrElse("") + "cat1/p1_1/2.0/newFile.st"
      val newFile = new File(gitRoot.getAbsoluteFile.getPath + "/" + newPath)
      FileUtils.writeStringToFile(newFile, "Some content for the new file", StandardCharsets.UTF_8)
      val git     = new Git(repo.db)
      git.add.addFilepattern(newPath).call
      git.commit.setMessage("Modify PT: cat1/p1_1/2.0").call

      val n = TechniqueName("p1_1")
      reader.getModifiedTechniques === Map(n -> TechniqueUpdated(n, Map(TechniqueVersionHelper("2.0") -> VersionUpdated)))
    }

    "and after a read, no more modification" in {
      reader.readTechniques // be sure there is no current modification
      reader.getModifiedTechniques.size === 0
    }

  }

  "if we create technique cat1/p1_1/3.0, it" should {
    "have not create technique if metadata.xml does not exist" in {
      reader.readTechniques // be sure there is no current modification
      val newPath = reader.canonizedRelativePath.map(_ + "/").getOrElse("") + "cat1/p1_1/3.0/newFile.st"
      val newFile = new File(gitRoot.getAbsoluteFile.getPath + "/" + newPath)
      FileUtils.writeStringToFile(newFile, "Some content for the new file", StandardCharsets.UTF_8)
      val git     = new Git(repo.db)
      git.add.addFilepattern(newPath).call
      git.commit.setMessage("Not a technique yet: cat1/p1_1/3.0").call
      reader.getModifiedTechniques === Map()
    }

    "have created a technique" in {
      reader.readTechniques // be sure there is no current modification
      val newPath = reader.canonizedRelativePath.map(_ + "/").getOrElse("") + "cat1/p1_1/3.0/metadata.xml"
      val newFile = new File(gitRoot.getAbsoluteFile.getPath + "/" + newPath)
      FileUtils.writeStringToFile(
        newFile,
        """<TECHNIQUE name="Package 1_1">
          |    <DESCRIPTION>A package</DESCRIPTION>
          |    <DISPLAY>true</DISPLAY>
          |    <TMLS></TMLS>
          |</TECHNIQUE>   """.stripMargin,
        StandardCharsets.UTF_8
      )
      val git     = new Git(repo.db)
      git.add.addFilepattern(newPath).call
      git.commit.setMessage("Create Technique: cat1/p1_1/3.0").call

      val n = TechniqueName("p1_1")
      reader.getModifiedTechniques === Map(n -> TechniqueUpdated(n, Map(TechniqueVersionHelper("3.0") -> VersionAdded)))
    }

    "have update the technique on new file" in {
      reader.readTechniques // be sure there is no current modification
      val newPath = reader.canonizedRelativePath.map(_ + "/").getOrElse("") + "cat1/p1_1/3.0/newFile-2.st"
      val newFile = new File(gitRoot.getAbsoluteFile.getPath + "/" + newPath)
      FileUtils.writeStringToFile(newFile, "Some content for the new file", StandardCharsets.UTF_8)
      val git     = new Git(repo.db)
      git.add.addFilepattern(newPath).call
      git.commit.setMessage("update technique: cat1/p1_1/3.0").call

      val n = TechniqueName("p1_1")
      reader.getModifiedTechniques === Map(n -> TechniqueUpdated(n, Map(TechniqueVersionHelper("3.0") -> VersionUpdated)))
    }

    "have updated the technique on metadata change" in {
      reader.readTechniques // be sure there is no current modification
      val newPath = reader.canonizedRelativePath.map(_ + "/").getOrElse("") + "cat1/p1_1/3.0/metadata.xml"
      val newFile = new File(gitRoot.getAbsoluteFile.getPath + "/" + newPath)
      FileUtils.writeStringToFile(
        newFile,
        """<TECHNIQUE name="Package 1_1">
          |    <DESCRIPTION>A new description</DESCRIPTION>
          |    <DISPLAY>true</DISPLAY>
          |    <TMLS></TMLS>
          |</TECHNIQUE>   """.stripMargin,
        StandardCharsets.UTF_8
      )
      val git     = new Git(repo.db)
      git.add.addFilepattern(newPath).call
      git.commit.setMessage("update technique: cat1/p1_1/3.0").call

      val n = TechniqueName("p1_1")
      reader.getModifiedTechniques === Map(n -> TechniqueUpdated(n, Map(TechniqueVersionHelper("3.0") -> VersionUpdated)))
    }

    "have updated the technique when a metadata.xml file that is not the real metadata.xml of the technique" in {
      reader.readTechniques // be sure there is no current modification
      val newPath = reader.canonizedRelativePath.map(_ + "/").getOrElse("") + "cat1/p1_1/3.0/resources/metadata.xml"
      val newFile = new File(gitRoot.getAbsoluteFile.getPath + "/" + newPath)
      FileUtils.writeStringToFile(
        newFile,
        """<TECHNIQUE name="Package 1_1">
          |    <DESCRIPTION>A new description</DESCRIPTION>
          |    <DISPLAY>true</DISPLAY>
          |    <TMLS></TMLS>
          |</TECHNIQUE>   """.stripMargin,
        StandardCharsets.UTF_8
      )
      val git     = new Git(repo.db)
      git.add.addFilepattern(newPath).call
      git.commit.setMessage("update technique: cat1/p1_1/3.0").call

      val n = TechniqueName("p1_1")
      reader.getModifiedTechniques === Map(n -> TechniqueUpdated(n, Map(TechniqueVersionHelper("3.0") -> VersionUpdated)))
    }

    "have deleted the technique" in {
      reader.readTechniques // be sure there is no current modification
      val newPath = reader.canonizedRelativePath.map(_ + "/").getOrElse("") + "cat1/p1_1/3.0/metadata.xml"
      val newFile = new File(gitRoot.getAbsoluteFile.getPath + "/" + newPath)
      FileUtils.delete(newFile)
      val git     = new Git(repo.db)
      git.rm().addFilepattern(newPath).call
      git.commit.setMessage("Delete PT: cat1/p1_1/3.0").call

      val n = TechniqueName("p1_1")
      reader.getModifiedTechniques === Map(n -> TechniqueUpdated(n, Map(TechniqueVersionHelper("3.0") -> VersionDeleted)))
    }
  }
  "if we modify technique cat1/p1_1/1.0 external file, it" should {
    "have update technique" in {

      val name    = "libdir/file1.txt" // no slash at the begining of git path
      reader.readTechniques
      val newFile = new File(gitRoot.getAbsolutePath + "/" + name)
      FileUtils.writeStringToFile(newFile, "Some more content for the new file arg arg arg", StandardCharsets.UTF_8)
      val git     = new Git(repo.db)
      git.add.addFilepattern(name).call
      git.commit.setMessage("Modify file /libdir/file1.txt in technique: cat1/p1_1/1.0").call
      val n       = TechniqueName("p1_1")
      reader.getModifiedTechniques === Map(n -> TechniqueUpdated(n, Map(TechniqueVersionHelper("1.0") -> VersionUpdated)))
    }
  }

}

/**
 * A test case where git repos and technique lib root are the same
 */
@RunWith(classOf[JUnitRunner])
class JGitPackageReader_SameRootTest extends JGitPackageReaderSpec {
  lazy val gitRoot = new File("/tmp/test-jgit-" + DateTime.now(DateTimeZone.UTC).toString())
  lazy val ptLib   = gitRoot
  lazy val relativePathArg: Option[String] = None
  def postInitHook():       Unit           = {}
}

/**
 * A test case where git repos is on a parent directory
 * of technique lib root.
 * In that configuration, we also add false categories in an other sub-directory of the
 * git to check that the technique reader does not look outside of its root.
 */
@RunWith(classOf[JUnitRunner])
class JGitPackageReader_ChildRootTest extends JGitPackageReaderSpec {
  lazy val gitRoot      = new File("/tmp/test-jgit-" + DateTime.now(DateTimeZone.UTC).toString())
  lazy val ptLibDirName = "techniques"
  lazy val ptLib        = new File(gitRoot, ptLibDirName)
  lazy val relativePathArg: Some[String] = Some("  /" + ptLibDirName + "/  ")

  def postInitHook(): Unit = {
    // add dummy files
    val destName = "phantomTechniquess"
    val dest     = new File(gitRoot, destName)
    logger.info("Add false techniques outside root in '%s'".format(gitRoot.getPath + "/phantomPTs"))
    FileUtils.copyDirectory(new File("src/test/resources/phantomTechniques"), dest)
    // commit in git these files
    val git      = new Git(repo.db)
    git.add.addFilepattern(destName).call
    git.commit.setMessage("Commit something looking like a technique but outside technique root directory").call
    ()
  }
}
