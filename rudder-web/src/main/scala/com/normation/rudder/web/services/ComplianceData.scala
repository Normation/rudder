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
import com.normation.rudder.web.components.DateFormaterService
import org.joda.time.Interval
import com.normation.cfclerk.xmlparsers.CfclerkXmlConstants.DEFAULT_COMPONENT_KEY
import com.normation.rudder.domain.policies.PolicyMode._

object ComputePolicyMode {
  def ruleMode(globalMode : GlobalPolicyMode, directives : Set[Directive]) = {
   globalMode.overridable match {
      case PolicyModeOverrides.Unoverridable =>
        (globalMode.mode.name, s"""Rudder's global agent policy mode is set to <i><b>${globalMode.mode.name}</b></i> and is not overridable on a per-node or per-directive basis. Please check your Settings or contact your Rudder administrator.""")
      case PolicyModeOverrides.Always =>

        // We have a Set here so we only have 3 elem max (None, Some(audit), some(enforce))
        val directivePolicyMode = directives.map(_.policyMode)
        directivePolicyMode.toList match {
          // We only have one element!!
          case mode :: Nil => mode match {
            // global mode was not overriden by any directive, use global mode
            case None => (globalMode.mode.name, "")
            // Global mode is overriden by all directives with the same mode
            case Some(m) => (m.name, "")
          }
          // No directives linked to the Rule, fallback to globalMode
          case Nil => (globalMode.mode.name, "This mode is the globally defined default. You can change it in the global <i><b>settings</b></i>.")
          case modes =>
            // Here we now need to check if global mode is the same that all selected mode for directives
            // So we replace any "None" (use global mode) by global mode and we will decide after what happens
            val effectiveModes = directivePolicyMode.map(_.getOrElse(globalMode.mode))
            // Check if there is still more than one mode applied
            if (effectiveModes.size > 1)  {
              // More that one mode applied, status is 'mixed', global mode was different than the one enforced on some directives
              ("mixed", "This rule has at least one directive that will enforce configurations, and at least one that will audit them.")
            } else {
              // After all there is only one mode and it's the same than global mode => some modes were using default mode,
              // some were enforce the same mode as global
              (globalMode.mode.name, "")
            }
        }
    }
  }

  def directiveModeOnNode(nodeMode : Option[PolicyMode], globalMode : GlobalPolicyMode)(directiveMode : Option[PolicyMode]) = {
   globalMode.overridable match {
      case PolicyModeOverrides.Unoverridable =>
        (globalMode.mode.name, s"""Rudder's global agent policy mode is set to <i><b>${globalMode.mode.name}</b></i> and is not overridable on a per-node or per-directive basis. Please check your Settings or contact your Rudder administrator.""")
      case PolicyModeOverrides.Always =>
        val (mode,expl) = matchMode(nodeMode,directiveMode,globalMode.mode)
        (mode.name,expl)
    }
  }

  // Here we are resolving conflict when we have an overridable global mode between mode of a Node, mode of a Directive and we are using global mode to use as default when both are not overriding
  // we do not pass a GlobalPolicyMode, but only the PolicyMode from it so we indicate that we don't want to treat overridabilty here
  private[this] def matchMode(nodeMode : Option[PolicyMode], directiveMode : Option[PolicyMode], globalMode : PolicyMode) : (PolicyMode,String) = {
    (nodeMode,directiveMode) match {
      case (None,None) =>
        (globalMode, "This mode is the globally defined default. You can change it in the global <i><b>settings</b></i>.")
      case (Some(Enforce),Some(Enforce)) =>
        (Enforce, "<b>Enforce</b> is forced by both this <i><b>Node</b></i> and this <i><b>Directive</b></i> mode")
      case (Some(Enforce),None) =>
        (Enforce, "<b>Enforce</b> is forced by this <i><b>Node</b></i> mode")
      case (None,Some(Enforce)) =>
        (Enforce, "<b>Enforce</b> is forced by this <i><b>Directive</b></i> mode")
      case (Some(Audit), Some(Audit)) =>
        (Audit, "<b>Audit</b> is forced by both this <i><b>Node</b></i> and this <i><b>Directive</b></i> mode")
      case (Some(Audit), None) =>
        (Audit, "<b>Audit</b> is forced by this <i><b>Node</b></i> mode")
      case (Some(Audit), Some(Enforce)) =>
        (Audit, "The <i><b>Directive</b></i> is configured to <b class='text-Enforce'>enforce</b> but is overriden to <b>audit</b> by this <i><b>Node</b></i>. ")
      case (None,Some(Audit))  =>
        (Audit, "<b>Audit</b> is forced by this <i><b>Directive</b></i> mode")
      case (Some(Enforce),Some(Audit))  =>
        (Audit, "The <i><b>Node</b></i> is configured to <b class='text-Enforce'>enforce</b> but is overriden to <b>audit</b> by this <i><b>Directive</b></i>. ")
    }
  }

