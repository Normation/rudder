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

import cats.data.*
import cats.implicits.*
import com.normation.rudder.api.ApiToken.prefixV2
import com.normation.rudder.facts.nodes.NodeSecurityContext
import enumeratum.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.bouncycastle.util.encoders.Hex
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
 *
 * There are two versions of tokens:
 *
 * * v1: 32 alphanumeric characters stored as clear text
 *       they are also displayed in clear text in the interface.
 * * v2: starting from Rudder 8.1, tokens are still 32 alphanumeric characters,
 *       but are now stored hashed in sha512 (128 characters), prefixed with "v2:".
 *       The tokens are only displayed once at creation.
 *
 * Both can have a `-system` suffix to mark the system token.
 *
 * To make the difference, we use a prefix to the hash value in v2
 *
 * * If it starts with "v2:", it is a v2 SHA512 hash of the token
 * * If it does not start with "v2:", it is a clear-text v1 token
 *   Note: v2 tokens can never start with "v" as they are encoded as en hexadecimal string
 */
case class ApiToken(value: String) extends AnyVal {
  // Avoid printing the value in logs, regardless of token type
  override def toString: String = s"[REDACTED ApiToken]"

  def isHashed: Boolean = {
    value.startsWith(prefixV2)
  }
}

object ApiToken {
  private val tokenSize = 32
  private val prefixV2  = "v2:"

  def hash(clearText: String): String = {
    val digest = MessageDigest.getInstance("SHA-512")
    prefixV2 + new String(Hex.encode(digest.digest(clearText.getBytes(StandardCharsets.UTF_8))), StandardCharsets.UTF_8)
  }

  def generate_secret(tokenGenerator: TokenGenerator, suffix: String = ""): String = {
    tokenGenerator.newToken(tokenSize) + suffix
  }
}

case class ApiVersion(
    value:      Int,
    deprecated: Boolean
)

/*
 * HTTP verbs
 */
sealed abstract class HttpAction(override val entryName: String) extends EnumEntry        {
  def name: String = entryName
}
case object HttpAction                                           extends Enum[HttpAction] {

  case object HEAD   extends HttpAction("head")
  case object GET    extends HttpAction("get")
  // perhaps we should have an "accepted content type"
  // for update verbs
  case object PUT    extends HttpAction("put")
  case object POST   extends HttpAction("post")
  case object DELETE extends HttpAction("delete")

  // no PATCH for now

  def values: IndexedSeq[HttpAction] = findValues

  // sort the action by the importance of the action, please DON'T FORGET TO ADD NEW VALUES HERE
  implicit val orderingHttpAction: Ordering[HttpAction] = new Ordering[HttpAction] {
    val order = List(HEAD, GET, PUT, POST, DELETE)
    override def compare(x: HttpAction, y: HttpAction): Int = {
      order.indexOf(x) - order.indexOf(y)
    }
  }

  def parse(action: String): Either[String, HttpAction] = {
    withNameInsensitiveOption(action)
      .toRight(
        s"Action '${action}' is not recognized as a supported HTTP action, supported actions are ${values.mkString("', '")}"
      )
  }
}

/*
 * An authorization control is done in a path (so we are managing
 * tree by essence).
 *
 * The path can be composed of 3 kinds of segments (as called in rfc3986):
 * - a named segment (ie the name of resources)
 * - a single-segment wildcard (in a glob metaphor, a '*')
 * - a multi-segment wildcard ('**'). This one can only appear one time
 *   as the last segment of the path.
 */

sealed trait AclPathSegment { def value: String }

object AclPathSegment {
  final case class Segment(value: String) extends AclPathSegment
  case object Wildcard                    extends AclPathSegment { val value = "*"  }
  case object DoubleWildcard              extends AclPathSegment { val value = "**" }

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
  def value:   String = parts.toList.map(_.value).mkString("/")
  def parts:   NonEmptyList[AclPathSegment]
  def display: String
}

object AclPath {

