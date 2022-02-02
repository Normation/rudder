/*
*************************************************************************************
* Copyright 2014 Normation SAS
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

package com.normation.rudder.web.services

import com.normation.cfclerk.domain.ReportingLogic
import com.normation.rudder.domain.policies._
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeInfo
import net.liftweb.http._
import net.liftweb.common._
import com.normation.rudder.domain.reports._
import net.liftweb.util.Helpers._
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsExp
import com.normation.cfclerk.domain.TechniqueVersion
import org.joda.time.Interval
import com.normation.cfclerk.xmlparsers.CfclerkXmlConstants.DEFAULT_COMPONENT_KEY
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.utils.DateFormaterService
import net.liftweb.json.JsonAST.JValue


/*
 * That files contains all the datastructures related to
 * compliance of different level of rules/nodes, and
 * that will be mapped to JSON
 *
 */




/*
 *   Javascript object containing all data to create a line about a change in the DataTable
 *   { "nodeName" : Name of the node [String]
 *   , "message" : Messages linked to that change [String]
 *   , "directiveName" : Name of the directive [String]
 *   , "component" : Component name [String]
 *   , "value": Value of the change [String]
 *   , "executionDate" : date the report was run on the Node [String]
 *   }
 */
final case class ChangeLine (
    report        : ResultReports
  , nodeName      : Option[String] = None
  , ruleName      : Option[String] = None
  , directiveName : Option[String] = None
) extends JsTableLine {
  val json = {
    JsObj (
        ( "nodeName"      -> JsExp.strToJsExp(nodeName.getOrElse(report.nodeId.value)) )
      , ( "message"       -> escapeHTML(report.message) )
      , ( "directiveName" -> escapeHTML(directiveName.getOrElse(report.directiveId.serialize)) )
      , ( "component"     -> escapeHTML(report.component) )
      , ( "value"         -> escapeHTML(report.keyValue) )
      , ( "executionDate" -> DateFormaterService.getDisplayDate(report.executionTimestamp ))
    )
  }
}

object ChangeLine {
  def jsonByInterval (
      changes : Map[Interval,Seq[ResultReports]]
    , ruleName : Option[String] = None
    , directiveLib : FullActiveTechniqueCategory
    , allNodeInfos : Map[NodeId, NodeInfo]
  ) = {

    val jsonChanges =
      for {
        // Sort changes by interval so we can use index to select changes
        (interval,changesOnInterval) <- changes.toList.sortWith{case ((i1,_),(i2,_)) => i1.getStart() isBefore i2.getStart() }

      } yield {
        val lines = for {
          change <- changesOnInterval
          nodeName = allNodeInfos.get(change.nodeId).map (_.hostname)
          directiveName = directiveLib.allDirectives.get(change.directiveId).map(_._2.name)
        } yield {
          ChangeLine(change, nodeName , ruleName, directiveName)
        }

       JsArray(lines.toList.map(_.json))
      }

    JsArray(jsonChanges.toSeq:_*)
  }
}

/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "rule" : Rule name [String]
 *   , "id" : Rule id [String]
 *   , "compliance" : array of number of reports by compliance status [Array[Float]]
 *   , "details" : Details of Directives contained in the Rule [Array of Directive values]
 *   , "jsid"    : unique identifier for the line [String]
 *   , "isSystem" : Is it a system Rule? [Boolean]
 *   , "policyMode" : Directive policy mode [String]
 *   , "explanation" : Policy mode explanation [String]
 *   }
 */
final case class RuleComplianceLine (
    rule             : Rule
  , id               : RuleId
  , compliance       : ComplianceLevel
  , details          : JsTableData[DirectiveComplianceLine]
  , policyMode       : String
  , modeExplanation  : String
  , tags             : JValue
) extends JsTableLine {
  val json = {
    JsObj (
        ( "rule"              -> escapeHTML(rule.name)       )
      , ( "compliance"        -> jsCompliance(compliance)    )
      , ( "compliancePercent" -> compliance.computePercent().compliance)
      , ( "id"                -> escapeHTML(rule.id.serialize) )
      , ( "details"           -> details.json                )
      //unique id, usable as DOM id - rules, directives, etc can
      //appear several time in a page
      , ( "jsid"              -> nextFuncName                )
      , ( "isSystem"          -> rule.isSystem               )
      , ( "policyMode"        -> policyMode                  )
      , ( "explanation"       -> modeExplanation             )
      , ( "tags"              -> tags                        )
    )
  }
}

