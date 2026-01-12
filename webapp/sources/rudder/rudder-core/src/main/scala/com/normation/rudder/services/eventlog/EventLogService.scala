package com.normation.rudder.services.eventlog

import com.normation.errors.IOResult
import com.normation.eventlog.EventLog
import com.normation.eventlog.EventLogRequest

trait EventLogService {

  def getUserEventLogs(filter: Option[EventLogRequest]): IOResult[Seq[EventLog]]

  def getEventLogCount(filter: Option[EventLogRequest]): IOResult[Long]

}
