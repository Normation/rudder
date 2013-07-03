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

package com.normation.rudder.services.servers

import com.normation.rudder.domain.servers.{ModifiedPolicy,ModifiedVariable,DeletedPolicy,AddedPolicy,NodeConfigurationDiff,NodeConfiguration}

class NodeConfigurationDiffService {


  def getDiff(server:NodeConfiguration) : Seq[NodeConfigurationDiff] = {
    val buffer = scala.collection.mutable.Buffer[NodeConfigurationDiff]()
    val cps = server.currentRulePolicyDrafts.map( _.draftId).toSet
    val tps = server.targetRulePolicyDrafts.map( _.draftId).toSet
    //added
    buffer ++= (tps -- cps).map(AddedPolicy(_))
    //deleted
    buffer ++= (cps -- tps) map(DeletedPolicy(_))
    //check for modified
    for( cpId <- (cps & tps) ) {
      val modVars = scala.collection.mutable.Buffer[ModifiedVariable]()
      val errorMessage = s"Try to find a policy draft with id ${cpId.value} in current draft for node configuration ${server.id}"
      val oldInstanceVars = server.currentRulePolicyDrafts.find( _.draftId == cpId).getOrElse(throw new IllegalArgumentException(errorMessage)).cf3PolicyDraft.getVariables
      val newInstanceVars = server.targetRulePolicyDrafts. find( _.draftId == cpId).getOrElse(throw new IllegalArgumentException(errorMessage)).cf3PolicyDraft.getVariables
      for {
        key <- oldInstanceVars.keySet ++ newInstanceVars.keySet
        oldVar <- oldInstanceVars.get(key)
        newVar <- newInstanceVars.get(key)
        // manage addedd/deleted vars
        if(oldVar.values.toList != newVar.values.toList)
      } {
        modVars += ModifiedVariable(oldVar,newVar.values)
      }
      if(modVars.nonEmpty) buffer += ModifiedPolicy(cpId,modVars.toList)
    }

    buffer.toSeq
  }
}