/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "directive" : Directive name [String]
 *   , "id" : Directive id [String]
 *   , "techniqueName": Name of the technique the Directive is based upon [String]
 *   , "techniqueVersion" : Version of the technique the Directive is based upon  [String]
 *   , "compliance" : array of number of reports by compliance status [Array[Float]]
 *   , "compliancePercent" : Compliance percentage [Float]
 *   , "details" : Details of components contained in the Directive [Array of Component values]
 *   , "jsid"    : unique identifier for the line [String]
 *   , "isSystem" : Is it a system Directive? [Boolean]
 *   , "policyMode" : Directive policy mode [String]
 *   , "explanation" : Policy mode explanation [String]
 *   }
 */
final case class DirectiveComplianceLine (
    directive        : Directive
  , techniqueName    : String
  , techniqueVersion : TechniqueVersion
  , compliance       : ComplianceLevel
  , details          : JsTableData[ComponentComplianceLine]
  , policyMode       : String
  , modeExplanation  : String
  , tags             : JValue
) extends JsTableLine {
  val json =  {
    JsObj (
        ( "directive"        -> escapeHTML(directive.name)            )
      , ( "id"               -> escapeHTML(directive.id.uid.value)                    )
      , ( "techniqueName"    -> escapeHTML(techniqueName)             )
      , ( "techniqueVersion" -> escapeHTML(techniqueVersion.serialize))
      , ( "compliance"       -> jsCompliance(compliance)              )
      , ( "compliancePercent"-> compliance.computePercent().compliance)
      , ( "details"          -> details.json                          )
      //unique id, usable as DOM id - rules, directives, etc can
      //appear several time in a page
      , ( "jsid"             -> nextFuncName                          )
      , ( "isSystem"         -> directive.isSystem                    )
      , ( "policyMode"       -> policyMode                            )
      , ( "explanation"      -> modeExplanation                       )
      , ( "tags"             -> tags                                  )
    )
  }
}

/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "node" : Node name [String]
 *   , "id" : Node id [String]
 *   , "compliance" : array of number of reports by compliance status [Array[Float]]
 *   , "compliancePercent" : Compliance percentage [Float]
 *   , "details" : Details of Directive applied by the Node [Array of Directive values ]
 *   , "jsid"    : unique identifier for the line [String]
 *   }
 */
final case class NodeComplianceLine (
    nodeInfo   : NodeInfo
  , compliance : ComplianceLevel
  , details    : JsTableData[DirectiveComplianceLine]
  , policyMode       : String
  , modeExplanation  : String
) extends JsTableLine {
  val json = {
    JsObj (
        ( "node"              -> escapeHTML(nodeInfo.hostname) )
      , ( "compliance"        -> jsCompliance(compliance)      )
      , ( "compliancePercent" -> compliance.computePercent().compliance)
      , ( "id"                -> escapeHTML(nodeInfo.id.value) )
      , ( "details"           -> details.json                  )
      //unique id, usable as DOM id - rules, directives, etc can
      //appear several time in a page
      , ( "jsid"              -> nextFuncName                  )
      , ( "policyMode"        -> policyMode                    )
      , ( "explanation"       -> modeExplanation               )
    )
  }
}

/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "component" : component name [String]
 *   , "id" : id generated about that component [String]
 *   , "compliance" : array of number of reports by compliance status [Array[Float]]
 *   , "compliancePercent" : Compliance percentage [Float]
 *   , "details" : Details of values contained in the component [ Array of Component values ]
 *   , "noExpand" : The line should not be expanded if all values are "None" [Boolean]
 *   , "jsid"    : unique identifier for the line [String]
 *   }
 */

sealed trait ComponentComplianceLine extends JsTableLine {
  def component : String
  def compliance : ComplianceLevel
}


