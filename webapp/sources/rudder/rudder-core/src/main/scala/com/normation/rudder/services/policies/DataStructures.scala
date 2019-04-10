/*
*************************************************************************************
* Copyright 2017 Normation SAS
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

package com.normation.rudder.services.policies

import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TrackerVariable
import com.normation.cfclerk.domain.Variable
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.parameters.ParameterName
import com.normation.rudder.domain.policies.{DirectiveId, GlobalPolicyMode, PolicyMode, RuleId}
import com.normation.utils.HashcodeCaching
import net.liftweb.common.Box

import scala.collection.immutable.TreeMap
import org.joda.time.DateTime
import com.normation.rudder.domain.reports.NodeModeConfig
import com.normation.rudder.exceptions.NotFoundException
import net.liftweb.common.Loggable
import com.normation.cfclerk.domain.TechniqueId
import com.normation.rudder.domain.parameters.Parameter
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.NodeId
import cats.data.NonEmptyList
import com.normation.rudder.domain.logger.PolicyLogger
import com.normation.cfclerk.domain.RunHook
import com.normation.cfclerk.domain.SystemVariableSpec
import com.normation.cfclerk.domain.TrackerVariableSpec
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.domain.TechniqueResourceId
import com.normation.cfclerk.domain.AgentConfig
import com.normation.cfclerk.domain.TechniqueGenerationMode
import com.normation.cfclerk.domain.TechniqueVersion

/*
 * This file contains all the specific data structures used during policy generation.
 * It introduce a set of new concepts:
 * - BundleOrder are a container used to keep a the main ordering property of policies.
 *
 * - InterpolationContext is the set of node properties used to bound (== expand) directive
 *   variables like ${node.properties[foo]}. When we parse a directive parameter, we get a
 *   fonction (InterpolationContext => expandedValue)
 *
 * - then, there is NodeConfiguration, which is the list of ordered Policies for that node.
 *   A Policy is a Technique+(Optionnaly merged) Directives, with variables expanded for
 *   the node.
 *   Policies have some intermediary representation with UnboundPolicyDraft & BoundPolicyDraft
 *   for different step in the process.
 */

final case class BundleOrder(value: String)

object BundleOrder {
  val default: BundleOrder = BundleOrder("")

  /**
   * Comparison logic for bundle: successlly alpha-numeric.
   * The empty string come first.
   * The comparison is stable, meaning that a sorted list
   * with equals values stay in the same order after a sort.
   *
   * The sort is case insensitive.
   */
  def compare(a: BundleOrder, b: BundleOrder): Int = {
    String.CASE_INSENSITIVE_ORDER.compare(a.value, b.value)
  }

  def compareList(a: List[BundleOrder], b: List[BundleOrder]): Int = {

    //only works on list of the same size
    def compareListRec(a: List[BundleOrder], b: List[BundleOrder]): Int = {
      (a, b) match {
        case (ha :: ta, hb :: tb) =>
          val comp = compare(ha,hb)
          if(comp == 0) {
            compareList(ta, tb)
          } else {
            comp
          }
        case _ => //we know they have the same size by construction, so it's a real equality
          0
      }
    }

    val maxSize = List(a.size, b.size).max
    compareListRec(a.padTo(maxSize, BundleOrder.default), b.padTo(maxSize, BundleOrder.default))

  }
}

/**
 * A class that hold all information that can be used to resolve
 * interpolated variables in directives variables.
 * It is by nature node dependent.
 */
case class InterpolationContext(
        nodeInfo        : NodeInfo
      , policyServerInfo: NodeInfo
      , globalPolicyMode: GlobalPolicyMode
        //environment variable for that server
        //must be a case insensitive Map !!!!
      , nodeContext     : TreeMap[String, Variable]
        // parameters for this node
        //must be a case SENSITIVE Map !!!!
      , parameters      : Map[ParameterName, InterpolationContext => Box[String]]
        //the depth of the interpolation context evaluation
        //used as a lazy, trivial, mostly broken way to detect cycle in interpretation
        //for ex: param a => param b => param c => ..... => param a
        //should not be evaluated
      , depth           : Int
)

object InterpolationContext {
  implicit val caseInsensitiveString = new Ordering[String] {
    def compare(x: String, y: String): Int = x.compareToIgnoreCase(y)
  }

