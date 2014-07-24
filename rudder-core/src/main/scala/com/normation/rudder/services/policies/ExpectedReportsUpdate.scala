/*
*************************************************************************************
* Copyright 2014 Normation SAS
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

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.ExpandedRuleVal
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.NodeAndConfigId
import com.normation.rudder.domain.reports.RuleExpectedReports
import net.liftweb.common.Box
import com.normation.rudder.domain.reports.NodeConfigId
import net.liftweb.common.Loggable
import com.normation.rudder.domain.reports.DirectiveExpectedReports
import com.normation.rudder.repository.ReportsRepository
import com.normation.rudder.reports.execution.RoReportsExecutionRepository
import com.normation.rudder.reports.ComplianceMode
import scala.collection.mutable.Buffer
import scala.collection.mutable.{Set => MutSet}
import scala.collection.mutable.{Map => MutMap}
import com.normation.rudder.domain.reports.NodeConfigVersions
import com.normation.utils.Control.sequence
import com.normation.rudder.domain.policies.ExpandedDirectiveVal
import com.normation.rudder.repository.UpdateExpectedReportsRepository
import com.normation.rudder.domain.reports.ComponentExpectedReport
import com.normation.rudder.repository.WoNodeConfigIdInfoRepository
import org.joda.time.DateTime

/**
 * The purpose of that service is to handle the update of
 * expected reports.
 *
 */
trait ExpectedReportsUpdate {

  /**
   * Update the list of expected reports when we do a deployment
   *
   * For each RuleVal, we check if it was present or modified
   * If it was present and not changed, nothing is done for it
   * If it changed, then the previous version is closed, and the new one is opened
   */
  def updateExpectedReports(
      expandedRuleVals  : Seq[ExpandedRuleVal]
    , deleteRules       : Seq[RuleId]
    , updatedNodeConfigs: Map[NodeId, NodeConfigId]
    , generationTime    : DateTime
  ) : Box[Seq[RuleExpectedReports]]

}


class ExpectedReportsUpdateImpl(
    confExpectedRepo   : UpdateExpectedReportsRepository
  , nodeConfigRepo     : WoNodeConfigIdInfoRepository
) extends ExpectedReportsUpdate with Loggable {
  /**
   * Update the list of expected reports when we do a deployment
   * For each RuleVal, we check if it was present or it serial changed
   *
   * Note : deleteRules is not really used (maybe it will in the future)
   * @param ruleVal
   * @return
   */
  override def updateExpectedReports(expandedRuleVals : Seq[ExpandedRuleVal], deleteRules : Seq[RuleId], updatedNodeConfigs: Map[NodeId, NodeConfigId], generationTime: DateTime
) : Box[Seq[RuleExpectedReports]] = {

    val openExepectedReports = confExpectedRepo.findAllCurrentExpectedReportsWithNodesAndSerial()

    // All the rule and serial. Used to know which one are to be removed
    val currentConfigurationsToRemove =  MutMap[RuleId, (Int, Int, Map[NodeId, NodeConfigVersions])]() ++ openExepectedReports


    val confToClose = MutSet[RuleId]()
    val confToCreate = Buffer[ExpandedRuleVal]()

    // Then we need to compare each of them with the one stored
    for (conf@ExpandedRuleVal(ruleId, newSerial, configs) <- expandedRuleVals) {
      currentConfigurationsToRemove.get(ruleId) match {
        // non existant, add it
        case None =>
          logger.debug("New rule %s".format(ruleId))
          confToCreate += conf

        case Some((serial, nodeJoinKey, nodeConfigMap)) if ((serial == newSerial)&&(configs.size > 0)) =>
            // no change if same serial and some config appliable, that's ok, trace level
            logger.trace(s"Serial number (${serial}) for expected reports for rule '${ruleId.value}' was not changed and cache up-to-date: nothing to do")
            // must check that their are no differents nodes in the DB than in the new reports
            // it can happen if we delete nodes in some corner case (detectUpdates(nodes) cannot detect it
            // And it's actually nodes, not node version because version may change and still use the
            // same expected report
            if (configs.keySet.map( _.nodeId) != nodeConfigMap.keySet) {
              logger.debug("Same serial %s for ruleId %s, but not same node set, it need to be closed and created".format(serial, ruleId))
              confToCreate += conf
              confToClose += ruleId
            } else

            currentConfigurationsToRemove.remove(ruleId)

        case Some((serial, nodeJoinKey, nodeSet)) if ((serial == newSerial)&&(configs.size == 0)) => // same serial, but no targets
            // if there is not target, then it need to be closed
          logger.debug(s"Serial number (${serial}) for expected reports for rule '${ruleId.value}' was not changed BUT no previous configuration known: update expected reports for that rule")
          confToClose += ruleId

        case Some(serial) => // not the same serial
          logger.debug(s"Serial number (${serial}) for expected reports for rule '${ruleId.value}' was changed: update expected reports for that rule")
            confToCreate += conf
            confToClose += ruleId

            currentConfigurationsToRemove.remove(ruleId)
      }
    }

    // close the expected reports that don't exist anymore
    // and the ones that need to be changed
    val allClosable = (currentConfigurationsToRemove.keys ++ confToClose).toSet

    for (closable <- allClosable) {
      confExpectedRepo.closeExpectedReport(closable, generationTime)
    }

    // Now I need to unfold the configuration to create, so that I get for a given
    // set of ruleId, serial, DirectiveExpectedReports we have the list of  corresponding nodes

    val expanded = confToCreate.map { case ExpandedRuleVal(ruleId, serial, configs) =>
      configs.toSeq.map { case (nodeConfigId, directives) =>
        // each directive is converted into Seq[DirectiveExpectedReports]
        val directiveExpected = directives.map { directive =>
               val seq = ComputeCardinalityOfDirectiveVal.getCardinality(directive)

               seq.map { case(componentName, componentsValues, unexpandedCompValues) =>
                          DirectiveExpectedReports(
                              directive.directiveId
                            , Seq(
                                ComponentExpectedReport(
                                    componentName
                                  , componentsValues.size
                                  , componentsValues
                                  , unexpandedCompValues
                                )
                              )
                          )
               }
        }

        (ruleId, serial, nodeConfigId, directiveExpected.flatten)
      }
    }

    // we need to group by DirectiveExpectedReports, RuleId, Serial
    val flatten = expanded.flatten.flatMap { case (ruleId, serial, nodeConfigId, directives) =>
      directives.map (x => (ruleId, serial, nodeConfigId, x))
    }

    val preparedValues = flatten.groupBy[(RuleId, Int, DirectiveExpectedReports)]{ case (ruleId, serial, nodeConfigId, directive) =>
      (ruleId, serial, directive) }.map { case (key, value) => (key -> value.map(x=> x._3))}.toSeq

    // here we group them by rule/serial/seq of node, so that we have the list of all DirectiveExpectedReports that apply to them
    val groupedContent = preparedValues.toSeq.map { case ((ruleId, serial, directive), nodeConfigIds) => (ruleId, serial, directive, nodeConfigIds) }.
       groupBy[(RuleId, Int, Seq[NodeAndConfigId])]{ case (ruleId, serial, directive, nodeConfigIds) => (ruleId, serial, nodeConfigIds.toSeq)}.map {
         case (key, value) => (key -> value.map(x => x._3))
       }.toSeq
    // now we save them

    for {
      createdExpectedReports   <- sequence(groupedContent) { case ((ruleId, serial, nodeConfigIds), directives) =>
                                    confExpectedRepo.saveExpectedReports(ruleId, serial, generationTime, directives, nodeConfigIds)
                                  }
      //we want to save updatedNodeConfiguration that were not already saved
      //in expected reports.
      newConfigId              =  expandedRuleVals.flatMap { case ExpandedRuleVal(_, _, configs) => configs.keySet.map{case NodeAndConfigId(id,v) => (id,v)}}.toMap
      notUpdateNodeJoinKey     =  openExepectedReports.filterKeys(k => !allClosable.contains(k)).flatMap{ case(ruleId, (serial, nodeJoinKey, mapNodes)) =>
                                    mapNodes.values.map { case NodeConfigVersions(nodeId, versions) =>
                                      val lastVersion = newConfigId(nodeId)
                                      val newVersions = if(versions.contains(lastVersion)) versions else lastVersion :: versions
                                      (nodeJoinKey, NodeConfigVersions(nodeId, newVersions))
                                    }
                                  }.toSeq
      updatedNodeConfigVersion <- confExpectedRepo.updateNodeConfigVersion(notUpdateNodeJoinKey)
      savedNodesInfos          <- nodeConfigRepo.addNodeConfigIdInfo(updatedNodeConfigs, generationTime)
    } yield {
      createdExpectedReports
    }
  }

}

  /*
   * Utilitary object to calculate cardinality of components
   * Component, ComponentValues(expanded), ComponentValues (unexpanded))
   */
