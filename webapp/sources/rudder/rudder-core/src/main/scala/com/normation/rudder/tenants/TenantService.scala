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
import com.normation.errors.RudderError
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import scala.collection.MapView
import zio.*
import zio.stream.ZStream
import zio.syntax.*

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
   * Logic to update a TenantAccessGrant based on the list of tenants and if the logic is
   * enabled.
   * `All` and `None` case are left as they are, but if the grant is by tenant, then only
   * grant to existing tenants if the service is available, or `None` otherwise.
   */
  def refineTenantAccessGrant(tag: TenantAccessGrant): UIO[TenantAccessGrant] = {
    tag match {
      case TenantAccessGrant.All                => TenantAccessGrant.All.succeed
      case TenantAccessGrant.None               => TenantAccessGrant.None.succeed
      case TenantAccessGrant.ByTenants(tenants) =>
        if (tenantsEnabled) {
          getTenants().map(existingTenants => TenantAccessGrant.ByTenants(tenants.filter(t => existingTenants.contains(t))))
        } else TenantAccessGrant.None.succeed
    }

  }

  // below, should be pure ?

  /*
   * Check if the node can be seen in the given query context. Return none if it can't.
   */
  def filter[A: HasSecurityTag](opt: Option[A])(using qc: QueryContext): UIO[Option[A]]

  def filterStream[A: HasSecurityTag](s: IOStream[A])(using qc: QueryContext): IOStream[A]

  /*
   * Filter a map of objects `A` based on tenants
   */
  def filterMapView[ID, A: HasSecurityTag](nodes: Ref[Map[ID, A]])(using qc: QueryContext): IOResult[MapView[ID, A]]

  /*
   * Get the node with ID if it exists on ref map and qc/tenants allows to get it
   */
  def getMapView[ID, A: HasSecurityTag](nodes: Ref[Map[ID, A]], id: ID)(using
      qc: QueryContext
  ): IOResult[Option[A]]

  /*
   * Check if the existing object `A` can be updated with the new object `B` given change context.
   * In case it can, a possibly updated version of the security tag to set to `A` is provided. Else, it's an error.
   */
  def manageUpdate[A: HasSecurityTag, B: HasSecurityTag, C](
      existing: Option[A],
      updated:  B,
      cc:       ChangeContext
  )(
      action:   B => IOResult[C]
  ): IOResult[C]

  /*
   * Check if the node can be deleted given ChangeContext
   */
  def checkDelete[A: HasSecurityTag](
      existing:         A,
      cc:               ChangeContext,
      availableTenants: Set[TenantId]
  ): Either[RudderError, A]

}

/*
 * A default implementation that just use a global Ref to store the list of known tenants.
 */
object DefaultTenantService {
  def make(tenantIds: IterableOnce[TenantId]): UIO[DefaultTenantService] = {
    for {
      ref <- Ref.make(Set.from(tenantIds))
    } yield new DefaultTenantService(_tenantsEnabled = false, tenantIds = ref)
  }
}

/*
 * `tenantsEnabled` is accessed in a lot of hot path, we prefer not to encapsulate it into a Ref.
 * We still put its modification behind an eval.
 */
class DefaultTenantService(private var _tenantsEnabled: Boolean, val tenantIds: Ref[Set[TenantId]]) extends TenantService {

  def setTenantEnabled(isEnabled: Boolean): UIO[Unit] = {
    ApplicationLoggerPure.Plugin.info(s"Multi-tenants feature enabled: ${isEnabled}") *>
    ZIO.succeed { _tenantsEnabled = isEnabled }
  }

  override def tenantsEnabled: Boolean = {
    _tenantsEnabled
  }

  override def getTenants(): UIO[Set[TenantId]] = {
    if (tenantsEnabled) tenantIds.get
    else Set().succeed
  }

  override def updateTenants(ids: Set[TenantId]): IOResult[Unit] = {
    if (tenantsEnabled) tenantIds.set(ids)
    else Inconsistency(s"Error: tenants are not enabled").fail
  }

