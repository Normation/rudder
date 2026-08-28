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
import com.normation.errors.PureResult
import com.normation.errors.RudderError
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.domain.workflows.ChangeRequest
import com.softwaremill.quicklens.*
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
            TenantAccessGrant.ByTenants(tenants.filter(t => existingTenants.contains(t.id)))
          case TenantStatus.Disabled                 =>
            TenantAccessGrant.None
        }
    }
  }
}

/*
 * For write operation like updating an object, we need to check if the object exists and what are its
 * tenants. We want to manage it by ourselves here to not let a user mixed-up query context.
 * `Lookup` is the type that allows the user to give the query with letting us specify the `QueryContext`.
 *
 * Concretely, a caller writes `roRepo.getOpt(id)` with no context at all, and we will use the right one.
 */
type Lookup[A] = QueryContext ?=> IOResult[Option[A]]

/*
 * For creation-like operations, we need to know how to get the container that will hold the new object
 * to check its rights. Like `Lookup`, with a more specific name.
 */
type Container[C] = QueryContext ?=> IOResult[C]

object Container {
  /*
   * For objects that have no container (global parameters, and anything living at the root of its own
   * namespace): there is then nothing to authorize but the object itself.
   */
  val none: Container[NoContainer] = ZIO.succeed(NoContainer)
}

/*
 * The "no container" marker used in `Container.none`.
 * It is `Open`, ie writable by anyone who may write at all, so it never adds nor removes any right.
 */
sealed trait NoContainer
object NoContainer extends NoContainer {
  implicit val hasSecurityTag: HasSecurityTag[NoContainer] = new HasSecurityTag[NoContainer] {
    extension (a: NoContainer) {
      def security:           Option[SecurityTag] = Some(SecurityTag.Open)
      def isSystem:           Boolean             = false
      def tenantTagLifecycle: TenantTagLifecycle  = TenantTagLifecycle.Monotonic
      def updateSecurityContext(security: Option[SecurityTag]): NoContainer = a
      def debugId: String = "no container"
    }
  }
}

/*
 * Delete operations are inconsistent: sometime, when the object is already absent, it's a noop, sometime an
 * error. We want to normalize toward noop, but in the meantime, we have to specify it with that enum.
 */
enum IfAbsent[+R] {
  case Noop(result: R)
  case Fail(msg: String)
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

  def flatMap[A: HasSecurityTag, B: HasSecurityTag](opt: Option[(A, B)])(using qc: QueryContext): Option[(A, B)] = {
    for {
      (a, b) <- opt
      _      <- check(a)
      _      <- check(b)
    } yield (a, b)
  }

  def flatMap[A: HasSecurityTag, B: HasSecurityTag, C: HasSecurityTag](
      opt: Option[(A, B, C)]
  )(using qc: QueryContext): Option[(A, B, C)] = {
    for {
      (a, b, c) <- opt
      _         <- check(a)
      _         <- check(b)
      _         <- check(c)
    } yield (a, b, c)
  }

  /*
   * Check if the node can be seen in the given query context. Return none if it can't.
   */
  def check[A: HasSecurityTag](a: A)(using qc: QueryContext): Option[A] = flatMap(Some(a))

  /*
   * Collect elements that can be seen
   */
  def collect[A: HasSecurityTag, B, CC[A] <: Iterable[A]](it: CC[A])(
      f: A => B
  )(using qc: QueryContext, bf: BuildFrom[CC[A], B, CC[B]]): CC[B]

  def filter[A: HasSecurityTag, CC[A] <: Iterable[A]](
      it: CC[A]
  )(using qc: QueryContext, bf: BuildFrom[CC[A], A, CC[A]]): CC[A] = {
    collect(it)(identity)
  }

  def filterStream[A: HasSecurityTag](s: IOStream[A])(using qc: QueryContext): IOStream[A]

  /*
   * Filter a map of objects `A` based on tenants
   */
  def filterMapView[ID, A: HasSecurityTag](objs: Ref[Map[ID, A]])(using qc: QueryContext): UIO[MapView[ID, A]]

