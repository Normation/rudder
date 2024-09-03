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
package com.normation.rudder.domain.policies

import zio.NonEmptyChunk
import zio.json.*

/*
 * A policy type is a mark in the format `aspect:namespace` applied to rules, directive and techniques.
 * It gives other part of Rudder, especially node configuration and compliance, the opportunity to adjust
 * what is done with things related to these policies.
 * A policy can get several policy types, ie several aspects. But most of time, it will have only one.
 */
final case class PolicyTypes(types: NonEmptyChunk[PolicyTypeName]) {
  // does that policyTypes isSystem ? It is system as soon as it contains the system aspect.
  def isSystem: Boolean = this.types.contains(PolicyTypeName.rudderSystem)
  def isBase:   Boolean = this.types.contains(PolicyTypeName.rudderBase)
  def contains(t: PolicyTypeName): Boolean = types.contains(t)
}

object PolicyTypes {

  // when building PolicyTypes, ensure names are sorted
  def apply(types: NonEmptyChunk[PolicyTypeName]): PolicyTypes = {
    if (types.size == 1) new PolicyTypes(types) else fromCons(types.toCons)
  }

  def fromTypes(t: PolicyTypeName, other: PolicyTypeName*): PolicyTypes = PolicyTypes(NonEmptyChunk(t, other*))

  def fromStrings(s: String, other: String*): PolicyTypes = PolicyTypes(
    NonEmptyChunk(PolicyTypeName(s.trim), other.map(s => PolicyTypeName(s.trim))*)
  )

  def fromCons(list: ::[PolicyTypeName]): PolicyTypes = {
    // not sure why, but sortBy loose the type and loosen it to list.
    list.sortBy[String](_.value).distinctBy(_.value).asInstanceOf[::[PolicyTypeName]] match {
      case h :: Nil  => new PolicyTypes(NonEmptyChunk(h))
      case h :: tail => new PolicyTypes(NonEmptyChunk(h, tail*))
    }
  }

  implicit val encoderPolicyTypes: JsonEncoder[PolicyTypes] = JsonEncoder.chunk[String].contramap(_.types.toChunk.map(_.value))
  implicit val decoderPolicyTypes: JsonDecoder[PolicyTypes] = JsonDecoder.list[String].mapOrFail {
    case Nil       => Left(s"At least one policy type name is needed for deserialization of policy types")
    case h :: tail => Right(PolicyTypes.fromStrings(h, tail*))
  }

  def compat(isSystem: Boolean) = if (isSystem) rudderSystem else rudderBase

  // for simpler compat with Rudder < 8.2 where we only had isSystem
  val rudderSystem: PolicyTypes = PolicyTypes(NonEmptyChunk(PolicyTypeName.rudderSystem))
  val rudderBase:   PolicyTypes = PolicyTypes(NonEmptyChunk(PolicyTypeName.rudderBase))
}

/*
 * Feature are tags with a name.
 */
final case class PolicyTypeName(value: String)

object PolicyTypeName {
  implicit val codecFeatureTagName: JsonCodec[PolicyTypeName] = JsonCodec.string.transform(PolicyTypeName.apply, _.value)

  val rudderSystem: PolicyTypeName = PolicyTypeName("rudder.system")
  val rudderBase:   PolicyTypeName = PolicyTypeName("rudder.base")
}
