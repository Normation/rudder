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

package com.normation.rudder.domain.reports.bean

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.PolicyInstanceId
import com.normation.rudder.domain.policies.ConfigurationRuleId
import org.joda.time._
import org.slf4j.{Logger,LoggerFactory}
import com.normation.utils.HashcodeCaching
/**
 * Store the reports entry from the execution
 * Contains : the datetime at which it was generated, the cr/policyinstanceid, 
 * the server on which it has been run, the severity, and the message,
 * and the serial (id of generation), the component and its key value
 * @author Nicolas CHARLES
 *
 */
trait Reports {
  val executionDate : DateTime
  val configurationRuleId : ConfigurationRuleId
  val policyInstanceId : PolicyInstanceId
  val nodeId : NodeId
  val serial : Int
  val component : String
  val keyValue : String // the key of the component
  val executionTimestamp : DateTime
  val severity : String
  val message : String
}

sealed case class ResultSuccessReport(val executionDate : DateTime,
		val configurationRuleId : ConfigurationRuleId,
    val policyInstanceId : PolicyInstanceId,
    val nodeId : NodeId,
    val serial : Int,
    val component : String,
    val keyValue : String,
    val executionTimestamp : DateTime,
    val message : String) extends Reports with HashcodeCaching {
  val severity = Reports.RESULT_SUCCESS
}

sealed case class ResultRepairedReport(val executionDate : DateTime,
		val configurationRuleId : ConfigurationRuleId,
    val policyInstanceId : PolicyInstanceId,
    val nodeId : NodeId,
    val serial : Int,
    val component : String,
    val keyValue : String,
    val executionTimestamp : DateTime,
    val message : String) extends Reports with HashcodeCaching {
  val severity = Reports.RESULT_REPAIRED
}

sealed case class ResultErrorReport(val executionDate : DateTime,
		val configurationRuleId : ConfigurationRuleId,
    val policyInstanceId : PolicyInstanceId,
    val nodeId : NodeId,
    val serial : Int,
    val component : String,
    val keyValue : String,
    val executionTimestamp : DateTime,
    val message : String) extends Reports with HashcodeCaching {
  val severity = Reports.RESULT_ERROR
}


sealed case class LogRepairedReport(val executionDate : DateTime,
		val configurationRuleId : ConfigurationRuleId,
    val policyInstanceId : PolicyInstanceId,
    val nodeId : NodeId,
    val serial : Int,
    val component : String,
    val keyValue : String,
    val executionTimestamp : DateTime,
    val message : String) extends Reports with HashcodeCaching {
  val severity = Reports.LOG_REPAIRED
}
  
sealed case class LogWarnReport(val executionDate : DateTime,
    val configurationRuleId : ConfigurationRuleId,
    val policyInstanceId : PolicyInstanceId,
    val nodeId : NodeId,
    val serial : Int,
    val component : String,
    val keyValue : String,
    val executionTimestamp : DateTime,
    val message : String) extends Reports with HashcodeCaching {
  val severity = Reports.LOG_WARN
}

sealed case class LogInformReport(val executionDate : DateTime,
    val configurationRuleId : ConfigurationRuleId,
    val policyInstanceId : PolicyInstanceId,
    val nodeId : NodeId,
    val serial : Int,
    val component : String,
    val keyValue : String,
    val executionTimestamp : DateTime,
    val message : String) extends Reports with HashcodeCaching {
  val severity = Reports.LOG_INFO
}

sealed case class LogDebugReport(val executionDate : DateTime,
    val configurationRuleId : ConfigurationRuleId,
    val policyInstanceId : PolicyInstanceId,
    val nodeId : NodeId,
    val serial : Int,
    val component : String,
    val keyValue : String,
    val executionTimestamp : DateTime,
    val message : String) extends Reports with HashcodeCaching {
  val severity = Reports.LOG_DEBUG
}

