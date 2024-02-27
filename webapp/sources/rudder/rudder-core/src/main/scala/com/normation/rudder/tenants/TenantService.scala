/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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

import com.normation.errors.Inconsistency
import com.normation.errors.IOResult
import com.normation.errors.IOStream
import com.normation.inventory.domain.NodeId
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.facts.nodes.MinimalNodeFactInterface
import com.normation.rudder.facts.nodes.NodeFact
import com.normation.rudder.facts.nodes.QueryContext
import scala.collection.MapView
import zio._
import zio.stream.ZStream
import zio.syntax._

/*
 * This interface provide the main entry point for other part of Rudder to know
 * what tenants are currently known on that server so that they can take their
 * informed decision based on that.
 */
trait TenantService {

  def tenantsEnabled: Boolean

  // we use set because tenant list should be small (less than 100) and generally used
  // in a "contains" way.
  def getTenants(): UIO[Set[TenantId]]
  def updateTenants(ids: Set[TenantId]): IOResult[Unit]

  /*
   * Check if the node can be seen in the given query context. Return none if it can't.
   */
  def nodeFilter[A <: MinimalNodeFactInterface](opt: Option[A])(implicit qc: QueryContext): UIO[Option[A]]

  def nodeFilterStream(s: IOStream[NodeFact])(implicit qc: QueryContext): IOStream[NodeFact]

  /*
   * Filter a map of nodes based on tenants
   */
  def nodeFilterMapView(nodes: Ref[Map[NodeId, CoreNodeFact]])(implicit qc: QueryContext): IOResult[MapView[NodeId, CoreNodeFact]]

  /*
   * Get the node with ID if it exists on ref map and qc/tenants allows to get it
   */
  def nodeGetMapView(nodes: Ref[Map[NodeId, CoreNodeFact]], nodeId: NodeId)(implicit
      qc:                   QueryContext
  ): IOResult[Option[CoreNodeFact]]

}

/*
 * A default implementation that just use a global Ref to store the list of known tenants.
 */
object DefaultTenantService {
  def make(tenantIds: IterableOnce[TenantId]): UIO[DefaultTenantService] = {
    for {
      ref <- Ref.make(Set.from(tenantIds))
    } yield new DefaultTenantService(false, ref)
  }
}

class DefaultTenantService(var tenantsEnabled: Boolean, val tenantIds: Ref[Set[TenantId]]) extends TenantService {

  override def getTenants(): UIO[Set[TenantId]] = {
    if (tenantsEnabled) tenantIds.get
    else Set().succeed
  }

  override def updateTenants(ids: Set[TenantId]): IOResult[Unit] = {
    if (tenantsEnabled) tenantIds.set(ids)
    else Inconsistency(s"Error: tenants are not enabled").fail
  }

  override def nodeFilter[A <: MinimalNodeFactInterface](opt: Option[A])(implicit qc: QueryContext): UIO[Option[A]] = {
    opt match {
      case Some(n) => getTenants().map(ids => if (qc.nodePerms.nsc.canSee(n)(ids)) Some(n) else None)
      case None    => None.succeed
    }

  }

  override def nodeFilterMapView(
      nodes:     Ref[Map[NodeId, CoreNodeFact]]
  )(implicit qc: QueryContext): IOResult[MapView[NodeId, CoreNodeFact]] = {
    if (qc.nodePerms.isNone) {
      MapView().succeed
    } else {
      for {
        ts <- getTenants()
        ns <- nodes.get
      } yield ns.view.filter { case (_, n) => qc.nodePerms.canSee(n)(ts) }
    }
  }

  override def nodeFilterStream(s: IOStream[NodeFact])(implicit qc: QueryContext): IOStream[NodeFact] = {
    if (qc.nodePerms.isNone) ZStream.empty
    else {
      ZStream
        .fromZIO(getTenants())
        .cross(s)
        .collect { case (tenantIds, n) if (qc.nodePerms.canSee(n)(tenantIds)) => n }
    }
  }

  override def nodeGetMapView(nodes: Ref[Map[NodeId, CoreNodeFact]], nodeId: NodeId)(implicit
      qc:                            QueryContext
  ): IOResult[Option[CoreNodeFact]] = {
    if (qc.nodePerms.isNone) None.succeed
    else {
      for {
        ts <- getTenants()
        ns <- nodes.get
      } yield ns.get(nodeId).filter(qc.nodePerms.canSee(_)(ts))
    }
  }
}
