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
import com.normation.rudder.reports.ComplianceMode
import com.normation.utils.Control.sequence
import com.normation.rudder.reports.FullCompliance
import com.normation.rudder.reports.ChangesOnly
import com.normation.rudder.reports.execution.AgentRunId
import net.liftweb.common.Loggable
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.repository.NodeConfigIdInfo
import com.normation.rudder.reports.execution.AgentRun
import com.normation.rudder.domain.policies.SerialedRuleId
import com.normation.rudder.domain.policies.SerialedRuleId
import com.normation.rudder.reports.ResolvedAgentRunInterval
import com.normation.rudder.repository.NodeConfigIdInfo
import com.normation.rudder.domain.policies.SerialedRuleId
import com.normation.cfclerk.xmlparsers.CfclerkXmlConstants.DEFAULT_COMPONENT_KEY

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
  def expectedConfigId: NodeConfigIdInfo
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
  def lastRunConfigId: NodeConfigIdInfo
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
case object NoRunNoInit extends ErrorNoConfigData

/*
 * We don't have the needed configId in the expected
 * table. Either we don't have any config id at all,
 * or we can't find the version matching a run.
 * (it is some weird data lost in the server, or a node
 * not yet initialized)
 */
case class VersionNotFound(
    lastRunDateTime: DateTime
  , lastRunConfigId: Option[NodeConfigId]
) extends ErrorNoConfigData


/*
 * No report of interest (either none, or
 * some but too old for our situation)
 */
case class NoReportInInterval(
    expectedConfigId: NodeConfigIdInfo
) extends NoReport



case class Pending(
    expectedConfigId   : NodeConfigIdInfo
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
  , lastRunConfigId   : NodeConfigIdInfo
  , lastRunExpiration : DateTime
  , expectedConfigId  : NodeConfigIdInfo
  , expectedExpiration: DateTime
) extends Unexpected with LastRunAvailable


