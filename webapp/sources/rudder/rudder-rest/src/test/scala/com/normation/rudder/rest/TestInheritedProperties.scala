/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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
import com.normation.GitVersion
import com.normation.errors.*
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.eventlog
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.properties.GenericProperty.StringToConfigValue
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.properties.GroupProperty
import com.normation.rudder.domain.properties.Visibility
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.zio.*
import org.junit.runner.RunWith
import zio.*
import zio.test.*
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class TestInheritedProperties extends ZIOSpecDefault {

  val restTestSetUp = RestTestSetUp.newEnv

  // let's say that's /var/rudder/share
  val tmpApiTemplate: File = restTestSetUp.baseTempDirectory / "apiTemplates"
  tmpApiTemplate.createDirectories()

  // nodeXX appears at several places

  def yamlSourceDirectory:  String = "api_inherited_prop"
  def yamlDestTmpDirectory: File   = tmpApiTemplate

  val transformations: Map[String, String => String] = Map()

  // there is some tests that need change in data model, that we don't want to have in all plugins.
  // For ex, the tests for inherited values
  val badOverrideType: GlobalParameter = {
    GlobalParameter(
      "badOverrideType",
      GitVersion.DEFAULT_REV,
      "a string".toConfigValue,
      None,
      "a string at first",
      None,
      Visibility.default
    )
  }
  restTestSetUp.mockParameters.paramsRepo.paramsMap.update(m => m + (badOverrideType.name -> badOverrideType)).runNow

  val gProp: GroupProperty = GroupProperty
    .parse(
      "badOverrideType",
      GitVersion.DEFAULT_REV,
      """{ "now":"a json" }""",
      None,
      None
    )
    .getOrElse(null) // for test

  import com.softwaremill.quicklens.*

  val g0Id: NodeGroupId = restTestSetUp.mockNodeGroups.g0.id
  (for {
    g <- restTestSetUp.mockNodeGroups.groupsRepo.getNodeGroupOpt(g0Id)(using QueryContext.testQC).notOptional("test")
    up = g._1.modify(_.properties).using(_.appended(gProp))
    _ <- restTestSetUp.mockNodeGroups.groupsRepo.update(up, ModificationId("test"), eventlog.RudderEventActor, None)
    _ <- restTestSetUp.mockNodeGroups.propService.updateAll() // the properties also need to be recomputed after group is updated
  } yield ()).runNow

  // we are testing error cases, so we don't want to output error log for them
  org.slf4j.LoggerFactory
    .getLogger("com.normation.rudder.rest.RestUtils")
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.OFF)

  override def spec: Spec[TestEnvironment & Scope, Any] = {
    (suite("All REST tests defined in files") {

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
    }) // @@ TestAspect.ignore
  }
}
