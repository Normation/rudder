package com.normation.rudder.domain.eventlog

import com.normation.eventlog._
import com.normation.utils.HashcodeCaching

case class ModifyGlobalProperty(
    eventType: ModifyGlobalPropertyEventType
  , eventDetails : EventLogDetails
) extends EventLog  {
  override final val eventLogCategory = GlobalPropertyEventLogCategory

  override val cause = None
  val propertyName = eventType.propertyName
}

case class ModifyGlobalPropertyEventFilter (eventType : ModifyGlobalPropertyEventType) extends EventLogFilter {

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
