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

package com.normation.rudder.services.policies

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.NodeAndConfigId
import net.liftweb.common.Box
import com.normation.rudder.domain.reports.NodeConfigId
import net.liftweb.common.Loggable
import com.normation.rudder.domain.reports.DirectiveExpectedReports
import com.normation.rudder.repository.UpdateExpectedReportsRepository
import com.normation.rudder.domain.reports.ComponentExpectedReport
import org.joda.time.DateTime
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.services.policies.write.Cf3PolicyDraftId
import com.normation.rudder.domain.reports.RuleExpectedReports
import com.normation.rudder.domain.reports.NodeExpectedReports
import scalaz.{Failure => _}
import com.normation.rudder.domain.logger.TimingDebugLogger
import com.normation.rudder.domain.reports.NodeModeConfig
import com.normation.rudder.domain.reports.OverridenPolicy


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
    , overrides         : Set[UniqueOverrides]
    , allNodeModes      : Map[NodeId, NodeModeConfig]
  ) : Box[List[NodeExpectedReports]]

}

/**
 * Container class to store that for a given
 * node, for a rule and directive, all values
 * are overriden by an other directive derived from the
 * same unique technique
 */
case class UniqueOverrides(
    nodeId: NodeId
  , ruleId: RuleId
  , directiveId: DirectiveId
  , overridenBy: Cf3PolicyDraftId
)

class ExpectedReportsUpdateImpl(confExpectedRepo: UpdateExpectedReportsRepository) extends ExpectedReportsUpdate with Loggable {

  /**
   * Utility method that filters overriden directive by node
   * in expandedruleval
   */
  private[this] def filterOverridenDirectives(
      expandedRuleVals  : Seq[ExpandedRuleVal]
    , overrides         : Set[UniqueOverrides]
  ): Seq[ExpandedRuleVal] = {
    /*
     * Start by filtering expandedRuleVals to remove overriden
     * directives from them.
     */
    val byRulesOverride = overrides.groupBy { _.ruleId }
    expandedRuleVals.map { case rule@ExpandedRuleVal(ruleId, newSerial, configs) =>
      byRulesOverride.get(ruleId) match {
        case None => rule
        case Some(overridenDirectives) =>
          val byNodeOverride = overridenDirectives.groupBy { _.nodeId }
          val filteredConfig = configs.map { case(i@NodeAndConfigId(nodeId, c), directives) =>
            val toFilter = byNodeOverride.getOrElse(nodeId, Seq()).map( _.directiveId).toSet
            (i, directives.filterNot { directive => toFilter.contains(directive.directiveId) })
          }
          ExpandedRuleVal(ruleId, newSerial, filteredConfig)
      }
    }
  }

  /*
   * Update the list of expected reports when we do a deployment
   * For each RuleVal, we check if it was present or it serial changed
   *
   * Note : deleteRules is not really used (maybe it will in the future)
   * @param ruleVal
   * @return
   */
  override def updateExpectedReports(
      expandedRuleVals  : Seq[ExpandedRuleVal]
    , deleteRules       : Seq[RuleId]
    , updatedNodeConfigs: Map[NodeId, NodeConfigId]
    , generationTime    : DateTime
    , overrides         : Set[UniqueOverrides]
    , allNodeModes      : Map[NodeId, NodeModeConfig]
  ) : Box[List[NodeExpectedReports]] = {
    val filteredExpandedRuleVals = filterOverridenDirectives(expandedRuleVals, overrides)


    //transform overrides toward a map of [nodeId -> OverridenDirectives]
    val overridesByNode = overrides.groupBy( _.nodeId ).mapValues(seq => seq.map(x =>
      OverridenPolicy(Cf3PolicyDraftId(x.ruleId, x.directiveId), x.overridenBy)
    ).toList)

    // transform to rule expected reports by node
    val directivesExpectedReports = filteredExpandedRuleVals.map { case ExpandedRuleVal(ruleId, serial, configs) =>
      configs.toSeq.map { case (nodeConfigId, directives) =>
        // each directive is converted into Seq[DirectiveExpectedReports]
        val directiveExpected = directives.map { directive =>
               val seq = ComputeCardinalityOfDirectiveVal.getCardinality(directive)

               seq.map { case(componentName, componentsValues, unexpandedCompValues) =>
                          DirectiveExpectedReports(
                              directive.directiveId
                            , directive.policyMode
                            , directive.isSystem
                            , List(
                                ComponentExpectedReport(
                                    componentName
                                  , componentsValues.size
                                  , componentsValues.toList
                                  , unexpandedCompValues.toList
                                )
                              )
                          )
               }
        }

        (nodeConfigId, ruleId, serial, directiveExpected.flatten)
      }
    }.flatten.toList

    //now group back by nodeConfigId and transorm to ruleExpectedReport
    val ruleExepectedByNode = directivesExpectedReports.groupBy( _._1 ).mapValues { seq =>
      // build ruleExpected
      // we're grouping by (ruleId, serial) even if we should have an homogeneous set here
      seq.groupBy( t => (t._2, t._3) ).map { case ( (ruleId, serial), directives ) =>
        RuleExpectedReports(ruleId, serial, directives.flatMap(_._4).toList)
      }
    }

    // and finally add what is missing for NodeExpectedReports

    val nodeExpectedReports = ruleExepectedByNode.map { case (nodeAndConfigId, rules) =>
      val nodeId = nodeAndConfigId.nodeId
      NodeExpectedReports(
          nodeId
        , nodeAndConfigId.version
        , generationTime
        , None
        , allNodeModes(nodeId) //that shall not throw, because we have all nodes here
        , rules.toList
        , overridesByNode.getOrElse(nodeId, Nil)
      )
    }.toList

    // now, we just need to go node by node, and for each:
    val time_0 = System.currentTimeMillis
    val res = confExpectedRepo.saveNodeExpectedReports(nodeExpectedReports)
    TimingDebugLogger.trace(s"updating expected node configuration in base took: ${System.currentTimeMillis-time_0}ms")
    res
  }
}

  /*
   * Utility object to calculate cardinality of components
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