  def apply(
        nodeInfo        : NodeInfo
      , policyServerInfo: NodeInfo
      , globalPolicyMode: GlobalPolicyMode
        //environment variable for that server
        //must be a case insensitive Map !!!!
      , nodeContext     : Map[String, Variable]
        // parameters for this node
        //must be a case SENSITIVE Map !!!!
      , parameters      : Map[ParameterName, InterpolationContext => Box[String]]
        //the depth of the interpolation context evaluation
        //used as a lazy, trivial, mostly broken way to detect cycle in interpretation
        //for ex: param a => param b => param c => ..... => param a
        //should not be evaluated
      , depth           : Int = 0
  ) = new InterpolationContext(nodeInfo, policyServerInfo, globalPolicyMode, TreeMap(nodeContext.toSeq:_*), parameters, depth)
}

final case class ParameterForConfiguration(
    name       : ParameterName
  , value      : String
) extends HashcodeCaching

final case object ParameterForConfiguration {
  def fromParameter(param: Parameter) : ParameterForConfiguration = {
    ParameterForConfiguration(param.name, param.value)
  }
}

/**
 * A Parameter Entry has a Name and a Value, and can be freely used within the promises
 * We need the get methods for StringTemplate, since it needs
 * get methods, and @Bean doesn't seem to do the trick
 */
final case class ParameterEntry(
    parameterName : String
  , escapedValue  : String
  , agentType     : AgentType
) {
  // returns the name of the parameter
  def getParameterName() : String = {
    parameterName
  }

  // returns the _escaped_ value of the parameter,
  // compliant with the syntax of CFEngine
  def getEscapedValue() : String = {
    escapedValue
  }
}

final object NodeRunHook {

  // a report id with the corresponding mode to
  // use to make reports.
  final case class ReportOn(id: PolicyId, mode: PolicyMode, technique: String, report: RunHook.Report)

}

/*
 * A node run hook is an (agent specific) action that should
 * be run only one time per node per run.
 * This one is the merged version, for a given node.
 */
final case class NodeRunHook(
    bundle    : String // the kind of hook. The bundle method to call can be derived from that
  , kind      : RunHook.Kind
  , reports   : List[NodeRunHook.ReportOn]
  , parameters: List[RunHook.Parameter]
)

final case class NodeConfiguration(
    nodeInfo    : NodeInfo
  , modesConfig : NodeModeConfig
    // sorted list of policies for the node.
  , policies    : List[Policy]
    // the merged pre-/post-run hooks
  , runHooks    : List[NodeRunHook]
    //environment variable for that server
  , nodeContext : Map[String, Variable]
  , parameters  : Set[ParameterForConfiguration]
  , isRootServer: Boolean = false
) extends HashcodeCaching with Loggable {

  def getTechniqueIds() : Set[TechniqueId] = {
    policies.map( _.technique.id ).toSet
  }
}

/**
 * Unique identifier for the policy.
 * These a general container that can be used to generate the different ID
 * used in rudder: the policyId as used in reports, the unique identifier
 * used to differenciate multi-version technique, etc.
 *
 */
final case class PolicyId(ruleId: RuleId, directiveId: DirectiveId, techniqueVersion: TechniqueVersion) extends HashcodeCaching {

  val value = s"${ruleId.value}@@${directiveId.value}"

  /**
   * Create the value of the Rudder Id from the Id of the Policy and
   * the serial
   */
  def getReportId = value + "@@0" // as of Rudder 4.3, serial is always 0

  lazy val getRudderUniqueId = (techniqueVersion.toString + "_" + directiveId.value).replaceAll("""\W""","_")
}

/*
 * A policy "vars" is all the var data for a policy (expandedVars, originalVars,
 * trackingKey). They are grouped together in policy because some policy can
 * be the result of merging several BoundPolicyDraft
 */
final case class PolicyVars(
    policyId       : PolicyId
  , policyMode     : Option[PolicyMode]
  , expandedVars   : Map[String, Variable]
  , originalVars   : Map[String, Variable] // variable with non-expanded ${node.prop etc} values
  , trackerVariable: TrackerVariable
)

/*
 * The technique bounded to a policy. It is specific to exactly one agent
 */
final case class PolicyTechnique(
    id                     : TechniqueId
  , agentConfig            : AgentConfig
  , trackerVariableSpec    : TrackerVariableSpec
  , rootSection            : SectionSpec //be careful to not split it from the TechniqueId, else you will not have the good spec for the version
  , systemVariableSpecs    : Set[SystemVariableSpec]
  , isMultiInstance        : Boolean = false // true if we can have several instance of this policy
  , isSystem               : Boolean = false
  , generationMode         : TechniqueGenerationMode = TechniqueGenerationMode.MergeDirectives
  , useMethodReporting     : Boolean = false
) extends HashcodeCaching {

  val templatesIds: Set[TechniqueResourceId] = agentConfig.templates.map(_.id).toSet

  val getAllVariableSpecs = this.rootSection.getAllVariables ++ this.systemVariableSpecs :+ this.trackerVariableSpec
}