  /*
   * Get the node with ID if it exists on ref map and qc/tenants allows to get it
   */
  def getMapView[ID, A: HasSecurityTag](objs: Ref[Map[ID, A]], id: ID)(using
      qc: QueryContext
  ): IOResult[Option[A]]

  /*
   * The acting subject's tenant read reach, for direct-SQL repositories that can NOT use the filtering
   * proxies above (they must filter inside the query itself, e.g. so paging and counts stay correct).
   * Instead of a proxy, they get the reader scope as a value and render it to SQL (see
   * `TenantSql.readerScopeFragment`). `canSeeSecurityTag` is the matching in-memory check for rows read
   * outside such a filtered query. Both derive from the same `ReaderScope`, so the SQL and in-memory
   * decisions can not drift.
   */
  def readerScope(using qc: QueryContext): ReaderScope

  def canSeeSecurityTag(tag: Option[SecurityTag])(using qc: QueryContext): Boolean

  /*
   * ----- write operations -----
   *
   * For write operation, we provide an completely managed API so that the user just have to call one
   * `manageXXX` method to correctly handle tenant logic.
   *
   * This is needed, because there is a lot of things to check:
   *   - the tenant feature status (`TenantService.getStatus`),
   *   - the security context each lookup must be done with (see `Lookup` / `Container`),
   *   - the tenants to give to a created object, and what an existing tenants may become,
   *   - the system-object admin-only rule,
   *   - the (possibly restricted) change context the action must run under: some query need system right
   *     to check objects properties and existences, other must absolutely run with user context.
   */

  /*
   * Create a new object `created` under container `into` (or `Container.none` for objects that have no
   * container, like global parameters).
   * - the container must be writable by the actor
   * - the created object is tagged from the actor's writable tenants matching user change context
   */
  def manageCreate[A: HasSecurityTag, C: HasSecurityTag, R](
      created: A,
      into:    Container[C]
  )(using cc: ChangeContext)(
      action:  ChangeContext ?=> A => IOResult[R]
  ): IOResult[R]

  /*
   * Update the existing object with `updated`.
   * - the actor must be able to write the existing object,
   * - the tenants may only evolve as the object's `TenantTagLifecycle` allows (a non-admin can not change it at all).
   *
   * `ifAbsent` says what happens when the object does not exist.
   */
  def manageUpdate[A: HasSecurityTag, B: HasSecurityTag, R](
      updated:  B,
      existing: Lookup[A],
      ifAbsent: IfAbsent[R]
  )(using cc: ChangeContext)(
      action:   ChangeContext ?=> B => IOResult[R]
  ): IOResult[R]

  /*
   * Put an object back in a state it had in the past: event-log rollback, restore from an archive.
   * It is a deliberate, named exception to the write law: in that case, a tenant list
   * may be non monotonic and so old logs can be visible by a tenant that
   * currently can't see the object anymore.
   *
   * Like a save, it also covers the object being absent, because reverting a deletion is a restore too: it
   * is then created back under `into`, with the tag a creation gets.
   */
  def manageRestore[A: HasSecurityTag, B: HasSecurityTag, C: HasSecurityTag, R](
      restored: B,
      existing: Lookup[A],
      into:     Container[C]
  )(using cc: ChangeContext)(
      action:   ChangeContext ?=> B => IOResult[R]
  ): IOResult[R]

  /*
   * Save (upsert) `saved`: create it under `into` if it does not exist yet, update it otherwise.
   * - the create/update decision is taken here based on lookup result
   * - based on the case (new or update), the object tenants may not be the same
   * - the container is only checked on the creation path
   */
  def manageSave[A: HasSecurityTag, B: HasSecurityTag, C: HasSecurityTag, R](
      saved:    B,
      existing: Lookup[A],
      into:     Container[C]
  )(using cc: ChangeContext)(
      action:   ChangeContext ?=> B => IOResult[R]
  ): IOResult[R]