  // The goal of that function is to compute the effective mode applied by a combination of a element of one type and a set of element of another type
  // and explain why it's result. (ie : Compute the policy mode applied on a RUle depending on mode of One Node and all Directive from that Rule

  // It uses:
  // * The global defined mode
  // * A unique policy mode of one element of type A as base (generally a Directive or a Node)
  // * multiples Policy modes of a set elements of type B that we will look into it to understand what's going on
  // The other parameters (uniqueKind/multipleKind, mixedExplnation) are used to build explanation message
  private[this] def genericComputeMode (
      uniqueMode : Option[PolicyMode]
    , uniqueKind : String
    , multipleModes : Set[Option[PolicyMode]]
    , multipleKind  : String
    , globalMode : GlobalPolicyMode
    , mixedExplanation : String
  ) : (String,String) = {

    // Is global mode overridable ?
    globalMode.overridable match {
      // No => We have global mode
      case PolicyModeOverrides.Unoverridable =>
        (globalMode.mode.name, s"""Rudder's global agent policy mode is set to <i><b>${globalMode.mode.name}</b></i> and is not overridable on a per-node or per-directive basis. Please check your Settings or contact your Rudder administrator.""")
      // Yes, Look for our unique mode
      case PolicyModeOverrides.Always =>
        uniqueMode match {
          // Our unique Mode overrides Audit => We are in Audit mode
          case Some(Audit) =>
            (Audit.name,s"<b>${Audit.name}</b> mode is forced by this <i><b>${uniqueKind}</b></i>")

          // We are not overriding global mode or we are enforcing Audit
          // Scala type system / pattern matching does not allow here to state that we have an Option[Enforce] So we will still have to treat audit case ...
          case uniqueMode =>

            // If something is missing or multiple mode does not override global mode we will fallback to this
            // Use unique mode if defined
            // else use the global mode
            val (defaultMode,expl) = uniqueMode match {
              case Some(mode) =>
                ( mode, s"<b>${mode.name}</b> mode is forced by this <i><b>${uniqueKind}</b></i>")
              case None =>
                ( globalMode.mode, "This mode is the globally defined default. You can change it in the global <i><b>settings</b></i>.")
            }
            val default = (defaultMode.name,expl)

            // We have a Set here so we only have 3 elem max  in it (None, Some(audit), some(enforce))
            // transform to list for pattern matching
            multipleModes.toList match {
              // Nothing defined ... , fallback to default
              case Nil => default
              // We only have one element!! So all multiple elements are defined on the same mode
              case mode :: Nil => mode match {
                // Not overriding => Default
                case None => default
                // Audit mode, we will have audit mode but explanation differs depending on which unique mode we have
                case Some(Audit) =>
                  uniqueMode match {
                    case Some(Enforce) =>
                     (Audit.name, s"The <i><b>${uniqueKind}</b></i> is configured to <b class='text-Enforce'>enforce</b> but is overriden to <b>audit</b> by all <i><b>${multipleKind}</b></i>. ")
                    // Audit is treated above ... maybe we should not treat it in that sense and always provide a more precise message
                    case Some(Audit) =>
                     (Audit.name, s"<b>Audit</b> mode is forced by both the <i><b>${uniqueKind}</b></i> and all <i><b>${multipleKind}</b></i>")
                    case None =>
                     (Audit.name, s"<b>Audit</b> mode is forced by all <i><b>${multipleKind}</b></i>")
                  }
                // Enforce mode, since we already treated unique Audit, above, only enforce cases are possible
                case Some(Enforce) =>
                  uniqueMode match {
                    case Some(Audit) =>
                     // Audit is treated above ... maybe we should not treat it in that sense and always provide a more precise message
                     (Audit.name,   s"All <i><b>${multipleKind}</b></i> are configured to <b class='text-Enforce'>enforce</b> but is overriden to <b>audit</b> by the <i><b>${uniqueKind}</b></i>. ")
                    case Some(Enforce) =>
                     (Enforce.name, s"<b>Enforce</b> mode is forced by both this <i><b>${uniqueKind}</b></i> and all <i><b>${multipleKind}</b></i>")
                    case None =>
                     (Enforce.name, s"<b>Enforce</b> mode is forced by all <i><b>${multipleKind}</b></i>")
                  }

              }

              // We have multiple modes ! We need to go deeper
              case modes  =>
                // Now we will replace None (non overriding mode) by it's effective mode (default one defined above)

                multipleModes.map(_.getOrElse(defaultMode)).toList match {
                  // That is treated above on the first match on the list, but still need to treat empty case
                  case Nil => default
                  // All modes defined are now replaced by default mode but there is only mode mode left
                  case mode :: Nil =>
                    mode match {
                      // Audit means that unique mode was not overriding and we that some 'multiple' modes are overriding to Audit and global mode is defined as audit
                      case Audit   => (Audit.name, s"<b>${Audit.name}</b> mode is forced by some <i><b>${multipleKind}</b></i>")
                      // That means that unique mode was not overriding or is in enforce Mode and that some directive are overrding to default and global mode is defined as audit
                      // Maybe we could look into unique mode to state if it depends on it or not
                      case Enforce => (Enforce.name, s"<b>${Enforce.name}</b> mode is forced by the <i><b>${uniqueKind}</b></i> and some <i><b>${multipleKind}</b></i>")
                    }

                  // We still have more that one mode, we are in mixed state, use the explanation povided
                  case _ =>
                  ("mixed", mixedExplanation)
                }
            }
        }
    }
  }