final case class BlockComplianceLine (
    component   : String
  , compliance  : ComplianceLevel
  , details     : JsTableData[ComponentComplianceLine]
  , reportingLogic: ReportingLogic
) extends ComponentComplianceLine {

  val json = {
    JsObj(
      ("component" -> escapeHTML(component))
      , ("compliance" -> jsCompliance(compliance))
      , ("compliancePercent" -> compliance.computePercent().compliance)
      , ("details" -> details.json)
      , ("jsid" -> nextFuncName)
      , ("composition" -> reportingLogic.toString)
    )
  }
}

final case class ValueComplianceLine (
    component   : String
  , compliance  : ComplianceLevel
  , details     : JsTableData[ComponentValueComplianceLine]
  , noExpand    : Boolean
) extends ComponentComplianceLine {

  val json = {
    JsObj (
        ( "component"         -> escapeHTML(component)    )
      , ( "compliance"        -> jsCompliance(compliance) )
      , ( "compliancePercent" -> compliance.computePercent().compliance)
      , ( "details"           -> details.json             )
      , ( "noExpand"          -> noExpand                 )
      , ( "jsid"              -> nextFuncName             )
    )
  }

}

/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "value" : value of the key [String]
 *   , "compliance" : array of number of reports by compliance status [Array[Float]]
 *   , "compliancePercent" : Compliance percentage [Float]
 *   , "status" : Worst status of the Directive [String]
 *   , "statusClass" : Class to use on status cell [String]
 *   , "messages" : (Status, Message) linked to that value, only used in message popup [ Array[(status:String,value:String)] ]
 *   , "jsid"    : unique identifier for the line [String]
 *   }
 */
final case class ComponentValueComplianceLine (
    value       : String
  , messages    : List[(String, String)]
  , compliance  : ComplianceLevel
  , status      : String
  , statusClass : String
) extends JsTableLine {

  val json = {
    JsObj (
        ( "value"             -> escapeHTML(value) )
      , ( "status"            -> status )
      , ( "statusClass"       -> statusClass )
      , ( "messages"          -> JsArray(messages.map{ case(s, m) => JsObj(("status" -> s), ("value" -> escapeHTML(m)))}))
      , ( "compliance"        -> jsCompliance(compliance))
      , ( "compliancePercent" -> compliance.computePercent().compliance)
      //unique id, usable as DOM id - rules, directives, etc can
      //appear several time in a page
      , ( "jsid"              -> nextFuncName )
    )
  }

}

object ComplianceData extends Loggable {

  /*
   * For a given rule, display compliance by nodes.
   * For each node, elements displayed are restraint
   */
  def getRuleByNodeComplianceDetails (
      directiveLib: FullActiveTechniqueCategory
    , ruleId      : RuleId
    , nodeReports : Map[NodeId, NodeStatusReport]
    , allNodeInfos: Map[NodeId, NodeInfo]
    , globalMode  : GlobalPolicyMode
    , allRules    : Seq[Rule]
  ) : JsTableData[NodeComplianceLine]= {

    // Compute node compliance detail
    val nodeComplianceLines = nodeReports.flatMap { case (nodeId, reports) =>
      for {
        aggregate         <- reports.byRules.get(ruleId)
        nodeInfo          <- allNodeInfos.get(nodeId)
      } yield {

      val overrides = getOverridenDirectiveDetails(reports.overrides, directiveLib, allRules, None)
      val directivesMode = aggregate.directives.keys.map(x => directiveLib.allDirectives.get(x).flatMap(_._2.policyMode)).toSet
      val (policyMode,explanation) = ComputePolicyMode.nodeModeOnRule(nodeInfo.policyMode, globalMode)(directivesMode)

        val details = getDirectivesComplianceDetails(aggregate.directives.values.toSet, directiveLib, globalMode, ComputePolicyMode.directiveModeOnNode(nodeInfo.policyMode, globalMode))
        NodeComplianceLine(
            nodeInfo
          , aggregate.compliance
          , JsTableData(details ++ overrides)
          , policyMode
          , explanation
        )
      }
    }

    JsTableData(nodeComplianceLines.toList)
  }

