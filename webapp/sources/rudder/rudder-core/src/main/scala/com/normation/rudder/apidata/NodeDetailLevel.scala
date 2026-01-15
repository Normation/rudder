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

package com.normation.rudder.apidata

import enumeratum.Enum
import enumeratum.EnumEntry
import enumeratum.EnumEntry.Lowercase

sealed trait NodeDetailLevel {
  def fields: Set[String]
}

case object MinimalDetailLevel extends NodeDetailLevel {
  val fields = NodeDetailLevel.minimalFields.toSet
}

case object DefaultDetailLevel extends NodeDetailLevel {
  val fields = NodeDetailLevel.defaultFields.toSet
}

case object FullDetailLevel extends NodeDetailLevel {
  val fields = NodeDetailLevel.allFields.toSet
}

final case class CustomDetailLevel private (
    base:         NodeDetailLevel,
    customFields: Set[String]
) extends NodeDetailLevel {
  val fields: Set[String] = base.fields ++ customFields
}

sealed trait BaseDetailLevel extends EnumEntry with Lowercase {
  def priority:       Int
  def toDetailsLevel: NodeDetailLevel = this match {
    case BaseDetailLevel.Minimal => MinimalDetailLevel
    case BaseDetailLevel.Default => DefaultDetailLevel
    case BaseDetailLevel.Full    => FullDetailLevel
  }
}
object BaseDetailLevel       extends Enum[BaseDetailLevel]    {
  case object Full    extends BaseDetailLevel { override def priority: Int = 2 }
  case object Minimal extends BaseDetailLevel { override def priority: Int = 1 }
  case object Default extends BaseDetailLevel { override def priority: Int = 0 }

  def values: IndexedSeq[BaseDetailLevel] = findValues
}

object CustomDetailLevel {
  /* Separate base fields from "minimal", "default", "full" keywords, and custom fields. All unknown fields are simply ignored. */
  private val allFieldsSet: Set[String] = NodeDetailLevel.allFields.toSet

  def apply(fields: Set[String]): NodeDetailLevel = {
    val customFields = fields.intersect(allFieldsSet)
    val baseFields   = fields.diff(allFieldsSet).flatMap(BaseDetailLevel.withNameOption)

    val base = baseFields.maxByOption(_.priority).map(_.toDetailsLevel).getOrElse(DefaultDetailLevel)
    if (customFields.isEmpty) {
      base
    } else {
      CustomDetailLevel(base, customFields)
    }
  }
}

/*
 *
 */
sealed trait NodeFileFormat
object NodeFileFormat {
  case object V1 extends NodeFileFormat
}

// this is for NodeFileFormat.V1
object NodeDetailLevel {

  val otherDefaultFields: List[String] = List(
    "state",
    "os",
    "architectureDescription",
    "ram",
    "machine",
    "ipAddresses",
    "description",
    "acceptanceDate",
    "lastInventoryDate",
    "lastRunDate",
    "policyServerId",
    "managementTechnology",
    "properties",
    "policyMode",
    "timezone",
    "tenant"
  )

  val otherAllFields: List[String] = List(
    "accounts",
    "bios",
    "controllers",
    "environmentVariables",
    "fileSystems",
    "instanceId",
    "managementTechnologyDetails",
    "memories",
    "networkInterfaces",
    "processes",
    "processors",
    "slots",
    "software",
    "softwareUpdate",
    "sound",
    "storage",
    "ports",
    "videos",
    "virtualMachines"
  )

  val minimalFields: List[String] = List("id", "hostname", "status")
  val defaultFields: List[String] = minimalFields ::: otherDefaultFields
  val allFields:     List[String] = defaultFields ::: otherAllFields

}