object ComputeCardinalityOfDirectiveVal extends Loggable {
  import com.normation.cfclerk.xmlparsers.CfclerkXmlConstants.DEFAULT_COMPONENT_KEY

  def getCardinality(container : ExpandedDirectiveVal) : Seq[(String, Seq[String], Seq[String])] = {


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
          (Seq(DEFAULT_COMPONENT_KEY),originalVariables.values) // this is an autobounding policy
        case (Some(variable), None) =>
          logger.warn("Somewhere in the expansion of variables, the bounded variable %s for %s in DirectiveVal %s appeared, but was not originally there".format(
              boundingVar, container.trackerVariable.spec.name, container.directiveId.value))
          (variable.values,Seq()) // this is an autobounding policy

      }
    }

    /**
     * We have two separate paths:
     * - if the technique is standart, we keep the complex old path
     * - if it is a meta technique, we take the easy paths
     */
    container.technique.providesExpectedReports match {
      case false =>
        // this is the old path
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
              //that log is outputed one time for each directive for each node using a technique, it's far too
              //verbose on debug.
              logger.trace("Technique '%s' does not define any components, assigning default component with expected report = 1 for Directive %s".format(
                container.technique.id, container.directiveId))

              val trackingVarCard = getTrackingVariableCardinality
              Seq((container.technique.id.name.value, trackingVarCard._1, trackingVarCard._2))
            } else {
              allComponents
            }
      case true =>
        // this is easy, everything is in the DirectiveVal; the components contains only one value, the
        // one that we want
        val allComponents = container.technique.rootSection.getAllSections.flatMap { section =>
          if (section.isComponent) {
            section.componentKey match {
              case None =>
                logger.error(s"We don't have defined reports keys for section ${section.name} that should provide predefined values")
                None
              case Some(name) =>
                (container.variables.get(name), container.originalVariables.get(name)) match {
                  case (Some(expandedValues), Some(originalValues)) if expandedValues.values.size == originalValues.values.size =>
                    Some((section.name, expandedValues.values, originalValues.values))

                  case (Some(expandedValues), Some(originalValues)) if expandedValues.values.size != originalValues.values.size =>
                    logger.error(s"In section ${section.name}, the original values and the expanded values don't have the same size. Orignal values are ${originalValues.values}, expanded are ${expandedValues.values}")
                    None

                  case _ =>
                    logger.error(s"The reports keys for section ${section.name} do not exist")
                    None
                }
            }
          } else {
            None
          }
        }
        allComponents
    }

  }

}
