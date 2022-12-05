/*
 *************************************************************************************
 * Copyright 2017 Normation SAS
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
package com.normation.rudder.api

import cats.data._
import cats.implicits._
import org.joda.time.DateTime

/**
 * ID of the Account
 */
final case class ApiAccountId(value: String) extends AnyVal

/**
 * Name of the principal, used in event log to know
 * who did actions.
 */
final case class ApiAccountName(value: String) extends AnyVal

/**
 * The actual authentication token.
 * A token is defined with [0-9a-zA-Z]{n}, with n not small.
 */
final case class ApiToken(value: String) extends AnyVal

object ApiToken {

  val tokenRegex = """[0-9a-zA-Z]{12,128}""".r

  def buildCheckValue(value: String): Option[ApiToken] = value.trim match {
    case tokenRegex(v) => Some(ApiToken(v))
    case _             => None
  }
}

case class ApiVersion(
    value:      Int,
    deprecated: Boolean
)

/*
 * HTTP verbs
 */
enum HttpAction(val name: String) {
  case HEAD   extends HttpAction("head")
  case GET    extends HttpAction("get")
// perhaps we should have an "accepted content type"
// for update verbs
  case PUT    extends HttpAction(name = "put")
  case POST   extends HttpAction(name = "post")
  case DELETE extends HttpAction(name = "delete")
  // no PATCH for now
}

object HttpAction {
  def parse(action: String): Either[String, HttpAction] = {
    val lower = action.toLowerCase()
    values.find(_.name == lower).toRight(s"Action '${action}' is not recognized as a supported HTTP action")
  }
}

/*
 * An authorization control is done in a path (so we are managing
 * tree by essense).
 *
 * The path can be composed of 3 kinds of segments (as called in rfc3986):
 * - a named segment (ie the name of resources)
 * - a single-segment wildcard (in a glob metaphor, a '*')
 * - a multi-segment wildcard ('**'). This one can only appear one time
 *   as the last segment of the path.
 */

sealed trait AclPathSegment { def value: String }

final object AclPathSegment {
  final case class Segment(value: String) extends AclPathSegment
  final case object Wildcard              extends AclPathSegment { val value = "*"  }
  final case object DoubleWildcard        extends AclPathSegment { val value = "**" }

  def parse(s: String): Either[String, AclPathSegment] = {
    s.trim() match {
      case ""   => Left("An ACL path segment can not be empty")
      case "*"  => Right(Wildcard)
      case "**" => Right(DoubleWildcard)
      case x    => Right(Segment(x))
    }
  }
}

/*
 *
 */
sealed trait AclPath extends Any {
  def value: String = parts.toList.map(_.value).mkString("/")
  def parts: NonEmptyList[AclPathSegment]
}

final object AclPath {

  // the full path is enumerated. At least one segment must be given ("/" is not possible
  // in our simpler case)
  final case class FullPath(segments: NonEmptyList[AclPathSegment]) extends AnyVal with AclPath {
    def parts = segments
  }
  // only the root is given, and the path ends with "**". It can even be only "**"
  final case class Root(segments: List[AclPathSegment])             extends AnyVal with AclPath {
    def parts = NonEmptyList.ofInitLast(segments, AclPathSegment.DoubleWildcard)
  }

  // parse a path to an acl path.
  // we don't accept empty string and ignore empty subpart,
  // and "**" must be the last segment.
  def parse(path: String): Either[String, AclPath] = {
    @scala.annotation.tailrec
    def doubleWildcardCanOnlyBeLast(l: List[AclPathSegment]): Either[String, Unit] = {
      l match {
        case Nil                                  => Right(())
        case AclPathSegment.DoubleWildcard :: Nil => Right(())
        case AclPathSegment.DoubleWildcard :: t   => Left("Error: you can only use '**' as the last segment of an ACL path")
        case h :: t                               => doubleWildcardCanOnlyBeLast(t)
      }
    }

    for {
      parts  <- path.trim.split("/").filter(_.size > 0).toList.traverse(AclPathSegment.parse)
      _      <- doubleWildcardCanOnlyBeLast(parts)
      parsed <- parts match {
                  case Nil    =>
                    Left("The given path is empty, it can't be a Rudder ACL path")
                  case h :: t =>
                    Right(FullPath(NonEmptyList(h, t)))
                }
    } yield {
      parsed
    }
  }

