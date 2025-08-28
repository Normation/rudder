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

import cats.implicits.*
import com.normation.errors.Inconsistency
import com.normation.errors.IOResult
import com.normation.errors.PureResult
import com.normation.rudder.Role.Builtin
import com.normation.rudder.Role.BuiltinName
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.zio.*
import enumeratum.*
import scala.collection.immutable.SortedMap
import zio.*
import zio.syntax.*

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
  sealed trait VALUE extends EnumEntry   { def name: String }
  object VALUE       extends Enum[VALUE] {
    case object READ  extends VALUE { val name = "read"  }
    case object WRITE extends VALUE { val name = "write" }
    case object EDIT  extends VALUE { val name = "edit"  }

    val values: IndexedSeq[VALUE] = findValues
    val all:    Set[VALUE]        = values.toSet

    def parse(s: String): Option[VALUE] = values.find(_.name == s.toLowerCase())
  }
}

sealed trait Administration extends EnumEntry with AuthorizationType { def authzKind = "administration" }
sealed trait Compliance     extends EnumEntry with AuthorizationType { def authzKind = "compliance"     }
sealed trait Configuration  extends EnumEntry with AuthorizationType { def authzKind = "configuration"  }
sealed trait Deployer       extends EnumEntry with AuthorizationType { def authzKind = "deployer"       }
sealed trait Deployment     extends EnumEntry with AuthorizationType { def authzKind = "deployment"     }
sealed trait Directive      extends EnumEntry with AuthorizationType { def authzKind = "directive"      }
sealed trait Group          extends EnumEntry with AuthorizationType { def authzKind = "group"          }
sealed trait Node           extends EnumEntry with AuthorizationType { def authzKind = "node"           }
sealed trait Parameter      extends EnumEntry with AuthorizationType { def authzKind = "parameter"      }
sealed trait Rule           extends EnumEntry with AuthorizationType { def authzKind = "rule"           }
sealed trait Technique      extends EnumEntry with AuthorizationType { def authzKind = "technique"      }
sealed trait UserAccount    extends EnumEntry with AuthorizationType { def authzKind = "userAccount"    }
sealed trait Validator      extends EnumEntry with AuthorizationType { def authzKind = "validator"      }

object AuthorizationType {

  case object NoRights  extends AuthorizationType { val authzKind = "no"; val action = "rights"  }
  case object AnyRights extends AuthorizationType { val authzKind = "any"; val action = "rights" }

