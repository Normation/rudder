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

package com.normation.rudder.services.reports

import com.normation.errors.PureResult
import com.normation.inventory.domain.NodeId
import com.normation.rudder.MockGlobalParam
import com.normation.rudder.MockNodeGroups
import com.normation.rudder.MockNodes
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.domain.properties.PropertyProvider
import com.normation.rudder.domain.reports.NodeComplianceExpiration
import com.normation.rudder.domain.reports.NodeComplianceExpirationMode
import com.normation.rudder.facts.nodes.ChangeContext
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.services.reports.NodePropertyBasedComplianceExpirationService.PROP_SUB_NAME
import com.normation.zio.*
import com.softwaremill.quicklens.*
import java.util.concurrent.TimeUnit
import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.JUnitRunner
import zio.*

/*
 * Test the cache behaviour
 */
@RunWith(classOf[JUnitRunner])
class ComplianceExpirationServiceTest extends Specification {
  implicit class ForceGetOpt[A](a: Option[A]) {
    def force: A = {
      a match {
        case Some(v) => v
        case None    => throw new RuntimeException(s"Can't extract a value from none in test")
      }
    }
  }

  import NodePropertyBasedComplianceExpirationService.getPolicyFromProp

  implicit class ForceGet[A](r: PureResult[A]) {
    def force = {
      r match {
        case Left(value)  => throw new IllegalArgumentException(s"Error in test: result is left: ${value}")
        case Right(value) => value
      }
    }
  }

  val rudder_ok1:  NodeProperty = NodeProperty
    .parse(
      "rudder",
      s"""{"${NodePropertyBasedComplianceExpirationService.PROP_SUB_NAME}": { "mode":"keep_last", "duration":"1 hour"}}""",
      None,
      Some(PropertyProvider.systemPropertyProvider)
    )
    .force
  val rudder_ok2:  NodeProperty = NodeProperty
    .parse(
      "rudder",
      s"""{"${NodePropertyBasedComplianceExpirationService.PROP_SUB_NAME}": { "mode":"keep_last", "duration":"1 hour"}}""",
      None,
      None
    )
    .force
  val rudder_ok3:  NodeProperty = NodeProperty
    .parse(
      "rudder",
      s"""{"${NodePropertyBasedComplianceExpirationService.PROP_SUB_NAME}": { "mode":"expire_immediately"}}""",
      None,
      None
    )
    .force
  val rudder_nok1: NodeProperty = NodeProperty
    .parse(
      "rudder",
      s"""{ "bad": {"${NodePropertyBasedComplianceExpirationService.PROP_SUB_NAME}": { "mode": "keep_last", "duration":"1 hour"}}}""",
      None,
      Some(PropertyProvider.systemPropertyProvider)
    )
    .force
  val rudder_nok2: NodeProperty =
    NodeProperty.parse("rudder", """{ "bad": "bad"}""", None, Some(PropertyProvider.systemPropertyProvider)).force
  val rudder_nok3: NodeProperty = NodeProperty
    .parse(
      "not_rudder",
      s"""{"${NodePropertyBasedComplianceExpirationService.PROP_SUB_NAME}": { "mode":"keep_last", "duration":"1 hour"}}""",
      None,
      Some(PropertyProvider.systemPropertyProvider)
    )
    .force

  s"When ${NodePropertyBasedComplianceExpirationService.PROP_SUB_NAME} is set we should find it" >> {
    val keep1h =
      NodeComplianceExpiration(NodeComplianceExpirationMode.KeepLast, Some(scala.concurrent.duration.Duration(1, TimeUnit.HOURS)))

    (getPolicyFromProp(
      Chunk(rudder_ok1),
      "rudder",
      s"${NodePropertyBasedComplianceExpirationService.PROP_SUB_NAME}",
      NodeId("node1")
    ) must beEqualTo(
      keep1h
    )) and (getPolicyFromProp(
      Chunk(rudder_ok2),
      "rudder",
      s"${NodePropertyBasedComplianceExpirationService.PROP_SUB_NAME}",
      NodeId("node1")
    ) must beEqualTo(
      keep1h
    )) and (getPolicyFromProp(
      Chunk(rudder_ok3),
      "rudder",
      s"${NodePropertyBasedComplianceExpirationService.PROP_SUB_NAME}",
      NodeId("node1")
    ) must beEqualTo(
      NodeComplianceExpiration.default
    ))
  }

  "Expire immediately in other cases " >> {

    getPolicyFromProp(
      Chunk(rudder_nok1, rudder_nok2, rudder_nok3),
      "rudder",
      s"${NodePropertyBasedComplianceExpirationService.PROP_SUB_NAME}",
      NodeId("node1")
    ) must beEqualTo(
      NodeComplianceExpiration.default
    )

  }

  "Find a property from the mock repos" >> {
    // for correct init of propRepo, we need param, node and group
    val mockNodes         = new MockNodes
    val mockGroup         = new MockNodeGroups(mockNodes, new MockGlobalParam)
    val expirationService = new NodePropertyBasedComplianceExpirationService(
      mockNodes.propRepo,
      NodePropertyBasedComplianceExpirationService.PROP_NAME,
      PROP_SUB_NAME
    )
    val expirationPolicyProp: NodeProperty = NodeProperty
      .parse("rudder", """{"compliance_expiration_policy" : { "mode":"keep_last", "duration":"1 hour"}}""", None, None)
      .getOrElse(throw new RuntimeException(s"For test"))

    val nodeId1: NodeId = NodeId("node1")
    val res = (for {
      n1 <- mockNodes.nodeFactRepo.get(nodeId1)(using QueryContext.testQC)
      up  = n1.force.modify(_.properties).using(ps => ps.appended(expirationPolicyProp))
      _  <- mockNodes.nodeFactRepo.save(up)(using ChangeContext.newForRudder())
      _  <- mockGroup.propService.updateAll()
      v  <- expirationService.getExpirationPolicy(List(nodeId1))
    } yield v).runNow

    res === Map(
      nodeId1 -> NodeComplianceExpiration(NodeComplianceExpirationMode.KeepLast, Some(Duration(1, TimeUnit.HOURS).asScala))
    )
  }
}
