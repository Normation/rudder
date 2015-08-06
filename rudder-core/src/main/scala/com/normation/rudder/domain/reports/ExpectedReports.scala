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

package com.normation.rudder.domain.reports

import org.joda.time.DateTime
import org.joda.time.Interval
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.reports.execution.AgentRunId
import com.normation.utils.HashcodeCaching
import com.normation.inventory.domain.NodeId


/**
 * The main class helping maps expected
 * reports for a node
 */
case class RuleNodeExpectedReports(
    ruleId    : RuleId
  , serial    : Int // the serial of the rule
  , directives: Seq[DirectiveExpectedReports]
  // the period where the configuration is applied to the servers
  , beginDate : DateTime = DateTime.now()
  , endDate   : Option[DateTime] = None
) extends HashcodeCaching {
  val interval = new Interval(beginDate, endDate.getOrElse(DateTime.now))
}

/**
 * The representation of the database object for the expected reports for
 * a rule and a list of nodes.
 *
 * Its use is discouraged in favor of RuleNodeExpectedReports
 *
 */
case class RuleExpectedReports(
    ruleId                  : RuleId
  , serial                  : Int // the serial of the rule
  , directivesOnNodes       : Seq[DirectivesOnNodes]
  // the period where the configuration is applied to the servers
  , beginDate               : DateTime = DateTime.now()
  , endDate                 : Option[DateTime] = None
) extends HashcodeCaching {
  val interval = new Interval(beginDate, endDate.getOrElse(DateTime.now))
}

/**
 * This class allow to have different directives for different nodes.
 * Actually, it is used in a constrainted way : same directiveId for all nodes,
 * but directives can have different components/componentValues per Nodes
 */
case class DirectivesOnNodes(
    nodeJoinKey             : Int// id following a sequence used to join to the list of nodes
  , nodeConfigurationIds    : Map[NodeId, Option[NodeConfigId]]
  , directiveExpectedReports: Seq[DirectiveExpectedReports]
) extends HashcodeCaching



/**
 * A Directive may have several components
 */
final case class DirectiveExpectedReports (
    directiveId: DirectiveId
  , components : Seq[ComponentExpectedReport]
) extends HashcodeCaching

final case class NodeAndConfigId(
    nodeId : NodeId
  , version: NodeConfigId
)

/**
 * The Cardinality is per Component
 */
case class ComponentExpectedReport(
    componentName             : String
  , cardinality               : Int

  //TODO: change that to have a Seq[(String, String).
  //or even better, un Seq[ExpectedValue] where expectedValue is the pair
  , componentsValues          : Seq[String]
  , unexpandedComponentsValues: Seq[String]
) extends HashcodeCaching {
  /**
   * Get a normalized list of pair of (value, unexpandedvalue).
   * We have three case to consider:
   * - both source list have the same size => easy, just zip them
   * - the unexpandedvalues is empty: it may happen due to old version of
   *   rudder not having recorded them => too bad, use the expanded value
   *   in both case
   * - different size: why on hell do we have a data model authorizing that
   *   and the TODO is not addressed ?
   *   In that case, there is no good solution. We choose to:
   *   - remove unexpanded values if it's the longer list
   *   - complete unexpanded values with matching values in the other case.
   */
  def groupedComponentValues : Seq[(String, String)] = {
    if (componentsValues.size <= unexpandedComponentsValues.size) {
      componentsValues.zip(unexpandedComponentsValues)
    } else { // strictly more values than unexpanded
      val n = unexpandedComponentsValues.size
      val unmatchedValues = componentsValues.drop(n)
      componentsValues.take(n).zip(unexpandedComponentsValues) ++ unmatchedValues.zip(unmatchedValues)
    }
  }
}

final case class NodeConfigId(value: String)

final case class NodeConfigVersions(
     nodeId  : NodeId
     //the most recent version is the head
     //and the list can be empty
   , versions: List[NodeConfigId]
 )
