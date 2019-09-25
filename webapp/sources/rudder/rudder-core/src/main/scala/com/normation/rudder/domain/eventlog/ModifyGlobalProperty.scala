package com.normation.rudder.domain.eventlog

import com.normation.eventlog._

final case class ModifyGlobalProperty(
    eventType: ModifyGlobalPropertyEventType
  , eventDetails : EventLogDetails
) extends EventLog  {
  override final val eventLogCategory = GlobalPropertyEventLogCategory

  override val cause = None
  val propertyName = eventType.propertyName
}

final case class ModifyGlobalPropertyEventFilter (eventType : ModifyGlobalPropertyEventType) extends EventLogFilter {

   def apply(x :(EventLogType,EventLogDetails)) : ModifyGlobalProperty  = ModifyGlobalProperty(eventType,x._2)
}

object ModifyGlobalPropertyEventLogsFilter {

  val eventTypes =
    ModifySendServerMetricsEventType ::
    ModifyComplianceModeEventType ::
    ModifyHeartbeatPeriodEventType ::
    ModifyAgentRunIntervalEventType ::
    ModifyAgentRunSplaytimeEventType  ::
    ModifyAgentRunStartHourEventType ::
    ModifyAgentRunStartMinuteEventType ::
    ModifyRudderSyslogProtocolEventType ::
    ModifyPolicyModeEventType ::
    Nil

  final val eventList : List[EventLogFilter] =
      eventTypes.map(ModifyGlobalPropertyEventFilter)
}