  /*
   * For a given unique node, create the "by rule"
   * tree structure of compliance elements.
   * (rule -> directives -> components -> value with messages and status)
   * addOverriden decides if we add overriden policies in the result (policy tab) or not (policy tab)
   */
  def getNodeByRuleComplianceDetails (
      nodeId      : NodeId
    , report      : NodeStatusReport
    , allNodeInfos: Map[NodeId, NodeInfo]
    , directiveLib: FullActiveTechniqueCategory
    , rules       : Seq[Rule]
    , globalMode  : GlobalPolicyMode
    , addOverriden: Boolean
  ) : JsTableData[RuleComplianceLine] = {

    //add overriden directive in the list under there rule
    val overridesByRules = if (addOverriden) {
      report.overrides.groupBy( _.policy.ruleId )
    } else {
      Map[RuleId, List[OverridenPolicy]]()
    }

    //we can have rules with only overriden reports, so we just prepend them. When
    //a rule is defined for that id, it will override that default.
    val overridesRules = overridesByRules.view.mapValues(_ => AggregatedStatusReport(Nil)).toMap

    val ruleComplianceLine = for {
      (ruleId, aggregate) <- (overridesRules ++ report.byRules)
      rule                <- rules.find( _.id == ruleId )
    } yield {
      val nodeMode = allNodeInfos.get(nodeId).flatMap { _.policyMode }
      val details = getOverridenDirectiveDetails(overridesByRules.getOrElse(ruleId, Nil), directiveLib, rules, None) ++
                    getDirectivesComplianceDetails(aggregate.directives.values.toSet, directiveLib, globalMode, ComputePolicyMode.directiveModeOnNode(nodeMode, globalMode))

      val directivesMode = aggregate.directives.keys.map(x => directiveLib.allDirectives.get(x).flatMap(_._2.policyMode)).toSet
      val (policyMode,explanation) = ComputePolicyMode.ruleModeOnNode(nodeMode, globalMode)(directivesMode)
      RuleComplianceLine (
          rule
        , rule.id
        , aggregate.compliance
        , JsTableData(details)
        , policyMode
        , explanation
        , JsonTagSerialisation.serializeTags(rule.tags)
      )
    }
    JsTableData(ruleComplianceLine.toList)
  }

  def getOverridenDirectiveDetails(
      overrides   : List[OverridenPolicy]
    , directiveLib: FullActiveTechniqueCategory
    , rules       : Seq[Rule]
    , onRuleScreen: Option[Rule] // if we are on a rule, we want to adapt message
  ) : List[DirectiveComplianceLine] = {
    val overridesData = for {
      // we don't want to write an overriden directive several time for the same overriding rule/directive.
      over                            <- overrides
      (overridenTech , overridenDir)  <- directiveLib.allDirectives.get(over.policy.directiveId)
      overridingRule                  <- rules.find( _.id == over.overridenBy.ruleId)
      (overridingTech, overridingDir) <- directiveLib.allDirectives.get(over.overridenBy.directiveId)
    } yield {
      val overridenTechName    = overridenTech.techniques.get(overridenDir.techniqueVersion).map(_.name).getOrElse("Unknown technique")
      val overridenTechVersion = overridenDir.techniqueVersion

      val policyMode = "overriden"
      val explanation= "This directive is unique: only one directive derived from its technique can be set on a given node "+
                       s"at the same time. This one is overriden by directive '<i><b>${overridingDir.name}</b></i>'" +
                       (onRuleScreen match {
                         case None                                  =>
                           s" in rule '<i><b>${overridingRule.name}</b></i>' on that node."
                         case Some(r) if(r.id == overridingRule.id) =>
                           s" in that rule."
                         case Some(r)                               => // it means that that directive is skipped on all nodes on that rule
                           s" in rule '<i><b>${overridingRule.name}</b></i>' on all nodes."
                       })

      DirectiveComplianceLine (
          overridenDir
        , overridenTechName
        , overridenTechVersion
        , ComplianceLevel()
        , JsTableData(Nil)
        , policyMode
        , explanation
        , JsonTagSerialisation.serializeTags(overridenDir.tags)
      )
    }

    overridesData.toList
  }

  //////////////// Directive Report ///////////////

