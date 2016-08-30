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

package com.normation.rudder.repository

import scala.concurrent.Future

import com.normation.cfclerk.domain.Technique
import com.normation.rudder.db.DB
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.Rule

/**
 * Repository to retrieve information about Nodes, Groups, directives, rules
 * An item is said to be opened when it doesn't have an endTime, meaning it
 * is the current version of the item (as reflected in the ldap)
 */
trait HistorizationRepository {

  /**
   * Return all the nodes that are still "opened"
   */
  def getAllOpenedNodes() : Future[Seq[DB.SerializedNodes]]

  /**
   * Update a list of nodes, and close (end) another list, based on their id
   * Updating is really only setting now as a endTime for nodes, and creating them after
   */
  def updateNodes(nodes : Seq[NodeInfo], closable : Seq[String]): Future[Unit]

  /**
   * Return all the groups that are still "opened"
   */
  def getAllOpenedGroups() : Future[Seq[(DB.SerializedGroups, Seq[DB.SerializedGroupsNodes])]]

  /**
   * Update a list of groups, and close (end) another list, based on their id
   * Updating is really setting a given endTime for the groups, and creating new ones, with all the nodes within
   */
  def updateGroups(nodes : Seq[NodeGroup], closable : Seq[String]): Future[Unit]

  /**
   * Return all the directives that are still "opened"
   */
  def getAllOpenedDirectives(): Future[Seq[DB.SerializedDirectives]]

  /**
   * Update a list of directives, and close (end) another list, based on their id
   * Updating is really only setting now as a endTime for the directives, and then
   * (re)create the directives
   */
  def updateDirectives(directives : Seq[(Directive, ActiveTechnique, Technique)], closable : Seq[String]): Future[Unit]

  /**
   * Return all the rules that are still "opened"
   */
  def getAllOpenedRules() : Future[Seq[Rule]]

  /**
   * close the rules based on their id, update the rule to update (updating is closing and creating)
   */
  def updateRules(rules : Seq[Rule], closable : Seq[String]) : Future[Unit]

  /**
   * Get the current GlobalSchedule historized (the one considered in use)
   */
  def getOpenedGlobalSchedule() : Future[Option[DB.SerializedGlobalSchedule]]

  /**
   * Update the current schedule historized by closing the previous and writing a new one
   */
  def updateGlobalSchedule(
        interval    : Int
      , splaytime   : Int
      , start_hour  : Int
      , start_minute: Int
  ): Future[Unit]
}

