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

package com.normation.rudder.services.policies.nodeconfig

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.RuleId
import com.normation.utils.Control.sequence
import net.liftweb.common._
import org.joda.time.DateTime
import com.normation.rudder.services.policies.write.Cf3PromisesFileWriterService
import com.normation.rudder.services.policies.BundleOrder
import com.normation.rudder.services.policies.write.Cf3PolicyDraftId


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
  , repository          : NodeConfigurationHashRepository
) extends NodeConfigurationService with Loggable {

  //delegate to repository for nodeconfig persistence
  def deleteNodeConfigurations(nodeIds:Set[NodeId]) :  Box[Set[NodeId]] = repository.deleteNodeConfigurations(nodeIds)
  def deleteAllNodeConfigurations() : Box[Unit] = repository.deleteAllNodeConfigurations
  def onlyKeepNodeConfiguration(nodeIds:Set[NodeId]) : Box[Set[NodeId]] = repository.onlyKeepNodeConfiguration(nodeIds)
  def cacheNodeConfiguration(nodeConfigurations: Set[NodeConfiguration], writtenDate: DateTime): Box[Set[NodeId]] = repository.save(nodeConfigurations.map(x => NodeConfigurationHash(x, writtenDate)))
  def getNodeConfigurationHash(): Box[Map[NodeId, NodeConfigurationHash]] = repository.getAll

  def sanitize(targets : Seq[NodeConfiguration]) : Box[Map[NodeId, NodeConfiguration]] = {

    /**
     * Sanitize directive to the node configuration, returning a new node configuration with
     * updated directives.
     *
     * That method check that:
     * - the directive added is not already in the NodeConfiguration (why ? perhaps a note to dev is better ?)
     * - a technique does not exist with two versions
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
          def r(j:JValue) = if(j == JNothing) "{}" else prettyRender(j)

          val jmain = Extraction.decompose(main)
          logger.error("First directivedraft: " + prettyRender(jmain))
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

  def selectUpdatedNodeConfiguration(nodeConfigurations: Map[NodeId, NodeConfiguration], cache: Map[NodeId, NodeConfigurationHash]): Set[NodeId] = {
    val notUsedTime = new DateTime(0) //this seems to tell us the nodeConfigurationHash should be refactor to split time frome other properties
    val newConfigCache = nodeConfigurations.map{ case (_, conf) => NodeConfigurationHash(conf, notUsedTime) }

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

  override def detectSerialIncrementRequest(nodes : Seq[NodeConfiguration], cacheIsEmpty: Boolean) : Set[RuleId] = {

    // changes in nodes don't imply changes in directives / rules / serial.
    // these change will lead to a new config id and generation if needed.
    // we only increment serial when the cache is empty, which mean that it
    // is a new rudder, or a "clear cache".
    // Note that if the cache is empty, all nodes will be rewriten in all
    // cases, but that allows to check that nodes really get their
    // policies (because node config id may be the same, but they will continue sending
    // reports with the previous serial)

    if(cacheIsEmpty) {
      nodes.flatMap( _.policyDrafts.map( _.id.ruleId ) ).toSet
    } else {
      Set()
    }
  }


}
