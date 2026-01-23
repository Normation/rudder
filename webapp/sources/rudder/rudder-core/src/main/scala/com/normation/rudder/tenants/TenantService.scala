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

  // get feature status and available tenants in one go
  def getStatus: UIO[TenantStatus]

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
        getStatus.map {
          case TenantStatus.Enabled(existingTenants) =>
            TenantAccessGrant.ByTenants(tenants.filter(t => existingTenants.contains(t)))
          case TenantStatus.Disabled                 =>
            TenantAccessGrant.None
        }
    }
  }
}

/*
 * TenantService is the service in charge with the logic to check/filter items with security
 * tag based on the security (query, change) context.
 */
trait TenantCheckLogic {

  /*
   * Check if the node can be seen in the given query context. Return none if it can't.
   */
  def flatMap[A: HasSecurityTag](opt: Option[A])(using qc: QueryContext): Option[A]

  /*
   * Check if the node can be seen in the given query context. Return none if it can't.
   */
  def filter[A: HasSecurityTag](a: A)(using qc: QueryContext): Option[A] = flatMap(Some(a))

  def filterStream[A: HasSecurityTag](s: IOStream[A])(using qc: QueryContext): IOStream[A]

  /*
   * Filter a map of objects `A` based on tenants
   */
  def filterMapView[ID, A: HasSecurityTag](nodes: Ref[Map[ID, A]])(using qc: QueryContext): UIO[MapView[ID, A]]

  /*
   * Get the node with ID if it exists on ref map and qc/tenants allows to get it
   */
  def getMapView[ID, A: HasSecurityTag](nodes: Ref[Map[ID, A]], id: ID)(using
      qc: QueryContext
  ): IOResult[Option[A]]

  /*
   * Check if the existing object `A` can be updated with the new object `B` given change context.
   * It the tenant feature is disabled, then we just don't change
   *  In case it can, a possibly updated version of the security tag to set to `A` is provided. Else, it's an error.
   *
   *
   */
  def manageUpdate[A: HasSecurityTag, B: HasSecurityTag, C](
      existing:     Option[A],
      updated:      B,
      cc:           ChangeContext,
      tenantStatus: TenantStatus
  )(
      action:       B => IOResult[C]
  ): IOResult[C]

  def manageCreate[A: HasSecurityTag, B](
      created:      A,
      cc:           ChangeContext,
      tenantStatus: TenantStatus
  )(
      action:       A => IOResult[B]
  ): IOResult[B]

  /*
   * Check if the node can be deleted given ChangeContext
   */
  def checkDelete[A: HasSecurityTag](
      existing: A,
      cc:       ChangeContext
  ): Either[RudderError, A]

}

/*
 * A default implementation that just use a global Ref to store the list of known tenants.
 */
object InMemoryTenantService {
  def make(tenantIds: IterableOnce[TenantId]): UIO[InMemoryTenantService] = {
    for {
      ref <- Ref.make(Set.from(tenantIds))
    } yield new InMemoryTenantService(_tenantsEnabled = false, tenantIds = ref)
  }
}

/*
 * `tenantsEnabled` is accessed in a lot of hot path, we prefer not to encapsulate it into a Ref.
 * We still put its modification behind an eval.
 */
class InMemoryTenantService(private var _tenantsEnabled: Boolean, val tenantIds: Ref[Set[TenantId]]) extends TenantService {

  def setTenantEnabled(isEnabled: Boolean): UIO[Unit] = {
    ApplicationLoggerPure.Plugin.info(s"Multi-tenants feature enabled: ${isEnabled}") *>
    ZIO.succeed { _tenantsEnabled = isEnabled }
  }

  override def tenantsEnabled: Boolean = {
    _tenantsEnabled
  }

  override def getStatus: UIO[TenantStatus] = {
    if (tenantsEnabled) tenantIds.get.map(TenantStatus.Enabled(_))
    else TenantStatus.Disabled.succeed
  }

  override def updateTenants(ids: Set[TenantId]): IOResult[Unit] = {
    if (tenantsEnabled) tenantIds.set(ids)
    else Inconsistency(s"Error: tenants are not enabled").fail
  }
}

class DefaultTenantCheckLogic extends TenantCheckLogic {
  override def flatMap[A: HasSecurityTag](opt: Option[A])(implicit qc: QueryContext): Option[A] = {
    opt match {
      case Some(n) => if (qc.accessGrant.canSee(n)) Some(n) else None
      case None    => None
    }
  }

