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

import cats.data.NonEmptyList
import com.normation.cfclerk.domain.AgentConfig
import com.normation.cfclerk.domain.RunHook
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.domain.SystemVariableSpec
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueGenerationMode
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueResourceId
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.domain.TrackerVariable
import com.normation.cfclerk.domain.TrackerVariableSpec
import com.normation.cfclerk.domain.Variable
import com.normation.cfclerk.domain.VariableSpec
import com.normation.errors.*
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.logger.PolicyGenerationLogger
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.PolicyTypes
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.reports.NodeModeConfig
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.typesafe.config.ConfigValue
import org.joda.time.DateTime
import scala.collection.immutable.TreeMap

/*
 * This file contains all the specific data structures used during policy generation.
 * It introduce a set of new concepts:
 * - BundleOrder are a container used to keep a the main ordering property of policies.
 *
 * - InterpolationContext is the set of node properties used to bound (== expand) directive
 *   variables like ${node.properties[foo]}. When we parse a directive parameter, we get a
 *   function (InterpolationContext => expandedValue)
 *
 * - then, there is NodeConfiguration, which is the list of ordered Policies for that node.
 *   A Policy is a Technique+(Optionally merged) Directives, with variables expanded for
 *   the node.
 *   Policies have some intermediary representation with UnboundPolicyDraft & BoundPolicyDraft
 *   for different step in the process.
 */

final case class BundleOrder(value: String) extends AnyVal

object BundleOrder {
  val default: BundleOrder = BundleOrder("")

  /**
   * Comparison logic for bundle: successfully alpha-numeric.
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

    // only works on list of the same size
    def compareListRec(a: List[BundleOrder], b: List[BundleOrder]): Int = {
      (a, b) match {
        case (ha :: ta, hb :: tb) =>
          val comp = compare(ha, hb)
          if (comp == 0) {
            compareList(ta, tb)
          } else {
            comp
          }
        case _                    => // we know they have the same size by construction, so it's a real equality
          0
      }
    }

    val maxSize = List(a.size, b.size).max
    compareListRec(a.padTo(maxSize, BundleOrder.default), b.padTo(maxSize, BundleOrder.default))

  }
}

sealed trait GenericInterpolationContext[PARAM] {
  def nodeInfo:         CoreNodeFact
  def policyServerInfo: CoreNodeFact
  def globalPolicyMode: GlobalPolicyMode
  // parameters for this node
  // must be a case SENSITIVE Map !!!!
  def parameters:       Map[String, PARAM]
  // the depth of the interpolation context evaluation
  // used as a lazy, trivial, mostly broken way to detect cycle in interpretation
  // for ex: param a => param b => param c => ..... => param a
  // should not be evaluated
  def depth:            Int
}

/**
 * A class that hold all information that can be used to resolve
 * interpolated variables in directives variables.
 * It is by nature node dependent.
 */
final case class ParamInterpolationContext(
    nodeInfo:         CoreNodeFact,
    policyServerInfo: CoreNodeFact,
    globalPolicyMode: GlobalPolicyMode,                                           // parameters for this node
    // must be a case SENSITIVE Map !!!!

    parameters:       Map[String, ParamInterpolationContext => IOResult[String]], // the depth of the interpolation context evaluation
    // used as a lazy, trivial, mostly broken way to detect cycle in interpretation
    // for ex: param a => param b => param c => ..... => param a
    // should not be evaluated

    depth:            Int = 0
) extends GenericInterpolationContext[ParamInterpolationContext => IOResult[String]]

final case class InterpolationContext(
    nodeInfo:         CoreNodeFact,
    policyServerInfo: CoreNodeFact,
    globalPolicyMode: GlobalPolicyMode,
    // environment variable for that server
    // must be a case insensitive Map !!!!
    nodeContext:      TreeMap[String, Variable],
    // parameters for this node
    // must be a case SENSITIVE Map !!!!
    parameters:       Map[String, ConfigValue], // the depth of the interpolation context evaluation
    // used as a lazy, trivial, mostly broken way to detect cycle in interpretation
    // for ex: param a => param b => param c => ..... => param a
    // should not be evaluated
    depth:            Int
) extends GenericInterpolationContext[ConfigValue]

object InterpolationContext {
  implicit val caseInsensitiveString: Ordering[String] = new Ordering[String] {
    def compare(x: String, y: String): Int = x.compareToIgnoreCase(y)
  }

