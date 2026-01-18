/*
 *************************************************************************************
 * Copyright 2016 Normation SAS
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

package com.normation.rudder.services.policies.write

import cats.implicits.*
import com.normation.cfclerk.domain.BundleName
import com.normation.cfclerk.domain.RunHook
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.domain.SystemVariable
import com.normation.cfclerk.domain.TechniqueGenerationMode
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.Variable
import com.normation.cfclerk.services.SystemVariableSpecService
import com.normation.errors.*
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.logger.PolicyGenerationLogger
import com.normation.rudder.domain.logger.PolicyGenerationLoggerPure
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.PolicyTypes
import com.normation.rudder.services.policies.IfVarClass
import com.normation.rudder.services.policies.NodeRunHook
import com.normation.rudder.services.policies.Policy
import com.normation.rudder.services.policies.PolicyId
import scala.collection.immutable.ListMap
import zio.*

/**
 * This file groups together everything related to building the bundle sequence and
 * related system variables.
 *
 * It is in charge to:
 * - correctly get bundles from techniques,
 * - sort them accordingly to defined rules,
 * - add utility bundle when needed, like ncf logging bundle and dry-run mode
 */
object BuildBundleSequence {

  /*
   * A data structure that holds the different values, with the different format,
   * for each of the bundle related elements.
   *
   * Each value is just a correctly formatted string, which means for the template
   * will never get the actual list of elements. This is a conscious decision that allows:
   * - a simple type (String), available in any templating engine
   * - the work is all done here, else most likely for each evolution, it will have to
   *   be done here and in the template. As for now, system technique are separated
   *   from Rudder code repository, this is extremlly inefficient.
   * - a consistent formatting (vertical align is impossible with string template)
   *
   * We are returning List for more flexibility, knowing that:
   * - in StringTemplate, Nil and List("") are not the same thing
   *   (you certainly want the latter)
   * - for the CFEngine case at least, we are returning ONLY ONE
   *   string with the formatted list of inputs/bundles.
   *   We could return one input by line.
   *
   */
  final case class BundleSequenceVariables(
      // the list of system inputs promise file
      systemDirectivesInputFiles: List[String], // the list of formated "usebundle" methods
      // for system techniques

      systemDirectivesUsebundle:  List[String], // the list of user inputs promise file

      directivesInputFiles: List[String], // the list of formated "usebundle" methods
      // for user techniques

      directivesUsebundle:  List[String]
  )

  /*
   * An input file to include as a dependency
   * (at least in cfengine)
   */
  final case class InputFile(path: String, policyTypes: PolicyTypes)

  // ad-hoc data structure to denote a directive name
  // (actually, the directive applied in to rule),
  // or in CFEngine name a "promiser"
  final case class Promiser(value: String) extends AnyVal

  // A bundle parameter is just a String, but it can be quoted with simple or double quote
  // (double quote is the default, and simple quote are used mostly for JSON)
  sealed trait BundleParam {
    def quote(agentEscape: String => String): String
    def name:         String
    def value:        String
    def variableName: Option[String]
  }
  object BundleParam       {
    final case class SimpleQuote(value: String, name: String, variableName: Option[String]) extends BundleParam {
      def quote(agentEscape: String => String): String = "'" + value + "'"
    }
    final case class DoubleQuote(value: String, name: String, variableName: Option[String]) extends BundleParam {
      def quote(agentEscape: String => String): String = "\"" + agentEscape(value) + "\""
    }
  }

  /*
   * A Bundle is a BundleName and a Rudder ID that will be used to identify reports for that bundle.
   * By convention, the bundle name looks like: `run_${directiveId};`.
   * The bundle can have parameters, which are appended to the bundle name: `run_${directiveId}("foo","bar");`.
   * Optionally, the bundle can have a run condition in the form of an `ifvarclass` expression. If so, it is
   * appended to the command after a comma:
   *   `run_${directiveId}("foo","bar"), ifvarclass => ${ifvarclass expression};`
   */
  final case class Bundle(id: Option[PolicyId], name: BundleName, params: List[BundleParam], ifvarclass: Option[IfVarClass]) {
    def callParams(escape: String => String): String = {
      if (params.nonEmpty) {
        params.map(_.quote(escape)).mkString("(", ",", ")")
      } else {
        ""
      }
    }

    def callIfVarClass(indent: Int): String = {
      ifvarclass match {
        case None                           => ""
        case Some(x) if x.value.length == 0 => ""
        case Some(x)                        => s",\n${" " * Math.max(0, indent)}if => ${x}"
      }
    }

    def callBundle(escape: String => String, indent: Int): String =
      s"${name}${callParams(escape)}${callIfVarClass(indent)}"
  }