case class ComputeCompliance(
    lastRunDateTime    : DateTime
  , lastRunConfigId    : NodeConfigIdInfo
  , expectedConfigId   : NodeConfigIdInfo
  , expirationDateTime : DateTime
  , missingReportStatus: ReportType
) extends Ok with LastRunAvailable with ExpiringStatus with MissingReportStatus




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
   * For each node, get the config it has.
   * This method bases its result on THE LAST RUN
   * of each node, and try to discover the run linked information (datetime, config id).
   *
   */
  def computeNodeRunInfo(
      nodeIds          : Map[NodeId, ResolvedAgentRunInterval]
    , runs             : Map[NodeId, Option[AgentRun]]
    , nodeConfigIdInfos: Map[NodeId, Option[Seq[NodeConfigIdInfo]]]
    , complianceMode   : ComplianceMode
  ): Map[NodeId, RunAndConfigInfo] = {

    val now = DateTime.now

    def missingReportType() = complianceMode match {
      case FullCompliance => MissingReportType
      case ChangesOnly(_) => SuccessReportType
    }

    def findVersionById(id: NodeConfigId, infos: Seq[NodeConfigIdInfo]): Option[NodeConfigIdInfo] ={
      infos.find { i => i.configId == id}
    }

    def findVersionByDate(date: DateTime, infos: Seq[NodeConfigIdInfo]): Option[NodeConfigIdInfo] ={
      infos.find { i => i.creation.isBefore(date) && (i.endOfLife match {
        case None => true
        case Some(t) => t.isAfter(date)
      }) }
    }

    /*
     * Find the config that was in use for the run.
     * If the run is before any config, we use the first one.
     * Run in the future use the last one.
     * The list of config must be non empty.
     */
    def findConfigForRun(runTime: DateTime, configs: Seq[NodeConfigIdInfo]) : NodeConfigIdInfo = {
      val sorted = configs.sortBy(_.creation.getMillis)
      (sorted.head/:sorted.tail) { case(best, next) =>
        if(next.creation.isAfter(runTime)) best
        else next
      }
    }

    /*
     * Utility method to factor out common logging task and be assured that
     * the log message is actually sync with the info type.
     */
    def runType(traceMessage: String, info: RunAndConfigInfo)(implicit nodeId: NodeId): RunAndConfigInfo = {
      val msg = if(traceMessage.trim.size == 0) "" else ": " + traceMessage
      ComplianceDebugLogger.trace(s"Run config for node ${nodeId.value}: ${info.logName} ${msg}")
      info
    }

    ComplianceDebugLogger.debug(s"Node run configuration: ${nodeIds.mapValues { x => (complianceMode, x) }.toLog }")

    nodeIds.map { case (nodeId, intervalInfo) =>
      implicit val _n = nodeId

      val runInfo = {

        val optRun = runs.getOrElse(nodeId, None)
        val optInfo = nodeConfigIdInfos.getOrElse(nodeId, None)

        (optRun, optInfo) match {
          case (None, None) =>
            runType("", NoRunNoInit)

          // There is no run for this node
          case (None, Some(configs)) =>
            if(configs.isEmpty) {
              runType("nodeId exists in DB but has no version (due to cleaning?)", NoRunNoInit)
            } else {
              val currentConfig = configs.maxBy(_.creation.getMillis)
              val expireTime = currentConfig.creation.plus(intervalInfo.interval.plus(GRACE_TIME_PENDING))

              if(expireTime.isBefore(now)) {
                ComplianceDebugLogger.trace(s"Run config for node ${nodeId.value}: NoReportInInterval")
                runType("no run ever", NoReportInInterval(currentConfig))
              } else {
                runType("no run ever", Pending(currentConfig, None, expireTime, missingReportType))
              }
            }

          case (Some(AgentRun(AgentRunId(_, t), optConfigId, _, _)), None) =>
            runType("need to regenerate policies?", VersionNotFound(t, optConfigId))

          case (Some(AgentRun(AgentRunId(_, t), None, _, _)), Some(configs)) =>
            if(configs.isEmpty) {
              runType("nodeId exists in DB but has no version (due to cleaning?)", VersionNotFound(t, None))
            } else {
              /*
               * Here, we want to check two things:
               * - does the run should have contain a config id ?
               *   It should if the oldest config was created too long ago
               *
               * - else, we look at the most recent
               *   config and decide between pending / no answer
               */
              val configForRun = findConfigForRun(t, configs)
              val oldestConfigId = configs.minBy( _.creation.getMillis)
              val oldestExpiration = oldestConfigId.creation.plus(intervalInfo.interval.plus(GRACE_TIME_PENDING))
              if(oldestExpiration.isBefore(t) ) {
                //we had a config set a long time ago, then interval+grace time happen, and then
                //we get a run without any config id => the node didn't updated its promises
                val currentConfig = configs.maxBy( _.creation.getMillis)
                val currentExpiration = currentConfig.creation.plus(intervalInfo.interval.plus(GRACE_TIME_PENDING))
                runType(s"node send reports without nodeConfigId but the oldest configId (${oldestConfigId.configId.value} expired since ${oldestExpiration})"
                , UnexpectedVersion(t, oldestConfigId, oldestExpiration, currentConfig, currentExpiration)
                )
              } else {
                val currentConfigId = configs.maxBy( _.creation.getMillis )
                val expirationTime = currentConfigId.creation.plus(intervalInfo.interval.plus(GRACE_TIME_PENDING))
                if(expirationTime.isBefore(t)) {
                  runType(s"node should have sent reports for configId ${currentConfigId.configId.value} before ${expirationTime} but got a report at ${t} without any configId"
                  , NoReportInInterval(currentConfigId)
                  )
                } else {
                  runType(s"waiting for node to send reports for configId ${currentConfigId.configId.value} before ${expirationTime} (last run at ${t} hadn't any configId"
                  , Pending(currentConfigId, Some((t, oldestConfigId)), expirationTime, missingReportType())
                  )
                }
              }
            }

          case (Some(AgentRun(AgentRunId(_, t), Some(rv), _, _)), Some(configs)) =>
            if(configs.isEmpty) {
              //error: we have a MISSING config id. Contrary to the case where any config id is missing
              //for the node, here we have a BAD id.
              runType("nodeId exists in DB but has no version (due to cleaning?)", VersionNotFound(t, Some(rv)))
            } else {
              findVersionById(rv, configs) match {
                case None =>
                  //it's a bad version.
                  runType(s"nodeId exists in DB and has configId, but not ${rv.value} (due to cleaning?)", VersionNotFound(t, Some(rv)))

                case Some(v) => //nominal case !
                  //check if the run is not too old for the version, i.e if endOflife + grace is before run
                  val currentConfigId = configs.maxBy( _.creation.getMillis )

                  v.endOfLife match {
                    case None =>
                      //nominal (bis)! The node is answering to current config !
                      val expirationTime = complianceMode match {
                        case ChangesOnly(_) =>
                          //expires after run*heartbeat period - we need an other run before that.
                          val heartbeat = Duration.standardMinutes((intervalInfo.interval.getStandardMinutes * intervalInfo.heartbeatPeriod ))
                          t.plus(heartbeat.plus(GRACE_TIME_PENDING))
                        case FullCompliance =>
                          t.plus(intervalInfo.interval.plus(GRACE_TIME_PENDING))
                      }
                      if(expirationTime.isBefore(now)) {
                        runType(s"Last run at ${t} is for the correct configId ${v.configId.value} but a new one should have been sent before ${expirationTime}"
                        , NoReportInInterval(v)
                        )
                      } else {
                        ComputeCompliance(t, v, currentConfigId, expirationTime, missingReportType())
                      }

                    case Some(eol) =>
                      // a more recent version exists, so we are either awaiting reports
                      // for it, or in some error state (completely unexpected version or "just" no report
                      val eolExpiration = eol.plus(intervalInfo.interval.plus(GRACE_TIME_PENDING))
                      val expirationTime = currentConfigId.creation.plus(intervalInfo.interval.plus(GRACE_TIME_PENDING))
                      if(eolExpiration.isBefore(t)) {
                        //we should have had a more recent run
                        runType(s"node sent reports at ${t} for configId ${rv.value} (which expired at ${eol}) but should have been for configId ${currentConfigId.configId.value}"
                        , UnexpectedVersion(t, v, eolExpiration, currentConfigId, expirationTime)
                        )
                      } else {
                        if(expirationTime.isBefore(now)) {
                          runType(s"last run at ${t} was for expired configId ${rv.value} and no report received for current configId ${currentConfigId.configId.value} (one was expected before ${expirationTime})"
                          , NoReportInInterval(currentConfigId)
                          )
                        } else {
                          //standard case: we changed version and are waiting for a run with the new one.
                          Pending(currentConfigId, Some((t, v)), eolExpiration, missingReportType())
                        }
                      }
                  }
              }
            }
        }
      }

      (nodeId, runInfo)
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
      // this is needed expected reports given for the node.
    , expectedReports      : Map[NodeConfigId, Map[SerialedRuleId, RuleNodeExpectedReports]]
      // reports we get on the last know run
    , agentExecutionReports: Seq[Reports]
  ) : Seq[RuleNodeStatusReport] = {


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

    //only interesting reports: for that node, with a status
    val nodeStatusReports = agentExecutionReports.collect{ case r: ResultReports if(r.nodeId == nodeId) => r }

    ComplianceDebugLogger.debug(s"Computing compliance for node ${nodeId.value} with: ${runInfo.toLog}")

    runInfo match {

      case ComputeCompliance(lastRunDateTime, lastRunConfigId, expectedConfigId, expirationTime, missingReportStatus) =>
        mergeCompareByRule(
            MergeInfo(nodeId, Some(lastRunDateTime), Some(lastRunConfigId.configId), expirationTime)
          , nodeStatusReports
          , getExpectedReports(lastRunConfigId.configId)
          , getExpectedReports(expectedConfigId.configId)
          , missingReportStatus
        )

      case Pending(expectedConfig, optLastRun, expirationTime, missingReportStatus) =>
        optLastRun match {
          case None =>
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
            mergeCompareByRule(
                MergeInfo(nodeId, Some(runTime), Some(expectedConfig.configId), expirationTime)
              , nodeStatusReports
              , getExpectedReports(runConfigId.configId)
              , getExpectedReports(expectedConfig.configId)
              , missingReportStatus
            )
        }

      case NoReportInInterval(expectedConfigId) =>
        buildRuleNodeStatusReport(
            //these reports don't really expires - without change, it will
            //always be the same.
            MergeInfo(nodeId, None, Some(expectedConfigId.configId), END_OF_TIME)
          , getExpectedReports(expectedConfigId.configId)
          , NoAnswerReportType
        )

      case UnexpectedVersion(runTime, runVersion, runExpiration, expectedVersion, expectedExpiration) =>
        //mark all report of run unexpected,
        //all expected missing
        logger.debug(s"Received a run at ${runTime} for node '${nodeId.value}' with configId '${runVersion.configId.value}' but that node should be sendind reports for configId ${expectedVersion.configId.value}")

        buildRuleNodeStatusReport(
            MergeInfo(nodeId, Some(runTime), Some(expectedVersion.configId), expectedExpiration)
          , getExpectedReports(expectedVersion.configId)
          , MissingReportType
        ) ++
        buildUnexpectedReports(MergeInfo(nodeId, Some(runTime), Some(runVersion.configId), runExpiration), nodeStatusReports)

      case VersionNotFound(runTime, optConfigId) =>
        // these reports where not expected
        logger.debug(s"Node '${nodeId.value}' sent reports for run at '${runInfo}' (with ${
          optConfigId.map(x => s" configuration ID: '${x.value}'").getOrElse(" no configuration ID")
        }). No expected configuration matches these reports.")
        buildUnexpectedReports(MergeInfo(nodeId, Some(runTime), optConfigId, END_OF_TIME), nodeStatusReports)

      case NoRunNoInit =>
        /*
         * Really, this node exists ? Shouldn't we just declare Ragnarök at that point ?
         */
        logger.debug(s"Can not get compliance for node with ID '${nodeId.value}' because it has no configuration id initialised nor sent reports (node just added ?)")
        Seq()


    }

  }



  /**
   * That method only take care of the low level logic of comparing
   * expected reports with actual reports rule by rule. So we expect
   * that something before took care of all the macro-,node-related-states
   * (pending, non compatible version, etc).
   *
   * In that method, if we don't have a report for an expected one, it
   * only can be missing, and if we have reports for other directives,
   * they are unexpected.
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
      mergeInfo                : MergeInfo
      // only report for that nodeid, of type ResultReports,
      // for the correct run, for the correct version
    , executionReports         : Seq[ResultReports]
    , expectedReportsForRun    : Map[SerialedRuleId, RuleNodeExpectedReports]
    , currentExpectedDirectives: Map[SerialedRuleId, RuleNodeExpectedReports]
      // the status to use for ACTUALLY missing reports, i.e for reports for which
      // we have a run but are not here. Basically, it's "missing" when on
      // full compliance and "success" when on changes only.
    , missingReportStatus      : ReportType
  ): Seq[RuleNodeStatusReport] = {


    val complianceForRun: Map[SerialedRuleId, RuleNodeStatusReport] = (for {
      (   SerialedRuleId(ruleId, serial)
        , expectedReport       ) <- expectedReportsForRun
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
    val (computed, newStatus) = ((nil, nil)/: currentExpectedDirectives) { case ( (c,n), (k, expectedReport)) =>
      complianceForRun.get(k) match {
        case None => //the whole rule is new!
          //here, the reports are ACTUALLY pending, not missing.
          val x = buildRuleNodeStatusReport(mergeInfo, Map(k -> expectedReport), PendingReportType)
          (c, n++x)
        case Some(complianceReport) => //use the already computed compliance
          (c:+complianceReport, n)
      }
    }

    ComplianceDebugLogger.trace(s"Compute compliance for node ${mergeInfo.nodeId.value} using: rules for which compliance is based on run reports: ${
      computed.map { x => s"[${x.ruleId.value}->${x.serial}]"}.mkString("")
    };"+s" rule updated since run: ${
      newStatus.map { x => s"${x.ruleId.value}->${x.serial}"}.mkString("[", "][", "]")
    }")

    computed ++ newStatus
  }


  private[this] def getUnexpanded(seq: Seq[Option[String]]): Option[String] = {
    val unexpanded = seq.toSet
    if(unexpanded.size > 1) {
      logger.debug("Several same looking expected component values have different unexpanded value, which is not supported: " + unexpanded.mkString(","))
    }
    unexpanded.head
  }


  private[this] def buildUnexpectedReports(mergeInfo: MergeInfo, reports: Seq[Reports]): Seq[RuleNodeStatusReport] = {
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
    }.toSeq
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
  ): Seq[RuleNodeStatusReport] = {
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
    }.toSeq
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
        ReportLogger.warn(s"Unexpected report for Directive '${r.directiveId.value}', Rule '${r.ruleId.value}' generated on '${r.executionTimestamp}' "+
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
      , cfeVar: Map[String, Seq[String]] = Map()
    )

    val componentMap = expectedComponent.groupedComponentValues
    val valueKind = (ValueKind() /:componentMap) {
      case (kind,  (v, DEFAULT_COMPONENT_KEY)) => kind.copy(none = v +: kind.none )
      case (kind,  (v, u)) =>
        v match {
          case matchCFEngineVars(_) => kind.copy(cfeVar = kind.cfeVar + ((u, v +: kind.cfeVar.getOrElse(u, Seq()))))
          case _ => kind.copy(simple = kind.simple + ((u, v +: kind.simple.getOrElse(u, Seq()))))
        }
    }

    // Regroup all None value into None component Value
    // There should only one None report per component and
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
        //Here, we DO use expanded values to do the match.
        val reports = purgedReports.filter(r => values.contains(r.keyValue))
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
    val remainingReports = purgedReports.filterNot(x => usedReports.exists(y => x == y))

    /*
     * Look into remaining reports for CFEngine variables, values are accumulated and we remove reports while we use them.
     *
     * We must sort patterns
     */
    def extractCFVarsFromReports (
        cfVars :List[(String, Seq[String])]
      , values: Seq[ComponentValueStatusReport]
      , remainingReports : Seq[Reports]
    ) : (Seq[ComponentValueStatusReport], Seq[Reports]) = {
      cfVars match {
        case Nil =>
          // Nothing to return results
          (values, remainingReports)
        case (unexpanded, patterns) :: rest =>
          var remains = remainingReports
          // Match our reports with our pattern, If we do not find a report, we will generate a NoAnswer MessageReport
          val matchingReports : Seq[(Option[Reports],MessageStatusReport)]= {
            // Accumulate result for each pattern
            // we can have several reports matching a pattern, but we take only the first one so that we limit (kind of)
            // the unexpected resulting from several reports being able to match several pattern.
            // Real unexpected will be processed at the end.
            (Seq[(Option[Reports],MessageStatusReport)]() /: patterns) {
              case (matching, pattern) =>
                matching :+ (remains.find( _.keyValue.matches(pattern)) match {
                  // The pattern is not found in the reports, Create a NoAnswer
                  case None =>
                    (None, MessageStatusReport(noAnswerType, None))
                  // We match a report, treat it
                  case Some(report) =>
                    remains = remains.diff(Seq(report))
                    val messageReport = MessageStatusReport(ReportType(report), report.message)
                    (Some(report),messageReport)
                })
            }
          }

          // Generate our Value for our unexpanded value
          val messageReports = matchingReports.map(_._2).toList
          val value = ComponentValueStatusReport(unexpanded, unexpanded, messageReports)
          // Remove our used reports
          val usedReports = matchingReports.flatMap(_._1)
          // Process next step
          extractCFVarsFromReports(rest, value +: values, remains)
      }
    }

    // Generate our value for cfengine variables
    val (cfeVarValues, lastReports) = extractCFVarsFromReports (valueKind.cfeVar.mapValues(_.map(replaceCFEngineVars(_))).toList, Seq(), remainingReports)


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

    ComponentStatusReport(
        expectedComponent.componentName
      , ComponentValueStatusReport.merge(unexpectedStatusReports ++ noneValue ++ simpleValues ++ cfeVarValues ++ lastUnexpected)
    )
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
              case 0 if noUnexpectedReports =>  MessageStatusReport(noAnswerType, None) :: Nil
              /* Reports were received for that component, but not for that key, that's a missing report */
              case 0 =>  MessageStatusReport(UnexpectedReportType, None) :: Nil
              //check if cardinality is ok
              case x if x == cardinality =>
                filteredReports.map { r =>
                  MessageStatusReport(ReportType(r), r.message)
                }.toList
              case _ =>
                filteredReports.map(r => MessageStatusReport(UnexpectedReportType, r.message)).toList
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
