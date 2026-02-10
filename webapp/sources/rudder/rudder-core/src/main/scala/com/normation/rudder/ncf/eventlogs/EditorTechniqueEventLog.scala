package com.normation.rudder.ncf.eventlogs

import com.normation.eventlog.*
import com.normation.rudder.domain.eventlog.*

sealed trait EditorTechniqueEventLog extends EventLog {
  final override val eventLogCategory: EventLogCategory = EditorTechniqueLogCategory
}

final case class AddEditorTechnique(
    override val eventDetails: EventLogDetails
) extends EditorTechniqueEventLog {
  override val cause: Option[Int] = None
  override val eventType = AddEditorTechnique.eventType
}

object AddEditorTechnique extends EventLogFilter {
  override val eventType: EventLogType = AddEditorTechniqueEventType

  override def apply(x: (EventLogType, EventLogDetails)): AddEditorTechnique = AddEditorTechnique(x._2)
}

final case class DeleteEditorTechnique(
    override val eventDetails: EventLogDetails
) extends EditorTechniqueEventLog {
  override val cause: Option[Int] = None
  override val eventType = DeleteEditorTechnique.eventType
}

object DeleteEditorTechnique extends EventLogFilter {
  override val eventType: EventLogType = DeleteEditorTechniqueEventType

  override def apply(x: (EventLogType, EventLogDetails)): DeleteEditorTechnique = DeleteEditorTechnique(x._2)
}

final case class ModifyEditorTechnique(
    override val eventDetails: EventLogDetails
) extends EditorTechniqueEventLog {
  override val cause: Option[Int] = None
  override val eventType = ModifyEditorTechnique.eventType
}

object ModifyEditorTechnique extends EventLogFilter {
  override val eventType: EventLogType = ModifyEditorTechniqueEventType

  override def apply(x: (EventLogType, EventLogDetails)): ModifyEditorTechnique = ModifyEditorTechnique(x._2)
}

object EditorTechniqueEventLogsFilter {
  final val eventList: List[EventLogFilter] = List(
    AddEditorTechnique,
    DeleteEditorTechnique,
    ModifyEditorTechnique
  )
}
