package com.normation.rudder.domain.log

import com.normation.eventlog._
import scala.xml._
import org.joda.time.DateTime
import net.liftweb.common._
import com.normation.utils.HashcodeCaching

sealed trait ImportExportEventLog  extends EventLog



final case class ExportGroups(
    override val id : Option[Int] = None
  , override val principal : EventActor
  , override val details : NodeSeq
  , override val creationDate : DateTime = DateTime.now()
  , override val severity : Int = 100
) extends ImportExportEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = ExportGroupsEventType
  override val eventLogCategory = ImportExportItemsLogCategory
  override def copySetCause(causeId:Int) = this
}

final case class ImportGroups(
    override val id : Option[Int] = None
  , override val principal : EventActor
  , override val details : NodeSeq
  , override val creationDate : DateTime = DateTime.now()
  , override val severity : Int = 100
) extends ImportExportEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = ImportGroupsEventType
  override val eventLogCategory = ImportExportItemsLogCategory
  override def copySetCause(causeId:Int) = this
}


final case class ExportPolicyLibrary(
    override val id : Option[Int] = None
  , override val principal : EventActor
  , override val details : NodeSeq
  , override val creationDate : DateTime = DateTime.now()
  , override val severity : Int = 100
) extends ImportExportEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = ExportPolicyLibraryEventType
  override val eventLogCategory = ImportExportItemsLogCategory
  override def copySetCause(causeId:Int) = this
}

final case class ImportPolicyLibrary(
    override val id : Option[Int] = None
  , override val principal : EventActor
  , override val details : NodeSeq
  , override val creationDate : DateTime = DateTime.now()
  , override val severity : Int = 100
) extends ImportExportEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = ImportPolicyLibraryEventType
  override val eventLogCategory = ImportExportItemsLogCategory
  override def copySetCause(causeId:Int) = this
}


final case class ExportCRs(
    override val id : Option[Int] = None
  , override val principal : EventActor
  , override val details : NodeSeq
  , override val creationDate : DateTime = DateTime.now()
  , override val severity : Int = 100
) extends ImportExportEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = ExportCrsEventType
  override val eventLogCategory = ImportExportItemsLogCategory
  override def copySetCause(causeId:Int) = this
}

final case class ImportCRs(
    override val id : Option[Int] = None
  , override val principal : EventActor
  , override val details : NodeSeq
  , override val creationDate : DateTime = DateTime.now()
  , override val severity : Int = 100
) extends ImportExportEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = ImportCrsEventType
  override val eventLogCategory = ImportExportItemsLogCategory
  override def copySetCause(causeId:Int) = this
}

final case class ExportAllLibraries(
    override val id : Option[Int] = None
  , override val principal : EventActor
  , override val details : NodeSeq
  , override val creationDate : DateTime = DateTime.now()
  , override val severity : Int = 100
) extends ImportExportEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = ExportAllLibrariesEventType
  override val eventLogCategory = ImportExportItemsLogCategory
  override def copySetCause(causeId:Int) = this
}

final case class ImportAllLibraries(
    override val id : Option[Int] = None
  , override val principal : EventActor
  , override val details : NodeSeq
  , override val creationDate : DateTime = DateTime.now()
  , override val severity : Int = 100
) extends ImportExportEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = ImportAllLibrariesEventType
  override val eventLogCategory = ImportExportItemsLogCategory
  override def copySetCause(causeId:Int) = this
}