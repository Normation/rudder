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

import com.normation.errors.Inconsistency
import com.normation.errors.IOResult
import com.normation.errors.PureResult
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.zio._
import scala.collection.immutable.SortedMap
import zio._
import zio.syntax._

/**
 * Base class for Authorization types.
 * Common types are read, write, etc but the list is
 * open and implementations are free to add their semantic.
 *
 */
trait AuthorizationType {

  /**
   * A string identifier of that authorization type
   * which may be use to transform string to that
   * type.
   */
  def id: String = authzKind + "_" + action

  def authzKind: String
  def action:    String
}

sealed trait ActionType {
  def action: String
}

/*
 * Different action types for authorization.
 * Read is to get information.
 * Write is for creation / deletion of objects
 * Edit is for update of one existing object
 */
object ActionType {
  trait Read  extends ActionType { def action = VALUE.READ.name  }
  trait Write extends ActionType { def action = VALUE.WRITE.name }
  trait Edit  extends ActionType { def action = VALUE.EDIT.name  }

  // we restrict the set of action types to only these three one to avoid future problem with named custom role
  sealed trait VALUE { def name: String }
  object VALUE       {
    final case object READ  extends VALUE { val name = "read"  }
    final case object WRITE extends VALUE { val name = "write" }
    final case object EDIT  extends VALUE { val name = "edit"  }

    val all: Set[VALUE] = ca.mrvisser.sealerate.values

    def parse(s: String): Option[VALUE] = all.find(_.name == s.toLowerCase())
  }
}

sealed trait Administration extends AuthorizationType { def authzKind = "administration" }
sealed trait Compliance     extends AuthorizationType { def authzKind = "compliance"     }
sealed trait Configuration  extends AuthorizationType { def authzKind = "configuration"  }
sealed trait Deployer       extends AuthorizationType { def authzKind = "deployer"       }
sealed trait Deployment     extends AuthorizationType { def authzKind = "deployment"     }
sealed trait Directive      extends AuthorizationType { def authzKind = "directive"      }
sealed trait Group          extends AuthorizationType { def authzKind = "group"          }
sealed trait Node           extends AuthorizationType { def authzKind = "node"           }
sealed trait Parameter      extends AuthorizationType { def authzKind = "parameter"      }
sealed trait Rule           extends AuthorizationType { def authzKind = "rule"           }
sealed trait Technique      extends AuthorizationType { def authzKind = "technique"      }
sealed trait UserAccount    extends AuthorizationType { def authzKind = "userAccount"    }
sealed trait Validator      extends AuthorizationType { def authzKind = "validator"      }

final object AuthorizationType {

  final case object NoRights  extends AuthorizationType { val authzKind = "no"; val action = "rights"  }
  final case object AnyRights extends AuthorizationType { val authzKind = "any"; val action = "rights" }

  // can't use sealerate here: "knownDirectSubclasses of observed before subclass ... registered"
  // I'm not sure exactly how/why it bugs. It's only for object where ".values" is used
  // in Role.

