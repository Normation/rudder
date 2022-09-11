/*
 *************************************************************************************
 * Copyright 2016 Normation SAS
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

package com.normation.rudder.rest

import better.files._
import com.normation.cfclerk.domain.TechniqueName
import com.normation.utils.ParseVersion
import com.normation.zio._
import net.liftweb.http.LiftRules
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import org.specs2.specification.AfterAll

@RunWith(classOf[JUnitRunner])
class TestRestFromFileDef extends TraitTestApiFromYamlFiles with AfterAll {

  val restTestSetUp = RestTestSetUp.newEnv
  // update package management revision with new commits
  restTestSetUp.updatePackageManagementRevision()

  // let's say that's /var/rudder/share
  val tmpApiTemplate = restTestSetUp.baseTempDirectory / "apiTemplates"
  tmpApiTemplate.createDirectories()

  // nodeXX appears at seleral places

  override def yamlSourceDirectory:  String    = "api"
  override def yamlDestTmpDirectory: File      = tmpApiTemplate
  override def liftRules:            LiftRules = restTestSetUp.liftRules

  override def afterAll(): Unit = {
    if (System.getProperty("tests.clean.tmp") != "false") {
      restTestSetUp.cleanup()
    }
  }

  def copyTransformApiRevision(orig: String): String = {
    // find interesting revision for technique
    val name     = TechniqueName("packageManagement")
    val version  = ParseVersion.parse("1.0").getOrElse(throw new Exception("bad version in test"))
    val revision =
      restTestSetUp.mockTechniques.techniqueRevisionRepo.getTechniqueRevision(name, version).map(_.drop(1).head).runNow
    orig.replaceAll("VALID-REV", revision.rev.value)
  }

  val transformations = Map(
    ("api_revisions.yml" -> copyTransformApiRevision _)
  )

  // we are testing error cases, so we don't want to output error log for them
  org.slf4j.LoggerFactory
    .getLogger("com.normation.rudder.rest.RestUtils")
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.OFF)

  doTest()

}
