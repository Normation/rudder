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

import com.normation.cfclerk.domain.BundleName
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.services.policies.BundleOrder
import com.normation.utils.Control.sequence
import net.liftweb.common._
import com.normation.cfclerk.domain.SystemVariable
import com.normation.cfclerk.services.SystemVariableSpecService
import com.normation.inventory.domain.AgentType
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.inventory.domain.OsDetails
import com.normation.rudder.services.policies.Policy
import com.normation.rudder.services.policies.ParameterEntry
import com.normation.cfclerk.domain.TechniqueGenerationMode


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
   * - the work is all done here, else most likelly for each evolution, it will have to
   *   be done here and in the template. As for now, system technique are separated
   *   from Rudder code repository, this is extremlly inifficient.
   * - a consistant formatting (vertical align is impossible with string template)
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
      systemDirectivesInputFiles: List[String]
      // the list of formated "usebundle" methods
      // for system techniques
    , systemDirectivesUsebundle : List[String]
      // the list of user inputs promise file
    , directivesInputFiles      : List[String]
      // the list of formated "usebundle" methods
      // for user techniques
    , directivesUsebundle       : List[String]
  )

  /*
   * An input file to include as a dependency
   * (at least in cfengine)
   */
  final case class InputFile(path: String, isSystem: Boolean)

  // ad-hoc data structure to denote a directive name
  // (actually, the directive applied in to rule),
  // or in CFEngine name a "promiser"
  final case class Directive(value: String)

  // a Bundle is a BundleName and a Rudder Id that will
  // be used to identify reports for that bundle
  final case class Bundle(id: ReportId, name: BundleName)

  // The rudder id is ruleid@@directiveid@@0
  final case class ReportId(value: String)


  /*
   * A to-be-written list of bundle related to a unique
   * technique. They all have the same Policy name, derived
   * from the technique/directive name.
   *
   * A list of bundle can be preceded and followed by some set-up bundles.
   * Typically to set/unset some technique related classes.
   *
   * Each bundle can be preceded/followed by other bundle.
   *
   * User of that object mostly want to know the final list
   * of bundle name, correctly sorted.
   */
  final case class TechniqueBundles(
      // Human readable name of the "Rule name / Directive name" for that list of bundle
      promiser               : Directive
      // identifier of the technique from which that list of bundle derive (that's the one without spaces and only ascii chars)
    , techniqueId            : TechniqueId
    , pre                    : List[Bundle]
    , main                   : List[Bundle]
      // pre- and post-bundle sequence are simple bundle
      // because we don't know any use case for having a
      // pack here.
    , post                   : List[Bundle]
    , isSystem               : Boolean
    , providesExpectedReports: Boolean
    , policyMode             : PolicyMode
  ) {
    def bundleSequence = pre ::: main ::: post
  }
}

