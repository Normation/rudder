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


import com.normation.eventlog._
import scala.xml._
import com.normation.rudder.domain.policies._
import org.joda.time.DateTime
import net.liftweb.common._
import com.normation.utils.HashcodeCaching


sealed trait ConfigurationRuleEventLog extends EventLog

object ConfigurationRuleEventLog {
  
  val xmlVersion = "1.0"
  
  /**
   * Print to XML a configuration rule, used
   * for "add" and "delete" actions. 
   */
  def toXml(cr:ConfigurationRule,action:String) =
    scala.xml.Utility.trim(<configurationRule changeType={action} fileFormat={xmlVersion}>
        <id>{cr.id.value}</id>
        <displayName>{cr.name}</displayName>
        <serial>{cr.serial}</serial>
        <target>{ cr.target.map( _.target).getOrElse("") }</target>
        <policyInstanceIds>{
          cr.policyInstanceIds.map { id => <id>{id.value}</id> } 
        }</policyInstanceIds>
        <shortDescription>{cr.shortDescription}</shortDescription>
        <longDescription>{cr.longDescription}</longDescription>
        <isActivated>{cr.isActivatedStatus}</isActivated>
        <isSystem>{cr.isSystem}</isSystem>
      </configurationRule>
    )
    
  
}

final case class AddConfigurationRule(
    override val id : Option[Int] = None
  , override val principal : EventActor
  , override val details : NodeSeq
  , override val creationDate : DateTime = new DateTime()
  , override val severity : Int = 100
) extends ConfigurationRuleEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = "ConfigurationRuleAdded"
  override val eventLogCategory = ConfigurationRuleLogCategory
  override def copySetCause(causeId:Int) = this
}

object AddConfigurationRule {
  def fromDiff(
      id : Option[Int] = None
    , principal : EventActor
    , addDiff:AddConfigurationRuleDiff
    , creationDate : DateTime = new DateTime()
    , severity : Int = 100
  ) : AddConfigurationRule = {
    val details = EventLog.withContent(ConfigurationRuleEventLog.toXml(addDiff.cr, "add"))
    
    AddConfigurationRule(id, principal, details, creationDate, severity)
  }
}

final case class DeleteConfigurationRule(
    override val id : Option[Int] = None
  , override val principal : EventActor
  , override val details : NodeSeq
  , override val creationDate : DateTime = new DateTime()
  , override val severity : Int = 100
) extends ConfigurationRuleEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = "ConfigurationRuleDeleted"
  override val eventLogCategory = ConfigurationRuleLogCategory
  override def copySetCause(causeId:Int) = this
}

object DeleteConfigurationRule {
  def fromDiff(    
      id : Option[Int] = None
    , principal : EventActor
    , deleteDiff:DeleteConfigurationRuleDiff
    , creationDate : DateTime = new DateTime()
    , severity : Int = 100
  ) : DeleteConfigurationRule = {
    val details = EventLog.withContent(ConfigurationRuleEventLog.toXml(deleteDiff.cr, "delete"))

    DeleteConfigurationRule(id, principal, details, creationDate, severity)
  }
}

final case class ModifyConfigurationRule(
    override val id : Option[Int] = None
  , override val principal : EventActor
  , override val details : NodeSeq
  , override val creationDate : DateTime = new DateTime()
  , override val severity : Int = 100
) extends ConfigurationRuleEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = "ConfigurationRuleModified"
  override val eventLogCategory = ConfigurationRuleLogCategory
  override def copySetCause(causeId:Int) = this
}

object ModifyConfigurationRule {
  def fromDiff(    
      id : Option[Int] = None
    , principal : EventActor
    , modifyDiff:ModifyConfigurationRuleDiff
    , creationDate : DateTime = new DateTime()
    , severity : Int = 100
  ) : ModifyConfigurationRule = {
    val details = EventLog.withContent{
      scala.xml.Utility.trim(<configurationRule changeType="modify" fileFormat={ConfigurationRuleEventLog.xmlVersion}>
        <id>{modifyDiff.id.value}</id>
        <displayName>{modifyDiff.name}</displayName>{
          modifyDiff.modName.map(x => SimpleDiff.stringToXml(<name/>, x) ) ++
          modifyDiff.modSerial.map(x => SimpleDiff.intToXml(<serial/>, x ) ) ++
          modifyDiff.modTarget.map(x => SimpleDiff.toXml[Option[PolicyInstanceTarget]](<target/>, x){ t =>
            t match {
              case None => <none/>
              case Some(y) => Text(y.target)
            }
          } ) ++
          modifyDiff.modPolicyInstanceIds.map(x => SimpleDiff.toXml[Set[PolicyInstanceId]](<policyInstanceIds/>, x){ ids =>
              ids.toSeq.map { id => <id>{id.value}</id> }
            } ) ++
          modifyDiff.modShortDescription.map(x => SimpleDiff.stringToXml(<shortDescription/>, x ) ) ++
          modifyDiff.modLongDescription.map(x => SimpleDiff.stringToXml(<longDescription/>, x ) ) ++
          modifyDiff.modIsActivatedStatus.map(x => SimpleDiff.booleanToXml(<isActivated/>, x ) ) ++
          modifyDiff.modIsSystem.map(x => SimpleDiff.booleanToXml(<isSystem/>, x ) )
        }
      </configurationRule>)
    }
    ModifyConfigurationRule(id, principal, details, creationDate, severity)
  }
}
