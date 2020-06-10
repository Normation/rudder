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

object RunHook {
/*
 * This data structure holds the agent specific
 * run hooks.
 * We can have pre- or post- hooks, but their
 * composition is the same
 */

  // hooks report name and value
  final case class Report(name: String, value: Option[String])

  // hooks parameters
  final case class Parameter(name: String, value: String)

  sealed trait Kind
  final object Kind {
    final case object Pre  extends Kind
    final case object Post extends Kind
  }

}

/*
 * A run hook is an (agent specific) action that should
 * be run only one time per node per run.
 */
final case class RunHook(
    bundle    : String // name of the hook to execute. The actual, agent dependent bundle method name can be derived from it
  , kind      : RunHook.Kind
  , report    : RunHook.Report
  , parameters: List[RunHook.Parameter]
)

final case class AgentConfig(
    agentType      : AgentType
  , templates      : List[TechniqueTemplate]
  , files          : List[TechniqueFile]
  , bundlesequence : List[BundleName]
  , runHooks       : List[RunHook]
)

/**
 * A type that tells if the Technique supports directive by directive
 * generation or not.
 */
sealed trait TechniqueGenerationMode {
  def name: String
}

final object TechniqueGenerationMode {

  /*
   * This technique does not support mutiple directives on the same node
   * but if the directive parameters are merged.
   * This is the historical way of working for Rudder techniques.
   */
  final case object MergeDirectives extends TechniqueGenerationMode {
    override val name = "merged"
  }

  /*
   * The technique supports several independant directives (and so,
   * several technique version or modes).
   */
  final case object MultipleDirectives extends TechniqueGenerationMode {
    override val name = "separated"
  }

  /*
   * The technique supports several independant directives (and so,
   * several technique version or modes).
   */
  final case object MultipleDirectivesWithParameters extends TechniqueGenerationMode {
    override val name = "separated-with-parameters"
  }

  def allValues = ca.mrvisser.sealerate.values[TechniqueGenerationMode]

  def parse(value: String): Option[TechniqueGenerationMode] = {
    val v = value.toLowerCase
    allValues.find( _.name == v)
  }
}

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
  , generationMode         : TechniqueGenerationMode = TechniqueGenerationMode.MergeDirectives
  , useMethodReporting     : Boolean = false
) extends HashcodeCaching {

  require(null != id && !isEmpty(id.name.value), "ID is required in policy")
  require(!isEmpty(name), "Name is required in policy")

  /**
   * Utity method that retrieve all templates IDs
   * Be carefull, you will get all templates for all agents
   */
  val templatesIds: Set[TechniqueResourceId] = agentConfigs.flatMap(cfg => cfg.templates.map(_.id)).toSet

  val getAllVariableSpecs = this.rootSection.getAllVariables ++ this.systemVariableSpecs :+ this.trackerVariableSpec

  // Escape the description, so that text cannot be used to inject anything in display
  def escapedDescription: String = {
    xml.Utility.escape(description)
  }
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
