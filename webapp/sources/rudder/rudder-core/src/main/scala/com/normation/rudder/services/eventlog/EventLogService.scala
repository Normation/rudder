package com.normation.rudder.services.eventlog

import com.normation.errors.IOResult
import com.normation.eventlog.EventLog
import com.normation.eventlog.EventLogRequest
import com.normation.rudder.tenants.QueryContext

trait EventLogService {

  def getUserEventLogs(filter: Option[EventLogRequest])(implicit qc: QueryContext): IOResult[Seq[EventLog]]

  def getUserEventLogCount(filter: Option[EventLogRequest])(implicit qc: QueryContext): IOResult[Long]

}
