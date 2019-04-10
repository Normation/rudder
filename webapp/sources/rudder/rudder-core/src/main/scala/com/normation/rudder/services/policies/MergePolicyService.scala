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

import com.normation.cfclerk.domain.{RunHook, TechniqueGenerationMode, TechniqueName}
import com.normation.inventory.domain.AgentType
import cats.kernel.Order
import cats.data._
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.policies.GlobalPolicyMode
import net.liftweb.common._
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.logger.PolicyLogger
import com.normation.utils.Control.sequence

import com.normation.zio._

/*
 * This file contains all the logic that allows to build a List of policies, for a node,
 * given the list of all applicable "BoundPolicyDraft" to that node.
 */
final object MergePolicyService {

  import com.normation.rudder.utils.Utils.eitherToBox
  /*
   * Order that compare Priority THEN bundle order to
   * sort BoundPolicyDraft
   */
  val priorityThenBundleOrder = new Order[BoundPolicyDraft]() {
    /* by definition:
     * x <= y    x >= y      Int
     * true      true        = 0     (corresponds to x == y)
     * true      false       < 0     (corresponds to x < y)
     * false     true        > 0     (corresponds to x > y)
     *
     * So:
     * d1 priority = 0 && d2 priority = 10 means d2 should come after d1, so comparison shoud
     * return a negative number, so d1 - d2 (0 - 10)
     */
    def compare(d1: BoundPolicyDraft, d2: BoundPolicyDraft) = {
      (d1.priority - d2.priority) match {
        case 0 => //sort by bundle order
          // the bundle full order in the couple (rule name, directive name)
          BundleOrder.compareList(d1.ruleOrder :: d1.directiveOrder :: Nil, d2.ruleOrder :: d2.directiveOrder :: Nil)
        case i => i
      }
    }
  }

  /*
   * Order that compare ONLY bundle order to
   * sort BoundPolicyDraft
   */
  val onlyBundleOrder = new Order[BoundPolicyDraft]() {
    def compare(d1: BoundPolicyDraft, d2: BoundPolicyDraft) = {
      BundleOrder.compareList(d1.ruleOrder :: d1.directiveOrder :: Nil, d2.ruleOrder :: d2.directiveOrder :: Nil)
    }
  }

