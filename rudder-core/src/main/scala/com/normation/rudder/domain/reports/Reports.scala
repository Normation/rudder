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

package com.normation.rudder.domain.reports

import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import com.normation.utils.HashcodeCaching

/**
 * Define one "line" of reports from an agent execution
 * (so for a given run, their would be a lot of them)
 *
 * This is a direct mapping of what the agent actually
 * send to the server via syslog.
 *
 * Contains : the datetime at which it was generated, the rule/directive,
 * the server on which it has been run, the severity, and the message,
 * and the serial (id of generation), the component and its key value
 */
sealed trait Reports {
  val executionDate      : DateTime //the execution timestamp of that report
  val ruleId             : RuleId
  val directiveId        : DirectiveId
  val nodeId             : NodeId
  val serial             : Int
  val component          : String
  val keyValue           : String // component value
  val executionTimestamp : DateTime //the start run timestamp
  val severity           : String
  val message            : String
}

//two marker trait to split between result and log reports
sealed trait ResultReports extends Reports
sealed trait LogReports extends Reports

final case class ResultSuccessReport(
    executionDate      : DateTime
  , ruleId             : RuleId
  , directiveId        : DirectiveId
  , nodeId             : NodeId
  , serial             : Int
  , component          : String
  , keyValue           : String
  , executionTimestamp : DateTime
  , message            : String
) extends ResultReports with HashcodeCaching {
  val severity = Reports.RESULT_SUCCESS
}

final case class ResultNotApplicableReport(
    executionDate      : DateTime
  , ruleId             : RuleId
  , directiveId        : DirectiveId
  , nodeId             : NodeId
  , serial             : Int
  , component          : String
  , keyValue           : String
  , executionTimestamp : DateTime
  , message            : String
) extends ResultReports with HashcodeCaching {
  val severity = Reports.RESULT_NOTAPPLICABLE
}

final case class ResultRepairedReport(
    executionDate      : DateTime
  , ruleId             : RuleId
  , directiveId        : DirectiveId
  , nodeId             : NodeId
  , serial             : Int
  , component          : String
  , keyValue           : String
  , executionTimestamp : DateTime
  , message            : String
) extends ResultReports with HashcodeCaching {
  val severity = Reports.RESULT_REPAIRED
}

final case class ResultErrorReport(
    executionDate      : DateTime
  , ruleId             : RuleId
  , directiveId        : DirectiveId
  , nodeId             : NodeId
  , serial             : Int
  , component          : String
  , keyValue           : String
  , executionTimestamp : DateTime
  , message            : String
) extends ResultReports with HashcodeCaching {
  val severity = Reports.RESULT_ERROR
}

final case class UnknownReport(
    executionDate      : DateTime
  , ruleId             : RuleId
  , directiveId        : DirectiveId
  , nodeId             : NodeId
  , serial             : Int
  , component          : String
  , keyValue           : String
  , executionTimestamp : DateTime
  , message            : String
) extends ResultReports with HashcodeCaching {
  val severity = Reports.RESULT_UNKNOWN
}

final case class LogRepairedReport(
    executionDate      : DateTime
  , ruleId             : RuleId
  , directiveId        : DirectiveId
  , nodeId             : NodeId
  , serial             : Int
  , component          : String
  , keyValue           : String
  , executionTimestamp : DateTime
  , message            : String
) extends LogReports with HashcodeCaching {
  val severity = Reports.LOG_REPAIRED
}

final case class LogWarnReport(
    executionDate      : DateTime
  , ruleId             : RuleId
  , directiveId        : DirectiveId
  , nodeId             : NodeId
  , serial             : Int
  , component          : String
  , keyValue           : String
  , executionTimestamp : DateTime
  , message            : String
) extends LogReports with HashcodeCaching {
  val severity = Reports.LOG_WARN
}

final case class LogInformReport(
    executionDate      : DateTime
  , ruleId             : RuleId
  , directiveId        : DirectiveId
  , nodeId             : NodeId
  , serial             : Int
  , component          : String
  , keyValue           : String
  , executionTimestamp : DateTime
  , message            : String
) extends LogReports with HashcodeCaching {
  val severity = Reports.LOG_INFO
}

