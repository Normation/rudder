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

package com.normation.rudder.services.reports

import com.normation.cfclerk.domain.TrackerVariableSpec
import com.normation.inventory.domain.NodeId
import net.liftweb.common._
import scala.collection.mutable.{Set => MutSet}
import scala.collection.mutable.{Map => MutMap}
import org.joda.time._
import org.slf4j.{Logger,LoggerFactory}
import com.normation.cfclerk.domain.{
  TrackerVariable,Variable,
  Cf3PolicyDraft,Cf3PolicyDraftId
}
import com.normation.rudder.domain._
import com.normation.rudder.domain.reports.RuleExpectedReports
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.bean._
import com.normation.rudder.services.reports._
import com.normation.rudder.repository._
import com.normation.rudder.domain.policies.RuleVal
import com.normation.rudder.domain.policies.DirectiveVal
import com.normation.rudder.domain.reports.DirectiveExpectedReports
import com.normation.rudder.domain.reports.ReportComponent
import com.normation.cfclerk.xmlparsers.CfclerkXmlConstants._
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.rudder.domain.policies.ExpandedRuleVal
import com.normation.utils.Control._
import scala.collection.mutable.Buffer

class ReportingServiceImpl(
    confExpectedRepo: RuleExpectedReportsRepository
  , reportsRepository: ReportsRepository
  , techniqueRepository: TechniqueRepository
) extends ReportingService {

  val logger = LoggerFactory.getLogger(classOf[ReportingServiceImpl])

  /**
   * Update the list of expected reports when we do a deployment
   * For each RuleVal, we check if it was present or it serial changed
   *
   * Note : deleteRules is not really used (maybe it will in the future)
   * @param ruleVal
   * @return
   */
  def updateExpectedReports(expandedRuleVals : Seq[ExpandedRuleVal], deleteRules : Seq[RuleId]) : Box[Seq[RuleExpectedReports]] = {
    // All the rule and serial. Used to know which one are to be removed
    val currentConfigurationsToRemove =  MutMap[RuleId, Int]() ++
      confExpectedRepo.findAllCurrentExpectedReportsAndSerial()

    val confToClose = MutSet[RuleId]()
    val confToCreate = Buffer[ExpandedRuleVal]()

    // Then we need to compare each of them with the one stored
    for (conf@ExpandedRuleVal(ruleId, configs, newSerial) <- expandedRuleVals) {
      currentConfigurationsToRemove.get(ruleId) match {
        // non existant, add it
        case None =>
          logger.debug("New rule %s".format(ruleId))
          confToCreate += conf

        case Some(serial) if ((serial == newSerial)&&(configs.size > 0)) =>
            // no change if same serial and some config appliable
            logger.debug("Same serial %s for ruleId %s, and configs presents".format(serial, ruleId))
            currentConfigurationsToRemove.remove(ruleId)

        case Some(serial) if ((serial == newSerial)&&(configs.size == 0)) => // same serial, but no targets
            // if there is not target, then it need to be closed
          logger.debug("Same serial, and no configs present")

        case Some(serial) => // not the same serial
          logger.debug("Not same serial")
            confToCreate += conf
            confToClose += ruleId

            currentConfigurationsToRemove.remove(ruleId)
      }
    }


    // close the expected reports that don't exist anymore
    for (closable <- currentConfigurationsToRemove.keys) {
      confExpectedRepo.closeExpectedReport(closable)
    }
    // close the expected reports that need to be changed
    for (closable <- confToClose) {
      confExpectedRepo.closeExpectedReport(closable)
    }

    // Now I need to unfold the configuration to create, so that I get for a given
    // set of ruleId, serial, DirectiveExpectedReports we have the list of  corresponding nodes

    val expanded = confToCreate.map { case ExpandedRuleVal(ruleId, configs, serial) =>
      configs.toSeq.map { case (nodeId, directives) =>
        // each directive are converted into Seq[DirectiveExpectedReports]
        val directiveExpected = directives.map { directive =>
	          val seq = getCardinality(directive)

	          seq.map { case(componentName, componentsValues, unexpandedCompValues) =>
	                     DirectiveExpectedReports(
	                         directive.directiveId
	                       , Seq(
	                           ReportComponent(
	                               componentName
	                             , componentsValues.size
	                             , componentsValues
	                             , unexpandedCompValues
	                           )
	                         )
	                     )
	          }
        }

        (ruleId, serial, nodeId, directiveExpected.flatten)
      }
    }

    // we need to group by DirectiveExpectedReports, RuleId, Serial
    val flatten = expanded.flatten.flatMap { case (ruleId, serial, nodeId, directives) =>
      directives.map (x => (ruleId, serial, nodeId, x))
    }

    val preparedValues = flatten.groupBy[(RuleId, Int, DirectiveExpectedReports)]{ case (ruleId, serial, nodeId, directive) =>
      (ruleId, serial, directive) }.map { case (key, value) => (key -> value.map(x=> x._3))}.toSeq

    // here we group them by rule/serial/seq of node, so that we have the list of all DirectiveExpectedReports that apply to them
    val groupedContent = preparedValues.toSeq.map { case ((ruleId, serial, directive), nodes) => (ruleId, serial, directive, nodes) }.
       groupBy[(RuleId, Int, Seq[NodeId])]{ case (ruleId, serial, directive, nodes) => (ruleId, serial, nodes.toSeq)}.map {
         case (key, value) => (key -> value.map(x => x._3))
       }.toSeq
    // now we save them
    sequence(groupedContent) { case ((ruleId, serial, nodes), directives) =>
      confExpectedRepo.saveExpectedReports(
                           ruleId
                         , serial
                         , directives
                         , nodes
                       )
    }
  }


  /**
   * Find the latest reports for a given rule (for all servers)
   * Note : if there is an expected report, and that we don't have it, we should say that it is empty
   */
  def findImmediateReportsByRule(ruleId : RuleId) : Box[Option[ExecutionBatch]] = {
     // look in the configuration
    confExpectedRepo.findCurrentExpectedReports(ruleId) match {
      case Empty => Empty
      case e:Failure => logger.error("Error when fetching reports for Rule %s : %s".format(ruleId.value, e.messageChain)); e
      case Full(expected) => Full(expected.map(createLastBatchFromConfigurationReports(_)))
    }
  }

  /**
   * Find the latest reports for a seq of rules (for all node)
   * Note : if there is an expected report, and that we don't have it, we should say that it is empty
   */
  def findImmediateReportsByRules(rulesIds : Seq[RuleId]) : Map[RuleId, Box[Option[ExecutionBatch]]] = {
    val expectedReports = rulesIds.map { ruleId =>
      (ruleId, confExpectedRepo.findCurrentExpectedReports(ruleId))
    }
    // We need to go through each full non none element, and put back the elements in the right order
    val nonEmptyExpected = expectedReports.map(_._2).flatten.flatten


    val mapBatch = createLastBatchesFromConfigurationReports(nonEmptyExpected)

    expectedReports.map { case (ruleId, status) =>
      (ruleId, status match {
        case Full(Some(expected)) =>
          mapBatch.get(expected.ruleId) match {
            case None =>
              logger.error(s"Error when fetching reports for Rule ID ${expected.ruleId}")
              Failure(s"Error when fetching reports for Rule ID ${expected.ruleId}")
            case Some(batch) =>
              Full(Some(batch))
          }
        case Full(None) => Full(None)
        case e : EmptyBox => e
      })
    }.toMap
  }


  /**
   * Find the latest (15 minutes) reports for a given node (all CR)
   * Note : if there is an expected report, and that we don't have it, we should say that it is empty
   */
  def findImmediateReportsByNode(nodeId : NodeId) :  Box[Seq[ExecutionBatch]] = {
    // look in the configuration
    confExpectedRepo.findCurrentExpectedReportsByNode(nodeId) match {
      case e:EmptyBox => e
      case Full(seq) =>
        Full(seq.map(expected => createLastBatchFromConfigurationReports(expected, Some(nodeId))))
    }
  }

  /************************ Helpers functions **************************************/

  /**
   * From a RuleExpectedReports, create batch synthetizing the last run
   * @param expectedOperationReports
   * @param reports
   * @return
   */
  private def createLastBatchFromConfigurationReports(expectedConfigurationReports : RuleExpectedReports,
                nodeId : Option[NodeId] = None) : ExecutionBatch = {

    // Fetch the reports corresponding to this rule, and filter them by nodes
    val reports = reportsRepository.findLastReportByRule(
            expectedConfigurationReports.ruleId,
            expectedConfigurationReports.serial, nodeId)

    // If we are only searching on a node, then we restrict the directivesonnode to this node
    val directivesOnNodes = nodeId match {
      case None => expectedConfigurationReports.directivesOnNodes.map(x => DirectivesOnNodeExpectedReport(x.nodeIds, x.directiveExpectedReports))
      case Some(node) =>
        expectedConfigurationReports.directivesOnNodes.filter(x => x.nodeIds.contains(node)).map(x => DirectivesOnNodeExpectedReport(Seq(node), x.directiveExpectedReports))
    }
    new ConfigurationExecutionBatch(
          expectedConfigurationReports.ruleId,
          expectedConfigurationReports.serial,
          directivesOnNodes,
          reports.headOption.map(x => x.executionTimestamp).getOrElse(DateTime.now()), // this is a dummy date !
          reports,
          expectedConfigurationReports.beginDate,
          expectedConfigurationReports.endDate)
  }

  private def createLastBatchesFromConfigurationReports(
      expectedConfigurationReports : Seq[RuleExpectedReports]
  ) : Map[RuleId, ExecutionBatch] = {

    val rulesAndSerials = expectedConfigurationReports.map(x => (x.ruleId, x.serial))
    val allReports = reportsRepository.findLastReportsByRules(rulesAndSerials)


    expectedConfigurationReports.map { x =>
      val reports = allReports.filter( report => report.ruleId == x.ruleId)
      (
          x.ruleId
        , ConfigurationExecutionBatch(
            x.ruleId
          , x.serial
          , x.directivesOnNodes.map(dir => DirectivesOnNodeExpectedReport(dir.nodeIds, dir.directiveExpectedReports))
          , reports.headOption.map(_.executionTimestamp).getOrElse(DateTime.now())
          , reports
          , x.beginDate
          , x.endDate
          )
       )
    }.toMap
  }
  /**
   * Returns a seq of
   * Component, ComponentValues(expanded), ComponentValues (unexpanded))
   *
   */
  private def getCardinality(container : DirectiveVal) : Seq[(String, Seq[String], Seq[String])] = {
    // Computes the components values, and the unexpanded component values
    val getTrackingVariableCardinality : (Seq[String], Seq[String]) = {
      val boundingVar = container.trackerVariable.spec.boundingVariable.getOrElse(container.trackerVariable.spec.name)
      // now the cardinality is the length of the boundingVariable
      (container.variables.get(boundingVar), container.originalVariables.get(boundingVar)) match {
        case (None, None) =>
          logger.debug("Could not find the bounded variable %s for %s in DirectiveVal %s".format(
              boundingVar, container.trackerVariable.spec.name, container.directiveId.value))
          (Seq(DEFAULT_COMPONENT_KEY),Seq()) // this is an autobounding policy
        case (Some(variable), Some(originalVariables)) if (variable.values.size==originalVariables.values.size) =>
          (variable.values, originalVariables.values)
        case (Some(variable), Some(originalVariables)) =>
          logger.warn("Expanded and unexpanded values for bounded variable %s for %s in DirectiveVal %s have not the same size : %s and %s".format(
              boundingVar, container.trackerVariable.spec.name, container.directiveId.value,variable.values, originalVariables.values ))
          (variable.values, originalVariables.values)
        case (None, Some(originalVariables)) =>
          logger.warn("Somewhere in the expansion of variables, the bounded variable %s for %s in DirectiveVal %s was lost".format(
              boundingVar, container.trackerVariable.spec.name, container.directiveId.value))
          (Seq(DEFAULT_COMPONENT_KEY),originalVariables.values) // this is an autobounding policy
        case (Some(variable), None) =>
          logger.warn("Somewhere in the expansion of variables, the bounded variable %s for %s in DirectiveVal %s appeared, but was not originally there".format(
              boundingVar, container.trackerVariable.spec.name, container.directiveId.value))
          (variable.values,Seq()) // this is an autobounding policy

      }
    }

    /*
     * We can have several components, one by section.
     * If there is no component for that policy, the policy is autobounded to DEFAULT_COMPONENT_KEY
     */
    val allComponents = container.technique.rootSection.getAllSections.flatMap { section =>
      if(section.isComponent) {
        section.componentKey match {
          case None =>
            //a section that is a component without componentKey variable: card=1, value="None"
            Some((section.name, Seq(DEFAULT_COMPONENT_KEY), Seq(DEFAULT_COMPONENT_KEY)))
          case Some(varName) =>
            //a section with a componentKey variable: card=variable card
            val values = container.variables.get(varName).map( _.values).getOrElse(Seq())
            val unexpandedValues = container.originalVariables.get(varName).map( _.values).getOrElse(Seq())
            if (values.size != unexpandedValues.size)
              logger.warn("Caution, the size of unexpanded and expanded variables for autobounding variable in section %s for directive %s are not the same : %s and %s".format(
                  section.componentKey, container.directiveId.value, values, unexpandedValues ))
            Some((section.name, values, unexpandedValues))
        }
      } else {
        None
      }
    }

    if(allComponents.size < 1) {
      logger.debug("Technique '%s' does not define any components, assigning default component with expected report = 1 for Directive %s".format(
        container.technique.id, container.directiveId))
        val trackingVarCard = getTrackingVariableCardinality
      Seq((container.technique.id.name.value, trackingVarCard._1, trackingVarCard._2))
    } else {
      allComponents
    }
  }

}