  /*
   * Modify an existing object without submitting a new version of it (enable/disable, accept a technique
   * version, update a group's node list...).
   * Same authorization as `manageUpdate`, but tenant are not changed, we use the ones from `Lookup`
   */
  def manageModify[A: HasSecurityTag, R](
      existing: Lookup[A],
      ifAbsent: IfAbsent[R]
  )(using cc: ChangeContext)(
      action:   ChangeContext ?=> A => IOResult[R]
  ): IOResult[R]

  /*
   * Update an existing object AND (re)place it under `into` in the same operation: both the update and the
   * destination container are authorized.
   */
  def manageUpdateAndMove[A: HasSecurityTag, B: HasSecurityTag, C: HasSecurityTag, R](
      updated:  B,
      existing: Lookup[A],
      into:     Container[C],
      ifAbsent: IfAbsent[R]
  )(using cc: ChangeContext)(
      action:   ChangeContext ?=> B => IOResult[R]
  ): IOResult[R]

  /*
   * Move an existing object into container `into`: the actor must be able to modify the object (as for an
   * update) AND to write into the destination container (as for a creation).
   */
  def manageMove[A: HasSecurityTag, C: HasSecurityTag, R](
      moved:    Lookup[A],
      into:     Container[C],
      ifAbsent: IfAbsent[R]
  )(using cc: ChangeContext)(
      action:   ChangeContext ?=> A => IOResult[R]
  ): IOResult[R]

  /*
   * Delete an existing object.
   * A delete is like a modify (with simpler logic).
   */
  def manageDelete[A: HasSecurityTag, R](
      existing: Lookup[A],
      ifAbsent: IfAbsent[R]
  )(using cc: ChangeContext)(
      action:   ChangeContext ?=> A => IOResult[R]
  ): IOResult[R]

  /*
   * The effect-less version of `manageDelete`, for the object caches which decide under a `Ref.modify`
   * (see `CoreNodeFactRepository`). The object is already at hand, so there is no lookup to do.
   */
  def manageDeletePure[A: HasSecurityTag, R](
      existing: A
  )(using cc: ChangeContext)(
      action:   A => PureResult[R]
  ): PureResult[R]

  /*
   * Check that the actor may perform an admin-only operation.
   * Used for system operations that have no `HasSecurityTag` object to check (e.g. policy server targets).
   * Fails unless the grant is all-tenants.
   */
  def checkAdmin(using cc: ChangeContext): IOResult[Unit]

  /*
   * A change request is, tenant-wise, a compound MODIFY over every configuration object it touches: seeing
   * (or acting on) the whole request implies seeing (or acting on) each of those objects. Its visibility and
   * writability are therefore the AND / fail-closed combination over those objects (see
   * `ChangeRequest.securityTags`): a request that touches an object the actor can not see/modify is entirely
   * invisible / not actionable to them. Objects with no tenant (and rollback / empty requests) are admin-only.
   */
  def isChangeRequestVisible(cr: ChangeRequest)(implicit qc: QueryContext): Boolean

  /*
   * Authorize a write action on a whole change request (submit / validate / deploy / decline / rename): fails
   * unless the actor may modify every object it touches. This is enforced on top of the role check and is
   * fail-closed; the per-object write is still checked again at commit time by the repositories.
   */
  def checkChangeRequestModify(cr: ChangeRequest, cc: ChangeContext): IOResult[Unit]

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
  private def showTenantIds(ids: Set[TenantId]) = ids.toList.map(_.value).sorted.mkString(s",", "','", "'")

  def setTenantEnabled(isEnabled: Boolean): UIO[Unit] = {
    ApplicationLoggerPure.Plugin.info(s"Multi-tenants feature enabled: ${isEnabled}") *>
    ZIO.succeed { _tenantsEnabled = isEnabled }
  }

  override def tenantsEnabled: Boolean = {
    _tenantsEnabled
  }

