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

package com.normation.cfclerk.domain


import com.normation.GitVersion.RevId
import com.normation.utils._


final case class TechniqueVersion(version: Version, revId: Option[RevId] = None) extends Ordered[TechniqueVersion] {
  def compare(v: TechniqueVersion): Int = (version compare v.version) match {
    case 0 =>
      // we can't compare two revId, so just use alpha-num order
      // A revId is always before none (which means "HEAD")
      (revId, v.revId) match {
        case (None   , None   ) =>  0
        case (None   , _      ) =>  1
        case (_      , None   ) => -1
        case (Some(x), Some(y)) =>  String.CASE_INSENSITIVE_ORDER.compare(x.value, y.value)
      }
    case i => i
  }

  def withDefaultRevId = this.copy(revId = None)

  // intended for debug
  def debugString = serialize
  // for serialisation on path
  def serialize = revId.fold(
    version.toVersionString
  )(
    r => version.toVersionString + "+" + r.value
  )

}

object TechniqueVersion {

  /*
   * A technique version is *much* simpler than a full blown version.
   * We can only have: a.b.c.etc+commitId
   *
   * NOTE: it seems that in existing env, sometimes epoch was written when it should
   * not have. So we must accept also leading number+":".
   */
  protected def apply(v: Version): Either[String, TechniqueVersion] = {
    if((v.head :: v.parts).exists {
      case PartType.Chars(_) => true
      case _                 => false
    }) {
      Left("Technique version must be composed of digits")
    } else {
      Right(new TechniqueVersion(v, None))
    }
  }

  def parse(value: String): Either[String, TechniqueVersion] = {
    val (v, revId) = {
      val parts = value.split("\\+")
      if(parts.size == 1) {
        (value, None)
      } else {
        (parts.take(parts.size-1).mkString("+"), Some(RevId(parts(parts.size-1).trim)))
      }
    }
    ParseVersion.parse(v).map(TechniqueVersion(_,revId))
  }
}

case class UpstreamTechniqueVersion(parsed: Version) extends Ordered[UpstreamTechniqueVersion] {
  def compare(upsreamTechniqueVersion: UpstreamTechniqueVersion): Int = {
    parsed.compareTo(upsreamTechniqueVersion.parsed)
  }
}
