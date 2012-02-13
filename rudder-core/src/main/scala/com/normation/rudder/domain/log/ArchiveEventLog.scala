package com.normation.rudder.domain.log

import com.normation.eventlog._
import scala.xml._
import org.joda.time.DateTime
import net.liftweb.common._
import com.normation.utils.HashcodeCaching
import com.normation.rudder.repository.NodeGroupCategoryContent
import com.normation.rudder.repository.UptCategoryContent
import com.normation.rudder.repository.jdbc.ConfigurationRules
import com.normation.rudder.domain.policies.ConfigurationRule
import com.normation.rudder.repository.GitPath
import com.normation.rudder.repository.GitArchiveId
import com.normation.rudder.repository.GitCommitId

sealed trait ImportExportEventLog  extends EventLog { override final val eventLogCategory = ImportExportItemsLogCategory }

sealed trait ImportEventLog  extends ImportExportEventLog
sealed trait ExportEventLog  extends ImportExportEventLog

object ImportExportEventLog {
  
  def buildCommonExportDetails(tagName:String, gitArchiveId: GitArchiveId) : Elem = 
    EventLog.withContent(new Elem(null, tagName, Null, TopScope, child = (  
        <path>{gitArchiveId.path.value}</path>
        <commit>{gitArchiveId.commit.value}</commit>
        <commiterName>{gitArchiveId.commiter.getName}</commiterName>
        <commiterEmail>{gitArchiveId.commiter.getEmailAddress}</commiterEmail>
      ):_*
    ) )

  def buildCommonImportDetails(tagName:String, gitCommitId: GitCommitId) : NodeSeq = (
    EventLog.withContent(new Elem(null, tagName, Null, TopScope, child = (  
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
    
  val tagName = "NewGroupsArchive"
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

  val tagName = "RestoreGroupsArchive"
}

final case class ExportPolicyLibraryArchive(
    override val eventDetails : EventLogDetails
) extends ExportEventLog with HashcodeCaching {
  override val eventType = ExportPolicyLibraryArchive.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))

  def this(actor:EventActor, gitArchiveId:GitArchiveId) = this(EventLogDetails(
      principal = actor
    , details = ExportPolicyLibraryArchive.buildDetails(gitArchiveId)
  ))
}

object ExportPolicyLibraryArchive extends EventLogFilter {
  override val eventType = ExportPolicyLibraryEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : ExportPolicyLibraryArchive = ExportPolicyLibraryArchive(x._2) 
  
  def buildDetails(gitArchiveId:GitArchiveId) = 
    ImportExportEventLog.buildCommonExportDetails(tagName = tagName, gitArchiveId)
    
  val tagName = "NewPolicyLibraryArchive"
}

final case class ImportPolicyLibraryArchive(
    override val eventDetails : EventLogDetails
) extends ImportEventLog with HashcodeCaching {
  override val eventType = ImportPolicyLibraryArchive.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))

  def this(actor:EventActor, gitCommitId:GitCommitId) = this(EventLogDetails(
      principal = actor
    , details = ImportPolicyLibraryArchive.buildDetails(gitCommitId)
  ))
}

object ImportPolicyLibraryArchive extends EventLogFilter {
  override val eventType = ImportPolicyLibraryEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : ImportPolicyLibraryArchive = ImportPolicyLibraryArchive(x._2) 

  def buildDetails(gitCommitId:GitCommitId) =
    ImportExportEventLog.buildCommonImportDetails(tagName = tagName, gitCommitId)
  
  val tagName = "RestorePolicyLibraryArchive"
}


final case class ExportConfigurationRulesArchive(
    override val eventDetails : EventLogDetails
) extends ExportEventLog with HashcodeCaching {
  override val eventType = ExportConfigurationRulesArchive.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))

  def this(actor:EventActor, gitArchiveId:GitArchiveId) = this(EventLogDetails(
      principal = actor
    , details = ExportConfigurationRulesArchive.buildDetails(gitArchiveId)
  ))
}

object ExportConfigurationRulesArchive extends EventLogFilter {
  override val eventType = ExportConfigurationRulesEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : ExportConfigurationRulesArchive = ExportConfigurationRulesArchive(x._2) 

  def buildDetails(gitArchiveId:GitArchiveId) = 
    ImportExportEventLog.buildCommonExportDetails(tagName = tagName, gitArchiveId)
    
  val tagName = "NewGroupsArchive"
}

final case class ImportConfigurationRulesArchive(
    override val eventDetails : EventLogDetails
) extends ImportEventLog with HashcodeCaching {
  override val eventType = ImportConfigurationRulesArchive.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))

  def this(actor:EventActor, gitCommitId:GitCommitId) = this(EventLogDetails(
      principal = actor
    , details = ImportConfigurationRulesArchive.buildDetails(gitCommitId)
  ))
}

object ImportConfigurationRulesArchive extends EventLogFilter {
  override val eventType = ImportConfigurationRulesEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : ImportConfigurationRulesArchive = ImportConfigurationRulesArchive(x._2) 

  def buildDetails(gitCommitId:GitCommitId) =
    ImportExportEventLog.buildCommonImportDetails(tagName = tagName, gitCommitId)

  val tagName = "RestoreConfigurationRulesArchive"
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
    
  val tagName = "NewFullArchive"
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

  val tagName = "RestoreFullArchive"
}

object ImportExportEventLogsFilter {
  final val eventList : List[EventLogFilter] = List(
      ExportGroupsArchive 
    , ExportPolicyLibraryArchive
    , ExportConfigurationRulesArchive
    , ExportFullArchive
    , ImportGroupsArchive 
    , ImportPolicyLibraryArchive
    , ImportConfigurationRulesArchive
    , ImportFullArchive
    )
}