  // UIs and action related to Rudder app management (settings menu, etc)
  case object Administration extends Enum[Administration] {
    case object Read  extends Administration with ActionType.Read with AuthorizationType
    case object Edit  extends Administration with ActionType.Edit with AuthorizationType
    case object Write extends Administration with ActionType.Write with AuthorizationType
    val values: IndexedSeq[Administration] = findValues
  }
  //
  case object Compliance     extends Enum[Compliance]     {
    case object Read  extends Compliance with ActionType.Read with AuthorizationType
    case object Edit  extends Compliance with ActionType.Edit with AuthorizationType
    case object Write extends Compliance with ActionType.Write with AuthorizationType
    val values: IndexedSeq[Compliance] = findValues
  }
  // configure param, techniques, directives, rules, groups
  case object Configuration  extends Enum[Configuration]  {
    case object Read  extends Configuration with ActionType.Read with AuthorizationType
    case object Edit  extends Configuration with ActionType.Edit with AuthorizationType
    case object Write extends Configuration with ActionType.Write with AuthorizationType

    val values: IndexedSeq[Configuration] = findValues
  }
  // in workflow, ability to merge a change
  case object Deployer       extends Enum[Deployer]       {
    case object Read  extends Deployer with ActionType.Read with AuthorizationType
    case object Edit  extends Deployer with ActionType.Edit with AuthorizationType
    case object Write extends Deployer with ActionType.Write with AuthorizationType
    val values: IndexedSeq[Deployer] = findValues
  }
  // in rudder, ability to start/interact with a policy generation
  case object Deployment     extends Enum[Deployment]     {
    case object Read  extends Deployment with ActionType.Read with AuthorizationType
    case object Edit  extends Deployment with ActionType.Edit with AuthorizationType
    case object Write extends Deployment with ActionType.Write with AuthorizationType
    val values: IndexedSeq[Deployment] = findValues
  }
  case object Directive      extends Enum[Directive]      {
    case object Read  extends Directive with ActionType.Read with AuthorizationType
    case object Edit  extends Directive with ActionType.Edit with AuthorizationType
    case object Write extends Directive with ActionType.Write with AuthorizationType
    val values: IndexedSeq[Directive] = findValues
  }
  case object Group          extends Enum[Group]          {
    case object Read  extends Group with ActionType.Read with AuthorizationType
    case object Edit  extends Group with ActionType.Edit with AuthorizationType
    case object Write extends Group with ActionType.Write with AuthorizationType
    val values: IndexedSeq[Group] = findValues
  }
  case object Node           extends Enum[Node]           {
    case object Read  extends Node with ActionType.Read with AuthorizationType
    case object Edit  extends Node with ActionType.Edit with AuthorizationType
    case object Write extends Node with ActionType.Write with AuthorizationType
    val values: IndexedSeq[Node] = findValues
  }
  case object Rule           extends Enum[Rule]           {
    case object Read  extends Rule with ActionType.Read with AuthorizationType
    case object Edit  extends Rule with ActionType.Edit with AuthorizationType
    case object Write extends Rule with ActionType.Write with AuthorizationType
    val values: IndexedSeq[Rule] = findValues
  }
  case object Parameter      extends Enum[Parameter]      {
    case object Read  extends Parameter with ActionType.Read with AuthorizationType
    case object Edit  extends Parameter with ActionType.Edit with AuthorizationType
    case object Write extends Parameter with ActionType.Write with AuthorizationType
    val values: IndexedSeq[Parameter] = findValues
  }
  case object Technique      extends Enum[Technique]      {
    case object Read  extends Technique with ActionType.Read with AuthorizationType
    case object Edit  extends Technique with ActionType.Edit with AuthorizationType
    case object Write extends Technique with ActionType.Write with AuthorizationType
    val values: IndexedSeq[Technique] = findValues
  }
  case object UserAccount    extends Enum[UserAccount]    {
    case object Read  extends UserAccount with ActionType.Read with AuthorizationType
    case object Edit  extends UserAccount with ActionType.Edit with AuthorizationType
    case object Write extends UserAccount with ActionType.Write with AuthorizationType
    val values: IndexedSeq[UserAccount] = findValues
  }
  case object Validator      extends Enum[Validator]      {
    case object Read  extends Validator with ActionType.Read with AuthorizationType
    case object Edit  extends Validator with ActionType.Edit with AuthorizationType
    case object Write extends Validator with ActionType.Write with AuthorizationType
    val values: IndexedSeq[Validator] = findValues
  }

  val configurationKind: Set[AuthorizationType] =
    (Configuration.values ++ Rule.values ++ Directive.values ++ Technique.values ++ Parameter.values).toSet
  val nodeKind:          Set[AuthorizationType] = (Node.values ++ Group.values).toSet
  val workflowKind:      Set[AuthorizationType] = (Validator.values ++ Deployer.values).toSet
  val complianceKind:    Set[AuthorizationType] = {
    (Compliance.values ++
    (nodeKind ++ Configuration.values ++ Rule.values ++ Directive.values).collect { case x: ActionType.Read => x }).toSet
  }

  /*
   * Authorization are extensible but can not be removed.
   * Typically, a plugin can register its own rights
   */
  def allKind: Set[AuthorizationType] = allKindsMap

  private var allKindsMap: Set[AuthorizationType] = {
    Set(
      AnyRights
    ) ++ Administration.values ++ Compliance.values ++ Configuration.values ++ Deployer.values ++ Deployment.values ++
    Directive.values ++ Group.values ++ Node.values ++ Rule.values ++ Parameter.values ++ Technique.values ++
    UserAccount.values ++ Validator.values
  }
  def addAuthKind(newKinds: Set[AuthorizationType]): Unit = allKindsMap = allKindsMap ++ newKinds

