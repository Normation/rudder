package com.normation.rudder.services.eventlog

import com.normation.errors.IOResult
import com.normation.eventlog.EventLog
import com.normation.rudder.domain.eventlog.criteria.EventLogCriteriaFilter

trait EventLogService {

  def getUserEventLogs(filter: Option[EventLogCriteriaFilter]): IOResult[Seq[EventLog]]

  def getEventLogCount(filter: Option[EventLogCriteriaFilter]): IOResult[Long]

}
