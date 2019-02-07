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
  def id: String = authzKind + "_" + action

  def authzKind: String
  def action   : String
}

sealed trait ActionType {
  def action  : String
}

/*
 * Different action types for authorization.
 * Read is to get information.
 * Write is for creation / deletion of objects
 * Edit is for update of one existing object
 */
object ActionType {
  trait Read  extends ActionType { def action = "read"  }
  trait Write extends ActionType { def action = "write" }
  trait Edit  extends ActionType { def action = "edit"  }
}

sealed trait Administration extends AuthorizationType { def authzKind = "administration" }
sealed trait Compliance     extends AuthorizationType { def authzKind = "compliance"     }
sealed trait Configuration  extends AuthorizationType { def authzKind = "configuration"  }
sealed trait Deployer       extends AuthorizationType { def authzKind = "deployer"       }
sealed trait Deployment     extends AuthorizationType { def authzKind = "deployment"     }
sealed trait Directive      extends AuthorizationType { def authzKind = "directive"      }
sealed trait Group          extends AuthorizationType { def authzKind = "group"          }
sealed trait Node           extends AuthorizationType { def authzKind = "node"           }
sealed trait Rule           extends AuthorizationType { def authzKind = "rule"           }
sealed trait Technique      extends AuthorizationType { def authzKind = "technique"      }
sealed trait UserAccount    extends AuthorizationType { def authzKind = "userAccount"    }
sealed trait Validator      extends AuthorizationType { def authzKind = "validator"      }

final object AuthorizationType {


  final case object NoRights  extends AuthorizationType { val authzKind = "no"  ; val action = "rights" }
  final case object AnyRights extends AuthorizationType { val authzKind = "any" ; val action = "rights" }

    // can't use sealerate here: "knownDirectSubclasses of observed before subclass ... registered"
    // I'm not sure exactly how/why it bugs. It's only for object where ".values" is used
    // in Role.

  final case object Administration {
    final case object Read  extends Administration with ActionType.Read  with AuthorizationType
    final case object Edit  extends Administration with ActionType.Edit  with AuthorizationType
    final case object Write extends Administration with ActionType.Write with AuthorizationType
    def values = Set(Read, Edit, Write)
  }
  final case object Compliance {
    final case object Read  extends Compliance with ActionType.Read  with AuthorizationType
    final case object Edit  extends Compliance with ActionType.Edit  with AuthorizationType
    final case object Write extends Compliance with ActionType.Write with AuthorizationType
    def values = Set(Read, Edit, Write)
  }
  final case object Configuration {
    final case object Read  extends Configuration with ActionType.Read  with AuthorizationType
    final case object Edit  extends Configuration with ActionType.Edit  with AuthorizationType
    final case object Write extends Configuration with ActionType.Write with AuthorizationType

    def values = Set(Read, Edit, Write)
  }
  final case object Deployer {
    final case object Read  extends Deployer with ActionType.Read  with AuthorizationType
    final case object Edit  extends Deployer with ActionType.Edit  with AuthorizationType
    final case object Write extends Deployer with ActionType.Write with AuthorizationType
    def values = Set(Read, Edit, Write)
  }
  final case object Deployment {
    final case object Read  extends Deployment with ActionType.Read  with AuthorizationType
    final case object Edit  extends Deployment with ActionType.Edit  with AuthorizationType
    final case object Write extends Deployment with ActionType.Write with AuthorizationType
    def values = Set(Read, Edit, Write)
  }
  final case object Directive {
    final case object Read  extends Directive with ActionType.Read  with AuthorizationType
    final case object Edit  extends Directive with ActionType.Edit  with AuthorizationType
    final case object Write extends Directive with ActionType.Write with AuthorizationType
    def values = Set(Read, Edit, Write)
  }
  final case object Group {
    final case object Read  extends Group with ActionType.Read  with AuthorizationType
    final case object Edit  extends Group with ActionType.Edit  with AuthorizationType
    final case object Write extends Group with ActionType.Write with AuthorizationType
    def values = Set(Read, Edit, Write)
  }
  final case object Node {
    final case object Read  extends Node with ActionType.Read  with AuthorizationType
    final case object Edit  extends Node with ActionType.Edit  with AuthorizationType
    final case object Write extends Node with ActionType.Write with AuthorizationType
    def values = Set(Read, Edit, Write)
  }
  final case object Rule {
    final case object Read  extends Rule with ActionType.Read  with AuthorizationType
    final case object Edit  extends Rule with ActionType.Edit  with AuthorizationType
    final case object Write extends Rule with ActionType.Write with AuthorizationType
    def values = Set(Read, Edit, Write)
  }
  final case object Technique {
    final case object Read  extends Technique with ActionType.Read  with AuthorizationType
    final case object Edit  extends Technique with ActionType.Edit  with AuthorizationType
    final case object Write extends Technique with ActionType.Write with AuthorizationType
    def values = Set(Read, Edit, Write)
  }
  final case object UserAccount {
    final case object Read  extends UserAccount with ActionType.Read  with AuthorizationType
    final case object Edit  extends UserAccount with ActionType.Edit  with AuthorizationType
    final case object Write extends UserAccount with ActionType.Write with AuthorizationType
    def values = Set(Read, Edit, Write)
  }
  final case object Validator {
    final case object Read  extends Validator with ActionType.Read  with AuthorizationType
    final case object Edit  extends Validator with ActionType.Edit  with AuthorizationType
    final case object Write extends Validator with ActionType.Write with AuthorizationType
    def values = Set(Read, Edit, Write)
  }

