/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.rudder.services.reports

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.DirectiveId
import org.joda.time._
import org.joda.time.format._
import com.normation.rudder.domain.Constants
import com.normation.utils.HashcodeCaching
import scala.collection.mutable.Buffer
import com.normation.rudder.domain.logger.ReportLogger
import com.normation.rudder.domain.logger.ComplianceDebugLogger
import com.normation.rudder.domain.logger.ComplianceDebugLogger._
import com.normation.rudder.domain.policies.Directive
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.reports._
import com.normation.utils.Control.sequence
import com.normation.rudder.reports._
import com.normation.rudder.reports.execution.AgentRunId
import net.liftweb.common.Loggable
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.repository.NodeConfigIdInfo
import com.normation.rudder.reports.execution.AgentRun
import com.normation.rudder.domain.policies.SerialedRuleId
import com.normation.rudder.domain.policies.SerialedRuleId
import com.normation.rudder.repository.NodeConfigIdInfo
import com.normation.rudder.domain.policies.SerialedRuleId
import com.normation.cfclerk.xmlparsers.CfclerkXmlConstants.DEFAULT_COMPONENT_KEY
import java.util.regex.Pattern

/*
 *  we want to retrieve for each node the expected reports that matches it LAST
 *  run, or if no last run, what we are wainting for.
 *
 *  So the general resolution for ONE node is:
 *  - 1/ context
 *  - get the last run
 *  - get the list of nodeConfigIdInfo
 *
 *  - 2/ get the config id and date
 *  - 2.1: from the run:
 *    - if we have it, find the option[date] matching that version
 *  - 2.2: else (no run), we get the last available from nodeinfo with date
 *    - if none, get the last expected reports for the node, but not configId/date for it
 *  - so, we have a configId and an option[date] of validity
 *    => get expectedReports
 *
 *  - now, get reports for the run if any, and compute merge.
 *
 *
 *  For a node, we will get:
 *
 *  - run date:
 *    - Some(t): the node at least talked to us!
 *    - None   : never talked, build a pending/noreport list
 *  - (configId, eol of that config):
 *    - Some(id): we know what we expect!
 *      - Some(date): we know the validity !: the simplest case
 *      - None: this is an unexpected version (no meta info on it). Error.
 *    - None: migration only, that case will disappear with time. Compat mode,
 *            consider expected report for the node are ok. Don't care of the (None) date.
 *
 */

sealed trait RunAndConfigInfo

sealed trait ErrorNoConfigData extends RunAndConfigInfo

sealed trait ExpectedConfigAvailable extends RunAndConfigInfo {
  def expectedConfigInfo: NodeConfigIdInfo
}

sealed trait NoReport extends ExpectedConfigAvailable

sealed trait Unexpected extends ExpectedConfigAvailable

sealed trait Ok extends ExpectedConfigAvailable

//a marker trait which indicate that we want to
//have the details of the run
sealed trait LastRunAvailable extends RunAndConfigInfo {
  // this is the date and time reported by the run
  // (local node time of the starts of the run)
  def lastRunDateTime: DateTime

  //this is not an option even if we must take care of nodes
  //upgrading from previous version of Rudder without
  //config id because we must have some logic to find WHAT
  //is the applicable version in all case - it's one of the
  //major goal of ExecutionBatch#computeNodeRunInfo
  def lastRunConfigId: NodeConfigId

  // most of the time, we do have the corresponding
  // configId in base. But not always, for example
  // if the node configId was corrupted
  def lastRunConfigInfo: Option[NodeConfigIdInfo]
}



/**
 * The date and time when the status expires.
 * Depends of the compliance mode and the heartbeat period
 */
sealed trait MissingReportStatus extends RunAndConfigInfo {
    def missingReportStatus: ReportType
}

/**
 * The type of report to use for missing reports
 * (actually missing reports).
 * Depends of compliance mode.
 */
sealed trait ExpiringStatus extends RunAndConfigInfo {
    def expirationDateTime: DateTime
}

/*
 * Really, that node exists ?
 */
case object NoRunNoExpectedReport extends ErrorNoConfigData

/*
 * We don't have the needed configId in the expected
 * table. Either we don't have any config id at all,
 * or we can't find the version matching a run.
 * (it is some weird data lost in the server, or a node
 * not yet initialized)
 */
case class NoExpectedReport(
    lastRunDateTime: DateTime
  , lastRunConfigId: Option[NodeConfigId]
) extends ErrorNoConfigData

/*
 * No report of interest (either none, or
 * some but too old for our situation)
 */
case class NoReportInInterval(
    expectedConfigInfo: NodeConfigIdInfo
) extends NoReport

/*
 * No report of interest but expected because
 * we are on the correct mode for that
 */
case class ReportsDisabledInInterval(
    expectedConfigInfo: NodeConfigIdInfo
) extends NoReport

case class Pending(
    expectedConfigInfo : NodeConfigIdInfo
  , optLastRun         : Option[(DateTime, NodeConfigIdInfo)]
  , expirationDateTime : DateTime
  , missingReportStatus: ReportType
) extends NoReport with ExpiringStatus with MissingReportStatus

/*
 * the case where we have a version on the run,
 * versions are init in the server for that node,
 * and we don't have a version is an error
 */
case class UnexpectedVersion(
    lastRunDateTime   : DateTime
  , lastRunConfigInfo : Some[NodeConfigIdInfo]
  , lastRunExpiration : DateTime
  , expectedConfigInfo: NodeConfigIdInfo
  , expectedExpiration: DateTime
) extends Unexpected with LastRunAvailable {
  val lastRunConfigId = lastRunConfigInfo.get.configId
}

/**
 * A case where we have a run without version,
 * but we really should, because versions are init
 * in the server for that node
 */
case class UnexpectedNoVersion(
    lastRunDateTime   : DateTime
  , lastRunConfigInfo : Some[NodeConfigIdInfo]
  , lastRunExpiration : DateTime
  , expectedConfigInfo: NodeConfigIdInfo
  , expectedExpiration: DateTime
) extends Unexpected with LastRunAvailable {
  val lastRunConfigId = lastRunConfigInfo.get.configId
}

/**
 * A case where we have a run with a version,
 * but we didn't find it in db,
 * but we really should, because versions are init
 * in the server for that node
 */
case class UnexpectedUnknowVersion(
    lastRunDateTime   : DateTime
  , lastRunConfigId   : NodeConfigId
  , expectedConfigInfo: NodeConfigIdInfo
  , expectedExpiration: DateTime
) extends Unexpected with LastRunAvailable {
  val lastRunConfigInfo = None
}


