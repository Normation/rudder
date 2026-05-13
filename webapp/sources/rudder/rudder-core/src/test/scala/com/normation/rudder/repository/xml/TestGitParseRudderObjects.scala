/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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

package com.normation.rudder.repository.xml

import com.normation.GitVersion.Revision
import com.normation.cfclerk.domain.TechniqueCategoryName
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.rudder.services.policies.TestTechniqueRepo
import com.normation.utils.ParseVersion
import com.normation.zio.*
import net.liftweb.common.Loggable
import org.apache.commons.io.FileUtils
import org.junit.runner.RunWith
import org.specs2.matcher.ContentMatchers
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.AfterAll
import zio.Chunk

@RunWith(classOf[JUnitRunner])
class TestGitParseRudderObjects extends Specification with Loggable with AfterAll with ContentMatchers {

  ////////// set up / clean-up and utilities //////////

  lazy val testRepo     = new TestTechniqueRepo("", "same-end-technique-name", None)
  lazy val abstractRoot = testRepo.abstractRoot
  lazy val parseObjects = new GitParseTechniqueLibrary(
    testRepo.draftParser,
    testRepo.repo,
    testRepo.gitRevisionProvider,
    "techniques",
    "metadata.xml"
  )

  override def afterAll(): Unit = {
    if (System.getProperty("tests.clean.tmp") != "false") {
      logger.debug("Deleting directory " + abstractRoot.getAbsolutePath)
      FileUtils.deleteDirectory(abstractRoot.getAbsoluteFile)
    }
  }

  val v1_0 = ParseVersion.parse("1.0").getOrElse(throw new RuntimeException("Version 1.0"))

  sequential

  "looking for technique with the same end name" should {
    "return the correct one" in {
      val res = parseObjects.getTechnique(TechniqueName("file"), v1_0, Revision("master")).runNow

      res.map(_._1) must (beSome((Chunk(TechniqueCategoryName("zz_last")))))
      res.map(_._2.id.serialize) must (beSome("file/1.0+master"))
    }

    "give the correct resources" in {
      val res = parseObjects.getTechniqueFileContents(TechniqueId(TechniqueName("file"), TechniqueVersion.V1_0)).runNow

      // must not contain Create_file.ps1 etc
      res.map(_.map(_._1).toSet) must (beSome(containTheSameElementsAs(List("metadata.xml", "packageManagement.st"))))
    }
  }
}