  object Bundle {
    def modeBundle(policyMode: PolicyMode, isSystem: Boolean): Bundle = {
      (isSystem, policyMode) match {
        case (true, _)                   => enforce
        case (false, PolicyMode.Audit)   => audit
        case (false, PolicyMode.Enforce) => enforce
      }
    }
    val audit:                                                 Bundle =
      Bundle.apply(None, BundleName("""set_dry_run_mode"""), BundleParam.DoubleQuote("true", "mode", None) :: Nil, None)
    val enforce:                                               Bundle =
      Bundle.apply(None, BundleName("""set_dry_run_mode"""), BundleParam.DoubleQuote("false", "mode", None) :: Nil, None)
  }

  /*
   * A to-be-written list of bundle related to a unique
   * technique. They all have the same Policy name, derived
   * from the technique/directive name.
   *
   * A list of bundle can be preceded and followed by some set-up bundles.
   * Typically, to set/unset some technique related classes.
   *
   * Each bundle can be preceded/followed by other bundle.
   *
   * User of that object mostly want to know the final list
   * of bundle name, correctly sorted.
   */
  final case class TechniqueBundles(
      // Human readable name of the "Rule name / Directive name" for that list of bundle
      promiser:    Promiser,
      directiveId: DirectiveId, // identifier of the technique from which that list of bundle derive (that's the one without spaces and only ascii chars)

      techniqueId:           TechniqueId,
      pre:                   List[Bundle],
      main:                  List[Bundle], // pre- and post-bundle sequence are simple bundle
      // because we don't know any use case for having a
      // pack here.

      post:                  List[Bundle],
      policyTypes:           PolicyTypes,
      policyMode:            PolicyMode,
      enableMethodReporting: Boolean,
      ifVarClass:            Option[IfVarClass]
  ) {
    val contextBundle: List[Bundle] = main
      .map(_.id)
      .distinct
      .collect {
        case Some(id) =>
          Bundle(
            None,
            BundleName("rudder_reporting_context_v4"),
            List(
              (id.directiveId.serialize, "directiveId", None),
              (id.ruleId.serialize, "ruleId", None),
              (techniqueId.name.value, "techniqueName", None),
              ("", "component", None),
              ("", "value", None),
              (id.directiveId.serialize ++ id.ruleId.serialize, "report_id", None)
            ).map((BundleParam.DoubleQuote.apply).tupled),
            None
          ) :: Nil
      }
      .flatten

    val methodReportingState: List[Bundle] = {
      val bundleToUse = if (enableMethodReporting) "enable_reporting" else "disable_reporting"
      Bundle(None, BundleName(bundleToUse), Nil, None) :: Nil

    }

    val runBundle: Bundle = {
      // these are the main bundle sequence chain, so here we add the `ifVarClass`. It's the only place where we need them
      Bundle(None, BundleName(s"run_${directiveId.serialize}"), Nil, ifVarClass)
    }

    // here, since we use "policyTypes.isSystem", it means that system update, hardening pack, etc will respect policy mode
    def runBundles:     List[Bundle] = Bundle.modeBundle(policyMode, policyTypes.isSystem) :: runBundle :: Nil
    def bundleSequence: List[Bundle] =
      contextBundle ::: pre ::: methodReportingState ::: main ::: post ::: (cleanReportingBundle :: Nil)
  }

  val cleanReportingBundle: Bundle = Bundle(None, BundleName(s"""clean_reporting_context"""), Nil, None)

  /*
   * Some Techniques don't have any bundle (at least common).
   * We don't want to include these technique in the bundle sequence,
   * obviously
   */
  implicit final class NoBundleTechnique(private val bundles: List[TechniqueBundles]) extends AnyVal {
    def removeEmptyBundle: List[TechniqueBundles] = bundles.filterNot(_.main.isEmpty)
  }
}

