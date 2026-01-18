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

import com.normation.inventory.domain.AgentType
import com.normation.rudder.domain.policies.PolicyTypes
import com.normation.rudder.services.policies.ComponentId
import com.normation.utils.Utils.*
import enumeratum.*
import org.apache.commons.text.StringEscapeUtils

/**
 * A name, used as an identifier, for a policy.
 * The name must be unique among all policies!
 *
 * TODO : check case sensitivity and allowed chars.
 *
 */
final case class TechniqueName(value: String) extends AnyVal with Ordered[TechniqueName] {
  override def compare(that: TechniqueName): Int = this.value.compare(that.value)

  override def toString: String = value
}

/**
 * Unique ID for a policy.
 * A policy ID is built from the policy name, unique
 * among all policies, and a version for that policy.
 */
final case class TechniqueId(name: TechniqueName, version: TechniqueVersion) extends Ordered[TechniqueId] {
  // intended for debug/log, not serialization
  def debugString:    String      = serialize
  // a technique
  def serialize:      String      = name.value + "/" + version.serialize
  def withDefaultRev: TechniqueId = TechniqueId(name, version.withDefaultRev)

  override def compare(that: TechniqueId): Int = {
    val c = this.name.compare(that.name)
    if (c == 0) this.version.compare(that.version)
    else c
  }

  override def toString: String = serialize
}

object TechniqueId {
  def parse(s: String): Either[String, TechniqueId] = {
    s.split("/").toList match {
      case n :: v :: Nil =>
        TechniqueVersion.parse(v).map(x => TechniqueId(TechniqueName(n), x))
      case _             =>
        Left(
          s"Error when parsing '${s}' as a technique id. It should have format 'techniqueName/version+rev' (with +rev optional)"
        )
    }
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
  object Kind {
    case object Pre  extends Kind
    case object Post extends Kind
  }

}

/*
 * A run hook is an (agent specific) action that should
 * be run only one time per node per run.
 */
final case class RunHook(
    bundle: String, // name of the hook to execute. The actual, agent dependent bundle method name can be derived from it

    kind:       RunHook.Kind,
    report:     RunHook.Report,
    parameters: List[RunHook.Parameter]
)

final case class AgentConfig(
    agentType:      AgentType,
    templates:      List[TechniqueTemplate],
    files:          List[TechniqueFile],
    bundlesequence: List[BundleName],
    runHooks:       List[RunHook]
)

/**
 * A type that tells if the Technique supports directive by directive
 * generation or not.
 */
sealed abstract class TechniqueGenerationMode(override val entryName: String) extends EnumEntry {
  def name: String = entryName
}

object TechniqueGenerationMode extends Enum[TechniqueGenerationMode] {

  /*
   * This technique does not support mutiple directives on the same node
   * but if the directive parameters are merged.
   * This is the historical way of working for Rudder techniques.
   */
  case object MergeDirectives extends TechniqueGenerationMode("merged")

  /*
   * The technique supports several independant directives (and so,
   * several technique version or modes).
   */
  case object MultipleDirectives extends TechniqueGenerationMode("separated")

  /*
   * The technique supports several independant directives (and so,
   * several technique version or modes).
   */
  case object MultipleDirectivesWithParameters extends TechniqueGenerationMode("separated-with-parameters")

  def values: IndexedSeq[TechniqueGenerationMode] = findValues

  def parse(value: String): Either[String, TechniqueGenerationMode] = {
    withNameInsensitiveOption(value)
      .toRight(
        s"Value '${value}' is not recognized as TechniqueGenerationMode. Accepted values are: '${values.map(_.name).mkString("', '")}'"
      )
  }
}

/**
 * A structure containing all informations about a technique deprecation
 */
final case class TechniqueDeprecationInfo(message: String) extends AnyVal

/**
 * A Policy is made of a name, a description, and the list of templates name relevant
 * The templates are found thanks to the Descriptor file which holds all the relevant
 * informations
 * A policy may or may not be shown (ex : common which is a system policy)
 * @author Nicolas Charles
 *
 */
final case class Technique(
    id:                  TechniqueId,
    name:                String,
    description:         String,
    agentConfigs:        List[AgentConfig],
    trackerVariableSpec: TrackerVariableSpec,
    rootSection:         SectionSpec, // be careful to not split it from the TechniqueId, else you will not have the good spec for the version

    deprecrationInfo:    Option[TechniqueDeprecationInfo],
    systemVariableSpecs: Set[SystemVariableSpec] = Set(),
    isMultiInstance:     Boolean = false, // true if we can have several instance of this policy

    longDescription:    String = "",
    policyTypes:        PolicyTypes = PolicyTypes.rudderBase,
    generationMode:     TechniqueGenerationMode = TechniqueGenerationMode.MergeDirectives,
    useMethodReporting: Boolean = false
) {

  require(null != id && !isEmpty(id.name.value), "ID is required in policy")
  require(!isEmpty(name), "Name is required in policy")

  /**
   * Utility method that retrieve all templates IDs
   * Be carefully, you will get all templates for all agents
   */
  val templatesIds: Set[TechniqueResourceId] = agentConfigs.flatMap(cfg => cfg.templates.map(_.id)).toSet

  def getAllVariableSpecs: Map[ComponentId, VariableSpec] = {
    val inputVars = rootSection
      .getAllVariablesBySection(Nil)
      .map {
        case (parents, spec) =>
          val reportId = spec.id.orElse(parents.headOption.flatMap(_.id))
          (ComponentId(spec.name, parents.map(_.name), reportId), spec)
      }
      .toMap

    val specialVars =
      (this.systemVariableSpecs.toSeq :+ this.trackerVariableSpec).map(s => (ComponentId(s.name, Nil, None), s)).toMap

    // we should not have redefinition - but with that, a special var could overwrite an input one
    inputVars ++ specialVars
  }

  // Escape the description, so that text cannot be used to inject anything in display
  def escapedDescription: String = {
    StringEscapeUtils.escapeHtml4(description)
  }
}

/**
 * The representation of a bundle name, used for the bundlesequence
 */
opaque type BundleName = String
object BundleName {

  // in CFEngine, we don't want +- in names
  def escape(s: String): String = s.replaceAll("[-+]", "_")

  def apply(s: String): BundleName = escape(s)

  extension (x: BundleName) {
    def value: String = x

    // sometimes we need an other name in test \o/
    def getValue: String = x
  }
}

object Technique {
  def normalizeName(name: String): String = {
    name.replaceAll("""\s""", "").toLowerCase
  }
}
