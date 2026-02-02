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

package com.normation.rudder.repository.ldap

import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.ldap.sdk.GeneralizedTime
import com.normation.rudder.domain.policies.AcceptationDateTime
import com.normation.rudder.reports.AgentRunInterval
import java.time.Instant
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.json.*

/**
 * A test to check that the Normation OID is not defined in
 * rudder.schema.
 *
 * We let-it in that file to be able the generate derived
 * schema (for example for UnboundID DS) from it
 */
@RunWith(classOf[JUnitRunner])
class LDAPEntityMapperTest extends Specification {

  "Agent schedule regarding json serialisation" should {

    "be able to read LDAP format without splay hour/min" in {

      val json1 = """{"overrides":true,"interval":20,"startMinute":11,"startHour":0,"splaytime":8}"""
      val i1    = AgentRunInterval(Some(true), 20, 11, 0, 8)
      val json2 = """{"overrides":null,"interval":13,"startMinute":42,"startHour":4,"splaytime":8}"""
      val i2    = AgentRunInterval(None, 13, 42, 4, 8)

      (json1.fromJson[AgentRunInterval] must beRight(i1)) and
      (json2.fromJson[AgentRunInterval] must beRight(i2))
    }
  }

  "active technique acceptationTimestamp map" >> {
    implicit class GetGT(s: String) {
      def getGT: Instant = {
        GeneralizedTime.parse(s) match {
          case Some(gt) => gt.instant
          case None     => throw new IllegalArgumentException(s"Can not parse GeneralizedTime from: ${s}")
        }
      }
    }

    implicit class GetTV(s: String) {
      def getTV: TechniqueVersion = {
        TechniqueVersion.parse(s) match {
          case Left(err) => throw new IllegalArgumentException(s"Can not parse technique version from '${s}': ${err}")
          case Right(tv) => tv
        }
      }
    }

    val json =
      """{"2.1":"20151023163856.291Z","4.0":"20180118172119.866Z","2.0":"20150121155441.663Z","4.1":"20220125202025.198Z"}"""
    val map  = AcceptationDateTime(
      Map(
        "2.1".getTV -> "20151023163856.291Z".getGT,
        "4.0".getTV -> "20180118172119.866Z".getGT,
        "2.0".getTV -> "20150121155441.663Z".getGT,
        "4.1".getTV -> "20220125202025.198Z".getGT
      )
    )
    (json.fromJson[AcceptationDateTime] must beRight(map)) and
    (map.toJson === json)
  }

}