  // UIs and action related to Rudder app management (settings menu, etc)
  final case object Administration {
    final case object Read  extends Administration with ActionType.Read with AuthorizationType
    final case object Edit  extends Administration with ActionType.Edit with AuthorizationType
    final case object Write extends Administration with ActionType.Write with AuthorizationType
    def values: Set[AuthorizationType] = Set(Read, Edit, Write)
  }
  //
  final case object Compliance     {
    final case object Read  extends Compliance with ActionType.Read with AuthorizationType
    final case object Edit  extends Compliance with ActionType.Edit with AuthorizationType
    final case object Write extends Compliance with ActionType.Write with AuthorizationType
    def values: Set[AuthorizationType] = Set(Read, Edit, Write)
  }
  // configure param, techniques, directives, rules, groups
  final case object Configuration  {
    final case object Read  extends Configuration with ActionType.Read with AuthorizationType
    final case object Edit  extends Configuration with ActionType.Edit with AuthorizationType
    final case object Write extends Configuration with ActionType.Write with AuthorizationType

    def values: Set[AuthorizationType] = Set(Read, Edit, Write)
  }
  // in workflow, ability to merge a change
  final case object Deployer       {
    final case object Read  extends Deployer with ActionType.Read with AuthorizationType
    final case object Edit  extends Deployer with ActionType.Edit with AuthorizationType
    final case object Write extends Deployer with ActionType.Write with AuthorizationType
    def values: Set[AuthorizationType] = Set(Read, Edit, Write)
  }
  // in rudder, ability to start/interact with a policy generation
  final case object Deployment     {
    final case object Read  extends Deployment with ActionType.Read with AuthorizationType
    final case object Edit  extends Deployment with ActionType.Edit with AuthorizationType
    final case object Write extends Deployment with ActionType.Write with AuthorizationType
    def values: Set[AuthorizationType] = Set(Read, Edit, Write)
  }
  final case object Directive      {
    final case object Read  extends Directive with ActionType.Read with AuthorizationType
    final case object Edit  extends Directive with ActionType.Edit with AuthorizationType
    final case object Write extends Directive with ActionType.Write with AuthorizationType
    def values: Set[AuthorizationType] = Set(Read, Edit, Write)
  }
  final case object Group          {
    final case object Read  extends Group with ActionType.Read with AuthorizationType
    final case object Edit  extends Group with ActionType.Edit with AuthorizationType
    final case object Write extends Group with ActionType.Write with AuthorizationType
    def values: Set[AuthorizationType] = Set(Read, Edit, Write)
  }
  final case object Node           {
    final case object Read  extends Node with ActionType.Read with AuthorizationType
    final case object Edit  extends Node with ActionType.Edit with AuthorizationType
    final case object Write extends Node with ActionType.Write with AuthorizationType
    def values: Set[AuthorizationType] = Set(Read, Edit, Write)
  }
  final case object Rule           {
    final case object Read  extends Rule with ActionType.Read with AuthorizationType
    final case object Edit  extends Rule with ActionType.Edit with AuthorizationType
    final case object Write extends Rule with ActionType.Write with AuthorizationType
    def values: Set[AuthorizationType] = Set(Read, Edit, Write)
  }
  final case object Parameter      {
    final case object Read  extends Parameter with ActionType.Read with AuthorizationType
    final case object Edit  extends Parameter with ActionType.Edit with AuthorizationType
    final case object Write extends Parameter with ActionType.Write with AuthorizationType
    def values: Set[AuthorizationType] = Set(Read, Edit, Write)
  }
  final case object Technique      {
    final case object Read  extends Technique with ActionType.Read with AuthorizationType
    final case object Edit  extends Technique with ActionType.Edit with AuthorizationType
    final case object Write extends Technique with ActionType.Write with AuthorizationType
    def values: Set[AuthorizationType] = Set(Read, Edit, Write)
  }
  final case object UserAccount    {
    final case object Read  extends UserAccount with ActionType.Read with AuthorizationType
    final case object Edit  extends UserAccount with ActionType.Edit with AuthorizationType
    final case object Write extends UserAccount with ActionType.Write with AuthorizationType
    def values: Set[AuthorizationType] = Set(Read, Edit, Write)
  }
  final case object Validator      {
    final case object Read  extends Validator with ActionType.Read with AuthorizationType
    final case object Edit  extends Validator with ActionType.Edit with AuthorizationType
    final case object Write extends Validator with ActionType.Write with AuthorizationType
    def values: Set[AuthorizationType] = Set(Read, Edit, Write)
  }

  val configurationKind: Set[AuthorizationType] =
    Configuration.values ++ Rule.values ++ Directive.values ++ Technique.values ++ Parameter.values
  val nodeKind:          Set[AuthorizationType] = Node.values ++ Group.values
  val workflowKind:      Set[AuthorizationType] = Validator.values ++ Deployer.values
  val complianceKind:    Set[AuthorizationType] = Compliance.values ++ (nodeKind ++ configurationKind).collect {
    case x: ActionType.Read => x
  }

  /*
   * Authorization are extensible but can not be removed.
   * Typically, a plugin can register its own rights
   */
  def allKind: Set[AuthorizationType] = allKindsMap

  private[this] var allKindsMap: Set[AuthorizationType] = {
    Set(
      AnyRights
    ) ++ Administration.values ++ Compliance.values ++ Configuration.values ++ Deployer.values ++ Deployment.values ++
    Directive.values ++ Group.values ++ Node.values ++ Rule.values ++ Parameter.values ++ Technique.values ++
    UserAccount.values ++ Validator.values
  }
  def addAuthKind(newKinds: Set[AuthorizationType]) = allKindsMap = allKindsMap ++ newKinds