final object PolicyTechnique {
  def forAgent(technique: Technique, agentType: AgentType): Either[String, PolicyTechnique] = {
    technique.agentConfigs.find( _.agentType == agentType) match {
      case None    => Left(s"Error: Technique '${technique.name}' (${technique.id.toString()}) does not support agent type '${agentType.displayName}'")
      case Some(x) => Right(PolicyTechnique(
          id                     = technique.id
        , agentConfig            = x
        , trackerVariableSpec    = technique.trackerVariableSpec
        , rootSection            = technique.rootSection
        , systemVariableSpecs    = technique.systemVariableSpecs
        , isMultiInstance        = technique.isMultiInstance
        , isSystem               = technique.isSystem
        , generationMode         = technique.generationMode
        , useMethodReporting     = technique.useMethodReporting
      ))
    }
  }
}

/*
 *
 * A policy is a technique bound to one or more directives in the context of a
 * nodes and rules.
 * A technique can be bound to exactly one (directive,rule) in the case of non-multi
 * instance technique, or if the technique supports directive by directive gen.
 * A technique can be bound to several (directive,rule) pairs in the case of
 * multi-instance technique which does not support directive by directive gen, and
 * for which variables parameters were merged. In that case, the policy builder
 * must ensure that all directives are consistent regarding policy mode, priority,
 * "isSystem", agent type, etc.
 *
 * Policy has an ordering (currently, the directive and rule name).
 * If a directive is provided by several rules for that node, we always choose
 * the rules with higher priority for the resulting pair.
 * If a policy is the result of merging several directives, the most prioritary
 * is chosen.
 *
 * That policy is bound to a particular node, so that its variable's values can be
 * specialized given the node context. We also keep original variable values
 * (before replacing ${node.properties} and the like) to be able to construct
 * reporting in all cases.
 *
 * That object is part of a node configuration and is the last abstraction used
 * before actual agent configurations files are generated.

 * Please note that a Directive should really have a Variable of the type TrackerVariable,
 * that will hold the id of the directive to be written in the template
 *
 */
final case class Policy(
    id                 : PolicyId
  , technique          : PolicyTechnique
  , techniqueUpdateTime: DateTime
  , policyVars         : NonEmptyList[PolicyVars]
  , priority           : Int
  , policyMode         : Option[PolicyMode]
  , ruleOrder          : BundleOrder
  , directiveOrder     : BundleOrder
  , overrides          : Set[PolicyId] //a set of other draft overriden by that one
) extends Loggable {

  // here, it is extremely important to keep sorted order
  // List[Map[id, List[Variable]]
  // == map .values (keep order) ==> Iterator[List[Variable]]
  // == .toList (keep order)     ==> List[List[Variable]]
  // == flatten (keep order)     ==> List[Variable]
  val expandedVars    = Policy.mergeVars(policyVars.map( _.expandedVars.values).toList.flatten)
  val originalVars    = Policy.mergeVars(policyVars.map( _.originalVars.values).toList.flatten)
  val trackerVariable = policyVars.head.trackerVariable.spec.cloneSetMultivalued.toVariable(policyVars.map(_.trackerVariable.values).toList.flatten)
}

final object Policy {

  val TAG_OF_RUDDER_ID = "@@RUDDER_ID@@"
  val TAG_OF_RUDDER_MULTI_POLICY = "RudderUniqueID"

  /*
   * A method which change a path for a given policy toward a unique
   * path containing the given directive id
   */
  def makeUniqueDest(path: String, p: Policy): String = {
    val subPath = s"${p.technique.id.name.value}/${p.technique.id.version.toString}"
    path.replaceFirst(subPath, s"${p.technique.id.name.value}/${p.id.getRudderUniqueId}")
  }

  /*
   * Replace the Rudder Unique Id also in the path for input list
   */
  def replaceRudderUniqueId(path: String, p: Policy): String = {
    path.replace(TAG_OF_RUDDER_MULTI_POLICY, p.id.getRudderUniqueId)
  }

  def withParams(p:Policy) : String  = {
    s"${p.technique.id.name.value}(${p.expandedVars.values.map(_.values.headOption.getOrElse(""))})"
  }

