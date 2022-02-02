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

import org.joda.time._
import com.normation.rudder.domain.logger.ComplianceDebugLogger
import com.normation.rudder.domain.logger.ComplianceDebugLogger._
import com.normation.rudder.domain.logger.TimingDebugLogger
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.reports._
import com.normation.rudder.reports._
import com.normation.rudder.reports.execution.AgentRunId
import net.liftweb.common.Loggable

import java.util.regex.Pattern
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.reports.ReportType.BadPolicyMode
import com.normation.rudder.reports.execution.AgentRunWithNodeConfig
import com.normation.rudder.domain.policies.RuleId
import org.apache.commons.lang3.StringUtils

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

/**
 * The type of report to use for missing reports
 * (actually missing reports).
 * Depends of compliance mode.
 */
sealed trait ExpiringStatus extends RunAndConfigInfo {
  def expirationDateTime: DateTime
}

sealed trait ErrorNoConfigData extends RunAndConfigInfo

sealed trait ExpectedConfigAvailable extends RunAndConfigInfo with ExpiringStatus {
  def expectedConfig: NodeExpectedReports
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
  def lastRunConfigInfo: Option[NodeExpectedReports]
}

/*
 * Really, that node exists ?
 */
final case object NoRunNoExpectedReport extends ErrorNoConfigData

/*
 * We don't have the needed configId in the expected
 * table. Either we don't have any config id at all,
 * or we can't find the version matching a run.
 * (it is some weird data lost in the server, or a node
 * not yet initialized)
 */
final case class NoExpectedReport(
    lastRunDateTime: DateTime
  , lastRunConfigId: Option[NodeConfigId]
) extends ErrorNoConfigData

/*
 * The run doesn't provide an expected report: it is probably broken
 * we assume it is the current expected report, but more important,
 * we must complain loudly to the users
 */
final case class RunWithoutExpectedReport(
    lastRunDateTime: DateTime
  , expectedConfig : NodeExpectedReports
)
/*
 * No Rules defined, but run was ok
 */
final case class NoUserRulesDefined(
    lastRunDateTime: DateTime
  , expectedConfig : NodeExpectedReports
  , lastRunConfigId: NodeConfigId
  , lastRunConfigInfo: Option[NodeExpectedReports]
  , expirationDateTime: DateTime
) extends NoReport with LastRunAvailable


/*
 * No report of interest (either none, or
 * some but too old for our situation)
 * ExpirationDateTime here is use to know that the computation is outdated
 * and we out to recompute it to serialize again a NoReportInInterval
 */
final case class NoReportInInterval(
    expectedConfig    : NodeExpectedReports
  , expirationDateTime: DateTime
) extends NoReport

/*
 * No report of interest but expected because
 * we are on the correct mode for that
 */
final case class ReportsDisabledInInterval(
    expectedConfig    : NodeExpectedReports
  , expirationDateTime: DateTime
) extends NoReport

final case class Pending(
    expectedConfig     : NodeExpectedReports
  , optLastRun         : Option[(DateTime, NodeExpectedReports)]
  , expirationDateTime : DateTime
) extends NoReport with ExpiringStatus

/*
 * the case where we have a version on the run,
 * versions are init in the server for that node,
 * and we don't have a version is an error
 */
final case class UnexpectedVersion(
    lastRunDateTime   : DateTime
  , lastRunConfigInfo : Some[NodeExpectedReports]
  , lastRunExpiration : DateTime
  , expectedConfig    : NodeExpectedReports
  , expectedExpiration: DateTime
  , expirationDateTime: DateTime
) extends Unexpected with LastRunAvailable {
  val lastRunConfigId = lastRunConfigInfo.get.nodeConfigId
}

/**
 * A case where we have a run without version,
 * but we really should, because versions are init
 * in the server for that node
 */
final case class UnexpectedNoVersion(
    lastRunDateTime   : DateTime
  , lastRunConfigId   : NodeConfigId
  , lastRunExpiration : DateTime
  , expectedConfig    : NodeExpectedReports
  , expectedExpiration: DateTime
  , expirationDateTime: DateTime
) extends Unexpected with LastRunAvailable {
  val lastRunConfigInfo = None
}

/**
 * A case where we have a run with a version,
 * but we didn't find it in db,
 * but we really should, because versions are init
 * in the server for that node
 */
final case class UnexpectedUnknowVersion(
    lastRunDateTime   : DateTime
  , lastRunConfigId   : NodeConfigId
  , expectedConfig    : NodeExpectedReports
  , expectedExpiration: DateTime
  , expirationDateTime: DateTime
) extends Unexpected with LastRunAvailable {
  val lastRunConfigInfo = None
}


final case class ComputeCompliance(
    lastRunDateTime    : DateTime
  , expectedConfig     : NodeExpectedReports
  , expirationDateTime : DateTime
) extends Ok with LastRunAvailable  {
  val lastRunConfigId   = expectedConfig.nodeConfigId
  val lastRunConfigInfo = Some(expectedConfig)
}

/*
 * An ADT to describe the behaviour regarding Unexpected reports.
 * Unexpected reports are reports that the node sent but that were not
 * awaited in the expected configuration reporting.
 * There is mainly 4 causes for unexpected rerpots:
 * - a bug in a Technique that send a report when it should not. This is a really unexpected report, and
 *   shoud be reported to Technique maintener for correction.
 * - a Technique is executed when it should not. Typically, the node didn't fetch the updated version
 *   of its policies and it still report on the previous one. Real unexpectation.
 * - the network sneezed and syslog went mad. In such a case, we can miss reports or have some duplicated. Missing
 *   reports is embarassing, but syslog tries very hard to not do that, so we have more often duplicated ones.
 * - reports were on a part of the technique with a cfengine parameter which happened to be an iterator and
 *   so it was exectued several time, and there is as many reports. This case is expected, but in the general case,
 *   we don't have anything that allows to anticipate the number of reports sent back (and specific cases like "the
 *   number is available in a server-side known variable" are hard, too, because it means that we know to parse and
 *   interpret a lot more of cfengine gramar).
 *
 * The last two cases bring a lot of false positive bad compliance, and we want to let the user be able to
 * accept them. We want that to be optionnal, has in each cases, it could be real unexpected reports (but it's very
 * unlikely).
 */
