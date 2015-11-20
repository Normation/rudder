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

package com.normation.cfclerk.services

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner._
import scala.collection._
import com.normation.cfclerk.domain._
import java.io.File
import org.apache.commons.io.IOUtils
import net.liftweb.common._
import org.apache.commons.io.FileUtils
import org.specs2.specification.AfterExample
import org.specs2.specification.After
import com.normation.cfclerk.xmlparsers._
import com.normation.cfclerk.services.impl._
import org.specs2.specification.Step
import org.specs2.specification.Fragments
import org.eclipse.jgit.api.Git


/**
 * Details of tests executed in each instances of
 * the test.
 * To see values for gitRoot, ptLib, etc, see at the end
 * of that file.
 */
trait JGitPackageReaderSpec extends Specification with Loggable {

  def gitRoot : File
  def ptLib : File
  def relativePathArg : Option[String]

  def deleteDir() = {
    logger.info("Deleting directory " + gitRoot.getAbsoluteFile)
    FileUtils.deleteDirectory(gitRoot)
  }

  //hook to allows to make some more initialisation
  def postInitHook() : Unit

  override def map(fs: =>Fragments) = fs ^ Step(deleteDir)

  val variableSpecParser = new VariableSpecParser
  val policyParser: TechniqueParser = new TechniqueParser(
      variableSpecParser,
      new SectionSpecParser(variableSpecParser),
      new SystemVariableSpecServiceImpl
  )

  //copy the directory with testing policy templates lib in some temp place
  //we use a different directory for git repos and ptlib

  if(true == ptLib.mkdirs) {
    logger.info("Created a new directory to store the policy template library: " + ptLib.getPath)
    logger.info("Git repository will be in: " + gitRoot.getPath)
  } else sys.error("Can not create directory: " + ptLib.getPath)


  FileUtils.copyDirectory(new File("src/test/resources/techniquesRoot") , ptLib)

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
  val templateId = TechniqueResourceIdByPath(Nil, "template")
  val templateContent = "this is some template content"
  template.getParentFile.mkdirs
  FileUtils.writeStringToFile(template, templateContent)
  val template2 = new File(new File(gitRoot, "libdir"), "template2.st")
  val template2Id = TechniqueResourceIdByPath(List("libdir"), "template2")
  val template2Content = "this is template2 content"
  template2.getParentFile.mkdirs
  FileUtils.writeStringToFile(template2, template2Content)

  val f1 = new File(new File(gitRoot, "libdir"), "file1.txt")
  val f1Content = "this is the content of file 1"
  val file1 = TechniqueResourceIdByPath(List("libdir"), f1.getName)
  FileUtils.writeStringToFile(f1, f1Content)

  val file2 = TechniqueResourceIdByName(TechniqueId(TechniqueName("p1_1"), TechniqueVersion("1.0")), "file2.txt")


  val repo = new GitRepositoryProviderImpl(gitRoot.getAbsolutePath)

  //post init hook
  postInitHook

  val reader = new GitTechniqueReader(
                policyParser
              , new SimpleGitRevisionProvider("refs/heads/master", repo)
              , repo
              , "metadata.xml"
              , "category.xml"
              , "expected_reports.csv"
              , relativePathArg
            )

  val infos = reader.readTechniques
  val R = RootTechniqueCategoryId

  //utility to assert the content of a ressource equals some string
  def assertResourceContent(id: TechniqueResourceId, isTemplate: Boolean, expectedContent: String) = {
    val ext = if(isTemplate) Some(TechniqueTemplate.templateExtension) else None
    reader.getResourceContent(id, ext) {
        case None => ko("Can not open an InputStream for " + id.toString)
        case Some(is) => IOUtils.toString(is) === expectedContent
      }
  }

  "The test lib" should {
    "have 3 categories" in infos.subCategories.size === 3
  }

  "The root category" should {
    val rootCat = infos.rootCategory
    "be named 'Root category'" in "Root category" === rootCat.name
    "has no description" in "" === rootCat.description
    "has one policy package..." in 1 === rootCat.packageIds.size
    "...with name p_root_1" in "p_root_1" === rootCat.packageIds.head.name.value
    "...with version 1.0" in "1.0" === rootCat.packageIds.head.version.toString
    "has 1 valid subcategory (because cat2 has no category.xml descriptor)" in 1 === rootCat.subCategoryIds.size
    "...with name cat1" in rootCat.subCategoryIds.head === rootCat.id / "cat1"
  }

