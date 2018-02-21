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
import org.joda.time._
import com.normation.rudder.domain.logger.ComplianceDebugLogger
import com.normation.rudder.domain.logger.ComplianceDebugLogger._
import com.normation.rudder.domain.logger.TimingDebugLogger
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.reports._
import com.normation.rudder.reports._
import com.normation.rudder.reports.execution.AgentRunId
import net.liftweb.common.Loggable
import com.normation.rudder.domain.policies.SerialedRuleId
import com.normation.cfclerk.xmlparsers.CfclerkXmlConstants.DEFAULT_COMPONENT_KEY
import java.util.regex.Pattern

import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.reports.ReportType.BadPolicyMode
import com.normation.rudder.reports.execution.AgentRunWithNodeConfig

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

sealed trait RunAndConfigInfo {
  // a messagine explaining why that run is like that. Not exactly mandatory,
  // but with experience, we saw that it is so much easier to log / debug with it.
  def msg : String
  // a normalized name for serialization
  def name: String
}

sealed trait ErrorNoConfigData extends RunAndConfigInfo

sealed trait ExpectedConfigAvailable extends RunAndConfigInfo {
  def expectedConfig: NodeExpectedReports
}

sealed trait NoReport extends ExpectedConfigAvailable

sealed trait Unexpected extends ExpectedConfigAvailable

sealed trait HasCompliance extends ExpectedConfigAvailable

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
  def lastRunConfigInfo: Option[NodeExpectedReports]
}



/**
 * The type of report to use for missing reports
 * (actually missing reports).
 * Depends of compliance mode.
 */
sealed trait ExpiringStatus extends RunAndConfigInfo {
    def expirationDateTime: DateTime
}

/**
 * We also define two broad kinds of run status: OK (for Pending and
 * ComputeCompliance) or not OK
 */
sealed trait RunKind
object RunKind {
  trait Valid   extends RunKind
  trait Invalid extends RunKind
}


/*
 * Really, that node exists ?
 */
final case class NoRunNoExpectedReport(msg: String) extends ErrorNoConfigData {
  val name = "NoRunNoExpectedReport"
}

/*
 * We don't have the needed configId in the expected
 * table. Either we don't have any config id at all,
 * or we can't find the version matching a run.
 * (it is some weird data lost in the server, or a node
 * not yet initialized)
 */
final case class NoExpectedReport(
    msg            : String
  , lastRunDateTime: DateTime
  , lastRunConfigId: Option[NodeConfigId]
) extends ErrorNoConfigData with RunKind.Invalid {
  val name = "NoExpectedReport"
}

/*
 * No report of interest (either none, or
 * some but too old for our situation)
 */
final case class NoReportInInterval(
    msg            : String
  , expectedConfig : NodeExpectedReports
) extends NoReport with RunKind.Invalid {
  val name = "NoReportsInInterval"
}

/*
 * No report of interest but expected because
 * we are on the correct mode for that
 */
final case class ReportsDisabledInInterval(
    msg            : String
  , expectedConfig : NodeExpectedReports
) extends NoReport with RunKind.Valid {
  val name = "ReportsDisabledInInterval"
}

final case class Pending(
    msg                : String
  , expectedConfig     : NodeExpectedReports
  , optLastRun         : Option[(DateTime, Option[NodeExpectedReports])]
  , expirationDateTime : DateTime
) extends NoReport with ExpiringStatus with RunKind.Valid {
  val name = "Pending"
}

/*
 * the case where we have a version on the run,
 * versions are init in the server for that node,
 * and we don't have a version is an error
 */
final case class UnexpectedVersion(
    msg               : String
  , lastRunDateTime   : DateTime
  , lastRunConfigInfo : Some[NodeExpectedReports]
  , lastRunExpiration : DateTime
  , expectedConfig    : NodeExpectedReports
  , expectedExpiration: DateTime
) extends Unexpected with LastRunAvailable with RunKind.Invalid {
  val name            = "UnexpectedVersion"
  val lastRunConfigId = lastRunConfigInfo.get.nodeConfigId
}

/**
 * A case where we have a run without version,
 * but we really should, because versions are init
 * in the server for that node
 */
final case class UnexpectedNoVersion(
    msg               : String
  , lastRunDateTime   : DateTime
  , lastRunConfigId   : NodeConfigId
  , lastRunExpiration : DateTime
  , expectedConfig    : NodeExpectedReports
  , expectedExpiration: DateTime
) extends Unexpected with LastRunAvailable with RunKind.Invalid {
  val name              = "UnexpectedNoVersion"
  val lastRunConfigInfo = None
}

/**
 * A case where we have a run with a version,
 * but we didn't find it in db,
 * but we really should, because versions are init
 * in the server for that node
 */