  def apply(
      nodeInfo:         CoreNodeFact,
      policyServerInfo: CoreNodeFact,
      globalPolicyMode: GlobalPolicyMode,         // environment variable for that server
      // must be a case insensitive Map !!!!

      nodeContext:      Map[String, Variable],    // parameters for this node
      // must be a case SENSITIVE Map !!!!

      parameters:       Map[String, ConfigValue], // the depth of the interpolation context evaluation
      // used as a lazy, trivial, mostly broken way to detect cycle in interpretation
      // for ex: param a => param b => param c => ..... => param a
      // should not be evaluated

      depth:            Int = 0
  ) = new InterpolationContext(nodeInfo, policyServerInfo, globalPolicyMode, TreeMap(nodeContext.toSeq*), parameters, depth)
}

final case class ParameterForConfiguration(
    name:  String,
    value: String
)

case object ParameterForConfiguration {
  def fromParameter(param: GlobalParameter): ParameterForConfiguration = {
    // here, we need to go back to a string for resolution of
    // things like ${rudder.param[foo] | default = ... }
    ParameterForConfiguration(param.name, param.valueAsString)
  }
}

/**
 * A Parameter Entry has a Name and a Value, and can be freely used within the promises
 * We need the get methods for StringTemplate, since it needs
 * get methods, and @Bean doesn't seem to do the trick
 */
final case class ParameterEntry(
    parameterName: String,
    escapedValue:  String,
    agentType:     AgentType
) {

  val escapedParameterName = parameterName.replaceAll("""[^\p{Alnum}_]""", "_")
  // returns the escaped name of the parameter
  def getEscapedParameterName(): String = {
    escapedParameterName
  }

  // returns the name of the parameter
  def getParameterName(): String = {
    parameterName
  }

  // returns the _escaped_ value of the parameter,
  // compliant with the syntax of CFEngine
  def getEscapedValue(): String = {
    escapedValue
  }
}

object NodeRunHook {

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
    bundle: String, // the kind of hook. The bundle method to call can be derived from that