  /*
   * That methods takes all the policy draft of a node and does what need to be done
   * to have a list of policies that can be translated into agent specific config files.
   * That includes:
   * - merge directives that need to be merged,
   * - split system and user policy,
   * - chose policy for unique technique,
   * - retrieve last update time for techniques,
   * - sanity check policies:
   *   - agent target is ok
   *   - for merged directives:
   *     - same policy mode
   *     - same technique version
   * - sort everything
   *
   * NodeInfo is only used for reporting, that method should be contextualized in an other fashion to avoid that.
   */
  def buildPolicy(nodeInfo: NodeInfo, mode: GlobalPolicyMode, boundedPolicyDrafts: Seq[BoundPolicyDraft]): Box[List[Policy]] = {

    // now manage merge of mutli-instance mono-policy techniques
    // each merge can fails because of a consistency error, so we are grouping merge and checks
    // for a technique in a failable function.
    // must give at least one element in parameter
    def merge(nodeInfo: NodeInfo, agentType: AgentType, globalPolicyMode: GlobalPolicyMode, drafts: List[BoundPolicyDraft]): Box[Policy] = {

      //
      // ACTUALY check and merge if needed
      //
      drafts match {
        case Nil =>
          Failure(s"Policy Generation is trying to merge a 0 directive set, which is a development bug. Please report")
        case draft :: Nil => //no merge actually needed
          draft.toPolicy(agentType)
        case d :: tail => //merging needed
          for {
             // check same technique (name and version), policy mode
            sameTechniqueName <- drafts.map( _.technique.id.name ).distinct match {
                                   case name :: Nil => Full(name)
                                   case list        => Failure(s"Policy Generation is trying to merge several directives from different techniques for " +
                                                         s"node ${nodeInfo.hostname} '${nodeInfo.id.value}'. This i likely a bug, please report it. "+
                                                         s"Techniques: ${drafts.map(_.technique.id.toString).mkString(", ")}"
                                                       )
                                 }
            // check that all drafts are system or none are
            sameIsSystem      <- if(drafts.map( _.isSystem ).distinct.size == 1) {
                                   Full("ok")
                                 } else {
                                   Failure(s"Policy Generation is trying to merge several directives with some being systems and other not for " +
                                     s"node ${nodeInfo.hostname} '${nodeInfo.id.value}'. This is likely a bug, please report it. "+
                                     s"Techniques: ${drafts.map(_.technique.id.toString).mkString(", ")}" //not sure if we want techniques or directives or rules here.
                                   )
                                 }
            // check that all drafts are bound to
            sameVersion       <- drafts.map( _.technique.id.version ).distinct match {
                                   case v :: Nil => Full(v)
                                   case list     => Failure(s"Node ${nodeInfo.hostname} '${nodeInfo.id.value}' get directives from different versions of technique '${sameTechniqueName}', but " +
                                                            s"that technique does not support multi-policy generation. Problematic rules/directives: " +
                                                            drafts.map(d => d.id.ruleId.value + " / " + d.id.directiveId.value).mkString(" ; ")
                                                    )
                                 }
            /*
             * Here, we must go to some lengh to try to keep the "directive" policy mode (for the merge). So we need to distinguish
             * four cases:
             * - all drafts have None policy mode => the result is none.
             * - all drafts have Some(policy mode) and all the same => the directive get that Some(policy mode)
             * - at least two draft have Some(policy mode) which are differents => it's an error, report it
             * - we have some homogeneous Some(policy mode) with at least one None => we must compute the resulting mode with default (global, node) values.
             * The last two can be computed with PolicyMode facility .
             */
            samePolicyMode    <- drafts.map( _.policyMode ).distinct match {
                                   case Nil         => Full(None) //should not happen
                                   case mode :: Nil => Full(mode) //either None or Some(mode), that's ok
                                   case modes       => PolicyMode.computeMode(globalPolicyMode, nodeInfo.node.policyMode, modes).map(Some(_)).toBox ?~! (s"Node ${nodeInfo.hostname} "+
                                                          s"'${nodeInfo.id.value}' get directives with incompatible different policy mode but technique " +
                                                          s"'${sameTechniqueName}/${sameVersion}' does not support multi-policy generation. Problematic rules/directives: " +
                                                          drafts.map(d => d.id.ruleId.value + " / " + d.id.directiveId.value).mkString(" ; "))
                                 }
            // actually merge.
            // Be carefull, there is TWO merge to consider:
            // 1. the order on which the vars are merged. It must follow existing semantic, i.e:
            //    first by priority, then by rule order, then by directive order.
            sortedVars     =  NonEmptyList(d, tail).sorted(priorityThenBundleOrder).map { d =>
                                PolicyVars(
                                  d.id
                                  , d.policyMode
                                  , d.expandedVars
                                  , d.originalVars
                                  , d.trackerVariable
                                )
                              }
            // 2. what is the rule / directive (and corresponding bundle order) to use. Here,
            //    we need to keep the most prioritary based on BundleOrder to ensure that variables
            //    are correctly initialized before there use in the bundle sequence.
            mainDraft      <- NonEmptyList(d, tail).sorted(onlyBundleOrder).head.toPolicy(agentType)
          } yield {
            // and then we merge all draft values into main draft.
            mainDraft.copy(policyVars = sortedVars, policyMode = samePolicyMode)
          }
      }
    }

    /*
     * We need to split draft in 3 categories:
     * - draft from non-multi instance techniques: we must choose which one to take
     * - draft from multi-instance, non multi-directive-gen technique: we need to
     *   merge directive and check specific consistency
     * - draft from multi-instance, multi-directive-gen technique (nothing special for them)
     */
    final case class GroupedDrafts(
        uniqueInstance : Map[TechniqueName, Seq[BoundPolicyDraft]]
      , toMerge        : Map[TechniqueName, Seq[BoundPolicyDraft]]
      , multiDirectives: Set[BoundPolicyDraft]
    ) {
      // return a copy of that group with one more toMerge at the correct place
      def addToMerge(draft: BoundPolicyDraft): GroupedDrafts = {
        val name = draft.technique.id.name
        val thatTechnique = toMerge.getOrElse(name, Seq()) :+ draft
        this.copy(toMerge = toMerge + (name -> thatTechnique))
      }
      // return a copy of that group with on more toMerge at the correct place
      def addUniqueInstance(draft: BoundPolicyDraft): GroupedDrafts = {
        val name = draft.technique.id.name
        val thatTechnique = uniqueInstance.getOrElse(draft.technique.id.name, Seq()) :+ draft
        this.copy(uniqueInstance = uniqueInstance + (name -> thatTechnique))
      }
    }

    //
    // Actually do stuff !
    //

    /*
     * First: we must be sure that each BoundPolicyDraft has a unique id.
     * It seems to be a sequel of the past, and we most likely can get ride of it.
     */
    val deduplicateDrafts = boundedPolicyDrafts.groupBy(x => (x.id.ruleId, x.id.directiveId)).map { case (draftId, seq) =>
      val main = seq.head //can not fail because of groupBy
      //compare policy draft
      //Following parameter are not relevant in that comparison (we compare directive, not rule, here:)
      if(seq.size > 1) {
        PolicyLogger.error(s"The directive '${seq.head.id.directiveId.value}' on rule '${seq.head.id.ruleId.value}' was added several times on node "+
                     s"'${nodeInfo.id.value}' WITH DIFFERENT PARAMETERS VALUE. It's a bug, please report it. Taking one set of parameter "+
                     s"at random for the promise generation.")
        import net.liftweb.json._
        implicit val formats = Serialization.formats(NoTypeHints)
        def r(j:JValue) = if(j == JNothing) "{}" else prettyRender(j)

        val jmain = Extraction.decompose(main)
        PolicyLogger.error("First directivedraft: " + prettyRender(jmain))
        seq.tail.foreach{ x =>
          val diff  = jmain.diff(Extraction.decompose(x))
          PolicyLogger.error(s"Diff with other draft: \nadded:${r(diff.added)} \nchanged:${r(diff.changed)} \ndeleted:${r(diff.deleted)}")
        }
      }
      main
    }

    // set trackingKeyVariable to the correct values
    // remark: now that trackingKeyVariable is independant from serial, it could just
    // be given as a method from the draft (no need for more info)
    val updatedTrackingKeyValues = deduplicateDrafts.map { d =>
      val (trackingKeyVariable, trackedVariable) = d.getDirectiveVariable

      val values = {
        // Only multi-instance policy may have a trackingKeyVariable with high cardinal
        // Because if the technique is Unique, then we can't have several directive ID on the same
        // rule, and we just always use the same cf3PolicyId
        val size = if (d.technique.isMultiInstance) { trackedVariable.values.size } else { 1 }
        Seq.fill(size)(d.id.getReportId)
      }
      val newTrackingKey = trackingKeyVariable.copyWithSavedValues(values) match {
        case Left(err) =>
          PolicyLogger.error(s"Error when updating tracking key variable for '${d.id.value}'. Using initial values. Error was: ${err.fullMsg}")
          trackingKeyVariable
        case Right(key) => key
      }
      d.copy(trackerVariable = newTrackingKey)
    }

    // group directives by non-multi-instance, multi-instance non-multi-policy, multi-instance-multi-policy
    val groupedDrafts = (GroupedDrafts(Map(),Map(),Set()) /: updatedTrackingKeyValues) { case(grouped, draft) =>
      if(draft.technique.isMultiInstance) {
        draft.technique.generationMode match {
          case TechniqueGenerationMode.MultipleDirectives | TechniqueGenerationMode.MultipleDirectivesWithParameters =>
            grouped.copy( multiDirectives = grouped.multiDirectives + draft )
          case TechniqueGenerationMode.MergeDirectives =>
            //look is there is already directives for the technique
            grouped.addToMerge(draft)
        }
      } else {
        grouped.addUniqueInstance(draft)
      }
    }

    // choose among directives from non-multi-instance technique which one to keep
    val keptUniqueDraft = groupedDrafts.uniqueInstance.map { case (techniqueName, drafts) =>

      val withSameTechnique = drafts.sortBy( _.priority )
      //we know that the size is at least one, so keep the head, and log discard tails

      //two part here: discard less priorized directive,
      //and for same priority, take the first in rule/directive order
      //and add a big warning

      val priority = withSameTechnique.head.priority

      val lesserPriority = withSameTechnique.dropWhile( _.priority == priority )

      //keep the directive with
      val samePriority = withSameTechnique.takeWhile( _.priority == priority).sortWith{ case (d1, d2) =>
        BundleOrder.compareList(List(d1.ruleOrder, d1.directiveOrder), List(d2.ruleOrder, d2.directiveOrder)) <= 0
      }

      val keep = samePriority.head

      //only one log for all discard draft
      if(samePriority.size > 1) {
        PolicyLogger.warn(s"Unicity check: NON STABLE POLICY ON NODE '${nodeInfo.hostname}' for mono-instance (unique) technique "+
            s"'${keep.technique.id}'. Several directives with same priority '${keep.priority}' are applied. "+
            s"Keeping (ruleId@@directiveId) '${keep.id.ruleId.value}@@${keep.id.directiveId.value}' (order: ${keep.ruleOrder.value}/"+
            s"${keep.directiveOrder.value}, discarding: ${samePriority.tail.map(x => s"${x.id.ruleId.value}@@${x.id.directiveId.value}:"+
            s"${x.ruleOrder.value}/${x.directiveOrder.value}").mkString("'", "', ", "'")}")
      }
      PolicyLogger.trace(s"Unicity check: on node '${nodeInfo.id.value}' for mono-instance (unique) technique '${keep.technique.id}': "+
          s"keeping (ruleId@@directiveId) '${keep.id.ruleId.value}@@${keep.id.directiveId.value}', discarding less priorize: "+
          s"${lesserPriority.map(x => x.id.ruleId.value+"@@"+x.id.directiveId.value).mkString("'", "', ", "'")}")

      val o = (samePriority.tail.map(x => PolicyId(x.id.ruleId,x.id.directiveId,x.technique.id.version)) ++
                  lesserPriority.map(x => PolicyId(x.id.ruleId,x.id.directiveId,x.technique.id.version))
              ).toSet

      keep.copy(overrides = o)
    }


    // for multiDirective directives, we must only keep one copy of the same directive even if provided by several rules
    // (ie: some directive id => only keep the first by (rule name, directive name)
    val deduplicatedMultiDirective = groupedDrafts.multiDirectives.groupBy( _.id.directiveId ).map { case (directiveId, set) =>
      set.toList.sorted(onlyBundleOrder.toOrdering).head
    }


    // now proceed the policies that need to be merged
    for {
      agent  <- Box(nodeInfo.agentsName.headOption) ?~! s"No agent defined for Node ${nodeInfo.hostname}, (id ${nodeInfo.id.value}), at least one should be defined"
      merged <- {
                val drafts = groupedDrafts.toMerge.toSeq
                sequence(drafts) { case (name, seq) =>
                  merge(nodeInfo, agent.agentType, mode, seq.toList)
                }
      }
      //now change remaining BoundPolicyDraft to Policy, managing tracking variable values
      others <- { import cats.implicits._
                  (keptUniqueDraft ++ deduplicatedMultiDirective).toList.traverse( _.toPolicy(agent.agentType) )
                }
    } yield {
      // we are sorting several things in that method, and I'm not sure we want to sort them in the same way (and so
      // factorize out sorting core) or not. We sort:
      // - policies based on non-multi-instance technique, by priority and then rule/directive order, to choose the correct one
      // - in a multi-instance, mono-policy case, we sort directives in the same fashion (but does priority make sense here?)
      // - and finally, here we sort all drafts (in that case, only by bundle order)

      (merged++others).sortWith { case (d1, d2) =>
        BundleOrder.compareList(List(d1.ruleOrder, d1.directiveOrder), List(d2.ruleOrder, d2.directiveOrder)) <= 0
      }.toList
    }
  }

