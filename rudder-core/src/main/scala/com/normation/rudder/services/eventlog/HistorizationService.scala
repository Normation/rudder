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

import net.liftweb.common._
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.RuleVal
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.repository.HistorizationRepository
import com.normation.rudder.repository.NodeGroupRepository
import com.normation.rudder.repository.DirectiveRepository
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.cfclerk.domain.TechniqueId
import com.normation.rudder.repository.RuleRepository
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.repository.jdbc.SerializedGroups
import com.normation.rudder.domain.logger.HistorizationLogger
import com.normation.inventory.domain.NodeId

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
  def updateNodes() : Box[Unit]


  /**
   * Update the groups, based on what is on the ldap, and return nothing (I don't know yet how to know what has been updated)
   */
  def updateGroups() : Box[Unit]

  /**
   * Update the policy details, based on what is on the ldap and what is on the file system.
   * A directive changed when it is deleted, renamed, changed version, changed priority,
   * it's underlying PT changed name, description
   */
  def updatePINames() : Box[Unit]

  def updatesRuleNames() : Box[Unit]

}


class HistorizationServiceImpl(
    historizationRepository : HistorizationRepository,
    nodeInfoService : NodeInfoService,
    nodeGroupRepository : NodeGroupRepository,
    directiveRepository : DirectiveRepository,
    techniqueRepository : TechniqueRepository,
    ruleRepository : RuleRepository) extends HistorizationService {


  def updateNodes() : Box[Unit] = {
    try {
    // Fetch all nodeinfo from the ldap
      val ids = nodeInfoService.getAllUserNodeIds().openOr({HistorizationLogger.error("Could not fetch all node ids"); Seq()})

      val nodeInfos = nodeInfoService.find(ids).openOr(
          {HistorizationLogger.error("Could not fetch node details "); Seq()} )

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

  def updateGroups() : Box[Unit] = {
    try {
    // Fetch all groups from the ldap
      val nodeGroups = nodeGroupRepository.getAll().openOr(
          {HistorizationLogger.error("Could not fetch all groups"); Seq()} )

      // fetch all the current group in the database
      val registered = historizationRepository.getAllOpenedGroups().map(x => x._1.groupId -> x).toMap

      // detect changes
      val changed = nodeGroups.filter(x => registered.get(x.id.value) match {
        case None => true
        case Some((entry, nodes)) =>
          (entry.groupName != x.name ||
           entry.groupDescription != x.description ||
           nodes.map(x => NodeId(x.nodes)).toSet != x.serverList ||
           SerializedGroups.fromSQLtoDynamic(entry.groupStatus) != Some(x.isDynamic))
      })

      // a group closable is a group that is current in the database, but don't exist in the
      // ldap
      val closable = registered.keySet.filter(x => !(nodeGroups.map(group => group.id.value)).contains(x))

      historizationRepository.updateGroups(changed, closable.toSeq)
      Full(())
    } catch {
      case e:Exception => HistorizationLogger.error("Could not update the groups. Reason : "+e.getMessage())
                          Failure("Could not update the groups. Reason : "+e.getMessage())
    }
  }

  def updatePINames() : Box[Unit] = {
    try {
    // sorry for this piece of nightmare
    // I don't want it to fail on PT deleted
    // Fetch a triplet policyinstance, userPT, policypackage from the ldap/filesystem
    // starting by the directives, then search for the userPT (which can fails)
    // then look on the file system for the matching policypackagename/policyversion
    // againt, it should not fail (but report an error nonetheless)
      val directives = directiveRepository.getAll().openOr(Seq()).map(x =>
            (x, directiveRepository.getActiveTechnique(x.id)))
              .filter { case (directive, userPt) =>
                          userPt match {
                              case Full(userPT) => true
                              case _ => HistorizationLogger.error("Could not find matching Technique for Directive %s".format(directive.id.value))
                                    false
                          }
              }.
              map(x =>( x._1 -> x._2.openOrThrowException("That should not fail if a Technique is deleted"))).
              map { case (directive, userPT) =>
                (directive, userPT, techniqueRepository.get(new TechniqueId(userPT.techniqueName, directive.techniqueVersion)))
              }.filter { case (directive, userPt, policyPackage) =>
                          policyPackage match {
                              case Some(pp) => true
                              case _ => HistorizationLogger.error("Could not find matching Technique for Directive %s".format(directive.id.value))
                                    false
                          }
              }.map(x =>( x._1 , x._2 , x._3.get))



      val registered = historizationRepository.getAllOpenedDirectives().map(x => x.directiveId -> x).toMap


      val changed = directives.filter { case (directive, userPT, technique) =>
          registered.get(directive.id.value) match {
              case None => true
              case Some(entry) =>
                     (entry.directiveName != directive.name ||
                  entry.directiveDescription != directive.shortDescription ||
                  entry.priority != directive.priority ||
                  entry.techniqueHumanName != technique.name ||
                  entry.techniqueName != userPT.techniqueName.value ||
                  entry.techniqueDescription != technique.description ||
                  entry.techniqueVersion != directive.techniqueVersion.toString )

         }
     }

     val closable = registered.keySet.filter(x => !(directives.map(directive => directive._1.id.value)).contains(x))

     historizationRepository.updateDirectives(changed, closable.toSeq)
     Full(())
    } catch {
      case e:Exception => HistorizationLogger.error("Could not update the directives. Reason : "+e.getMessage())
                          Failure("Could not update the directives. Reason : "+e.getMessage())
    }
  }

  def updatesRuleNames() : Box[Unit] = {
    try {
      val rules = ruleRepository.getAll().openOr({
          HistorizationLogger.error("Could not fetch all Rules");
          Seq()})

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
