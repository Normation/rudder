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

package com.normation.inventory.domain

import enumeratum.*
import zio.json.*

/**
 * The enumeration holding the values for the agent
 */
sealed trait AgentType extends EnumEntry {
  /*
   * this is the default agent identifier, the main name, used in:
   * - in hooks: RUDDER_AGENT_TYPE: dsc (cfengine-community)
   * - in technique metadata: <AGENT type="dsc">...</AGENT> (cfengine-community)
   * - in serialisation
   */
  def id: String

  // yeah, you know, java
  final override def toString(): String = id

  /*
   * This is the old, short name, which used to be used in LDAP "agentName"
   * attribute and in (very old) fusion inventories (i.e: community, ...).
   */
  def oldShortName: String

  /*
   * - in User facing UI: "Rudder (Windows DSC)" ("CFEngine Community" => "Rudder (CFEngine Community)", "CFEngine Enterprise")
   */
  def displayName: String

  /*
   * - for policy generation: /var/rudder/share/xxxx/rules/dsc (/rules/cfengine-community, ...)
   */
  def toRulesPath: String

  /*
   * This is the list of <AGENTNAME> to look for in inventory and in LDAP
   * to choose the agent type.
   * - in inventory file: <AGENTNAME>dsc</AGENTNAME> ("Community" => "cfengine-community", ...)
   * - in LDAP agentName attribute
   * This is a set, because we want to accept renaming along the way.
   * Everything must be lower case in it.
   * It is most likely Set(id, oldShortName)
   */
  def inventoryAgentNames: Set[String]

  /*
   *  the name to look for in the inventory to know the agent version (when not reported in <AGENT><VERSION>)
   *  - for inventory software name (i.e package name in software): rudder-agent-dsc ("rudder-agent", ...)
   */
  def inventorySoftwareName: String
  // and a transformation function from reported software version name to agent version name, internal use only
  def toAgentVersionName(softwareVersionName: String): String

  // default policy file extension
  def defaultPolicyExtension: String
}

object AgentType extends Enum[AgentType] {
  implicit val codecAgentType: JsonCodec[AgentType] =
    JsonCodec.string.transformOrFail[AgentType](s => AgentType.fromValue(s).left.map(_.fullMsg), _.id)

  case object CfeCommunity extends AgentType {
    override def id           = "cfengine-community"
    override def oldShortName = "community"
    override def displayName  = "Rudder"
    override def toRulesPath  = "/cfengine-community"
    override def inventoryAgentNames: Set[String] = Set("cfengine-community", "community")
    override val inventorySoftwareName = "rudder-agent"
    override def toAgentVersionName(softwareVersionName: String) = softwareVersionName
    override val defaultPolicyExtension = ".cf"
  }

  case object Dsc extends AgentType {
    override def id           = "dsc"
    override def oldShortName = "dsc"
    override def displayName  = "Rudder Windows"
    override def toRulesPath  = "/dsc"
    override def inventoryAgentNames: Set[String] = Set("dsc")
    override val inventorySoftwareName = "Rudder agent (DSC)"
    override def toAgentVersionName(softwareVersionName: String) = softwareVersionName
    override val defaultPolicyExtension =
      "" // no extension - .ps1 extension is already in the template name (more by convention than anything else)
  }

  def values: IndexedSeq[AgentType] = findValues

  def fromValue(value: String): Either[InventoryError.AgentType, AgentType] = {
    // Check if the value is correct compared to the agent tag name (fusion > 2.3) or its toString value (added by CFEngine)
    def checkValue(agent: AgentType) = {
      agent.inventoryAgentNames.contains(value.toLowerCase)
    }

    values.find(checkValue) match {
      case None        => Left(InventoryError.AgentType(s"Wrong type of value for the agent '${value}'"))
      case Some(agent) => Right(agent)
    }
  }
}

/*
 * Version of the agent
 */
final case class AgentVersion(value: String) extends AnyVal

object AgentVersion {
  implicit val agentVersionCodec: JsonCodec[AgentVersion] = JsonCodec[String].transform(AgentVersion.apply, _.value)
}

final case class AgentInfo(
    agentType:     AgentType,
    // for now, the version must be an option, because we don't add it in the inventory
    // and must try to find it from packages
    version:       Option[AgentVersion],
    securityToken: SecurityToken,
    // agent capabilities are lower case string used as tags giving information about what agent can do
    capabilities:  Set[AgentCapability] = Set() // default value is needed for JSON decoding when "capabilities" is missing
)

object AgentInfo {
  // make capabilities sorted when written
  implicit val encoderSetAgentCapability: JsonEncoder[Set[AgentCapability]] =
    JsonEncoder.list[AgentCapability].contramap(_.toList.sortBy(_.value))
  implicit val codecAgentInfo:            JsonCodec[AgentInfo]              = DeriveJsonCodec.gen
}