class BuildBundleSequence(
    systemVariableSpecService:  SystemVariableSpecService,
    writeAllAgentSpecificFiles: WriteAllAgentSpecificFiles
) {
  import BuildBundleSequence.*

  /*
   * The main entry point of the object: for each variable related to
   * bundle sequence, compute the corresponding values.
   *
   * This may fail if the policy modes are not consistent for directives
   * declined from the same (multi-instance) technique.
   */
  def prepareBundleVars(
      agentNodeProps:   AgentNodeProperties,
      nodePolicyMode:   Option[PolicyMode],
      globalPolicyMode: GlobalPolicyMode,
      policies:         List[Policy],
      runHooks:         List[NodeRunHook]
  ): IOResult[Map[String, SystemVariable]] = {

    // Fetch the policies configured and sort them according to (rules, directives)

    val sortedPolicies = PolicyOrdering.sort(policies)

    // Then builds bundles and inputs:
    // - build list of inputs file to include: all the outPath of templates that should be "included".
    //   (returns the pair of (outpath, isSystem) )

    val inputs: List[InputFile] = sortedPolicies.flatMap { p =>
      val inputs = p.technique.agentConfig.templates.collect {
        case template if (template.included) => InputFile(template.outPath, p.technique.policyTypes)
      } ++
        p.technique.agentConfig.files.collect {
          case file if (file.included) => InputFile(file.outPath, p.technique.policyTypes)
        }
      // must create unique path, or replace RudderUniqueID in the paths
      p.technique.generationMode match {
        case TechniqueGenerationMode.MultipleDirectives =>
          val create = inputs.map(i => i.copy(path = Policy.makeUniqueDest(i.path, p)))
          create.map(i => i.copy(path = Policy.replaceRudderUniqueId(i.path, p)))
        case _                                          =>
          inputs
      }
    }.toList

    // get the output string for each bundle variables, agent dependant
    for {
      _                 <- PolicyGenerationLoggerPure.trace(s"Preparing bundle list and input list for node : ${agentNodeProps.nodeId.value}")
      // - build techniques bundles from the sorted list of techniques
      techniquesBundles <-
        ZIO.foreach(sortedPolicies)(
          buildTechniqueBundles(agentNodeProps.nodeId, agentNodeProps.agentType, globalPolicyMode, nodePolicyMode)(_).toIO
        )
      // split system and user directive (technique)
      bundles            = techniquesBundles.toList.removeEmptyBundle
      bundleVars        <- writeAllAgentSpecificFiles
                             .getBundleVariables(agentNodeProps, inputs, bundles, runHooks)
                             .toIO
                             .chainError(
                               s"Error for node '${agentNodeProps.nodeId.value}' bundle creation"
                             )
      // map to correct variables
      vars              <- ZIO.collectAll(
                             List(
                               // this one is CFEngine specific and kept for historical reason
                               systemVariableSpecService
                                 .get("INPUTLIST")
                                 .map(v => (v.name, SystemVariable(v, CfengineBundleVariables.formatBundleFileInputFiles(inputs.map(_.path)))))
                                 .toIO, // this one is CFengine specific and kept for historical reason

                               systemVariableSpecService
                                 .get("BUNDLELIST")
                                 .map(v => {
                                   (
                                     v.name,
                                     SystemVariable(v, techniquesBundles.flatMap(_.bundleSequence.map(_.name)).mkString(", ", ", ", "") :: Nil)
                                   )
                                 })
                                 .toIO,
                               systemVariableSpecService
                                 .get("RUDDER_SYSTEM_DIRECTIVES_INPUTS")
                                 .map(v => (v.name, SystemVariable(v, bundleVars.systemDirectivesInputFiles)))
                                 .toIO,
                               systemVariableSpecService
                                 .get("RUDDER_SYSTEM_DIRECTIVES_SEQUENCE")
                                 .map(v => (v.name, SystemVariable(v, bundleVars.systemDirectivesUsebundle)))
                                 .toIO,
                               systemVariableSpecService
                                 .get("RUDDER_DIRECTIVES_INPUTS")
                                 .map(v => (v.name, SystemVariable(v, bundleVars.directivesInputFiles)))
                                 .toIO,
                               systemVariableSpecService
                                 .get("RUDDER_DIRECTIVES_SEQUENCE")
                                 .map(v => (v.name, SystemVariable(v, bundleVars.directivesUsebundle)))
                                 .toIO
                             )
                           )
    } yield {
      vars.toMap
    }
  }

  ////////////////////////////////////////////
  ////////// Implementation details //////////
  ////////////////////////////////////////////

  /*
   * For each techniques:
   * - check the node AgentType and fails if the technique is not compatible with it
   * - build the name
   * - build the list of bundle included,
   *
   * The List[BundleOrder] is actually List(ruleName, directiveName) for the chose couple for that technique.
   * The ReportId is the same for all bundles, because we don't have a better granularity for now
   * (and it is also why we get it from sortTechniques, which is kind of strange :)
   *
   */
  def buildTechniqueBundles(
      nodeId:           NodeId,
      agentType:        AgentType,
      globalPolicyMode: GlobalPolicyMode,
      nodePolicyMode:   Option[PolicyMode]
  )(policy: Policy): PureResult[TechniqueBundles] = {
    // naming things to make them clear
    val name = Promiser(policy.ruleName + "/" + policy.directiveName)

    // and for now, all bundle get the same reportKey
    def getVars(
        generationMode: TechniqueGenerationMode,
        rootSection:    SectionSpec,
        expandedVars:   Map[String, Variable]
    ): PureResult[List[BundleParam]] = {
      generationMode match {
        case TechniqueGenerationMode.MultipleDirectivesWithParameters                             =>
          rootSection.copyWithoutSystemVars.getAllVariables.accumulatePure { v =>
            // Here we are looking for variables value from a Directive based on a ncf technique that has parameter
            // ncf technique parameters ( having an id and a name, which is used inside the technique) were translated into Rudder variables spec
            // (having a name, which acts as an id, and allow to do templating on techniques, and a description which is presented to users) with the following Rule
            //  ncf Parameter | Rudder variable
            //      id        |      name
            //     name       |   description
            // So here we only have Rudder variables, and we want to create a new object BundleParam, which name needs to be the value used inside the technique (so the technique parameter name)
            // And we will get the value in the Directive by looking for it with the name of the Variable (which is the id of the parameter)
            //  ncf Parameter | Rudder variable | Bundle param
            //      id        |      name       |     N/A
            //     name       |   description   |     name = variable.description = parameter.name
            //      N/A       |        N/A      |    value = values.get(variable.name) = values.get(parameter.id)
            // We would definitely be happier if we add a way to describe them as Rudder technique parameter and not as variable
            val (varId, varName, variableName) = (v.name, v.description, v.variableName)

            for {
              value <-
                expandedVars
                  .get(varId)
                  .flatMap(_.values.headOption)
                  .notOptionalPure(
                    s"Missing variable value for '${varId}' (${varName}) in node ${nodeId.value} -> ${name}. This is " +
                    s"likely a bug, please report it to rudder devs"
                  )
            } yield {
              BundleParam.DoubleQuote(value, varName, variableName)
            }
          }
        case TechniqueGenerationMode.MergeDirectives | TechniqueGenerationMode.MultipleDirectives =>
          Right(Nil)
      }
    }

    val techniqueBundles = policy.technique.agentConfig.bundlesequence.accumulatePure { bundleName =>
      if (bundleName.value.trim.nonEmpty) {
        for {
          vars <- getVars(policy.technique.generationMode, policy.technique.rootSection, policy.expandedVars)
        } yield {
          // here, we are processing the bundle composing a technique that will be translated
          // into `bundle agen run_xxx { mehtods: yyyy }` - the yyy part. So here, we don't
          // want the if, it is enough to have it in the main bundle chain.
          List(Bundle(Some(policy.id), bundleName, vars, None))
        }
      } else {
        val msg =
          s"Technique '${policy.technique.id}' used in node '${nodeId.value}' contains some bundle with empty name, which is forbidden and so they are ignored in the final bundle sequence"
        PolicyGenerationLogger.warn(msg)
        Left(Inconsistency(msg))
      }
    }.map(_.flatten)

    for {
      policyMode <- PolicyMode.computeMode(globalPolicyMode, nodePolicyMode, policy.policyMode :: Nil)
      // we must update technique bundle in case policy generation is multi-instance
      bundles    <- policy.technique.generationMode match {
                      case TechniqueGenerationMode.MultipleDirectives =>
                        techniqueBundles.map(
                          _.map(b => {
                            b.copy(name =
                              BundleName(b.name.value.replaceAll(Policy.TAG_OF_RUDDER_MULTI_POLICY, policy.id.getRudderUniqueId))
                            )
                          })
                        )
                      case _                                          =>
                        techniqueBundles
                    }
    } yield {
      TechniqueBundles(
        name,
        policy.id.directiveId,
        policy.technique.id,
        Nil,
        bundles,
        Nil,
        policy.technique.policyTypes,
        policyMode,
        policy.technique.useMethodReporting,
        None
      )
    }
  }
}