  /*
   * Parse a string as a set of authorization type, taking care of the special keyword "all" as an alias of read, write, edit.
   * If the string is well formed but no authz are found, we return an empty set, not an error.
   * Error is only if the string isn't well formed (for example "foo"), or if it's well formed but action is not one of
   * edit, write, read (ex: "foo_bar").
   */
  def parseAuthz(authz: String): PureResult[Set[AuthorizationType]] = {
    val regex = """(.*)_(.*)""".r
    authz.toLowerCase() match {
      case "any"                => Right(AuthorizationType.allKind)
      case regex(authz, action) =>
        val allActions = if (action == "all") {
          Right(ActionType.VALUE.all)
        } else {
          ActionType.VALUE.parse(action) match {
            case Some(a) => Right(Set(a))
            case None    =>
              Left(
                Inconsistency(
                  s"Authorization action with identifier name '${action}' is not known. Possible values: ${ActionType.VALUE.all.map(_.name).mkString(", ")}'"
                )
              )
          }
        }

        allActions.map(
          _.flatMap(a => AuthorizationType.allKind.find(x => x.authzKind.toLowerCase == authz && x.action == a.name).toSet)
        )
      case _                    =>
        Left(
          Inconsistency(
            s"String '${authz}' is not recognized as an authorization string. It should be either 'any' or structured 'kind_[edit|write|read]'"
          )
        )
    }
  }

}

/**
 * That class represents a set of AuthorizationType that
 * HAS TO all be validated at the same time. It acts like
 * a new AuthorizationType which melt each AuthorizationType
 * that composed it.
 */
class Rights(_authorizationTypes: AuthorizationType*) {

  require(
    null != _authorizationTypes && _authorizationTypes.nonEmpty,
    "At least one AuthorizationType must be include in a Rights object"
  )

  val authorizationTypes = _authorizationTypes.toSet

  def displayAuthorizations = authorizationTypes.map(_.id).toList.sorted.mkString(", ")

  override lazy val hashCode = 23 * authorizationTypes.hashCode

  override def equals(other: Any) = other match {
    case that: Rights => this.authorizationTypes == that.authorizationTypes
    case _ => false
  }

  override def toString: String = {
    displayAuthorizations
  }
}

/*
 * Rudder "Role" which are kind of an aggregate of rights which somehow
 * make sense from a rudder usage point of view.
 */
sealed trait Role {
  def name:   String
  def rights: Rights
  def debugString: String = name
}
object Role       {
  import com.normation.rudder.{AuthorizationType => A}
  implicit private class ToRights[T <: AuthorizationType](authorizations: Set[T]) {
    def toRights: Rights = new Rights(authorizations.toSeq: _*)
  }
  def allRead = A.allKind.collect { case x: ActionType.Read => x }

  // for now, all account type also have the "user account" rights
  val ua = A.UserAccount.values

  // a special account, with all rights, present and future, even if declared at runtime.
  final case object Administrator extends Role {
    val name = "administrator"; def rights = new Rights(AuthorizationType.AnyRights)
  }

  // other standard predefined roles
  final case object User          extends Role { val name = "user"; def rights = (ua ++ A.nodeKind ++ A.configurationKind).toRights }
  final case object AdminOnly     extends Role {
    val name = "administration_only"; def rights = (ua ++ A.Administration.values.map(identity)).toRights
  }
  final case object Workflow      extends Role {
    val name = "workflow"; def rights = (ua ++ A.workflowKind ++ A.complianceKind).toRights
  }
  final case object Deployer      extends Role {
    val name = "deployer"; def rights = (ua ++ A.Deployer.values ++ A.complianceKind).toRights
  }
  final case object Validator     extends Role {
    val name = "validator"; def rights = (ua ++ A.Validator.values ++ A.complianceKind).toRights
  }
  final case object Configuration extends Role {
    val name = "configuration"; val rights = (ua ++ A.configurationKind.map(identity)).toRights
  }
  final case object ReadOnly      extends Role { val name = "read_only"; def rights = (ua ++ allRead).toRights                      }
  final case object Compliance    extends Role { val name = "compliance"; def rights = (ua ++ A.complianceKind).toRights            }
  final case object Inventory     extends Role { val name = "inventory"; def rights = (ua ++ Set(A.Node.Read)).toRights             }
  final case object RuleOnly      extends Role {
    val name = "rule_only"; def rights = (ua ++ Set(A.Configuration.Read, A.Rule.Read)).toRights
  }

  // a special Role that means that a user has no rights at all. That role must super-seed any other right given by other roles
  final case object NoRights extends Role { val name = "no_rights"; def rights = (Set(AuthorizationType.NoRights)).toRights }