final case class UnexpectedUnknowVersion(
    msg               : String
  , lastRunDateTime   : DateTime
  , lastRunConfigId   : NodeConfigId
  , expectedConfig    : NodeExpectedReports
  , expectedExpiration: DateTime
) extends Unexpected with LastRunAvailable with RunKind.Invalid {
  val name              = "UnexpectedUnknowVersion"
  val lastRunConfigInfo = None
}


final case class ComputeCompliance(
    msg                : String
  , lastRunDateTime    : DateTime
  , expectedConfig     : NodeExpectedReports
  , expirationDateTime : DateTime
) extends HasCompliance with LastRunAvailable with ExpiringStatus with RunKind.Valid {
  val name              = "ComputeCompliance"
  val lastRunConfigId   = expectedConfig.nodeConfigId
  val lastRunConfigInfo = Some(expectedConfig)
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
  private[this] final case class MergeInfo(
      nodeId: NodeId
    , run: Option[DateTime]
    , configId: Option[NodeConfigId]
    , expirationTime: DateTime
  )

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
   * How long time a run is valid AFTER AN UPDATE (i.e, not in permanent regime).
   * This is shorter than runValidityTime, because a config update IS a change and
   * force to send reports in all case.
   */
  def updateValidityDuration(runIntervalInfo: ResolvedAgentRunInterval) = runIntervalInfo.interval.plus(GRACE_TIME_PENDING)

  /*
   * How long time a run is valid before receiving any report (but not after an update)
   */
  def runValidityDuration(runIntervalInfo: ResolvedAgentRunInterval, complianceMode: ComplianceMode) = complianceMode.mode match {
    case ChangesOnly =>
      //expires after run*heartbeat period - we need an other run before that.
      val heartbeat = Duration.standardMinutes((runIntervalInfo.interval.getStandardMinutes * runIntervalInfo.heartbeatPeriod ))
      heartbeat.plus(GRACE_TIME_PENDING)
    case FullCompliance | ReportsDisabled =>
      updateValidityDuration(runIntervalInfo)
  }

  /*
   * A data structure with a valide state and its validity interval
   * (actually the uper bound only, the start of the interval is the run exec time)
   */
  final case class RunAndConfigInfoInterval(
      validState  : RunAndConfigInfo with RunKind.Valid
    , expiration  : DateTime
    , invalidState: RunAndConfigInfo with RunKind.Invalid
  )

  /*
   * Method that, given a "run and config info" object, calculates what is the run state
   * given node config info.
   *
   * The method returns either an error (no expected reports, bad config id, no reports at all...)
   * or a datastructure with the Correct state and its validity interval, and the next state after the
   * interval (before interval does not make sense, the begining of the interval is the run execution time)
   */
  def computeNodeRunAndConfigInfo(
      runInfos         : AgentRunWithNodeConfig
    , currentNodeConfig: Option[NodeExpectedReports]
    , nodeConfigIdInfos: Option[Seq[NodeConfigIdInfo]]
  ): Either[RunAndConfigInfo with RunKind.Invalid, RunAndConfigInfoInterval] = {
    implicit val _n = runInfos.agentRunId.nodeId

    (runInfos, currentNodeConfig) match {

      //
      // #1 : What the hell ?
      //      that's not good. Why a run without expected config ?
      //      More over, we group together the cases where we have config for the run, because without
      //      a current expected config, it should be impossible (no way to paired it with that run).
      //      Just log an error for dev.
      case ((AgentRunWithNodeConfig(AgentRunId(_, t), optConfigId, _, _)), None) =>
        if(nodeConfigIdInfos.isDefined) {
          Left(NoExpectedReport("nodeId exists in DB but has no version (due to cleaning?). Need regeneration, no expected report yet.", t, optConfigId.map( _._1)))
        } else {
          Left(NoExpectedReport("nodeId was not found in DB but is sending reports. It is likely a new node which needs regeneration, no expected report yet."
          , t, optConfigId.map( _._1))
          )
        }

      //
      // #2 : run without config ID (neither in it nor found)
      //      no expected config for the run. Why so? At least, a recent config.
      case ((AgentRunWithNodeConfig(AgentRunId(_, t), None, _, _)), Some(currentConfig)) =>
        /*
         * Here, we want to check two things:
         * - does the run should have contain a config id ?
         *   It should if the oldest config was created a long time ago,
         *   and if it is the case most likelly the node can't get
         *   its updated promises.
         *   The logic is that only nodes with initial promises send reports without a config Id. So
         *   if a node is in that case, it is because it never got genererated promises.
         *   If the first generated promises for that node are beyond the grace period, it means that
         *   the run should have used theses promises, and we have a (DNS) problem because it didn't.
         *
         * - else, we look at the most recent
         *   config and decide between pending / no answer
         *
         * Note: we must use value from current expected config for modes,
         *       because we don't have anything else really.
         */
        val oldestConfigId = nodeConfigIdInfos.getOrElse(Seq(currentConfig.configInfo)).minBy( _.creation.getMillis)
        val oldestExpiration = oldestConfigId.creation.plus(updateValidityDuration(currentConfig.agentRun))

        if(oldestExpiration.isBefore(t) ) {
          //we had a config set a long time ago, then interval+grace time happen, and then
          //we get a run without any config id => the node didn't updated its promises
          Left(UnexpectedNoVersion(s"node send reports without nodeConfigId but the oldest configId (${oldestConfigId.configId.value}) expired since ${oldestExpiration})"
          , t, oldestConfigId.configId, oldestExpiration, currentConfig, oldestExpiration)
          )
        } else {
          val expirationTime = currentConfig.beginDate.plus(updateValidityDuration(currentConfig.agentRun))
          Right(RunAndConfigInfoInterval(
              Pending(s"waiting for node to send reports for configId ${currentConfig.nodeConfigId.value} before ${expirationTime} (last run at ${t} didn't have any configId)"
              , currentConfig, Some((t, None)), expirationTime
              ) //here, "None" even if we have a old run, because we don't have expectedConfig for it.
            , expirationTime
            , NoReportInInterval(s"node should have sent reports for configId ${currentConfig.nodeConfigId.value} before ${expirationTime} but got a report at ${t} without any configId"
              , currentConfig
              )
          ))
        }


      //
      // #3 : run with a version ID !
      //      But no corresponding expected Node. A
      //      And no current one.
      case ((AgentRunWithNodeConfig(AgentRunId(_, t), Some((rv,None)), _, _)), Some(currentConfig)) =>
        //it's a bad version, but we have config id in DB => likelly a corruption on node
        //expirationTime is the date after which we must have gotten a report for the current version
        val expirationTime = currentConfig.beginDate.plus(updateValidityDuration(currentConfig.agentRun))

        Left(UnexpectedUnknowVersion("nodeId exists in DB and has configId, expected configId is ${currentConfig.nodeConfigId.value}, but ${rv.value} was not found (node corruption?)"
        , t, rv, currentConfig, expirationTime
        ))

      //
      // #4 : run with an ID! And a matching expected config! And a current expected config!
      //      So this is the standard case.
      //      We have to check if run version == expected, if it's the case: nominal case.
      //      Else, we need to check if the node version is not too old,
      case ((AgentRunWithNodeConfig(AgentRunId(_, t), Some((rv, Some(runConfig))), _, _)), Some(currentConfig)) =>
        runConfig.endDate match {

          case None =>
            val expirationTime = t.plus(runValidityDuration(currentConfig.agentRun, currentConfig.complianceMode))
            //take care of the potential case where currentConfig != runConfig in the log message
            val invalid = NoReportInInterval(s"Last run at ${t} is for the configId ${runConfig.nodeConfigId.value} but a new one should have been sent for configId ${currentConfig.nodeConfigId.value} before ${expirationTime}"
              , currentConfig
            )

            //nominal case
            //here, we have to verify if the config ids are different, because we can
            //be in the middle of a generation or have a badly closed node configuration on base
            val valid = if(runConfig.nodeConfigId != currentConfig.nodeConfigId) {
              //we changed version and are waiting for a run with the new one.
              Pending(s"last run at ${t} was for previous configId ${runConfig.nodeConfigId.value} and no report received for current configId ${currentConfig.nodeConfigId.value}, but until expiration time ${expirationTime}: Pending"
              , currentConfig, Some((t, Some(runConfig))), expirationTime
              )
            } else {
              //nominal case: the node is answering current config, on time
              ComputeCompliance(s"Last run at ${t} is for the correct configId ${currentConfig.nodeConfigId.value} and not expired, compute compliance"
              , t, currentConfig, expirationTime
              )
            }
            Right(RunAndConfigInfoInterval(valid, expirationTime, invalid))

          case Some(eol) =>
            //check if the run is not too old for the version, i.e if endOflife + grace is before run

            // a more recent version exists, so we are either awaiting reports
            // for it, or in some error state (completely unexpected version or "just" no report
            val eolExpiration = eol.plus(updateValidityDuration(runConfig.agentRun))
            val expirationTime = currentConfig.beginDate.plus(updateValidityDuration(currentConfig.agentRun))
            if(eolExpiration.isBefore(t)) {
              //we should have had a more recent run
              Left(UnexpectedVersion(s"node sent reports at ${t} for configId ${rv.value} (which expired at ${eol}) but should have been for configId ${currentConfig.nodeConfigId.value}"
              , t, Some(runConfig), eolExpiration, currentConfig, expirationTime
              ))
            } else {
              Right(RunAndConfigInfoInterval(
                  //standard case: we changed version and are waiting for a run with the new one.
                  Pending(s"last run at ${t} was for expired configId ${rv.value} and no report received for current configId ${currentConfig.nodeConfigId.value}, but until expiration time ${expirationTime}: Pending"
                  , currentConfig, Some((t, Some(runConfig))), eolExpiration
                  )
                , expirationTime
                , NoReportInInterval(s"last run at ${t} was for expired configId ${rv.value} and no report received for current configId ${currentConfig.nodeConfigId.value} (one was expected before ${expirationTime})"
                  , currentConfig
                  )
              ))
            }
        }
    }
  }


  /*
   * For each node, get the config it has.
   * This method bases its result on THE LAST RUN
   * of each node, and try to discover the run linked information (datetime, config id).
   *
   */
  def computeNodesRunInfo(
      // The set of run associated with ALL requested node.
      //
      // If exists, the last run received for these nodes is coupled with the
      // corresponding expected node configuration for that run, which will allow to know what
      // config option to apply.
      runs              : Map[NodeId, Option[AgentRunWithNodeConfig]]
      // the current expected node configurations for all nodes.
      // This is useful for nodes without runs (ex in no-report mode), node with a run not for the
      // last config (show diff etc). It may be none for ex. when a node was added since last generation
    , currentNodeConfigs: Map[NodeId, Option[NodeExpectedReports]]
      // other config information to allows better reporting on error
    , nodeConfigIdInfos: Map[NodeId, Option[Seq[NodeConfigIdInfo]]]
  ): Map[NodeId, RunAndConfigInfo] = {


    val now = DateTime.now

    runs.map { case (nodeId, optRun) =>
      implicit val _n = nodeId

      //all the problem is to find the correct NodeRunInfo from the optRun and other information
      val nodeRunInfo = optRun match {

        //
        // [I] First case: we don't have (recent) runs for that node
        // Perhaps its a new node (with or without a generation), or are we in ReportsDisabled
        // So we don't have matching expected reports either.
        //
        case None =>
          // Let try to see what is currently expected from that node
          currentNodeConfigs.get(nodeId).flatten match {
            case None =>
              //let's see if the node has ANY config info
              nodeConfigIdInfos.getOrElse(nodeId, None) match {
                case None =>
                  //ok, it's a node without any config (so without runs, of course). Perhaps a new node ?
                  NoRunNoExpectedReport(s"nodeId has no configuration ID version, perhaps it's a new Node?")
                case Some(configs) =>
                  //so, the node has existed at some point, but not now. Strange.
                  NoRunNoExpectedReport("nodeId exists in DB but has no version (due to cleaning, migration, synchro, etc)")
              }

            case Some(currentConfig) =>
              if(currentConfig.complianceMode.mode == ReportsDisabled) { // oh, so in fact it's normal to not have runs!
                ReportsDisabledInInterval(s"compliance mode is set to '${ReportsDisabled.name}', it's ok to not having reports", currentConfig)
              } else { //let's further examine the situation
                val expireTime = currentConfig.beginDate.plus(updateValidityDuration(currentConfig.agentRun))

                if(expireTime.isBefore(now)) {
                  NoReportInInterval("no run (ever or too old)", currentConfig)
                } else {
                  Pending(s"no run (ever or too old), Pending until ${expireTime}"
                  , currentConfig, None, expireTime
                  )
                }
              }
          }

        //
        // [II] Second case: we DO have a run.
        // We need to look if it has an associated expected configuration,
        // and if not try to gather piece of information from elsewhere.
        // Then analyse the consistancy of the result.
        //
        case Some(runInfos) =>
          val nodeId = runInfos.agentRunId.nodeId
          computeNodeRunAndConfigInfo(runInfos, currentNodeConfigs.get(nodeId).flatten, nodeConfigIdInfos.get(nodeId).flatten) match {
            case Left(invalid) => invalid
            case Right(RunAndConfigInfoInterval(valid, expiration, invalid)) =>
              if(now.isBefore(expiration)) {
                valid
              } else {
                invalid
              }
          }
      }

      // log explain compliance if requested by loglevel
      ComplianceDebugLogger.node(nodeId).trace(s"Run config for node ${nodeId.value}: ${nodeRunInfo.name}: ${nodeRunInfo.msg}")

      // now that we finally have the runInfo, returned it coupled with nodeId for final result
      (nodeId, nodeRunInfo)
    }.toMap
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
      // reports we get on the last know run
    , agentExecutionReports: Seq[Reports]
  ) : NodeStatusReport = {

    def buildUnexpectedVersion(runTime: DateTime, runVersion: Option[NodeConfigIdInfo], runExpiration: DateTime, expectedConfig: NodeExpectedReports, expectedExpiration: DateTime, nodeStatusReports: Seq[ResultReports]) = {
        //mark all report of run unexpected,
        //all expected missing
        buildRuleNodeStatusReport(
            MergeInfo(nodeId, Some(runTime), Some(expectedConfig.nodeConfigId), expectedExpiration)
          , expectedConfig
          , ReportType.Missing
        ) ++
        buildUnexpectedReports(MergeInfo(nodeId, Some(runTime), runVersion.map(_.configId), runExpiration), nodeStatusReports)
    }

    //only interesting reports: for that node, with a status
    val nodeStatusReports = agentExecutionReports.collect{ case r: ResultReports if(r.nodeId == nodeId) => r }

    ComplianceDebugLogger.node(nodeId).trace(s"Computing compliance for node ${nodeId.value} with: [${runInfo.toLog}]")

    val t1 = System.currentTimeMillis
    val ruleNodeStatusReports = runInfo match {

      case ReportsDisabledInInterval(_, expectedConfig) =>
        ComplianceDebugLogger.node(nodeId).trace(s"Compliance mode is ${ReportsDisabled.name}, so we don't have to try to merge/compare with expected reports")
        buildRuleNodeStatusReport(
            //these reports don't really expires - without change, it will
            //always be the same.
            MergeInfo(nodeId, None, Some(expectedConfig.nodeConfigId), END_OF_TIME)
          , expectedConfig
          , ReportType.Disabled
        )

      case ComputeCompliance(_, lastRunDateTime, expectedConfig, expirationTime) =>
        ComplianceDebugLogger.node(nodeId).trace(s"Using merge/compare strategy between last reports from run at ${lastRunDateTime} and expect reports ${expectedConfig.toLog}")
        mergeCompareByRule(
            MergeInfo(nodeId, Some(lastRunDateTime), Some(expectedConfig.nodeConfigId), expirationTime)
          , nodeStatusReports
          , expectedConfig
          , expectedConfig
        )

      case Pending(_, expectedConfig, optLastRun, expirationTime) =>
        optLastRun match {
          case None | Some((_, None)) => //if we don't have a configId, we can't compare
            ComplianceDebugLogger.node(nodeId).trace(s"Node is Pending with no reports from a previous run, everything is pending")
            // we don't have previous run, so we can simply say that all component in node are Pending
            buildRuleNodeStatusReport(
                MergeInfo(nodeId, optLastRun.map(_._1), Some(expectedConfig.nodeConfigId), expirationTime)
              , expectedConfig
              , ReportType.Pending
            )

          case Some((runTime, Some(runConfig))) =>
            /*
             * In that case, we need to compute the status of all component in the previous run,
             * then keep these result for component in the new expected config and for
             * component in new expected config BUT NOT is the one for which we have the run,
             * set pending.
             */
            ComplianceDebugLogger.node(nodeId).trace("Node is Pending with reports from previous run, using merge/compare strategy between last "
                                                    + s"reports from run ${runConfig.toLog} and expect reports ${expectedConfig.toLog}")
            mergeCompareByRule(
                MergeInfo(nodeId, Some(runTime), Some(expectedConfig.nodeConfigId), expirationTime)
              , nodeStatusReports
              , runConfig
              , expectedConfig
            )
        }

      case NoReportInInterval(_, expectedConfig) =>
        ComplianceDebugLogger.node(nodeId).trace(s"Node didn't received reports recently, status depend of the compliance mode and previous report status")
        buildRuleNodeStatusReport(
            //these reports don't really expires - without change, it will
            //always be the same.
            MergeInfo(nodeId, None, Some(expectedConfig.nodeConfigId), END_OF_TIME)
          , expectedConfig
          , ReportType.NoAnswer
        )

      case UnexpectedVersion(_, runTime, Some(runConfig), runExpiration, expectedConfig, expectedExpiration) =>
        ComplianceDebugLogger.node(nodeId).warn(s"Received a run at ${runTime} for node '${nodeId.value}' with configId '${runConfig.nodeConfigId.value}' but that node should be sending reports for configId ${expectedConfig.nodeConfigId.value}")
        buildUnexpectedVersion(runTime, Some(runConfig.configInfo), runExpiration, expectedConfig, expectedExpiration, nodeStatusReports)

      case UnexpectedNoVersion(_, runTime, runId, runExpiration, expectedConfig, expectedExpiration) => //same as unextected, different log
        ComplianceDebugLogger.node(nodeId).warn(s"Received a run at ${runTime} for node '${nodeId.value}' without any configId but that node should be sending reports for configId ${expectedConfig.nodeConfigId.value}")
        buildUnexpectedVersion(runTime, None, runExpiration, expectedConfig, expectedExpiration, nodeStatusReports)

      case UnexpectedUnknowVersion(_, runTime, runId, expectedConfig, expectedExpiration) => //same as unextected, different log
        ComplianceDebugLogger.node(nodeId).warn(s"Received a run at ${runTime} for node '${nodeId.value}' configId '${runId.value}' which is not known by Rudder, and that node should be sending reports for configId ${expectedConfig.nodeConfigId.value}")
        buildUnexpectedVersion(runTime, None, runTime, expectedConfig, expectedExpiration, nodeStatusReports)

      case NoExpectedReport(_, runTime, optConfigId) =>
        // these reports where not expected
        ComplianceDebugLogger.node(nodeId).warn(s"Node '${nodeId.value}' sent reports for run at '${runInfo}' (with ${
          optConfigId.map(x => s" configuration ID: '${x.value}'").getOrElse(" no configuration ID")
        }). No expected configuration matches these reports.")
        buildUnexpectedReports(MergeInfo(nodeId, Some(runTime), optConfigId, END_OF_TIME), nodeStatusReports)

      case NoRunNoExpectedReport(_) =>
        /*
         * Really, this node exists ? Shouldn't we just declare Ragnarök at that point ?
         */
        ComplianceDebugLogger.node(nodeId).warn(s"Can not get compliance for node with ID '${nodeId.value}' because it has no configuration id initialised nor sent reports (node just added ?)")
        Set[RuleNodeStatusReport]()

    }

    /*
     * We must adapt the node run compliance info if we have
     * an abort message or at least one mixed mode result
     */

    val t2 = System.currentTimeMillis
    TimingDebugLogger.trace(s"Compliance: getNodeStatusReports - computing compliance for node ${nodeId}: ${t2-t1}ms")

    val status = {
      val abort = agentExecutionReports.collect {
        case r: LogReports if(r.nodeId == nodeId && r.component.toLowerCase == "abort run") =>
          RunComplianceInfo.PolicyModeError.AgentAbortMessage(r.keyValue, r.message)
      }.toSet
      val mixed = ruleNodeStatusReports.collect { case r => r.directives.collect { case (_, d) if (d.compliance.badPolicyMode > 0) =>
        RunComplianceInfo.PolicyModeError.TechniqueMixedMode(s"Error for node '${nodeId.value}' in directive '${d.directiveId.value}': either that directive is"+
            " not sending the correct Policy Mode reports (for example Enforce reports in place of Audit one - does the directive's Technique is up-to-date?)"+
            " or at least one other directive on that node based on the same Technique sends reports for a different Policy Mode")
      }.toSet }.flatten

      (abort ++ mixed).toList match {
        case Nil  => RunComplianceInfo.OK
        case list => RunComplianceInfo.PolicyModeInconsistency(list)
      }
    }
    val t3 = System.currentTimeMillis
    TimingDebugLogger.trace(s"Compliance: computing policy status for ${nodeId}: ${t3-t2}ms")

    NodeStatusReport.applyByNode(nodeId, runInfo, status, ruleNodeStatusReports)
  }


  // utility method to find how missing report should be reported given the compliance
  // mode of the node.
  private[reports] def missingReportType(complianceMode: ComplianceMode, policyMode: PolicyMode) = complianceMode.mode match {
    case FullCompliance  => ReportType.Missing
    case ChangesOnly     => policyMode match {
      case PolicyMode.Enforce => ReportType.EnforceSuccess
      case PolicyMode.Audit   => ReportType.AuditCompliant
    }
    case ReportsDisabled => ReportType.Disabled
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
      mergeInfo         : MergeInfo
      // only report for that nodeid, of type ResultReports,
      // for the correct run, for the correct version
    , executionReports  : Seq[ResultReports]
    , lastRunNodeConfig : NodeExpectedReports
    , currentConfig     : NodeExpectedReports
  ): Set[RuleNodeStatusReport] = {

    val t0 = System.currentTimeMillis
    val complianceForRun: Map[SerialedRuleId, RuleNodeStatusReport] = (for {
      RuleExpectedReports(ruleId
        , serial, directives     ) <- lastRunNodeConfig.ruleExpectedReports
      directiveStatusReports =  {

                                   val t1 = System.currentTimeMillis
                                   //here, we had at least one report, even if it not a ResultReports (i.e: run start/end is meaningful

                                   val reportsForThatNodeRule: Seq[ResultReports] = executionReports.filter( _.ruleId == ruleId)

                                   val (goodReports, badReports) =  reportsForThatNodeRule.partition(r => r.serial == serial)

                                   val reports = goodReports.groupBy(x => (x.directiveId, x.component) )

                                   val expectedComponents = (for {
                                     directive <- directives
                                     component <- directive.components
                                   } yield {

                                     val policyMode = PolicyMode.directivePolicyMode(
                                         lastRunNodeConfig.modes.globalPolicyMode
                                       , lastRunNodeConfig.modes.nodePolicyMode
                                       , directive.policyMode
                                       , directive.isSystem
                                     )

                                     // the status to use for ACTUALLY missing reports, i.e for reports for which
                                     // we have a run but are not here. Basically, it's "missing" when on
                                     // full compliance and "success" when on changes only - but that success
                                     // depends upon the policy mode
                                     val missingReportStatus = missingReportType(lastRunNodeConfig.complianceMode, policyMode)

                                     ((directive.directiveId, component.componentName), (policyMode, missingReportStatus, component))
                                   }).toMap
                                   val t2 = System.currentTimeMillis
                                   TimingDebugLogger.trace(s"Compliance: mergeCompareByRule - prepare data: ${t2-t1}ms")

                                   /*
                                    * now we have three cases:
                                    * - expected component without reports => missing (modulo changes only interpretation)
                                    * - reports without expected component => unknown
                                    * - both expected component and reports => check
                                    */
                                   val reportKeys = reports.keySet
                                   val expectedKeys = expectedComponents.keySet
                                   val okKeys = reportKeys.intersect(expectedKeys)

                                   val missing = expectedComponents.filterKeys(k => !reportKeys.contains(k)).map { case ((d,_), (pm,mrs,c)) =>
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
                                         ComponentValueStatusReport(v, u, MessageStatusReport(mrs, "") :: Nil)
                                       )}.toMap)
                                     ))
                                   }
                                   val t3 = System.currentTimeMillis
                                   TimingDebugLogger.trace(s"Compliance: mergeCompareByRule - get missing reports: ${t3-t2}ms")

                                   //unexpected contains the one with unexpected key and all non matching serial/version
                                   val unexpected = buildUnexpectedDirectives(
                                       reports.filterKeys(k => !expectedKeys.contains(k)).values.flatten.toSeq ++
                                       badReports
                                   )
                                   val t4 = System.currentTimeMillis
                                   TimingDebugLogger.trace(s"Compliance: mergeCompareByRule - unexpected directives computation: ${t4-t3}ms")

                                   val expected = okKeys.map { k =>
                                     val (policyMode, missingReportStatus, components) = expectedComponents(k)
                                     DirectiveStatusReport(k._1, Map(k._2 ->
                                       checkExpectedComponentWithReports(components, reports(k), missingReportStatus, policyMode)
                                     ))
                                   }
                                   val t5 = System.currentTimeMillis
                                   TimingDebugLogger.trace(s"Compliance: mergeCompareByRule - expected directives computation: ${t5-t4}ms")

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
    val t10 = System.currentTimeMillis
    TimingDebugLogger.trace(s"Compliance: Compute complianceForRun map: ${t10-t0}ms")

    //now, for all current expected reports, choose between the computed value and the default one

    // note: isn't there something specific to do for unexpected reports ? Keep them all ?

    val nil = Seq[RuleNodeStatusReport]()
    val currentRunReports = buildRuleNodeStatusReport(mergeInfo, currentConfig, ReportType.Pending)
    val t11 = System.currentTimeMillis
    TimingDebugLogger.trace(s"Compliance: mergeCompareByRule - compute buildRuleNodeStatusReport: ${t11-t10}ms")

    val (computed, newStatus) = ((nil, nil)/: currentRunReports) { case ( (c,n), currentStatusReports) =>
      complianceForRun.get(SerialedRuleId(currentStatusReports.ruleId, currentStatusReports.serial)) match {
        case None => //the whole rule is new!
          //here, the reports are ACTUALLY pending, not missing.
          (c, n++Set(currentStatusReports))

        case Some(runStatusReport) => //look for added / removed directive
          val runDirectives = runStatusReport.directives
          val currentDirectives = currentStatusReports.directives

          //don't keep directive that were removed between the two configs
          val toKeep = runDirectives.filterKeys(k => currentDirectives.keySet.contains(k))

          //now override currentDirective with the one to keep in currentReport
          val updatedDirectives = currentDirectives ++ toKeep
          val newCompliance = runStatusReport.copy(directives = updatedDirectives)

          (c:+newCompliance, n)
      }
    }
    val t12 = System.currentTimeMillis
    TimingDebugLogger.trace(s"Compliance: mergeCompareByRule - compute compliance : ${t12-t11}ms")


    ComplianceDebugLogger.node(mergeInfo.nodeId).trace(s"Compute compliance for node ${mergeInfo.nodeId.value} using: rules for which compliance is based on run reports: ${
      computed.map { x => s"[${x.ruleId.value}->${x.serial}]"}.mkString("")
    };"+s" rule updated since run: ${
      newStatus.map { x => s"${x.ruleId.value}->${x.serial}"}.mkString("[", "][", "]")
    }")

    val t13 = System.currentTimeMillis
    TimingDebugLogger.debug(s"Compliance: mergeCompareByRule global cost : ${t13-t0}ms")

    (computed ++ newStatus).toSet
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
          ComponentValueStatusReport(r.keyValue, r.keyValue, MessageStatusReport(ReportType.Unexpected, r.message) :: Nil)
        )))
      )
    }
  }

  private[reports] def buildRuleNodeStatusReport(
      mergeInfo      : MergeInfo
    , expectedReports: NodeExpectedReports
    , status         : ReportType
    , message        : String = ""
  ): Set[RuleNodeStatusReport] = {
    expectedReports.ruleExpectedReports.map { case RuleExpectedReports(ruleId, serial, directives) =>
      val d = directives.map { d =>
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
    , filteredReports  : Seq[ResultReports]
    , noAnswerType     : ReportType
    , policyMode       : PolicyMode //the one of the directive, or node, or global
  ) : ComponentStatusReport = {

    // build the list of unexpected ComponentValueStatusReport
    // i.e value
    val unexpectedStatusReports = {
      val unexpectedReports = getUnexpectedReports(
          expectedComponent.componentsValues.toList
        , filteredReports
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
         , List(MessageStatusReport(ReportType.Unexpected, unexpectedReport.message))
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
        val reports = filteredReports.filter(r => r.keyValue == DEFAULT_COMPONENT_KEY)
        ( Some(buildComponentValueStatus(
              DEFAULT_COMPONENT_KEY
            , reports
            , unexpectedStatusReports.isEmpty
            , valueKind.none.size
            , noAnswerType
            , policyMode
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
          val possible = filteredReports.filter(r => values.contains(r.keyValue))
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
          , policyMode
        )
        (status, reports)
      }
      (result.keys,result.values.flatten)
    }

    // Remove all already parsed reports so we only look in remaining reports for Cfengine variables
    val usedReports = noneReports ++ simpleReports
    val remainingReports = filteredReports.diff(usedReports)

    // Find what reports matche what cfengine variables

    val (cfeVarValues, lastReports) = extractCFVarsFromReports(valueKind.cfeVar, remainingReports.toList, noAnswerType, policyMode)

    // Finally, if we still got some reports, generate an unexpected report.
    /*
     * Here, we don't havve any mean to know if the unexpected comes from a CFEngine variable or not.
     * It could be a constant value coming from nowhere as much as a value coming from a badly replaced
     * CFEngine var.
     */
    val lastUnexpected = lastReports.groupBy(_.keyValue).map{
      case (value, reports) =>
        val messageReports = reports.map(r => MessageStatusReport(ReportType.Unexpected, r.message)).toList
        ComponentValueStatusReport(value, value, messageReports)
    }

    /*
     * And now, merge all values into a component.
     */
    ComponentStatusReport(
        expectedComponent.componentName
      , ComponentValueStatusReport.merge(unexpectedStatusReports ++ noneValue ++ simpleValues ++ cfeVarValues ++ lastUnexpected)
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
    , policyMode  : PolicyMode
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
                 val v = ComponentValueStatusReport(unexpanded, unexpanded, List(report.toMessageStatusReport(policyMode)))
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
              val v = ComponentValueStatusReport(unexpanded, unexpanded, List(report.toMessageStatusReport(policyMode)))
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
              val v = ComponentValueStatusReport(unexpanded, unexpanded, List(report.toMessageStatusReport(policyMode)))
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


  implicit class ToMessageStatusReport(r: Reports) {
    // build the resulting reportType from a report, checking that the
    // policy mode is the one expected
    def toMessageStatusReport(mode: PolicyMode) = {
      ReportType(r, mode) match {
        case BadPolicyMode => MessageStatusReport(BadPolicyMode, s"PolicyMode is configured to ${mode.name} but we get: '${r.severity}': ${r.message}")
        case x             => MessageStatusReport(x, r.message)
      }
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
    , policyMode          : PolicyMode
  ) : ComponentValueStatusReport = {

    val messageStatusReports = {
      filteredReports.size match {
        /* Nothing was received at all for that component so : No Answer or Pending */
        case 0 if noUnexpectedReports =>
          MessageStatusReport(noAnswerType, None) :: Nil
        case x if(x <= cardinality) =>
          filteredReports.map(_.toMessageStatusReport(policyMode)).toList ++
          /* We need to complete the list of correct with missing, if some are missing */
          (x until cardinality).map( i => MessageStatusReport(ReportType.Missing, s"[Missing report #${i}]")).toList
        //check if cardinality is ok
        case x if(x > cardinality) =>
          filteredReports.map { r => MessageStatusReport(ReportType.Unexpected, r.message) }.toList
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
