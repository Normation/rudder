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
  def configIdForExpectedReports: Option[NodeConfigId]
}

sealed trait NoReport extends RunAndConfigInfo

sealed trait Unexpected extends RunAndConfigInfo

sealed trait Ok extends RunAndConfigInfo

//a marker trait which indicate that we want to
//have the details of the run
sealed trait InterestingRun extends RunAndConfigInfo {
  def dateTime: DateTime
}

/*
 * Really, that node exists ?
 */
case object NoRunNoInit extends NoReport {
  val configIdForExpectedReports = None
}


/*
 * No report of interest (either none, or
 * some but too old for our situation)
 */
case class NoReportInInterval(
  expectedConfig: NodeConfigIdInfo
) extends NoReport {
  val configIdForExpectedReports = Some(expectedConfig.configId)
}


/*
 * That node does not have any entry at all:
 * we never generated a version for it.
 * We can't have a version for the run
 * (or it is some weird data lost in the server)
 */
case class VersionNotInitialized(
    dateTime  : DateTime
) extends Unexpected with InterestingRun {
  val configIdForExpectedReports = None
}

/*
 * the case where we have a version on the run,
 * versions are init in the server for that node,
 * and we don't have a version is an error
 */
case class UnexpectedVersion(
    dateTime  : DateTime
  , expected  : NodeConfigIdInfo
  , runVersion: Option[NodeConfigId]
) extends Unexpected with InterestingRun {
  val configIdForExpectedReports = Some(expected.configId)
}

case class Pending(
  expectedConfig: NodeConfigIdInfo
) extends NoReport {
  val configIdForExpectedReports = Some(expectedConfig.configId)
}


case class CheckChanges(
    dateTime: DateTime
  , config: NodeConfigIdInfo
) extends Ok with InterestingRun{
  val configIdForExpectedReports = Some(config.configId)
}

