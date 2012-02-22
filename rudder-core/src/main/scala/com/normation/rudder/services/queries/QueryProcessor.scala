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

package com.normation.rudder.services.queries

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.queries.Query
import com.normation.ldap.sdk.LDAPEntry
import net.liftweb.common.Box
import scala.collection.Seq

trait QueryProcessor {
  
   /**
   * Process a query and (hopefully) return the list of entry that match it.
   * @param query - the query to process
   * @param select - attributes to fetch in the ldap entry. If empty, all attributes are fetched
   * @return
   */
  def process(query:Query) : Box[Seq[NodeInfo]]
 
}


trait QueryChecker {

  /**
   * Each server denoted by its id is tested against query to see if it
   * matches. Return the list of matching server ids. 
   * @param Query
   *    the query to test 
   * @param Seq[NodeId]
   *    list of server which have to be tested for query   
   * @return
   *   Empty or Failure in case of a error during the process 
   *   Full(seq) with seq being the list of nodeId which verify
   *   query.
   */
  def check(query:Query, nodeIds:Seq[NodeId]) : Box[Seq[NodeId]]
  
}