//////////////////////////////////////////////////////////////////////
////////// Agent specific implementation to format outputs //////////
//////////////////////////////////////////////////////////////////////

object CfengineBundleVariables {
  import BuildBundleSequence.*

  def getBundleVariables(
      escape:   String => String,
      inputs:   List[InputFile],
      bundles:  List[TechniqueBundles],
      runHooks: List[NodeRunHook]
  ): BundleSequenceVariables = {

    BundleSequenceVariables(
      // we need system techniques first, and they can't be dry run.
      formatBundleFileInputFiles(inputs.collect { case x if (x.policyTypes.isSystem) => x.path }),
      formatMethodsUsebundle(escape, bundles.filter(_.policyTypes.isSystem), Nil, cleanDryRunEnd = false),

      // then other, just filter out system techniques. These ones can be set on PolicyMode = Verify
      formatBundleFileInputFiles(inputs.collect { case x if (!x.policyTypes.isSystem) => x.path }),
      formatMethodsUsebundle(escape, bundles.filterNot(_.policyTypes.isSystem), runHooks, cleanDryRunEnd = true)
    )
  }

  /*
   * Method for formating list of "promiser usebundle => bundlename;"
   *
   * For the CFEngine agent, we are waiting ONE string of the fully
   * formatted result, ie. something like: """
   *  "Rule1/directive one"                         usebundle => fetchFusionTools;
   *  "Rule1/some other directive with a long name" usebundle => doInventory;
   *  "An other rule/its directive"                 usebundle => virtualMachines;
   * """
   */
  def formatMethodsUsebundle(
      escape:         String => String,
      bundleSeq:      List[TechniqueBundles],
      runHooks:       List[NodeRunHook],
      cleanDryRunEnd: Boolean
  ): List[String] = {
    // the promiser value (may) comes from user input, so we need to escape
    // also, get the list of bundle for each promiser.
    // and we don't need isSystem anymore
    val escapedSeq = bundleSeq.map(x => (CFEngineAgentSpecificGeneration.escape(x.promiser.value), x.runBundles))

    // create / add in the escapedSeq hooks
    val preHooks  = runHooks.collect { case h if (h.kind == RunHook.Kind.Pre) => getBundleForHook(h) }
    val postHooks = runHooks.collect { case h if (h.kind == RunHook.Kind.Post) => getBundleForHook(h) }

    val allBundles = preHooks ::: escapedSeq ::: postHooks

    // that's the length to correctly vertically align things. Most important
    // number in all Rudder !
    val alignWidth = if (escapedSeq.isEmpty) 0 else escapedSeq.map(_._1.size).max

    val escapedCleanDryRunEnd = {
      if (cleanDryRunEnd) {
        val promiser = "clean_mode"
        s"""\n      "${promiser}"${" " * Math.max(0, alignWidth - promiser.size)} usebundle => ${Bundle.enforce.callBundle(
            escape,
            alignWidth
          )};""" :: Nil
      } else {
        Nil
      }
    }

    val runBundles = if (allBundles.nonEmpty) {

      allBundles.flatMap {
        case (promiser, bundles) =>
          bundles.map { bundle =>
            // We want to add the spaces so it is correctly aligned for Rudder agent
            // We used to have the variable correctly indented in rudder-directives but this prevent using multi-line variables
            // See #12824 for more details
            s"""      "${promiser}"${" " * Math.max(0, alignWidth - promiser.size)} usebundle => ${bundle.callBundle(
                escape,
                alignWidth + 9 + 7 // usebundle length, and "if" is 7 more char
              )};"""
          }
      }.mkString("\n") :: escapedCleanDryRunEnd
    } else {
      Nil
    }

    val bundles = {
      if (bundleSeq.nonEmpty) {
        bundleSeq.map { bundle =>
          val subBundles = bundle.bundleSequence.map { b =>
            s"""      "${CFEngineAgentSpecificGeneration.escape(bundle.promiser.value)}" usebundle => ${b.callBundle(
                escape,
                0 // we don't have ifvarclass here, so we don't need to set indent
              )};"""
          }
          s"""bundle agent ${bundle.runBundle.name.value}
             |{
             |  methods:
             |${subBundles.mkString("\n")}""".stripMargin
        }.mkString("\n}\n", "\n}\n", "")
      } else ""
    }

    runBundles ::: (bundles :: Nil)

  }