  // From Rule Point of view
  def getRuleByDirectivesComplianceDetails (
      report          : RuleStatusReport
    , rule            : Rule
    , allNodeInfos    : Map[NodeId, NodeInfo]
    , directiveLib    : FullActiveTechniqueCategory
    , groupLib        : FullNodeGroupCategory
    , allRules        : Seq[Rule] // for overrides
    , globalMode      : GlobalPolicyMode
  ) : JsTableData[DirectiveComplianceLine] = {
    val overrides = getOverridenDirectiveDetails(report.overrides, directiveLib, allRules, Some(rule))
    // restrict mode calcul to node really targetted by that rule
    val appliedNodes = groupLib.getNodeIds(rule.targets, allNodeInfos)
    val nodeModes = appliedNodes.flatMap(id => allNodeInfos.get(id).map(_.policyMode))
    val lines = getDirectivesComplianceDetails(report.report.directives.values.toSet, directiveLib, globalMode, ComputePolicyMode.directiveModeOnRule(nodeModes, globalMode))

    JsTableData(overrides ++ lines)
  }

  // From Node Point of view
  private[this] def getDirectivesComplianceDetails (
      directivesReport : Set[DirectiveStatusReport]
    , directiveLib     : FullActiveTechniqueCategory
    , globalPolicyMode : GlobalPolicyMode
    , computeMode      : Option[PolicyMode]=> (String,String)
  ) : List[DirectiveComplianceLine] = {
    val directivesComplianceData = for {
      directiveStatus                  <- directivesReport
      (fullActiveTechnique, directive) <- directiveLib.allDirectives.get(directiveStatus.directiveId)
    } yield {
      val techniqueName    = fullActiveTechnique.techniques.get(directive.techniqueVersion).map(_.name).getOrElse("Unknown technique")
      val techniqueVersion = directive.techniqueVersion
      val components       =  getComponentsComplianceDetails(directiveStatus.components.toSet, true)
      val (policyMode,explanation) = computeMode(directive.policyMode)
      val directiveTags    = JsonTagSerialisation.serializeTags(directive.tags)
      DirectiveComplianceLine (
          directive
        , techniqueName
        , techniqueVersion
        , directiveStatus.compliance
        , components
        , policyMode
        , explanation
        , directiveTags
      )
    }

    directivesComplianceData.toList
  }
  //////////////// Component Report ///////////////

  // From Node Point of view
  private[this] def getComponentsComplianceDetails (
      components    : Set[ComponentStatusReport]
    , includeMessage: Boolean
  ) : JsTableData[ComponentComplianceLine] = {
    val componentsComplianceData = components.map {
      case component : BlockStatusReport =>
        BlockComplianceLine(
          component.componentName
          , component.compliance
          , getComponentsComplianceDetails(component.subComponents.toSet, includeMessage)
          , component.reportingLogic
        )
      case component : ValueStatusReport =>

      val (noExpand, values) = if(!includeMessage) {
        (true, getValuesComplianceDetails(component.componentValues.toSet))
      } else {
        val noExpand  = component.componentValues.forall( x => x.componentValue == DEFAULT_COMPONENT_KEY)

        (noExpand, getValuesComplianceDetails(component.componentValues.toSet))
      }

      ValueComplianceLine(
          component.componentName
        , component.compliance
        , values
        , noExpand
      )
    }

    JsTableData(componentsComplianceData.toList)
  }

  //////////////// Value Report ///////////////

  // From Node Point of view
  private[this] def getValuesComplianceDetails (
      values  : Set[ComponentValueStatusReport]
  ) : JsTableData[ComponentValueComplianceLine] = {
    val valuesComplianceData = for {
      value <- values
    } yield {
      val severity = ReportType.getWorseType(value.messages.map( _.reportType)).severity
      val status = getDisplayStatusFromSeverity(severity)
      val key = value.unexpandedComponentValue
      val messages = value.messages.map(x => (x.reportType.severity, x.message.getOrElse("")))

      ComponentValueComplianceLine(
          key
        , messages
        , value.compliance
        , status
        , severity
      )
    }
    JsTableData(valuesComplianceData.toList)
  }

   private[this] def getDisplayStatusFromSeverity(severity: String) : String = {
    S.?(s"reports.severity.${severity}")
  }

}
