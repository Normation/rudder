/*
*************************************************************************************
* Copyright 2012 Normation SAS
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

package com.normation.rudder.batch

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.logger.AllReportLogger
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.Reports
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.ReportsRepository
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.repository.RudderPropertiesRepository
import com.normation.rudder.services.nodes.NodeInfoService

import net.liftweb.actor._
import net.liftweb.common._
import com.normation.rudder.domain.logger.ScheduledJobLogger
import com.normation.box._

/**
 * This object will be used as message for the non compliant reports logger
 */
object StartAutomaticReporting

/**
 * A class that periodically check if there is some new non compliant reports to log.
 *
 * parameter reportLogInterval define the interval between two reports check
 * Informations of logs a are taken from different repository
 */
class AutomaticReportLogger(
    propertyRepository  : RudderPropertiesRepository
  , reportsRepository   : ReportsRepository
  , ruleRepository      : RoRuleRepository
  , directiveRepository : RoDirectiveRepository
  , nodeInfoService     : NodeInfoService
  , reportLogInterval   : Int
) {

  private val propertyName = "rudder.batch.reports.logInterval"
  val logger = ScheduledJobLogger

  if (reportLogInterval < 1) {
    logger.info("Disable dynamic group updates sinces property %s is 0 or negative".format(propertyName))
  } else {
    logger.trace("***** starting Automatic Report Logger batch *****")
    (new LAAutomaticReportLogger) ! StartAutomaticReporting
  }

  /**
   * Actor for non compliant logging purpose
   */
  private class LAAutomaticReportLogger extends SpecializedLiftActor[StartAutomaticReporting.type] {
    val logger = ScheduledJobLogger

    /*
     * List of all reports kind processed by the logger
     */
    private[this] val reportsKind =
      List(
          Reports.LOG_REPAIRED
        , Reports.LOG_WARN
        , Reports.RESULT_ERROR
        , Reports.RESULT_REPAIRED
        , Reports.AUDIT_ERROR
        , Reports.AUDIT_NONCOMPLIANT
      )

    override protected def messageHandler = {
      case StartAutomaticReporting =>
        propertyRepository.getReportLoggerLastId match {
          // Report logger was not running before, try to log the last hundred reports and initialize lastId
          case Empty =>
            logger.warn("Automatic report logger has never run, logging latest 100 non compliant reports")
            val isSuccess = for {
              hundredReports <- reportsRepository.getLastHundredErrorReports(reportsKind)
              nodes          <- nodeInfoService.getAll
              rules          <- ruleRepository.getAll(true).toBox
              directives     <- directiveRepository.getFullDirectiveLibrary().toBox
            } yield {
              val id = hundredReports.headOption match {
                // None means this is a new rudder without any reports, don't log anything, current id is 0
                case None =>
                  logger.warn("There is no reports to log")
                  0l

                // return last id and report the latest 100 non compliant reports
                case Some(report) =>
                  logReports(hundredReports.reverse, nodes, rules.map(r => (r.id, r)).toMap, directives)
                  report._1
              }
              updateLastId(id)
            }

            isSuccess match {
              case eb:EmptyBox =>
                logger.error("report logger could not fetch latest 100 non compliant reports, retry on next run",eb)
              case Full(x) => //
            }

          case Full(lastId) =>
            logger.trace("***** get current highest report id")
            val highest = reportsRepository.getHighestId()
            logger.trace(s"***** highest report id = ${highest} and last processed id = ${lastId}")
            highest match {
              case Full(currentId) if (currentId > lastId) =>
                logReportsBetween(lastId, currentId)

              case _ =>
                logger.trace("***** no reports to log")
            }

          case Failure(message,_,_) =>
            logger.error(s"could not fetch last id, don't log anything, wait next run, cause is ${message}")
        }

        //schedule next log, every minute
        LAPinger.schedule(this, StartAutomaticReporting, reportLogInterval*1000L*60)
        () //ok for the unit value discarded

      case _ =>
        logger.error("Wrong message received by non compliant reports logger, do nothing")
    }

    /*
     * This method handle the logic to process non-compliant reports between the
     * two id in parameter.
     * It is in charge to:
     * - update the last processed report id when needed
     * - split the process in batches so that the memory consumption is bounded
     *
     * Both bounds are inclusive (so both fromId and maxId will be logged if
     * they are non-compliant reports).
     */
    private[this] def logReportsBetween(lastProcessedId: Long, maxId: Long): Unit = {

      /*
       * Log all non-compliant reports between fromId and maxId (both inclusive), limited to batch size max.
       * If 0 reports where found, Full(None) is returned.
       * If > 0 reports were logged, the highest logged report id is returned.
       * Other param are context resources.
       */
      def log(fromId: Long, maxId: Long, batchSize: Int
            , allNodes: Map[NodeId, NodeInfo], rules: Map[RuleId, Rule],  directives: FullActiveTechniqueCategory
      ): Box[Long] = {
        for {
          reports <- reportsRepository.getReportsByKindBeetween(fromId, maxId, batchSize, reportsKind)
        } yield {
          //when we get an empty here, it means that we don't have more non-compliant report
          //in the interval, just return the max id
          val id = logReports(reports, allNodes, rules, directives).getOrElse(maxId)
          logger.debug(s"Wrote non-compliant-reports logs from id '${fromId}' to id '${id}'")
          updateLastId(id)
          id
        }
      }

      /*
       * We need a way to let the gc reclame the memory used by the log seq, so to let "log" scope be closed before
       * next logRec call.
       */
      def logRec(fromId: Long, maxId: Long, batchSize: Int, nodes: Map[NodeId, NodeInfo], rules: Map[RuleId, Rule],  directives: FullActiveTechniqueCategory): Box[Long] = {
        log(fromId, maxId, batchSize, nodes, rules, directives) match {
          case eb: EmptyBox => eb
          case Full(id) =>
            if(id < maxId) {
              //one more time
              //start at +1 because of inclusive bounds
              logRec(id+1, maxId, batchSize, nodes, rules, directives)
            } else {
              // done
              Full(id)
            }
        }
      }

      //start at +1 because of inclusive bounds
      val startAt = lastProcessedId+1
      logger.debug(s"Writting non-compliant-report logs beetween ids ${startAt} and ${maxId} (both incuded)")
      (for {
        nodes      <- nodeInfoService.getAll
        rules      <- ruleRepository.getAll(true).toBox
        directives <- directiveRepository.getFullDirectiveLibrary().toBox
      } yield {
        logRec(startAt, maxId, 10000, nodes, rules.map(r => (r.id, r)).toMap, directives)
      }) match {
          case eb: EmptyBox =>
            logger.error("report logger could not fetch latest non compliant reports, try on next run", eb)
          case Full(id) =>
            logger.trace(s"***** done: log report beetween ids ${startAt} and ${maxId}")
      }
    }

    def updateLastId(newId : Long) : Unit = {
      //don't blow the stack
      def maxTry(tried: Int): Unit = {
        propertyRepository.updateReportLoggerLastId(newId) match {
          case f:Full[_]    =>
            //ok, nothing to do
          case eb:EmptyBox  =>
            if(tried >= 5) {
              logger.error(s"Could not update property 'reportLoggerLastId' for 5 times in a row, trying on new batch")
            } else {
              logger.error(s"Could not update property 'reportLoggerLastId' with id ${newId}, retrying now",eb)
              Thread.sleep(100)
              maxTry(tried+1)
            }
        }
      }
      maxTry(0)
    }

    def logReports(reports : Seq[(Long, Reports)], allNodes: Map[NodeId, NodeInfo], rules: Map[RuleId, Rule],  directives: FullActiveTechniqueCategory): Option[Long] = {
      if(reports.isEmpty) {
        None
      } else {
        val loggedIds = reports.map { case (id, report) =>
          val t         = report.executionDate.toString("yyyy-MM-dd HH:mm:ssZ")
          val s         = report.severity
          val nid       = report.nodeId.value
          val n         = allNodes.get(report.nodeId).map(_.hostname).getOrElse("Unknown node")
          val rid       = report.ruleId.value
          val r         = rules.get(report.ruleId).map(_.name).getOrElse("Unknown rule")
          val did       = report.directiveId.value
          val (d,tn,tv) = directives.allDirectives.get(report.directiveId) match {
                            case Some((at, d)) => (d.name, at.techniqueName, d.techniqueVersion.toString)
                            case _ => ("Unknown directive", "Unknown technique id", "N/A")
                          }
          val c         = report.component
          val v         = report.keyValue
          val m         = report.message

          //exceptionnally use $var in place of ${var} for log format lisibility
          val reportLine = s"[$t] N: $nid [$n] S: [$s] R: $rid [$r] D: $did [$d] T: $tn/$tv C: [$c] V: [$v] $m"

          AllReportLogger.FindLogger(report.severity)(reportLine)

          id
        }
        //return the max, ok since loggedIds.size >= 1
        Some(loggedIds.max)
      }
    }
  }
}
