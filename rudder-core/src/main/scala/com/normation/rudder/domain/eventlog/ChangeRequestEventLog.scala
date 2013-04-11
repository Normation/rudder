package com.normation.rudder.domain.eventlog

import com.normation.eventlog.EventActor
import org.joda.time.DateTime
import com.normation.utils._
import com.normation.eventlog._
import com.normation.rudder.domain.workflows.ChangeRequest
import com.normation.rudder.domain.policies.SimpleDiff


sealed trait ChangeRequestEventLog extends EventLog { override final val eventLogCategory = ChangeRequestLogCategory }

final case class AddChangeRequest(
    override val eventDetails : EventLogDetails
) extends ChangeRequestEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = AddChangeRequest.eventType
}

object AddChangeRequest extends EventLogFilter {
  override val eventType = AddChangeRequestEventType

  override def apply(x : (EventLogType, EventLogDetails)) : AddChangeRequest = AddChangeRequest(x._2)
}

final case class DeleteChangeRequest(
    override val eventDetails : EventLogDetails
) extends ChangeRequestEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = DeleteChangeRequest.eventType
}

object DeleteChangeRequest extends EventLogFilter {
  override val eventType = DeleteChangeRequestEventType

  override def apply(x : (EventLogType, EventLogDetails)) : DeleteChangeRequest = DeleteChangeRequest(x._2)
}

final case class ModifyChangeRequest(
    override val eventDetails : EventLogDetails
) extends ChangeRequestEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = ModifyChangeRequest.eventType
}

object ModifyChangeRequest extends EventLogFilter {
  override val eventType = ModifyChangeRequestEventType

  override def apply(x : (EventLogType, EventLogDetails)) : ModifyChangeRequest = ModifyChangeRequest(x._2)
}

object ChangeRequestLogsFilter {
  final val eventList : List[EventLogFilter] = List(
      AddChangeRequest
    , DeleteChangeRequest
    , ModifyChangeRequest
    )
}

/*
 * Event log on change request
 */
sealed trait ChangeRequestDiff {
  def changeRequest : ChangeRequest
  def diffName : Option[SimpleDiff[String]] = None
  def diffDescription : Option[SimpleDiff[String]] = None
}

case class AddChangeRequestDiff(
    changeRequest: ChangeRequest
)extends ChangeRequestDiff

case class DeleteChangeRequestDiff(
    changeRequest: ChangeRequest
) extends ChangeRequestDiff

object ModifyToChangeRequestDiff {
  def apply(newCr : ChangeRequest, oldCr : ChangeRequest) : ModifyToChangeRequestDiff = {
    val modName =
      if (newCr.info.name == oldCr.info.name)
        None
      else
        Some(SimpleDiff(oldCr.info.name,newCr.info.name))
    val modDesc =
      if (newCr.info.description == oldCr.info.description)
        None
      else
        Some(SimpleDiff(oldCr.info.description,newCr.info.description))
    ModifyToChangeRequestDiff(newCr,modName,modDesc)
  }
}
case class ModifyToChangeRequestDiff(
    changeRequest : ChangeRequest
  , override val diffName        : Option[SimpleDiff[String]]
  , override val diffDescription : Option[SimpleDiff[String]]
) extends ChangeRequestDiff


