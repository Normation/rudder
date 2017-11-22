/*
*************************************************************************************
* Copyright 2012 Normation SAS
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
package com.normation.rudder

/**
 * Base class for Authorization types.
 * Common types are read, write, etc but the list is
 * open and implementations are free to add their semantic.
 *
 */
sealed trait AuthorizationType {

  /**
   * A string identifier of that authorization type
   * which may be use to transform string to that
   * type.
   */
  val id : String

}

case object AuthorizationType {

  case object NoRights  extends AuthorizationType { val id = "NO_RIGHTS" }
  case object AnyRights extends AuthorizationType { val id = "ANY_RIGHTS"}
  case class  Read(name:String)  extends AuthorizationType { val id = s"${name.toUpperCase()}_READ" }
  case class  Edit(name:String)  extends AuthorizationType { val id = s"${name.toUpperCase()}_EDIT" }
  case class  Write(name:String) extends AuthorizationType { val id = s"${name.toUpperCase()}_WRITE"}

}

/**
 * That class represents a set of AuthorizationType that
 * HAS TO all be validated at the same time. It acts like
 * a new AuthorizationType which melt each AuthorizationType
 * that composed it.
 */
class Rights(_authorizationTypes:AuthorizationType*) {

  require(null != _authorizationTypes && _authorizationTypes.nonEmpty, "At least one AuthorizationType must be include in a Rights object")

  val authorizationTypes = _authorizationTypes.toSet

  override lazy val hashCode = 23 * authorizationTypes.hashCode

  override def equals(other:Any) = other match {
    case that:Rights => this.authorizationTypes == that.authorizationTypes
    case _ => false
  }

}



object AuthzToRights {
  import AuthorizationType._

  /*
   * Authorization kind
   */
  val configurationKind = List("configuration","rule","directive","technique")
  val nodeKind = List("node","group")
  val workflowKind = List("validator", "deployer")
  val allKind = "deployment"::"administration"::configurationKind ::: nodeKind ::: workflowKind

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
      case "user"                => toAllAuthz (nodeKind ::: configurationKind)
      case "administration_only" => toAllAuthz (List("administration"))
      case "workflow"            => toAllAuthz (workflowKind) ::: toReadAuthz (configurationKind)
      case "deployer"            => toAllAuthz (List("deployer")) ::: toReadAuthz (nodeKind ::: configurationKind)
      case "validator"           => toAllAuthz (List("validator")) ::: toReadAuthz (nodeKind ::: configurationKind)
      case "configuration"       => toAllAuthz (configurationKind)
      case "read_only"           => toReadAuthz (allKind)
      case "compliance"          => toReadAuthz (nodeKind ::: configurationKind)
      case "inventory"           => toReadAuthz (List("node"))
      case "rule_only"           => toReadAuthz (List("configuration","rule"))
      case role => parseAuthz(role)
    }): _*)
  }

  def parseAuthz(role : String) : List[AuthorizationType] = {
    val authz = """(.*)_(.*)""".r
     role.toLowerCase() match {
       case "any" => List(AnyRights)
       case authz(authType,kind)  if (allKind.contains(authType)) => toAuthz(List(authType),kind)
       case _ =>  List(NoRights)
     }
  }
}
