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


import com.normation.cfclerk.domain.Bundle
import com.normation.cfclerk.domain.Technique
import com.normation.inventory.domain.NodeId
import com.normation.rudder.services.policies.BundleOrder
import com.normation.utils.Control._


import net.liftweb.common._
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.GlobalPolicyMode

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
 */
final case class BundleSequenceVariables(
    // The first two variables are not used
    // since 3.2, kept for migration compat from 3.1

    // A list of unique file name to include.
    inputlist : String
    // the bundle list.
  , bundlelist: String

    // The next four variable are used since 3.2

    // the list of system inputs promise file
  , systemDirectivesInputs   : String
    // the list of formated "usebundle" methods
    // for system techniques
  , systemDirectivesUsebundle: String
    // the list of user inputs promise file
  , directivesInputs   : String
    // the list of formated "usebundle" methods
    // for user techniques
  , directivesUsebundle: String
)

/**
 * This file groups together everything related to building the bundle sequence and
 * related system variables.
 *
 * It is in charge to:
 * - correctly get bundles from techniques,
 * - sort them accordingly to defined rules,
 * - add utility bundle when needed, like ncf logging bundle and dry-run mode
 */
final object BuildBundleSequence extends Loggable {

  /*
   * A cfengine input file to include
   */
  final case class Input(path: String, isSystem: Boolean)

  //ad-hoc data structure to denote a promiser name
  final case class Promiser(value: String)

  /*
   * A to-be-written list of bundle related to a unique
   * technique. They all have the same promiser, derived
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
      promiser               : Promiser
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

  /*
   * The main entry point of the object: for each variable related to
   * bundle sequence, compute the corresponding values.
   *
   * This may fail if the policy modes are not consistant for directives
   * declined from the same (multi-instance) technique.
   */
  def prepareBundleVars(
      nodeId          : NodeId
    , nodePolicyMode  : Option[PolicyMode]
    , globalPolicyMode: GlobalPolicyMode
    , container       : Cf3PolicyDraftContainer
  ): Box[BundleSequenceVariables] = {

    logger.trace(s"Preparing bundle list and input list for node : ${nodeId.value}")

    // Fetch the policies configured and sort them according to rules and with the system policies first
    sortTechniques(nodeId, nodePolicyMode, globalPolicyMode, container) match {
      case eb: EmptyBox =>
        val msg = eb match {
          case Empty      => ""
          case f: Failure => //here, dedup
            ": " + f.failureChain.map(m => m.msg.trim).toSet.mkString("; ")
        }
        Failure(s"Error when trying to generate the list of directive to apply to node '${nodeId.value}' ${msg}")

      case Full(sortedTechniques) =>

        // Then builds bundles and inputs:

        // - build techniques bundles from the sorted list of techniques
        val techniquesBundles = sortedTechniques.toList.map(buildTechniqueBundles(nodeId)).removeEmptyBundle.addNcfReporting

        // - build list of inputs file to include: all the outPath of templates that should be "included".
        //   (returns the pair of (outpath, isSystem) )

        val inputs: Seq[Input] = sortedTechniques.flatMap {case (technique, _, _) =>
            technique.templates.collect { case template if(template.included) => Input(template.outPath, technique.isSystem) } ++
            technique.files.collect { case file if(file.included) => Input(file.outPath, technique.isSystem) }
        }

        //split system and user directive (technique)
        val (systemInputs, userInputs) = inputs.partition( _.isSystem )
        val (systemBundle, userBundle) = techniquesBundles.partition( _.isSystem )

        //only user bundle may be set on PolicyMode = Verify
        val userBundleWithDryMode = userBundle.addDryRunManagement

        // All done, return all the correctly formatted system variables
        Full(BundleSequenceVariables(
            inputlist                 = formatBundleFileInputs(inputs.map(_.path))
          , bundlelist                = techniquesBundles.flatMap( _.bundleSequence.map(_.name)).mkString(", ", ", ", "")
          , systemDirectivesInputs    = formatBundleFileInputs(systemInputs.map(_.path))
          , systemDirectivesUsebundle = formatMethodsUsebundle(systemBundle)
          , directivesInputs          = formatBundleFileInputs(userInputs.map(_.path))
          , directivesUsebundle       = formatMethodsUsebundle(userBundleWithDryMode)
        ))
      }
  }

  ////////////////////////////////////////////
  ////////// Implementation details //////////
  ////////////////////////////////////////////

  /*
   * For each techniques, build:
   * - the promiser
   * - the list of bundle included,
   */
  def buildTechniqueBundles(nodeId: NodeId)(t3: (Technique, List[BundleOrder], PolicyMode)): TechniqueBundles = {
    // naming things to make them clear
    val technique  = t3._1
    val promiser   = Promiser(t3._2.map(_.value).mkString("/"))
    val policyMode = t3._3

    // We need to remove zero-length bundle name from the bundlesequence (like, if there is no ncf bundles to call)
    // to avoid having two successives commas in the bundlesequence
    val techniqueBundles = technique.bundlesequence.flatMap { bundle =>
      if(bundle.name.trim.size > 0) {
        Some(bundle)
      } else {
        logger.warn(s"Technique '${technique.id}' used in node '${nodeId.value}' contains some bundle with empty name, which is forbidden and so they are ignored in the final bundle sequence")
        None
      }
    }.toList

    TechniqueBundles(promiser, Nil, techniqueBundles, Nil, technique.isSystem, technique.providesExpectedReports, policyMode)
  }

