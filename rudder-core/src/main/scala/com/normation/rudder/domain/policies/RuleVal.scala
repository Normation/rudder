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

package com.normation.rudder.domain.policies

import com.normation.cfclerk.domain._
import com.normation.utils.Utils._
import org.joda.time.DateTime
import net.liftweb.common._
import Box._
import org.joda.time.{LocalDate,LocalTime,Duration,DateTime}
import com.normation.rudder.domain._
import com.normation.utils.HashcodeCaching

/*
 * Immutable bridge between cfclerk and rudder 
 */

/**
 * This class holds the necessary data for a Directive. It kind of replace the DirectiveBean
 */
case class DirectiveVal(
    techniqueId      : TechniqueId
  , activeTechniqueId: ActiveTechniqueId
  //TODO: why there is no technique version ? ANS : The technique Version is within the ActiveTechniqueId
  , directiveId      : DirectiveId
  , priority         : Int
  , trackerVariable  : TrackerVariable
  , variables        : Map[String, Variable]
) extends HashcodeCaching 

case class RuleVal(
  ruleId       : RuleId,
  targets      : Set[RuleTarget],  //list of target for that directive (server groups, server ids, etc)
  directiveVals: Seq[DirectiveVal],
  serial       : Int // the generation serial of the Rule
) extends HashcodeCaching {
  
  def toRuleWithCf3PolicyDraft : Seq[RuleWithCf3PolicyDraft] = 
    directiveVals.map ( pol => RuleWithCf3PolicyDraft(ruleId,
        new Cf3PolicyDraft(Cf3PolicyDraftId(ruleId.value + "@@" + pol.directiveId.value),
            pol.techniqueId, __variableMap = pol.variables, pol.trackerVariable,
            priority = pol.priority, serial = serial )))
}

/**
 * A composite class, to keep the link between the applied Directive and the Rule
 */
case class RuleWithCf3PolicyDraft (
    ruleId        : RuleId
  , cf3PolicyDraft: Cf3PolicyDraft
) extends HashcodeCaching 


