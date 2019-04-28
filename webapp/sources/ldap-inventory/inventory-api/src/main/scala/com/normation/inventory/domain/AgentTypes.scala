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

import com.normation.errors._
import scalaz.zio._
import scalaz.zio.syntax._

/**
 * The enumeration holding the values for the agent
 */
sealed trait AgentType {
  /*
   * this is the default agent identifier, the main name, used in:
   * - in hooks: RUDDER_AGENT_TYPE: dsc (cfengine-community, cfengine-nova)
   * - in technique metadata: <AGENT type="dsc">...</AGENT> (cfengine-community, cfengine-nova)
   * - in serialisation
   */
  def id: String

  //yeah, you know, java
  final override def toString() : String = id

  /*
   * This is the old, short name, which used to be used in LDAP "agentName"
   * attribute and in (very old) fusion inventory reports (i.e: community, nova).
   */
  def oldShortName: String

  /*
   * - in User facing UI: "Rudder (Windows DSC)" ("CFEngine Community" => "Rudder (CFEngine Community)", "CFEngine Enterprise")
   */
  def displayName: String

  /*
   * - for policy generation: /var/rudder/share/xxxx/rules/dsc (/rules/cfengine-community, /rules/cfengine-nova)
   */
  def toRulesPath: String

  /*
   * This is the list of <AGENTNAME> to look for in fusion inventory report of LDAP
   * to choose the agent type.
   * - in inventory report: <AGENTNAME>dsc</AGENTNAME> ("Community" => "cfengine-community", "Nova" => "cfengine-nova")
   * - in LDAP agentName attribute
   * This is a set, because we want to accept renaming along the way.
   * Everything must be lower case in it.
   * It is most likely Set(id, oldShortName)
   */
  def inventoryAgentNames: Set[String]

  /*
   *  the name to look for in the inventory to know the agent version (when not reported in <AGENT><VERSION>)
   *  - for inventory software name (i.e package name in software): rudder-agent-dsc ("rudder-agent", "cfengine nova")
   */
  def inventorySoftwareName: String
  // and a transformation function from reported software version name to agent version name, internal use only
  def toAgentVersionName(softwareVersionName: String): String

  // default policy file extension
  def defaultPolicyExtension: String

}

object AgentType {

  final case object CfeEnterprise extends AgentType {
    override def id           = "cfengine-nova"
    override def oldShortName = "nova"
    override def displayName  = "CFEngine Enterprise"
    override def toRulesPath  = "/cfengine-nova"
    override def inventoryAgentNames   = Set("cfengine-nova", "nova")
    override val inventorySoftwareName = "cfengine nova"
    override def toAgentVersionName(softwareVersionName: String) = s"cfe-${softwareVersionName}"
    override val defaultPolicyExtension =".cf"
  }

  final case object CfeCommunity extends AgentType {
    override def id           = "cfengine-community"
    override def oldShortName = "community"
    override def displayName  = "Rudder (CFEngine Community)"
    override def toRulesPath  = "/cfengine-community"
    override def inventoryAgentNames   = Set("cfengine-community", "community")
    override val inventorySoftwareName = "rudder-agent"
    override def toAgentVersionName(softwareVersionName: String) = softwareVersionName
    override val defaultPolicyExtension =".cf"
  }

  final case object Dsc extends AgentType {
    override def id           = "dsc"
    override def oldShortName = "dsc"
    override def displayName  = "Rudder (Windows DSC)"
    override def toRulesPath  = "/dsc"
    override def inventoryAgentNames   = Set("dsc")
    override val inventorySoftwareName = "Rudder agent (DSC)"
    override def toAgentVersionName(softwareVersionName: String) = softwareVersionName
    override val defaultPolicyExtension ="" // no extension - .ps1 extension is already in the template name (more by convention than anything else)
  }

  def allValues = ca.mrvisser.sealerate.values[AgentType]

  def fromValue(value : String) : Either[InventoryError.AgentType, AgentType] = {
    // Check if the value is correct compared to the agent tag name (fusion > 2.3) or its toString value (added by CFEngine)
    def checkValue( agent : AgentType) = {
      agent.inventoryAgentNames.contains(value.toLowerCase)
    }

    allValues.find(checkValue)  match {
      case None        => Left(InventoryError.AgentType(s"Wrong type of value for the agent '${value}'"))
      case Some(agent) => Right(agent)
    }
  }
}

/*
 * Version of the agent
 */
final case class AgentVersion(value: String)

final case class AgentInfo(
    agentType     : AgentType
    //for now, the version must be an option, because we don't add it in the inventory
    //and must try to find it from packages
  , version       : Option[AgentVersion]
  , securityToken : SecurityToken
)

object AgentInfoSerialisation {
  import net.liftweb.json.JsonDSL._
  import AgentType._

