package com.normation.rudder.domain.log

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
      , attributes = new UnprefixedAttribute("fileFormat", Seq(Text(Constants.XML_FILE_FORMAT_2_0)), Null)
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
      , attributes = new UnprefixedAttribute("fileFormat", Seq(Text(Constants.XML_FILE_FORMAT_2_0)), Null)
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

  def this(actor:EventActor, gitArchiveId:GitArchiveId) = this(EventLogDetails(
      principal = actor
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

  def this(actor:EventActor, gitCommitId:GitCommitId) = this(EventLogDetails(
      principal = actor
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

  def this(actor:EventActor, gitArchiveId:GitArchiveId) = this(EventLogDetails(
      principal = actor
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

  def this(actor:EventActor, gitCommitId:GitCommitId) = this(EventLogDetails(
      principal = actor
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

  def this(actor:EventActor, gitArchiveId:GitArchiveId) = this(EventLogDetails(
      principal = actor
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

  def this(actor:EventActor, gitCommitId:GitCommitId) = this(EventLogDetails(
      principal = actor
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

  def this(actor:EventActor, gitArchiveId:GitArchiveId) = this(EventLogDetails(
      principal = actor
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

  def this(actor:EventActor, gitCommitId:GitCommitId) = this(EventLogDetails(
      principal = actor
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