  // this is the anonymous custom roles, the one computed on fly for user who have several roles in their attribute
  final case class Custom(rights: Rights) extends Role {
    val name                 = "custom"
    override def debugString = s"authz[${rights.displayAuthorizations}]"
  }
  def forAuthz(right: AuthorizationType) = Custom(new Rights(right))
  def forAuthz(rights: Set[AuthorizationType]) = Custom(new Rights(rights.toSeq: _*))

  // this is the named custom roles defined in <custom-roles> tag
  final case class NamedCustom(name: String, roles: Seq[Role]) extends Role {
    def rights               = new Rights(roles.flatMap(_.rights.authorizationTypes): _*)
    override def debugString = s"customRole[${roles.map(_.debugString).mkString(",")}]"
  }

  def values: Set[Role] = ca.mrvisser.sealerate.collect[Role]
}

// custom role utility classes to help parse/resolve them
final case class UncheckedCustomRole(name: String, roles: List[String])
final case class CustomRoleResolverResult(validRoles: List[Role], invalid: List[(UncheckedCustomRole, String)])

/*
 * Facility to parse roles in rudder.
 * This object use stateful resolution of authorisation, so be careful, it may
 * be dependant of what happened elsewhere.
 */
object RudderRoles {

  // our database of roles. Everything is case insensitive, so role name are mapped "to lower string"

  // role names are case insensitive
  implicit val roleOrdering = Ordering.comparatorToOrdering(String.CASE_INSENSITIVE_ORDER)
  val builtInRoles          = SortedMap[String, Role](Role.values.toList.map(r => (r.name, r)): _*)
  private val customRoles   = Ref.make(SortedMap.empty[String, Role]).runNow
  private val allRoles: Ref[SortedMap[String, Role]] = ZioRuntime.unsafeRun(for {
    all <- computeAllRoles
    ref <- Ref.make(all)
  } yield ref)

  // compute all roles but be sure that no custom role ever override a builtIn one and that
  private def computeAllRoles: UIO[SortedMap[String, Role]] =
    customRoles.get.map(_ ++ builtInRoles)

  def getAllRoles = allRoles.get

  /*
   * Register custom roles. No check is done here, we just add them to the
   * pool of known roles.
   * Name is case insensitive. In case of redefinition, the last one registered win.
   * If `resetExisting` is true, then existing custom roles will be forgotten.
   */
  def register(roles: List[Role], resetExisting: Boolean): IOResult[Unit] = {
    for {
      crs <- customRoles.updateAndGet(existing => {
               val newRoles = roles.map(r => (r.name.toLowerCase, r))
               if (resetExisting) SortedMap(newRoles: _*) else existing ++ newRoles
             })
      all <- computeAllRoles
      _   <- allRoles.set(all)
    } yield ()
  }

  def findRoleByName(role: String): IOResult[Option[Role]] = {
    allRoles.get.map(_.get(role.toLowerCase()))
  }

  /*
   * Role parser that only rely of currently known roles, ie rudder default roles or
   * any registered named custom roles.
   * If no role is found by name, we try to parse for authorization. Else, the parsing fails.
   */
  def parseRole(role: String): IOResult[Role] = {
    findRoleByName(role).flatMap(r => {
      r match {
        case Some(x) => x.succeed
        case None    => // check if we have a custom roles based on authorization (ie anonymous)
          AuthorizationType.parseAuthz(role) match {
            case Left(err) =>
              Inconsistency(s"Role '${role}' can not be resolved to a named role nor it matches a valid authorization").fail
            case Right(x)  => Role.Custom(new Rights(x.toSeq: _*)).succeed
          }
      }
    })
  }

  // Utility method for parsing several roles at once
  // No consistency enforcement other than if at least on "NoRight" exists, then only NoRight is returned.
  // Unknown roles are filtered out (with log)
  def parseRoles(roles: List[String]): UIO[List[Role]] = {
    ZIO
      .foreach(roles) { role =>
        parseRole(role).foldZIO(
          err => ApplicationLoggerPure.Authz.warn(err.fullMsg) *> Nil.succeed,
          r => List(r).succeed
        )
      }
      .map { list =>
        val l = list.flatten
        if (l.exists(_ == Role.NoRights)) List(Role.NoRights) else l.distinct
      }
  }

