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

import com.normation.cfclerk.domain.Cf3PolicyDraft
import com.normation.cfclerk.domain.Cf3PolicyDraftId
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TrackerVariable
import com.normation.cfclerk.domain.Variable
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.parameters.ParameterName
import com.normation.utils.HashcodeCaching
import net.liftweb.common.Box
import scala.collection.immutable.TreeMap
import com.normation.inventory.domain.NodeInventory
import com.normation.rudder.domain.reports.NodeAndConfigId
import com.normation.cfclerk.domain.BundleOrder

/*
 * Immutable bridge between cfclerk and rudder
 */


/**
 * A class that hold all information that can be used to resolve
 * interpolated variables in directives variables.
 * It is by nature node dependent.
 */
case class InterpolationContext(
        nodeInfo        : NodeInfo
      , policyServerInfo: NodeInfo
      , inventory       : NodeInventory
        //environment variable for that server
        //must be a case insensitive Map !!!!
      , nodeContext     : TreeMap[String, Variable]
        // parameters for this node
        //must be a case SENSITIVE Map !!!!
      , parameters      : Map[ParameterName, InterpolationContext => Box[String]]
        //the depth of the interpolation context evaluation
        //used as a lazy, trivial, mostly broken way to detect cycle in interpretation
        //for ex: param a => param b => param c => ..... => param a
        //should not be evaluated
      , depth           : Int
)

object InterpolationContext {
  implicit val caseInsensitiveString = new Ordering[String] {
    def compare(x: String, y: String): Int = x.compareToIgnoreCase(y)
  }

  def apply(
        nodeInfo        : NodeInfo
      , policyServerInfo: NodeInfo
      , inventory       : NodeInventory
        //environment variable for that server
        //must be a case insensitive Map !!!!
      , nodeContext     : Map[String, Variable]
        // parameters for this node
        //must be a case insensitive Map !!!!
      , parameters      : Map[ParameterName, InterpolationContext => Box[String]]
        //the depth of the interpolation context evaluation
        //used as a lazy, trivial, mostly broken way to detect cycle in interpretation
        //for ex: param a => param b => param c => ..... => param a
        //should not be evaluated
      , depth           : Int = 0
  ) = new InterpolationContext(nodeInfo, policyServerInfo, inventory, TreeMap(nodeContext.toSeq:_*), parameters, depth)
}

/**
 * This class hold information for a directive:
 * - after that Variable were parsed to look for Rudder parameters
 * - before these parameters are contextualize
 */
case class DirectiveVal(
    technique        : Technique
  , directiveId      : DirectiveId
  , priority         : Int
  , trackerVariable  : TrackerVariable
  , variables        : InterpolationContext => Box[Map[String, Variable]]
  , originalVariables: Map[String, Variable] // the original variable, unexpanded
  , directiveOrder   : BundleOrder
) extends HashcodeCaching {

  def toExpandedDirectiveVal(context: InterpolationContext) = {
    variables(context).map { vars =>

      ExpandedDirectiveVal(
          technique
        , directiveId
        , priority
        , trackerVariable
        , vars
        , originalVariables
      )
    }
  }
}

case class RuleVal(
  ruleId       : RuleId,
  targets      : Set[RuleTarget],  //list of target for that directive (server groups, server ids, etc)
  directiveVals: Seq[DirectiveVal],
  serial       : Int, // the generation serial of the Rule. Do we need it ?
  ruleOrder    : BundleOrder
) extends HashcodeCaching


/**
 * A composite class, to keep the link between the applied Directive and the Rule
 */
case class RuleWithCf3PolicyDraft private (
    ruleId        : RuleId
  , directiveId   : DirectiveId
  , cf3PolicyDraft: Cf3PolicyDraft
  , ruleOrder     : BundleOrder
  , directiveOrder: BundleOrder
  , overrides     : Set[(RuleId,DirectiveId)] //a set of other draft overriden by that one
) extends HashcodeCaching {
  val draftId = cf3PolicyDraft.id

  def toDirectiveVal(originalVariables: Map[String, Variable]) = ExpandedDirectiveVal(
    technique         = cf3PolicyDraft.technique
  , directiveId       = directiveId
  , priority          = cf3PolicyDraft.priority
  , trackerVariable   = cf3PolicyDraft.trackerVariable
  , variables         = cf3PolicyDraft.variableMap
  , originalVariables = originalVariables
  )
}

object RuleWithCf3PolicyDraft {
  def apply(
    ruleId         : RuleId
  , directiveId    : DirectiveId
  , technique      : Technique
  , variableMap    : Map[String, Variable]
  , trackerVariable: TrackerVariable
  , priority       : Int
  , serial         : Int
  , ruleOrder      : BundleOrder
  , directiveOrder : BundleOrder
  ): RuleWithCf3PolicyDraft = new RuleWithCf3PolicyDraft(
      ruleId
    , directiveId
    , Cf3PolicyDraft(
            Cf3PolicyDraftId(ruleId.value + "@@" + directiveId.value)
          , technique
          , variableMap
          , trackerVariable
          , priority = priority
          , serial = serial
          , order = List(ruleOrder, directiveOrder)
      )
    , ruleOrder
    , directiveOrder
    , Set()
  )
}


/**
 * Used for expected reports
 */
case class ExpandedDirectiveVal(
    technique        : Technique
  , directiveId      : DirectiveId
  , priority         : Int
  , trackerVariable  : TrackerVariable
  , variables        : Map[String, Variable]
  , originalVariables: Map[String, Variable] // the original variable, unexpanded
) extends HashcodeCaching

case class ExpandedRuleVal(
    ruleId       : RuleId
  , serial       : Int // the generation serial of the Rule
  , configs      : Map[NodeAndConfigId, Seq[ExpandedDirectiveVal]] // A map of NodeId->DirectiveId, where all vars are expanded
) extends HashcodeCaching