  /*
   * Parse a right string (ie: object_operation) as a set of authorization type, taking care of the special keyword "all"
   * as an alias of read, write, edit and "any" for all authorizations.
   * If the string is well formed but no authz are found, we return an empty set, not an error.
   * Error is only if the string isn't well formed (for example "foo"), or if it's well formed but action is not one of
   * edit, write, read, all (ex: "foo_bar").
   */
  // check if it's a valid right syntaxt, and if it's a special one word case or a (object, operation) one
  def checkSyntax(right: String): Option[Either[AuthorizationType.AnyRights.type, (String, String)]] = {
    val regex = """(.*)_(.*)""".r
    right.toLowerCase() match {
      case "any"                 => Some(Left(AuthorizationType.AnyRights))
      case regex(obj, operation) => Some(Right((obj, operation)))
      case _                     => None
    }

  }

  def parseRight(right: String): PureResult[Set[AuthorizationType]] = {
    checkSyntax(right) match {
      case None                          =>
        Left(
          Inconsistency(
            s"String '${right}' is not recognized as a right string. It should be either 'any' or structured 'object_[edit|write|read]'"
          )
        )
      case Some(Left(_))                 =>
        Right(AuthorizationType.allKind)
      case Some(Right((obj, operation))) =>
        for {
          ops <- if (operation == "all") {
                   Right(ActionType.VALUE.all)
                 } else {
                   ActionType.VALUE.parse(operation) match {
                     case Some(a) => Right(Set(a))
                     case None    =>
                       Left(
                         Inconsistency(
                           s"Permission operation with identifier name '${operation}' is not known. Possible values: ${ActionType.VALUE.all.map(_.name).mkString(", ")}'"
                         )
                       )
                   }
                 }
          r   <- ops.toList.traverse { a =>
                   AuthorizationType.allKind
                     .find(x => x.authzKind.toLowerCase == obj && x.action == a.name)
                     .toRight(
                       // we can have something that has a valid syntax but is not a valid known action (for example, when plugin is not loaded)
                       Inconsistency(
                         s"Permission operation with identifier name '${operation}' is not known. Possible values: ${ActionType.VALUE.all.map(_.name).mkString(", ")}'"
                       )
                     )
                 }
        } yield {
          r.toSet
        }
    }
  }

}

/**
 * That class represents a set of AuthorizationType that
 * HAS TO all be validated at the same time. It acts like
 * a new AuthorizationType which melt each AuthorizationType
 * that composed it.
 */
case class Rights private (authorizationTypes: Set[AuthorizationType]) {

  def displayAuthorizations: String = authorizationTypes.map(_.id).toList.sorted.mkString(", ")

  override def equals(other: Any): Boolean = other match {
    case that: Rights => this.authorizationTypes == that.authorizationTypes
    case _ => false
  }

  override def toString: String = {
    displayAuthorizations
  }
}

object Rights {

  val NoRights: Rights = new Rights(Set(AuthorizationType.NoRights))

  val AnyRights: Rights = Rights.forAuthzs(AuthorizationType.AnyRights)

  def apply(authorizationTypes: AuthorizationType*): Rights = {
    apply(authorizationTypes)
  }

  def apply(authorizationTypes: Iterable[AuthorizationType]): Rights = {
    if (authorizationTypes.isEmpty) {
      NoRights
    } else {
      new Rights(authorizationTypes.toSet)
    }
  }

  def forAuthzs(authorizationTypes: AuthorizationType*): Rights = apply(authorizationTypes.toSeq)

  def combineAll(rights: Iterable[Rights]): Rights = apply(rights.map(_.authorizationTypes).toList.combineAll)
}

/*
 * Rudder "Role" which are kind of an aggregate of rights which somehow
 * make sense from a rudder usage point of view.
 */
sealed trait Role extends EnumEntry  {
  def name:        String
  def rights:      Rights
  def debugString: String = name
}
object Role       extends Enum[Role] {
  import com.normation.rudder.AuthorizationType as A
  // for now, all account type also have the "user account" rights
  val ua = A.UserAccount.values

