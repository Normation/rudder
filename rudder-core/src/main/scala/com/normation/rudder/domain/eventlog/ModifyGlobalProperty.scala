package com.normation.rudder.domain.eventlog

import com.normation.eventlog._
import com.normation.utils.HashcodeCaching


sealed trait ModifyGlobalProperty extends EventLog {
  override final val eventLogCategory = GlobalPropertyEventLogCategory
  def propertyName : String
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


object ModifyGlobalProperty {

   def apply(eventType: ModifyGlobalPropertyEventType, eventDetails : EventLogDetails) : ModifyGlobalProperty  = {
    eventType match {
      case ModifySendServerMetricsEventType => ModifySendServerMetrics(eventDetails)
    }
   }
}


object ModifyGlobalPropertyEventLogsFilter {
  final val eventList : List[EventLogFilter] =
      ModifySendServerMetrics ::
      Nil
}