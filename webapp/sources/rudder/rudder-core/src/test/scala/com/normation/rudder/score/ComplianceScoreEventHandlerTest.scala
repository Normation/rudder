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

package com.normation.rudder.score

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.reports.CompliancePercent
import com.normation.rudder.domain.reports.CompliancePrecision
import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.JUnitRunner
import zio.json.*
import zio.json.ast.*

/*
 * Test the cache behaviour
 */
@RunWith(classOf[JUnitRunner])
class ComplianceScoreEventHandlerTest extends Specification {

  implicit class ForceGet[A](either: Either[String, A]) {
    def forceGet = {
      either match {
        case Left(err)    => throw new IllegalArgumentException(s"should be ok in tests: ${err}")
        case Right(value) => value
      }
    }
  }

  s"check that the serializations is consistent since it's an API" >> {

    val n1    = NodeId("node1")
    val event = ComplianceScoreEvent(
      n1,
      CompliancePercent(pending = 17, success = 33, repaired = 21, error = 14, 0, 0, noAnswer = 25, 0, 0, 0, 0, 0, 0, 0)(
        CompliancePrecision.Level2
      )
    )

    val result = {
      """{
        |"applying":17.0,
        |"successAlreadyOK":33.0,
        |"successRepaired":21.0,
        |"error":14.0,
        |"noReport":25.0
        |}""".stripMargin.fromJson[Json].forceGet
    }

    ComplianceScoreEventHandler.handle(event) must beEqualTo(
      Right(List((n1, List(Score(ComplianceScore.scoreId, ScoreValue.C, "Compliance is between 50% and 80%", result)))))
    )
  }
}
