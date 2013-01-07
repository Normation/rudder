/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.domain.eventlog

import com.normation.eventlog._
import scala.xml._
import org.joda.time.DateTime
import net.liftweb.common._
import com.normation.utils.HashcodeCaching
import com.normation.rudder.repository.NodeGroupCategoryContent
import com.normation.rudder.repository.ActiveTechniqueCategoryContent
import com.normation.rudder.repository.jdbc.Rules
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.repository.GitPath
import com.normation.rudder.repository.GitArchiveId
import com.normation.rudder.repository.GitCommitId
import com.normation.rudder.domain.Constants

sealed trait ImportExportEventLog  extends EventLog { override final val eventLogCategory = ImportExportItemsLogCategory }

sealed trait ImportEventLog  extends ImportExportEventLog
sealed trait ExportEventLog  extends ImportExportEventLog

object ImportExportEventLog {
  
  def buildCommonExportDetails(tagName:String, gitArchiveId: GitArchiveId) : Elem = 
    EventLog.withContent(new Elem(
        prefix = null
      , label = tagName
      , attributes = new UnprefixedAttribute("fileFormat", Seq(Text(Constants.XML_CURRENT_FILE_FORMAT.toString)), Null)
      , scope = TopScope
      , child = (  
          <path>{gitArchiveId.path.value}</path>
          <commit>{gitArchiveId.commit.value}</commit>
          <commiterName>{gitArchiveId.commiter.getName}</commiterName>
          <commiterEmail>{gitArchiveId.commiter.getEmailAddress}</commiterEmail>
        ):_*
    ) )

  def buildCommonImportDetails(tagName:String, gitCommitId: GitCommitId) : NodeSeq = (
    EventLog.withContent(new Elem(
        prefix = null
      , label = tagName
      , attributes = new UnprefixedAttribute("fileFormat", Seq(Text(Constants.XML_CURRENT_FILE_FORMAT.toString)), Null)
      , scope = TopScope
      , child = (  
          <commit>{gitCommitId.value}</commit>
        ):_*
    ) )
  )
  
}

final case class ExportGroupsArchive(
    override val eventDetails : EventLogDetails
) extends ExportEventLog with HashcodeCaching {
  override val eventType = ExportGroupsArchive.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))

  def this(actor:EventActor, gitArchiveId:GitArchiveId, reason: Option[String]) = this(EventLogDetails(
      modificationId = None
    , principal = actor
    , reason = reason
    , details = ExportGroupsArchive.buildDetails(gitArchiveId)
  ))
}

object ExportGroupsArchive extends EventLogFilter {
  override val eventType = ExportGroupsEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : ExportGroupsArchive = ExportGroupsArchive(x._2) 
  
  def buildDetails(gitArchiveId:GitArchiveId) = 
    ImportExportEventLog.buildCommonExportDetails(tagName = tagName, gitArchiveId)
    
  val tagName = "newGroupsArchive"
}

final case class ImportGroupsArchive(
    override val eventDetails : EventLogDetails
) extends ImportEventLog with HashcodeCaching {
  override val eventType = ImportGroupsArchive.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))

  def this(actor:EventActor, gitCommitId:GitCommitId, reason: Option[String]) = this(EventLogDetails(
      modificationId = None
    , principal = actor
    , reason = reason
    , details = ImportGroupsArchive.buildDetails(gitCommitId)
  ))
}

object ImportGroupsArchive extends EventLogFilter {
  override val eventType = ImportGroupsEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : ImportGroupsArchive = ImportGroupsArchive(x._2) 
  
  def buildDetails(gitCommitId:GitCommitId) =
    ImportExportEventLog.buildCommonImportDetails(tagName = tagName, gitCommitId)

  val tagName = "restoreGroupsArchive"
}

final case class ExportTechniqueLibraryArchive(
    override val eventDetails : EventLogDetails
) extends ExportEventLog with HashcodeCaching {
  override val eventType = ExportTechniqueLibraryArchive.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))

  def this(actor:EventActor, gitArchiveId:GitArchiveId, reason: Option[String]) = this(EventLogDetails(
      modificationId = None
    , principal = actor
    , reason = reason
    , details = ExportTechniqueLibraryArchive.buildDetails(gitArchiveId)
  ))
}

object ExportTechniqueLibraryArchive extends EventLogFilter {
  override val eventType = ExportTechniqueLibraryEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : ExportTechniqueLibraryArchive = ExportTechniqueLibraryArchive(x._2) 
  
  def buildDetails(gitArchiveId:GitArchiveId) = 
    ImportExportEventLog.buildCommonExportDetails(tagName = tagName, gitArchiveId)
    