  /*
   * merge an ordered seq of variables.
   *
   */
  def mergeVars(vars: Seq[Variable]): Map[String, Variable] = {
    val mergedVars = scala.collection.mutable.Map[String, Variable]()
    for (variable <- vars) {
      variable match {
        case _     : TrackerVariable => // nothing, it's been dealt with already
        case newVar: Variable        =>
          // TODO: #10625 : checked is not used anymore
          if ((!newVar.spec.checked) || (newVar.spec.isSystem)) {
            // Only user defined variables should need to be agregated
          } else {
            val variable = mergedVars.get(newVar.spec.name) match {
              case None =>
                Variable.matchCopy(newVar, setMultivalued = true)
              case Some(existingVariable) => // value is already there
                // hope it is multivalued, otherwise BAD THINGS will happen
                if (!existingVariable.spec.multivalued) {
                  PolicyLogger.warn(s"Attempt to append value into a non multivalued variable '${existingVariable.spec.name}', please report the problem as a bug.")
                }
                existingVariable.copyWithAppendedValues(newVar.values) match {
                  case Left(err) =>
                    PolicyLogger.error(s"Error when merging variables '${existingVariable.spec.name}' (init: ${existingVariable.values.toString} ; " +
                                       s"new val: ${newVar.values.toString}. This is most likely a bug, please report it")
                    existingVariable
                  case Right(v) => v
                }
            }
            mergedVars.put(newVar.spec.name, variable)
          }
      }
    }
    mergedVars.toMap
  }
}

/**
 * This class hold information for a directive:
 * - after that Variable were parsed to look for Rudder parameters
 * - before these parameters are contextualize
 */
final case class ParsedPolicyDraft(
    id               : PolicyId
  , technique        : Technique
  , acceptationDate  : DateTime
  , priority         : Int
  , isSystem         : Boolean
  , policyMode       : Option[PolicyMode]
  , trackerVariable  : TrackerVariable
  , variables        : InterpolationContext => Box[Map[String, Variable]]
  , originalVariables: Map[String, Variable] // the original variable, unexpanded
  , ruleOrder        : BundleOrder
  , directiveOrder   : BundleOrder
) extends HashcodeCaching {

  def toBoundedPolicyDraft(expandedVars: Map[String, Variable]) = {
    BoundPolicyDraft(
        id             = id
      , technique      = technique
      , acceptationDate= acceptationDate
      , expandedVars   = expandedVars
      , originalVars   = originalVariables
      , trackerVariable= trackerVariable
      , priority       = priority
      , isSystem       = isSystem
      , policyMode     = policyMode
      , ruleOrder      = ruleOrder
      , directiveOrder = directiveOrder
      , overrides      = Set()
    )
  }

}

/**
 * This is the draft of the policy. Variable may not be bound yet, and
 * it may not be unique in a node coniguration (i.e: this pre-sorting,
 * pre-merge, pre-filtering Techniques).
 */
final case class BoundPolicyDraft(
    id             : PolicyId
  , technique      : Technique
  , acceptationDate: DateTime
  , expandedVars   : Map[String, Variable] // contains vars with expanded parameters
  , originalVars   : Map[String, Variable] // contains original, pre-compilation, variable values
  , trackerVariable: TrackerVariable
  , priority       : Int
  , isSystem       : Boolean
  , policyMode     : Option[PolicyMode]
  , ruleOrder      : BundleOrder
  , directiveOrder : BundleOrder
  , overrides      : Set[PolicyId] //a set of other draft overriden by that one
) {

  /**
   * Search in the variables of the policy for the TrackerVariable (that should be unique for the moment),
   * and retrieve it, along with bounded variable (or itself if it's bound to nothing)
   * Can throw a lot of exceptions if something fails
   */
  def getDirectiveVariable(): (TrackerVariable, Variable) = {
      trackerVariable.spec.boundingVariable match {
        case None | Some("") | Some(null) => (trackerVariable, trackerVariable)
        case Some(value) =>
          originalVars.get(value) match {
            //should not happen, techniques consistency are checked
            case None => throw new NotFoundException("No valid bounding found for trackerVariable " + trackerVariable.spec.name + " found in directive " + id.directiveId.value)
            case Some(variable) => (trackerVariable, variable)
          }
      }
  }

  def toPolicy(agent: AgentType): Either[String, Policy] = {
    PolicyTechnique.forAgent(technique, agent).map { pt =>
      Policy(
          id
        , pt
        , acceptationDate
        , NonEmptyList.of(PolicyVars(
              id
            , policyMode
            , expandedVars
            , originalVars
            , trackerVariable
          ))
        , priority
        , policyMode
        , ruleOrder
        , directiveOrder
        , overrides
      )
    }
  }
}

final case class RuleVal(
    ruleId            : RuleId
  , nodeIds           : Set[NodeId]
  , parsedPolicyDrafts: Seq[ParsedPolicyDraft]
) extends HashcodeCaching