final case class LogDebugReport(
    executionDate      : DateTime
  , ruleId             : RuleId
  , directiveId        : DirectiveId
  , nodeId             : NodeId
  , serial             : Int
  , component          : String
  , keyValue           : String
  , executionTimestamp : DateTime
  , message            : String
) extends LogReports with HashcodeCaching {
  val severity = Reports.LOG_DEBUG
}

final case class LogTraceReport(
    executionDate      : DateTime
  , ruleId             : RuleId
  , directiveId        : DirectiveId
  , nodeId             : NodeId
  , serial             : Int
  , component          : String
  , keyValue           : String
  , executionTimestamp : DateTime
  , message            : String
) extends LogReports with HashcodeCaching {
  val severity = Reports.LOG_TRACE
}

object Reports {

  val logger = LoggerFactory.getLogger(classOf[Reports])

  def factory(
      executionDate     : DateTime
    , ruleId            : RuleId
    , directiveId       : DirectiveId
    , nodeId            : NodeId
    , serial            : Int
    , component         : String
    , componentValue    : String
    , executionTimestamp: DateTime
    , severity          : String
    , message           : String
  ) : Reports = {
    severity.toLowerCase match {
      case RESULT_ERROR => new ResultErrorReport(executionDate, ruleId, directiveId, nodeId,
              serial, component, componentValue, executionTimestamp, message )

      case RESULT_SUCCESS => new ResultSuccessReport(executionDate, ruleId, directiveId, nodeId,
              serial, component, componentValue, executionTimestamp, message )

      case RESULT_REPAIRED => new ResultRepairedReport(executionDate, ruleId, directiveId, nodeId,
              serial, component, componentValue, executionTimestamp, message )

      case RESULT_NOTAPPLICABLE => new ResultNotApplicableReport(executionDate, ruleId, directiveId, nodeId,
              serial, component, componentValue, executionTimestamp, message )

      case LOG_REPAIRED => new LogRepairedReport(executionDate, ruleId, directiveId, nodeId,
              serial, component, componentValue, executionTimestamp, message )

      case LOG_WARN | LOG_WARNING  => new LogWarnReport(executionDate, ruleId, directiveId, nodeId,
              serial, component, componentValue, executionTimestamp, message )

      case LOG_INFO | LOG_INFORM => new LogInformReport(executionDate, ruleId, directiveId, nodeId,
              serial, component, componentValue, executionTimestamp, message )

      case LOG_DEBUG => new LogDebugReport(executionDate, ruleId, directiveId, nodeId,
              serial, component, componentValue, executionTimestamp, message )

      case LOG_TRACE => new LogTraceReport(executionDate, ruleId, directiveId, nodeId,
              serial, component, componentValue, executionTimestamp, message )

      case _ =>
        logger.error(s"Invalid report type ${severity} for directive ${directiveId}")
        new UnknownReport(executionDate, ruleId, directiveId, nodeId,
              serial, component, componentValue, executionTimestamp, message)
    }
  }

  def apply(
      executionDate      : DateTime
    , ruleId             : RuleId
    , directiveId        : DirectiveId
    , nodeId             : NodeId
    , serial             : Int
    , component          : String
    , componentValue     : String
    , executionTimestamp : DateTime
    , severity           : String
    , message            : String
  ) : Reports = {
    factory(executionDate, ruleId, directiveId, nodeId, serial, component, componentValue, executionTimestamp, severity,  message)
  }

  def unapply(report : Reports) = Some((report.executionDate, report.ruleId,
    report.directiveId, report.nodeId, report.serial, report.component, report.keyValue, report.executionTimestamp, report.severity, report.message))


  val LOG_TRACE       = "log_trace"
  val LOG_DEBUG       = "log_debug"
  val LOG_INFO        = "log_info"
  val LOG_INFORM      = "log_inform"
  val LOG_WARN        = "log_warn"
  val LOG_WARNING     = "log_warning"
  val LOG_REPAIRED    = "log_repaired"

  val RESULT_SUCCESS       = "result_success"
  val RESULT_NOTAPPLICABLE = "result_na"
  val RESULT_REPAIRED      = "result_repaired"
  val RESULT_ERROR         = "result_error"
  val RESULT_UNKNOWN       = "Unknown"
}
