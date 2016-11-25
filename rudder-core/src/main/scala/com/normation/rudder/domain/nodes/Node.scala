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

package com.normation.rudder.domain.nodes

import com.normation.inventory.domain.FullInventory

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.SimpleDiff
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.reports.HeartbeatConfiguration
import com.normation.rudder.reports.ReportingConfiguration
import com.normation.utils.HashcodeCaching

import org.joda.time.DateTime
import com.normation.rudder.domain.policies.SimpleDiff
import com.normation.inventory.domain.FullInventory
import net.liftweb.json.JsonAST.JValue
import net.liftweb.json.JsonAST.JString
import net.liftweb.json.JsonParser.ParseException

/**
 * The entry point for a REGISTERED node in Rudder.
 *
 * This is independant from inventory, and can exist without one.
 *
 */
case class Node(
    id                        : NodeId
  , name                      : String
  , description               : String
  , isBroken                  : Boolean
  , isSystem                  : Boolean
  , isPolicyServer            : Boolean
  , creationDate              : DateTime
  , nodeReportingConfiguration: ReportingConfiguration
  , properties                : Seq[NodeProperty]
  , policyMode                : Option[PolicyMode]
) extends HashcodeCaching

case object Node {
  def apply (inventory : FullInventory) : Node = {
    Node(
        inventory.node.main.id
      , inventory.node.main.hostname
      , inventory.node.description.getOrElse("")
      , false
      , false
      , false
      , inventory.node.inventoryDate.getOrElse(new DateTime(0))
      , ReportingConfiguration(None,None)
      , Seq()
      , None
    )
  }
}

case class NodeProperty(name: String, value: JValue) {
  def renderValue: String = value match {
    case JString(s) => s
    case v          => net.liftweb.json.compactRender(v)
  }
}

object NodeProperty {

  import net.liftweb.json.parse
  import net.liftweb.json.JsonAST.{JNothing, JString}

  /**
   * A builder with the logic to handle the value part.
   *
   * For compatibity reason, we want to be able to process
   * empty (JNothing) and primitive types, especially string, specificaly as
   * a JString *but* a string representing and actual JSON should be
   * used as json.
   */
  def apply(name: String, value: String): NodeProperty = {
    try {
      val v = parse(value) match {
        case JNothing => JString("")
        case json     => json
      }
      NodeProperty(name, v)
    } catch {
      case ex: ParseException =>
        // in that case, we didn't had a valid json top-level structure,
        // i.e either object or array. Use a JString with the content
        NodeProperty(name, JString(value))
    }
  }
}


object CompareProperties {
  /**
   * Update a set of properties with the map:
   * - if a key of the map matches a property name,
   *   use the map value for the key as value for
   *   the property
   * - if the value is the emtpy string, remove
   *   the property
   */
  def updateProperties(props: Seq[NodeProperty], updates: Option[Seq[NodeProperty]]) = {
    updates match {
      case None => props
      case Some(u) =>
        val values = u.map { case NodeProperty(k, v) => (k, v)}.toMap
        val existings = props.map(_.name).toSet
        //for news values, don't keep empty
        val news = (values -- existings).collect { case(k,v) if(v != JString("")) => NodeProperty(k,v) }
        props.flatMap { case p@NodeProperty(name, value)  =>
          values.get(name) match {
            case None              => Some(p)
            case Some(JString("")) => None
            case Some(x)           => Some(NodeProperty(name, x))
          }
        } ++ news
    }
  }

}

/**
 * Node diff for event logs:
 * Change
 * - heartbeat frequency
 * - run interval
 * - properties
 *
 * For now, other simple properties are not handle.
 */

sealed trait NodeDiff

/**
 * Denote a change on the heartbeat frequency.
 */
object ModifyNodeHeartbeatDiff{
  def apply(id: NodeId,  modHeartbeat: Option[SimpleDiff[Option[HeartbeatConfiguration]]]) = ModifyNodeDiff(id,modHeartbeat, None, None, None)
}

/**
 * Diff on a change on agent run period
 */
object ModifyNodeAgentRunDiff{
  def apply(id: NodeId, modAgentRun: Option[SimpleDiff[Option[AgentRunInterval]]]) = ModifyNodeDiff(id,None,modAgentRun, None, None)
}

/**
 * Diff on the list of properties
 */
object ModifyNodePropertiesDiff{
  def apply(id: NodeId, modProperties: Option[SimpleDiff[Seq[NodeProperty]]]) = ModifyNodeDiff(id,None,None, modProperties, None)
}

/**
 * Diff on the list of properties
 */
final case class ModifyNodeDiff(
    id           : NodeId
  , modHeartbeat : Option[SimpleDiff[Option[HeartbeatConfiguration]]]
  , modAgentRun  : Option[SimpleDiff[Option[AgentRunInterval]]]
  , modProperties: Option[SimpleDiff[Seq[NodeProperty]]]
  , modPolicyMode: Option[SimpleDiff[Option[PolicyMode]]]
)

object ModifyNodeDiff {
  def apply(oldNode : Node, newNode : Node) : ModifyNodeDiff = {
    val policy     = if (oldNode.policyMode == newNode.policyMode) None else Some(SimpleDiff(oldNode.policyMode,newNode.policyMode))
    val properties = if (oldNode.properties.toSet == newNode.properties.toSet) None else Some(SimpleDiff(oldNode.properties,newNode.properties))
    val agentRun   = if (oldNode.nodeReportingConfiguration.agentRunInterval == newNode.nodeReportingConfiguration.agentRunInterval) None else Some(SimpleDiff(oldNode.nodeReportingConfiguration.agentRunInterval,newNode.nodeReportingConfiguration.agentRunInterval))
    val heartbeat  = if (oldNode.nodeReportingConfiguration.heartbeatConfiguration == newNode.nodeReportingConfiguration.heartbeatConfiguration) None else Some(SimpleDiff(oldNode.nodeReportingConfiguration.heartbeatConfiguration,newNode.nodeReportingConfiguration.heartbeatConfiguration))

    ModifyNodeDiff(newNode.id,heartbeat,agentRun,properties,policy)
  }
}

/**
 * The part dealing with JsonSerialisation of node related
 * attributes (especially properties)
 */
object JsonSerialisation {

  import net.liftweb.json._
  import net.liftweb.json.JsonDSL._

  implicit class JsonNodeProperty(x: NodeProperty) {
    def toLdapJson(): JObject = (
        ( "name"  , x.name  )
      ~ ( "value" , x.value )
    )
  }

  implicit class JsonNodeProperties(props: Seq[NodeProperty]) {
    implicit val formats = DefaultFormats

    private[this] def json(x: NodeProperty): JObject = (
        ( "name"  , x.name  )
      ~ ( "value" , x.value )
    )

    def dataJson(x: NodeProperty) : JField = {
      JField(x.name, x.value)
    }

    def toApiJson(): JArray = {
      JArray(props.map(json(_)).toList)
    }

    def toDataJson(): JObject = {
      props.map(dataJson(_)).toList.sortBy { _.name }
    }
  }

  def unserializeLdapNodeProperty(value:String): NodeProperty = {
    import net.liftweb.json.JsonParser._
    implicit val formats = DefaultFormats

    parse(value).extract[NodeProperty]
  }

}