sealed trait UnexpectedReportBehavior
object UnexpectedReportBehavior {
  // if a reports originally has a CFEngine var, allows to get several reports value for it.
  final case object UnboundVarValues extends UnexpectedReportBehavior
}

final case class UnexpectedReportInterpretation(options: Set[UnexpectedReportBehavior]) {

  // true if the set of option contains `option`
  def isSet(opt: UnexpectedReportBehavior) = options.contains(opt)

  // check if ALL the provided options are set
  def allSet(opts: UnexpectedReportBehavior*) = {
    val o = opts.toSet
    options.intersect(o) == o
  }

  // check if AT LEAST ONE of provided options is set
  def anySet(opts: UnexpectedReportBehavior*) = options.intersect(opts.toSet).nonEmpty

  // return a copy of that interpretation with the given value set
  def set(opt: UnexpectedReportBehavior) = UnexpectedReportInterpretation(options + opt)

  // return a copy of that interpretation with the given value removed
  def unset(opt: UnexpectedReportBehavior) = UnexpectedReportInterpretation(options.filter( _ != opt))
}


/**
 * An execution batch contains the node reports for a given Rule / Directive at a given date
 * An execution Batch is at a given time <- TODO : Is it relevant when we have several node ?
 */

object ExecutionBatch extends Loggable {
  //these patterns must be reluctant matches to avoid strange things
  //when two variables are presents, or something like: ${foo}xxxxxx}.
  //Note: the method checkExpectedVariable expects that a $ is in the pattern
  final val matchCFEngineVars = """.*\$(\{.+?\}|\(.+?\)).*""".r
  final private val replaceCFEngineVars = """\$\{.+?\}|\$\(.+?\)"""

