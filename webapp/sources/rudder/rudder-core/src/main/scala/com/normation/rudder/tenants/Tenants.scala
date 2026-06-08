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

import enumeratum.*
import scala.util.matching.Regex
import zio.json.*

/*
 * A node can belong to one (or technically more, but we will limit that for now) `tenant`.
 * A `tenant` is a segregation limit defining an isolated zone.
 * Tenants should be \ascii\num_-
 */
final case class TenantId(value: String) extends AnyVal {
  def debugString = value
}

object TenantId {

  implicit val codecTenant: JsonCodec[TenantId] = new JsonCodec[TenantId](
    JsonEncoder.string.contramap(_.value),
    JsonDecoder.string.map(TenantId(_))
  )

  // Tenant d can only be non empty alpha-num and hyphen. Check externally to avoid perf cost
  // at instantiation;
  val checkTenantId: Regex = """^(\p{Alnum}[\p{Alnum}-_]*)$""".r

  // parse a tenant id, returning None if it is not a valid identifier
  def parse(s: String): Option[TenantId] = s match {
    case checkTenantId(v) => Some(TenantId(v))
    case _                => None
  }
}

/*
 * The kind of access a user/API token has on a given tenant:
 * - `Read`     : can only access the tenant objects through read (`QueryContext`) operations
 * - `ReadWrite`: can access the tenant objects through both read and write (`ChangeContext`) operations
 * - `None`     : has no access at all (mostly used as the neutral/absorbing element of the lattice)
 *
 * Serialized form (in the `tenantId:permission` access string): `r` for Read, `rw` for ReadWrite.
 * For backward compatibility, an absent permission means `ReadWrite`.
 */
sealed trait TenantPermission extends EnumEntry {
  // the optional serialized permission token (after the ':'). `ReadWrite` has none for backward compat.
  def token:    Option[String]
  def canRead:  Boolean
  def canWrite: Boolean
  // rank in the read < write lattice, used to merge two permissions on the same tenant
  def rank:     Int
}

object TenantPermission extends Enum[TenantPermission] {
  case object Read      extends TenantPermission {
    override val token    = Some("r")
    override val canRead  = true
    override val canWrite = false
    override val rank     = 1
  }
  case object ReadWrite extends TenantPermission {
    override val token    = scala.None // bare tenant id, for backward compatibility with previous serialization
    override val canRead  = true
    override val canWrite = true
    override val rank     = 2
  }
  case object None      extends TenantPermission {
    override val token    = Some("n")
    override val canRead  = false
    override val canWrite = false
    override val rank     = 0
  }

  override val values: IndexedSeq[TenantPermission] = findValues

  // keep the highest permission of the two (used to merge accesses on the same tenant)
  def lub(a: TenantPermission, b: TenantPermission): TenantPermission = if (a.rank >= b.rank) a else b

  // parse the permission token (the part after the ':'); absence (None) means ReadWrite for backward compat
  def parseToken(token: Option[String]): Option[TenantPermission] = token match {
    case scala.None => Some(ReadWrite)
    case Some("r")  => Some(Read)
    case Some("rw") => Some(ReadWrite)
    case Some("n")  => Some(None)
    case Some(_)    => scala.None
  }
}

/*
 * A `TenantAccess` is the access a user/API token has on a single tenant: a tenant id plus the
 * permission (read or read-write) on it.
 *
 * It is NOT json-serialized as a structure, but as a simple string with the pattern `tenantId:permission`
 * where the permission part is optional:
 * - `zoneA`    -> ReadWrite on `zoneA` (backward compatible with the previous `TenantId`-only serialization)
 * - `zoneA:r`  -> Read on `zoneA`
 * - `zoneA:rw` -> ReadWrite on `zoneA`
 */
final case class TenantAccess(id: TenantId, grant: TenantPermission) {
  def serialize: String = grant.token match {
    case scala.None => id.value
    case Some(t)    => s"${id.value}:${t}"
  }
}

object TenantAccess {
  // convenience: a tenant access defaults to ReadWrite (backward compatible)
  def apply(id: TenantId): TenantAccess = TenantAccess(id, TenantPermission.ReadWrite)

  /*
   * Parse a `tenantId:permission` string. The permission suffix is optional (absent means ReadWrite).
   * Returns None if the tenant id or the permission token is not valid.
   */
  def parse(s: String): Option[TenantAccess] = {
    val (rawId, optToken) = s.split(":", 2) match {
      case Array(id)       => (id, scala.None)
      case Array(id, perm) => (id, Some(perm))
      case _               => (s, scala.None)
    }
    for {
      id    <- TenantId.parse(rawId)
      grant <- TenantPermission.parseToken(optToken)
    } yield TenantAccess(id, grant)
  }
}

/*
 * A data structure representing current tenant feature state
 */
sealed trait TenantStatus
object TenantStatus {
  case object Disabled                       extends TenantStatus
  case class Enabled(tenants: Set[TenantId]) extends TenantStatus
}
