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

import com.normation.utils.Utils._
import com.normation.utils.HashcodeCaching
import com.normation.inventory.domain.AgentType


/**
 * A name, used as an identifier, for a policy.
 * The name must be unique among all policies!
 *
 * TODO : check case sensivity and allowed chars.
 *
 */
case class TechniqueName(value: String) extends Ordered[TechniqueName] with HashcodeCaching {
  override lazy val toString = value

  override def compare(that: TechniqueName) = this.value.compare(that.value)
}

/**
 * Unique ID for a policy.
 * A policy ID is built from the policy name, unique
 * among all policies, and a version for that policy.
 */
case class TechniqueId(name: TechniqueName, version: TechniqueVersion) extends Ordered[TechniqueId] with HashcodeCaching {
  override def toString() = name.toString + "/" + version.toString

  override def compare(that: TechniqueId): Int = {
    val c = this.name.compare(that.name)
    if (c == 0) this.version.compare(that.version)
    else c
  }
}

final case class AgentConfig(
    agentType      : AgentType
  , templates      : Seq[TechniqueTemplate]
  , files          : Seq[TechniqueFile]
  , bundlesequence : Seq[BundleName]
)

/**
 * A structure containing all informations about a technique deprecation
 */
case class TechniqueDeprecationInfo (message : String)

/**
 * A Policy is made of a name, a description, and the list of templates name relevant
 * The templates are found thanks to the Descriptor file which holds all the relevant
 * informations
 * A policy may or may not be shown (ex : common which is a system policy)
 * @author Nicolas Charles
 *
 */
case class Technique(
    id                     : TechniqueId
  , name                   : String
  , description            : String
  , agentConfigs           : List[AgentConfig]
  , trackerVariableSpec    : TrackerVariableSpec
  , rootSection            : SectionSpec //be careful to not split it from the TechniqueId, else you will not have the good spec for the version
  , deprecrationInfo       : Option[TechniqueDeprecationInfo]
  , systemVariableSpecs    : Set[SystemVariableSpec] = Set()
  , compatible             : Option[Compatible] = None
  , isMultiInstance        : Boolean = false // true if we can have several instance of this policy
  , longDescription        : String = ""
  , isSystem               : Boolean = false
  , providesExpectedReports: Boolean = false //does that Technique comes with a template file (csv) of expected reports ?

) extends HashcodeCaching {

  require(null != id && nonEmpty(id.name.value), "ID is required in policy")
  require(nonEmpty(name), "Name is required in policy")

  /**
   * Utity method that retrieve all templates IDs
   * Be carefull, you will get all templates for all agents
   */
  val templatesIds: Set[TechniqueResourceId] = agentConfigs.flatMap(cfg => cfg.templates.map(_.id)).toSet

  val getAllVariableSpecs = this.rootSection.getAllVariables ++ this.systemVariableSpecs :+ this.trackerVariableSpec
}


/**
 * The representation of a bundle name, used for the bundlesequence
 */
case class BundleName(value : String) extends HashcodeCaching

object Technique {
  def normalizeName(name: String): String = {
    name.replaceAll("""\s""", "").toLowerCase
  }
}