case class ComputeCompliance(
    lastRunDateTime    : DateTime
  , expectedConfigInfo : NodeConfigIdInfo
  , expirationDateTime : DateTime
  , missingReportStatus: ReportType
) extends Ok with LastRunAvailable with ExpiringStatus with MissingReportStatus {
  val lastRunConfigId   = expectedConfigInfo.configId
  val lastRunConfigInfo = Some(expectedConfigInfo)
}

/**
 * An execution batch contains the node reports for a given Rule / Directive at a given date
 * An execution Batch is at a given time <- TODO : Is it relevant when we have several node ?
 */

object ExecutionBatch extends Loggable {
  final val matchCFEngineVars = """.*\$(\{.+\}|\(.+\)).*""".r
  final private val replaceCFEngineVars = """\$\{.+\}|\$\(.+\)"""

  /**
   * containers to store common information about "what are we
   * talking about in that merge ?"
   */
  private[this] final case class MergeInfo(nodeId: NodeId, run: Option[DateTime], configId: Option[NodeConfigId], expirationTime: DateTime)

  /**
   * The time that we are going to give to the agent as a grace period to get
   * its reports.
   * That time is added to the normal agent execution period, so that:
   * - if the agent runs every 2 minutes, reports should have been received
   *   after 7 minutes max
   * - if the agent runs every 240 minutes, reports should have been received
   *   after 245 minutes max.
   *
   * That notion only makes sens for the compliance mode, as it is expected to
   * NOT receive report in the changes-only mode.
   */
  final val GRACE_TIME_PENDING = Duration.standardMinutes(5)

  /**
   * Then end of times, used to denote report which are not expiring
   */
  final val END_OF_TIME = new DateTime(Long.MaxValue)

  /**
   * Takes a string, that should contains a CFEngine var ( $(xxx) or ${xxx} )
   * replace the $(xxx) (or ${xxx}) part by .*
   * and doubles all the \
   * Returns a string that is suitable for a being used as a regexp
   */
  final def replaceCFEngineVars(x : String) : String = {
    x.replaceAll(replaceCFEngineVars, ".*").replaceAll("""\\""", """\\\\""")
  }

  case class ContextForNoAnswer(
      agentExecutionInterval: Int
    , complianceMode        : ComplianceMode
  )

  /*
   * Utility method to factor out common logging task and be assured that
   * the log message is actually sync with the info type.
   */
  private[this] def runType(traceMessage: String, runType: RunAndConfigInfo)(implicit nodeId: NodeId): RunAndConfigInfo = {
    val msg = if(traceMessage.trim.size == 0) "" else ": " + traceMessage
    ComplianceDebugLogger.node(nodeId).trace(s"Run config for node ${nodeId.value}: ${runType.logName} ${msg}")
    runType
  }

  /*
   * For each node, get the config it has.
   * This method bases its result on THE LAST RUN
   * of each node, and try to discover the run linked information (datetime, config id).
   *
   */
  def computeNodesRunInfo(
      nodeIds          : Map[NodeId, ResolvedAgentRunInterval]
    , runs             : Map[NodeId, Option[AgentRun]]
    , nodeConfigIdInfos: Map[NodeId, Option[Seq[NodeConfigIdInfo]]]
    , complianceMode   : ComplianceMode
  ): Map[NodeId, RunAndConfigInfo] = {

    val missingReportType = complianceMode.mode match {
      case FullCompliance => MissingReportType
      case ChangesOnly => SuccessReportType
      case ReportsDisabled => DisabledReportType
    }

    /*
     * How long time a run is valid AFTER AN UPDATE (i.e, not in permanent regime).
     * This is shorter than runValidityTime, because a config update IS a change and
     * force to send reports in all case.
     */
    def updateValidityTime(runIntervalInfo: ResolvedAgentRunInterval) = runIntervalInfo.interval.plus(GRACE_TIME_PENDING)

    /*
     * How long time a run is valid before receiving any report (but not after an update)
     */
    def runValidityTime(runIntervalInfo: ResolvedAgentRunInterval) = complianceMode.mode match {
      case ChangesOnly =>
        //expires after run*heartbeat period - we need an other run before that.
        val heartbeat = Duration.standardMinutes((runIntervalInfo.interval.getStandardMinutes * runIntervalInfo.heartbeatPeriod ))
        heartbeat.plus(GRACE_TIME_PENDING)
      case FullCompliance | ReportsDisabled =>
        updateValidityTime(runIntervalInfo)
    }

    val now = DateTime.now

    nodeIds.foreach { case (nodeId, runInfos) =>
      ComplianceDebugLogger.node(nodeId).debug(s"Node run configuration: ${(nodeId, complianceMode, runInfos).toLog }")
    }

    nodeIds.map { case (nodeId, intervalInfo) =>
      implicit val _n = nodeId

      val optInfo = nodeConfigIdInfos.getOrElse(nodeId, None)
      //special case if we are on "reports-disabled" mode
      val runInfo = if(complianceMode.mode == ReportsDisabled) {
        optInfo match {
          case Some(configs) if(configs.nonEmpty) =>
            runType(s"compliance mode is set to '${}', it's ok to not having reports", ReportsDisabledInInterval(configs.maxBy(_.creation.getMillis)))
          case _ =>
            runType(s"nodeId has no configuration ID version (it should, even in ${ReportsDisabled.name} compliance mode", NoRunNoExpectedReport)
        }
      } else {

        val optRun  = runs.get(nodeId).flatten match {
          case None => None
          case Some(run) =>
            //here we have to check that the run is not too old, see #7743
            val expirationTime = run.agentRunId.date.plus(runValidityTime(intervalInfo))
            if(expirationTime.isBefore(now)) {
              ComplianceDebugLogger.node(nodeId).debug(s"Last run [${run.agentRunId}] for node expired at ${expirationTime} => ignoring runs for the node")
              None
            } else {
              Some(run)
            }

        }

        (optRun, optInfo) match {
          case (None, None) =>
            runType("", NoRunNoExpectedReport)

          // There is no run for this node
          case (None, Some(configs)) =>
            if(configs.isEmpty) {
              runType("nodeId exists in DB but has no version (due to cleaning?)", NoRunNoExpectedReport)
            } else {
              val currentConfig = configs.maxBy(_.creation.getMillis)
              val expireTime = currentConfig.creation.plus(updateValidityTime(intervalInfo))

              if(expireTime.isBefore(now)) {
                runType("no run (ever or too old)", NoReportInInterval(currentConfig))
              } else {
                runType(s"no run (ever or too old), Pending until ${expireTime}", Pending(currentConfig, None, expireTime, missingReportType))
              }
            }

          case (Some(run), _) =>
            computeNodeRunInfo(
                  nodeId, optInfo, missingReportType
                , intervalInfo, updateValidityTime(intervalInfo), runValidityTime(intervalInfo)
                , now, run
            )

        }
      }

      (nodeId, runInfo)
    }.toMap
  }