class BuildBundleSequence(
    systemVariableSpecService : SystemVariableSpecService
  , writeAllAgentSpecificFiles: WriteAllAgentSpecificFiles
) extends Loggable {
  import BuildBundleSequence._

  /*
   * The main entry point of the object: for each variable related to
   * bundle sequence, compute the corresponding values.
   *
   * This may fail if the policy modes are not consistant for directives
   * declined from the same (multi-instance) technique.
   */
  def prepareBundleVars(
      nodeId          : NodeId
    , agentType       : AgentType
    , osDetails       : OsDetails
    , nodePolicyMode  : Option[PolicyMode]
    , globalPolicyMode: GlobalPolicyMode
    , policies        : List[Policy]
  ): Box[List[SystemVariable]] = {

    logger.trace(s"Preparing bundle list and input list for node : ${nodeId.value}")

    // Fetch the policies configured and sort them according to (rules, directives)

    val sortedPolicies = sortPolicies(policies)

    // Then builds bundles and inputs:
    // - build list of inputs file to include: all the outPath of templates that should be "included".
    //   (returns the pair of (outpath, isSystem) )

    val inputs: List[InputFile] = sortedPolicies.flatMap { p =>
      p.technique.agentConfigs.find( _.agentType == agentType) match {
        case None      => Nil
        case Some(cfg) =>
          val inputs = cfg.templates.collect { case template if(template.included) => InputFile(template.outPath, p.technique.isSystem) } ++
                       cfg.files.collect { case file if(file.included) => InputFile(file.outPath, p.technique.isSystem) }
          //must replace RudderUniqueID in the paths
          p.technique.generationMode match {
            case TechniqueGenerationMode.MultipleDirectives =>
              inputs.map(i => i.copy(path = Policy.makeUniqueDest(i.path, p)))
            case _ =>
              inputs
          }
      }
    }.toList

    //split (system | user) inputs
    val (systemInputFiles, userInputFiles) = inputs.partition( _.isSystem )

    // get the output string for each bundle variables, agent dependant
    for {
      // - build techniques bundles from the sorted list of techniques
      techniquesBundles          <- sequence(sortedPolicies)(buildTechniqueBundles(nodeId, agentType, globalPolicyMode, nodePolicyMode))
      //split system and user directive (technique)
      (systemBundle, userBundle) =  techniquesBundles.toList.removeEmptyBundle.partition( _.isSystem )
      bundleVars                 <- writeAllAgentSpecificFiles.getBundleVariables(agentType, osDetails, systemInputFiles, systemBundle, userInputFiles, userBundle) ?~!
                                    s"Error for node '${nodeId.value}' bundle creation"
    } yield {
      // map to correct variables
      List(
          //this one is CFengine specific and kept for historical reason
          SystemVariable(systemVariableSpecService.get("INPUTLIST") , CfengineBundleVariables.formatBundleFileInputFiles(inputs.map(_.path)))
          //this one is CFengine specific and kept for historical reason
        , SystemVariable(systemVariableSpecService.get("BUNDLELIST"), techniquesBundles.flatMap( _.bundleSequence.map(_.name)).mkString(", ", ", ", "") :: Nil)
        , SystemVariable(systemVariableSpecService.get("RUDDER_SYSTEM_DIRECTIVES_INPUTS")  , bundleVars.systemDirectivesInputFiles)
        , SystemVariable(systemVariableSpecService.get("RUDDER_SYSTEM_DIRECTIVES_SEQUENCE"), bundleVars.systemDirectivesUsebundle)
        , SystemVariable(systemVariableSpecService.get("RUDDER_DIRECTIVES_INPUTS")         , bundleVars.directivesInputFiles)
        , SystemVariable(systemVariableSpecService.get("RUDDER_DIRECTIVES_SEQUENCE")       , bundleVars.directivesUsebundle)
      )
    }
  }

  ////////////////////////////////////////////
  ////////// Implementation details //////////
  ////////////////////////////////////////////

  /*
   * Some Techniques don't have any bundle (at least common).
   * We don't want to include these technique in the bundle sequence,
   * obviously
   */
  implicit final class NoBundleTechnique(bundles: List[TechniqueBundles]) {
    def removeEmptyBundle: List[TechniqueBundles] = bundles.filterNot(_.main.isEmpty)
  }


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
  def buildTechniqueBundles(nodeId: NodeId, agentType: AgentType, globalPolicyMode: GlobalPolicyMode, nodePolicyMode: Option[PolicyMode])(policy: Policy): Box[TechniqueBundles] = {
    // naming things to make them clear
    val name = Directive(policy.ruleOrder.value + "/" + policy.directiveOrder.value)

    // and for now, all bundle get the same reportKey
    policy.technique.agentConfigs.find(_.agentType == agentType) match {
      case Some(cfg) =>
        val techniqueBundles = cfg.bundlesequence.map { bundleName =>
          if(bundleName.value.trim.size > 0) {
            List(Bundle(ReportId(policy.id.getReportId), bundleName))
          } else {
            logger.warn(s"Technique '${policy.technique.id}' used in node '${nodeId.value}' contains some bundle with empty name, which is forbidden and so they are ignored in the final bundle sequence")
            Nil
          }
        }.flatten.toList
        for {
          policyMode <- PolicyMode.computeMode(globalPolicyMode, nodePolicyMode, policy.policyMode :: Nil)
        } yield {
          //we must update technique bundle in case policy generation is multi-instance
          val bundles = policy.technique.generationMode match {
            case TechniqueGenerationMode.MultipleDirectives =>
              techniqueBundles.map(b => b.copy(name = BundleName(b.name.value.replaceAll(Policy.TAG_OF_RUDDER_MULTI_POLICY, policy.id.getRudderUniqueId))))
            case _ =>
              techniqueBundles
          }


          TechniqueBundles(name, policy.technique.id, Nil, bundles, Nil, policy.technique.isSystem, policy.technique.providesExpectedReports, policyMode)
        }

      case None =>
        Failure(s"Node with id '${nodeId.value}' is configured with agent type='${agentType.toString}' but technique '${policy.technique.id.toString}' applying via Directive '${name.value}' is not compatible with that agent type.")
    }
  }

  /*
   * Sort the techniques according to the order of the associated BundleOrder of Policy.
   * Sort at best: sort rule then directives, and take techniques on that order, only one time.
   *
   * CAREFUL: this method only take care of sorting based on "BundleOrder", other sorting (like
   * "system must go first") are not taken into account here !
   */
  def sortPolicies(
      policies: List[Policy]
  ): List[Policy] = {
    def compareBundleOrder(a: Policy, b: Policy): Boolean = {
      BundleOrder.compareList(List(a.ruleOrder, a.directiveOrder), List(b.ruleOrder, b.directiveOrder)) <= 0
    }
    val sorted = policies.sortWith(compareBundleOrder)

    //some debug info to understand what order was used for each node:
    if(logger.isDebugEnabled) {
      val logSorted = sorted.map(p => s"${p.technique.id}: [${p.ruleOrder.value} | ${p.directiveOrder.value}]").mkString("[","][", "]")
      logger.debug(s"Sorted Technique (and their Rules and Directives used to sort): ${logSorted}")
    }
    sorted
  }
}