  // this is the anonymous custom roles, the one computed on fly for user who have several roles in their attribute
  final case class Custom(rights: Rights) extends Role {
    val name = "custom" // yes, that should be anonymous, it's for historical reason in the plugin. Will change in 8.2

    override def debugString: String = s"anonymousRole:authz[${rights.displayAuthorizations}]"
  }

  trait BuiltinName  { // not sealed, plugins need to extend
    val value: String
  }
  // core builtin name are given as object here, plugin
  object BuiltinName {
    case object User               extends BuiltinName { val value = "user"                }
    case object AdministrationOnly extends BuiltinName { val value = "administration_only" }
    case object Workflow           extends BuiltinName { val value = "workflow"            }
    case object Deployer           extends BuiltinName { val value = "deployer"            }
    case object Validator          extends BuiltinName { val value = "validator"           }
    case object Configuration      extends BuiltinName { val value = "configuration"       }
    case object ReadOnly           extends BuiltinName { val value = "read_only"           }
    case object Compliance         extends BuiltinName { val value = "compliance"          }
    case object Inventory          extends BuiltinName { val value = "inventory"           }
    case object RuleOnly           extends BuiltinName { val value = "rule_only"           }

    case class PluginRoleName(value: String) extends BuiltinName
  }

  // built-in roles
  final case class Builtin(_name: BuiltinName, rights: Rights) extends Role {
    val name = _name.value
  }

  // a special account, with all rights, present and future, even if declared at runtime.
  case object Administrator extends Role {
    val name = "administrator";

    def rights = Rights.AnyRights
  }

  // a special Role that means that a user has no rights at all. That role must super-seed any other right given by other roles
  case object NoRights extends Role {
    val name = "no_rights";

    def rights: Rights = Rights(Set(A.NoRights))
  }

  // standard predefined built-in roles
  def standardBuiltIn: Map[BuiltinName, Role] = {
    import BuiltinName.*
    List(
      Builtin(User, Rights(ua ++ A.nodeKind ++ A.configurationKind)),
      Builtin(AdministrationOnly, Rights(ua ++ A.Administration.values.map(identity))),
      Builtin(Workflow, Rights(ua ++ A.workflowKind ++ A.complianceKind)),
      Builtin(Deployer, Rights(ua ++ A.Deployer.values ++ A.complianceKind)),
      Builtin(Validator, Rights(ua ++ A.Validator.values ++ A.complianceKind)),
      Builtin(Configuration, Rights(ua ++ A.configurationKind.map(identity))),
      Builtin(ReadOnly, Rights(ua ++ A.allKind.collect { case x: ActionType.Read => x })),
      Builtin(Compliance, Rights(ua ++ A.complianceKind)),
      Builtin(Inventory, Rights(ua ++ Set(A.Node.Read))),
      Builtin(RuleOnly, Rights(ua ++ Set(A.Configuration.Read, A.Rule.Read)))
    ).map(r => (r._name, r)).toMap
  }

  def forRight(right:   AuthorizationType):      Custom = Custom(Rights.forAuthzs(right))
  def forRights(rights: Set[AuthorizationType]): Custom = Custom(Rights(rights))

  // this is the named custom roles defined in <custom-roles> tag
  final case class NamedCustom(name: String, permissions: Seq[Role]) extends Role {
    def rights:               Rights = Rights(permissions.flatMap(_.rights.authorizationTypes))
    override def debugString: String = s"${name}:customRole[${permissions.map(_.debugString).mkString(",")}]"
  }

  // a role that is just an alias for an other, which can be useful to add a relevant name/description
  // NOTE: we use the name of the aliased role for `name` so that Rudder internal resolution works as expected with
  // custom roles etc.
  final case class Alias(of: Role, aliasName: String, description: String) extends Role {
    override def name:   String = of.name
    override def rights: Rights = of.rights

    override def debugString: String = {
      s"${aliasName}:aliasof[${of.name}][${of.rights}]"
    }
  }

