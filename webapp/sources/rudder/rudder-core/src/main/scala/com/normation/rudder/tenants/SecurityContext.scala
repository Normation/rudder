/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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
package com.normation.rudder.tenants

import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.eventlog
import java.time.Instant
import zio.*
import zio.json.*
import zio.syntax.*

/*
 * Business objects in Rudder can be separated in tenants, ie in arbitrary segment
 * identified by a name. That name is used to tag objects that can be isolated in
 * tenants.
 * Object that can be put in tenants have a `HasSecurityTag[Object]` given instance.
 *
 * People can access nodes and other objects with a security tags based on a
 * security context. For now, the security context is only a tenant access grant,
 * ie a permit to access all, none, or some tenants and the object they contain.
 *
 * The security context is carried by a `QueryContext` - abbreviated `qc` - for read
 * operations) or a `ChangeContext` - abbreviated `cc` - for change operation.
 */

/* `TenantAccessGrant` defines the list of tenants a user has access to.
 * For now, there is only three cases:
 * - access all or none objects in , whatever properties the node has
 * - access nodes only if they belong to one of the listed tenants.
 */
sealed trait TenantAccessGrant {
  def value:     String
  // serialize into a string that can be parsed by parse `NodeSecurityContext.parse`
  def serialize: String

  def toSecurityTag: Option[SecurityTag]
}

object TenantAccessGrant {

  // a grant that can see all nodes whatever their security tags
  case object All                                      extends TenantAccessGrant {
    override val value = "all"
    override def serialize:     String              = "*"
    // Be careful here: having the right to see any tenants does mean that the security
    // tag is `None`, not that it is `Some(Open)`, which means "viewable by anyone".
    override def toSecurityTag: Option[SecurityTag] = Option.empty
  }
  // a grant that can't see any node. Very good for performance.
  case object None                                     extends TenantAccessGrant {
    override val value     = "none"
    override def serialize = "-"
    override def toSecurityTag: Option[SecurityTag] = Some(SecurityTag.empty)
  }
  // a grant associated with a list of tenants. If the node share at least one of the
  // tenants, if it can be seen. Be careful, it's really just non-empty interesting (so that adding
  // more tag here leads to more nodes, not less).
  final case class ByTenants(tenants: Chunk[TenantId]) extends TenantAccessGrant {
    override val value: String = s"tags:[${tenants.map(_.value).mkString(", ")}]"
    override def serialize = tenants.map(_.value).mkString(",")
    override def toSecurityTag: Option[SecurityTag] = Some(SecurityTag.ByTenants(tenants))
  }

  /*
   * Tenants name are only alphanumeric (mandatory first) + '-' + '_' with two special cases:
   * - '*' means "all"
   * - '-' means "none"
   * None means "all" for compat
   */
  def parse(tenantString: Option[String], ignoreMalformed: Boolean = true): PureResult[TenantAccessGrant] = {
    parseList(tenantString.map(_.split(",").toList))
  }

  def parseList(tenants: Option[List[String]], ignoreMalformed: Boolean = true): PureResult[TenantAccessGrant] = {
    tenants match {
      case scala.None => Right(TenantAccessGrant.All) // for compatibility with previous versions
      case Some(ts)   =>
        (ts
          .foldLeft(Right(TenantAccessGrant.ByTenants(Chunk.empty)): PureResult[TenantAccessGrant]) {
            case (x: Left[RudderError, TenantAccessGrant], _) => x
            case (Right(t1), t2)                              =>
              t2.strip() match {
                case "*"                       => Right(t1.plus(TenantAccessGrant.All))
                case "-"                       => Right(TenantAccessGrant.None)
                case TenantId.checkTenantId(v) => Right(t1.plus(TenantAccessGrant.ByTenants(Chunk(TenantId(v)))))
                case x                         =>
                  if (ignoreMalformed) Right(t1.plus(TenantAccessGrant.ByTenants(Chunk.empty)))
                  else {
                    Left(
                      Inconsistency(
                        s"Value '${x}' is not a valid tenant identifier. It must contains only alpha-num  ascii chars or " +
                        s"'-' and '_' (not in the first) place; or exactly '*' (all tenants) or '-' (none tenants)"
                      )
                    )
                  }
              }
          })
          .map {
            case TenantAccessGrant.ByTenants(c) if (c.isEmpty) => TenantAccessGrant.None
            case x                                             => x
          }
    }
  }

  // json codec for `TenantAccessGrant`
  given encoderTenantAccessGrant: JsonEncoder[TenantAccessGrant] =
    JsonEncoder.string.contramap(_.serialize)
  given decoderTenantAccessGrant: JsonDecoder[TenantAccessGrant] =
    JsonDecoder.string.mapOrFail(s => TenantAccessGrant.parse(Some(s)).left.map(_.fullMsg))

