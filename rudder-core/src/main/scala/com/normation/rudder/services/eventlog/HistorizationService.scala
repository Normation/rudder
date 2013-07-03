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

package com.normation.rudder.services.eventlog

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.logger.HistorizationLogger
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.repository.HistorizationRepository
import com.normation.rudder.repository.jdbc.SerializedGroups

import net.liftweb.common._

/**
 * At each deployment, we compare the content of the groups/PI/CR in the ldap with the content
 * in the database
 * If there is a change (serial updated, name changed, size of group modification, etc)
 * we close the previous entry, and add a new one
 *
 */
trait HistorizationService {

  /**
   * Update the nodes, based on what is on the ldap, and return nothing (I don't know yet how to know what has been updated)
   */
  def updateNodes(allNodeInfo: Set[NodeInfo]) : Box[Unit]


  /**
   * Update the groups, based on what is on the ldap, and return nothing (I don't know yet how to know what has been updated)
   */
  def updateGroups(groupLib: FullNodeGroupCategory) : Box[Unit]

  /**
   * Update the policy details, based on what is on the ldap and what is on the file system.
   * A directive changed when it is deleted, renamed, changed version, changed priority,
   * it's underlying PT changed name, description
   */
  def updateDirectiveNames(directiveLib: FullActiveTechniqueCategory) : Box[Unit]

  def updatesRuleNames(rules:Seq[Rule]) : Box[Unit]

}


class HistorizationServiceImpl(
    historizationRepository: HistorizationRepository
) extends HistorizationService {


  override def updateNodes(allNodeInfo: Set[NodeInfo]) : Box[Unit] = {

    val nodeInfos = allNodeInfo.filterNot(_.isPolicyServer).toSeq

    try {
      // fetch all the current nodes in the jdbc
      val registered = historizationRepository.getAllOpenedNodes().map(x => x.nodeId -> x).toMap

      // detect changes
      val changed = nodeInfos.filter(x => registered.get(x.id.value) match {
        case None => true
        case Some(entry) => (entry.nodeName != x.hostname || entry.nodeDescription != x.description )
      })

      // a node closable is a node that is current in the database, but don't exist in the
      // ldap
      val closable = registered.keySet.filter(x => !(nodeInfos.map(node => node.id.value)).contains(x))

      historizationRepository.updateNodes(changed, closable.toSeq)
      Full(())
    } catch {
      case e:Exception => HistorizationLogger.error("Could not update the nodes. Reason : "+e.getMessage())
                          Failure("Could not update the nodes. Reason : "+e.getMessage())
    }
  }

  override def updateGroups(groupLib: FullNodeGroupCategory) : Box[Unit] = {
    // Fetch all groups from the ldap
    val nodeGroups = groupLib.allGroups.values

    try {
      // fetch all the current group in the database
      val registered = historizationRepository.getAllOpenedGroups().map(x => x._1.groupId -> x).toMap

      // detect changes
      val changed = nodeGroups.filter(x => registered.get(x.nodeGroup.id.value) match {
        case None => true
        case Some((entry, nodes)) =>
          (entry.groupName != x.nodeGroup.name ||
           entry.groupDescription != x.nodeGroup.description ||
           nodes.map(x => NodeId(x.nodes)).toSet != x.nodeGroup.serverList ||
           SerializedGroups.fromSQLtoDynamic(entry.groupStatus) != Some(x.nodeGroup.isDynamic))
      }).toSeq.map( _.nodeGroup )

      // a group closable is a group that is current in the database, but don't exist in the
      // ldap
      val closable = registered.keySet.filter(x => !(nodeGroups.map( _.nodeGroup.id.value)).toSet.contains(x))

      historizationRepository.updateGroups(changed, closable.toSeq)
      Full(())
    } catch {
      case e:Exception => HistorizationLogger.error("Could not update the groups. Reason : "+e.getMessage())
                          Failure("Could not update the groups. Reason : "+e.getMessage())
    }
  }

  override def updateDirectiveNames(directiveLib: FullActiveTechniqueCategory) : Box[Unit] = {
    // we only want to keep directives with a matching technique.
    // just filter out (with an error message) when we don't have the technique
    val directives = directiveLib.allDirectives.flatMap { case (did, (fullActiveTechnique, directive)) =>
      fullActiveTechnique.techniques.get(directive.techniqueVersion) match {
        case None =>
          HistorizationLogger.error(s"Could not find version ${directive.techniqueVersion} for Technique with name ${fullActiveTechnique.techniqueName} for Directive ${directive.id.value}")
          None
        case Some(t) => Some((did, (t, fullActiveTechnique, directive)))
      }
    }

    try {
      val registered = historizationRepository.getAllOpenedDirectives().map(x => x.directiveId -> x).toMap

      val changed = directives.values.filter { case (technique, fullActiveTechnique, directive) =>
        registered.get(directive.id.value) match {
          case None => true
          case Some(entry) => (
              entry.directiveName != directive.name
           || entry.directiveDescription != directive.shortDescription
           || entry.priority != directive.priority
           || entry.techniqueHumanName != technique.name
           || entry.techniqueName != fullActiveTechnique.techniqueName.value
           || entry.techniqueDescription != technique.description
           || entry.techniqueVersion != directive.techniqueVersion.toString
          )
         }
      }.toSeq.map { case (t,fat,d) => (d, fat.toActiveTechnique, t) }

      val stringDirectiveIds = directives.keySet.map( _.value)

      val closable = registered.keySet.filter(x => !stringDirectiveIds.contains(x))

      historizationRepository.updateDirectives(changed, closable.toSeq)
      Full(())
    } catch {
      case e:Exception => HistorizationLogger.error("Could not update the directives. Reason : "+e.getMessage())
                          Failure("Could not update the directives. Reason : "+e.getMessage())
    }
  }

  override def updatesRuleNames(rules:Seq[Rule]) : Box[Unit] = {
    try {
      val registered = historizationRepository.getAllOpenedRules().map(x => x.id -> x).toMap


      val changed = rules.filter(rule => registered.get(rule.id) match {
          case None => true
          case Some(entry) =>
            !isEqual(entry, rule)
      })

      // a closable rule is a rule that is in the database, but not in the ldap
      val closable = registered.keySet.filter(x => !(rules.map(rule => rule.id)).contains(x)).
                    map(x => x.value)

      historizationRepository.updateRules(changed, closable.toSeq)
      Full(())
    } catch {
      case e:Exception => HistorizationLogger.error("Could not update the rules. Reason : "+e.getMessage())
                          Failure("Could not update the rules. Reason : "+e.getMessage())
    }
  }

  private def isEqual(entry : Rule, rule : Rule) : Boolean = (
       entry.name == rule.name
    && entry.shortDescription == rule.shortDescription
    && entry.longDescription == rule.longDescription
    && entry.isEnabledStatus == rule.isEnabledStatus
    && entry.targets == rule.targets
    && entry.directiveIds == rule.directiveIds
 )

}