//////////////////////////////////////////////////////////////////////
////////// Agent specific implementation to format outputs //////////
//////////////////////////////////////////////////////////////////////



object CfengineBundleVariables extends AgentFormatBundleVariables {
  import BuildBundleSequence._

  override def getBundleVariables(
      systemInputs: List[InputFile]
    , sytemBundles: List[TechniqueBundles]
    , userInputs  : List[InputFile]
    , userBundles : List[TechniqueBundles]
  ) : BundleSequenceVariables = {


    BundleSequenceVariables(
        formatBundleFileInputFiles(systemInputs.map(_.path))
      , formatMethodsUsebundle(sytemBundles.addNcfReporting)
      , formatBundleFileInputFiles(userInputs.map(_.path))
        //only user bundle may be set on PolicyMode = Verify
      , formatMethodsUsebundle(userBundles.addNcfReporting.addDryRunManagement)
    )
  }


  /*
   * The logic that is in charge to add ncf reporting information
   * by calling the correct bundle when appropriate
   */
  implicit final class NcfReporting(bundles: List[TechniqueBundles]) {
    //now, for each technique that provided reports (i.e: an ncf technique), we must add the
    //NCF_REPORT_DEFINITION_BUNDLE_NAME just before the other bundle of the technique
    def addNcfReporting: List[TechniqueBundles] = bundles.map { tb =>
      //we assume that the bundle name to use as suffix of NCF_REPORT_DEFINITION_BUNDLE_NAME
      // is the first of the provided bundle sequence for that technique
      if(tb.providesExpectedReports) {
        val newMain = tb.main match {
          case Nil => Nil
          // in that case, we are using the same ReportId for report and bundle
          case firstBundle :: tail => (Bundle(firstBundle.id, BundleName(s"""current_technique_report_info("${firstBundle.name.value}")"""))
                                       :: firstBundle :: tail)
        }
        tb.copy(main = newMain)
      } else {
        tb
      }
    }
  }

  /*
   * Each bundle must be preceded by the correct "set_dry_mode" mode,
   * and we always end with a "set_dry_mode("false")".
   * Also, promiser must be differents for all items so that cfengine
   * doesn't try to avoid to do the set, so we are using the technique
   * promiser.
   */
  implicit final class DryRunManagement(bundles: List[TechniqueBundles]) {
    def addDryRunManagement: List[TechniqueBundles] = bundles match {
      case Nil  => Nil
      case list => list.map { tb =>
                       val pre = tb.policyMode match {
                         case PolicyMode.Audit  => audit
                         case PolicyMode.Enforce => enforce
                       }
                       tb.copy(pre = pre :: Nil)
                   //always remove dry mode in last action
                   } ::: ( cleanup :: Nil )
    }

    //before each technique, set the correct mode
    private[this] val audit   = Bundle(ReportId("internal"), BundleName("""set_dry_run_mode("true")"""))
    private[this] val enforce = Bundle(ReportId("internal"), BundleName("""set_dry_run_mode("false")"""))
    val dryRun = TechniqueId(TechniqueName("remove_dry_run_mode"), TechniqueVersion("1.0"))
    private[this] val cleanup = TechniqueBundles(Directive(dryRun.name.value), dryRun, Nil, enforce :: Nil, Nil, false, false, PolicyMode.Enforce)
  }


  /*
   * Method for formating list of "promiser usebundle => bundlename;"
   *
   * For the CFengine agent, we are waiting ONE string of the fully
   * formatted result, ie. something like: """
   *  "Rule1/directive one"                         usebundle => fetchFusionTools;
   *  "Rule1/some other directive with a long name" usebundle => doInventory;
   *  "An other rule/its directive"                 usebundle => virtualMachines;
   * """
   */
  def formatMethodsUsebundle(bundleSeq: Seq[TechniqueBundles]): List[String] = {
    //the promiser value (may) comes from user input, so we need to escape
    //also, get the list of bundle for each promiser.
    //and we don't need isSystem anymore
    val escapedSeq = bundleSeq.map(x => (ParameterEntry.escapeString(x.promiser.value, AgentType.CfeCommunity), x.bundleSequence) )

    //that's the length to correctly vertically align things. Most important
    //number in all Rudder !
    val alignWidth = if(escapedSeq.size <= 0) 0 else escapedSeq.map(_._1.size).max

    (escapedSeq.flatMap { case (promiser, bundles) =>
      bundles.map { bundle =>
        s""""${promiser}"${ " " * Math.max(0, alignWidth - promiser.size) } usebundle => ${bundle.name.value};"""
      }
    }.mkString( "\n")) :: Nil
  }

  /*
   * utilitary method for formating an input list
   * For the CFengine agent, we are waiting ONE string of the fully
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
      List("") //we must have one empty parameter to have the awaited behavior with string template
    } else {
      List(inputs.mkString("\"", s"""",\n"""", s"""","""))
    }
  }
}