case class CheckCompliance(
    dateTime: DateTime
  , config: NodeConfigIdInfo
) extends Ok with InterestingRun{
  val configIdForExpectedReports = Some(config.configId)
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
  private[this] final case class MergeInfo(nodeId: NodeId, run: Option[DateTime], configId: Option[NodeConfigId])

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
  final val GRACE_TIME_PENDING = 5


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
      nodeIds          : Map[NodeId, Duration]
    , runs             : Map[NodeId, Option[AgentRun]]
    , nodeConfigIdInfos: Map[NodeId, Option[Seq[NodeConfigIdInfo]]]
    , complianceMode   : ComplianceMode
  ): Map[NodeId, RunAndConfigInfo] = {

    val now = DateTime.now

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

      nodeIds.map { case (nodeId, graceDuration) => (nodeId, {
        val optRun = runs.getOrElse(nodeId, None)
        val optInfo = nodeConfigIdInfos.getOrElse(nodeId, None)

        (optRun, optInfo) match {
          case (None, None) =>
            NoRunNoInit

          case (None, Some(configs)) =>
            if(configs.isEmpty) {
              NoRunNoInit
            } else {
              val currentConfig = configs.maxBy(_.creation.getMillis)

              //in change only, it's just no report
              complianceMode match {
                case ChangesOnly(_) => NoReportInInterval(currentConfig)
                case FullCompliance =>
                  if(currentConfig.creation.plus(graceDuration).isBefore(now)) {
                    NoReportInInterval(currentConfig)
                  } else {
                    Pending(currentConfig)
                  }
              }
            }

          case (Some(AgentRun(AgentRunId(_, t), None, _)), None) =>
            VersionNotInitialized(t)

          case (Some(AgentRun(AgentRunId(_, t), None, _)), Some(configs)) =>
            if(configs.isEmpty) {
              VersionNotInitialized(t)
            } else {
              /*
               * Here, we want to check two things:
               * - does the run should have contain a config id ?
               *   It should if the oldest config was created too long ago
               *
               * - else, if in change only, we are obligatory in a run for
               *   the oldest run. In compliance, we look at the most recent
               *   config and decide between pending / no answer
               */


              val configForRun = findConfigForRun(t, configs)

              val oldest = configs.minBy( _.creation.getMillis)

              if(oldest.creation.plus(graceDuration).isBefore(t) ) {
                UnexpectedVersion(t, configs.maxBy( _.creation.getMillis), None)
              } else {
                val current = configs.maxBy( _.creation.getMillis )
                complianceMode match {
                  case ChangesOnly(_) => CheckChanges(t, current)
                  case FullCompliance =>
                    if(current.creation.plus(graceDuration).isBefore(t)) {
                      NoReportInInterval(current)
                    } else {
                      Pending(current)
                    }
                }
              }
            }

          case (Some(AgentRun(AgentRunId(_, t), Some(rv), _)), None) =>
            //that seems to indicate a bug, the node should not be able to have
            //a version without having any information in the table.
            //migration from an other Rudder ? table deleted ?
            logger.debug(s"Agent for node '${nodeId.value}' sent the node config identifier '${rv.value}' which is not known by Rudder. Policy for that node should be regenerated" )
            //in all case, we want to act as if the node didn't had any config id.
            VersionNotInitialized(t)

          case (Some(AgentRun(AgentRunId(_, t), Some(rv), _)), Some(configs)) =>
            if(configs.isEmpty) {
              //error: we have a MISSING config id. Contrary to the case where any config id is missing
              //for the node, here we have a BAD id.
              VersionNotInitialized(t)
            } else {
              findVersionById(rv, configs) match {
                case None =>
                  //it's a bad version. In all case, we say that the expected one
                  //is the last
                  UnexpectedVersion(t, configs.maxBy( _.creation.getMillis ), Some(rv))

                case Some(v) => //nominal case !

                  //check if the run is not too old for the version, i.e if endOflife + grace is before run
                  v.endOfLife match {
                    case None =>
                      complianceMode match {
                        case ChangesOnly(_) => CheckChanges(t, v)
                        case FullCompliance =>
                          if(t.plus(graceDuration).isBefore(now)) {
                            NoReportInInterval(v)
                          } else {
                            CheckCompliance(t, v)
                          }
                      }

                    case Some(eol) =>
                      if(eol.plus(graceDuration).isBefore(t)) {
                        //in all case, we should have had a more recent run
                        UnexpectedVersion(t, configs.maxBy( _.creation.getMillis), Some(rv))
                      } else {
                        complianceMode match {
                          case ChangesOnly(_) => CheckChanges(t, v)
                          case FullCompliance =>
                            val expected = configs.maxBy( _.creation.getMillis )
                            if(t.plus(graceDuration).isBefore(now)) {
                              NoReportInInterval(expected)
                            } else {
                              Pending(expected)
                            }
                        }
                      }
                  }
              }
            }
        }
      } ) }.toMap
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
      nodeId                 : NodeId
      // run info: if we have a run, we have a datetime for it
      // and perhaps a configId
    , runInfo                : RunAndConfigInfo
    , expectedReports        : Seq[RuleExpectedReports]
    , agentExecutionReports  : Seq[Reports]
    // this is the agent execution interval, in minutes
  ) : Seq[RuleNodeStatusReport] = {


    //now, find the actual expect reports for that node
    val expectedRules: Map[(RuleId, Int),Seq[DirectiveExpectedReports]] = expectedReports.flatMap { r =>
      val directives = r.directivesOnNodes.collect { case x if(x.nodeConfigurationIds.isDefinedAt(nodeId)) =>
         x.directiveExpectedReports
      }.flatten
      if(directives.isEmpty) None
      else Some( (r.ruleId, r.serial) -> directives)
    }.toMap

    //only interesting reports: for that node, with a status
    val nodeStatusReports = agentExecutionReports.collect{ case r: ResultReports if(r.nodeId == nodeId) => r }.groupBy(_.ruleId)


    runInfo match {
      case NoRunNoInit =>
        /*
         * Really, this node exists ? Shouldn't we just declare Ragnarök at that point ?
         */
        Seq()


      case NoReportInInterval(expectedConfig) =>

        buildRuleNodeStatusReport(
            MergeInfo(nodeId, None, Some(expectedConfig.configId))
          , expectedRules
          , NoAnswerReportType
        )

      case UnexpectedVersion(runTime, expectedVersion, optRunVersion) =>
        //mark all report of run unexpected,
        //all expected missing

        buildRuleNodeStatusReport(
            MergeInfo(nodeId, Some(runTime), Some(expectedVersion.configId))
          , expectedRules
          , MissingReportType
        ) ++ nodeStatusReports.map { case (ruleId, reports) =>
                RuleNodeStatusReport(
                    nodeId
                  , ruleId
                  , reports.head.serial //can not be empty because of groupBy
                  , Some(runTime)
                  , optRunVersion
                  , DirectiveStatusReport.merge(buildUnexpectedReport(reports))
                )
        }


      case VersionNotInitialized(runTime) =>
        // these reports where not expected
         buildRuleNodeStatusReport(
            MergeInfo(nodeId, Some(runTime), None)
          , expectedRules
          , UnexpectedReportType
        )

      case Pending(expectedConfig) =>
         buildRuleNodeStatusReport(
            MergeInfo(nodeId, None, Some(expectedConfig.configId))
          , expectedRules
          , PendingReportType
        )

      case CheckChanges(runTime, config) =>
        mergeCompareByRule(
            MergeInfo(nodeId, Some(runTime), Some(config.configId))
          , nodeStatusReports
          , expectedRules
          , SuccessReportType
        )

      case CheckCompliance(runTime, config) =>
        mergeCompareByRule(
            MergeInfo(nodeId, Some(runTime), Some(config.configId))
          , nodeStatusReports
          , expectedRules
          , MissingReportType
        )

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
   */
  private[reports] def mergeCompareByRule(
      mergeInfo            : MergeInfo
      // only report for that nodeid, of type ResultReports,
      // for the correct run, for the correct version
    , executionReports   : Map[RuleId, Seq[ResultReports]]
    , expectedDirectives : Map[(RuleId, Int), Seq[DirectiveExpectedReports]]
    , missingReportStatus: ReportType
  ): Seq[RuleNodeStatusReport] = {

    (for {
      (   (ruleId, serial)
        , directives       ) <- expectedDirectives
      directiveStatusReports =  {
                                   //here, we had at least one report, even if it not a ResultReports (i.e: run start/end is meaningful

                                   val reportsForThatNodeRule: Seq[ResultReports] = executionReports.getOrElse(ruleId, Seq())

                                   val (goodReports, badReports) =  reportsForThatNodeRule.partition(r => r.serial == serial)

                                   val reports = goodReports.groupBy(x => (x.directiveId, x.component) )

                                   val expectedComponents = (for {
                                     directive <- directives
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
                                       ComponentStatusReport(c.componentName, c.groupedComponentValues.map { case(v,u) => (v ->
                                         ComponentValueStatusReport(v, u, MessageStatusReport(missingReportStatus, "") :: Nil)
                                       )}.toMap)
                                     ))
                                   }

                                   //unexpected contains the one with unexpected key and all non matching serial/version
                                   val unexpected = buildUnexpectedReport(
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
      RuleNodeStatusReport(
          mergeInfo.nodeId
        , ruleId
        , serial
        , mergeInfo.run
        , mergeInfo.configId
        , DirectiveStatusReport.merge(directiveStatusReports)
      )
    }).toSeq
  }


  private[this] def getUnexpanded(seq: Seq[Option[String]]): Option[String] = {
    val unexpanded = seq.toSet
    if(unexpanded.size > 1) {
      logger.debug("Several same looking expected component values have different unexpanded value, which is not supported: " + unexpanded.mkString(","))
    }
    unexpanded.head
  }



  /**
   * Build unexpected reports for the given reports
   */
  private[reports] def buildUnexpectedReport(reports: Seq[Reports]): Seq[DirectiveStatusReport] = {
    reports.map { r =>
      DirectiveStatusReport(r.directiveId, Map(r.component ->
        ComponentStatusReport(r.component, Map(r.keyValue ->
          ComponentValueStatusReport(r.keyValue, None, MessageStatusReport(UnexpectedReportType, r.message) :: Nil)
        )))
      )
    }
  }

  private[reports] def buildRuleNodeStatusReport(
      mergeInfo      : MergeInfo
    , expectedReports: Map[(RuleId, Int), Seq[DirectiveExpectedReports]]
    , status         : ReportType
    , message        : String = ""
  ): Seq[RuleNodeStatusReport] = {
    expectedReports.map { case ((ruleId, serial), directives) =>
      val d = directives.map { d =>
        DirectiveStatusReport(d.directiveId,
          d.components.map { c =>
            (c.componentName, ComponentStatusReport(c.componentName,
              c.groupedComponentValues.map { case(v,uv) =>
                (v, ComponentValueStatusReport(v, uv, MessageStatusReport(status, "") :: Nil) )
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
      )
    }.toSeq
  }


  /**
   * Allows to calculate the status of component for a node.
   * We don't deal with interpretation at that level,
   * in particular regarding the not received / etc status, we
   * simply put "no answer" for each case where we don't
   * have an actual report corresponding to the expected one
   *
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
         , None // <- is it really None that we set there ?
         , List(MessageStatusReport(UnexpectedReportType, unexpectedReport.message))
        )
      }

    }



    //container for the kind of value for cfe
    case class ValueKind(
        none  : Seq[(String, Option[String])] = Seq()
      , simple: Seq[(String, Option[String])] = Seq()
      , cfeVar: Seq[(String, Option[String])] = Seq()
    )

    //now, we group values by what they look like: None, cfengine variable, simple value
    val valueKind = (ValueKind()/:expectedComponent.groupedComponentValues) {
      case (kind,  n@("None", _)) => kind.copy(none = kind.none :+ n)
      case (kind,  v@(value, unexpanded)) =>
        value match {
          case matchCFEngineVars(_) => kind.copy(cfeVar = kind.cfeVar :+ v)
          case _ => kind.copy(simple = kind.simple :+ v)
        }
    }

    //non report and simple values are pairs of
    //ComponentValueStatus / reports used
    val noneReport = if(valueKind.none.isEmpty){
      (Seq(), Seq())
    } else {
      val reports = purgedReports.filter(r => r.keyValue == "None")
      (Seq(buildComponentValueStatus(
          "None"
        , reports
        , unexpectedStatusReports.isEmpty
        , valueKind.none.size
        , noAnswerType
        , getUnexpanded(valueKind.none.map( _._2))
      )), reports)
    }

    val simpleValueReports = {
      for {
        (value, seq) <- valueKind.simple.groupBy( _._1 ).toSeq
      } yield {
        val reports = purgedReports.filter(r => r.keyValue == value)
        val status = buildComponentValueStatus(
            value
          , reports
          , unexpectedStatusReports.isEmpty
          , seq.size
          , noAnswerType
          , getUnexpanded(seq.map( _._2))
        )
        (status, reports)
      }
    }

    val usedReports = noneReport._2 ++ simpleValueReports.map( _._2).flatten
    val remainingReports = purgedReports.filterNot(x => usedReports.exists(y => x == y))

    val cfeVarReports = for {
      (pattern, seq) <- valueKind.cfeVar.groupBy( x => replaceCFEngineVars(x._1) )
    } yield {
      /*
       * Here, for a given pattern, we can have different source cfengine vars, and
       * a list of report that matches.
       * We have no way to know what source cfe var goes to which reports.
       * We can only check that the total number of expected reports for a given
       * pattern is equal to the number of report that matches that pattern.
       * If it's not the case, all is "Unexpected".
       * Else, randomly assign values to source pattern
       */

      val matchingReports = remainingReports.filter(r => r.keyValue.matches(pattern))

      if(matchingReports.size > seq.size) {
        seq.map { case (value, unexpanded) =>
          ComponentValueStatusReport(value, unexpanded, MessageStatusReport(UnexpectedReportType, None)::Nil)
        }
      } else {
        //seq is >= matchingReports
        (seq.zip(matchingReports).map { case ((value, unexpanded), r) =>
          ComponentValueStatusReport(value, unexpanded, MessageStatusReport(ReportType(r), r.message)::Nil)
        } ++
        seq.drop(matchingReports.size).map { case (value, unexpanded) =>
          ComponentValueStatusReport(value, unexpanded, MessageStatusReport(noAnswerType, None)::Nil)
        })
      }
    }


    ComponentStatusReport(
        expectedComponent.componentName
      , ComponentValueStatusReport.merge(unexpectedStatusReports ++ noneReport._1 ++ simpleValueReports.map(_._1) ++ cfeVarReports.flatten)
    )
  }

  /*
   * An utility method that fetches the proper status and messages
   * of a component value.
   */
  private[this] def buildComponentValueStatus(
      currentValue        : String
    , filteredReports     : Seq[Reports]
    , noUnexpectedReports : Boolean
    , cardinality         : Int
    , noAnswerType        : ReportType
    , unexpandedValue     : Option[String]
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

    ComponentValueStatusReport(
        currentValue
      , unexpandedValue
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
