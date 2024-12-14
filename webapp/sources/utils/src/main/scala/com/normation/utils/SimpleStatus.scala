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

package com.normation.utils

import enumeratum.*

/**
 * A simple "status" object, with two values: enabled and disabled.
 * By default, it's serialized to string for clarity.
 * It is isomorphic to boolean with `true` (resp. `false`) meaning `enabled` (resp. `disabled`)
 */
sealed abstract class SimpleStatus(override val entryName: String) extends EnumEntry {
  def asBool: Boolean
}

object SimpleStatus extends Enum[SimpleStatus] {
  case object Enabled  extends SimpleStatus("enabled")  { override def asBool: Boolean = true  }
  case object Disabled extends SimpleStatus("disabled") { override def asBool: Boolean = false }

  def values: IndexedSeq[SimpleStatus] = findValues

  def parse(s: String): Either[String, SimpleStatus] = {
    withNameInsensitiveOption(s)
      .toRight(
        s"Value '${s}' is not recognized as SimpleStatus. Accepted values are: '${values.map(_.entryName).mkString("', '")}'"
      )
  }

  def fromBool(b: Boolean): SimpleStatus = if (b) Enabled else Disabled
}
