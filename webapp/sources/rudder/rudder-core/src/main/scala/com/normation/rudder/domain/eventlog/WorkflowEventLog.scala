package com.normation.rudder.domain.eventlog

import com.normation.eventlog.*
sealed trait WorkflowEventLog extends EventLog { final override val eventLogCategory: EventLogCategory = WorkflowLogCategory }

final case class WorkflowStepChanged(
    override val eventDetails: EventLogDetails
) extends WorkflowEventLog {
  override val cause: Option[Int] = None
  override val eventType = WorkflowStepChanged.eventType
}

object WorkflowStepChanged extends EventLogFilter {
  override val eventType: EventLogType = WorkflowStepChangedEventType

  override def apply(x: (EventLogType, EventLogDetails)): WorkflowStepChanged = WorkflowStepChanged(x._2)
}