  def configurationKind: Set[AuthorizationType] = Configuration.values ++ Rule.values ++ Directive.values ++ Technique.values
  def nodeKind: Set[AuthorizationType] = Node.values ++ Group.values
  def workflowKind: Set[AuthorizationType] = Validator.values ++ Deployer.values
  def complianceKind: Set[AuthorizationType] = Compliance.values ++ (nodeKind ++ configurationKind).collect { case x: ActionType.Read  => x }
  def allKind: Set[AuthorizationType] = ca.mrvisser.sealerate.collect[AuthorizationType] - NoRights
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

  def displayAuthorizations = authorizationTypes.map { _.id }.toList.sorted.mkString(", ")

  override lazy val hashCode = 23 * authorizationTypes.hashCode

  override def equals(other:Any) = other match {
    case that:Rights => this.authorizationTypes == that.authorizationTypes
    case _ => false
  }

}

/*
 * Rudder "Role" which are kind of an aggregate of rights which somehow
 * make sense froma rudder usage point of view.
 */
sealed trait Role {
  def name  : String
  def rights: Rights
}
object Role {
  import com.normation.rudder.{AuthorizationType => A}
  private implicit class ToRights[T <: AuthorizationType](authorizations: Set[T]) {
    def toRights: Rights = new Rights(authorizations.toSeq: _*)
  }
  def allRead = A.allKind.collect { case x: ActionType.Read => x }

  // for now, all account type also have the "user account" rights
  val ua = A.UserAccount.values

  final case object Administrator extends Role { val name = "administrator"       ; val rights = (A.allKind                                    ).toRights }
  final case object User          extends Role { val name = "user"                ; val rights = (ua ++ A.nodeKind ++ A.configurationKind      ).toRights }
  final case object AdminOnly     extends Role { val name = "administration_only" ; val rights = (ua ++ A.Administration.values.map(identity)  ).toRights }
  final case object Workflow      extends Role { val name = "workflow"            ; val rights = (ua ++ A.workflowKind ++ A.complianceKind     ).toRights }
  final case object Deployer      extends Role { val name = "deployer"            ; val rights = (ua ++ A.Deployer.values ++ A.complianceKind  ).toRights }
  final case object Validator     extends Role { val name = "validator"           ; val rights = (ua ++ A.Validator.values ++ A.complianceKind ).toRights }
  final case object Configuration extends Role { val name = "configuration"       ; val rights = (ua ++ A.configurationKind.map(identity)      ).toRights }
  final case object ReadOnly      extends Role { val name = "read_only"           ; val rights = (ua ++ allRead                                ).toRights }
  final case object Compliance    extends Role { val name = "compliance"          ; val rights = (ua ++ A.complianceKind                       ).toRights }
  final case object Inventory     extends Role { val name = "inventory"           ; val rights = (ua ++ Set(A.Node.Read)                       ).toRights }
  final case object RuleOnly      extends Role { val name = "rule_only"           ; val rights = (ua ++ Set(A.Configuration.Read, A.Rule.Read) ).toRights }
  final case object NoRights      extends Role { val name = "no_rights"           ; val rights = (Set(AuthorizationType.NoRights)              ).toRights }

  final case class  Custom(rights: Rights) extends Role { val name = "custom" }

  def values: Set[Role] = ca.mrvisser.sealerate.collect[Role]
}


object RoleToRights {
  /*
   * Authorization parser
   */
  def parseRole(roles:Seq[String]): Seq[Role] = {
    def parseOne(role: String): Role = {
      val r = role.toLowerCase
      Role.values.find { _.name == r } match {
        case Some(r) => r
        case None    => // check if we have a custom roles based on authorization
          val authz = parseAuthz(role)
          if(authz.isEmpty) {
            Role.NoRights
          } else {
            Role.Custom(new Rights(authz.toSeq: _*))
          }

      }
    }
    roles.map(parseOne)
  }

  def parseAuthz(authz : String) : Set[AuthorizationType] = {
    val regex = """(.*)_(.*)""".r
     authz.toLowerCase() match {
       case "any"                => AuthorizationType.allKind
       case regex(authz, action) =>
         val allActions = if(action == "all") {
           Set("read", "write", "edit")
         } else {
           Set(action)
         }
         allActions.flatMap( a => AuthorizationType.allKind.find( x => x.authzKind == authz && x.action == a).toSet )
       case _                    => Set()
     }
  }
}
