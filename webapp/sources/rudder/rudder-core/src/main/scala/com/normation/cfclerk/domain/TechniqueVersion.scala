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

import com.normation.GitVersion
import com.normation.GitVersion.Revision
import com.normation.utils.*

final case class TechniqueVersion protected (version: Version, rev: Revision) extends Ordered[TechniqueVersion] {
  def compare(v: TechniqueVersion): Int = (version compare v.version) match {
    case 0 =>
      // we can't compare two rev, so just use alpha-num order
      // A rev is always before none (which means "HEAD")
      (rev, v.rev) match {
        case (GitVersion.DEFAULT_REV, GitVersion.DEFAULT_REV) => 0
        case (GitVersion.DEFAULT_REV, _)                      => 1
        case (_, GitVersion.DEFAULT_REV)                      => -1
        case (x, y)                                           => String.CASE_INSENSITIVE_ORDER.compare(x.value, y.value)
      }
    case i => i
  }

  def withDefaultRev: TechniqueVersion = this.copy(rev = GitVersion.DEFAULT_REV)

  // we don't have to check here, revision can be anything
  def withRevision(r: Revision): TechniqueVersion = this.copy(rev = r)

  // intended for debug
  def debugString: String = serialize
  // for serialisation on path
  def serialize:   String = rev match {
    case GitVersion.DEFAULT_REV => version.toVersionString
    case r                      => version.toVersionString + "+" + r.value
  }

  override def toString: String = serialize
}

object TechniqueVersion {

  // predefined object 1.0 because we use it in a lot of places
  val V1_0: TechniqueVersion = new TechniqueVersion(
    Version(0, PartType.Numeric(1), List(VersionPart.After(Separator.Dot, PartType.Numeric(0)))),
    GitVersion.DEFAULT_REV
  )

  /*
   * A technique version is *much* simpler than a full blown version.
   * We can only have: a.b.c.etc+commitId
   *
   * NOTE: it seems that in existing env, sometimes epoch was written when it should
   * not have. So we must accept also leading number+":".
   */
  def apply(v: Version, rev: Revision = GitVersion.DEFAULT_REV): Either[String, TechniqueVersion] = {
    if (
      v.head.isInstanceOf[PartType.Numeric] && v.parts.forall {
        case VersionPart.After(Separator.Dot, PartType.Numeric(_)) => true
        case x                                                     => false
      }
    ) {
      Right(new TechniqueVersion(v, rev))
    } else {
      Left("Technique version must be composed of digits")
    }
  }

  /*
   * Technique parsing is a bit more complex than RuleId/etc
   * because technique version must be parsed, too.
   * We start by splitting on "+", since technique version can't have
   * that character in them, but we still take the last bit of the split
   * for revision, and redo a string separated with + with the first parts
   * (of course, if "+" is authorized in techniques version, we don't know
   * if the '+' is for the version or the revision).
   */
  def parse(value: String): Either[String, TechniqueVersion] = {
    val (v, rev) = {
      val parts = value.split("\\+")
      if (parts.size == 1) {
        (value, GitVersion.DEFAULT_REV)
      } else {
        (parts.take(parts.size - 1).mkString("+"), Revision(parts(parts.size - 1).trim))
      }
    }
    ParseVersion.parse(v).flatMap(TechniqueVersion(_, rev))
  }
}

case class UpstreamTechniqueVersion(parsed: Version) extends Ordered[UpstreamTechniqueVersion] {
  def compare(upsreamTechniqueVersion: UpstreamTechniqueVersion): Int = {
    parsed.compareTo(upsreamTechniqueVersion.parsed)
  }
}