  def computeNodeRunInfo(
      nodeId                : NodeId
    , nodeConfigIdInfos     : Option[Seq[NodeConfigIdInfo]]
    , missingReportType     : ReportType
    , runIntervalInfo       : ResolvedAgentRunInterval
    , updateValidityDuration: Duration
    , runValidityDuration   : Duration
    , timeToCompareTo       : DateTime
    , runToCheck            : AgentRun
  ): RunAndConfigInfo = {

    implicit val _n = nodeId

    (runToCheck, nodeConfigIdInfos) match {

      case (AgentRun(AgentRunId(_, t), optConfigId, _, _), None) =>
        runType("need to regenerate policies?", NoExpectedReport(t, optConfigId))

      //The run does not have a configId: migration, new nodes, etc.
      case (AgentRun(AgentRunId(_, t), None, _, _), Some(configs)) =>
        if(configs.isEmpty) {
          runType("nodeId exists in DB but has no version (due to cleaning?)", NoExpectedReport(t, None))
        } else {
          /*
           * Here, we want to check two things:
           * - does the run should have contain a config id ?
           *   It should if the oldest config was created too long ago
           *
           * - else, we look at the most recent
           *   config and decide between pending / no answer
           */
          val oldestConfigId = configs.minBy( _.creation.getMillis)
          val oldestExpiration = oldestConfigId.creation.plus(updateValidityDuration)
          if(oldestExpiration.isBefore(t) ) {
            //we had a config set a long time ago, then interval+grace time happen, and then
            //we get a run without any config id => the node didn't updated its promises
            val currentConfig = configs.maxBy( _.creation.getMillis)
            val currentExpiration = currentConfig.creation.plus(updateValidityDuration)
            runType(s"node send reports without nodeConfigId but the oldest configId (${oldestConfigId.configId.value}) expired since ${oldestExpiration})"
            , UnexpectedNoVersion(t, Some(oldestConfigId), oldestExpiration, currentConfig, currentExpiration)
            )
          } else {
            val currentConfigId = configs.maxBy( _.creation.getMillis )
            val expirationTime = currentConfigId.creation.plus(updateValidityDuration)
            if(expirationTime.isBefore(t)) {
              runType(s"node should have sent reports for configId ${currentConfigId.configId.value} before ${expirationTime} but got a report at ${t} without any configId"
              , NoReportInInterval(currentConfigId)
              )
            } else {
              runType(s"waiting for node to send reports for configId ${currentConfigId.configId.value} before ${expirationTime} (last run at ${t} didn't have any configId"
              , Pending(currentConfigId, Some((t, oldestConfigId)), expirationTime, missingReportType)
              )
            }
          }
        }

      //the run has a nodeConfigId: nominal case
      case (AgentRun(AgentRunId(_, t), Some(rv), _, _), Some(configs)) =>
        if(configs.isEmpty) {
          //error: we have a MISSING config id. Contrary to the case where any config id is missing
          //for the node, here we have a BAD id.
          runType("nodeId exists in DB but has no version (due to cleaning?)", NoExpectedReport(t, Some(rv)))
        } else {
          configs.find { i => i.configId == rv} match {
            case None =>
              //it's a bad version, but we have config id in DB => likelly a corruption on node
              val expectedVersion = configs.maxBy( _.creation.getMillis )
              //expirationTime is the date after which we must have gotten a report for that version
              val expirationTime = expectedVersion.creation.plus(updateValidityDuration)

              runType(s"nodeId exists in DB and has configId, expected configId is ${expectedVersion.configId.value}, but ${rv.value} was not found (node corruption?)",
                  UnexpectedUnknowVersion(t, rv, expectedVersion, expirationTime)
              )

            case Some(v) => //nominal case !
              v.endOfLife match {
                case None =>
                  //nominal (bis)! The node is answering to current config !
                  val expirationTime = t.plus(runValidityDuration)
                  if(expirationTime.isBefore(timeToCompareTo)) {
                    runType(s"Last run at ${t} is for the correct configId ${v.configId.value} but a new one should have been sent before ${expirationTime}"
                    , NoReportInInterval(v)
                    )
                  } else { //nominal case
                    runType(s"Last run at ${t} is for the correct configId ${v.configId.value} and not expired, compute compliance"
                    , ComputeCompliance(t, v, expirationTime, missingReportType)
                    )
                  }

                case Some(eol) =>
                  //check if the run is not too old for the version, i.e if endOflife + grace is before run
                  val currentConfigId = configs.maxBy( _.creation.getMillis )

                  // a more recent version exists, so we are either awaiting reports
                  // for it, or in some error state (completely unexpected version or "just" no report
                  val eolExpiration = eol.plus(updateValidityDuration)
                  val expirationTime = currentConfigId.creation.plus(updateValidityDuration)
                  if(eolExpiration.isBefore(t)) {
                    //we should have had a more recent run
                    runType(s"node sent reports at ${t} for configId ${rv.value} (which expired at ${eol}) but should have been for configId ${currentConfigId.configId.value}"
                    , UnexpectedVersion(t, Some(v), eolExpiration, currentConfigId, expirationTime)
                    )
                  } else {
                    if(expirationTime.isBefore(timeToCompareTo)) {
                      runType(s"last run at ${t} was for expired configId ${rv.value} and no report received for current configId ${currentConfigId.configId.value} (one was expected before ${expirationTime})"
                      , NoReportInInterval(currentConfigId)
                      )
                    } else {
                      //standard case: we changed version and are waiting for a run with the new one.
                      runType(s"last run at ${t} was for expired configId ${rv.value} and no report received for current configId ${currentConfigId.configId.value}, but ${timeToCompareTo} is before expiration time ${expirationTime}, Pending"
                      , Pending(currentConfigId, Some((t, v)), eolExpiration, missingReportType)
                      )
                    }
                  }
              }
          }
        }
    }
  }

