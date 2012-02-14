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

package com.normation.rudder.domain.log

import com.normation.eventlog.EventLogCategory
import com.normation.eventlog.EventLogType
import com.normation.eventlog.UnknownEventLogType

///// Define intersting categories /////
final case object UserLogCategory extends EventLogCategory
final case object RudderApplicationLogCategory extends EventLogCategory
final case object ConfigurationRuleLogCategory extends EventLogCategory
final case object PolicyInstanceLogCategory extends EventLogCategory
final case object PolicyTemplateLogCategory extends EventLogCategory
final case object DeploymentLogCategory extends EventLogCategory
final case object NodeGroupLogCategory extends EventLogCategory
final case object AssetLogCategory extends EventLogCategory
final case object RedButtonLogCategory extends EventLogCategory

final case object PolicyServerLogCategory extends EventLogCategory
final case object ImportExportItemsLogCategory extends EventLogCategory





// the promises related event type
final case object AutomaticStartDeployementEventType extends EventLogType {
  def serialize = "AutomaticStartDeployement"
}
final case object ManualStartDeployementEventType extends EventLogType {
  def serialize = "ManualStartDeployement"
}
final case object SuccessfulDeploymentEventType extends EventLogType {
  def serialize = "SuccessfulDeployment"
}
final case object FailedDeploymentEventType extends EventLogType {
  def serialize = "FailedDeployment"
}
// the login related event type
final case object LoginEventType extends EventLogType {
  def serialize = "UserLogin"
}
final case object BadCredentialsEventType extends EventLogType {
  def serialize = "BadCredentials"
}
final case object LogoutEventType extends EventLogType {
  def serialize = "UserLogout"
}
// the node related event type
final case object AddNodeGroupEventType extends EventLogType {
  def serialize = "NodeGroupAdded"
}
final case object DeleteNodeGroupEventType extends EventLogType {
  def serialize = "NodeGroupDeleted"
}
final case object ModifyNodeGroupEventType extends EventLogType {
  def serialize = "NodeGroupModified"
}
// policy instance related
final case object AddPolicyInstanceEventType extends EventLogType {
  def serialize = "PolicyInstanceAdded"
}
final case object DeletePolicyInstanceEventType extends EventLogType {
  def serialize = "PolicyInstanceDeleted"
}
final case object ModifyPolicyInstanceEventType extends EventLogType {
  def serialize = "PolicyInstanceModified"
}

// the generic event related event type
final case object ApplicationStartedEventType extends EventLogType {
  def serialize = "ApplicationStarted"
}
final case object ActivateRedButtonEventType extends EventLogType {
  def serialize = "ActivateRedButton"
}
final case object ReleaseRedButtonEventType extends EventLogType {
  def serialize = "ReleaseRedButton"
}

final case object ReloadPolicyTemplateLibraryType extends EventLogType {
  def serialize = "ReloadPolicyTemplateLibrary"
}

// configuration rule related event type
final case object AddConfigurationRuleEventType extends EventLogType {
  def serialize = "ConfigurationRuleAdded"
}
final case object DeleteConfigurationRuleEventType extends EventLogType {
  def serialize = "ConfigurationRuleDeleted"
}
final case object ModifyConfigurationRuleEventType extends EventLogType {
  def serialize = "ConfigurationRuleModified"
}
// asset related event type
final case object AcceptNodeEventType extends EventLogType {
  def serialize = "AcceptNode"
}
final case object RefuseNodeEventType extends EventLogType {
  def serialize = "RefuseNode"
}
final case object DeleteNodeEventType extends EventLogType {
  def serialize = "DeleteNode"
}

// the system event type
final case object ClearCacheEventType extends EventLogType {
  def serialize = "ClearCache"
}
final case object UpdatePolicyServerEventType extends EventLogType {
  def serialize = "UpdatePolicyServer"
}

// Import/export
final case object ExportGroupsEventType extends EventLogType {
  def serialize = "ExportGroups"
}
final case object ImportGroupsEventType extends EventLogType {
  def serialize = "ImportGroups"
}
final case object ExportPolicyLibraryEventType extends EventLogType {
  def serialize = "ExportPolicyLibrary"
}
final case object ImportPolicyLibraryEventType extends EventLogType {
  def serialize = "ImportPolicyLibrary"
}
final case object ExportConfigurationRulesEventType extends EventLogType {
  def serialize = "ExportConfigurationRules"
}
final case object ImportConfigurationRulesEventType extends EventLogType {
  def serialize = "ImportConfigurationRules"
}
final case object ExportFullArchiveEventType extends EventLogType {
  def serialize = "ExportFullArchive"
}
final case object ImportFullArchiveEventType extends EventLogType {
  def serialize = "ImportFullArchive"
}


/**
 * List of event generating a modification of promises
 */
object ModificationWatchList {
  val events = Seq[EventLogType](
      AcceptNodeEventType
    , DeleteNodeEventType
    , AddConfigurationRuleEventType
    , DeleteConfigurationRuleEventType
    , ModifyConfigurationRuleEventType
    , AddPolicyInstanceEventType
    , DeletePolicyInstanceEventType
    , ModifyPolicyInstanceEventType
    , AddNodeGroupEventType
    , DeleteNodeGroupEventType
    , ModifyNodeGroupEventType
    , ClearCacheEventType
    , UpdatePolicyServerEventType
    , ReloadPolicyTemplateLibraryType
    , ImportGroupsEventType
    , ImportPolicyLibraryEventType
    , ImportConfigurationRulesEventType
    , ImportFullArchiveEventType
  )
  
}


object EventTypeFactory {
  val eventTypes = List[EventLogType](
      AutomaticStartDeployementEventType
    , ManualStartDeployementEventType
    , SuccessfulDeploymentEventType
    , FailedDeploymentEventType
    
    , LoginEventType
    , BadCredentialsEventType
    , LogoutEventType
    
    , AddNodeGroupEventType
    , DeleteNodeGroupEventType
    , ModifyNodeGroupEventType
    
    , AddPolicyInstanceEventType
    , DeletePolicyInstanceEventType
    , ModifyPolicyInstanceEventType
    
    , ApplicationStartedEventType
    , ActivateRedButtonEventType
    , ReleaseRedButtonEventType
    , ReloadPolicyTemplateLibraryType 
      
    , AddConfigurationRuleEventType
    , DeleteConfigurationRuleEventType
    , ModifyConfigurationRuleEventType
      
    , AcceptNodeEventType
    , RefuseNodeEventType
    , DeleteNodeEventType
      
    , ClearCacheEventType
    , UpdatePolicyServerEventType
      
    , ExportGroupsEventType
    , ImportGroupsEventType
    , ExportPolicyLibraryEventType
    , ImportPolicyLibraryEventType
    , ExportConfigurationRulesEventType
    , ImportConfigurationRulesEventType
    , ExportFullArchiveEventType
    , ImportFullArchiveEventType
  )
      
  def apply(s:String) : EventLogType = {
    eventTypes.find {
      pf => pf.isDefinedAt(s)
    }.getOrElse(UnknownEventLogType)
    
  }
}