    kind:       RunHook.Kind,
    reports:    List[NodeRunHook.ReportOn],
    parameters: List[RunHook.Parameter]
)

final case class NodeConfiguration(
    nodeInfo:    CoreNodeFact,
    modesConfig: NodeModeConfig,
    // sorted list of policies for the node.
    policies:    List[Policy],
    // the merged pre-/post-run hooks
    runHooks:    List[NodeRunHook],
    // environment variable for that server
    nodeContext: Map[String, Variable],
    parameters:  Set[ParameterForConfiguration]
) {
  def isRootServer: Boolean = nodeInfo.id == Constants.ROOT_POLICY_SERVER_ID

  def getTechniqueIds(): Set[TechniqueId] = {
    policies.map(_.technique.id).toSet
  }
}

/**
 * Unique identifier for the policy.
 * This is a general container that can be used to generate the different ID
 * used in rudder: the policyId as used in reports, the unique identifier
 * used to differentiate multi-version technique, etc.
 */
final case class PolicyId(ruleId: RuleId, directiveId: DirectiveId, techniqueVersion: TechniqueVersion) {

  val value: String = s"${ruleId.serialize}@@${directiveId.serialize}"

  /**
   * Create the value of the Rudder Id from the Id of the Policy and
   * the serial
   */
  def getReportId: String = value + "@@0" // as of Rudder 4.3, serial is always 0

  lazy val getRudderUniqueId: String = (techniqueVersion.serialize + "_" + directiveId.serialize).replaceAll("""\W""", "_")
}

/**
 * Until we have a unique identifier, we need to use another key to identify our components/Variable so that blocks
 * can be identified  correctly when building expected reports. Since in this case several blocks can have the same
 * component name.
 * This will allow to prevent missing expected reports, because we were building a map with toMap, that only keep
 * one elem for a specific key. A component Id for now is composed of component name and a list of Parents
 * Section (and maybe blocks) that has lead to it.
 *
 * - since 7.1, with a reportId. The report id is optional since we don't have all techniques ported to
 *   use it: as of 7.1, only ncf techniques (from editor) got it, so we needed a transitional period.
 *
 */
case class ComponentId(value: String, parents: List[String], reportId: Option[String])

/*
 * A policy "vars" is all the var data for a policy (expandedVars, originalVars,
 * trackingKey). They are grouped together in policy because some policy can
 * be the result of merging several BoundPolicyDraft
 */
final case class PolicyVars(
    policyId:     PolicyId,
    policyMode:   Option[PolicyMode],
    expandedVars: Map[ComponentId, Variable],
    originalVars: Map[ComponentId, Variable], // variable with non-expanded ${node.prop etc} values

    trackerVariable: TrackerVariable
)

/*
 * The technique bounded to a policy. It is specific to exactly one agent
 */
final case class PolicyTechnique(
    id:                  TechniqueId,
    agentConfig:         AgentConfig,
    trackerVariableSpec: TrackerVariableSpec,
    rootSection:         SectionSpec, // be careful to not split it from the TechniqueId, else you will not have the good spec for the version

    systemVariableSpecs: Set[SystemVariableSpec],
    isMultiInstance:     Boolean = false, // true if we can have several instance of this policy

    // for compat reason, we replace isSystem (default false) with base feature tag
    policyTypes:        PolicyTypes = PolicyTypes.rudderBase,
    generationMode:     TechniqueGenerationMode = TechniqueGenerationMode.MergeDirectives,
    useMethodReporting: Boolean = false
) {
  val templatesIds: Set[TechniqueResourceId] = agentConfig.templates.map(_.id).toSet

  val getAllVariableSpecs: Seq[VariableSpec] =
    this.rootSection.getAllVariables ++ this.systemVariableSpecs :+ this.trackerVariableSpec
}

object PolicyTechnique {
  def forAgent(technique: Technique, agentType: AgentType): Either[String, PolicyTechnique] = {
    technique.agentConfigs.find(_.agentType == agentType) match {
      case None    =>
        Left(
          s"Error: Technique '${technique.name}' (${technique.id.debugString}) does not support agent type '${agentType.displayName}'"
        )
      case Some(x) =>
        Right(
          PolicyTechnique(
            id = technique.id,
            agentConfig = x,
            trackerVariableSpec = technique.trackerVariableSpec,
            rootSection = technique.rootSection,
            systemVariableSpecs = technique.systemVariableSpecs,
            isMultiInstance = technique.isMultiInstance,
            policyTypes = technique.policyTypes,
            generationMode = technique.generationMode,
            useMethodReporting = technique.useMethodReporting
          )
        )
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
    id:       PolicyId,
    ruleName: String, // human readable name of the original rule, for ex for log

    directiveName: String, // human readable name of the original directive, for ex for log

    technique:           PolicyTechnique,
    techniqueUpdateTime: DateTime,
    policyVars:          NonEmptyList[PolicyVars],
    priority:            Int,
    policyMode:          Option[PolicyMode],
    ruleOrder:           BundleOrder,
    directiveOrder:      BundleOrder,
    overrides:           Set[PolicyId] // a set of other draft overridden by that one
) {

  // here, it is extremely important to keep sorted order
  // List[Map[id, List[Variable]]
  // == map .values (keep order) ==> Iterator[List[Variable]]
  // == .toList (keep order)     ==> List[List[Variable]]
  // == flatten (keep order)     ==> List[Variable]
  def expandedVars:    Map[String, Variable] = Policy.mergeVars(policyVars.map(_.expandedVars.values).toList.flatten)
  val trackerVariable: TrackerVariable       =
    policyVars.head.trackerVariable.spec.cloneSetMultivalued.toVariable(policyVars.map(_.trackerVariable.values).toList.flatten)
}

object Policy {

  val TAG_OF_RUDDER_ID           = "@@RUDDER_ID@@"
  val TAG_OF_RUDDER_MULTI_POLICY = "RudderUniqueID"

  /*
   * A method which change a path for a given policy toward a unique
   * path containing the given directive id
   */
  def makeUniqueDest(path: String, p: Policy): String = {
    val subPath = p.technique.id.serialize
    path.replaceFirst(subPath, s"${p.technique.id.name.value}/${p.id.getRudderUniqueId}")
  }

  /*
   * Replace the Rudder Unique Id also in the path for input list
   */
  def replaceRudderUniqueId(path: String, p: Policy): String = {
    path.replace(TAG_OF_RUDDER_MULTI_POLICY, p.id.getRudderUniqueId)
  }

  /*
   * merge an ordered seq of variables.
   *
   */
  def mergeVars(vars: Seq[Variable]): Map[String, Variable] = {
    val mergedVars = scala.collection.mutable.Map[String, Variable]()
    for (variable <- vars) {
      variable match {
        case _:      TrackerVariable => // nothing, it's been dealt with already
        case newVar: Variable        =>
          // TODO: #10625 : checked is not used anymore
          if ((!newVar.spec.checked) || (newVar.spec.isSystem)) {
            // Only user defined variables should need to be agregated
          } else {
            val variable = mergedVars.get(newVar.spec.name) match {
              case None                   =>
                Variable.matchCopy(newVar, setMultivalued = true)
              case Some(existingVariable) => // value is already there
                // hope it is multivalued, otherwise BAD THINGS will happen
                if (!existingVariable.spec.multivalued) {
                  PolicyGenerationLogger.warn(
                    s"Attempt to append value into a non multivalued variable '${existingVariable.spec.name}', please report the problem as a bug."
                  )
                }
                existingVariable.copyWithAppendedValues(newVar.values) match {
                  case Left(err) =>
                    PolicyGenerationLogger.error(
                      s"Error when merging variables '${existingVariable.spec.name}' (init: ${existingVariable.values.toString} ; " +
                      s"new val: ${newVar.values.toString}. This is most likely a bug, please report it"
                    )
                    existingVariable
                  case Right(v)  => v
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
    id:       PolicyId,
    ruleName: String, // human readable name of the original rule, for ex for log

    directiveName: String, // human readable name of the original directive, for ex for log

    technique:         Technique,
    acceptationDate:   DateTime,
    priority:          Int,
    isSystem:          Boolean,
    policyMode:        Option[PolicyMode],
    trackerVariable:   TrackerVariable,
    variables:         InterpolationContext => IOResult[Map[ComponentId, Variable]],
    originalVariables: Map[ComponentId, Variable], // the original variable, unexpanded

    ruleOrder:      BundleOrder,
    directiveOrder: BundleOrder
) {

  def toBoundedPolicyDraft(expandedVars: Map[ComponentId, Variable]): BoundPolicyDraft = {
    BoundPolicyDraft(
      id = id,
      ruleName = ruleName,
      directiveName = directiveName,
      technique = technique,
      acceptationDate = acceptationDate,
      expandedVars = expandedVars,
      originalVars = originalVariables,
      trackerVariable = trackerVariable,
      priority = priority,
      isSystem = isSystem,
      policyMode = policyMode,
      ruleOrder = ruleOrder,
      directiveOrder = directiveOrder,
      overrides = Set()
    )
  }

}

/**
 * This is the draft of the policy. Variable may not be bound yet, and
 * it may not be unique in a node configuration (i.e: this pre-sorting,
 * pre-merge, pre-filtering Techniques).
 */
final case class BoundPolicyDraft(
    id:       PolicyId,
    ruleName: String, // human-readable name of the original rule, for ex for log

    directiveName: String, // human-readable name of the original directive, for ex for log

    technique:       Technique,
    acceptationDate: DateTime,
    expandedVars:    Map[ComponentId, Variable], // contains vars with expanded parameters

    originalVars: Map[ComponentId, Variable], // contains original, pre-compilation, variable values

    trackerVariable: TrackerVariable,
    priority:        Int,
    isSystem:        Boolean,
    policyMode:      Option[PolicyMode],
    ruleOrder:       BundleOrder,
    directiveOrder:  BundleOrder,
    overrides:       Set[PolicyId] // a set of other draft overridden by that one
) {

  /**
   * Search in the variables of the policy for the TrackerVariable (that should be unique for the moment),
   * and retrieve it, along with bounded variable (or itself if it's bound to nothing)
   * Can throw a lot of exceptions if something fails
   */
  def getDirectiveVariable(): (TrackerVariable, Seq[Variable]) = {
    trackerVariable.spec.boundingVariable match {
      case None | Some("") | Some(null) => (trackerVariable, Seq(trackerVariable))
      case Some(value)                  =>
        originalVars.filter(_._1.value == value).values.toList match {
          // should not happen, techniques consistency are checked
          case Nil      =>
            throw new IllegalArgumentException(
              "No valid bounding found for trackerVariable " + trackerVariable.spec.name + " found in directive " + id.directiveId.debugString
            )
          case variable => (trackerVariable, variable)
        }
    }
  }

  /**
   * Transform to a BoundPolicyDraft to a final Policy.
   * This is the place where we finally have all information to check if the policy structure is valid
   * for the targeted agent.
   * This is the place where we can check for variable consistency too, since we have the final
   * techniques and expanded variables (like: check that a mandatory variable was expanded to "").
   *
   * It's also the place where we add missing variables that are well defined, and where we check for
   * constrains.
   *
   * If there is variable defined here but not in the technique, they are removed.
   */
  def toPolicy(agent: AgentType): Either[String, Policy] = {
    PolicyTechnique.forAgent(technique, agent).flatMap { pt =>
      // check that all variables from root section are here and well defined
      // the lookup for variables is a bit complicated. We have unique variable by name, so for now, we must to only match on that.
      // Since we use the same technique to build section path, it should be the same (apart in tests) but
      // I prefer to do the lookup on name
      val techVarSpecs = technique.getAllVariableSpecs.map { case (cid, s) => (cid.value, (cid, s)) }

      val allVars = expandedVars + (ComponentId(trackerVariable.spec.name, Nil, None) -> trackerVariable)

      (for {
        checkedExistingValues <- allVars.accumulatePure {
                                   case (cid, variable) =>
                                     techVarSpecs.get(cid.value) match {
                                       case None            =>
                                         Left(
                                           Inconsistency(
                                             s"Error for policy for directive '${directiveName}' [${id.directiveId.debugString}] in rule '${ruleName}' [${id.ruleId.serialize}]: " +
                                             s"a value is defined but the technique doesn't specify a variable with name '${variable.spec.name}' in section '${cid.parents.reverse
                                                 .mkString("/")}'"
                                           )
                                         )
                                       case Some((cid2, _)) =>
                                         if (cid != cid2) {
                                           PolicyGenerationLogger.debug(
                                             s"In '${directiveName}' [${id.directiveId.debugString}] in rule '${ruleName}' [${id.ruleId.serialize}]: component Id are " +
                                             s"not the same for technique and var for '${cid.value}'. In technique: '${cid2.parents.reverse
                                                 .mkString("/")}' ; In var: '${cid.parents.reverse.mkString("/")}'"
                                           )
                                         }
                                         // check if there is mandatory variable with missing/blank values
                                         // NOTE : it's OK that system variables are not filled here yet
                                         (
                                           variable.spec.constraint.mayBeEmpty || variable.spec.isSystem,
                                           variable.values.isEmpty || variable.values.exists(v => v == null || v.isEmpty)
                                         ) match {
                                           // simple case: it's optional, we don't care if empty or not.
                                           case (true, _) => Right((cid, variable))

                                           // simple case: all mandatory are filled
                                           case (false, false) => Right((cid, variable))

                                           // complicate case: mandatory with unfilled value: check if a default is available
                                           case (false, true) =>
                                             // first, if we have a default value, use that
                                             variable.spec.constraint.default match {
                                               case Some(d) =>
                                                 variable
                                                   .copyWithSavedValues(variable.values.map(v => if (v.isEmpty) d else v))
                                                   .map(v => (cid, v))
                                               // else it's an error
                                               case None    =>
                                                 Left(
                                                   Inconsistency(
                                                     s"Error for policy for directive '${directiveName}' [${id.directiveId.debugString}] in rule '${ruleName}' [${id.ruleId.serialize}]: " +
                                                     s"a non optional value is missing for parameter '${variable.spec.description}' [param ID: ${variable.spec.name}]"
                                                   )
                                                 )
                                             }

                                         }
                                     }
                                 }
        missing                = techVarSpecs -- checkedExistingValues.map(_._1.value).toSet
        // add missing if possible
        added                 <- missing.accumulatePure {
                                   case (_, (cid, spec)) =>
                                     // if mayBeEmpty or if it's a system variable, just add it - empty.
                                     (spec.constraint.mayBeEmpty || spec.isSystem, spec.constraint.default) match {
                                       case (true, _)        => Right((cid, spec.toVariable()))
                                       case (false, Some(d)) => Right((cid, spec.toVariable(Seq(d))))
                                       case (false, None)    =>
                                         Left(
                                           Inconsistency(
                                             s"Error for policy for directive '${directiveName}' [${id.directiveId.debugString}] in rule '${ruleName}' [${id.ruleId.serialize}]: " +
                                             s"a non optional value is missing for parameter '${spec.description}' [param ID: ${spec.name}]"
                                           )
                                         )
                                     }

                                 }
      } yield {
        Policy(
          id,
          ruleName,
          directiveName,
          pt,
          acceptationDate,
          NonEmptyList.of(
            PolicyVars(
              id,
              policyMode,
              (added ++ checkedExistingValues).toMap,
              originalVars,
              trackerVariable
            )
          ),
          priority,
          policyMode,
          ruleOrder,
          directiveOrder,
          overrides
        )
      }).left
        .map(_.deduplicate.msg)
    }
  }
}

final case class RuleVal(
    ruleId:             RuleId,
    nodeIds:            Set[NodeId],
    parsedPolicyDrafts: Seq[ParsedPolicyDraft]
)