  import net.liftweb.json._

  implicit class ToJson(agent: AgentInfo) {

    def toJsonString =
      compactRender(
          ("agentType" -> agent.agentType.id)
        ~ ("version"   -> agent.version.map( _.value ))
        ~ ("securityToken" ->
              ("value" -> agent.securityToken.key)
            ~ ("type"  -> SecurityToken.kind(agent.securityToken))
          )
      )
  }

  def parseSecurityToken(agentType : AgentType, tokenJson: JValue, tokenDefault : Option[String]) : Either[InventoryError.SecurityToken, SecurityToken]= {
    import net.liftweb.json.compactRender

    def extractValue(tokenJson : JValue): Option[String] = {
      tokenJson \ "value" match {
        case JString(s) => Some(s)
        case _ => None
      }
    }
    def error(kind: String) = s"""Bad value defined for security token. Expected format is: "securityToken: {"type":"${kind}","value":"...."} """

    agentType match {
      case Dsc => tokenJson \ "type" match {
        case JString(Certificate.kind) => extractValue(tokenJson) match {
          case Some(token) => Right(Certificate(token))
          case None => Left(InventoryError.SecurityToken(error(Certificate.kind)))
        }
        case JString(PublicKey.kind) => Left(InventoryError.SecurityToken("Cannot have a public Key for dsc agent, only a certificate is valid"))
        case JNothing => Left(InventoryError.SecurityToken(error(Certificate.kind)))
        case invalidJson => Left(InventoryError.SecurityToken(s"Invalid value for security token, ${compactRender(invalidJson)}"))
      }
      case _ => tokenJson \ "type" match {
        case JString(Certificate.kind) => extractValue(tokenJson) match {
          case Some(token) => Right(Certificate(token))
          case None => Left(InventoryError.SecurityToken(error(Certificate.kind)))
        }
        case JString(PublicKey.kind) => extractValue(tokenJson) match {
          case Some(token) => Right(PublicKey(token))
          case None => Left(InventoryError.SecurityToken(error(PublicKey.kind)))
        }
        case invalidJson =>
          tokenDefault match {
            case Some(default) => Right(PublicKey(default))
            case None =>
              val error = invalidJson match {
                case JNothing => "no value define for security token"
                case x        => compactRender(invalidJson)
              }
              Left(InventoryError.SecurityToken(s"Invalid value for security token: ${error}, and no public key were stored"))
          }
      }
    }
  }

  /*
   * Retrieve the agent information from JSON. "agentType" is mandatory,
   * but version isn't, and even if we don't parse it correctly, we
   * successfully return an agent (without version).
   */
  def parseJson(s: String, optToken : Option[String]): IOResult[AgentInfo] = {
    for {
      json <- ZIO.effect { parse(s) } mapError  { ex => InventoryError.Deserialisation(s"Can not parse agent info: ${ex.getMessage }", ex) }
      agentType <- (json \ "agentType") match {
                     case JString(tpe) => IO.fromEither(AgentType.fromValue(tpe))
                     case JNothing => InventoryError.SecurityToken("No value defined for security token").fail
                     case invalidJson  => InventoryError.AgentType(s"Error when trying to parse string as JSON Agent Info (missing required field 'agentType'): ${compactRender(invalidJson)}").fail
                   }
     agentVersion = json \ "version" match {
                      case JString(version) => Some(AgentVersion(version))
                      case _                => None
                    }
     token <- IO.fromEither(json \ "securityToken" match {
                case JObject(json) => parseSecurityToken(agentType, json, optToken)
                case _             => parseSecurityToken(agentType, JNothing, optToken)
              })

    } yield {
      AgentInfo(agentType, agentVersion, token)
    }
  }

  /*
   * Parsing agent must be done in two steps for compat with old versions:
   * - try to parse in json: if ok, we have the new version
   * - else, try to parse in old format, put None to version.
   */
  def parseCompatNonJson(s: String, optToken : Option[String]): IOResult[AgentInfo] = {
    parseJson(s, optToken).catchAll { eb =>

        val jsonError = "Error when parsing JSON information about the agent type: " + eb.msg
        for {
          agentType <- IO.fromEither(AgentType.fromValue(s)).mapError ( e =>
               e.copy(e.msg + " <- " + s"Error when mapping '${s}' to an agent info. We are expecting either " +
                 s"an agentType with allowed values in ${AgentType.allValues.mkString(", ")}" +
                 s" or " +
                 s"a json like {'agentType': type, 'version': opt_version, 'securityToken': ...} but we get: ${jsonError}"
               ) )
          token  <- IO.fromEither(parseSecurityToken(agentType, JNothing, optToken))
        } yield {
          AgentInfo(agentType, None, token)
        }
    }
  }
}
