/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
 *************************************************************************************
 *
 * This file is part of Rudder.
 *
 * Rudder is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * In accordance with the terms of section 7 (7. Additional Terms.) of
 * the GNU General Public License version 3, the copyright holders add
 * the following Additional permissions:
 * Notwithstanding to the terms of section 5 (5. Conveying Modified Source
 * Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
 * Public License version 3, when you create a Related Module, this
 * Related Module is not considered as a part of the work and may be
 * distributed under the license agreement of your choice.
 * A "Related Module" means a set of sources files including their
 * documentation that, without modification of the Source Code, enables
 * supplementary functions or services in addition to those offered by
 * the Software.
 *
 * Rudder is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

 *
 *************************************************************************************
 */

package com.normation.rudder.domain.eventlog

import com.normation.eventlog.*
import com.normation.rudder.domain.Constants
import com.normation.rudder.git.GitArchiveId
import com.normation.rudder.git.GitCommitId
import com.normation.utils.DateFormaterService
import scala.xml.*
sealed trait ImportExportEventLog extends EventLog {
  final override val eventLogCategory: EventLogCategory = ImportExportItemsLogCategory
}

sealed trait ImportEventLog extends ImportExportEventLog
sealed trait ExportEventLog extends ImportExportEventLog

object ImportExportEventLog {

  def buildCommonExportDetails(tagName: String, gitArchiveId: GitArchiveId): Elem = {
    EventLog.withContent(
      new Elem(
        prefix = null,
        label = tagName,
        attributes1 = new UnprefixedAttribute("fileFormat", Seq(Text(Constants.XML_CURRENT_FILE_FORMAT.toString)), Null),
        scope = TopScope,
        minimizeEmpty = false,
        child = (
          <path>{gitArchiveId.path.value}</path>
          <commit>{gitArchiveId.commit.value}</commit>
          <commiterName>{gitArchiveId.commiter.getName}</commiterName>
          <commiterEmail>{gitArchiveId.commiter.getEmailAddress}</commiterEmail>
        )*
      )
    )
  }

  def buildCommonImportDetails(tagName: String, gitCommitId: GitCommitId): Elem = (
    EventLog.withContent(
      new Elem(
        prefix = null,
        label = tagName,
        attributes1 = new UnprefixedAttribute("fileFormat", Seq(Text(Constants.XML_CURRENT_FILE_FORMAT.toString)), Null),
        scope = TopScope,
        minimizeEmpty = false,
        child = (
          <commit>{gitCommitId.value}</commit>
        )*
      )
    )
  )

}

final case class ExportGroupsArchive(
    override val eventDetails: EventLogDetails
) extends ExportEventLog {
  override val eventType = ExportGroupsArchive.eventType

  def this(actor: EventActor, gitArchiveId: GitArchiveId, reason: Option[String]) = this(
    EventLogDetails(
      modificationId = None,
      principal = actor,
      reason = reason,
      details = ExportGroupsArchive.buildDetails(gitArchiveId)
    )
  )
}

object ExportGroupsArchive extends EventLogFilter {
  override val eventType: EventLogType = ExportGroupsEventType

  override def apply(x: (EventLogType, EventLogDetails)): ExportGroupsArchive = ExportGroupsArchive(x._2)

  def buildDetails(gitArchiveId: GitArchiveId): Elem =
    ImportExportEventLog.buildCommonExportDetails(tagName = tagName, gitArchiveId)

  val tagName = "newGroupsArchive"
}

final case class ImportGroupsArchive(
    override val eventDetails: EventLogDetails
) extends ImportEventLog {
  override val eventType = ImportGroupsArchive.eventType

  def this(actor: EventActor, gitCommitId: GitCommitId, reason: Option[String]) = this(
    EventLogDetails(
      modificationId = None,
      principal = actor,
      reason = reason,
      details = ImportGroupsArchive.buildDetails(gitCommitId)
    )
  )
}

object ImportGroupsArchive extends EventLogFilter {
  override val eventType: EventLogType = ImportGroupsEventType

  override def apply(x: (EventLogType, EventLogDetails)): ImportGroupsArchive = ImportGroupsArchive(x._2)

  def buildDetails(gitCommitId: GitCommitId): Elem =
    ImportExportEventLog.buildCommonImportDetails(tagName = tagName, gitCommitId)

  val tagName = "restoreGroupsArchive"
}

final case class ExportTechniqueLibraryArchive(
    override val eventDetails: EventLogDetails
) extends ExportEventLog {
  override val eventType = ExportTechniqueLibraryArchive.eventType

  def this(actor: EventActor, gitArchiveId: GitArchiveId, reason: Option[String]) = this(
    EventLogDetails(
      modificationId = None,
      principal = actor,
      reason = reason,
      details = ExportTechniqueLibraryArchive.buildDetails(gitArchiveId)
    )
  )
}

object ExportTechniqueLibraryArchive extends EventLogFilter {
  override val eventType: EventLogType = ExportTechniqueLibraryEventType

  override def apply(x: (EventLogType, EventLogDetails)): ExportTechniqueLibraryArchive = ExportTechniqueLibraryArchive(x._2)

  def buildDetails(gitArchiveId: GitArchiveId): Elem =
    ImportExportEventLog.buildCommonExportDetails(tagName = tagName, gitArchiveId)

  val tagName = "newDirectivesArchive"
}

final case class ImportTechniqueLibraryArchive(
    override val eventDetails: EventLogDetails
) extends ImportEventLog {
  override val eventType = ImportTechniqueLibraryArchive.eventType

  def this(actor: EventActor, gitCommitId: GitCommitId, reason: Option[String]) = this(
    EventLogDetails(
      modificationId = None,
      principal = actor,
      reason = reason,
      details = ImportTechniqueLibraryArchive.buildDetails(gitCommitId)
    )
  )
}

object ImportTechniqueLibraryArchive extends EventLogFilter {
  override val eventType: EventLogType = ImportTechniqueLibraryEventType

  override def apply(x: (EventLogType, EventLogDetails)): ImportTechniqueLibraryArchive = ImportTechniqueLibraryArchive(x._2)

  def buildDetails(gitCommitId: GitCommitId): Elem =
    ImportExportEventLog.buildCommonImportDetails(tagName = tagName, gitCommitId)

  val tagName = "restoreDirectivesArchive"
}

final case class ExportRulesArchive(
    override val eventDetails: EventLogDetails
) extends ExportEventLog {
  override val eventType = ExportRulesArchive.eventType

  def this(actor: EventActor, gitArchiveId: GitArchiveId, reason: Option[String]) = this(
    EventLogDetails(
      modificationId = None,
      principal = actor,
      reason = reason,
      details = ExportRulesArchive.buildDetails(gitArchiveId)
    )
  )
}

object ExportRulesArchive extends EventLogFilter {
  override val eventType: EventLogType = ExportRulesEventType

  override def apply(x: (EventLogType, EventLogDetails)): ExportRulesArchive = ExportRulesArchive(x._2)

  def buildDetails(gitArchiveId: GitArchiveId): Elem =
    ImportExportEventLog.buildCommonExportDetails(tagName = tagName, gitArchiveId)

  val tagName = "newRulesArchive"
}

final case class ImportRulesArchive(
    override val eventDetails: EventLogDetails
) extends ImportEventLog {
  override val eventType = ImportRulesArchive.eventType

  def this(actor: EventActor, gitCommitId: GitCommitId, reason: Option[String]) = this(
    EventLogDetails(
      modificationId = None,
      principal = actor,
      reason = reason,
      details = ImportRulesArchive.buildDetails(gitCommitId)
    )
  )
}

object ImportRulesArchive extends EventLogFilter {
  override val eventType: EventLogType = ImportRulesEventType

  override def apply(x: (EventLogType, EventLogDetails)): ImportRulesArchive = ImportRulesArchive(x._2)

  def buildDetails(gitCommitId: GitCommitId): Elem =
    ImportExportEventLog.buildCommonImportDetails(tagName = tagName, gitCommitId)

  val tagName = "restoreRulesArchive"
}

final case class ExportParametersArchive(
    override val eventDetails: EventLogDetails
) extends ExportEventLog {
  override val eventType = ExportParametersArchive.eventType

  def this(actor: EventActor, gitArchiveId: GitArchiveId, reason: Option[String]) = this(
    EventLogDetails(
      modificationId = None,
      principal = actor,
      reason = reason,
      details = ExportParametersArchive.buildDetails(gitArchiveId)
    )
  )
}

object ExportParametersArchive extends EventLogFilter {
  override val eventType: EventLogType = ExportParametersEventType

  override def apply(x: (EventLogType, EventLogDetails)): ExportParametersArchive = ExportParametersArchive(x._2)

  def buildDetails(gitArchiveId: GitArchiveId): Elem =
    ImportExportEventLog.buildCommonExportDetails(tagName = tagName, gitArchiveId)

  val tagName = "newParametersArchive"
}

final case class ImportParametersArchive(
    override val eventDetails: EventLogDetails
) extends ImportEventLog {
  override val eventType = ImportParametersArchive.eventType

  def this(actor: EventActor, gitCommitId: GitCommitId, reason: Option[String]) = this(
    EventLogDetails(
      modificationId = None,
      principal = actor,
      reason = reason,
      details = ImportParametersArchive.buildDetails(gitCommitId)
    )
  )
}

object ImportParametersArchive extends EventLogFilter {
  override val eventType: EventLogType = ImportParametersEventType

  override def apply(x: (EventLogType, EventLogDetails)): ImportParametersArchive = ImportParametersArchive(x._2)

  def buildDetails(gitCommitId: GitCommitId): Elem =
    ImportExportEventLog.buildCommonImportDetails(tagName = tagName, gitCommitId)

  val tagName = "restoreParametersArchive"
}

final case class ExportFullArchive(
    override val eventDetails: EventLogDetails
) extends ExportEventLog {
  override val eventType = ExportFullArchive.eventType

  def this(actor: EventActor, gitArchiveId: GitArchiveId, reason: Option[String]) = this(
    EventLogDetails(
      modificationId = None,
      principal = actor,
      reason = reason,
      details = ExportFullArchive.buildDetails(gitArchiveId)
    )
  )
}

object ExportFullArchive extends EventLogFilter {
  override val eventType: EventLogType = ExportFullArchiveEventType

  override def apply(x: (EventLogType, EventLogDetails)): ExportFullArchive = ExportFullArchive(x._2)

  def buildDetails(gitArchiveId: GitArchiveId): Elem =
    ImportExportEventLog.buildCommonExportDetails(tagName = tagName, gitArchiveId)

  val tagName = "newFullArchive"
}

final case class ImportFullArchive(
    override val eventDetails: EventLogDetails
) extends ImportEventLog {
  override val eventType = ImportFullArchive.eventType

  def this(actor: EventActor, gitCommitId: GitCommitId, reason: Option[String]) = this(
    EventLogDetails(
      modificationId = None,
      principal = actor,
      reason = reason,
      details = ImportFullArchive.buildDetails(gitCommitId)
    )
  )
}

object ImportFullArchive extends EventLogFilter {
  override val eventType: EventLogType = ImportFullArchiveEventType

  override def apply(x: (EventLogType, EventLogDetails)): ImportFullArchive = ImportFullArchive(x._2)

  def buildDetails(gitCommitId: GitCommitId): Elem =
    ImportExportEventLog.buildCommonImportDetails(tagName = tagName, gitCommitId)

  val tagName = "restoreFullArchive"
}

final case class Rollback(
    override val eventDetails: EventLogDetails
) extends ImportEventLog {
  override val eventType: EventLogType = RollbackEventType

  def this(
      actor:           EventActor,
      rollbackedEvent: Seq[EventLog],
      targetEvent:     EventLog,
      rollbackType:    String,
      reason:          Option[String]
  ) = this(
    EventLogDetails(
      modificationId = None,
      principal = actor,
      reason = reason,
      details = Rollback.buildDetails(rollbackedEvent, targetEvent, rollbackType)
    )
  )
}

object Rollback extends EventLogFilter {
  override val eventType: EventLogType = RollbackEventType

  override def apply(x: (EventLogType, EventLogDetails)): Rollback = Rollback(x._2)

  def buildDetails(rollbackedEvents: Seq[EventLog], targetEvent: EventLog, rollbackType: String): Elem = {
    EventLog.withContent(
      new Elem(
        prefix = null,
        label = "rollbackedEvents",
        attributes1 = new UnprefixedAttribute("fileFormat", Seq(Text(Constants.XML_CURRENT_FILE_FORMAT.toString)), Null),
        scope = TopScope,
        minimizeEmpty = false,
        child = {
          val events = {
            for {
              ev <- rollbackedEvents
            } yield {
              <rollbackedEvent>
                <id>{ev.id.get}</id>
                <type>{ev.eventType.serialize}</type>
                <author>{ev.principal.name}</author>
                <date>{DateFormaterService.serializeInstant(ev.creationDate)}</date>
              </rollbackedEvent>
            }
          }
          val main   = {
            <main>
                <rollbackType>{rollbackType}</rollbackType>
                <id>{targetEvent.id.get}</id>
                <type>{targetEvent.eventType.serialize}</type>
                <author>{targetEvent.principal.name}</author>
                <date>{DateFormaterService.serializeInstant(targetEvent.creationDate)}</date>
            </main>
          }
          events ++ main
        }*
      )
    )
  }

  val tagName = "rollback"

}

object ImportExportEventLogsFilter {
  final val eventList: List[EventLogFilter] = List(
    ExportGroupsArchive,
    ExportTechniqueLibraryArchive,
    ExportRulesArchive,
    ExportFullArchive,
    ExportParametersArchive,
    ImportGroupsArchive,
    ImportTechniqueLibraryArchive,
    ImportRulesArchive,
    ImportFullArchive,
    ImportParametersArchive,
    Rollback
  )
}
