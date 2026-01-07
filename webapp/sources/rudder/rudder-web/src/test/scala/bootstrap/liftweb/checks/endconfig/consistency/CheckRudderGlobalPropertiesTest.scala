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
import com.normation.utils.StringUuidGenerator
import com.typesafe.config.ConfigValueFactory
import zio.test.Assertion.equalTo
import zio.test.{Spec, TestEnvironment, ZIOSpecDefault, assert}
import zio.{Ref, Scope}

import java.util.UUID

object CheckRudderGlobalPropertiesTest extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment & Scope, Any] = {
    suite("CheckRudderGlobalProperties")(test("""it should make some test
                                                        |""".stripMargin) {
      for {
        counterRef <- Ref.make(0)
        pRepo = new MockGlobalParam().paramsRepo
        uuidGen   = new MockStringUuidGenerator
        underTest = CheckRudderGlobalProperties(roParamRepo = pRepo, woParamRepo = pRepo, uuidGen = uuidGen)
        _         = underTest.checks()
        paramsMap <- pRepo.paramsMap.get
      } yield assert(paramsMap.get("rudder").get)(equalTo(Map("rudder" -> globalParameter)))
    })
  }
}

/*class MockRoParameterRepository extends RoParameterRepository {

  override def getGlobalParameter(parameterName: String): IOResult[Option[GlobalParameter]] = {
    println(parameterName)
    ZIO.succeed(None)
    /*ZIO.succeed(
      Some(
        GlobalParameter(config = {
          GenericProperty.toConfig(
            name = "rudder",
            rev = Revision("rev"),
            value = ConfigValueFactory.fromAnyRef("value"),
            mode = None,
            provider = Some(PropertyProvider("system")),
            description = Some("description"),
            visibility = Some(Visibility.Displayed))
        })
      )
    )*/
  }

  override def getAllGlobalParameters(): IOResult[Seq[GlobalParameter]] = ZIO.succeed(Seq())



}

class MockWoParameterRepository(counterRef: Ref[Int]) extends WoParameterRepository {

  override def saveParameter(
      parameter: GlobalParameter,
      modId:     ModificationId,
      actor:     EventActor,
      reason:    Option[String]
  ): IOResult[AddGlobalParameterDiff] = {
    println(reason)
    ZIO.succeed(AddGlobalParameterDiff(globalParameterStub))
  }

  override def updateParameter(
      parameter: GlobalParameter,
      modId:     ModificationId,
      actor:     EventActor,
      reason:    Option[String]
  ): IOResult[Option[ModifyGlobalParameterDiff]] = ZIO.succeed(None)

  override def delete(
      parameterName: String,
      provider:      Option[PropertyProvider],
      modId:         ModificationId,
      actor:         EventActor,
      reason:        Option[String]
  ): IOResult[Option[DeleteGlobalParameterDiff]] = ZIO.succeed(None)

  override def swapParameters(newParameters: Seq[GlobalParameter]): IOResult[ParameterArchiveId] = ZIO.succeed(ParameterArchiveId("ParameterArchiveId"))

  override def deleteSavedParametersArchiveId(saveId: ParameterArchiveId): IOResult[Unit] = ZIO.succeed(())
}*/

class MockStringUuidGenerator extends StringUuidGenerator {

  override def newUuid: String = UUID.randomUUID().toString
}

val globalParameter = GlobalParameter(config = {
  GenericProperty.toConfig(
    name = "rudder",
    rev = GitVersion.DEFAULT_REV,

    // GenericProperty.parseValue("""{"a":"b"}""") must beRight(ConfigValueFactory.fromMap(jmap(("a", "b"))))
    // ConfigValueFactory.fromMap(jmap(("log",jmap(("syslog_facility","NONE"),("packages", (("installed_cache_expire", 60), ("updates_cache_expire",240),("server", jmap(("cf_serverd_bind_address","::"))))))))),

    value = ConfigValueFactory.fromAnyRef("""{"log":{"syslog_facility":"NONE"},"packages":{"installed_cache_expire":60,"updates_cache_expire":240},"server":{"cf_serverd_bind_address":"::"}}"""),
    mode = None,
    provider = Some(PropertyProvider("system")),
    description = Some("This parameter defines important properties for Rudder and must always be defined. You can't modify them directly, but you can override them by setting a group or node property named \"rudder\" with updated values."),
    visibility = Some(Visibility.Displayed))
})
