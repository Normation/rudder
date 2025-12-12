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
import zio.*

/*
 * People can access nodes based on a security context.
 * For now, there is only three cases:
 * - access all or none nodes, whatever properties the node has
 * - access nodes only if they belong to one of the listed tenants.
 */
sealed trait NodeSecurityContext {
  def value:     String
  // serialize into a string that can be parsed by parse `NodeSecurityContext.parse`
  def serialize: String

  def toSecurityTag: Option[SecurityTag]
}

object NodeSecurityContext {

  // a context that can see all nodes whatever their security tags
  case object All                                      extends NodeSecurityContext {
    override val value = "all"
    override def serialize:     String              = "*"
    override def toSecurityTag: Option[SecurityTag] = Option.empty
  }
  // a security context that can't see any node. Very good for performance.
  case object None                                     extends NodeSecurityContext {
    override val value     = "none"
    override def serialize = "-"
    override def toSecurityTag: Option[SecurityTag] = Some(SecurityTag.empty)
  }
  // a security context associated with a list of tenants. If the node share at least one of the
  // tenants, if it can be seen. Be careful, it's really just non-empty interesting (so that adding
  // more tag here leads to more nodes, not less).
  final case class ByTenants(tenants: Chunk[TenantId]) extends NodeSecurityContext {
    override val value: String = s"tags:[${tenants.map(_.value).mkString(", ")}]"
    override def serialize = tenants.map(_.value).mkString(",")
    override def toSecurityTag: Option[SecurityTag] = Some(SecurityTag(tenants))
  }

  /*
   * Tenants name are only alphanumeric (mandatory first) + '-' + '_' with two special cases:
   * - '*' means "all"
   * - '-' means "none"
   * None means "all" for compat
   */
  def parse(tenantString: Option[String], ignoreMalformed: Boolean = true): PureResult[NodeSecurityContext] = {
    parseList(tenantString.map(_.split(",").toList))
  }

  def parseList(tenants: Option[List[String]], ignoreMalformed: Boolean = true): PureResult[NodeSecurityContext] = {
    tenants match {
      case scala.None => Right(NodeSecurityContext.All) // for compatibility with previous versions
      case Some(ts)   =>
        (ts
          .foldLeft(Right(NodeSecurityContext.ByTenants(Chunk.empty)): PureResult[NodeSecurityContext]) {
            case (x: Left[RudderError, NodeSecurityContext], _) => x
            case (Right(t1), t2)                                =>
              t2.strip() match {
                case "*"                       => Right(t1.plus(NodeSecurityContext.All))
                case "-"                       => Right(NodeSecurityContext.None)
                case TenantId.checkTenantId(v) => Right(t1.plus(NodeSecurityContext.ByTenants(Chunk(TenantId(v)))))
                case x                         =>
                  if (ignoreMalformed) Right(t1.plus(NodeSecurityContext.ByTenants(Chunk.empty)))
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
            case NodeSecurityContext.ByTenants(c) if (c.isEmpty) => NodeSecurityContext.None
            case x                                               => x
          }
    }
  }

  /*
   * check if the given security context allows to access items marked with
   * that tag
   */
  implicit class NodeSecurityContextExt(val nsc: NodeSecurityContext) extends AnyVal {
    def isNone: Boolean = {
      nsc == None
    }

    // can that security tag be seen in that context, given the set of known tenants?
    def canSee(nodeTag: SecurityTag)(implicit tenants: Set[TenantId]): Boolean = {
      nsc match {
        case All           => true
        case None          => false
        case ByTenants(ts) => ts.exists(s => nodeTag.tenants.exists(_ == s) && tenants.contains(s))
      }
    }

    def canSee(optTag: Option[SecurityTag])(implicit tenants: Set[TenantId]): Boolean = {
      optTag match {
        case Some(t)    => canSee(t)
        case scala.None => nsc == NodeSecurityContext.All // only admin can see private nodes
      }
    }

    def canSee(n: HasSecurityContext)(implicit tenants: Set[TenantId]): Boolean = {
      canSee(n.security)
    }

    // NodeSecurityContext is a lattice
    def plus(nsc2: NodeSecurityContext): NodeSecurityContext = {
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