  /*
   * A hook will look like:
   * "pre-run-hook"  usebundle => do_run_hook("name", "condition", json)
   * ....
   * "post-run-hook" usebundle => do_run_hook("name", "condition", json)
   *
   * Where json is:
   * {
   *   "parameters": { "service": "systlog", ... }
   * , "reports"   : [ { "id": "report id" , "mode": "audit" }, { "id": "report id" , "mode": "enforce" }, ... ]
   * }
   */
  def getBundleForHook(hook: NodeRunHook): (String, List[Bundle]) = {
    import JsonRunHookSer.*
    val promiser = hook.kind match {
      case RunHook.Kind.Pre  => "pre-run-hook"
      case RunHook.Kind.Post => "post-run-hook"
    }
    import BundleParam.*
    (promiser, Bundle(None, BundleName(hook.bundle), List(SimpleQuote(hook.jsonParam, "hook_param", None)), None) :: Nil)
  }

  /*
   * utility method for formating an input list
   * For the CFEngine agent, we are waiting ONE string of the fully
   * formatted result, ie. something like: """
   *     "common/1.0/update.cf",
   *     "rudder-directives.cf",
   *     "rudder-system-directives.cf",
   *     "common/1.0/rudder-parameters.cf",
   * """
   */
  def formatBundleFileInputFiles(x: Seq[String]): List[String] = {
    val inputs = x.distinct
    if (inputs.isEmpty) {
      List("") // we must have one empty parameter to have the awaited behavior with string template
    } else {
      List(inputs.mkString("\"", s"""",\n"""", s"""","""))
    }
  }
}