  /*
   * Some Techniques don't have any bundle (at least common).
   * We don't want to include these technique in the bundle sequence,
   * obviously
   */
  implicit final class NoBundleTechnique(bundles: List[TechniqueBundles]) {
    def removeEmptyBundle: List[TechniqueBundles] = bundles.filterNot(_.main.isEmpty)
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
          case firstBundle :: tail => (Bundle(s"""current_technique_report_info("${firstBundle.name}")""")
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
    private[this] val audit  = Bundle("""set_dry_run_mode("true")""")
    private[this] val enforce = Bundle("""set_dry_run_mode("false")""")
    private[this] val cleanup = TechniqueBundles(Promiser("remove_dry_run_mode"), Nil, enforce :: Nil, Nil, false, false, PolicyMode.Enforce)
  }

  /*
   * Method for formating list of "promiser usebundle => bundlename;"
   */
  def formatMethodsUsebundle(bundleSeq: Seq[TechniqueBundles]): String = {
    //the promiser value (may) comes from user input, so we need to escape
    //also, get the list of bundle for each promiser.
    //and we don't need isSystem anymore
    val escapedSeq = bundleSeq.map(x => (ParameterEntry.escapeString(x.promiser.value), x.bundleSequence) )

    //that's the length to correctly vertically align things. Most important
    //number in all Rudder !
    val alignWidth = if(escapedSeq.size <= 0) 0 else escapedSeq.map(_._1.size).max

    escapedSeq.flatMap { case (promiser, bundles) =>
      bundles.map { bundle =>
        s""""${promiser}"${ " " * Math.max(0, alignWidth - promiser.size) } usebundle => ${bundle.name};"""
      }
    }.mkString( "\n")
  }

  /*
   * utilitary method for formating an input list
   */
  def formatBundleFileInputs(x: Seq[String]) = {
    val inputs = x.distinct
    if (inputs.isEmpty) {
      ""
    } else {
      inputs.mkString("\"", s"""",\n"""", s"""",""")
    }
  }

  /*
   * Sort the techniques according to the order of the associated BundleOrder of Cf3PolicyDraft.
   * Sort at best: sort rule then directives, and take techniques on that order, only one time
   * Sort system directive first.
   *
   * CAREFUL: this method only take care of sorting based on "BundleOrder", other sorting (like
   * "system must go first") are not taken into account here !
   */
  def sortTechniques(
      nodeId          : NodeId
    , nodePolicyMode  : Option[PolicyMode]
    , globalPolicyMode: GlobalPolicyMode
    , container       : Cf3PolicyDraftContainer
  ): Box[Seq[(Technique, List[BundleOrder], PolicyMode)]] = {

    val techniques = container.getTechniques().values.toList

    def compareBundleOrder(a: Cf3PolicyDraft, b: Cf3PolicyDraft): Boolean = {
      BundleOrder.compareList(List(a.ruleOrder, a.directiveOrder), List(b.ruleOrder, b.directiveOrder)) <= 0
    }

    val drafts = container.cf3PolicyDrafts.values.toSeq

    for {
      //for each technique, get it's best order from draft (if several directive use it) and return a pair (technique, List(order))
      triples <- bestEffort(techniques) { t =>

                   val tDrafts = drafts.filter { _.technique.id == t.id }.sortWith( compareBundleOrder )

                   //the order we want is the one with the lowest draft order, or the default one if no draft found (but that should not happen by construction)
                   val order = tDrafts.map( t => List(t.ruleOrder, t.directiveOrder)).headOption.getOrElse(List(BundleOrder.default))

                   // until we have a directive-level granularity for policy mode, we have to define a technique-level policy mode
                   // if directives don't have a consistant policy mode, we fail (and report it)
                   (PolicyMode.computeMode(globalPolicyMode, nodePolicyMode, tDrafts.map(_.policyMode)).map { policyMode =>
                     (t, order, policyMode)
                   }) match {
                     case Full(x)      => Full(x)
                     case eb: EmptyBox =>
                       val msg = eb match {
                         case Empty            => ""
                         case Failure(m, _, _) => ": " + m
                       }
                       Failure(s"Error with Technique ${t.name} and [Rule ID // Directives ID: Policy Mode] ${tDrafts.map { x =>
                         s"[${x.id.ruleId.value} // ${x.id.directiveId.value}: ${x.policyMode.map(_.name).getOrElse("inherited")}]"
                       }.mkString("; ") } ${msg}")
                   }
                 }
    } yield {

      //now just sort the pair by order and keep only techniques
      val ordered = triples.sortWith { case ((_, o1, _), (_, o2, _)) => BundleOrder.compareList(o1, o2) <= 0 }

      //some debug info to understand what order was used for each node:
      if(logger.isDebugEnabled) {
        val sorted = ordered.map(p => s"${p._1.name}: [${p._2.map(_.value).mkString(" | ")}]").mkString("[","][", "]")
        logger.debug(s"Sorted Technique (and their Rules and Directives used to sort): ${sorted}")
      }

      ordered
    }
  }
}