  /**
   * containers to store common information about "what are we
   * talking about in that merge ?"
   */
  private[reports] final case class MergeInfo(
      nodeId        : NodeId
    , run           : Option[DateTime]
    , configId      : Option[NodeConfigId]
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
   * Returns a string that is suitable for a being used as a regexp, with anything not
   * in ".*" quoted with \Q...\E.
   * For example, "${foo}(bar)$(baz)foo" => "\Q\E.*\Q(bar)\E.*\Qfoo\E"
   */
  final def replaceCFEngineVars(x : String) : Pattern = {
    Pattern.compile("""\Q"""+ x.replaceAll(replaceCFEngineVars, """\\E.*\\Q""") + """\E""")
  }

  final def checkExpectedVariable(expected: ExpectedValue, effective: ResultReports) : Boolean = {
    expected match {
      case ExpectedValueId(_,id) =>
        id == effective.reportId
      case ExpectedValueMatch(expected, _) =>
        if (expected == effective.keyValue) {
          true
        } else {
          // If this is not a var, there isn't anything to match.
          if (StringUtils.contains(expected, '$')) {
            val isVar = matchCFEngineVars.pattern.matcher(expected).matches()
            if (isVar) {
              replaceCFEngineVars(expected).matcher(effective.keyValue).matches()
            } else {
              false
            }
          } else {
            false
          }
        }
    }
  }

final case class ContextForNoAnswer(
      agentExecutionInterval: Int
    , complianceMode        : ComplianceMode
  )

  /*
   * Utility method to factor out common logging task and be assured that
   * the log message is actually sync with the info type.
   */
  private[this] def runType(traceMessage: String, runType: RunAndConfigInfo)(implicit nodeId: NodeId): RunAndConfigInfo = {
    val msg = if(traceMessage.trim.isEmpty) "" else ": " + traceMessage
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

    /** Compute expiration date from a datetime (run in most case, but can be now in case of no run)
     * and the configured interval */
    def computeExpirationDate(date:DateTime, runIntervalInfo: ResolvedAgentRunInterval, complianceMode: ComplianceMode): DateTime = {
      date.plus(runValidityDuration(runIntervalInfo, complianceMode))
    }

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
                  runType(s"nodeId has no configuration ID version, perhaps it's a new Node?", NoRunNoExpectedReport)
                case Some(configs) =>
                  //so, the node has existed at some point, but not now. Strange.
                  runType("nodeId exists in DB but has no version (due to cleaning, migration, synchro, etc)", NoRunNoExpectedReport)
              }

            case Some(currentConfig) =>
              // expiration time in no reports when expired or disabled is so based on now
              val complianceComputationExpirationTime = computeExpirationDate(now, currentConfig.n.agentRun, currentConfig.complianceMode)

              if(currentConfig.complianceMode.mode == ReportsDisabled) { // oh, so in fact it's normal to not have runs!

                runType(s"compliance mode is set to '${ReportsDisabled.name}', it's ok to not having reports", ReportsDisabledInInterval(currentConfig, complianceComputationExpirationTime))
              } else { //let's further examine the situation
                val expireTime = currentConfig.beginDate.plus(updateValidityDuration(currentConfig.agentRun))

                if(expireTime.isBefore(now)) {
                  runType("no run (ever or too old)", NoReportInInterval(currentConfig, complianceComputationExpirationTime))
                } else {
                  runType(s"no run (ever or too old), Pending until ${expireTime}"
                  , Pending(currentConfig, None, expireTime)
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
//          ComplianceDebugLogger.node(nodeId).debug(s"Node run configuration: ${(nodeId, complianceMode, runInfos).toLog }")
//
//          val computed =    computeNodeRunInfo(
//                    nodeId, optInfo, missingReportType
//                  , intervalInfo, updateValidityTime(intervalInfo), runValidityTime(intervalInfo)
//                  , now, run
//              )

          (runInfos, currentNodeConfigs.get(nodeId).flatten) match {

            //
            // #1 : What the hell ?
            //      that's not good. Why a run without expected config ?
            //      More over, we group together the cases where we have config for the run, because without
            //      a current expected config, it should be impossible (no way to paired it with that run).
            //      Just log an error for dev.
            case ((AgentRunWithNodeConfig(AgentRunId(_, t), optConfigId, _)), None) =>
              if(nodeConfigIdInfos.isDefinedAt(nodeId)) {
                runType("nodeId exists in DB but has no version (due to cleaning?). Need regeneration, no expected report yet.", NoExpectedReport(t, None))
              } else {
                runType("nodeId was not found in DB but is sending reports. It is likely a new node. Need regeneration, no expected report yet."
                , NoExpectedReport(t, None)
                )
              }

            //
            // #2 : run without config ID (neither in it nor found)
            //      no expected config for the run. Why so? At least, a recent config.
            case ((AgentRunWithNodeConfig(AgentRunId(_, t), None, _)), Some(currentConfig)) =>
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
              val oldestConfigId = nodeConfigIdInfos.get(nodeId).flatten.getOrElse(Seq(currentConfig.configInfo)).minBy( _.creation.getMillis)
              val oldestExpiration = oldestConfigId.creation.plus(updateValidityDuration(currentConfig.agentRun))

              // expiration time is based on now as run is invalid and we can't get proper data
              val complianceComputationExpirationTime = computeExpirationDate(now, currentConfig.n.agentRun, currentConfig.complianceMode)

              if(oldestExpiration.isBefore(t) ) {
                //we had a config set a long time ago, then interval+grace time happen, and then
                //we get a run without any config id => the node didn't updated its promises

                runType(s"node send reports without nodeConfigId but the oldest configId (${oldestConfigId.configId.value}) expired since ${oldestExpiration})"
                , UnexpectedNoVersion(t, oldestConfigId.configId, oldestExpiration, currentConfig, oldestExpiration, complianceComputationExpirationTime)
                )
              } else {
                val configurationExpirationTime = currentConfig.beginDate.plus(updateValidityDuration(currentConfig.agentRun))
                if(configurationExpirationTime.isBefore(t)) {
                  runType(s"node should have sent reports for configId ${currentConfig.nodeConfigId.value} before ${configurationExpirationTime} but got a report at ${t} without any configId"
                  , NoReportInInterval(currentConfig, complianceComputationExpirationTime)
                  )
                } else {
                  runType(s"waiting for node to send reports for configId ${currentConfig.nodeConfigId.value} before ${configurationExpirationTime} (last run at ${t} didn't have any configId"
                  , Pending(currentConfig, None, configurationExpirationTime) //here, "None" even if we have a old run, because we don't have expectedConfig for it.
                  )
                }
              }


            //
            // #3 : run with a version ID !
            //      But no corresponding expected Node. A
            //      And no current one.
            case ((AgentRunWithNodeConfig(AgentRunId(_, t), Some((rv,None)), _)), Some(currentConfig)) =>
              //it's a bad version, but we have config id in DB => likelly a corruption on node
              //expirationTime is the date after which we must have gotten a report for the current version
              val configurationExpirationTime = currentConfig.beginDate.plus(updateValidityDuration(currentConfig.agentRun))
              // expiration time is based on now as run is invalid and we can't get proper data
              val complianceComputationExpirationTime = computeExpirationDate(now, currentConfig.n.agentRun, currentConfig.complianceMode)

              runType(s"nodeId exists in DB and has configId, expected configId is ${currentConfig.nodeConfigId.value}, but ${rv.value} was not found (node corruption?)",
                  UnexpectedUnknowVersion(t, rv, currentConfig, configurationExpirationTime, complianceComputationExpirationTime)
              )

            //
            // #4 : run with an ID ! And a matching expected config ! And a current expected config !
            //      So this is the standard case.
            //      We have to check if run version == expected, if it's the case: nominal case.
            //      Else, we need to check if the node version is not too old,
            case ((AgentRunWithNodeConfig(AgentRunId(_, t), Some((rv, Some(runConfig))), _)), Some(currentConfig)) =>
              runConfig.endDate match {

                case None =>
                  val expirationTime = t.plus(runValidityDuration(currentConfig.agentRun, currentConfig.complianceMode))
                  if(expirationTime.isBefore(now)) {
                    // expiration time is based on now as run is invalid and we can't get proper data
                    val complianceComputationExpirationTime = computeExpirationDate(now, currentConfig.n.agentRun, currentConfig.complianceMode)

                    //take care of the potential case where currentConfig != runConfig in the log message
                    runType(s"Last run at ${t} is for the configId ${runConfig.nodeConfigId.value} but a new one should have been sent for configIf ${currentConfig.nodeConfigId.value} before ${expirationTime}"
                    , NoReportInInterval(currentConfig, complianceComputationExpirationTime)
                    )
                  } else { //nominal case
                    //here, we have to verify that the config id are different, because we can
                    //be in the middle of a generation of have a badly closed node configuration on base
                    if(runConfig.nodeConfigId != currentConfig.nodeConfigId) {
                      //standard case: we changed version and are waiting for a run with the new one.
                      runType(s"last run at ${t} was for previous configId ${runConfig.nodeConfigId.value} and no report received for current configId ${currentConfig.nodeConfigId.value}, but ${now} is before expiration time ${expirationTime}, Pending"
                      , Pending(currentConfig, Some((t, runConfig)), expirationTime)
                      )
                    } else {
                      // the node is answering current config, on time
                      runType(s"Last run at ${t} is for the correct configId ${currentConfig.nodeConfigId.value} and not expired, compute compliance"
                      , ComputeCompliance(t, currentConfig, expirationTime)
                      )
                    }
                  }

                case Some(eol) =>
                  //check if the run is not too old for the version, i.e if endOflife + grace is before run

                  // a more recent version exists, so we are either awaiting reports
                  // for it, or in some error state (completely unexpected version or "just" no report
                  val eolExpiration = eol.plus(updateValidityDuration(runConfig.agentRun))
                  val configurationExpirationTime = currentConfig.beginDate.plus(updateValidityDuration(currentConfig.agentRun))
                  if(eolExpiration.isBefore(t)) {
                    // compliance expiration time is based on run time
                    val complianceComputationExpirationTime = computeExpirationDate(t, currentConfig.n.agentRun, currentConfig.complianceMode)

                    //we should have had a more recent run
                    runType(s"node sent reports at ${t} for configId ${rv.value} (which expired at ${eol}) but should have been for configId ${currentConfig.nodeConfigId.value}"
                    , UnexpectedVersion(t, Some(runConfig), eolExpiration, currentConfig, configurationExpirationTime, complianceComputationExpirationTime)
                    )
                  } else {
                    if(configurationExpirationTime.isBefore(now)) {
                      // compliance expiration time in no reports when expired or disabled is so based on now
                      val complianceComputationExpirationTime = computeExpirationDate(now, currentConfig.n.agentRun, currentConfig.complianceMode)

                      runType(s"last run at ${t} was for expired configId ${rv.value} and no report received for current configId ${currentConfig.nodeConfigId.value} (one was expected before ${configurationExpirationTime})"
                      , NoReportInInterval(currentConfig, complianceComputationExpirationTime)
                      )
                    } else {
                      //standard case: we changed version and are waiting for a run with the new one.
                      runType(s"last run at ${t} was for expired configId ${rv.value} and no report received for current configId ${currentConfig.nodeConfigId.value}, but ${now} is before expiration time ${configurationExpirationTime}, Pending"
                      , Pending(currentConfig, Some((t, runConfig)), eolExpiration)
                      )
                    }
                  }
              }
          }
      }

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
   *  It returns a properly merged NodeStatusReports
   */
  def getNodeStatusReports(
      nodeId                  : NodeId
      // run info: if we have a run, we have a datetime for it
      // and perhaps a configId
    , runInfo                 : RunAndConfigInfo
      // reports we get on the last know run
    , agentExecutionReports   : Seq[Reports]
    , unexpectedInterpretation: UnexpectedReportInterpretation
  ) : NodeStatusReport = {


    // UnexpectedVersion are always called for only one node
    // The status can't merge, as the merge group by nodeconfigid, and unexpected and missing have, by constuct, different configid
    def buildUnexpectedVersion(runTime: DateTime, runVersion: Option[NodeConfigIdInfo], runExpiration: DateTime, expectedConfig: NodeExpectedReports, expectedExpiration: DateTime, nodeStatusReports: Seq[ResultReports]): Set[RuleNodeStatusReport] = {
      // we have 2 separate status: the missing and the expected, so two different RuleNodeStatusReport that will never merge
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

      case ReportsDisabledInInterval(expectedConfig, _) =>
        ComplianceDebugLogger.node(nodeId).trace(s"Compliance mode is ${ReportsDisabled.name}, so we don't have to try to merge/compare with expected reports")
        buildRuleNodeStatusReport(
            //these reports don't really expires - without change, it will
            //always be the same.
            MergeInfo(nodeId, None, Some(expectedConfig.nodeConfigId), END_OF_TIME)
          , expectedConfig
          , ReportType.Disabled
        )

      case ComputeCompliance(lastRunDateTime, expectedConfig, expirationTime) =>
        ComplianceDebugLogger.node(nodeId).trace(s"Using merge/compare strategy between last reports from run at ${lastRunDateTime} and expect reports ${expectedConfig.toLog}")
        mergeCompareByRule(
            MergeInfo(nodeId, Some(lastRunDateTime), Some(expectedConfig.nodeConfigId), expirationTime)
          , nodeStatusReports
          , expectedConfig
          , expectedConfig
          , unexpectedInterpretation
        )

      case Pending(expectedConfig, optLastRun, expirationTime) =>
        optLastRun match {
          case None =>
            ComplianceDebugLogger.node(nodeId).trace(s"Node is Pending with no reports from a previous run, everything is pending")
            // we don't have previous run, so we can simply say that all component in node are Pending
            buildRuleNodeStatusReport(
                MergeInfo(nodeId, None, Some(expectedConfig.nodeConfigId), expirationTime)
              , expectedConfig
              , ReportType.Pending
            )

          case Some((runTime, runConfig)) =>
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
              , unexpectedInterpretation
            )
        }

      case NoReportInInterval(expectedConfig, expirationTime) =>
        ComplianceDebugLogger.node(nodeId).trace(s"Node didn't received reports recently, status depend of the compliance mode and previous report status")
        buildRuleNodeStatusReport(
            //these reports need to expire, so that we can recompute them automatically
            // at expiration, and store that the nodes were not answering
            MergeInfo(nodeId, None, Some(expectedConfig.nodeConfigId), expirationTime)
          , expectedConfig
          , ReportType.NoAnswer
        )

      case UnexpectedVersion(runTime, Some(runConfig), runExpiration, expectedConfig, expectedExpiration, _) =>
        ComplianceDebugLogger.node(nodeId).warn(s"Received a run at ${runTime} for node '${nodeId.value}' with configId '${runConfig.nodeConfigId.value}' but that node should be sending reports for configId ${expectedConfig.nodeConfigId.value}")
        buildUnexpectedVersion(runTime, Some(runConfig.configInfo), runExpiration, expectedConfig, expectedExpiration, nodeStatusReports)

      case UnexpectedNoVersion(runTime, runId, runExpiration, expectedConfig, expectedExpiration, _) => //same as unextected, different log
        ComplianceDebugLogger.node(nodeId).warn(s"Received a run at ${runTime} for node '${nodeId.value}' without any configId but that node should be sending reports for configId ${expectedConfig.nodeConfigId.value}")
        buildUnexpectedVersion(runTime, None, runExpiration, expectedConfig, expectedExpiration, nodeStatusReports)

      case UnexpectedUnknowVersion(runTime, runId, expectedConfig, expectedExpiration, _) => //same as unextected, different log
        ComplianceDebugLogger.node(nodeId).warn(s"Received a run at ${runTime} for node '${nodeId.value}' configId '${runId.value}' which is not known by Rudder, and that node should be sending reports for configId ${expectedConfig.nodeConfigId.value}")
        buildUnexpectedVersion(runTime, None, runTime, expectedConfig, expectedExpiration, nodeStatusReports)


      case NoUserRulesDefined(runTime, expectedConfig, runId, _, _) => //same as unextected, different log
        val expectedExpiration = expectedConfig.beginDate.plus(expectedConfig.agentRun.interval.plus(GRACE_TIME_PENDING))
        ComplianceDebugLogger.node(nodeId).warn(s"Received a run at ${runTime} for node '${nodeId.value}' configId '${runId.value}' which is not known by Rudder, and that node should be sending reports for configId ${expectedConfig.nodeConfigId.value}")
        buildUnexpectedVersion(runTime, None, runTime, expectedConfig, expectedExpiration, nodeStatusReports)

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
        RunComplianceInfo.PolicyModeError.TechniqueMixedMode(s"Error for node '${nodeId.value}' in directive '${d.directiveId.debugString}': either that directive is" +
                                                             " not sending the correct Policy Mode reports (for example Enforce reports in place of Audit one - does the directive's Technique is up-to-date?)" +
                                                             " or at least one other directive on that node based on the same Technique sends reports for a different Policy Mode")
      }.toSet }.flatten

      (abort ++ mixed).toList match {
        case Nil  => RunComplianceInfo.OK
        case list => RunComplianceInfo.PolicyModeInconsistency(list)
      }
    }
    val t3 = System.currentTimeMillis
    TimingDebugLogger.trace(s"Compliance: computing policy status for ${nodeId}: ${t3-t2}ms")

    val overrides = runInfo match {
      case x: ExpectedConfigAvailable => x.expectedConfig.overrides
      case x: LastRunAvailable        => x.lastRunConfigInfo.map( _.overrides ).getOrElse(Nil).toList
      case _                          => Nil
    }

    NodeStatusReport.apply(nodeId, runInfo, status, overrides, ruleNodeStatusReports)
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




  class ComputeComplianceTimer() {
    var u1, u2, u3, u4 = 0L
  }

  private[reports] def getComplianceForRule(
      mergeInfo               : MergeInfo
      // only report for that ruleId, for that nodeid, of type ResultReports,
      // for the correct run, for the correct version
    , reportsForThatNodeRule  : Seq[ResultReports]
    , modes                   : NodeModeConfig
    , ruleExpectedReports     : RuleExpectedReports
    , unexpectedInterpretation: UnexpectedReportInterpretation
    , timer                   : ComputeComplianceTimer
  ): RuleNodeStatusReport = {

    // An effective expected component contains only the component path from the root component to a unique Value
    // Component is the root component and only contains subElemens that leads to the value
    // Value is the in just a shortcut the last leaf of the component
    // This will allow  to analyse each component path separately so that reports are affected accordingly with blocks
    // In case there is no block, the component is the same than the value
    case class EffectiveExpectedComponent (
      component : ComponentExpectedReport
    , value     : ValueExpectedReport
    )

    def getExpectedComponents(component : ComponentExpectedReport) : List[EffectiveExpectedComponent] = {
      component match {
        case c : ValueExpectedReport => EffectiveExpectedComponent(c, c) :: Nil
        case c : BlockExpectedReport => c.subComponents.flatMap(getExpectedComponents).map(exp => exp.copy(component = c.copy(subComponents = exp.component :: Nil)))
      }
    }

    val RuleExpectedReports(ruleId, directives) = ruleExpectedReports
    val t1 = System.nanoTime
    //here, we had at least one report, even if it not a ResultReports (i.e: run start/end is meaningful

    val reports = reportsForThatNodeRule.groupBy(x => x.directiveId )

    val expectedComponents: Map[(DirectiveId, List[EffectiveExpectedComponent]), (PolicyMode, ReportType)] = (for {
      directive  <- directives
      policyMode =  PolicyMode.directivePolicyMode(
                           modes.globalPolicyMode
                         , modes.nodePolicyMode
                         , directive.policyMode
                         , directive.isSystem
                    )
      // the status to use for ACTUALLY missing reports, i.e for reports for which
      // we have a run but are not here. Basically, it's "missing" when on
      // full compliance and "success" when on changes only - but that success
      // depends upon the policy mode
      missingReportStatus = missingReportType(modes.globalComplianceMode, policyMode)

      component  <- directive.components

    } yield {

      ((directive.directiveId, getExpectedComponents(component)), (policyMode, missingReportStatus))
    }).toMap
    val t2 = System.nanoTime
    timer.u1 += t2-t1

    /*
     * now we have three cases:
     * - expected component without reports => missing (modulo changes only interpretation)
     * - reports without expected component => unknown
     * - both expected component and reports => check
     */
    val reportKeys = reports.keySet
    val expectedKeys: Set[DirectiveId] = expectedComponents.keySet.map(_._1)
    val okKeys = reportKeys.intersect(expectedKeys)

    val t3 = System.nanoTime
    timer.u2 += t3-t2

    //unexpected contains the one with unexpected key and all non matching serial/version
    val unexpected = (if (okKeys.size != reportKeys.size) {
      buildUnexpectedDirectives(
        reports.filter(k => !expectedKeys.contains(k._1                                       )).values.flatten.toSeq
      )
    } else {
      Seq[DirectiveStatusReport]()
    })

    val t4 = System.nanoTime
    timer.u3 += t4-t3

    // okKeys is DirectiveId, ComponentName
    val expected: Iterable[DirectiveStatusReport] =
      expectedComponents.groupBy(_._1._1).map {
        case (directiveId, expectedComponentsForDirective) =>
          DirectiveStatusReport(directiveId, expectedComponentsForDirective.flatMap {
            case ((directiveId, components), (policyMode, missingReportStatus)) =>
              val filteredReports = reports.get(directiveId).getOrElse(Seq())
              // We iterate on each effective component (not a component but a component that only contains a unique path to a unique value
              val r = components.map { c =>
                // HEre reports need to be filtered to only match values that are expected for the Value that is at the end of the component path
                // and its component nae
                // The component used to analyse reports is the component at the top of our structure that we recreate the whole tree
                checkExpectedComponentWithReports(c.component, filteredReports, missingReportStatus, policyMode, unexpectedInterpretation)
              }
              // Merge all components together
              ComponentStatusReport.merge(r.flatten)
          }.toList)
      }


    val t5 = System.nanoTime
    timer.u4 += t5-t4

    // if there is no missing nor unexpected, then data is already correct, otherwise we need to merge it
    val directiveStatusReports = {
      if (unexpected.nonEmpty) {
        DirectiveStatusReport.merge(expected ++ unexpected)
      } else {
        expected.map( dir => (dir.directiveId, dir)).toMap
      }
    }

    RuleNodeStatusReport(
        mergeInfo.nodeId
      , ruleId
      , mergeInfo.run
      , mergeInfo.configId
      , directiveStatusReports
      , mergeInfo.expirationTime
    )

  }

  /**
   * Compute compliance for run for each rules
   *
   */
  private[reports] def getComplianceForRun(
      mergeInfo               : MergeInfo
      // only report for that nodeid, of type ResultReports,
      // for the correct run, for the correct version
    , executionReports        : Seq[ResultReports]
    , lastRunNodeConfig       : NodeExpectedReports
    , unexpectedInterpretation: UnexpectedReportInterpretation
  ): Map[RuleId, RuleNodeStatusReport] = {

    val timer = new ComputeComplianceTimer()
    val t0 = System.currentTimeMillis

    val reportsPerRule = executionReports.groupBy(_.ruleId)
    val complianceForRun: Map[RuleId, RuleNodeStatusReport] = (for {
      ruleExpectedReport <- lastRunNodeConfig.ruleExpectedReports
    } yield {

      val reportsForThatNodeRule: Seq[ResultReports] = reportsPerRule.getOrElse(ruleExpectedReport.ruleId, Seq[ResultReports]())
      val ruleCompliance = getComplianceForRule(mergeInfo, reportsForThatNodeRule, lastRunNodeConfig.modes, ruleExpectedReport, unexpectedInterpretation, timer)
      (ruleExpectedReport.ruleId, ruleCompliance)
    }).toMap

    val t1 = System.currentTimeMillis

    TimingDebugLogger.trace(s"Compliance: mergeCompareByRule - prepare data: ${timer.u1/1000}µs")
    TimingDebugLogger.trace(s"Compliance: mergeCompareByRule - get missing reports: ${timer.u2/1000}µs")
    TimingDebugLogger.trace(s"Compliance: mergeCompareByRule - unexpected directives computation: ${timer.u3/1000}µs")
    TimingDebugLogger.trace(s"Compliance: mergeCompareByRule - expected directives computation: ${timer.u4/1000}µs")

    TimingDebugLogger.trace(s"Compliance: Compute complianceForRun map: ${t1-t0}ms")

    complianceForRun
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
      mergeInfo               : MergeInfo
      // only report for that nodeid, of type ResultReports,
      // for the correct run, for the correct version
    , executionReports        : Seq[ResultReports]
    , lastRunNodeConfig       : NodeExpectedReports
    , currentConfig           : NodeExpectedReports
    , unexpectedInterpretation: UnexpectedReportInterpretation
  ): Set[RuleNodeStatusReport] = {

    val t0 = System.currentTimeMillis()

    val complianceForRun = getComplianceForRun(mergeInfo, executionReports, lastRunNodeConfig, unexpectedInterpretation)

    val t10 = System.currentTimeMillis()

    //now, for all current expected reports, choose between the computed value and the default one

    // note: isn't there something specific to do for unexpected reports ? Keep them all ?

    val currentRunReports = buildRuleNodeStatusReport(mergeInfo, currentConfig, ReportType.Pending)
    val t11 = System.currentTimeMillis

    TimingDebugLogger.trace(s"Compliance: mergeCompareByRule - compute buildRuleNodeStatusReport: ${t11-t10}ms")

    val (computed, newStatus) = currentRunReports.foldLeft((List[RuleNodeStatusReport](), List[RuleNodeStatusReport]())) { case ( (c,n), currentStatusReports) =>
      complianceForRun.get(currentStatusReports.ruleId) match {
        case None => //the whole rule is new!
          //here, the reports are ACTUALLY pending, not missing.
          (c,  currentStatusReports :: n )

        case Some(runStatusReport) => //look for added / removed directive
          val runDirectives = runStatusReport.directives
          val currentDirectives = currentStatusReports.directives

          //don't keep directive that were removed between the two configs
          val toKeep = runDirectives.view.filterKeys(k => currentDirectives.keySet.contains(k)).toMap

          //now override currentDirective with the one to keep in currentReport
          val updatedDirectives = currentDirectives ++ toKeep
          val newCompliance = runStatusReport.copy(directives = updatedDirectives)

          (newCompliance :: c, n)
      }
    }

    val t12 = System.currentTimeMillis
    TimingDebugLogger.trace(s"Compliance: mergeCompareByRule - compute compliance : ${t12-t11}ms")


    if (ComplianceDebugLogger.node(mergeInfo.nodeId).isTraceEnabled) {
      ComplianceDebugLogger.node(mergeInfo.nodeId).trace(s"Compute compliance for node ${mergeInfo.nodeId.value} using: rules for which compliance is based on run reports: ${
        computed.map { x => s"[${x.ruleId.serialize}]" }.mkString("")
      };" + s" rule updated since run: ${
        newStatus.map { x => s"${x.ruleId.serialize}" }.mkString("[", "][", "]")
      }")
    }

    val t13 = System.currentTimeMillis
    TimingDebugLogger.debug(s"Compliance: mergeCompareByRule global cost : ${t13-t0}ms")

    (computed ::: newStatus).toSet
  }

  private[this] def buildUnexpectedReports(mergeInfo: MergeInfo, reports: Seq[Reports]): Set[RuleNodeStatusReport] = {
    reports.groupBy(x => x.ruleId).map { case (ruleId, seq) =>
      RuleNodeStatusReport(
        mergeInfo.nodeId
        , ruleId
        , mergeInfo.run
        , mergeInfo.configId
        , seq.groupBy(_.directiveId).map{ case (directiveId, reportsByDirectives) =>
          (directiveId, DirectiveStatusReport(directiveId, reportsByDirectives.groupBy(_.component).toList.map { case (component, reportsByComponents) =>
            ValueStatusReport(component, reportsByComponents.groupBy(_.keyValue).toList.map { case (keyValue, reportsByComponent) =>
              ComponentValueStatusReport(keyValue, keyValue, reportsByComponent.map(r => MessageStatusReport(ReportType.Unexpected, r.message)).toList)
            })
            })
          )}.toMap
        , mergeInfo.expirationTime
      )
    }.toSet
  }
  /**
   * Build unexpected reports for the given reports
   */
  private[this] def buildUnexpectedDirectives(reports: Seq[Reports]): Seq[DirectiveStatusReport] = {
    reports.map { r =>
      DirectiveStatusReport(r.directiveId,
        ValueStatusReport(r.component, ComponentValueStatusReport(r.keyValue, r.keyValue, MessageStatusReport(ReportType.Unexpected, r.message) :: Nil) :: Nil
        ) :: Nil)

    }
  }

  private[reports] def componentExpectedReportToStatusReport (reportType: ReportType, c : ComponentExpectedReport) : ComponentStatusReport = {
       c match {
         case c : ValueExpectedReport =>
              ValueStatusReport(c.componentName, c.componentsValues.map {
                case ExpectedValueId(v,_) => (v,v)
                case ExpectedValueMatch(v,u) => (v,u)
              }.map { case(v,u) =>
                  ComponentValueStatusReport(v, u, MessageStatusReport(reportType, None) :: Nil)
                  })
            case c : BlockExpectedReport =>
              BlockStatusReport(c.componentName, c.reportingLogic, c.subComponents.map(componentExpectedReportToStatusReport(reportType, _)))
          }

        }


  // by construct, NodeExpectedReports are correctly grouped by Rule/Directive/Component
  private[reports] def buildRuleNodeStatusReport(
      mergeInfo      : MergeInfo
    , expectedReports: NodeExpectedReports
    , status         : ReportType
    , message        : String = ""
  ): Set[RuleNodeStatusReport] = {
    expectedReports.ruleExpectedReports.map { case RuleExpectedReports(ruleId, directives) =>
      val d = directives.map { d =>
        (d.directiveId, DirectiveStatusReport(d.directiveId,
          d.components.map { c =>
            componentExpectedReportToStatusReport(status, c)
          }
        )
        )}.toMap
      RuleNodeStatusReport(
          mergeInfo.nodeId
        , ruleId
        , mergeInfo.run
        , mergeInfo.configId
        , d
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
      expectedComponent       : ComponentExpectedReport
    , filteredReports         : Seq[ResultReports]
    , noAnswerType            : ReportType
    , policyMode              : PolicyMode //the one of the directive, or node, or global
    , unexpectedInterpretation: UnexpectedReportInterpretation
  ) : List[ComponentStatusReport] = {

    expectedComponent match {
      case g: BlockExpectedReport =>
        BlockStatusReport(g.componentName, g.reportingLogic, g.subComponents.flatMap { case e =>
          checkExpectedComponentWithReports(e, filteredReports, noAnswerType, policyMode, unexpectedInterpretation)
        }) :: Nil
         case expectedComponent: ValueExpectedReport =>

        val (matchValue, matchId) = expectedComponent.componentsValues.partitionMap{
          case e:ExpectedValueMatch => Left(e)
          case e: ExpectedValueId => Right(e)
        }


        // an utility class that store an expected value and the list of matching reports for it
        final case class Value(
            expectedValue: ExpectedValueMatch
          , cardinality: Int // number of expected reports, most of the time '1'
          , isVar: Boolean
          , pattern: Option[Pattern]
          , specificity: Int // how specific the pattern is. ".*" is 0 (not specific at all), "foobarbaz" is 9.
          , matchingReports: List[Reports]
        )

        /*
         * This function recursively try to pair the first report from input list with one of the component
         * value.
         * If no component value is found for the report, it is set aside in an "unexpected" list.
         * A value can hold at most one report safe if:
         * - the report is variable AND UnexpectedReportBehavior.UnboundVarValues is set
         */
        def recPairReports(reports: List[ResultReports], freeValues: List[Value], pairedValues: List[Value], unexpected: List[ResultReports], mode: UnexpectedReportInterpretation): (List[Value], List[ResultReports]) = {
          // utility function: given a list of Values and one report, try to find a value whose pattern matches reports component value.
          // the first matching value is used (they must be sorted by specificity).
          // Returns (list of unmatched values, Option[matchedValue])
          // We also have two special case to deal with:
          // - in some case, we want to ignore a report totally (ie: it must not change compliance). This is typically for
          //   what we want for duplicated reports. We need to log the report erasure as it won't be displayed in UI (by
          //   design). The log level should be "info" and not more because it was chosen by configuration to ignore them.
          // - in some case, we want to accept more reports than originally expected. Then, we must update cardinality to
          //   trace that decision. It's typically what we want to do for expected reports matching a runtime computed seq of
          //   values (or if not runtime, at least a sequence obtained through a variable).
          // Be careful: the list of values must be kept sorted in the same order as paramater!
          def findMatchingValue(
              report: ResultReports
            , values: List[Value]
            , incrementCardinality: (Value, ResultReports) => Boolean
          ): (List[Value], Option[Value]) = {
            val (stack, found) = values.foldLeft(((Nil: List[Value]), Option.empty[Value])) { case ((stack, found), value) =>
              found match {
                case Some(x) => (value :: stack, Some(x))
                case None =>
                  // We don't need to match if it's not a variable. By construct, if it is a var, there is a pattern
                  if (((!value.isVar) && (value.expectedValue.value == report.keyValue)) || (value.isVar && (value.pattern.get.matcher(report.keyValue).matches()))) {
                    val card = value.cardinality + (if (incrementCardinality(value, report)) 1 else 0)
                    (stack, Some(value.copy(
                      cardinality = card
                      , matchingReports = report :: value.matchingReports
                    )))
                  } else {
                    (value :: stack, None)
                  }
              }
            }
            (stack.reverse, found)
          }

          reports match {
            case Nil => (freeValues ::: pairedValues, unexpected)
            case report :: tail =>
              // we look in the free values for one matching the report. If found, we return (remaining freevalues, Some[paired value]
              // else (all free values, None).
              // never increment the cardinality for free value.
              val (newFreeValues, tryPair) = findMatchingValue(report, freeValues,  (value, report) => false)

              logger.trace(s"found unpaired value for '${report.keyValue}'? " + tryPair)

              tryPair match {
                case Some(v) =>
                  recPairReports(tail, newFreeValues, v :: pairedValues, unexpected, mode)
                case None =>
                  // here, we don't have found any free value for that report. We are not done yet because it can be an
                  // unexpected reports bound to an already existing value (and so the whole component should be
                  // unexpected or if mode allows duplicates or unbound var values, it can be ok.


                  val unboundedVar = (value: Value, report: ResultReports) => {
                    mode.isSet(UnexpectedReportBehavior.UnboundVarValues) && {
                      // this predicate is simpler: it just have to be a variable
                      value.isVar
                    }
                  }

                  val (newPairedValues, pairedAgain) = findMatchingValue(report, pairedValues, unboundedVar)

                  logger.trace(s"Found paired again value for ${report.keyValue}? " + pairedAgain)

                  pairedAgain match {
                    case None => //really unexpected after all
                      recPairReports(tail, newFreeValues, newPairedValues, report :: unexpected, mode)
                    case Some(v) => //found a new pair!
                      recPairReports(tail, newFreeValues, v :: newPairedValues, unexpected, mode)
                  }
              }
          }
        }


        val (matched,last_unexpected) =  matchId.foldLeft((List.empty[ValueStatusReport],filteredReports)) {
          case ((acc, reports), id) =>
            val (matched, left) = reports.partition(_.reportId == id.id)
            val res = matched.groupBy(_.component).toList.map {
              case (c, reports) =>
                ValueStatusReport(c,
                  reports.groupBy(_.keyValue).toList.map {
                    case (v, r) =>
                      ComponentValueStatusReport(v, v, r.map(r => r.toMessageStatusReport(policyMode)).toList)
                  }
                )
            }
            (res ::: acc, left)
        }
        val componentGotAtLeastOneReport = filteredReports.nonEmpty

        // the list of expected (value, unexpanded value for display)
        // it is very important to sort pattern so that the more precise come first to avoid
        // bugs like #7758. A pattern specificity, in our case, can somehow be told from
        // the lenght of the pattern when \Q\E.* are removed.
        //
        val values = matchValue.map { v =>
          val isVar = matchCFEngineVars.pattern.matcher(v.value).matches()
          val pattern = if (isVar) { // If this is not a var, there isn't anything to replace.
            Some(replaceCFEngineVars(v.value))
          } else {
            None
          }
          //If this is not a variable, we use the variable itself
          val specificity = pattern.map(_.toString.replaceAll("""\\Q""", "").replaceAll("""\\E""", "").replaceAll("""\.\*""", "")).getOrElse(v.value).size
          // default cardinality for a value is 1
          // default duplicate is 0 (and hopefully will remain so)
          Value(v, 1, isVar, pattern, specificity, Nil)
        }.sortWith(_.specificity > _.specificity)

        if (logger.isTraceEnabled)
          logger.trace("values order: \n - " + values.mkString("\n - "))

        // we also need to sort reports to have a chance to not use a specific pattern for not the most specific report
        val sortedReports = last_unexpected.sortWith(_.keyValue.size > _.keyValue.size)

        if (logger.isTraceEnabled)
          logger.trace("sorted reports: \n - " + sortedReports.map(_.keyValue).mkString("\n - "))

        val (pairedValue, unexpected) = recPairReports(sortedReports.toList.filter(_.component == expectedComponent.componentName), values, Nil, Nil, unexpectedInterpretation)

        if (logger.isTraceEnabled) {
          logger.trace("paires: \n + " + pairedValue.mkString("\n + "))
          logger.trace("unexpected: " + unexpected)
        }
        val pairedReportStatus = pairedValue.map { v =>
          //here, we need to lie a little about the cardinality. It should be 1 (because it's only one component value),
          //but it may be more if we accept duplicate/unboundVar. So just use the max(1, number of paired reports)
          buildComponentValueStatus(
              v.expectedValue
            , v.matchingReports
            , componentGotAtLeastOneReport
            , v.cardinality
            , noAnswerType
            , policyMode
          )
        }


        /*
     * And now, merge all values into a component.
     */
           if (pairedReportStatus.isEmpty) {
             matched
           } else {
             ValueStatusReport(
               expectedComponent.componentName
               , ComponentValueStatusReport.merge(pairedReportStatus)
             ) :: matched
           }
    }
  }


  implicit class ToMessageStatusReport(val r: Reports) extends AnyVal {
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
      expectedValueMatch: ExpectedValueMatch
    , filteredReports     : Seq[Reports]
    , componentGotReports : Boolean // does the component got at least one report?
    , cardinality         : Int
    , noAnswerType        : ReportType
    , policyMode          : PolicyMode
  ) : ComponentValueStatusReport = {

    val messageStatusReports = {
      filteredReports.size match {
        /* Nothing was received at all for that component so : No Answer or Pending */
        case 0 if !componentGotReports =>
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
        expectedValueMatch.value
      , expectedValueMatch.value
      , messageStatusReports
    )
  }


}
