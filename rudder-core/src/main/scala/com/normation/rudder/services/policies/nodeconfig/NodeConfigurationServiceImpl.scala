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

import java.io.File
import java.io.PrintWriter

import org.joda.time.DateTime

import com.normation.cfclerk.domain.Cf3PolicyDraft
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleWithCf3PolicyDraft
import com.normation.rudder.exceptions.TechniqueException
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.services.policies.TemplateWriter
import com.normation.utils.Control._

import net.liftweb.common._
import net.liftweb.json.NoTypeHints
import net.liftweb.json.Serialization
import net.liftweb.json.Serialization.writePretty



/**
 * A class implementing the logic about node configuration change.
 * Extracted from NodeConfigurationServiceImpl to make it testable.
 */
class DetectChangeInNodeConfiguration extends Loggable {

  def detectChangeInNode(currentOpt: Option[NodeConfigurationCache], targetConfig: NodeConfiguration, directiveLib: FullActiveTechniqueCategory) : Set[RuleId] = {
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
    currentOpt match {
      case None =>
        //what do we do if we don't have a cache for the node ? All the target rules are "changes" ?
        logger.trace("`-> No node configuration cache availabe for that node")
        targetConfig.policyDrafts.map( _.ruleId ).toSet
      case Some(current) =>

        val target = NodeConfigurationCache(targetConfig)
        val allRuleIds = (current.policyCache.map( _.ruleId ) ++ target.policyCache.map( _.ruleId )).toSet

        // First case : a change in the minimalnodeconfig is a change of all CRs
        if (current.nodeInfoCache != target.nodeInfoCache) {
          logger.trace(s"`-> there was a change in the node inventory information")
          allRuleIds

        // Second case : a change in the system variable is a change of all CRs
        } else if(current.nodeContextCache != target.nodeContextCache) {
          logger.trace(s"`-> there was a change in the system variables of the node")
          allRuleIds

        // Third case : a change in the parameters is a change of all CRs
        } else if(current.parameterCache != target.parameterCache) {
          logger.trace(s"`-> there was a change in the parameters of the node")
          allRuleIds
        } else {

          //check for different policy draft.
          val currentDrafts = current.policyCache.map( x => (x.draftId, x) ).toMap
          val targetDrafts = target.policyCache.map( x => (x.draftId, x) ).toMap

          //draftid in one and not the other are new,
          //for the one in both, check both ruleId and cacheValue

          ((currentDrafts.keySet ++ targetDrafts.keySet).map(id => (currentDrafts.get(id), targetDrafts.get(id))).flatMap {
            case (None, None) => //should not happen
              Set[RuleId]()
            case (Some(PolicyCache(ruleId, _, _)), None) =>
              logger.trace(s"`-> rule with ID '${ruleId.value}' was deleted")
              Set(ruleId)
            case (None, Some(PolicyCache(ruleId, _, _))) =>
              logger.trace(s"`-> rule with ID '${ruleId.value}' was added")
              Set(ruleId)
            case (Some(PolicyCache(r0, d0, c0)), Some(PolicyCache(r1, d1, c1))) =>
              //d0 and d1 are equals by construction, but keep them for future-proofing
              if(d0 == d1) {
                if(
                   //check that the rule is the same
                      r0 == r1
                   //and that the policy draft is the same (it's cache value, actually)
                   && c0 == c1

                ) {
                  Set[RuleId]() //no modification
                } else {
                  logger.trace(s"`-> there was a change in the promise with draft ID '${d0.value}'")
                  Set(r0,r1)
                }
              } else Set[RuleId]()
          }) ++ {
            //we also have to add all Rule ID for a draft whose technique has been accepted since last cache generation
            //(because we need to write template again)
            val ids = (targetConfig.policyDrafts.collect {
              case r:RuleWithCf3PolicyDraft if(wasUpdatedSince(r.cf3PolicyDraft, current.writtenDate, directiveLib)) => r.ruleId
            }).toSet

            if(ids.nonEmpty) {
              logger.trace(s"`-> there was a change in the applied techniques (technique was updated) for rules ID [${ids.mkString(", ")}]")
            }
            ids
          }
        }
    }
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
    policyTranslator    : TemplateWriter
  , repository          : NodeConfigurationCacheRepository
  , logNodeConfig       : NodeConfigurationLogger
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
     * - there is at most one directive for each "unique" technique
     */
    def sanitizeOne(nodeConfig: NodeConfiguration) : Box[NodeConfiguration] = {

      //first of all: be sure to keep only one draft for a given draft id
      val deduplicateDraft = nodeConfig.policyDrafts.groupBy(_.draftId).map { case (draftId, set) =>
        val main = set.head
        //compare policy draft
        //Following parameter are not relevant in that comparison (we compare directive, not rule, here:)
        if(set.size > 1) {
          logger.error(s"The directive '${set.head.directiveId.value}' on rule '${set.head.ruleId.value}' was added several times on node '${nodeConfig.nodeInfo.id.value}' WITH DIFFERENT PARAMETERS VALUE. It's a bug, please report it. Taking one set of parameter at random for the promise generation.")
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

      //now, we have to case to process:
      // - directives based on "unique" technique: we must keep only one. And to attempt to get a little stability in
      //    our generated promises, for a given technique, we will try to always choose the same directive
      //    (in case of ambiguity)
      // - other: just add them all!

      val (otherDrafts, uniqueTechniqueBasedDrafts) = deduplicateDraft.partition(_.cf3PolicyDraft.technique.isMultiInstance)

      //sort unique based draft by technique, and then check priority on each groups

      val keptUniqueDraft = uniqueTechniqueBasedDrafts.groupBy(_.cf3PolicyDraft.technique.id).map { case (techniqueId, setDraft) =>

        val withSameTechnique = setDraft.toSeq.sortBy( _.cf3PolicyDraft.priority )
        //we know that the size is at least one, so keep the head, and log discard tails

        //two part here: discart less priorized directive,
        //and for same priority, take one at random (the first sorted by rule id, to keep some consistency)
        //and add a big warning

        val priority = withSameTechnique.head.cf3PolicyDraft.priority

        val lesserPriority = withSameTechnique.dropWhile( _.cf3PolicyDraft.priority == priority)

        //keep the directive with the first (alpha-num) ID - as good as other comparison.
        val samePriority = withSameTechnique.takeWhile( _.cf3PolicyDraft.priority == priority).sortBy(_.directiveId.value)

        val keep = samePriority.head

        //only one log for all discared draft
        if(samePriority.size > 1) {
          logger.warn(s"Unicity check: NON STABLE POLICY ON NODE '${nodeConfig.nodeInfo.hostname}' for mono-instance (unique) technique '${keep.cf3PolicyDraft.technique.id}'. Several directives with same priority '${keep.cf3PolicyDraft.priority}' are applied. Keeping (ruleId@@directiveId) '${keep.draftId.value}', discarding: ${samePriority.tail.map(_.draftId.value).mkString("'", "', ", "'")}")
        }
        logger.trace(s"Unicity check: on node '${nodeConfig.nodeInfo.id.value}' for mono-instance (unique) technique '${keep.cf3PolicyDraft.technique.id}': keeping (ruleId@@directiveId) '${keep.draftId.value}', discarding less priorize: ${lesserPriority.map(_.draftId.value).mkString("'", "', ", "'")}")

        keep

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
        case None => true
        case Some(e) => e != p
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

  /**
   * Write templates for node configuration that changed since the last write.
   *
   */
  def writeTemplate(rootNodeId: NodeId, nodesToWrite: Set[NodeId], allNodeConfigs: Map[NodeId, NodeConfiguration]) : Box[Seq[NodeConfiguration]] = {
    val nodeConfigsToWrite = allNodeConfigs.filterKeys(nodesToWrite.contains(_))
    //debug - but don't fails for debugging !
    logNodeConfig.log(nodeConfigsToWrite.values.toSeq) match {
      case eb:EmptyBox =>
        val e = eb ?~! "Error when trying to write node configurations for debugging"
        logger.error(e)
        e.rootExceptionCause.foreach { ex =>
          logger.error("Root exception cause was:", ex)
        }
      case _ => //nothing to do
    }

    policyTranslator.writePromisesForMachines(nodesToWrite, rootNodeId, allNodeConfigs).map(_ => nodeConfigsToWrite.values.toSeq )

  }


  override def detectChangeInNodes(nodes : Seq[NodeConfiguration], cache: Map[NodeId, NodeConfigurationCache], directiveLib: FullActiveTechniqueCategory) : Set[RuleId] = {
    nodes.flatMap{ x =>
      detectChangeInNode(cache.get(x.nodeInfo.id), x, directiveLib)
    }.toSet
  }


  override def detectChangeInNode(currentOpt: Option[NodeConfigurationCache], targetConfig: NodeConfiguration, directiveLib: FullActiveTechniqueCategory) : Set[RuleId] =
    detect.detectChangeInNode(currentOpt, targetConfig, directiveLib)
}
