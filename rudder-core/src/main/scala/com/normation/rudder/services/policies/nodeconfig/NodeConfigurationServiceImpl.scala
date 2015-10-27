/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.services.policies.nodeconfig

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.services.policies.write.Cf3PolicyDraft
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.utils.Control.sequence
import net.liftweb.common._
import org.joda.time.DateTime
import com.normation.rudder.services.policies.write.Cf3PolicyDraft
import com.normation.rudder.services.policies.write.Cf3PolicyDraftId
import com.normation.rudder.services.policies.write.Cf3PromisesFileWriterService
import com.normation.rudder.services.policies.BundleOrder


/**
 * A class implementing the logic about node configuration change.
 * Extracted from NodeConfigurationServiceImpl to make it testable.
 */
class DetectChangeInNodeConfiguration extends Loggable {

  /**
   * Detect the change within a node with previous generation
   * If there is no cache for this node, it can mean it is a new node, or we clear cached
   *
   * If we have a cache for other nodes, then it means it is probably a new node; we don't report changes for this node
   * and changes will be caught by the change on rules
   *
   * If we don't have any cache at all, then we probably hit clear cache, and expect a full regeneration, so all rules are detected
   * as new version of the rules (and this is reinforced by the fact that we won't be able to detect change
   * in rules afterwards)
   */
  def detectChangeInNode(
        currentOpt  : Option[NodeConfigurationCache]
      , targetConfig: NodeConfiguration
      , directiveLib: FullActiveTechniqueCategory
      , cacheDefined: Boolean) : Set[RuleId] = {
    /*
     * Check if a policy draft (Cf3PolicyDraft) has a technique updated more recently
     * than the given date.
     *
     * If no date is passed, we consider that it's "now" and so that
     * nothing was updated.
     */
    def wasUpdatedSince(draft: Cf3PolicyDraft, optDate: Option[DateTime], directiveLib: FullActiveTechniqueCategory): Boolean = {
      //it's updated if any of the technique in the draft acceptation date is more recent than the given one

      optDate match {
        case None => false
        case Some(date) =>

        directiveLib.allTechniques.get(draft.technique.id) match {
          case None => //technique not available: consider it's an update
            true
          case Some((_, None)) => //no acceptation date available: consider it's an update
            true
          case Some((_, Some(d))) =>
            d.isAfter(date)
        }
      }
    }

    logger.trace(s"Checking changes in node '${targetConfig.nodeInfo.id.value}'")
    val changedRuleIds = currentOpt match {
      case None =>
        //what do we do if we don't have a cache for the node ? All the target rules are "changes" ? No, we skip,
        // as all changes will be caught later. Otherwise, it will increase the serial of all rules applied to this node
        if (cacheDefined) {
          logger.trace("`-> No node configuration cache available for that node")
          Set[RuleId]()
        } else {
          // there is no cache at all (no node as a cache, we can't know if it is a change or not, so we must assume
          // we need to increase the serial)
          logger.trace("`-> No node configuration cache available at all, increasing all serial")
          targetConfig.policyDrafts.map( _.id.ruleId ).toSet

        }
      case Some(current) =>

        val target = NodeConfigurationCache(targetConfig)
        val allRuleIds = (current.policyCache.map( _.draftId.ruleId ) ++ target.policyCache.map( _.draftId.ruleId )).toSet

        // First case : a change in the minimalnodeconfig is a change of all CRs
        // because we don't know what directive use informations from that - this should be improved
        if (current.nodeInfoCache != target.nodeInfoCache) {
          logger.trace(s"`-> there was a change in the node inventory information")
          allRuleIds


        // Second case : a change in the parameters is a change of all CRs
        // because we don't know what parameters are used in which directives - this should be improved
        } else if(current.parameterCache != target.parameterCache) {
          logger.trace(s"`-> there was a change in the parameters of the node")
          allRuleIds
        } else {

          //check for different policy draft.

          /*
           * Here, we need to work rule by rule to differenciate
           * the cases were:
           * - a directive was added/remove/updated on an already existing rule => rule serial must be updated
           * - a rule was fully added or remove => we don't need to change its serial,
           *   (but the missing expected reports will be generated in ReportingServiceImpl#updateExpectedReports
           *    in the case same serial, sub case different targets)
           */

          val currentDrafts = current.policyCache.groupBy( _.draftId.ruleId )
          val targetDrafts = target.policyCache.groupBy( _.draftId.ruleId )

          //draftid in one and not the other are new,
          //for the one in both, check both ruleId and cacheValue

          ((currentDrafts.keySet ++ targetDrafts.keySet).map(id => (id, currentDrafts.get(id), targetDrafts.get(id))).flatMap {
            case (ruleId, None, None) => //should not happen
              Set.empty[RuleId]

            /*
             * Here, the entire rule is no more applied to the node: we
             * don't update the serial
             */
            case (ruleId, Some(drafts), None) =>
              logger.trace(s"`-> rule with ID '${ruleId.value}' was deleted")
              Set.empty[RuleId]

            /*
             * Here, the entire rule starts to be applied to the node: we
             * don't update the serial.
             */
            case (ruleId, None, Some(drafts)) =>
              logger.trace(s"`-> rule with ID '${ruleId.value}' was added")
              Set.empty[RuleId]

            /*
             * That case is the interesting one: the rule is present in current
             * and target configuration for the node.
             * We check if all the drafts are the same (i.e same directives applied,
             * and no modification in directives). On any change, update the serial.
             */
            case (ruleId, Some(cDrafts), Some(tDrafts)) =>

              //here, we can just compare the sets for equality

              if(cDrafts == tDrafts) {
                Set.empty[RuleId]
              } else {
                //the set of directive which changed - because we don't have xor on set
                val diff = ((cDrafts -- tDrafts) ++ (tDrafts -- cDrafts)).map { case PolicyCache(Cf3PolicyDraftId(ruleId, directiveId), _) =>
                  directiveId.value
                }
                logger.trace(s"`-> there was a change in the rule with ID '${ruleId.value}', following directives are different: [${diff.mkString(", ")}]")
                Set(ruleId)
              }
          }) ++ {
            //we also have to add all Rule ID for a draft whose technique has been accepted since last cache generation
            //(because we need to write template again)
            val ids = (targetConfig.policyDrafts.collect {
              case r:Cf3PolicyDraft if(wasUpdatedSince(r, current.writtenDate, directiveLib)) => r.id.ruleId
            }).toSet

            if(ids.nonEmpty) {
              logger.trace(s"`-> there was a change in the applied techniques (technique was updated) for rules ID [${ids.mkString(", ")}]")
            }
            ids
          } ++ {
            // we also want to add rule with system variable if there was a change on them.
            // As we don't know here what is the system var which changed, we add any rule with at least
            // one system var - this should be improved
            if(current.nodeContextCache != target.nodeContextCache) {
              val ruleIdWithSystemVariable = targetConfig.policyDrafts.flatMap { x =>
                x.variableMap.values.find { _.spec.isSystem }.map { v =>  x.id.ruleId}
              }
              logger.trace(s"`-> there was a change in the system variables of the node for rules ID [${ruleIdWithSystemVariable.map(_.value).mkString(", ")}]")
              ruleIdWithSystemVariable
            } else {
              Set()
            }
          }
        }
    }

    logger.trace(s"`-> modified rules: [${changedRuleIds.map( _.value).mkString(", ")}]")
    changedRuleIds
  }

}



