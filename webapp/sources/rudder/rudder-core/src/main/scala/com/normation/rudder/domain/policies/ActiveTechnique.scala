/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
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

import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.ldap.sdk.GeneralizedTime
import com.normation.utils.DateFormaterService
import org.joda.time.DateTime
import zio.json.*

final case class ActiveTechniqueId(value: String) extends AnyVal

/**
 * An active technique is a technique from the technique library
 * that Rudder administrator choose to mark as "can be used to
 * derive directive from".
 * Non activated techniques are not available to build
 * directives.
 *
 * ActiveTechniques are grouped in the "Active Techniques" set,
 * organized in categories.
 *
 * An active technique keep metadatas about when the technique was
 * activated, what version of the technique are activated, what
 * directives use it.
 */
final case class ActiveTechnique(
    id:                   ActiveTechniqueId,
    techniqueName:        TechniqueName,
    acceptationDatetimes: AcceptationDateTime,
    directives:           List[DirectiveUid] = Nil,
    _isEnabled:           Boolean = true,
    policyTypes:          PolicyTypes = PolicyTypes.rudderBase
) {
  // system object must ALWAYS be ENABLED.
  def isEnabled: Boolean = _isEnabled || policyTypes.isSystem
}

opaque type AcceptationDateTime = Map[TechniqueVersion, DateTime]

object AcceptationDateTime {
  extension (self: AcceptationDateTime) {
    def withNewVersions(vs: Map[TechniqueVersion, DateTime]): AcceptationDateTime = (self ++ vs)
    def versions: Map[TechniqueVersion, DateTime] = self
  }

  def empty: AcceptationDateTime = Map()

  def apply(versions: Map[TechniqueVersion, DateTime]): AcceptationDateTime = versions

  given JsonCodec[AcceptationDateTime] = {
    given JsonFieldDecoder[TechniqueVersion] = JsonFieldDecoder.string.mapOrFail(TechniqueVersion.parse)
    given JsonFieldEncoder[TechniqueVersion] = JsonFieldEncoder.string.contramap(_.serialize)

    given JsonEncoder[DateTime] = JsonEncoder.string.contramap(s => GeneralizedTime(s).toString())
    given JsonDecoder[DateTime] = JsonDecoder.string.mapOrFail { x =>
      GeneralizedTime
        .parse(x)
        .map(x => DateFormaterService.toDateTime(x.instant))
        .toRight(s"Error when parsing '$x' as a generalized time'")
    }

    JsonCodec.map[TechniqueVersion, DateTime]
  }
}