  val values: IndexedSeq[Role] = findValues

  // standard predefined special roles, ie Admin et NoRights
  def specialBuiltIn: Set[Role] = values.toSet

  def allBuiltInRoles: Map[String, Role] =
    standardBuiltIn.map { case (k, v) => (k.value, v) } ++ specialBuiltIn.map(r => (r.name, r)).toMap

  // a method used for the log of permission in a correct human readable way, callable from Java
  def toDisplayNames(roles: Iterable[Role]): List[String] = {
    roles.map { r =>
      r match {
        case Custom(rights)                    => s"anon[${rights.displayAuthorizations}]"
        case Builtin(_name, rights)            => _name.value
        case Administrator                     => Administrator.name
        case NoRights                          => NoRights.name
        case NamedCustom(name, permissions)    => name
        case Alias(of, aliasName, description) => s"${aliasName}(${of.name})"
      }
    }.toList.sorted
  }
}

// custom role utility classes to help parse/resolve them
final case class UncheckedCustomRole(name: String, permissions: List[String])
final case class CustomRoleResolverResult(validRoles: List[Role], invalid: List[(UncheckedCustomRole, String)])

/*
 * Facility to parse roles in rudder.
 * This object use stateful resolution of authorisation, so be careful, it may
 * be dependant of what happened elsewhere.
 */
object RudderRoles {

  // our database of roles. Everything is case insensitive, so role name are mapped "to lower string"

  // role names are case insensitive
  implicit val roleOrdering: Ordering[String] = Ordering.comparatorToOrdering(using String.CASE_INSENSITIVE_ORDER)

  private val logger = ApplicationLoggerPure.Auth

  // built-in roles are provided by Rudder core and can be provided by plugins. We assume people knows what they are doing
  // and fewer check are done on them.
  private val builtInCoreRoles = SortedMap[String, Role](Role.allBuiltInRoles.toList*)

  // this is the actual set of currently knowed builin roles
  val builtInRoles: Ref[SortedMap[String, Role]] = Ref.make(builtInCoreRoles).runNow

  private val customRoles = Ref.make(SortedMap.empty[String, Role]).runNow
  private val allRoles: Ref[SortedMap[String, Role]] = ZioRuntime.unsafeRun(for {
    all <- computeAllRoles
    ref <- Ref.make(all)
  } yield ref)

