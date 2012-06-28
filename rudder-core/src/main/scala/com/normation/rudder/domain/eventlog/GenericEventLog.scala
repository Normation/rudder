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

import org.joda.time.DateTime
import com.normation.eventlog.EventActor
import com.normation.eventlog.EventLog
import com.normation.utils.HashcodeCaching
import com.normation.eventlog.EventLogDetails
import com.normation.eventlog.EventLogFilter
import com.normation.eventlog.EventLogType


final case class ApplicationStarted(
    override val eventDetails : EventLogDetails
) extends EventLog with HashcodeCaching {
  override val cause = None
  override val principal = RudderEventActor
  override val eventType = ApplicationStarted.eventType
  override def details = EventLog.emptyDetails
  override val eventLogCategory = RudderApplicationLogCategory
  override def copySetCause(causeId:Int) = this
}

object ApplicationStarted extends EventLogFilter {
  override val eventType = ApplicationStartedEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : ApplicationStarted = ApplicationStarted(x._2) 
}
/**
 * Red buton part
 */
final case class ActivateRedButton(
    override val eventDetails : EventLogDetails
) extends EventLog with HashcodeCaching {
  
  override val eventType = ActivateRedButton.eventType
  override def details = EventLog.emptyDetails
  override val eventLogCategory = RedButtonLogCategory
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))

}

object ActivateRedButton extends EventLogFilter {
  override val eventType = ActivateRedButtonEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : ActivateRedButton = ActivateRedButton(x._2) 
}

final case class ReleaseRedButton(
    override val eventDetails : EventLogDetails
) extends EventLog with HashcodeCaching {
  
  override val eventType = ReleaseRedButton.eventType
  override def details= EventLog.emptyDetails
  override val eventLogCategory = RedButtonLogCategory
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))

}

object ReleaseRedButton extends EventLogFilter {
  override val eventType = ReleaseRedButtonEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : ReleaseRedButton = ReleaseRedButton(x._2) 
}

/**
 * Clear cache
 */
final case class ClearCacheEventLog(
   override val eventDetails : EventLogDetails
) extends EventLog with HashcodeCaching {
  
  override val eventType = ClearCacheEventLog.eventType
  override def details = EventLog.emptyDetails
  override val eventLogCategory = RudderApplicationLogCategory
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))

}

object ClearCacheEventLog extends EventLogFilter {
  override val eventType = ClearCacheEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : ClearCacheEventLog = ClearCacheEventLog(x._2) 
}


object GenericEventLogsFilter {
  final val eventList : List[EventLogFilter] = List(
      ApplicationStarted 
    , ActivateRedButton 
    , ReleaseRedButton
    , ClearCacheEventLog
    )
}