  /**
   * This is the main entry point to get the detailed reporting
   * It returns a Sequence of NodeStatusReport which gives, for
   * each node, the status and all the directives associated.
   *
   * The contract is to give to that function a list of expected
   * report for an unique given node
   *
   */
  def getNodeStatusReports(
      nodeId               : NodeId
      // run info: if we have a run, we have a datetime for it
      // and perhaps a configId
    , runInfo              : RunAndConfigInfo
      // this is needed expected reports given for the node.
    , expectedReports      : Map[NodeConfigId, Map[SerialedRuleId, RuleNodeExpectedReports]]
      // reports we get on the last know run
    , agentExecutionReports: Seq[Reports]
  ) : (NodeId, (RunAndConfigInfo, Set[RuleNodeStatusReport])) = {

    //a method to get the expected reports for the given configId and log a debug message is none
    //are found - because they really should have
    def getExpectedReports(configId: NodeConfigId): Map[SerialedRuleId, RuleNodeExpectedReports] = {
      expectedReports.get(configId) match {
        case None => //that should not happen
          logger.error(s"Error when getting expected reports for node: '${nodeId.value}' and config id: '${configId.value}' even if they should be there. It is likelly a bug.")
          Map()
        case Some(x) => x
      }
    }

    def buildUnexpectedVersion(runTime: DateTime, runVersion: Option[NodeConfigIdInfo], runExpiration: DateTime, expectedVersion: NodeConfigIdInfo, expectedExpiration: DateTime, nodeStatusReports: Seq[ResultReports]) = {
        //mark all report of run unexpected,
        //all expected missing
        buildRuleNodeStatusReport(
            MergeInfo(nodeId, Some(runTime), Some(expectedVersion.configId), expectedExpiration)
          , getExpectedReports(expectedVersion.configId)
          , MissingReportType
        ) ++
        buildUnexpectedReports(MergeInfo(nodeId, Some(runTime), runVersion.map(_.configId), runExpiration), nodeStatusReports)
    }

    //only interesting reports: for that node, with a status
    val nodeStatusReports = agentExecutionReports.collect{ case r: ResultReports if(r.nodeId == nodeId) => r }

    ComplianceDebugLogger.node(nodeId).trace(s"Computing compliance for node ${nodeId.value} with: [${runInfo.toLog}]")

    val ruleNodeStatusReports = runInfo match {

      case ReportsDisabledInInterval(expectedConfig) =>
        ComplianceDebugLogger.node(nodeId).trace(s"Compliance mode is ${ReportsDisabled.name}, so we don't have to try to merge/compare with expected reports")
        buildRuleNodeStatusReport(
            //these reports don't really expires - without change, it will
            //always be the same.
            MergeInfo(nodeId, None, Some(expectedConfig.configId), END_OF_TIME)
          , getExpectedReports(expectedConfig.configId)
          , DisabledReportType
        )

      case ComputeCompliance(lastRunDateTime, expectedConfigId, expirationTime, missingReportStatus) =>
        ComplianceDebugLogger.node(nodeId).trace(s"Using merge/compare strategy between last reports from run at ${lastRunDateTime} and expect reports ${expectedConfigId.toLog}")
        mergeCompareByRule(
            MergeInfo(nodeId, Some(lastRunDateTime), Some(expectedConfigId.configId), expirationTime)
          , nodeStatusReports
          , getExpectedReports(expectedConfigId.configId)
          , getExpectedReports(expectedConfigId.configId)
          , missingReportStatus
        )

      case Pending(expectedConfig, optLastRun, expirationTime, missingReportStatus) =>
        optLastRun match {
          case None =>
            ComplianceDebugLogger.node(nodeId).trace(s"Node is Pending with no reports from a previous run, everything is pending")
            // we don't have previous run, so we can simply say that all component in node are Pending
            buildRuleNodeStatusReport(
                MergeInfo(nodeId, None, Some(expectedConfig.configId), expirationTime)
              , getExpectedReports(expectedConfig.configId)
              , PendingReportType
            )

          case Some((runTime, runConfigId)) =>
            /*
             * In that case, we need to compute the status of all component in the previous run,
             * then keep these result for component in the new expected config and for
             * component in new expected config BUT NOT is the one for which we have the run,
             * set pending.
             */
            ComplianceDebugLogger.node(nodeId).trace(s"Node is Pending with reports from previous run, using merge/compare strategy between last reports from run ${runConfigId.toLog} and expect reports ${expectedConfig.toLog}")
            mergeCompareByRule(
                MergeInfo(nodeId, Some(runTime), Some(expectedConfig.configId), expirationTime)
              , nodeStatusReports
              , getExpectedReports(runConfigId.configId)
              , getExpectedReports(expectedConfig.configId)
              , missingReportStatus
            )
        }

      case NoReportInInterval(expectedConfigId) =>
        ComplianceDebugLogger.node(nodeId).trace(s"Node didn't received reports recently, status depend of the compliance mode and previous report status")
        buildRuleNodeStatusReport(
            //these reports don't really expires - without change, it will
            //always be the same.
            MergeInfo(nodeId, None, Some(expectedConfigId.configId), END_OF_TIME)
          , getExpectedReports(expectedConfigId.configId)
          , NoAnswerReportType
        )

      case UnexpectedVersion(runTime, Some(runVersion), runExpiration, expectedVersion, expectedExpiration) =>
        ComplianceDebugLogger.node(nodeId).warn(s"Received a run at ${runTime} for node '${nodeId.value}' with configId '${runVersion.configId.value}' but that node should be sending reports for configId ${expectedVersion.configId.value}")
        buildUnexpectedVersion(runTime, Some(runVersion), runExpiration, expectedVersion, expectedExpiration, nodeStatusReports)

      case UnexpectedNoVersion(runTime, Some(runVersion), runExpiration, expectedVersion, expectedExpiration) => //same as unextected, different log
        ComplianceDebugLogger.node(nodeId).warn(s"Received a run at ${runTime} for node '${nodeId.value}' without any configId but that node should be sending reports for configId ${expectedVersion.configId.value}")
        buildUnexpectedVersion(runTime, Some(runVersion), runExpiration, expectedVersion, expectedExpiration, nodeStatusReports)

      case UnexpectedUnknowVersion(runTime, runId, expectedVersion, expectedExpiration) => //same as unextected, different log
        ComplianceDebugLogger.node(nodeId).warn(s"Received a run at ${runTime} for node '${nodeId.value}' configId '${runId.value}' which is not known by Rudder, and that node should be sending reports for configId ${expectedVersion.configId.value}")
        buildUnexpectedVersion(runTime, None, runTime, expectedVersion, expectedExpiration, nodeStatusReports)

      case NoExpectedReport(runTime, optConfigId) =>
        // these reports where not expected
        ComplianceDebugLogger.node(nodeId).warn(s"Node '${nodeId.value}' sent reports for run at '${runInfo}' (with ${
          optConfigId.map(x => s" configuration ID: '${x.value}'").getOrElse(" no configuration ID")
        }). No expected configuration matches these reports.")
        buildUnexpectedReports(MergeInfo(nodeId, Some(runTime), optConfigId, END_OF_TIME), nodeStatusReports)

      case NoRunNoExpectedReport =>
        /*
         * Really, this node exists ? Shouldn't we just declare Ragnarök at that point ?
         */
        ComplianceDebugLogger.node(nodeId).warn(s"Can not get compliance for node with ID '${nodeId.value}' because it has no configuration id initialised nor sent reports (node just added ?)")
        Set[RuleNodeStatusReport]()

    }