  // the full path is enumerated. At least one segment must be given ("/" is not possible
  // in our simpler case)
  final case class FullPath(segments: NonEmptyList[AclPathSegment]) extends AnyVal with AclPath {
    def parts = segments
    def display: String = "/" + value
  }
  // only the root is given, and the path ends with "**". It can even be only "**"
  final case class Root(segments: List[AclPathSegment])             extends AnyVal with AclPath {
    def parts:   NonEmptyList[AclPathSegment] = NonEmptyList.ofInitLast(segments, AclPathSegment.DoubleWildcard)
    def display: String                       = "/"
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
   * A compare method on path that sort them from "most specific" to "most generic"
   * so that!
   * - Segment < Wildcard < DoubleWildcard
   */
  implicit val orderingaAclPath: Ordering[AclPath] = new Ordering[AclPath] {
    // compare: negative if x < y
    override def compare(x: AclPath, y: AclPath): Int = {
      import AclPathSegment.*
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
 * A path may have 0 authorized action, which explicitly means that there
 * is no authorization for that path.
 */
final case class ApiAclElement(path: AclPath, actions: Set[HttpAction]) {
  def display: String = path.value + ":" + actions.map(_.name.toUpperCase()).mkString("[", ",", "]")
}

sealed abstract class ApiAuthorizationKind(override val entryName: String) extends EnumEntry { def name: String = entryName }

object ApiAuthorizationKind extends Enum[ApiAuthorizationKind] {
  case object None extends ApiAuthorizationKind("none")
  case object RO   extends ApiAuthorizationKind("ro")
  case object RW   extends ApiAuthorizationKind("rw")
  /*
   * An ACL (Access Control List) is the exhaustive list of
   * authorized path + the set of action on each path.
   *
   * It's a list, so ordered. If a path matches several entries in the
   * ACL list, only the first one is considered.
   */
  case object ACL  extends ApiAuthorizationKind("acl")

  def values: IndexedSeq[ApiAuthorizationKind] = findValues

  def parse(s: String): Either[String, ApiAuthorizationKind] = {
    withNameInsensitiveOption(s)
      .toRight(
        s"Deserialization error: '${s}' is not a known API authorization kind, possible values are '${values.map(_.name).mkString("', '")}'"
      )
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
object ApiAuthorization       {
  case object None                               extends ApiAuthorization { override val kind: ApiAuthorizationKind = ApiAuthorizationKind.None }
  case object RW                                 extends ApiAuthorization { override val kind: ApiAuthorizationKind = ApiAuthorizationKind.RW   }
  case object RO                                 extends ApiAuthorization { override val kind: ApiAuthorizationKind = ApiAuthorizationKind.RO   }
  final case class ACL(acl: List[ApiAclElement]) extends ApiAuthorization {
    override def kind: ApiAuthorizationKind = ApiAuthorizationKind.ACL
    def debugString:   String               = acl.map(_.display).mkString(";")
  }

  /**
   * An authorization object with ALL authorization,
   * present and future.
   */
  val allAuthz: ACL = ACL(List(ApiAclElement(AclPath.Root(Nil), HttpAction.values.toSet)))
}

/**
 * We have several kind of API accounts:
 * - the "system" account is a success in-memory one, whose token is generated at each start.
 *   It has super authz.
 * - User API accounts are linked to a given user. They get the same rights has their user.
 *   They are only available when a specific plugin enable them.
 * - Standard account are used for public API access.
 *
 */
sealed trait ApiAccountType extends EnumEntry            { def name: String }
object ApiAccountType       extends Enum[ApiAccountType] {
  // system token get special authorization and lifetime
  case object System    extends ApiAccountType { val name = "system" }
  // a token linked to an user account
  case object User      extends ApiAccountType { val name = "user"   }
  // a standard API token, that can be only for public API access
  case object PublicApi extends ApiAccountType { val name = "public" }

  def values: IndexedSeq[ApiAccountType] = findValues
}

sealed trait ApiAccountKind { def kind: ApiAccountType }
object ApiAccountKind       {
  case object System extends ApiAccountKind { val kind: ApiAccountType.System.type = ApiAccountType.System }
  case object User   extends ApiAccountKind { val kind: ApiAccountType.User.type = ApiAccountType.User     }
  final case class PublicApi(
      authorizations: ApiAuthorization,
      expirationDate: Option[DateTime]
  ) extends ApiAccountKind {
    val kind: ApiAccountType.PublicApi.type = ApiAccountType.PublicApi
  }
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
    tokenGenerationDate: DateTime,
    tenants:             NodeSecurityContext
)
