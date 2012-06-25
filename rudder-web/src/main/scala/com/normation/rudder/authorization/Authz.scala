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
 * 
 */
case object NodeRead extends AuthorizationType { val id = "NODE_READ" }

case class Read(name:String) extends AuthorizationType { val id = "%s_READ".format(name.toUpperCase()) }

case object Administration extends AuthorizationType { val id = "ADMINISTRATION" }

case class Edit(name:String) extends AuthorizationType { val id = "%s_EDIT".format(name.toUpperCase()) }

case class Write(name:String) extends AuthorizationType { val id = "%s_WRITE".format(name.toUpperCase()) }

case object NodeWrite extends AuthorizationType { val id = "NODE_WRITE" }

case object NoRights extends AuthorizationType { val id = "NO_RIGHTS" }

case object AnyRights extends AuthorizationType { val id = "ANY_RIGHTS" }

object AuthztoRights {
  
  val configurationkind = List("configuration","rule","directive","technique")
  val authKind = List("group","inventory"):::configurationkind
  val allWrite = authKind.map(Write(_))
  val allRead  = authKind.map(Read(_))
  val allEdit  = authKind.map(Edit(_))
  def parseRole(roles:Seq[String]):Rights = {
    new Rights(roles.flatMap(_ match {
      case "administrator" => List(Administration,NodeRead,NodeWrite) ::: allWrite ::: allRead ::: allEdit
      case "administration_only" => List(Administration)
      case "read_only" => List(Administration,NodeRead) ::: allRead 
      case "user" => List(NodeRead,NodeWrite) ::: allWrite ::: allRead ::: allEdit
      case "inventory" =>  List(NodeRead)
      case "configuration" => configurationkind.flatMap(kind => List(Read(kind),Write(kind),Edit(kind)))
      case "rule_only" => List(Read("configuration"),Read("rule"))
      case role => List(parseAuthz(role))
    }): _*)
  }
    
  def parseAuthz(role : String) : AuthorizationType = {
	  val read = """(.*)_read""".r
	  val write = """(.*)_write""".r
	  val edit = """(.*)_edit""".r
     role.toLowerCase() match {
	     case "any" => AnyRights
       case "node_read" => NodeRead
       case "node_write" => NodeWrite
       case read(kind)  if (authKind.contains(kind)) => Read(kind) 
       case write(kind) if (authKind.contains(kind)) => Write(kind) 
       case edit(kind)  if (authKind.contains(kind)) => Edit(kind)
       case "administration" => Administration
       case _ =>  NoRights
     }
  }
}