  override def getStatus: UIO[TenantStatus] = {
    if (tenantsEnabled) {
      tenantIds.get.flatMap(ids => {
        TenantsLogger.debug(
          s"Multi-tenant feature is enabled on tenants: '${showTenantIds(ids)}'"
        ) *>
        TenantStatus.Enabled(ids).succeed
      })
    } else {
      TenantsLogger.debug("Multi-tenant feature is disabled") *>
      TenantStatus.Disabled.succeed
    }
  }

  override def updateTenants(ids: Set[TenantId]): IOResult[Unit] = {
    if (tenantsEnabled) {
      tenantIds
        .getAndSet(ids)
        .flatMap(oldIds =>
          TenantsLogger.info(s"Available tenant list updated from: ${showTenantIds(oldIds)} to: ${showTenantIds(ids)}")
        )
    } else Inconsistency(s"Error: tenants are not enabled").fail
  }
}

class DefaultTenantCheckLogic(tenantService: TenantService) extends TenantCheckLogic {
  override def flatMap[A: HasSecurityTag](opt: Option[A])(implicit qc: QueryContext): Option[A] = {
    opt match {
      case Some(n) =>
        if (qc.accessGrant.canSee(n)) {
          TenantsLogger.logEffect.trace(s"User '${qc.actor.name}' can see ${n.debugId}")
          Some(n)
        } else {
          TenantsLogger.logEffect.trace(s"User '${qc.actor.name}' can not see ${n.debugId}")
          None
        }
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

  override def collect[A: HasSecurityTag, B, CC[A] <: Iterable[A]](
      it: CC[A]
  )(f: A => B)(using qc: QueryContext, bf: BuildFrom[CC[A], B, CC[B]]): CC[B] = {
    if (qc.accessGrant.isNone) bf.fromSpecific(it)(Nil)
    else {
      bf.fromSpecific(it)(it.collect {
        case x if qc.accessGrant.canSee(x.security) => f(x)
      })
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

  override def readerScope(using qc: QueryContext): ReaderScope = qc.accessGrant.toReaderScope

  override def canSeeSecurityTag(tag: Option[SecurityTag])(using qc: QueryContext): Boolean =
    readerScope.canSee(tag)

  // ----- write operations -----------------------------------
  //
  // Every write goes through one `manageXXX`, which is the only place where the tenant status, the lookup
  // contexts, the tag computation and the system-object rule are decided. See the trait for the contract.

  // the tag of an object, for error messages
  private def showTag(security: Option[SecurityTag]): String = {
    security match {
      case None                            => "*"
      case Some(SecurityTag.Open)          => "open"
      case Some(SecurityTag.ByTenants(ts)) => ts.map(_.value).mkString(",")
    }
  }

  // only id and tag, to avoid giving too much information about an object in that case
  private def cantWrite[X: HasSecurityTag](x: X)(using cc: ChangeContext): RudderError = {
    Inconsistency(
      s"Object '${x.debugId}' [${showTag(x.security)}] can't be modified by '${cc.actor.name}' (perm:${cc.accessGrant.value})"
    )
  }

  private def cantDelete[X: HasSecurityTag](x: X)(using cc: ChangeContext): RudderError = {
    Inconsistency(s"Object '${x.debugId}' can't be deleted by ${cc.actor.name}")
  }

  private def whenAbsent[R](ifAbsent: IfAbsent[R]): IOResult[R] = ifAbsent match {
    case IfAbsent.Noop(r)   => r.succeed
    case IfAbsent.Fail(msg) => Inconsistency(msg).fail
  }

  /*
   * The preamble common to every write, whatever it writes, and the reason there is a single write law:
   *   - a system (shared/global) object can only be managed by an administrator (all-tenants grant),
   *   - when the tenant feature is disabled, only an all-tenants grant may write at all,
   *   - an actor with no writable tenant (no grant, or read-only tenants) can not write anything.
   * It returns the tenant status, which the caller needs for the rest of the decision.
   */
  private def writeAllowed[X: HasSecurityTag](
      subject:  X,
      isSystem: Boolean,
      denied:   X => RudderError
  )(using cc: ChangeContext): IOResult[TenantStatus] = {
    // a write operation only considers the tenants on which the user has write ('rw') permission:
    // a read-only ('r') tenant access is dropped, as if the user didn't have the grant for that tenant.
    val writeGrant = cc.accessGrant.restrictToWrite
    tenantService.getStatus.flatMap { status =>
      if (isSystem && cc.accessGrant != TenantAccessGrant.All) {
        systemAdminError(subject.debugId).fail
      } else if (status == TenantStatus.Disabled && writeGrant != TenantAccessGrant.All) {
        denied(subject).fail
      } else if (writeGrant.isNone) {
        denied(subject).fail
      } else {
        status.succeed
      }
    }
  }

  /*
   * The actor may add or keep children under that container.
   * Tenant write-visibility only, but unlike the check on the object itself, this is NOT system-gated,
   * because tenant objects legitimately live under the shared/system root categories.
   */
  private def checkContainer[C: HasSecurityTag](into: Container[C])(using cc: ChangeContext): IOResult[Unit] = {
    into(using cc.toQC).flatMap { c =>
      ZIO
        .unless(cc.accessGrant.canModify(c))(
          Inconsistency(s"Objects can't be created or moved under '${c.debugId}' in the current security context").fail
        )
        .unit
    }
  }

  /*
   * The actor may change/move an existing object: same rules as an update, without a new version to tag.
   */
  private def checkExistingModify[A: HasSecurityTag](e: A)(using cc: ChangeContext): IOResult[Unit] = {
    writeAllowed(e, e.isSystem, cantWrite) *>
    ZIO.unless(cc.accessGrant.canModify(e))(cantWrite(e).fail).unit
  }

  /*
   * Creation: the object gets the actor's writable tenants (an explicitly provided tag is validated), and
   * the action runs under a change context restricted to those tenants.
   */
  private def createLogic[A: HasSecurityTag, R](
      created: A
  )(using cc: ChangeContext)(action: ChangeContext ?=> A => IOResult[R]): IOResult[R] = {
    writeAllowed(created, created.isSystem, cantWrite).flatMap {
      case TenantStatus.Disabled         =>
        // creation when feature disabled: set securityTag to "none" if admin, error in other cases
        cc.accessGrant match {
          case TenantAccessGrant.All => action(created.updateSecurityContext(None))
          case x                     =>
            Inconsistency(
              s"Tenant restricted actor '${cc.actor}' (${cc.accessGrant.serialize}) is trying to create an object when tenant plugin is disabled. '"
            ).fail
        }

      // in the case of creation, we force the user tenant to its (writable) tenant
      case TenantStatus.Enabled(tenants) =>
        cc.accessGrant match {
          case TenantAccessGrant.All           =>
            // admin can choose the tenant list from existing tenants
            created.security match {
              case Some(SecurityTag.ByTenants(ts)) =>
                val unknown = ts.filter(t => !tenants.contains(t))
                if (unknown.nonEmpty) {
                  Inconsistency(
                    s"Object '${created.debugId}' can not be created with tenant(s) '${unknown.map(_.value).mkString(",")}' because they don't exist"
                  ).fail
                } else action(using cc)(created)
              case _                               => action(using cc)(created) // None (admin-only) or Open: no tenant list to check
            }
          case TenantAccessGrant.None          =>
            // already managed by `writeAllowed`
            cantWrite(created).fail
          case TenantAccessGrant.ByTenants(ts) =>
            // restrict to the actual list of writeGrant intersect existing tenants
            val intersect = ts.filter(t => tenants.contains(t.id))
            if (intersect.isEmpty) {
              cantWrite(created).fail
            } else {
              val restrictedCC = cc.modify(_.accessGrant).setTo(TenantAccessGrant.ByTenants(intersect))
              action(using restrictedCC)(created.updateFromChangeContext(using restrictedCC))
            }
        }
    }
  }

  /*
   * Update: the actor must be able to write the existing object, and the tag may only evolve as the
   * object's `TenantTagLifecycle` allows.
   */
  /*
   * The tag a RESTORE gives back to an object.
   * A restore puts an object in a state it had in the past, so unlike an update it may narrow visibility.
   * That will fail is the tenant doesn't exist anymore.
   */
  private def restoredTag[A: HasSecurityTag, B: HasSecurityTag](
      existing: A,
      restored: B,
      tenants:  Set[TenantId]
  ): IOResult[B] = {
    restored.security match {
      case Some(SecurityTag.ByTenants(ts)) if ts.exists(t => !tenants.contains(t)) =>
        Inconsistency(
          s"Object '${restored.debugId}' can not be restored with tenant(s) " +
          s"'${ts.filter(t => !tenants.contains(t)).map(_.value).mkString(",")}' because they don't exist"
        ).fail
      case _                                                                       =>
        ZIO
          .when(!TenantAccessGrant.fromSecurityScope(existing.security).canSee(restored.security)) {
            TenantsLogger.info(
              s"Restoring object '${restored.debugId}' narrows its visibility from '[${showTag(existing.security)}]' to " +
              s"'[${showTag(restored.security)}]', which a regular change is not allowed to do"
            )
          }
          .as(restored)
    }
  }

  private def updateLogic[A: HasSecurityTag, B: HasSecurityTag, R](
      existing:       A,
      updated:        B,
      // `manageRestore` puts an object back in a state it had in the past, so it - and only it - may narrow
      // a monotonic tag. Every other guarantee is unchanged.
      allowNarrowing: Boolean = false
  )(using cc: ChangeContext)(action: ChangeContext ?=> B => IOResult[R]): IOResult[R] = {
    val writeGrant = cc.accessGrant.restrictToWrite

    writeAllowed(updated, existing.isSystem || updated.isSystem, cantWrite).flatMap {
      // when feature is disabled, we keep existing security tag
      case TenantStatus.Disabled         =>
        action(using cc)(updated.updateSecurityContext(existing.security))
      // when feature is enabled, we check consistency
      case TenantStatus.Enabled(tenants) =>
        (if (!writeGrant.canSee(existing)) {
           // the user can't even write the existing object
           cantWrite(existing).fail
         } else if (writeGrant == TenantAccessGrant.All) {
           // only admin (all-tenants write grant) is allowed to change the tenant list of an object.
           // How the tag may evolve depends on the object's tenant-tag lifecycle (see TenantTagLifecycle and ADR).
           def monotonicityError = {
             Inconsistency(
               s"Security tag of object '${updated.debugId}' can not change from '[${showTag(existing.security)}]' to " +
               s"'[${showTag(updated.security)}]': visibility can only grow (add tenants, or set 'open'), never " +
               s"shrink. To narrow the scope, create a new object with the wanted tenant list"
             ).fail
           }

           updated.tenantTagLifecycle match {
             // a restore is allowed to put back any tag the referenced tenants still exist for
             case _ if allowNarrowing          => restoredTag(existing, updated, tenants)
             case TenantTagLifecycle.Monotonic =>
               (existing.security, updated.security) match {
                 // identical tags: nothing changes
                 case (a, b) if (a == b)                        => updated.succeed
                 // no tag submitted: keep the existing one
                 case (before, None)                            => updated.updateSecurityContext(before).succeed
                 // growing to open (top of the lattice) is always allowed
                 case (_, Some(SecurityTag.Open))               => updated.succeed
                 // an open object can not be narrowed
                 case (Some(SecurityTag.Open), _)               => monotonicityError
                 // from none or a tenant list to a tenant list: tenants can only be added, and added ones must exist
                 case (before, Some(SecurityTag.ByTenants(ts))) =>
                   val previous = before match {
                     case Some(SecurityTag.ByTenants(prev)) => prev.toSet
                     case _                                 => Set.empty[TenantId]
                   }
                   val unknown  = ts.filter(t => !previous.contains(t) && !tenants.contains(t))
                   if (!previous.subsetOf(ts.toSet)) {
                     monotonicityError
                   } else if (unknown.nonEmpty) {
                     Inconsistency(
                       s"Object '${updated.debugId}' security tag can not be updated to '[${ts.map(_.value).mkString(",")}]' " +
                       s"because tenant(s) '${unknown.map(_.value).mkString(",")}' don't exist"
                     ).fail
                   } else {
                     updated.succeed
                   }
               }

             case TenantTagLifecycle.Reassignable =>
               // admin may reassign to any tenant list (including a narrower one); the only constraint is
               // that every referenced tenant must exist.
               (existing.security, updated.security) match {
                 // clearing the tag or opening it are always allowed for a reassignable object
                 case (_, None)                            => updated.succeed
                 case (_, Some(SecurityTag.Open))          => updated.succeed
                 // identical tags: nothing changes
                 case (Some(a), Some(b)) if (a == b)       => updated.succeed
                 // any other tenant list is accepted as long as the referenced tenants exist
                 case (_, Some(SecurityTag.ByTenants(ts))) =>
                   val unknown = ts.filter(t => !tenants.contains(t))
                   if (unknown.isEmpty) {
                     updated.succeed
                   } else {
                     Inconsistency(
                       s"Object '${updated.debugId}' security tag's tenant can not be updated to " +
                       s"'${unknown.map(_.value).mkString(",")}' because it does not exist"
                     ).fail
                   }
               }
           }
         } else {
           // non-admin user: the tenant list can not be changed.
           // Ignore the security tag the request carries and keep the existing tag
           updated.updateSecurityContext(existing.security).succeed
         }).flatMap(up => action(using cc)(up))
    }
  }

  override def manageCreate[A: HasSecurityTag, C: HasSecurityTag, R](
      created: A,
      into:    Container[C]
  )(using cc: ChangeContext)(
      action:  ChangeContext ?=> A => IOResult[R]
  ): IOResult[R] = {
    checkContainer(into) *> createLogic(created)(action)
  }

  override def manageUpdate[A: HasSecurityTag, B: HasSecurityTag, R](
      updated:  B,
      existing: Lookup[A],
      ifAbsent: IfAbsent[R]
  )(using cc: ChangeContext)(
      action:   ChangeContext ?=> B => IOResult[R]
  ): IOResult[R] = {
    existing(using QueryContext.systemQC).flatMap {
      case None    => whenAbsent(ifAbsent)
      case Some(e) => updateLogic(e, updated)(action)
    }
  }

  override def manageRestore[A: HasSecurityTag, B: HasSecurityTag, C: HasSecurityTag, R](
      restored: B,
      existing: Lookup[A],
      into:     Container[C]
  )(using cc: ChangeContext)(
      action:   ChangeContext ?=> B => IOResult[R]
  ): IOResult[R] = {
    existing(using QueryContext.systemQC).flatMap {
      // reverting a deletion is a restore too: the object is created back
      case None    => checkContainer(into) *> createLogic(restored)(action)
      case Some(e) => updateLogic(e, restored, allowNarrowing = true)(action)
    }
  }

  override def manageSave[A: HasSecurityTag, B: HasSecurityTag, C: HasSecurityTag, R](
      saved:    B,
      existing: Lookup[A],
      into:     Container[C]
  )(using cc: ChangeContext)(
      action:   ChangeContext ?=> B => IOResult[R]
  ): IOResult[R] = {
    existing(using QueryContext.systemQC).flatMap {
      // the container is only checked when the object is created into it: an update doesn't change it
      case None    => checkContainer(into) *> createLogic(saved)(action)
      case Some(e) => updateLogic(e, saved)(action)
    }
  }

  override def manageUpdateAndMove[A: HasSecurityTag, B: HasSecurityTag, C: HasSecurityTag, R](
      updated:  B,
      existing: Lookup[A],
      into:     Container[C],
      ifAbsent: IfAbsent[R]
  )(using cc: ChangeContext)(
      action:   ChangeContext ?=> B => IOResult[R]
  ): IOResult[R] = {
    existing(using QueryContext.systemQC).flatMap {
      case None    => whenAbsent(ifAbsent)
      case Some(e) => checkContainer(into) *> updateLogic(e, updated)(action)
    }
  }

  override def manageModify[A: HasSecurityTag, R](
      existing: Lookup[A],
      ifAbsent: IfAbsent[R]
  )(using cc: ChangeContext)(
      action:   ChangeContext ?=> A => IOResult[R]
  ): IOResult[R] = {
    existing(using QueryContext.systemQC).flatMap {
      case None    => whenAbsent(ifAbsent)
      // there is no new version to tag: the object is its own "updated" version, so the tag can not change
      case Some(e) => updateLogic(e, e)(action)
    }
  }

  override def manageMove[A: HasSecurityTag, C: HasSecurityTag, R](
      moved:    Lookup[A],
      into:     Container[C],
      ifAbsent: IfAbsent[R]
  )(using cc: ChangeContext)(
      action:   ChangeContext ?=> A => IOResult[R]
  ): IOResult[R] = {
    moved(using QueryContext.systemQC).flatMap {
      case None    => whenAbsent(ifAbsent)
      case Some(m) => checkExistingModify(m) *> checkContainer(into) *> action(using cc)(m)
    }
  }

  override def manageDelete[A: HasSecurityTag, R](
      existing: Lookup[A],
      ifAbsent: IfAbsent[R]
  )(using cc: ChangeContext)(
      action:   ChangeContext ?=> A => IOResult[R]
  ): IOResult[R] = {
    existing(using QueryContext.systemQC).flatMap {
      case None    => whenAbsent(ifAbsent)
      case Some(e) =>
        writeAllowed(e, e.isSystem, cantDelete) *>
        ZIO.unless(cc.accessGrant.canModify(e))(cantDelete(e).fail) *>
        action(using cc)(e)
    }
  }

  override def manageDeletePure[A: HasSecurityTag, R](
      existing: A
  )(using cc: ChangeContext)(
      action:   A => PureResult[R]
  ): PureResult[R] = {
    val writeGrant = cc.accessGrant.restrictToWrite
    // same law as `manageDelete`, but without effect
    if (existing.isSystem && cc.accessGrant != TenantAccessGrant.All) {
      Left(systemAdminError(existing.debugId))
    } else if (!tenantService.tenantsEnabled && writeGrant != TenantAccessGrant.All) {
      Left(cantDelete(existing))
    } else if (writeGrant.isNone || !cc.accessGrant.canModify(existing)) {
      Left(cantDelete(existing))
    } else {
      action(existing)
    }
  }

  override def checkAdmin(using cc: ChangeContext): IOResult[Unit] = {
    ZIO
      .unless(cc.accessGrant == TenantAccessGrant.All)(
        Inconsistency("This operation on a system object is only allowed to an administrator").fail
      )
      .unit
  }

  override def isChangeRequestVisible(cr: ChangeRequest)(implicit qc: QueryContext): Boolean = {
    ChangeRequest.securityTags(cr).forall(tag => qc.accessGrant.canSee(tag))
  }

  override def checkChangeRequestModify(cr: ChangeRequest, cc: ChangeContext): IOResult[Unit] = {
    val writeGrant = cc.accessGrant.restrictToWrite
    ZIO
      .unless(ChangeRequest.securityTags(cr).forall(tag => writeGrant.canSee(tag)))(
        Inconsistency(
          s"Change request #${cr.id.value} '${cr.info.name}' can not be acted upon in the current security context: " +
          s"it changes objects your tenants do not all allow to modify"
        ).fail
      )
      .unit
  }

  // error raised when a non-administrator (a tenant-restricted actor) tries to manage a system object
  private def systemAdminError(debugId: String): RudderError =
    Inconsistency(s"Only an administrator can create, modify or delete the system object '${debugId}'")
}