/**
 * Implementation of the Node Configuration service
 * It manages the NodeConfiguration content (the cache of the deployed conf)
 *
 * That implementation is not thread safe at all, and all call to its
 * methods should be made in the context of an actor
 * (deployment service and it's actor model is a good example)
 *
 */
class NodeConfigurationServiceImpl(
    policyTranslator    : Cf3PromisesFileWriterService
  , repository          : NodeConfigurationCacheRepository
) extends NodeConfigurationService with Loggable {

  private[this] val detect = new DetectChangeInNodeConfiguration()

  //delegate to repository for nodeconfig persistence
  def deleteNodeConfigurations(nodeIds:Set[NodeId]) :  Box[Set[NodeId]] = repository.deleteNodeConfigurations(nodeIds)
  def deleteAllNodeConfigurations() : Box[Unit] = repository.deleteAllNodeConfigurations
  def onlyKeepNodeConfiguration(nodeIds:Set[NodeId]) : Box[Set[NodeId]] = repository.onlyKeepNodeConfiguration(nodeIds)
  def cacheNodeConfiguration(nodeConfigurations: Set[NodeConfiguration]): Box[Set[NodeId]] = repository.save(nodeConfigurations.map(x => NodeConfigurationCache(x)))
  def getNodeConfigurationCache(): Box[Map[NodeId, NodeConfigurationCache]] = repository.getAll

  def sanitize(targets : Seq[NodeConfiguration]) : Box[Map[NodeId, NodeConfiguration]] = {

    /**
     * Sanitize directive to the node configuration, returning a new node configuration with
     * updated directives.
     *
     * That method check that:
     * - the directive added is not already in the NodeConfiguration (why ? perhaps a note to dev is better ?)
     * - a technique does not exists with two versions
     * - there is at most one directive for each "unique" technique
     */
    def sanitizeOne(nodeConfig: NodeConfiguration) : Box[NodeConfiguration] = {

      //first of all: be sure to keep only one draft for a given draft id
      val deduplicateDraft = nodeConfig.policyDrafts.groupBy(_.id).map { case (draftId, set) =>
        val main = set.head
        //compare policy draft
        //Following parameter are not relevant in that comparison (we compare directive, not rule, here:)
        if(set.size > 1) {
          logger.error(s"The directive '${set.head.id.directiveId.value}' on rule '${set.head.id.ruleId.value}' was added several times on node '${nodeConfig.nodeInfo.id.value}' WITH DIFFERENT PARAMETERS VALUE. It's a bug, please report it. Taking one set of parameter at random for the promise generation.")
          import net.liftweb.json._
          implicit val formats = Serialization.formats(NoTypeHints)
          def r(j:JValue) = if(j == JNothing) "{}" else pretty(render(j))

          val jmain = Extraction.decompose(main)
          logger.error("First directivedraft: " + pretty(render(jmain)))
          set.tail.foreach{ x =>
            val diff  = jmain.diff(Extraction.decompose(x))
            logger.error(s"Diff with other draft: \nadded:${r(diff.added)} \nchanged:${r(diff.changed)} \ndeleted:${r(diff.deleted)}")
          }
        }
        main
      }

      //now, check for technique version consistency
      val techniqueByName = nodeConfig.policyDrafts.groupBy(x => x.technique.id.name)
      // Filter this grouping by technique having two different version
      val multipleVersion = techniqueByName.filter(x => x._2.groupBy(x => x.technique.id.version).size > 1).map(x => x._1).toSet

      if(multipleVersion.nonEmpty) {
        return Failure(s"There are directives based on techniques with different versions applied to the same node, please correct the version for the following directive(s): ${multipleVersion.mkString(", ")}")
      }

      //now, we have to case to process:
      // - directives based on "unique" technique: we must keep only one. And to attempt to get a little stability in
      //    our generated promises, for a given technique, we will try to always choose the same directive
      //    (in case of ambiguity)
      // - other: just add them all!

      val (otherDrafts, uniqueTechniqueBasedDrafts) = deduplicateDraft.partition(_.technique.isMultiInstance)

      //sort unique based draft by technique, and then check priority on each groups

      val keptUniqueDraft = uniqueTechniqueBasedDrafts.groupBy(_.technique.id).map { case (techniqueId, setDraft) =>

        val withSameTechnique = setDraft.toSeq.sortBy( _.priority )
        //we know that the size is at least one, so keep the head, and log discard tails

        //two part here: discard less priorized directive,
        //and for same priority, take the first in rule/directive order
        //and add a big warning

        val priority = withSameTechnique.head.priority

        val lesserPriority = withSameTechnique.dropWhile( _.priority == priority)

        //keep the directive with
        val samePriority = withSameTechnique.takeWhile( _.priority == priority).sortWith{ case (x1, x2) =>
          BundleOrder.compareList(List(x1.ruleOrder, x1.directiveOrder), List(x2.ruleOrder, x2.directiveOrder)) <= 0
        }

        val keep = samePriority.head

        //only one log for all discared draft
        if(samePriority.size > 1) {
          logger.warn(s"Unicity check: NON STABLE POLICY ON NODE '${nodeConfig.nodeInfo.hostname}' for mono-instance (unique) technique '${keep.technique.id}'. Several directives with same priority '${keep.priority}' are applied. "+
              s"Keeping (ruleId@@directiveId) '${keep.id.value}' (order: ${keep.ruleOrder.value}/${keep.directiveOrder.value}, discarding: ${samePriority.tail.map(x => s"${x.id.value}:${x.ruleOrder.value}/${x.directiveOrder.value}").mkString("'", "', ", "'")}")
        }
        logger.trace(s"Unicity check: on node '${nodeConfig.nodeInfo.id.value}' for mono-instance (unique) technique '${keep.technique.id}': keeping (ruleId@@directiveId) '${keep.id.value}', discarding less priorize: ${lesserPriority.map(_.id.value).mkString("'", "', ", "'")}")

        val overrides = (samePriority.tail.map(x => (x.id.ruleId,x.id.directiveId)) ++ lesserPriority.map(x => (x.id.ruleId,x.id.directiveId))).toSet
        keep.copy(overrides = overrides)

      }

      Full(nodeConfig.copy(policyDrafts = (otherDrafts.toSet ++ keptUniqueDraft)))
    }


    for {
      sanitized <- sequence(targets) { sanitizeOne(_) }
    } yield {
      sanitized.map(c => (c.nodeInfo.id, c)).toMap
    }

  }

  def selectUpdatedNodeConfiguration(nodeConfigurations: Map[NodeId, NodeConfiguration], cache: Map[NodeId, NodeConfigurationCache]): Set[NodeId] = {
    val newConfigCache = nodeConfigurations.map{ case (_, conf) => NodeConfigurationCache(conf) }

    val (updatedConfig, notUpdatedConfig) = newConfigCache.toSeq.partition{ p =>
      cache.get(p.id) match {
        case None    => true
        case Some(e) => !e.equalWithoutWrittenDate(p)
      }
    }

    if(notUpdatedConfig.size > 0) {
      logger.debug(s"Not updating non-modified node configuration: [${notUpdatedConfig.map( _.id.value).mkString(", ")}]")
    }

    if(updatedConfig.size == 0) {
      logger.info("No node configuration was updated, no promises to write")
      Set()
    } else {
      val nodeToKeep = updatedConfig.map( _.id ).toSet
      logger.info(s"Configuration of following nodes were updated, their promises are going to be written: [${updatedConfig.map(_.id.value).mkString(", ")}]")
      nodeConfigurations.keySet.intersect(nodeToKeep)
    }
  }

  override def detectChangeInNodes(nodes : Seq[NodeConfiguration], cache: Map[NodeId, NodeConfigurationCache], directiveLib: FullActiveTechniqueCategory) : Set[RuleId] = {
    nodes.flatMap{ x =>
      detectChangeInNode(cache.get(x.nodeInfo.id), x, directiveLib, cache.size>0)
    }.toSet
  }


  // cacheDefined is a boolean defining if the cache of node is defined (we know what was written before) or not
  // it change the behaviour for returning list of rules to change in case of no cache:
  // - No cache for this node only, then don't return change for the node
  // - No cache for any node, then we can't know, and we return a change for all rules applied to the nodes
  override def detectChangeInNode(currentOpt: Option[NodeConfigurationCache], targetConfig: NodeConfiguration, directiveLib: FullActiveTechniqueCategory, cacheDefined: Boolean) : Set[RuleId] =
    detect.detectChangeInNode(currentOpt, targetConfig, directiveLib, cacheDefined)
}
