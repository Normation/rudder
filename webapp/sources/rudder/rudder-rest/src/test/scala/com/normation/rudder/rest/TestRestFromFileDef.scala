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

import better.files.*
import com.normation.cfclerk.domain.TechniqueName
import com.normation.errors.*
import com.normation.utils.ParseVersion
import com.normation.zio.*
import org.junit.runner.RunWith
import zio.*
import zio.test.*
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class TestRestFromFileDef extends ZIOSpecDefault {

  // nodeXX appears at several places

  def copyTransformApiRevision(restTestSetUp: RestTestSetUp)(orig: String): String = {
    // find interesting revision for technique
    val name     = TechniqueName("packageManagement")
    val version  = ParseVersion.parse("1.0").getOrElse(throw new Exception("bad version in test"))
    val revision =
      restTestSetUp.mockTechniques.techniqueRevisionRepo.getTechniqueRevision(name, version).map(_.drop(1).head).runNow
    orig.replaceAll("VALID-REV", revision.rev.value)
  }

  // you can pass a list of file to test exclusively if you don't want to test all .yml
  // files in src/test/resource/${yamlSourceDirectory}
  // we are testing error cases, so we don't want to output error log for them
  org.slf4j.LoggerFactory
    .getLogger("com.normation.rudder.rest.RestUtils")
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.OFF)

  val restTestSetUp = RestTestSetUp.newEnv
  // update package management revision with new commits

  // let's say that's /var/rudder/share
  val tmpApiTemplate: File = restTestSetUp.baseTempDirectory / "apiTemplates"

  val yamlSourceDirectory:  String = "api"
  val yamlDestTmpDirectory: File   = tmpApiTemplate

  val transformations: Map[String, String => String] = Map(
    ("api_revisions.yml" -> copyTransformApiRevision(restTestSetUp))
  )

  tmpApiTemplate.createDirectories()

  override def spec: Spec[TestEnvironment & Scope, Any] = {
    suite("All REST tests defined in files") {

      for {
        s <- TraitTestApiFromYamlFiles.doTest(
               yamlSourceDirectory,
               yamlDestTmpDirectory,
               restTestSetUp.liftRules,
               Nil,
               transformations
             )
        _ <- effectUioUnit(
               if (java.lang.System.getProperty("tests.clean.tmp") != "false") IOResult.attempt(restTestSetUp.cleanup())
               else ZIO.unit
             )
      } yield s
    } @@ TestAspect.sequential // this is important so that modification done in files are always in the same order in the global env
  }

}