  override def filter[A: HasSecurityTag](opt: Option[A])(implicit qc: QueryContext): UIO[Option[A]] = {
    opt match {
      case Some(n) =>
        getTenants().map { ids =>
          implicit val tenantIds: Set[TenantId] = ids
          if (qc.accessGrant.nsc.canSee(n)) Some(n) else None
        }
      case None    => None.succeed
    }

  }

  override def filterMapView[ID, A: HasSecurityTag](
      nodes: Ref[Map[ID, A]]
  )(implicit qc: QueryContext): IOResult[MapView[ID, A]] = {
    if (qc.accessGrant.isNone) {
      MapView().succeed
    } else {
      for {
        ts <- getTenants()
        ns <- nodes.get
      } yield ns.view.filter { case (_, n) => qc.accessGrant.canSee(n.security)(using ts) }
    }
  }

  override def filterStream[A: HasSecurityTag](s: IOStream[A])(implicit qc: QueryContext): IOStream[A] = {
    if (qc.accessGrant.isNone) ZStream.empty
    else {
      ZStream
        .fromZIO(getTenants())
        .cross(s)
        .collect { case (tenantIds, n) if (qc.accessGrant.canSee(n.security)(using tenantIds)) => n }
    }
  }

  override def getMapView[ID, A: HasSecurityTag](cache: Ref[Map[ID, A]], id: ID)(implicit
      qc: QueryContext
  ): IOResult[Option[A]] = {
    if (qc.accessGrant.isNone) None.succeed
    else {
      for {
        ts <- getTenants()
        ns <- cache.get
      } yield ns.get(id).filter(a => qc.accessGrant.canSee(a.security)(using ts))
    }
  }

  override def manageUpdate[A: HasSecurityTag, B: HasSecurityTag, C](
      existing: Option[A],
      updated:  B,
      cc:       ChangeContext
  )(
      action:   B => IOResult[C]
  ): IOResult[C] = {
    // only id to avoid giving too much info in error in that case
    def error[X: HasSecurityTag](x: X) = {
      val tag = x.security match {
        case None    => '*'
        case Some(t) => t.tenants.map(_.value).mkString(",")
      }
      Inconsistency(s"Object '${x.debugId}' [${tag}] can't be modified by '${cc.actor.name}' (perm:${cc.accessGrant.value})").fail
    }

    getTenants().flatMap { availableTenants =>
      existing match {
        // in the case of creation, we just have to check if the user has actual access on update node fact
        case None    =>
          if (cc.accessGrant.canSee(updated.security)(using availableTenants)) {
            action(updated)
          } else {
            error(updated)
          }
        case Some(e) =>
          (if (cc.accessGrant.canSee(e.security)(using availableTenants)) {
             if (cc.accessGrant.canSee(updated.security)(using availableTenants)) {
               // here, if tenants are not enabled, we must keep the old ones in any case
               if (tenantsEnabled) {
                 // here, we also need to check if the tenant are changing, if the new tenant is in the list
                 // (we already know that the permission is ok, but "*" can see even non existing tenants)
                 // We also accept non modified tenant.
                 (e.security, updated.security) match {
                   case (_, None)                      => updated.succeed
                   case (Some(a), Some(b)) if (a == b) => updated.succeed
                   case (_, Some(b))                   =>
                     if (b.tenants.forall(t => availableTenants.contains(t))) {
                       updated.succeed
                     } else {
                       Inconsistency(
                         s"Object '${updated.debugId}' security tag's tenant can not be updated to '${b.tenants.map(_.value).mkString(",")}' because it does not exist"
                       ).fail
                     }
                 }
               } else {
                 updated.updateSecurityContext(e.security).succeed
               }
             } else {
               error(updated)
             }
           } else {
             error(e)
           }).flatMap(up => action(up))
      }
    }
  }

  override def checkDelete[A: HasSecurityTag](
      existing:         A,
      cc:               ChangeContext,
      availableTenants: Set[TenantId]
  ): Either[RudderError, A] = {
    if (cc.accessGrant.canSee(existing.security)(using availableTenants)) {
      Right(existing)
    } else {
      // only id to avoid giving too much info in error in that case
      Left(Inconsistency(s"Object '${existing.debugId}' can't be deleted by ${cc.actor.name}"))
    }
  }
}
