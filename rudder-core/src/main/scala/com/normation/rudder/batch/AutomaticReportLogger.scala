/*
*************************************************************************************
* Copyright 2012 Normation SAS
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

package com.normation.rudder.batch

import com.normation.rudder.domain.logger.AllReportLogger
import com.normation.rudder.domain.reports.bean.Reports
import com.normation.rudder.repository._
import com.normation.rudder.services.nodes.NodeInfoService
import net.liftweb.actor._
import net.liftweb.common._
import com.normation.utils.UuidRegex
import com.normation.rudder.domain.nodes.NodeInfo



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
    propertyRepository    : RudderPropertiesRepository
  , reportsRepository   : ReportsRepository
  , ruleRepository      : RoRuleRepository
  , directiveRepository : RoDirectiveRepository
  , nodeInfoService     : NodeInfoService
  , reportLogInterval   : Int
) extends Loggable {

  private val propertyName = "rudder.batch.reports.logInterval"

  if (reportLogInterval < 1) {
    logger.info("Disable dynamic group updates sinces property %s is 0 or negative".format(propertyName))
  } else {
    logger.trace("***** starting Automatic Report Logger batch *****")
    (new LAAutomaticReportLogger) ! StartAutomaticReporting
  }

  /**
   * Actor for non compliant logging purpose
   */
  private class LAAutomaticReportLogger extends LiftActor with Loggable {

    /*
     * Last id processed
     */
    private[this] var lastId   = propertyRepository.getReportLoggerLastId
    /*
     * List of all reports kind processed by the logger
     */
    private[this] var Reportskind = List(Reports.LOG_REPAIRED,Reports.RESULT_ERROR,Reports.RESULT_REPAIRED,Reports.LOG_WARN)

    private[this] val reportLineTemplate = "[%s] N: %s S: [%s] R: %s D: %s T: %s C: [%s] V: [%s] %s"

    override protected def messageHandler = {
      case StartAutomaticReporting =>


      lastId match {
        // Report logger was not running before, try to log the last hundred reports and initialize lastId
        case Empty =>
          logger.warn("Automatic report logger has never run, logging latest 100 non compliant reports")
          reportsRepository.getLastHundredErrorReports(Reportskind) match {
            case Full(hundredReports) =>
              val id = hundredReports.headOption match {
                // None means this is a new rudder without any reports, don't log anything, current id is 0
                case None =>
                  logger.warn("There is no reports to log")
                  0

                  // return last id and report the latest 100 non compliant reports
                case Some(report) =>
                  logReports(hundredReports.map(_._1).reverse)
                  report._2
              }
              updateLastId(id)

            case eb:EmptyBox =>
              logger.error("report logger could not fetch latest 100 non compliant reports, retry on next run",eb)
          }

        case Full(last) =>
          logger.trace("***** get current log id")

          reportsRepository.getHighestId match {
            case Full(currentId) if (currentId > last) =>
              logger.trace("***** log report beetween ids %d and %d".format(last,currentId))
              reportsRepository.getErrorReportsBeetween(last,currentId,Reportskind) match {
                case Full(reports) =>
                  logReports(reports)
                  updateLastId(currentId)
                case eb:EmptyBox =>
                  logger.error("report logger could not fetch latest non compliant reports, try on next run",eb)
              }

            case _ =>
              logger.trace("***** no reports to log")
          }

        case Failure(message,_,_) =>
          logger.error("could not fetch last id, don't log anything, wait next run, cause is %s".format(message))
      }

      //schedule next log, every minute
      LAPinger.schedule(this, StartAutomaticReporting, reportLogInterval*1000L*60)
      () //ok for the unit value discarded

      case _ =>
        logger.error("Wrong message received by non compliant reports logger, do nothing")
    }


    def updateLastId(newId : Int) : Unit = {
      propertyRepository.updateReportLoggerLastId(newId) match {
        case f:Full[_]    =>
          lastId = f
        case eb:EmptyBox  =>
          logger.error("could not update last id with id %d, retry now".format(newId),eb)
          updateLastId(newId : Int)
      }
    }

    def logReports(reports : Seq[Reports]) = {
      for {
        allNodes <- nodeInfoService.getAll
        report <- reports
      } logReport(report, allNodes)
    }
    /*
     * Transform to log line and log a report with the appropriate kind
     */
    def logReport(report:Reports, allNodes: Set[NodeInfo]) = {
      val execDate = report.executionDate.toString("yyyy-MM-dd HH:mm:ssZ")

      val ruleId =
        if(UuidRegex.isValid(report.ruleId.value))
          report.ruleId.value.toUpperCase()
         else
          report.ruleId.value

      val rule = "%s [%s]".format(
            ruleId
          , ruleRepository.get(report.ruleId).map(_.name).openOr("Unknown rule")
          )

      val nodeId =
        if(UuidRegex.isValid(report.nodeId.value))
            report.nodeId.value.toUpperCase()
          else
            report.nodeId.value

      val node :String = "%s [%s]".format(
            nodeId
          , allNodes.find( _.id == report.nodeId).map(_.hostname).getOrElse("Unknown node")
          )

      val directiveId =
        if(UuidRegex.isValid(report.directiveId.value))
            report.directiveId.value.toUpperCase()
          else
            report.directiveId.value

      val directive : String = "%s [%s]".format(
            directiveId
          , directiveRepository.getDirective(report.directiveId).map(_.name).openOr("Unknown directive")
          )

     val techniqueName = directiveRepository.getActiveTechnique(report.directiveId).map(_.techniqueName).openOr("Unknown technique id")
     val techniqueVersion = directiveRepository.getDirective(report.directiveId).map(_.techniqueVersion.toString).openOr("N/A")
     val technique = "%s/%s".format(techniqueName,techniqueVersion)

      AllReportLogger.FindLogger(report.severity)(reportLineTemplate.format(execDate,node,report.severity,rule,directive,technique,report.component,report.keyValue,report.message))
    }
  }
}