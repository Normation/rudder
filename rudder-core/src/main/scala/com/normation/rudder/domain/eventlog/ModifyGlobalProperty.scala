package com.normation.rudder.domain.eventlog

import com.normation.eventlog._
import com.normation.utils.HashcodeCaching


sealed trait ModifyGlobalProperty extends EventLog {
  override final val eventLogCategory = GlobalPropertyEventLogCategory
  def propertyName : String
}

object ModifyGlobalProperty {

   def apply(eventType: ModifyGlobalPropertyEventType, eventDetails : EventLogDetails) : ModifyGlobalProperty  = {
    eventType match {
      case ModifySendServerMetricsEventType => ModifySendServerMetrics(eventDetails)
      case ModifyComplianceModeEventType => ModifyComplianceMode(eventDetails)
      case ModifyHeartbeatPeriodEventType => ModifyHeartbeatPeriod(eventDetails)
    }
   }
}

object ModifyGlobalPropertyEventLogsFilter {
  final val eventList : List[EventLogFilter] =
      ModifySendServerMetrics ::
      ModifyComplianceMode ::
      ModifyHeartbeatPeriod ::
      Nil
}

final case class ModifySendServerMetrics (
    override val eventDetails : EventLogDetails
) extends ModifyGlobalProperty with HashcodeCaching {
  override val cause = None
  override val eventType = ModifySendServerMetrics.eventType
  override val propertyName = "Send metrics"
}

object ModifySendServerMetrics extends EventLogFilter {
  override val eventType = ModifySendServerMetricsEventType

  override def apply(x : (EventLogType, EventLogDetails)) : ModifySendServerMetrics = ModifySendServerMetrics(x._2)
}

final case class ModifyComplianceMode (
    override val eventDetails : EventLogDetails
) extends ModifyGlobalProperty with HashcodeCaching {
  override val cause = None
  override val eventType = ModifyComplianceMode.eventType
  override val propertyName = "Compliance mode"
}

object ModifyComplianceMode extends EventLogFilter {
  override val eventType = ModifyComplianceModeEventType

  override def apply(x : (EventLogType, EventLogDetails)) : ModifyComplianceMode = ModifyComplianceMode(x._2)
}

final case class ModifyHeartbeatPeriod (
    override val eventDetails : EventLogDetails
) extends ModifyGlobalProperty with HashcodeCaching {
  override val cause = None
  override val eventType = ModifyHeartbeatPeriod.eventType
  override val propertyName = "Heartbeat period"
}

object ModifyHeartbeatPeriod extends EventLogFilter {
  override val eventType = ModifyHeartbeatPeriodEventType

  override def apply(x : (EventLogType, EventLogDetails)) : ModifyHeartbeatPeriod = ModifyHeartbeatPeriod(x._2)
}