  /*
   * check if the given security context allows to access items marked with a security tag.
   *
   * Important: it is assumed here that all the tenants in a TenantAccessGrant are valid,
   * we don't refine anymore with a set of valid tenants in a specific context.
   * If that need happens, then you need to refine the `TenantAccessGrant`'s tenant list
   * before checking for `canSee` or `canChange`.
   */
  extension (nsc: TenantAccessGrant) {
    def isNone: Boolean = {
      nsc == None
    }

    // can that security tag be seen in that context, given the set of known tenants?
    def canSee(tag: SecurityTag): Boolean = {
      tag match {
        case SecurityTag.ByTenants(tenants) =>
          nsc match {
            case All           => true
            case None          => false
            case ByTenants(ts) => ts.exists(s => tenants.exists(_ == s))
          }
        case SecurityTag.Open               => true
      }
    }

    def canSee(optTag: Option[SecurityTag]): Boolean = {
      optTag match {
        case Some(t)    => canSee(t)
        case scala.None => nsc == TenantAccessGrant.All // only admin can see private nodes
      }
    }

    def canSee[A: HasSecurityTag](n: A): Boolean = {
      canSee(n.security)
    }

    // execute given action if canSee n or fail.
    def canSeeOrFail[A: HasSecurityTag, B](n: A)(zio: IOResult[B]): IOResult[B] = {
      if (canSee(n)) zio else Inconsistency(s"Object '${n.debugId}' can't be accessed in current security context'").fail
    }

    // NodeSecurityContext is a lattice
    def plus(nsc2: TenantAccessGrant): TenantAccessGrant = {
      (nsc, nsc2) match {
        case (None, _)                      => None
        case (_, None)                      => None
        case (All, _)                       => All
        case (_, All)                       => All
        case (ByTenants(c1), ByTenants(c2)) => ByTenants((c1 ++ c2).distinctBy(_.value))
      }
    }
  }

}

/*
 * A query context groups together information that are needed to either filter out
 * some result regarding a security context, or to enhance query efficiency by limiting
 * the item to retrieve. Its granularity is at the item level, not attribute level. For that
 * latter need, by-item solution need to be used (see for ex: SelectFacts for nodes)
 */
final case class QueryContext(
    actor:       EventActor,
    accessGrant: TenantAccessGrant,
    actorIp:     Option[String] = None
) {
  /*
   * Create a fresh new ChangeContext, with a new modification ID, from that QueryContext
   */
  def newCC(message: Option[String] = None): ChangeContext = {
    ChangeContext.newFor(actor, accessGrant, message, actorIp)
  }
}

object QueryContext {
  // for test
  implicit val testQC: QueryContext = QueryContext(eventlog.RudderEventActor, TenantAccessGrant.All)

  // for place that didn't get a real node security context yet
  implicit val todoQC: QueryContext = QueryContext(eventlog.RudderEventActor, TenantAccessGrant.All)

  // for system queries (when rudder needs to look-up things)
  implicit val systemQC: QueryContext = QueryContext(eventlog.RudderEventActor, TenantAccessGrant.All)
}

/*
 * A change context groups together information needed to track a change: who, when, why.
 * It's the same as `QueryContext`, but for updates.
 */
final case class ChangeContext(
    actor:       EventActor,
    accessGrant: TenantAccessGrant,
    modId:       ModificationId,
    eventDate:   Instant,
    message:     Option[String],
    actorIp:     Option[String]
) {
  // update message, when you want precise logs but with same modId/etc
  def withMsg(msg: String): ChangeContext = this.copy(message = Some(msg))

  // some as above when modId is provided
  def withModId(modId: ModificationId): ChangeContext = this.copy(modId = modId)
}

object ChangeContext {

  extension (cc: ChangeContext) {
    def toQC: QueryContext = QueryContext(cc.actor, cc.accessGrant)
  }

  def newFor(
      actor:       EventActor,
      accessGrant: TenantAccessGrant,
      message:     Option[String] = None,
      actorIp:     Option[String] = None
  ): ChangeContext = {
    ChangeContext(
      actor,
      accessGrant,
      ModificationId(java.util.UUID.randomUUID.toString),
      Instant.now(),
      message,
      actorIp
    )

  }

  def newForRudder(message: Option[String] = None, actorIp: Option[String] = None): ChangeContext = {
    newFor(eventlog.RudderEventActor, TenantAccessGrant.All, message, actorIp)
  }
}