    (nodeId, (runInfo, ruleNodeStatusReports))
  }

  /**
   * That method only take care of the low level logic of comparing
   * expected reports with actual reports rule by rule. So we expect
   * that something before took care of all the macro-,node-related-states
   * (pending, non compatible version, etc).
   *
   * In that method, if we don't have a report for an expected component, it
   * only can be a missing report (since we have all the reports of the run
   * for the node), and if we have reports for other components than the one
   * expected, they are unexpected.
   *
   * We have a two level comparison to do:
   * - the expected reports corresponding to the run
   *   => we actually do the comparison component by component
   * - the expected reports not in the previous set
   *   => the get the missingReport status (pending, success, missing
   *      depending of the time and compliance mode)
   *
   * The "diff" is done at the rule level, what means that, for example,
   * a pending state will be applied to the whole rule, even if only
   * one component changed on it (for ex, we just added an user).
   *
   * That choice can be made more precise latter but for now it is linked
   * to the "serial" of rule.
   */
  private[reports] def mergeCompareByRule(
      mergeInfo                      : MergeInfo
      // only report for that nodeid, of type ResultReports,
      // for the correct run, for the correct version
    , executionReports               : Seq[ResultReports]
    , expectedReportsForLastRun      : Map[SerialedRuleId, RuleNodeExpectedReports]
    , expectedReportsForCurrentConfig: Map[SerialedRuleId, RuleNodeExpectedReports]
      // the status to use for ACTUALLY missing reports, i.e for reports for which
      // we have a run but are not here. Basically, it's "missing" when on
      // full compliance and "success" when on changes only.
    , missingReportStatus            : ReportType
  ): Set[RuleNodeStatusReport] = {

    val complianceForRun: Map[SerialedRuleId, RuleNodeStatusReport] = (for {
      (   SerialedRuleId(ruleId, serial)
        , expectedReport       ) <- expectedReportsForLastRun
      directiveStatusReports =  {
                                   //here, we had at least one report, even if it not a ResultReports (i.e: run start/end is meaningful

                                   val reportsForThatNodeRule: Seq[ResultReports] = executionReports.filter( _.ruleId == ruleId)

                                   val (goodReports, badReports) =  reportsForThatNodeRule.partition(r => r.serial == serial)

                                   val reports = goodReports.groupBy(x => (x.directiveId, x.component) )

                                   val expectedComponents = (for {
                                     directive <- expectedReport.directives
                                     component <- directive.components
                                   } yield {
                                     ((directive.directiveId, component.componentName), component)
                                   }).toMap

                                   /*
                                    * now we have three cases:
                                    * - expected component without reports => missing (modulo changes only interpretation)
                                    * - reports without expected component => unknown
                                    * - both expected component and reports => check
                                    */
                                   val reportKeys = reports.keySet
                                   val expectedKeys = expectedComponents.keySet
                                   val okKeys = reportKeys.intersect(expectedKeys)

                                   val missing = expectedComponents.filterKeys(k => !reportKeys.contains(k)).map { case ((d,_), c) =>
                                     DirectiveStatusReport(d, Map(c.componentName ->
                                       /*
                                        * Here, we group by unexpanded component value, not expanded one. We want in the end:
                                        * -- edit file  ## component name
                                        * --- /tmp/${rudder.node.ip} ## component value
                                        * ----- /tmp/ip1 ## report 1
                                        * ----- /tmp/ip2 ## report 2
                                        * Not:
                                        * -- edit file
                                        * --- /tmp/ip1
                                        * ----- /tmp/ip1
                                        * --- /tmp/ip2
                                        * ----- /tmp/ip2
                                        */
                                       ComponentStatusReport(c.componentName, c.groupedComponentValues.map { case(v,u) => (u ->
                                         ComponentValueStatusReport(v, u, MessageStatusReport(missingReportStatus, "") :: Nil)
                                       )}.toMap)
                                     ))
                                   }

                                   //unexpected contains the one with unexpected key and all non matching serial/version
                                   val unexpected = buildUnexpectedDirectives(
                                       reports.filterKeys(k => !expectedKeys.contains(k)).values.flatten.toSeq ++
                                       badReports
                                   )

                                   val expected = okKeys.map { k =>
                                     DirectiveStatusReport(k._1, Map(k._2 ->
                                       checkExpectedComponentWithReports(expectedComponents(k), reports(k), missingReportStatus)
                                     ))
                                   }

                                   missing ++ unexpected ++ expected
                                }
    } yield {
      (
          SerialedRuleId(ruleId, serial)
        , RuleNodeStatusReport(
              mergeInfo.nodeId
            , ruleId
            , serial //can not be empty because of groupBy
            , mergeInfo.run
            , mergeInfo.configId
            , DirectiveStatusReport.merge(directiveStatusReports)
            , mergeInfo.expirationTime
          )
      )
    }).toMap

    //now, for all current expected reports, choose between the computed value and the default one

    // note: isn't there something specific to do for unexpected reports ? Keep them all ?

    val nil = Seq[RuleNodeStatusReport]()
    val (computed, newStatus) = ((nil, nil)/: expectedReportsForCurrentConfig) { case ( (c,n), (k, expectedReport)) =>
      complianceForRun.get(k) match {
        case None => //the whole rule is new!
          //here, the reports are ACTUALLY pending, not missing.
          val x = buildRuleNodeStatusReport(mergeInfo, Map(k -> expectedReport), PendingReportType)
          (c, n++x)
        case Some(complianceReport) => //use the already computed compliance
          (c:+complianceReport, n)
      }
    }

    ComplianceDebugLogger.node(mergeInfo.nodeId).trace(s"Compute compliance for node ${mergeInfo.nodeId.value} using: rules for which compliance is based on run reports: ${
      computed.map { x => s"[${x.ruleId.value}->${x.serial}]"}.mkString("")
    };"+s" rule updated since run: ${
      newStatus.map { x => s"${x.ruleId.value}->${x.serial}"}.mkString("[", "][", "]")
    }")

    (computed ++ newStatus).toSet
  }

  private[this] def getUnexpanded(seq: Seq[Option[String]]): Option[String] = {
    val unexpanded = seq.toSet
    if(unexpanded.size > 1) {
      logger.debug("Several same looking expected component values have different unexpanded value, which is not supported: " + unexpanded.mkString(","))
    }
    unexpanded.head
  }

  private[this] def buildUnexpectedReports(mergeInfo: MergeInfo, reports: Seq[Reports]): Set[RuleNodeStatusReport] = {
    reports.groupBy(x => (x.ruleId, x.serial)).map { case ((ruleId, serial), seq) =>
      RuleNodeStatusReport(
          mergeInfo.nodeId
        , ruleId
        , serial //can not be empty because of groupBy
        , mergeInfo.run
        , mergeInfo.configId
        , DirectiveStatusReport.merge(buildUnexpectedDirectives(seq))
        , mergeInfo.expirationTime
      )
    }.toSet
  }

  /**
   * Build unexpected reports for the given reports
   */
  private[this] def buildUnexpectedDirectives(reports: Seq[Reports]): Seq[DirectiveStatusReport] = {
    reports.map { r =>
      DirectiveStatusReport(r.directiveId, Map(r.component ->
        ComponentStatusReport(r.component, Map(r.keyValue ->
          ComponentValueStatusReport(r.keyValue, r.keyValue, MessageStatusReport(UnexpectedReportType, r.message) :: Nil)
        )))
      )
    }
  }

  private[reports] def buildRuleNodeStatusReport(
      mergeInfo      : MergeInfo
    , expectedReports: Map[SerialedRuleId, RuleNodeExpectedReports]
    , status         : ReportType
    , message        : String = ""
  ): Set[RuleNodeStatusReport] = {
    expectedReports.map { case (SerialedRuleId(ruleId, serial), expected) =>
      val d = expected.directives.map { d =>
        DirectiveStatusReport(d.directiveId,
          d.components.map { c =>
            (c.componentName, ComponentStatusReport(c.componentName,
              c.groupedComponentValues.map { case(v,uv) =>
                (uv, ComponentValueStatusReport(v, uv, MessageStatusReport(status, "") :: Nil) )
              }.toMap
            ))
          }.toMap
        )
      }
      RuleNodeStatusReport(
          mergeInfo.nodeId
        , ruleId
        , serial
        , mergeInfo.run
        , mergeInfo.configId
        , DirectiveStatusReport.merge(d)
        , mergeInfo.expirationTime
      )
    }.toSet
  }

  /**
   * Allows to calculate the status of component for a node.
   * We don't deal with interpretation at that level,
   * in particular regarding the not received / etc status, we
   * simply put "no answer" for each case where we don't
   * have an actual report corresponding to the expected one.
   *
   * We do deal with component value type, with 3 big kind of component value:
   * - the case with no component value. We just have a component key and don't expect any value.
   * - the with a component key that is fully know at generation time. It may be either a hard coded
   *   value, like the path "/etc/fstab" when editing a file, or a Rudder parameter expanded at generation
   *   time, like "${rudder.node.hostname}".
   * - the runtime-only parameter (for now, only cfe variable), that can't be known until the agent
   *   actually runs.
   *   This last kind is problematic for now, because we don't have any reliable mean to merge it
   *   with an expected report: on the expected side, we have something like "${sys.arch}", on the
   *   report side, we only have something like "x64". So if several values of that kind exists for
   *   a component, we can only guess and hope (I let you play with two file edit on "/tmp${sys.fstab}" and
   *   on "/tmp/${sys.arch}/${sys.hostname}" for example).
   * The visibility is for allowing tests
   */
  private[reports] def checkExpectedComponentWithReports(
      expectedComponent: ComponentExpectedReport
    , filteredReports  : Seq[Reports]
    , noAnswerType     : ReportType
  ) : ComponentStatusReport = {

    // First, filter out all the not interesting reports
    // (here, interesting means non log, info, etc)
    val purgedReports = filteredReports.filter(x => x.isInstanceOf[ResultReports])

    // build the list of unexpected ComponentValueStatusReport
    // i.e value
    val unexpectedStatusReports = {
      val unexpectedReports = getUnexpectedReports(
          expectedComponent.componentsValues.toList
        , purgedReports
      )
      unexpectedReports.foreach { r =>
        ComplianceDebugLogger.node(r.nodeId).warn(s"Unexpected report for Directive '${r.directiveId.value}', Rule '${r.ruleId.value}' generated on '${r.executionTimestamp}' "+
            s"on node '${r.nodeId.value}', Component is '${r.component}', keyValue is '${r.keyValue}'. The associated message is : ${r.message}"
        )
      }
      for {
        unexpectedReport <- unexpectedReports
      } yield {
        ComponentValueStatusReport(
           unexpectedReport.keyValue
         , unexpectedReport.keyValue
         , List(MessageStatusReport(UnexpectedReportType, unexpectedReport.message))
        )
      }

    }

    /*
     * The actual data model for the three kinds of component values.
     * We group values by there unexpanded value, so that's why we have
     * Seq in the right hand side. Of course, we only have one None.
     * CFEngine vars can't really be grouped. We may want to group them
     * by "most large pattern matching that value", so that "/tmp/.*" also
     * handle "/tmp/.* /.*", but I have no idea how to handle that.
     * Matching pattern with each other ?
     */
    case class ValueKind(
        none  : Seq[String] = Seq()
      , simple: Map[String, Seq[String]] = Map()
      , cfeVar: List[(String, Pattern)] = List()
    )

    val componentMap = expectedComponent.groupedComponentValues
    val valueKind = (ValueKind() /:componentMap) {
      case (kind,  (v, DEFAULT_COMPONENT_KEY)) => kind.copy(none = v +: kind.none )
      case (kind,  (value, unexpandedValue)) =>
        value match {
          case matchCFEngineVars(_) =>
            val pattern = Pattern.compile(replaceCFEngineVars(value))
            kind.copy(cfeVar = (unexpandedValue, pattern) :: kind.cfeVar)
          case _ => kind.copy(simple = kind.simple + ((unexpandedValue, value +: kind.simple.getOrElse(unexpandedValue, Seq()))))
        }
    }

    // Regroup all None value into None component Value
    // There should only one None report per component
    val (noneValue, noneReports) = {
      if(valueKind.none.isEmpty) {
      (None, Seq())
      } else {
        val reports = purgedReports.filter(r => r.keyValue == DEFAULT_COMPONENT_KEY)
        ( Some(buildComponentValueStatus(
              DEFAULT_COMPONENT_KEY
            , reports
            , unexpectedStatusReports.isEmpty
            , valueKind.none.size
            , noAnswerType
          ))
        , reports
        )
      }
    }

    // Create a simpleValue for each unexpandedValue, with all reports matching expanded values
    val (simpleValues, simpleReports) = {
      val result = for {
        (unexpandedValue, values) <- valueKind.simple
      } yield {
        // we find the reports matching these values, but take
        // at most the number of values if there is also cfevars
        // because the remaining values may be for cfengine vars
        val reports = {
          val possible = purgedReports.filter(r => values.contains(r.keyValue))
          if(valueKind.cfeVar.size > 0) {
            possible.take(values.size)
          } else {
            possible
          }
        }
        val status = buildComponentValueStatus(
            unexpandedValue
          , reports
          , unexpectedStatusReports.isEmpty
          , values.size
          , noAnswerType
        )
        (status, reports)
      }
      (result.keys,result.values.flatten)
    }

    // Remove all already parsed reports so we only look in remaining reports for Cfengine variables
    val usedReports = noneReports ++ simpleReports
    val remainingReports = purgedReports.diff(usedReports)

    // Find what reports matche what cfengine variables

    val (cfeVarValues, lastReports) = extractCFVarsFromReports(valueKind.cfeVar, remainingReports.toList, noAnswerType)

    // Finally, if we still got some reports, generate an unexpected report.
    /*
     * Here, we don't havve any mean to know if the unexpected comes from a CFEngine variable or not.
     * It could be a constant value coming from nowhere as much as a value coming from a badly replaced
     * CFEngine var.
     */
    val lastUnexpected = lastReports.groupBy(_.keyValue).map{
      case (value, reports) =>
        val messageReports = reports.map(r => MessageStatusReport(UnexpectedReportType, r.message)).toList
        ComponentValueStatusReport(value, value, messageReports)
    }

    /*
     * Here, we want to merge values BUT also to ballon to report type to the worst in
     * a list of messages, so that a component value partially in repaired and success
     * is view in repaired.
     * This is ok to do so here, because we are looking for the messages of a
     * same component value (for a node, for a rule, for a directive), so only
     * for component value with a cardinality > 1, which quite rare.
     */
    val componentValues = {
      val cv = ComponentValueStatusReport.merge(unexpectedStatusReports ++ noneValue ++ simpleValues ++ cfeVarValues ++ lastUnexpected)
      cv.mapValues { x =>
        val worst = ReportType.getWorseType(x.messages.map(_.reportType))
        val messages = x.messages.map(m => m.copy(reportType = worst))
        x.copy(messages = messages)
      }
    }

    ComponentStatusReport(
        expectedComponent.componentName
      , componentValues
    )
  }


  /*
   * Recursively look into remaining reports for CFEngine variables,
   * values are accumulated and we remove reports while we use them.
   *
   * The first matching pattern is used as the correct one, so
   * they must be sorted from the most specific to the less one
   * if you don't want to see things like in #7758 happen
   *
   * The returned reports are the one that are not matches by
   * any cfengine variable value
   *
   * Visibility is for test
   *
   */
  private[reports] def extractCFVarsFromReports (
      cfengineVars: List[(String, Pattern)]
    , allReports  : List[Reports]
    , noAnswerType: ReportType
  ) : (Seq[ComponentValueStatusReport], Seq[Reports]) = {

    /*
     * So, we don't have any simple, robust way to sort
     * reports or patterns to be sure that we are not
     * introducing loads of error case.
     *
     * So, we are going to test all patterns on all regex,
     * and find the combination with the most report used.
     *
     * One optimisation thought: when a pattern match exactly
     * one report, we can prune that couple for remaining
     * pattern tests.
     *
     * It is known that that way of testing is quadratic and
     * will takes A LOT OF TIME for big input.
     * The rationnal to do it none the less is:
     * - the quadratic nature is only value with CFEngine params,
     * - the time remains ok below 20 or so entries,
     * - the pruning helps
     * - there is a long terme solution with the unique
     *   identification of a report for a component (and so no more
     *   guessing)
     *
     * Given all that, if an user fall in the quadratic nature, we
     * can workaround the problem by splitting his long list of
     * problematic patterns into two directives, with a little
     * warn log message.
     */


    /*
     * From a list of pattern to match, find:
     * - the pattern with exactly 0 or 1 matching reports and transform
     *   them into ComponentValueStatusReport,
     * - the list of possible reports for patterns matching several reports
     *   when they are tested (but as pruning go, at the end they can have
     *   far less choice)
     * - the list of reports not matched by exactly 1 patterns (i.e 0 or more than 2)
     */
    def recExtract(
        cfVars      : List[(String, Pattern)]
      , values      : List[ComponentValueStatusReport]
      , multiMatches: List[((String, Pattern), Seq[Reports])]
      , reports     : List[Reports]
    ): (List[ComponentValueStatusReport], List[((String, Pattern), Seq[Reports])], List[Reports]) = {
      //utility: given a list of (pattern, option[report]), recursively construct the list of component value by
      //looking for used reports: if the option(report) exists and is not used, we have a value, else a missing
      def recPruneSimple(
          choices: List[((String, Pattern), Option[Reports])]
        , usedReports: Set[Reports]
        , builtValues: List[ComponentValueStatusReport]
      ): (Set[Reports], List[ComponentValueStatusReport]) = {
         choices match {
           case Nil => (usedReports, builtValues)
           case ((unexpanded, _), optReport) :: tail =>
             optReport match {
               case Some(report) if(!usedReports.contains(report)) => //youhou, a new value
                 val v = ComponentValueStatusReport(unexpanded, unexpanded, List(MessageStatusReport(ReportType(report), report.message)))
                 recPruneSimple(tail, usedReports + report, v :: builtValues)
               case _ =>
                 //here, either we don't have any reports matching the pattern, or the only possible reports was previously used => missing value for pattern
                 val v = ComponentValueStatusReport(unexpanded, unexpanded, List(MessageStatusReport(noAnswerType, None)))
                 recPruneSimple(tail, usedReports, v :: builtValues)
             }
         }
      }


      //prune the multiMatches with one reports, getting a new multi, a new list of matched reports, a new list of final values
      def recPruneMulti(
          pruneReports: List[Reports]
        , multi: List[((String, Pattern), Seq[Reports])]
        , values: List[ComponentValueStatusReport]
        , used: List[Reports]
      ): (List[Reports], List[((String, Pattern), Seq[Reports])], List[ComponentValueStatusReport]) = {

        pruneReports match {
          case Nil => (used, multi, values)
          case seq =>
            val m = multi.map { case (x, r) => (x, r.diff(seq)) }
            val newBuildableValues = m.collect { case (x, r) if(r.size <= 1) => (x, r.headOption) }
            val (newUsedReports, newValues) = recPruneSimple(newBuildableValues, Set(), List())

            val newMulti = m.collect { case (x, r) if(r.size > 1) => (x, r) }

            recPruneMulti(newUsedReports.toList, newMulti, values ++ newValues, used ++ newUsedReports)
        }
      }

      /*
       * ********* actually call the logic *********
       */

      cfVars match {
        case Nil =>
          // Nothing to do, time to return results
          (values, multiMatches, reports)
        case (unexpanded, pattern) :: remainingPatterns =>
          //collect all the reports being matched by that pattern
          val matchingReports = reports.collect { case(r) if(pattern.matcher(r.keyValue).matches) => r }

          matchingReports match {
            case Nil =>
              // The pattern is not found in the reports, Create a NoAnswer
              val v = ComponentValueStatusReport(unexpanded, unexpanded, List(MessageStatusReport(noAnswerType, None)))
              recExtract(remainingPatterns, v :: values, multiMatches, reports)
            case report :: Nil =>
              // we have exactly one report for the pattern, best matches possible => we are sure to take that one,
              // so remove report from both available reports and multiMatches possible case
              val v = ComponentValueStatusReport(unexpanded, unexpanded, List(MessageStatusReport(ReportType(report), report.message)))
              val (newUsedReports, newMulti, newValues) = if(multiMatches.size > 0) {
                recPruneMulti(List(report), multiMatches, List(), List())
              } else {
                (Nil, Nil, Nil)
              }
              recExtract(remainingPatterns, v :: newValues ::: values, newMulti, reports.diff( report :: newUsedReports))

            case multi => // the pattern matches several reports, keep them for latter processing
              recExtract(remainingPatterns, values, ((unexpanded, pattern), multi) :: multiMatches, reports)
          }
      }
    }

    /*
     * Now, we can still have some choices where several patterns matches the same sets of reports, typically:
     * P1 => A, B
     * P2 => B, C
     * P3 => A, C
     * We would need to find P1 => A, P2 => B, P3 => C (and not: P1 => A, P2 => C, P3 => ???)
     * But the case is suffiently rare to ignore it, and just take one report at random for each
     *
     * Return the list of chosen values with the matching used reports.
     */
    def recProcessMulti(
        choices: List[((String, Pattern), Seq[Reports])]
      , usedReports: List[Reports]
      , values: List[ComponentValueStatusReport]
    ): (List[ComponentValueStatusReport], List[Reports]) = {
      choices match {
        case Nil => (values, usedReports)
        case ((unexpanded, _), allReports) :: tail =>
          (allReports.diff(usedReports)) match {
            case Nil => //too bad, perhaps we could have chosen better
              val v =  ComponentValueStatusReport(unexpanded, unexpanded, List(MessageStatusReport(noAnswerType, None)))
              recProcessMulti(tail, usedReports, v :: values)
            case report :: _ =>
              val v = ComponentValueStatusReport(unexpanded, unexpanded, List(MessageStatusReport(ReportType(report), report.message)))
              recProcessMulti(tail, report :: usedReports, v :: values)
          }
      }
    }

    if(cfengineVars.size > 0) {
      //actually do the process
      val (values, multiChoice, remainingReports) = recExtract(cfengineVars, List(), List(), allReports)
      //chose for multi
      val (newValues, newUsedReports) = recProcessMulti(multiChoice, Nil, Nil)

      //return the final list of build values and remaining reports
      (values ::: newValues, remainingReports.diff(newUsedReports))
    } else {
      (List(), allReports)
    }
  }

  /*
   * An utility method that fetches the proper status and messages
   * of a component value.
   *
   * Please notice the fact that there is only one "current value", which is expected
   * to be the "unexpanded" one, and that so, the expanded value is lost.
   *
   */
  private[this] def buildComponentValueStatus(
      currentValue        : String
    , filteredReports     : Seq[Reports]
    , noUnexpectedReports : Boolean
    , cardinality         : Int
    , noAnswerType        : ReportType
  ) : ComponentValueStatusReport = {

    val messageStatusReports = {
       filteredReports.filter( x => x.isInstanceOf[ResultErrorReport]).size match {
          case i if i > 0 =>
            filteredReports.map(r => MessageStatusReport(ErrorReportType, r.message)).toList
          case _ => {
            filteredReports.size match {
              /* Nothing was received at all for that component so : No Answer or Pending */
              case 0 if noUnexpectedReports =>
                MessageStatusReport(noAnswerType, None) :: Nil
              case x if(x <= cardinality) =>
                filteredReports.map { r => MessageStatusReport(ReportType(r), r.message) }.toList ++
                /* We need to complete the list of correct with missing, if some are missing */
                (x until cardinality).map( i => MessageStatusReport(MissingReportType, s"[Missing report #${i}]")).toList
              //check if cardinality is ok
              case x if(x > cardinality) =>
                filteredReports.map { r => MessageStatusReport(UnexpectedReportType, r.message) }.toList
            }
          }
        }
    }

    /*
     * Here, we assume the loss of the actual value and only keep the
     * unexpanded value. That means that we don't have any way to see after
     * that what reports were linked to what value (safe by the content of the
     * report itself if it has the value.).
     *
     * Note: we DO use expanded values to merge with reports.
     */
    ComponentValueStatusReport(
        currentValue
      , currentValue
      , messageStatusReports
    )
  }

  /**
   * Retrieve all the reports that should not be there (due to
   * keyValue not present)
   */
  private[this] def getUnexpectedReports(
      keyValues: List[String]
    , reports  : Seq[Reports]
  ) : Seq[Reports] = {

    val isExpected = (head:String, s:String) => head match {
      case matchCFEngineVars(_) =>
        val matchableExpected = replaceCFEngineVars(head)
        s.matches(matchableExpected)
      case x => x == s
    }

    keyValues match {
      case Nil          => reports
      case head :: tail =>
        getUnexpectedReports(tail, reports.filterNot(r => isExpected(head, r.keyValue)))
    }
  }

}