  // compute all roles but be sure that no custom role ever override a builtIn one
  private def computeAllRoles: UIO[SortedMap[String, Role]] = {
    for {
      builtIns <- builtInRoles.get
      customs  <- customRoles.get
    } yield (customs ++ builtIns)
  }

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
               if (resetExisting) SortedMap(newRoles*) else existing ++ newRoles
             })
      all <- computeAllRoles
      _   <- allRoles.set(all)
    } yield ()
  }

  /*
   * Register built-in roles. These roles are typically provided by plugins and can't be unloaded or
   * changed at run-time, contrary to user-provided custom roles.
   * If the registered role is a new name, then it is just added to the list of built-in roles.
   * If the registered role has an existing name, then it *extends* the existing built-in role with the provided Rights.
   *
   * Administrator and NoRights roles are special and can't be overridden.
   */
  def registerBuiltin(roleName: BuiltinName, addedAuthorisations: Set[AuthorizationType]): IOResult[Unit] = {
    val debugAuthz = addedAuthorisations.map(_.id).mkString(", ")
    if (Role.specialBuiltIn.exists(_.name == roleName.value)) {
      // this is a noop, just log that it does nothing
      logger.warn(
        s"The role '${roleName.value}` can not have is permissions updated, ignoring request to add: ${debugAuthz}."
      )
    } else {
      for {
        _   <- logger.info(s"Extending built-in role '${roleName.value}' with permissions: ${debugAuthz}")
        // first already existing, then override with new ones, then be sure that admin/no rights are not changed
        _   <- builtInRoles.update { existing =>
                 val rights = existing.get(roleName.value) match {
                   case Some(r) => Rights(r.rights.authorizationTypes ++ addedAuthorisations)
                   case None    => Rights(addedAuthorisations)
                 }
                 existing + (roleName.value -> Builtin(roleName, rights))
               }
        all <- computeAllRoles
        _   <- allRoles.set(all)
      } yield ()
    }
  }

  // short-cut to register a new plugin role
  def registerBuiltin(role: Builtin): IOResult[Unit] = {
    registerBuiltin(role._name, role.rights.authorizationTypes)
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
          AuthorizationType.parseRight(role) match {
            case Left(err) =>
              Inconsistency(s"Role '${role}' can not be resolved to a named role nor it matches a valid authorization").fail
            case Right(x)  => Role.Custom(Rights(x)).succeed
          }
      }
    })
  }

  // Utility method for parsing several roles at once
  // No consistency enforcement other than if at least on "NoRight" exists, then only NoRight is returned.
  // Role are always trimmed.
  // Unknown roles are filtered out (with log), empty string are ignored.
  def parseRoles(roles: List[String]): UIO[List[Role]] = {
    val nonEmptyRoles = roles.map(_.trim).filter(_.nonEmpty)
    ZIO
      .foreach(nonEmptyRoles) { role =>
        parseRole(role).foldZIO(
          err => logger.warn(err.fullMsg) *> Nil.succeed,
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
                    if (availableReferences.contains(r) || AuthorizationType.parseRight(r).isRight) { Some(r).succeed }
                    else {
                      logger.warn(
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
      AuthorizationType.checkSyntax(role.name) match {
        case Some(_) =>
          Left("'any' and patterns 'kind_[read,edit,write,all] are reserved that can't be used for a custom role")
        case None    =>
          Right(())
      }
    }

    // utility sub-function to resolve one role list in the context of `knownRoles`.
    def resolveOne(knownRoles: Map[String, Role], role: PendingRole): Either[PendingRole, Role.NamedCustom] = {
      import com.softwaremill.quicklens.*
      // role can't be duplicate (case insensitive)
      val toResolve = role.pending.distinctBy(_.toLowerCase)
      val firstPass = toResolve.foldLeft(role.modify(_.pending).setTo(Nil)) {
        case (current, roleName) =>
          logger.logEffect.trace(s"Custom role resolution step: ${current}")
          knownRoles.get(roleName) match {
            // role name not yet resolved or part of a cycle
            case None    =>
              logger.logEffect.trace(s"[${role.role.name}] not found in existing role: ${roleName}")
              // try to parse role as an authorization, it is
              AuthorizationType.parseRight(roleName) match {
                // here, we need to take care of valid authz syntax that are from non loaded plugin, or just non existing
                case Right(x) =>
                  logger.logEffect.trace(s"[${role.role.name}] valid authorization: ${roleName}")
                  current.modify(_.role.permissions).using(_.appended(Role.forRights(x)))
                case Left(_)  =>
                  logger.logEffect.trace(s"[${role.role.name}] not an authorization: ${roleName}")
                  current.modify(_.pending).using(roleName :: _)
              }

            // yeah, role name already resolved !
            case Some(r) =>
              logger.logEffect.trace(s"[${role.role.name}] found existing role: ${roleName}")
              current.modify(_.role.permissions).using(_.appended(r))
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
                val r              = PendingRole(p.role.copy(permissions = p.role.permissions.appended(newRole)), pending = remainingRoles)
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
                case Right(()) =>
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
                           resolveOne(v, PendingRole(Role.NamedCustom(cr.name, Nil), cr.permissions)) match {
                             case Left(stillPending) =>
                               logger.trace(
                                 s"Custom role '${stillPending.role.name}' not fully resolved for roles: ${stillPending.pending.mkString(", ")}"
                               ) *>
                               pending.update(stillPending :: _)
                             case Right(nc)          =>
                               // before adding, remove possible duplicate resolved role
                               val toAdd = nc.copy(permissions = nc.permissions.distinct)
                               logger.trace(s"Custom role '${toAdd.name}' fully resolved") *>
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
      _         <- logger.debug(
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