  override def filterMapView[ID, A: HasSecurityTag](
      nodes: Ref[Map[ID, A]]
  )(implicit qc: QueryContext): UIO[MapView[ID, A]] = {
    if (qc.accessGrant.isNone) {
      MapView().succeed
    } else {
      for {
        ns <- nodes.get
      } yield ns.view.filter { case (_, n) => qc.accessGrant.canSee(n) }
    }
  }

  override def filterStream[A: HasSecurityTag](s: IOStream[A])(implicit qc: QueryContext): IOStream[A] = {
    if (qc.accessGrant.isNone) ZStream.empty
    else s.collect { case n if qc.accessGrant.canSee(n) => n }
  }

  override def getMapView[ID, A: HasSecurityTag](cache: Ref[Map[ID, A]], id: ID)(implicit
      qc: QueryContext
  ): UIO[Option[A]] = {
    if (qc.accessGrant.isNone) None.succeed
    else cache.get.map(_.get(id).filter(qc.accessGrant.canSee(_)))
  }

  override def manageUpdate[A: HasSecurityTag, B: HasSecurityTag, C](
      existing:     Option[A],
      updated:      B,
      cc:           ChangeContext,
      tenantStatus: TenantStatus
  )(
      action:       B => IOResult[C]
  ): IOResult[C] = {
    // only id to avoid giving too much info in error in that case
    def error[X: HasSecurityTag](x: X) = {
      val tag = x.security match {
        case None                            => '*'
        case Some(SecurityTag.Open)          => "open"
        case Some(SecurityTag.ByTenants(ts)) => ts.map(_.value).mkString(",")
      }
      Inconsistency(s"Object '${x.debugId}' [${tag}] can't be modified by '${cc.actor.name}' (perm:${cc.accessGrant.value})").fail
    }

    // whatever the status of "existing", if the plugin is disabled and there is a grant
    // different from '*' for user, then return an error
    if (tenantStatus == TenantStatus.Disabled && cc.accessGrant != TenantAccessGrant.All) {
      error(updated)
    } else {
      existing match {
        // creation case
        case None    =>
          tenantStatus match {
            // creation when feature disabled: set securityTag to "none".
            case TenantStatus.Disabled         =>
              action(updated.updateSecurityContext(None))
            // in the case of creation, we force the user tenant to its tenant
            case TenantStatus.Enabled(tenants) =>
              if (cc.accessGrant.canSee(updated)) {
                action(updated)
              } else {
                action(updated.updateFromChangeContext(using cc))
              }
          }

        // when we update an existing item, we must also check that the user can see the previous item
        case Some(e) =>
          tenantStatus match {
            // update when feature is disabled: keep existing security tag if any
            case TenantStatus.Disabled         =>
              action(updated.updateSecurityContext(e.security))
            // update when feature enabled: check consistency
            case TenantStatus.Enabled(tenants) =>
              (if (cc.accessGrant.canSee(e)) {
                 if (cc.accessGrant.canSee(updated)) {

                   (e.security, updated.security) match {
                     // no tenants in updated: existing security info is cleared
                     case (_, None)                            => updated.succeed
                     // if b is open, it's ok
                     case (_, Some(SecurityTag.Open))          => updated.succeed
                     // if both have identical tags, it's ok
                     case (Some(a), Some(b)) if (a == b)       => updated.succeed
                     // case where the tags are different: update only if the tenant exists.
                     // We know that the user has the right to change the security tag because
                     // his access grant is ok on both existing and updated items.
                     case (_, Some(SecurityTag.ByTenants(ts))) =>
                       if (ts.forall(t => tenants.contains(t))) {
                         updated.succeed
                       } else {
                         Inconsistency(
                           s"Object '${updated.debugId}' security tag's tenant can not be updated to '${ts.map(_.value).mkString(",")}' because it does not exist"
                         ).fail
                       }
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
  }

  override def manageCreate[A: HasSecurityTag, B](created: A, cc: ChangeContext, tenantStatus: TenantStatus)(
      action: A => IOResult[B]
  ): IOResult[B] = {
    manageUpdate[A, A, B](None, created, cc, tenantStatus)(action)
  }

  override def checkDelete[A: HasSecurityTag](
      existing: A,
      cc:       ChangeContext
  ): Either[RudderError, A] = {
    if (cc.accessGrant.canSee(existing)) {
      Right(existing)
    } else {
      // only id to avoid giving too much info in error in that case
      Left(Inconsistency(s"Object '${existing.debugId}' can't be deleted by ${cc.actor.name}"))
    }
  }
}
