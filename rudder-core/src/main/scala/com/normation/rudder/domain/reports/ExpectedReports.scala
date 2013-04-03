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

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import scala.collection._
import org.joda.time._
import org.joda.time.format._
import com.normation.utils.HashcodeCaching


/**
 * The representation of the database object for the expected reports for a rule
 *
 * @author Nicolas CHARLES
 *
 */
case class RuleExpectedReports(
    ruleId                  : RuleId
  , serial                  : Int // the serial of the rule
  , directivesOnNodes       : Seq[DirectivesOnNodes]
  // the period where the configuration is applied to the servers
  , beginDate               : DateTime = DateTime.now()
  , endDate                 : Option[DateTime] = None
) extends HashcodeCaching

/**
 * This class allow to have for differents nodes differents directives
 * Actually, it is used in a constrainted way : same directiveId for all nodes,
 * but directives can have differents component/componentValues per Nodes
 */
case class DirectivesOnNodes(
    nodeJoinKey             : Int// the version id of the rule, follows a sequence, used to join with the node table
  , nodeIds                 : Seq[NodeId]
  , directiveExpectedReports: Seq[DirectiveExpectedReports]
) extends HashcodeCaching

/**
 * The Cardinality is per Component
 */
case class ReportComponent(
    componentName             : String
  , cardinality               : Int
  , componentsValues          : Seq[String]
  , unexpandedComponentsValues: Seq[String]
) extends HashcodeCaching {
  // Utilitary method to returns componentValues along with unexpanded values
  // The unexpanded may be none, for older version of Rudder didn't have this
  def groupedComponentValues : Seq[(String, Option[String])] = {
    if (componentsValues.size != unexpandedComponentsValues.size) {
      componentsValues.map((_, None))
    } else {
      componentsValues.zip(unexpandedComponentsValues.map(Some(_)))
    }
  }
}

/**
 * A Directive may have several components
 */
case class DirectiveExpectedReports (
    directiveId: DirectiveId
  , components : Seq[ReportComponent]
) extends HashcodeCaching


/**
 * This utilitary class is used only to compare what is already saved in the
 * DB and compare it with what is to be saved
 */
case class Comparator(
    nodeId       : NodeId
  , directiveId  : DirectiveId
  , componentName: String
)
