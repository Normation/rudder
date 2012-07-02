/*
*************************************************************************************
* Copyright 2012 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/
package com.normation.rudder.authorization

import com.normation.authorization._

/**
 * That file describe the UI available roles and bounded authorization. 
 * For now, they are kind of ad-hoc, but some of the logic 
 * could be generalized to build a full 
 */

// list of know rights

case object NoRights extends AuthorizationType { val id = "NO_RIGHTS" }
case class Read(name:String) extends AuthorizationType { val id = "%s_READ".format(name.toUpperCase()) }
case class Edit(name:String) extends AuthorizationType { val id = "%s_EDIT".format(name.toUpperCase()) }
case class Write(name:String) extends AuthorizationType { val id = "%s_WRITE".format(name.toUpperCase()) }
case object AnyRights extends AuthorizationType { val id = "ANY_RIGHTS" }

object AuthzToRights {

  /*
   * Authorization kind
   */
  val configurationkind = List("configuration","rule","directive","technique")
  val nodeKind = List("node","group")
  val allKind = "deployment"::"administration"::configurationkind ::: nodeKind

  /*
   * Authorization mapping
   */
  def toAuthz(auth:List[String], kind:String) =
    kind match {
    case "write" => auth.map(Write(_))
    case "read" =>  auth.map(Read(_))
    case "edit" =>  auth.map(Edit(_))
    case "all"  =>  auth.flatMap(kind => List(Read(kind),Write(kind),Edit(kind)))
    case _ => List(NoRights)
  }
  val toWriteAuthz = toAuthz(_:List[String],"write")
  val toReadAuthz = toAuthz(_:List[String],"read")
  val toEditAuthz = toAuthz(_:List[String],"edit")
  val toAllAuthz = toAuthz(_:List[String],"all")

  /*
   * Authorization parser
   */
  def parseRole(roles:Seq[String]):Rights = {
    new Rights(roles.flatMap(_ match {
      case "administrator"       => toAllAuthz (allKind)
      case "user"                => toAllAuthz (nodeKind ::: configurationkind)
      case "administration_only" => toAllAuthz (List("administration"))
      case "configuration"       => toAllAuthz (configurationkind)
      case "read_only"           => toReadAuthz (allKind)
      case "inventory"           => toReadAuthz (List("node","inventory"))
      case "rule_only"           => toReadAuthz (List("configuration","rule"))
      case role => List(parseAuthz(role))
    }): _*)
  }
    
  def parseAuthz(role : String) : AuthorizationType = {
	  val authz = """(.*)_(.*)""".r
     role.toLowerCase() match {
	     case "any" => AnyRights
       case authz(authType,kind)  if (allKind.contains(authType)) => toAuthz(List(authType),kind).firstOption.getOrElse(NoRights)
       case _ =>  NoRights
     }
  }
}