  // Used to compute mode applied on a Rule depending on the Node state and directive from the Rule
  // Used in compliance table in node page
  def ruleModeOnNode(nodeMode : Option[PolicyMode], globalMode : GlobalPolicyMode)( directivesMode : Set[Option[PolicyMode]]) : (String,String) = {
    val mixed = "This Rule has at least one Directive that will <b class='text-Enforce'>enforces</b> configurations, and at least one that will <b class='text-Audit'>audits</b> them."
    genericComputeMode(nodeMode, "Node", directivesMode, "Directives", globalMode, mixed)
  }

  // Used to computed mode applied by a Node depending on it's mode and directive it applies
  // Used in Node compliance table in Rule page
  def nodeModeOnRule(nodeMode : Option[PolicyMode], globalMode : GlobalPolicyMode)( directivesMode : Set[Option[PolicyMode]]) : (String,String) = {
    val mixed = "This Node applies at least one Directive that will <b class='text-Enforce'>enforces</b> configurations, and at least one that will <b class='text-Audit'>audits</b> them."
    genericComputeMode(nodeMode, "Node", directivesMode, "Directives", globalMode, mixed)
  }

  // Used to computed mode applied on a Directive depending on it's mode and and all Nodes appliying it
  // Used in Node compliance table in Rule page
  def directiveModeOnRule(nodeModes : Set[Option[PolicyMode]], globalMode : GlobalPolicyMode)( directiveMode : Option[PolicyMode]) = {
    val mixed = "This Directive is applied on at least one Node that will <b class='text-Enforce'>enforces</b> configurations, and at least one that will <b class='text-Audit'>audits</b> them."
    genericComputeMode(directiveMode, "Node", nodeModes, "Directives", globalMode, mixed)
  }
}

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
case class ChangeLine (
    report        : ResultReports
  , nodeName      : Option[String] = None
  , ruleName      : Option[String] = None
  , directiveName : Option[String] = None
) extends JsTableLine {
  val json = {
    JsObj (
        ( "nodeName"      -> JsExp.strToJsExp(nodeName.getOrElse(report.nodeId.value)) )
      , ( "message"       -> escapeHTML(report.message) )
      , ( "directiveName" -> escapeHTML(directiveName.getOrElse(report.directiveId.value)) )
      , ( "component"     -> escapeHTML(report.component) )
      , ( "value"         -> escapeHTML(report.keyValue) )
      , ( "executionDate" -> DateFormaterService.getFormatedDate(report.executionTimestamp ))
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
case class RuleComplianceLine (
    rule             : Rule
  , id               : RuleId
  , compliance       : ComplianceLevel
  , details          : JsTableData[DirectiveComplianceLine]
  , policyMode       : String
  , modeExplanation  : String
) extends JsTableLine {
  val json = {
    JsObj (
        ( "rule"       -> escapeHTML(rule.name) )
      , ( "compliance" -> jsCompliance(compliance) )
      , ( "compliancePercent" -> compliance.compliance)
      , ( "id"         -> rule.id.value )
      , ( "details"    -> details.json )
      //unique id, usable as DOM id - rules, directives, etc can
      //appear several time in a page
      , ( "jsid"       -> nextFuncName )
      , ( "isSystem"   -> rule.isSystem )
      , ( "policyMode" -> policyMode )
      , ( "explanation"-> modeExplanation )
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
case class DirectiveComplianceLine (
    directive        : Directive
  , techniqueName    : String
  , techniqueVersion : TechniqueVersion
  , compliance       : ComplianceLevel
  , details          : JsTableData[ComponentComplianceLine]
  , policyMode       : String
  , modeExplanation  : String
) extends JsTableLine {
  val json =  {
    JsObj (
        ( "directive"        -> escapeHTML(directive.name) )
      , ( "id"               -> directive.id.value )
      , ( "techniqueName"    -> escapeHTML(techniqueName) )
      , ( "techniqueVersion" -> escapeHTML(techniqueVersion.toString) )
      , ( "compliance"       -> jsCompliance(compliance))
      , ( "compliancePercent"-> compliance.compliance)
      , ( "details"          -> details.json )
      //unique id, usable as DOM id - rules, directives, etc can
      //appear several time in a page
      , ( "jsid"             -> nextFuncName )
      , ( "isSystem"         -> directive.isSystem )
      , ( "policyMode"       -> policyMode )
      , ( "explanation"      -> modeExplanation )
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
case class NodeComplianceLine (
    nodeInfo   : NodeInfo
  , compliance : ComplianceLevel
  , details    : JsTableData[DirectiveComplianceLine]
  , policyMode       : String
  , modeExplanation  : String
) extends JsTableLine {
  val json = {
    JsObj (
        ( "node"       -> escapeHTML(nodeInfo.hostname) )
      , ( "compliance" -> jsCompliance(compliance))
      , ( "compliancePercent"       -> compliance.compliance)
      , ( "id"         -> nodeInfo.id.value )
      , ( "details"    -> details.json )
      //unique id, usable as DOM id - rules, directives, etc can
      //appear several time in a page
      , ( "jsid"       -> nextFuncName )
      , ( "policyMode" -> policyMode )
      , ( "explanation"-> modeExplanation )
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
case class ComponentComplianceLine (
    component   : String
  , compliance  : ComplianceLevel
  , details     : JsTableData[ValueComplianceLine]
  , noExpand    : Boolean
) extends JsTableLine {

  val json = {
    JsObj (
        ( "component"   -> escapeHTML(component) )
      , ( "compliance"  -> jsCompliance(compliance))
      , ( "compliancePercent"       -> compliance.compliance)
      , ( "details"     -> details.json )
      , ( "noExpand"    -> noExpand )
      , ( "jsid"        -> nextFuncName )
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
case class ValueComplianceLine (
    value       : String
  , messages    : List[(String, String)]
  , compliance  : ComplianceLevel
  , status      : String
  , statusClass : String
) extends JsTableLine {

  val json = {
    JsObj (
        ( "value"       -> escapeHTML(value) )
      , ( "status"      -> status )
      , ( "statusClass" -> statusClass )
      , ( "messages"    -> JsArray(messages.map{ case(s, m) => JsObj(("status" -> s), ("value" -> escapeHTML(m)))}))
      , ( "compliance"  -> jsCompliance(compliance))
      , ( "compliancePercent"       -> compliance.compliance)
      //unique id, usable as DOM id - rules, directives, etc can
      //appear several time in a page
      , ( "jsid"        -> nextFuncName )
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
    , report      : RuleStatusReport
    , allNodeInfos: Map[NodeId, NodeInfo]
    , globalMode  : GlobalPolicyMode
  ) : JsTableData[NodeComplianceLine]= {

    // Compute node compliance detail
    val nodeComplianceLine = for {
      (nodeId, aggregate) <- report.byNodes
      nodeInfo            <- allNodeInfos.get(nodeId)
    } yield {

      val directivesMode = aggregate.directives.keys.map(directiveLib.allDirectives.get(_).flatMap(_._2.policyMode)).toSet
      val (policyMode,explanation) = ComputePolicyMode.nodeModeOnRule(nodeInfo.policyMode, globalMode)(directivesMode)

      val details = getDirectivesComplianceDetails(aggregate.directives.values.toSet, directiveLib, globalMode, ComputePolicyMode.directiveModeOnNode(nodeInfo.policyMode, globalMode))
      NodeComplianceLine(
          nodeInfo
        , aggregate.compliance
        , JsTableData(details)
        , policyMode
        , explanation
      )
    }

    JsTableData(nodeComplianceLine.toList)
  }

  /*
   * For a given unique node, create the "by rule"
   * tree structure of compliance elements.
   * (rule -> directives -> components -> value with messages and status)
   */
  def getNodeByRuleComplianceDetails (
      nodeId      : NodeId
    , report      : NodeStatusReport
    , allNodeInfos: Map[NodeId, NodeInfo]
    , directiveLib: FullActiveTechniqueCategory
    , rules       : Seq[Rule]
    , globalMode  : GlobalPolicyMode
  ) : JsTableData[RuleComplianceLine] = {

    //add overriden directive in the list under there rule
    val overridesByRules = report.overrides.groupBy( _.policy.ruleId )

    //we can have rules with only overriden reports, so we just prepend them. When
    //a rule is defined for that id, it will override that default.
    val overridesRules = overridesByRules.mapValues(_ => AggregatedStatusReport(Nil))

    val ruleComplianceLine = for {
      (ruleId, aggregate) <- (overridesRules ++ report.byRules)
      rule                <- rules.find( _.id == ruleId )
    } yield {
      val nodeMode = allNodeInfos.get(nodeId).flatMap { _.policyMode }
      val details = getOverridenDirectiveDetails(overridesByRules.getOrElse(ruleId, Nil), directiveLib, rules) ++
                    getDirectivesComplianceDetails(aggregate.directives.values.toSet, directiveLib, globalMode, ComputePolicyMode.directiveModeOnNode(nodeMode, globalMode))

      val directivesMode = aggregate.directives.keys.map(directiveLib.allDirectives.get(_).flatMap(_._2.policyMode)).toSet
      val (policyMode,explanation) = ComputePolicyMode.ruleModeOnNode(nodeMode, globalMode)(directivesMode)
      RuleComplianceLine (
          rule
        , rule.id
        , aggregate.compliance
        , JsTableData(details)
        , policyMode
        , explanation
      )

    }

    JsTableData(ruleComplianceLine.toList)
  }

  def getOverridenDirectiveDetails(
      overrides   : List[OverridenPolicy]
    , directiveLib: FullActiveTechniqueCategory
    , rules       : Seq[Rule]
  ) : List[DirectiveComplianceLine] = {
    val overridesData = for {
      over                            <- overrides
      (overridenTech , overridenDir)  <- directiveLib.allDirectives.get(over.policy.directiveId)
      rule                            <- rules.find( _.id == over.overridenBy.ruleId)
      (overridingTech, overridingDir) <- directiveLib.allDirectives.get(over.overridenBy.directiveId)
    } yield {
      val overridenTechName    = overridenTech.techniques.get(overridenDir.techniqueVersion).map(_.name).getOrElse("Unknown technique")
      val overridenTechVersion = overridenDir.techniqueVersion

      val components = Nil
      val policyMode = "overriden"
      val explanation= "This directive is unique: only one directive derived from its technique can be set on a given node "+
                       s"at the same time. This one is overriden by direcitve '<i><b>${overridingDir.name}</b></i>' in rule "+
                       s"'<i><b>${rule.name}</b></i>' on that node."

      DirectiveComplianceLine (
          overridenDir
        , overridenTechName
        , overridenTechVersion
        , ComplianceLevel()
        , JsTableData(Nil)
        , policyMode
        , explanation
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
    , globalMode      : GlobalPolicyMode
  ) : JsTableData[DirectiveComplianceLine] = {

    val lines = getDirectivesComplianceDetails(report.report.directives.values.toSet, directiveLib, globalMode, ComputePolicyMode.directiveModeOnRule(allNodeInfos.map(_._2.policyMode).toSet, globalMode))
    JsTableData(lines.toList)
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
      val components       =  getComponentsComplianceDetails(directiveStatus.components.values.toSet, true)
      val (policyMode,explanation) = computeMode(directive.policyMode)

      DirectiveComplianceLine (
          directive
        , techniqueName
        , techniqueVersion
        , directiveStatus.compliance
        , components
        , policyMode
        , explanation
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

    val componentsComplianceData = components.map { component =>

      val (noExpand, values) = if(!includeMessage) {
        (true, getValuesComplianceDetails(component.componentValues.values.toSet))
      } else {
        val noExpand  = component.componentValues.forall( x => x._1 == DEFAULT_COMPONENT_KEY)

        (noExpand, getValuesComplianceDetails(component.componentValues.values.toSet))
      }

      ComponentComplianceLine(
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
  ) : JsTableData[ValueComplianceLine] = {
    val valuesComplianceData = for {
      value <- values
    } yield {
      val severity = ReportType.getWorseType(value.messages.map( _.reportType)).severity
      val status = getDisplayStatusFromSeverity(severity)
      val key = value.unexpandedComponentValue
      val messages = value.messages.map(x => (x.reportType.severity, x.message.getOrElse("")))

      ValueComplianceLine(
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