  /*
    * This method take all the hooks with the corresponding reportId / mode, and
    * merge what should be merged.
    * Order is not kept
    */
  def mergeRunHooks(policies: List[Policy], nodePolicyMode: Option[PolicyMode], globalPolicyMode: GlobalPolicyMode): List[NodeRunHook] = {
    /*
     * Hooks are merge:
     *  - for the same kind, name and parameter (exactly the same, "parameters" order is critical)
     * Then:
     *  - append all condition (they will be "or", but the syntax is agent specific)
     *  - append pair of (reportId, PolicyMode)
     *
     * If at least one of kind, name, parameter is not the same between two hooks, the are considered different.
     *
     * The merge must keep both the List[Policy] order AND in a Policy, the List[RunHook] order. So we
     * avoid groupBy
     */
    // utility class to store reportId + RunHook
    case class BoundHook(id: PolicyId, mode: PolicyMode, technique: String, hook: RunHook)

    def recMerge(currentHook: BoundHook, remaining: List[BoundHook]): List[NodeRunHook] = {
      // partition between mergeable hooks and non-mergeable one
      val (toMerge, other) = remaining.partition(h =>
        h.hook.kind == currentHook.hook.kind &&
          h.hook.bundle == currentHook.hook.bundle &&
          h.hook.parameters == currentHook.hook.parameters
      )
      //now, build the "NodeRunHook" from the currentHook ++ toMerge
      val mergeable = currentHook :: toMerge
      val nodeRunHook = NodeRunHook(
          currentHook.hook.bundle
        , currentHook.hook.kind
        , mergeable.map(h => NodeRunHook.ReportOn(h.id, h.mode, currentHook.technique, currentHook.hook.report))
        , currentHook.hook.parameters
      )
      // Recurse on "other" hooks, getting the one in head position as new current.
      // And keep the order, so "nodeRunHook" is on head!
      nodeRunHook :: (other match {
        case Nil => Nil
        case h :: tail => recMerge(h, tail)
      })
    }

    // OK, so the sort order is: for each policy, for each policyVar (i.e each directive if merged),
    // for each hook, in the node policy order.
    val sortedBoundHooks = for {
      p <- policies
      v <- p.policyVars.toList
      h <- p.technique.agentConfig.runHooks
    } yield {
      BoundHook(
          v.policyId
        , PolicyMode.directivePolicyMode(globalPolicyMode, nodePolicyMode, v.policyMode, p.technique.isSystem)
        , p.technique.id.name.value
        , h
      )
    }

    sortedBoundHooks match {
      case Nil => Nil
      case h :: tail => recMerge(h, tail)
    }

  }
}