  "cat1 sub category" should {
    val cat1 = infos.subCategories( R / "cat1" )
    val packages = cat1.packageIds.toSeq
    val tmlId = TechniqueResourceIdByName(packages(0), "theTemplate")
    "be named 'cat1'" in  cat1.name === "cat1"
    "has no description" in cat1.description === ""
    "has two packages..." in cat1.packageIds.size === 2
    "...with the same name p1_1" in cat1.packageIds.forall(id => "p1_1" === id.name.value)
    "...and version 1.0" in packages(0).version === TechniqueVersion("1.0")
    ".... that provides expected_reports.csv" in infos.techniques(packages(0).name)(packages(0).version).providesExpectedReports === true
    "...and version 2.0" in packages(1).version === TechniqueVersion("2.0")
    ".... that DOES NOT provide expected_reports.csv" in infos.techniques(packages(1).name)(packages(1).version).providesExpectedReports === false
    "...with 3 templates" in {
      infos.techniques(packages(0).name)(packages(0).version).templates.toSet === Set(
        TechniqueTemplate(tmlId, s"p1_1/1.0/${tmlId.name}.cf", true)
      , TechniqueTemplate(templateId, s"bob.txt", false)
      , TechniqueTemplate(template2Id, s"p1_1/1.0/${template2Id.name}.cf", true)
      )
    }
    "...with a template from which we can read 'The template content\\non two lines.'" in {
      assertResourceContent(tmlId, true, "The template content\non two lines.")
    }
    "...with an absolute template from which we can read " in {
      assertResourceContent(templateId, true, templateContent)
    }
    "...with an absolute template2 from which we can read " in {
      assertResourceContent(template2Id, true, template2Content)
    }
    "...with 2 files" in {
      infos.techniques(packages(0).name)(packages(0).version).files.toSet === Set(
        TechniqueFile(file1, s"p1_1/1.0/${file1.name}")
      , TechniqueFile(file2, s"file2")
      )
    }
    "...with a file1 from which we can read" in {
      assertResourceContent(file1, false, f1Content)
    }
    "...with a file2 from which we can read" in {
      assertResourceContent(file2, false, "This is the content of file 2")
    }
  }

  "cat1/cat1_1 sub category" should {
    val cat1_1 = infos.subCategories( R / "cat1" / "cat1_1" )
    "be named 'Category 1.1 name'" in  cat1_1.name === "Category 1.1 name"
    "has description 'Category 1.1 description'" in cat1_1.description === "Category 1.1 description"
    "has 0 package " in cat1_1.packageIds.size === 0
  }

  "cat1/cat1_1/cat1_1_1 sub category" should {
    val cat1_1_1 = infos.subCategories( R / "cat1" / "cat1_1" / "cat1_1_1" )
    "be named 'Category 1.1 name'" in  cat1_1_1.name === "cat1_1_1"
    "has no description" in cat1_1_1.description === ""
    "has two packages..." in cat1_1_1.packageIds.size === 2
    "...with name p1_1_1_1 and p1_1_1_2" in {
      Seq("p1_1_1_1", "p1_1_1_2").forall(name => cat1_1_1.packageIds.exists(id => id.name.value == name)) === true
    }
    "...and the same version 1.0" in {
      cat1_1_1.packageIds.forall(id => id.version === TechniqueVersion("1.0"))
    }
  }

  "if we modify policy cat1/p1_1/2.0, it" should {
    val newPath = reader.canonizedRelativePath.map( _ + "/").getOrElse("") + "cat1/p1_1/2.0/newFile.st"
    val newFile = new File(gitRoot.getAbsoluteFile + "/" + newPath)
    FileUtils.writeStringToFile(newFile, "Some content for the new file")
    val git = new Git(repo.db)
    git.add.addFilepattern(newPath).call
    git.commit.setMessage("Modify PT: cat1/p1_1/2.0").call

    "have update package" in {
      reader.getModifiedTechniques.size === 1
    }
  }

}


/**
 * A test case where git repos and technique lib root are the same
 */
@RunWith(classOf[JUnitRunner])
class JGitPackageReader_SameRootTest extends JGitPackageReaderSpec {
  lazy val gitRoot = new File("/tmp/test-jgit", System.currentTimeMillis.toString)
  lazy val ptLib = gitRoot
  lazy val relativePathArg = None
  def postInitHook() : Unit = {}
}

/**
 * A test case where git repos is on a parent directory
 * of technique lib root.
 * In that configuration, we also add false categories in an other sub-directory of the
 * git to check that the PT reader does not look outside of its root.
 */
@RunWith(classOf[JUnitRunner])
class JGitPackageReader_ChildRootTest extends JGitPackageReaderSpec {
  lazy val gitRoot = new File("/tmp/test-jgit", System.currentTimeMillis.toString)
  lazy val ptLibDirName = "techniques"
  lazy val ptLib = new File(gitRoot, ptLibDirName)
  lazy val relativePathArg = Some("  /" + ptLibDirName + "/  ")

  def postInitHook() : Unit = {
    //add dummy files
    val destName = "phantomTechniquess"
    val dest = new File(gitRoot, destName)
    logger.info("Add false techniques outside root in '%s'".format(gitRoot.getPath + "/phantomPTs"))
    FileUtils.copyDirectory(new File("src/test/resources/phantomTechniques") ,dest)
    //commit in git these files
    val git = new Git(repo.db)
    git.add.addFilepattern(destName).call
    git.commit.setMessage("Commit something looking like a technique but outside PT root directory").call
    ()
  }
}
