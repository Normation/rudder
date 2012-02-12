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

sealed trait ImportExportEventLog  extends EventLog { override final val eventLogCategory = ImportExportItemsLogCategory }



final case class ExportGroups(
    override val eventDetails : EventLogDetails
) extends ImportExportEventLog with HashcodeCaching {
  override val eventType = ExportGroups.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))
}

object ExportGroups extends EventLogFilter {
  override val eventType = ExportGroupsEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : ExportGroups = ExportGroups(x._2) 
  
  def buildDetails(gitPath:GitPath, groups:NodeGroupCategoryContent) = EventLog.withContent {
    
    error("TODO")
  }
}

final case class ImportGroups(
    override val eventDetails : EventLogDetails
) extends ImportExportEventLog with HashcodeCaching {
  override val eventType = ImportGroups.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))
}

object ImportGroups extends EventLogFilter {
  override val eventType = ImportGroupsEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : ImportGroups = ImportGroups(x._2) 
  
  def buildDetails(gitPath:GitPath, groups:NodeGroupCategoryContent) = EventLog.withContent {
    
    error("TODO")
  }
}

final case class ExportPolicyLibrary(
    override val eventDetails : EventLogDetails
) extends ImportExportEventLog with HashcodeCaching {
  override val eventType = ExportPolicyLibrary.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))
}

object ExportPolicyLibrary extends EventLogFilter {
  override val eventType = ExportPolicyLibraryEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : ExportPolicyLibrary = ExportPolicyLibrary(x._2) 
  
  def buildDetails(gitPath:GitPath, policyLibrary:UptCategoryContent) = EventLog.withContent {
    
    error("TODO")
  }
}

final case class ImportPolicyLibrary(
    override val eventDetails : EventLogDetails
) extends ImportExportEventLog with HashcodeCaching {
  override val eventType = ImportPolicyLibrary.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))
}

object ImportPolicyLibrary extends EventLogFilter {
  override val eventType = ImportPolicyLibraryEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : ImportPolicyLibrary = ImportPolicyLibrary(x._2) 
  def buildDetails(gitPath:GitPath, policyLibrary:UptCategoryContent) = EventLog.withContent {
    
    error("TODO")
  }
}


final case class ExportCRs(
    override val eventDetails : EventLogDetails
) extends ImportExportEventLog with HashcodeCaching {
  override val eventType = ExportCRs.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))
}

object ExportCRs extends EventLogFilter {
  override val eventType = ExportCrsEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : ExportCRs = ExportCRs(x._2) 

  def buildDetails(gitPath:GitPath, configurationRules:Seq[ConfigurationRule]) = EventLog.withContent {
    
    error("TODO")
  }
}

final case class ImportCRs(
    override val eventDetails : EventLogDetails
) extends ImportExportEventLog with HashcodeCaching {
  override val eventType = ImportCRs.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))
}

object ImportCRs extends EventLogFilter {
  override val eventType = ImportCrsEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : ImportCRs = ImportCRs(x._2) 

  def buildDetails(gitPath:GitPath, configurationRules:Seq[ConfigurationRule]) = EventLog.withContent {
    
    error("TODO")
  }
}

final case class ExportAllLibraries(
    override val eventDetails : EventLogDetails
) extends ImportExportEventLog with HashcodeCaching {
  override val eventType = ExportAllLibraries.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))
}

object ExportAllLibraries extends EventLogFilter {
  override val eventType = ExportAllLibrariesEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : ExportAllLibraries = ExportAllLibraries(x._2) 

  def buildDetails(gitPath:GitPath) = EventLog.withContent {
    
    error("TODO")
  }
}

final case class ImportAllLibraries(
    override val eventDetails : EventLogDetails
) extends ImportExportEventLog with HashcodeCaching {
  override val eventType = ImportAllLibraries.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))
}

object ImportAllLibraries extends EventLogFilter {
  override val eventType = ImportAllLibrariesEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : ImportAllLibraries = ImportAllLibraries(x._2) 

  def buildDetails(gitPath:GitPath) = EventLog.withContent {
    
    error("TODO")
  }
}

object ImportExportEventLogsFilter {
  final val eventList : List[EventLogFilter] = List(
      ExportGroups 
    , ImportGroups 
    , ExportPolicyLibrary
    , ImportPolicyLibrary
    , ExportCRs
    , ImportCRs
    , ExportAllLibraries
    , ImportAllLibraries
    )
}