  val tagName = "newDirectivesArchive"
}

final case class ImportTechniqueLibraryArchive(
    override val eventDetails : EventLogDetails
) extends ImportEventLog with HashcodeCaching {
  override val eventType = ImportTechniqueLibraryArchive.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))

  def this(actor:EventActor, gitCommitId:GitCommitId, reason: Option[String]) = this(EventLogDetails(
      modificationId = None
    , principal = actor
    , reason = reason
    , details = ImportTechniqueLibraryArchive.buildDetails(gitCommitId)
  ))
}

object ImportTechniqueLibraryArchive extends EventLogFilter {
  override val eventType = ImportTechniqueLibraryEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : ImportTechniqueLibraryArchive = ImportTechniqueLibraryArchive(x._2) 

  def buildDetails(gitCommitId:GitCommitId) =
    ImportExportEventLog.buildCommonImportDetails(tagName = tagName, gitCommitId)
  
  val tagName = "restoreDirectivesArchive"
}


final case class ExportRulesArchive(
    override val eventDetails : EventLogDetails
) extends ExportEventLog with HashcodeCaching {
  override val eventType = ExportRulesArchive.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))

  def this(actor:EventActor, gitArchiveId:GitArchiveId, reason: Option[String]) = this(EventLogDetails(
      modificationId = None
    , principal = actor
    , reason = reason
    , details = ExportRulesArchive.buildDetails(gitArchiveId)
  ))
}

object ExportRulesArchive extends EventLogFilter {
  override val eventType = ExportRulesEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : ExportRulesArchive = ExportRulesArchive(x._2) 

  def buildDetails(gitArchiveId:GitArchiveId) = 
    ImportExportEventLog.buildCommonExportDetails(tagName = tagName, gitArchiveId)
    
  val tagName = "newRulesArchive"
}

final case class ImportRulesArchive(
    override val eventDetails : EventLogDetails
) extends ImportEventLog with HashcodeCaching {
  override val eventType = ImportRulesArchive.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))

  def this(actor:EventActor, gitCommitId:GitCommitId, reason: Option[String]) = this(EventLogDetails(
      modificationId = None
    , principal = actor
    , reason = reason
    , details = ImportRulesArchive.buildDetails(gitCommitId)
  ))
}

object ImportRulesArchive extends EventLogFilter {
  override val eventType = ImportRulesEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : ImportRulesArchive = ImportRulesArchive(x._2) 

  def buildDetails(gitCommitId:GitCommitId) =
    ImportExportEventLog.buildCommonImportDetails(tagName = tagName, gitCommitId)

  val tagName = "restoreRulesArchive"
}

final case class ExportFullArchive(
    override val eventDetails : EventLogDetails
) extends ExportEventLog with HashcodeCaching {
  override val eventType = ExportFullArchive.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))

  def this(actor:EventActor, gitArchiveId:GitArchiveId, reason: Option[String]) = this(EventLogDetails(
      modificationId = None
    , principal = actor
    , reason = reason
    , details = ExportFullArchive.buildDetails(gitArchiveId)
  ))
}

object ExportFullArchive extends EventLogFilter {
  override val eventType = ExportFullArchiveEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : ExportFullArchive = ExportFullArchive(x._2) 

  def buildDetails(gitArchiveId:GitArchiveId) = 
    ImportExportEventLog.buildCommonExportDetails(tagName = tagName, gitArchiveId)
    
  val tagName = "newFullArchive"
}

final case class ImportFullArchive(
    override val eventDetails : EventLogDetails
) extends ImportEventLog with HashcodeCaching {
  override val eventType = ImportFullArchive.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))

  def this(actor:EventActor, gitCommitId:GitCommitId, reason: Option[String]) = this(EventLogDetails(
      modificationId = None
    , principal = actor
    , reason = reason
    , details = ImportFullArchive.buildDetails(gitCommitId)
  ))
}

object ImportFullArchive extends EventLogFilter {
  override val eventType = ImportFullArchiveEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : ImportFullArchive = ImportFullArchive(x._2) 

  def buildDetails(gitCommitId:GitCommitId) =
    ImportExportEventLog.buildCommonImportDetails(tagName = tagName, gitCommitId)

  val tagName = "restoreFullArchive"
}

object ImportExportEventLogsFilter {
  final val eventList : List[EventLogFilter] = List(
      ExportGroupsArchive 
    , ExportTechniqueLibraryArchive
    , ExportRulesArchive
    , ExportFullArchive
    , ImportGroupsArchive 
    , ImportTechniqueLibraryArchive
    , ImportRulesArchive
    , ImportFullArchive
    )
}