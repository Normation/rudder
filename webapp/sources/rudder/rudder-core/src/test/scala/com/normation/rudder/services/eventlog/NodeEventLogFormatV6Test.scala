/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.rudder.services.eventlog

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.SimpleDiff
import net.liftweb.common.Full
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.domain.nodes.GenericProperty._
import com.normation.rudder.domain.nodes.ModifyNodeDiff
import com.normation.rudder.domain.nodes.NodeProperty
import com.normation.rudder.reports.HeartbeatConfiguration
import scala.collection.mutable.ArrayBuffer
import com.normation.rudder.domain.eventlog.EventTypeFactory
import com.normation.rudder.domain.eventlog.ModifyNodeEventType

/**
 *
 * Test that node event logs from Rudder 3.2
 * are correctly read by eventlog in 4.0
 * (because we merged all node "modify" events
 * into one)
 *
 */
@RunWith(classOf[JUnitRunner])
class NodeEventLogFormatV6Test extends Specification {

  // we only want to unserialize node properties - all these null, a piece of beauty
  val eventDetails = new EventLogDetailsServiceImpl(null, null, null, null, null, null, null, null)

  val event_32_NodeHeartbeatModified = <entry><node changeType="modify" fileFormat="6">
    <id>59e66fe7-7f0e-497d-8055-a103116b4a08</id>
    <heartbeat><from/><to><override>true</override><period>20</period></to></heartbeat>
  </node></entry>

  val nodeHeartbeatModified = ModifyNodeDiff(NodeId("59e66fe7-7f0e-497d-8055-a103116b4a08")
          , modHeartbeat  = Some(SimpleDiff(
                              None
                            , Some(HeartbeatConfiguration(true, 20))
                            ))
          , modAgentRun   = None
          , modProperties = None
          , modPolicyMode = None
          , modKeyValue   = None
          , modKeyStatus  = None
        )

  val event_32_NodePropertiesModified = <entry><node changeType="modify" fileFormat="6">
    <id>root</id>
    <properties>
      <from><property><name>env_type</name><value>production</value></property><property><name>shell</name><value>/bin/sh</value></property></from>
      <to>
        <property><name>shell</name><value>/bin/sh</value></property><property><name>env</name><value>PROD</value></property>
        <property><name>datacenter</name><value>{{"Europe":{{"France":true}}}}</value></property>
      </to>
    </properties>
  </node></entry>

  val nodePropertiesModified = ModifyNodeDiff(NodeId("root")
          , modHeartbeat  = None
          , modAgentRun   = None
          , modProperties = Some(SimpleDiff(
                              ArrayBuffer(
                                NodeProperty("env_type", "production".toConfigValue, None)
                              , NodeProperty("shell", "/bin/sh".toConfigValue, None)
                              ).toList
                            , ArrayBuffer(
                                NodeProperty("shell", "/bin/sh".toConfigValue, None)
                              , NodeProperty("env", "PROD".toConfigValue, None)
                              , NodeProperty.parse("datacenter", """{"Europe":{"France":true}}""", None).fold(
                                  err => throw new IllegalArgumentException("Error in test: " + err.fullMsg)
                                , res => res
                              )).toList
                            ))
          , modPolicyMode = None
          , modKeyValue   = None
          , modKeyStatus  = None
        )


  val event_32_NodeAgentRunPeriodModified = <entry><node changeType="modify" fileFormat="6">
    <id>dd702a09-6cf9-4d22-9f2d-a6e1f1df63ea</id>
    <agentRun>
      <from><override></override><interval>15</interval><startMinute>1</startMinute><startHour>0</startHour><splaytime>5</splaytime></from>
      <to><override></override><interval>10</interval><startMinute>1</startMinute><startHour>0</startHour><splaytime>5</splaytime></to>
    </agentRun>
  </node></entry>

  val nodeAgentRunPeriodModified = ModifyNodeDiff(NodeId("dd702a09-6cf9-4d22-9f2d-a6e1f1df63ea")
          , modHeartbeat  = None
          , modAgentRun   = Some(SimpleDiff(
                              Some(AgentRunInterval(None, 15, 1, 0, 5))
                            , Some(AgentRunInterval(None, 10, 1, 0, 5))
                            ))
          , modProperties = None
          , modPolicyMode = None
          , modKeyValue   = None
          , modKeyStatus  = None
        )


  "Current EventTypeFactory" should {
    "identify NodeHeartbeatModified as NodeModified event" in {
      EventTypeFactory("NodeHeartbeatModified") must beEqualTo(ModifyNodeEventType)
    }
    "identify NodePropertiesModified as NodeModified event" in {
      EventTypeFactory("NodePropertiesModified") must beEqualTo(ModifyNodeEventType)
    }
    "identify NodeAgentRunPeriodModified as NodeModified event" in {
      EventTypeFactory("NodeAgentRunPeriodModified") must beEqualTo(ModifyNodeEventType)
    }
    "identify NodeModified as NodeModified event" in {
      EventTypeFactory("NodeModified") must beEqualTo(ModifyNodeEventType)
    }
  }

  "Current eventlog reader" should {
    "correctly read past agent run modification" in {
      eventDetails.getModifyNodeDetails(event_32_NodeAgentRunPeriodModified) must beEqualTo(
        Full(nodeAgentRunPeriodModified)
      )
    }

    "correctly read past heartbeat modification" in {
      eventDetails.getModifyNodeDetails(event_32_NodeHeartbeatModified) must beEqualTo(
        Full(nodeHeartbeatModified)
      )
    }

    "correctly read past node properties modification" in {
      eventDetails.getModifyNodeDetails(event_32_NodePropertiesModified) must beEqualTo(
        Full(nodePropertiesModified)
      )
    }
  }
}
