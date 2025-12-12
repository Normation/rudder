/*
 *************************************************************************************
 * Copyright 2020 Normation SAS
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

package bootstrap.liftweb.checks.endconfig.consistency

import com.normation.GitVersion
import com.normation.rudder.MockGlobalParam
import com.normation.rudder.domain.properties.*
import com.normation.rudder.repository.RoParameterRepository
import com.normation.rudder.repository.WoParameterRepository
import com.normation.utils.StringUuidGenerator
import com.typesafe.config.ConfigFactory
import java.util.UUID
import zio.Ref
import zio.Scope
import zio.test.Assertion.equalTo
import zio.test.Spec
import zio.test.TestEnvironment
import zio.test.ZIOSpecDefault
import zio.test.assert

object CheckRudderGlobalPropertiesTest extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment & Scope, Any] = {
    suite("CheckRudderGlobalProperties")(
      test("it should check the global properties without errors") {
        for {
          counterRef <- Ref.make(0)
          pRepo       = new MockGlobalParam().paramsRepo
          uuidGen     = stubStringUuidGenerator
          underTest   = CheckRudderGlobalProperties(roParamRepo = pRepo, woParamRepo = pRepo, uuidGen = uuidGen)
          _           = underTest.checks()
          paramsMap  <- pRepo.paramsMap.get
        } yield assert(paramsMap("rudder"))(equalTo(globalParameter))
      },
      test("it should check the inconsistent global properties handling errors") {
        for {
          counterRef <- Ref.make(0)
          pRepo       = new MockGlobalParam().paramsRepo
          uuidGen     = stubStringUuidGenerator
          underTest   = CheckRudderGlobalInconsistentProperties(roParamRepo = pRepo, woParamRepo = pRepo, uuidGen = uuidGen)
          _           = underTest.checks()
          paramsMap  <- pRepo.paramsMap.get
        } yield assert(paramsMap("rudder"))(equalTo(globalInconsistentParameter))
      }
    )
  }
}

class CheckRudderGlobalInconsistentProperties(
    roParamRepo: RoParameterRepository,
    woParamRepo: WoParameterRepository,
    uuidGen:     StringUuidGenerator
) extends CheckRudderGlobalProperties(roParamRepo, woParamRepo, uuidGen) {
  override protected[consistency] val resource: String = "rudder-system-global-parameter-inconsistent.conf"
}

val stubStringUuidGenerator = new StringUuidGenerator {
  override def newUuid: String = UUID.randomUUID().toString
}

val jsonString      = {
  """{
    |   "log": {
    |     "syslog_facility":"NONE"
    |   },
    |   "packages": {
    |     "installed_cache_expire":60,
    |     "updates_cache_expire":240
    |   },
    |   "server": {
    |     "cf_serverd_bind_address":"::"
    |   }
    | }""".stripMargin
}
val globalParameter = GlobalParameter(config = {
  GenericProperty.toConfig(
    name = "rudder",
    rev = GitVersion.DEFAULT_REV,
    value = ConfigFactory.parseString(jsonString).root(),
    mode = None,
    provider = Some(PropertyProvider("system")),
    description = Some(
      "This parameter defines important properties for Rudder and must always be defined. You can't modify them directly, but you can override them by setting a group or node property named \"rudder\" with updated values."
    ),
    security = None,
    visibility = Some(Visibility.Displayed)
  )
})

val jsonErrorMessage            = {
  """{
    |   "compliance_expiration_policy": {
    |       "mode": "expire_immediately"
    |   }
    | }""".stripMargin
}
val globalInconsistentParameter = GlobalParameter(config = {
  GenericProperty.toConfig(
    name = "rudder",
    rev = GitVersion.DEFAULT_REV,
    value = ConfigFactory.parseString(jsonErrorMessage).root(),
    mode = None,
    provider = Some(PropertyProvider("system")),
    description = Some(
      "rudder system config"
    ),
    security = None,
    visibility = Some(Visibility.Displayed)
  )
})