  /**
   * A compare method on path that sort them from "most specific" to "most genereric"
   * so that!
   * - Segment < Wildcard < DoubleWildcard
   */
  implicit val orderingaAclPath = new Ordering[AclPath] {
    // compare: negative if x < y
    override def compare(x: AclPath, y: AclPath): Int = {
      import AclPathSegment._
      if (x.parts.size == y.parts.size) {
        (x.parts.last, y.parts.last) match {
          case (p1, p2) if (p1 == p2)   => 0
          case (_, DoubleWildcard)      => -1 // "**" last
          case (DoubleWildcard, _)      => 1  // "**" last
          case (_, Wildcard)            => -1
          case (Wildcard, _)            => 1
          case (Segment(a), Segment(b)) => String.CASE_INSENSITIVE_ORDER.compare(a, b)
        }
      } else y.parts.size - x.parts.size // longest (ie most specific) first
    }
  }

}

/*
 * one API authorization is an API with a set of authorized action.
 * A path may have 0 authorized action, which explicity mean that there
 * is no authorization for that path.
 */
final case class ApiAclElement(path: AclPath, actions: Set[HttpAction]) {
  def display = path.value + ":" + actions.map(_.name.toUpperCase()).mkString("[", ",", "]")
}

enum ApiAuthorizationKind(val name: String) {
  case None extends ApiAuthorizationKind("none")
  case RO   extends ApiAuthorizationKind("ro")
  case RW   extends ApiAuthorizationKind("rw")
  /*
   * An ACL (Access Control List) is the exhaustive list of
   * authorized path + the set of action on each path.
   *
   * It's a list, so ordered. If a path matches several entries in the
   * ACL list, only the first one is considered.
   */
  case ACL  extends ApiAuthorizationKind("acl")
}

object ApiAuthorizationKind {
  def parse(s: String): Either[String, ApiAuthorizationKind] = {
    val lc = s.toLowerCase
    values.find(_.name == lc) match {
      case scala.None => Left(s"Unserialization error: '${s}' is not a known API authorization kind ")
      case Some(x)    => Right(x)
    }
  }
}

/**
 * Api authorisation kind.
 * We have 3 levels:
 * - no authorizations (for ex, an unknown user)
 * - read-only / read-write: coarse grained authz with access to all GET (resp everything)
 * - ACL: fine grained authz.
 */
sealed trait ApiAuthorization { def kind: ApiAuthorizationKind }
final object ApiAuthorization {
  final case object None                         extends ApiAuthorization { override val kind = ApiAuthorizationKind.None }
  final case object RW                           extends ApiAuthorization { override val kind = ApiAuthorizationKind.RW   }
  final case object RO                           extends ApiAuthorization { override val kind = ApiAuthorizationKind.RO   }
  final case class ACL(acl: List[ApiAclElement]) extends ApiAuthorization { override def kind = ApiAuthorizationKind.ACL  }

  /**
   * An authorization object with ALL authorization,
   * present and future.
   */
  val allAuthz = ACL(List(ApiAclElement(AclPath.Root(Nil), HttpAction.values.toSet)))
}

/**
 * We have several kind of API accounts:
 * - the "system" account is a success in-memory one, whose token is genererated at each start.
 *   It has super authz.
 * - User API accounts are linked to a given user. They get the same rights has their user.
 *   They are only available when a spcecific plugin enable them.
 * - Standard account are used for public API acess.
 *
 */
enum ApiAccountType(val name: String)       {
  // system token get special authorization and lifetime
  case System    extends ApiAccountType("system")
  // a token linked to an user account
  case User      extends ApiAccountType("user")
  // a standard API token, that can be only for public API access
  case PublicApi extends ApiAccountType("public")
}

enum ApiAccountKind(val kind: ApiAccountType) {
  case System extends ApiAccountKind(ApiAccountType.System)
  case User   extends ApiAccountKind(ApiAccountType.User)
  case PublicApi(
      authorizations: ApiAuthorization,
      expirationDate: Option[DateTime]
  )           extends ApiAccountKind(ApiAccountType.PublicApi)
}

/**
 * An API principal
 */
final case class ApiAccount(
    id:   ApiAccountId,
    kind: ApiAccountKind, // Authentication token. It is a mandatory value, and can't be ""
    // If a token should be revoked, use isEnabled = false.

    name: ApiAccountName, // used in event log to know who did actions.

    token:               ApiToken,
    description:         String,
    isEnabled:           Boolean,
    creationDate:        DateTime,
    tokenGenerationDate: DateTime
)