/*
 * Serialization version of a node hook parameter:
 *  {
 *    "parameters": { "package": "visudo", "condition": "debian", ... }
 *  , "reports"   : [
 *      { "id": "report id" , "mode": "audit"  , "technique":"some technique", "name":"check_visudo_installed", "value":"ok" }
 *    , { "id": "report id" , "mode": "enforce", "technique":"some technique", "name":"check_visudo_installed", "value":"ok" }
 *    , ...
 *    ]
 *  }
 *
 * Must be top level to make Liftweb JSON lib happy
 */
final case class JsonRunHookReport(id: String, mode: String, technique: String, name: String, value: String)
final case class JsonRunHook(
    parameters: ListMap[String, String], // to keep order

    reports: List[JsonRunHookReport]
)

object JsonRunHookSer {
  implicit class ToJson(private val h: NodeRunHook) extends AnyVal {
    import net.liftweb.json.*
    def jsonParam: String = {
      val jh = JsonRunHook(
        ListMap(h.parameters.map(p => (p.name, p.value))*),
        h.reports.map(r =>
          JsonRunHookReport(r.id.getReportId, r.mode.name, r.technique, r.report.name, r.report.value.getOrElse("None"))
        )
      )
      implicit val formats: Formats = DefaultFormats
      Serialization.write(jh)
    }
  }
}
