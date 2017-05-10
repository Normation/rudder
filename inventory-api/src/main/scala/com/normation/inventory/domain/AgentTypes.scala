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

import InventoryConstants._
import net.liftweb.common._
import com.normation.utils.HashcodeCaching

/**
 * The enumeration holding the values for the agent
 *
 */
sealed abstract class AgentType {
  def toString() : String
  def fullname() : String = "CFEngine "+this
  // Tag used in fusion inventory ( > 2.3 )
  lazy val tagValue = s"cfengine-${this}".toLowerCase
  def toRulesPath() : String

  // the name to look for in the inventory to know the agent version
  def inventorySoftwareName: String
  // and a transformation function from reported software version name to agent version name
  def toAgentVersionName(softwareVersionName: String): String
}

final case object NOVA_AGENT extends AgentType with HashcodeCaching {
  override def toString() = A_NOVA_AGENT
  override def toRulesPath() = "/cfengine-nova"
  override val inventorySoftwareName = "cfengine nova"
  override def toAgentVersionName(softwareVersionName: String) = s"cfe-${softwareVersionName}"
}

final case object COMMUNITY_AGENT extends AgentType with HashcodeCaching {
  override def toString() = A_COMMUNITY_AGENT
  override def toRulesPath() = "/cfengine-community"
  override val inventorySoftwareName = "rudder-agent"
  override def toAgentVersionName(softwareVersionName: String) = softwareVersionName
}

object AgentType {
  def allValues = NOVA_AGENT :: COMMUNITY_AGENT :: Nil

  def fromValue(value : String) : Box[AgentType] = {
    // Check if the value is correct compared to the agent tag name (fusion > 2.3) or its toString value (added by CFEngine)
    def checkValue( agent : AgentType) = {
      value.toLowerCase == agent.toString.toLowerCase || value.toLowerCase == agent.tagValue.toLowerCase
    }

    allValues.find(checkValue)  match {
      case None => Failure(s"Wrong type of value for the agent '${value}'")
      case Some(agent) => Full(agent)
    }
  }
}

/*
 * Version of the agent
 */
final case class AgentVersion(value: String)


final case class AgentInfo(
    name   : AgentType
    //for now, the version must be an option, because we don't add it in the inventory
    //and must try to find it from packages
  , version: Option[AgentVersion]
)


object AgentInfoSerialisation {
  import net.liftweb.json.JsonDSL._
  import net.liftweb.json._

  implicit class ToJson(agent: AgentInfo) {

    def toJsonString = compactRender(
        ("agentType" -> agent.name.toString())
      ~ ("version"   -> agent.version.map( _.value ))
    )
  }

  /*
   * Retrieve the agent information from JSON. "agentType" is mandatory,
   * but version isn't, and even if we don't parse it correctly, we
   * successfully return an agent (without version).
   */
  def parseJson(s: String): Box[AgentInfo] = {
    for {
      json <- try { Full(parse(s)) } catch { case ex: Exception => Failure(s"Can not parse agent info: ${ex.getMessage }", Full(ex), Empty) }
      info <- (json \ "agentType") match {
                case JString(tpe) => AgentType.fromValue(tpe).map { agentType =>
                  (json \ "version") match {
                    case JString(version) => AgentInfo(agentType, Some(AgentVersion(version)))
                    case _                => AgentInfo(agentType, None)
                  }
                }
                case _ => Failure(s"Error when trying to parse string as JSON Agent Info (missing required field 'agentType'): ${s}")
              }
    } yield {
      info
    }
  }

  /*
   * Parsing agent must be done in two steps for compat with old versions:
   * - try to parse in json: if ok, we have the new version
   * - else, try to parse in old format, put None to version.
   */
  def parseCompatNonJson(s: String): Box[AgentInfo] = {
    parseJson(s).or(
      for {
        tpe <- AgentType.fromValue(s) ?~! (
                 s"Error when mapping '${s}' to an agent info. We are expecting either a json like "+
             s"{'agentType': type, 'version': opt_version}, or an agentType with allowed values in ${AgentType.allValues.mkString(", ")}"
               )
      } yield {
        AgentInfo(tpe, None)
      }
    )
  }
}


