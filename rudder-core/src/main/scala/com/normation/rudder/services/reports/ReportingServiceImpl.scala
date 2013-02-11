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
import scala.collection._
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
import com.normation.rudder.domain.transporter._
import com.normation.rudder.services.reports._
import com.normation.rudder.repository._
import com.normation.rudder.domain.policies.RuleVal
import com.normation.rudder.services.policies.RuleTargetService
import com.normation.utils.Control._
import com.normation.rudder.domain.policies.DirectiveVal
import com.normation.rudder.domain.reports.DirectiveExpectedReports
import com.normation.rudder.domain.reports.ReportComponent
import com.normation.cfclerk.xmlparsers.CfclerkXmlConstants._
import com.normation.cfclerk.services.TechniqueRepository

class ReportingServiceImpl(
    directiveTargetService: RuleTargetService
  , confExpectedRepo: RuleExpectedReportsRepository
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
  def updateExpectedReports(ruleVals : Seq[RuleVal], deleteRules : Seq[RuleId]) : Box[Seq[RuleExpectedReports]] = {

    // First we need to get the targets of each rule
    val confAndNodes = ruleVals.map { ruleVal =>
      (ruleVal -> ruleVal.targets.flatMap { target =>
        directiveTargetService.getNodeIds(target).openOr(Seq())
      }.toSeq)
    }

    // All the rule and serial. Used to know which one are to be removed
    val currentConfigurationsToRemove =  mutable.Map[RuleId, Int]() ++
      confExpectedRepo.findAllCurrentExpectedReportsAndSerial()

    val confToClose = mutable.Set[RuleId]()
    val confToAdd = mutable.Map[RuleId, (RuleVal,Seq[NodeId])]()

    // Then we need to compare each of them with the one stored
    for (conf@(ruleVal, nodes) <- confAndNodes) {
      currentConfigurationsToRemove.get(ruleVal.ruleId) match {
                    // non existant, add it
        case None => confToAdd += (  ruleVal.ruleId -> conf)

        case Some(serial) if ((serial == ruleVal.serial)&&(nodes.size > 0)) => // no change if same serial
            currentConfigurationsToRemove.remove(ruleVal.ruleId)

        case Some(serial) if ((serial == ruleVal.serial)&&(nodes.size == 0)) => // no change if same serial
            // if there is not target, then it need to be closed

        case Some(serial) => // not the same serial
            confToAdd += (  ruleVal.ruleId -> conf)
            confToClose += ruleVal.ruleId

            currentConfigurationsToRemove.remove(ruleVal.ruleId)
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

    // compute the cardinality and save them
    sequence(confToAdd.values.toSeq) { case(ruleVal, nodeIds) =>
      ( for {
        policyExpectedReports <- sequence(ruleVal.directiveVals) { policy =>
                                   ( for {
                                     seq <- getCardinality(policy) ?~!
                                     "Can not get cardinality for rule %s".format(ruleVal.ruleId)
                                    } yield {
                                      seq.map { case(componentName, componentsValues) =>
                                        DirectiveExpectedReports(policy.directiveId,
                                          Seq(ReportComponent(componentName, componentsValues.size, componentsValues)))
                                      }
                                   } )
                                 }
         expectedReport <- confExpectedRepo.saveExpectedReports(
                               ruleVal.ruleId
                             , ruleVal.serial
                             , policyExpectedReports.flatten
                             , nodeIds
                           )
      } yield {
        expectedReport
      } )
    }
  }


  /**
   * Returns the reports for a rule
   */
  def findReportsByRule(ruleId : RuleId, beginDate : Option[DateTime], endDate : Option[DateTime]) : Seq[ExecutionBatch] = {
    val result = mutable.Buffer[ExecutionBatch]()

    // look in the configuration
    var expectedConfigurationReports = confExpectedRepo.findExpectedReports(ruleId, beginDate, endDate)

    for (expected <- expectedConfigurationReports) {
      result ++= createBatchesFromConfigurationReports(expected, expected.beginDate, expected.endDate)
    }

    result

  }


  /**
   * Returns the reports for a node (for all directive/CR) (and hides result from other servers)
   */
  def findReportsByNode(nodeId : NodeId, beginDate : Option[DateTime], endDate : Option[DateTime]) : Seq[ExecutionBatch] = {
    val result = mutable.Buffer[ExecutionBatch]()

    // look in the configuration
    val expectedConfigurationReports = confExpectedRepo.
      findExpectedReportsByNode(nodeId, beginDate, endDate).
      map(x => x.copy(nodeIds = Seq[NodeId](nodeId)))

    for (expected <- expectedConfigurationReports) {
      result ++= createBatchesFromConfigurationReports(expected, expected.beginDate, expected.endDate)
    }

    result
  }



  /**
   * Find the latest reports for a given rule (for all servers)
   * Note : if there is an expected report, and that we don't have it, we should say that it is empty
   */
  def findImmediateReportsByRule(ruleId : RuleId) : Option[ExecutionBatch] = {
     // look in the configuration
    confExpectedRepo.findCurrentExpectedReports(ruleId).map(
        expected => createLastBatchFromConfigurationReports(expected) )
  }

  /**
   * Find the latest (15 minutes) reports for a given node (all CR)
   * Note : if there is an expected report, and that we don't have it, we should say that it is empty
   */
  def findImmediateReportsByNode(nodeId : NodeId) :  Seq[ExecutionBatch] = {
    // look in the configuration
    confExpectedRepo.findCurrentExpectedReportsByNode(nodeId).
      map(x => x.copy(nodeIds = Seq[NodeId](nodeId))).
      map(expected => createLastBatchFromConfigurationReports(expected, Some(nodeId)) )

  }

  /**
   *  find the last reports for a given node, for a sequence of rules
   *  look for each CR for the current report
   */
  def findImmediateReportsByNodeAndCrs(nodeId : NodeId, ruleIds : Seq[RuleId]) : Seq[ExecutionBatch] = {

    //  fetch the current expected rule report
    confExpectedRepo.findCurrentExpectedReportsByNode(nodeId).
            filter(x => ruleIds.contains(x.ruleId)).
            map(x => x.copy(nodeIds = Seq[NodeId](nodeId))).
            map(expected => createLastBatchFromConfigurationReports(expected, Some(nodeId)) )

  }

  /**
   *  find the reports for a given server, for the whole period of the last application
   */
  def findCurrentReportsByNode(nodeId : NodeId) : Seq[ExecutionBatch] = {
    //  fetch the current expected configuration report
    var expectedConfigurationReports = confExpectedRepo.findCurrentExpectedReportsByNode(nodeId)
    val configuration = expectedConfigurationReports.map(x => x.copy(nodeIds = Seq[NodeId](nodeId)))

    val result = mutable.Buffer[ExecutionBatch]()

    for (conf <- configuration) {
      result ++= createBatchesFromConfigurationReports(conf,conf.beginDate, conf.endDate)
    }

    result
  }


  /************************ Helpers functions **************************************/


   /**
   * From a RuleExpectedReports, create batch synthesizing these information by
   * searching reports in the database from the beginDate to the endDate
   * @param expectedOperationReports
   * @param reports
   * @return
   */
  private def createBatchesFromConfigurationReports(expectedConfigurationReports : RuleExpectedReports, beginDate : DateTime, endDate : Option[DateTime]) : Seq[ExecutionBatch] = {
    val batches = mutable.Buffer[ExecutionBatch]()

    // Fetch the reports corresponding to this rule, and filter them by nodes
    val reports = reportsRepository.findReportsByRule(expectedConfigurationReports.ruleId, Some(expectedConfigurationReports.serial), Some(beginDate), endDate).filter( x =>
            expectedConfigurationReports.nodeIds.contains(x.nodeId)  )

    for {
      (rule,date) <- reports.map(x => (x.ruleId ->  x.executionTimestamp)).toSet[(RuleId, DateTime)].toSeq
    } yield {
      new ConfigurationExecutionBatch(
          rule,
          expectedConfigurationReports.directiveExpectedReports,
          expectedConfigurationReports.serial,
          date,
          reports.filter(x => x.executionTimestamp == date), // we want only those of this run
          expectedConfigurationReports.nodeIds,
          expectedConfigurationReports.beginDate,
          expectedConfigurationReports.endDate)
    }
  }



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

    new ConfigurationExecutionBatch(
          expectedConfigurationReports.ruleId,
          expectedConfigurationReports.directiveExpectedReports,
          expectedConfigurationReports.serial,
          reports.headOption.map(x => x.executionTimestamp).getOrElse(DateTime.now()), // this is a dummy date !
          reports,
          expectedConfigurationReports.nodeIds,
          expectedConfigurationReports.beginDate,
          expectedConfigurationReports.endDate)
  }


  private def getCardinality(container : DirectiveVal) : Box[Seq[(String, Seq[String])]] = {
    val getTrackingVariableCardinality : Seq[String] = {
      val boundingVar = container.trackerVariable.spec.boundingVariable.getOrElse(container.trackerVariable.spec.name)
      // now the cardinality is the length of the boundingVariable
      container.variables.get(boundingVar) match {
        case None =>
          logger.debug("Could not find the bounded variable %s for %s in DirectiveVal %s".format(
              boundingVar, container.trackerVariable.spec.name, container.directiveId.value))
          Seq(DEFAULT_COMPONENT_KEY) // this is an autobounding policy
        case Some(variable) =>
          variable.values
      }
    }

    /*
     * We can have several components, one by section.
     * If there is no component for that policy, the policy is autobounded to DEFAULT_COMPONENT_KEY
     */
    for {
      technique <- Box(techniqueRepository.get(container.techniqueId)) ?~! "Can not find technique %s".format(container.techniqueId)
    } yield {
      val allComponents = technique.rootSection.getAllSections.flatMap { section =>
        if(section.isComponent) {
          section.componentKey match {
            case None =>
              //a section that is a component without componentKey variable: card=1, value="None"
              Some((section.name, Seq(DEFAULT_COMPONENT_KEY)))
            case Some(varName) =>
              //a section with a componentKey variable: card=variable card
              val values = container.variables.get(varName).map( _.values).getOrElse(Seq())
              Some((section.name, values))
          }
        } else {
          None
        }
      }

      if(allComponents.size < 1) {
        logger.debug("Technique '%s' does not define any components, assigning default component with expected report = 1 for Directive %s".format(
          container.techniqueId, container.directiveId))
        Seq((container.techniqueId.name.value, getTrackingVariableCardinality))
      } else {
        allComponents
      }
    }
  }

}