sealed case class LogTraceReport(val executionDate : DateTime,
    val configurationRuleId : ConfigurationRuleId,
    val policyInstanceId : PolicyInstanceId,
    val nodeId : NodeId,
    val serial : Int,
    val component : String,
    val keyValue : String,
    val executionTimestamp : DateTime,
    val message : String) extends Reports with HashcodeCaching {
  val severity = Reports.LOG_TRACE
}

sealed case class UnknownReport(val executionDate : DateTime,
    val configurationRuleId : ConfigurationRuleId,
		val policyInstanceId : PolicyInstanceId,
    val nodeId : NodeId,
    val serial : Int,
    val component : String,
    val keyValue : String,
    val executionTimestamp : DateTime,
    val message : String) extends Reports with HashcodeCaching {
  val severity = "Unknown"
}

object Reports {
  
  val logger = LoggerFactory.getLogger(classOf[Reports])
  
  def factory(executionDate : DateTime, configurationRuleId : ConfigurationRuleId,
  		policyInstanceId : PolicyInstanceId, nodeId : NodeId,  serial : Int,
  			component : String, keyValue : String,executionTimestamp : DateTime,
        severity : String,  message : String) : Reports = {
    severity.toLowerCase match {
      case RESULT_ERROR => new ResultErrorReport(executionDate, configurationRuleId, policyInstanceId, nodeId, 
              serial, component, keyValue, executionTimestamp, message )
      case RESULT_SUCCESS => new ResultSuccessReport(executionDate, configurationRuleId, policyInstanceId, nodeId, 
              serial, component, keyValue, executionTimestamp, message )
      case RESULT_REPAIRED => new ResultRepairedReport(executionDate, configurationRuleId, policyInstanceId, nodeId, 
              serial, component, keyValue, executionTimestamp, message )

      case LOG_REPAIRED => new LogRepairedReport(executionDate, configurationRuleId, policyInstanceId, nodeId, 
              serial, component, keyValue, executionTimestamp, message )
      
      case LOG_WARN | LOG_WARNING  => new LogWarnReport(executionDate, configurationRuleId, policyInstanceId, nodeId, 
              serial, component, keyValue, executionTimestamp, message )

      case LOG_INFO | LOG_INFORM => new LogInformReport(executionDate, configurationRuleId, policyInstanceId, nodeId, 
              serial, component, keyValue, executionTimestamp, message )
      
      
      case LOG_DEBUG => new LogDebugReport(executionDate, configurationRuleId, policyInstanceId, nodeId, 
              serial, component, keyValue, executionTimestamp, message )
            
      case LOG_TRACE => new LogTraceReport(executionDate, configurationRuleId, policyInstanceId, nodeId, 
              serial, component, keyValue, executionTimestamp, message )
            
     
      case _ =>
        logger.error("Invalid report type {} for policyInstance {}", severity, policyInstanceId)
        new UnknownReport(executionDate, configurationRuleId, policyInstanceId, nodeId, 
              serial, component, keyValue, executionTimestamp, message)
    }
  }

  def apply(executionDate : DateTime, configurationRuleId : ConfigurationRuleId,
  		policyInstanceId : PolicyInstanceId, nodeId : NodeId,serial : Int,
  			component : String, keyValue : String, executionTimestamp : DateTime,
        severity : String,  message : String) : Reports = {
    factory(executionDate, configurationRuleId, policyInstanceId, nodeId, serial, component, keyValue, executionTimestamp, severity,  message)
  }

  def unapply(report : Reports) = Some(report.executionDate, report.configurationRuleId,
    report.policyInstanceId, report.nodeId, report.serial, report.component, report.keyValue, report.executionTimestamp, report.severity, report.message)

    
  val LOG_TRACE = "log_trace"
  val LOG_DEBUG = "log_debug"
  val LOG_INFO = "log_info"
  val LOG_INFORM = "log_inform"
  val LOG_WARN = "log_warn"
  val LOG_WARNING = "log_warning"
  val LOG_REPAIRED = "log_repaired"
  
  val RESULT_SUCCESS = "result_success"
  val RESULT_REPAIRED = "result_repaired"
  val RESULT_ERROR = "result_error"

}
