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
import com.normation.rudder.domain.policies._
import org.joda.time.DateTime
import net.liftweb.common._
import com.normation.cfclerk.domain._
import com.normation.utils.HashcodeCaching
import com.normation.eventlog.EventLogDetails
import com.normation.rudder.domain.Constants

sealed trait TechniqueEventLog extends EventLog { override final val eventLogCategory = TechniqueLogCategory }

final case class ReloadTechniqueLibrary(
    override val eventDetails : EventLogDetails
) extends TechniqueEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = ReloadTechniqueLibrary.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))
}

object ReloadTechniqueLibrary extends EventLogFilter {
  override val eventType = ReloadTechniqueLibraryType
 
  override def apply(x : (EventLogType, EventLogDetails)) : ReloadTechniqueLibrary = ReloadTechniqueLibrary(x._2) 

  def buildDetails(TechniqueIds:Seq[TechniqueId]) : NodeSeq = EventLog.withContent { 
    <reloadTechniqueLibrary fileFormat={Constants.XML_CURRENT_FILE_FORMAT.toString}>{ TechniqueIds.map { case TechniqueId(name, version) =>
      <modifiedTechnique>
        <name>{name.value}</name>
        <version>{version.toString}</version>
      </modifiedTechnique>
    } }</reloadTechniqueLibrary>
  }

}

object TechniqueEventLogsFilter {
  final val eventList : List[EventLogFilter] = List(
      ReloadTechniqueLibrary, ModifyTechnique
    )
}

final case class ModifyTechnique(
    override val eventDetails : EventLogDetails
) extends TechniqueEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = ModifyTechnique.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))
}

object ModifyTechnique extends EventLogFilter {
  override val eventType = ModifyTechniqueEventType
  override def apply(x : (EventLogType, EventLogDetails)) : ModifyTechnique = ModifyTechnique(x._2) 
}