  /*
   * Resolve all roles given in input with a starting knowledge of existing `resolved` roles.
   * Resolving a role means: "checking for all roles in the `roles` list if they are known (either authorization-based or
   * built-in-role reference or previously resolved custom role).
   * Return a structure with fully resolved roles ("success") and role which where not resolved due to a problem. The
   * cause of the problem is zipped with the faulty roles.
   */
  def resolveCustomRoles(
      customRoles: List[UncheckedCustomRole],
      knownRoles:  SortedMap[String, Role]
  ): UIO[CustomRoleResolverResult] = {
    /*
     * General logic strategy:
     * We start by removing all role reference that are neither a knownRoles nor one in the uncheck list. These
     * unknown role references may be due to missing plugins, typos, or whatever but we want them to just be ignored,
     * not lead to invalid custom-roles.
     * Then we must detect roles with cycle to remove them.
     * For that, we have a pending(list(roles)) state that means that a role is pending until each of the roles
     * in the list are resolved.
     * When a role if fully resolved, we walk the pending list and remove it from each list. New roles are
     * unblocked recursively.
     * When we have process all the input list, if there is still roles in the pending list, it means that
     * they are part of a cycle and we can remove them.
     */

    // utility class to hold a pending role, ie a role for which only parts of the role-list has been resolved.
    // `role` is the wanna be custom role with the already resolved roles,
    // `pending` is the list of remaining roles.
    case class PendingRole(role: Role.NamedCustom, pending: List[String])

    // filter out role in permission that are neither defined nor in the customRoles
    def filterUnknownRoles(
        customRoles: List[UncheckedCustomRole],
        knownRoles:  SortedMap[String, Role]
    ): UIO[List[UncheckedCustomRole]] = {
      val availableReferences = knownRoles.keySet ++ customRoles.map(_.name) // case-insensitive
      ZIO.foreach(customRoles) {
        case UncheckedCustomRole(name, roles) =>
          for {
            ok <- ZIO.foreach(roles) { r =>
                    if (availableReferences.contains(r) || AuthorizationType.parseAuthz(r).isRight) { Some(r).succeed }
                    else {
                      ApplicationLoggerPure.Authz.warn(
                        s"Role '${name}' reference unknown role '${r}': '${r}' will be ignored."
                      ) *> None.succeed
                    }
                  }
          } yield {
            UncheckedCustomRole(name, ok.flatten)
          }
      }
    }

    // utility sub-function used to check that the named custom role does not overlap with authorization patterns
    def checkExistingName(role: UncheckedCustomRole): Either[String, Unit] = {
      AuthorizationType.parseAuthz(role.name) match {
        case Right(value) =>
          Left("'any' and patterns 'kind_[read,edit,write,all] are reserved that can't be used for a custom role")
        case Left(err)    =>
          Right(())
      }
    }

    // utility sub-function to resolve one role list in the context of `knownRoles`.
    def resolveOne(knownRoles: Map[String, Role], role: PendingRole): Either[PendingRole, Role.NamedCustom] = {
      import com.softwaremill.quicklens._
      // role can't be duplicate (case insensitive)
      val toResolve = role.pending.distinctBy(_.toLowerCase)
      val firstPass = toResolve.foldLeft(role.modify(_.pending).setTo(Nil)) {
        case (current, roleName) =>
          ApplicationLoggerPure.Authz.logEffect.trace(s"Custom role resolution step: ${current}")
          knownRoles.get(roleName) match {
            // role name not yet resolved or part of a cycle
            case None    =>
              ApplicationLoggerPure.Authz.logEffect.trace(s"[${role.role.name}] not found in existing role: ${roleName}")
              // try to parse role as an authorization, it is
              AuthorizationType.parseAuthz(roleName) match {
                case Right(x) =>
                  ApplicationLoggerPure.Authz.logEffect.trace(s"[${role.role.name}] valid authorization: ${roleName}")
                  current.modify(_.role.roles).using(_.appended(Role.forAuthz(x)))
                case Left(_)  =>
                  ApplicationLoggerPure.Authz.logEffect.trace(s"[${role.role.name}] not an authorization: ${roleName}")
                  current.modify(_.pending).using(roleName :: _)
              }

            // yeah, role name already resolved !
            case Some(r) =>
              ApplicationLoggerPure.Authz.logEffect.trace(s"[${role.role.name}] found existing role: ${roleName}")
              current.modify(_.role.roles).using(_.appended(r))
          }
      }
      // After first pass, if all role name are resolved, we have our full custom role. Else, we will need to wait for other role resolution
      // A named custom role can have zero role in the list (it won't give any rights)
      firstPass match {
        case PendingRole(role, Nil) => Right(role)
        case x                      => Left(x)
      }
    }

    // resolve all roles from the input list given the roles already in `resolved`. For each step, update either `pending`
    // or `resolved` with the current role. When a role is added to `resolved`, call `resolveNew` to walk again the
    // `pending` list with that new knowledge.
    def resolveAll(
        input:    List[PendingRole],
        pending:  Ref[List[PendingRole]],
        resolved: Ref[SortedMap[String, Role]],
        errors:   Ref[List[(UncheckedCustomRole, String)]]
    ): UIO[Unit] = {

      // Sub-function to call when we have a new role resolved: it will walk list of pending role to remove the new resolved
      // one when it is encountered in the pending list of a role and - if the role becomes full resolved - call itself back
      def resolveNew(newRole: Role, pending: Ref[List[PendingRole]], resolved: Ref[SortedMap[String, Role]]): UIO[Unit] = {
        pending.get.flatMap(list => {
          ZIO
            .foreach(list) { p =>
              val matching = p.pending.collect { case r if r.equalsIgnoreCase(newRole.name) => r }
              if (matching.nonEmpty) {
                val remainingRoles = p.pending.filterNot(r => matching.contains(r))
                // only add one time, even several names match
                val r              = PendingRole(p.role.copy(roles = p.role.roles.appended(newRole)), pending = remainingRoles)
                if (remainingRoles.isEmpty) { // promote, recurse
                  for {
                    _ <- pending.update(_.filterNot(_.role.name == p.role.name))
                    _ <- resolved.update(_ + (r.role.name -> r.role))
                    _ <- resolveNew(r.role, pending, resolved)
                  } yield ()
                } else { // update pending
                  pending.update(all => r :: all.filterNot(_.role.name == r.role.name))
                }
              } else { // nothing to do
                ZIO.unit
              }
            }
            .unit
        })
      }

      filterUnknownRoles(customRoles, knownRoles)
        .flatMap(filtered => {
          ZIO
            .foreach(filtered) { cr =>
              checkExistingName(cr) match {
                case Left(err) => errors.update((cr, err) :: _)
                case Right(_)  =>
                  for {
                    // for each role, resolve it or put it in pending
                    v <- resolved.get
                    // check that the name is not already in use
                    _ <- if (v.isDefinedAt(cr.name)) {
                           errors.update(
                             (
                               cr,
                               s"Custom role with name '${cr.name}' will be ignored because a role with same name (case insensitive) already exists"
                             ) :: _
                           )
                         } else {
                           resolveOne(v, PendingRole(Role.NamedCustom(cr.name, Nil), cr.roles)) match {
                             case Left(stillPending) =>
                               ApplicationLoggerPure.Authz.trace(
                                 s"Custom role '${stillPending.role.name}' not fully resolved for roles: ${stillPending.pending.mkString(", ")}"
                               ) *>
                               pending.update(stillPending :: _)
                             case Right(nc)          =>
                               // before adding, remove possible duplicate resolved role
                               val toAdd = nc.copy(roles = nc.roles.distinct)
                               ApplicationLoggerPure.Authz.trace(s"Custom role '${toAdd.name}' fully resolved") *>
                               resolved.update(_ + (toAdd.name -> toAdd)) *> resolveNew(toAdd, pending, resolved)
                           }
                         }
                  } yield ()
              }
            }
        })
        .unit
    }

    //
    // Main program: create the needed refs and exec "resolveAll". Then, deal with the result and create the
    // appropriate `CustomRoleResolverResult` with them.
    //
    for {
      pending   <- Ref.make(List.empty[PendingRole]) // this is for the pending role for which one pass won't be enough
      resolved  <- Ref.make(knownRoles)
      errors    <- Ref.make(List.empty[(UncheckedCustomRole, String)])
      inputs     = customRoles.map(role => PendingRole(Role.NamedCustom(role.name, Nil), Nil))
      debugR    <- resolved.get
      _         <- ApplicationLoggerPure.Authz.debug(
                     s"Resolving custom roles from known roles: ${debugR.keys.toList.sorted.mkString(", ")}"
                   )
      _         <- resolveAll(inputs, pending, resolved, errors)
      // all remaining roles in pending are in cycle
      remaining <- pending.get
      bad        = remaining.map { b =>
                     (
                       UncheckedCustomRole(b.role.name, b.pending),
                       s"Custom role with name '${b.role.name}' will ignored: it is part of a cycle which is forbidden"
                     )
                   }
      errs      <- errors.get
      good      <- resolved.get
    } yield {
      val newCustom = (good -- knownRoles.keys).values.toList
      CustomRoleResolverResult(newCustom, bad ::: errs)
    }
  }
}
