/*
 *************************************************************************************
 * Copyright 2013 Normation SAS
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

package com.normation.rudder.domain.appconfig

import com.normation.errors.Inconsistency
import com.normation.errors.PureResult
import enumeratum.*
import java.util.regex.Pattern

final case class RudderWebPropertyName(value: String) extends AnyVal

object RudderWebPropertyName {
  val patternName: Pattern = Pattern.compile("[a-zA-Z0-9_]+");
}

/**
 * A Property used by the webapp, configured in the Administration page
 */
final case class RudderWebProperty(
    name:        RudderWebPropertyName,
    value:       String,
    description: String
)

/**
 * A little domain language for feature switches
 * (just enabled/disabled with the parsing)
 */
sealed trait FeatureSwitch extends EnumEntry           { def name: String }
object FeatureSwitch       extends Enum[FeatureSwitch] {

  case object Enabled  extends FeatureSwitch { override val name = "enabled"  }
  case object Disabled extends FeatureSwitch { override val name = "disabled" }

  val values: IndexedSeq[FeatureSwitch] = findValues

  def parse(value: String): PureResult[FeatureSwitch] = {
    value match {
      case null | "" => Left(Inconsistency("An empty or null string can not be parsed as a feature switch status"))
      case s         =>
        withNameInsensitiveEither(s.trim).left.map(err => {
          Inconsistency(
            s"Cannot parse the given value as a valid feature switch status: '${err.notFoundName}'. Authorised values are: '${err.enumValues.map(_.name).mkString(", ")}'"
          )
        })
    }
  }
}
