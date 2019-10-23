package com.normation.rudder.domain.eventlog

import com.normation.eventlog._
import com.normation.utils.HashcodeCaching


sealed trait WorkflowEventLog extends EventLog { override final val eventLogCategory = WorkflowLogCategory }

final case class WorkflowStepChanged(
    override val eventDetails : EventLogDetails
) extends WorkflowEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = WorkflowStepChanged.eventType
}

object WorkflowStepChanged extends EventLogFilter {
  override val eventType = WorkflowStepChangedEventType

  override def apply(x : (EventLogType, EventLogDetails)) : WorkflowStepChanged = WorkflowStepChanged(x._2)
}
