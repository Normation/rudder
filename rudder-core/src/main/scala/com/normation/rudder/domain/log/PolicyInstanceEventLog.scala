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
import com.normation.cfclerk.domain._
import com.normation.utils.HashcodeCaching

sealed trait PolicyInstanceEventLog extends EventLog

object PolicyInstanceEventLog {
  
  val xmlVersion = "1.0"
          
  /**
   * Print to XML a configuration rule, used
   * for "add" and "delete" actions. 
   */
  def toXml(
      ptName             : PolicyPackageName
    , variableRootSection: SectionSpec
    , pi                 : PolicyInstance
    , action             : String
  ) = {
    scala.xml.Utility.trim(
      <policyInstance changeType={action} fileFormat={xmlVersion}>
        <id>{pi.id.value}</id>
        <displayName>{pi.name}</displayName>
        <policyTemplateName>{ptName.value}</policyTemplateName>
        <policyTemplateVersion>{pi.policyTemplateVersion}</policyTemplateVersion>
        {SectionVal.toXml(SectionVal.piValToSectionVal(variableRootSection, pi.parameters))}
        <shortDescription>{pi.shortDescription}</shortDescription>
        <longDescription>{pi.longDescription}</longDescription>
        <priority>{pi.priority}</priority>
        <isActivated>{pi.isActivated}</isActivated>
        <isSystem>{pi.isSystem}</isSystem>
      </policyInstance>
    )
  }
  
}

final case class AddPolicyInstance(
    override val id : Option[Int] = None
  , override val principal : EventActor
  , override val details : NodeSeq
  , override val creationDate : DateTime = new DateTime()
  , override val severity : Int = 100
) extends PolicyInstanceEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = "PolicyInstanceAdded"
  override val eventLogCategory = PolicyInstanceLogCategory
  override def copySetCause(causeId:Int) = this
}

object AddPolicyInstance {
  def fromDiff(
      id : Option[Int] = None
    , principal : EventActor
    , addDiff:AddPolicyInstanceDiff
    , variableRootSection: SectionSpec
    , creationDate : DateTime = new DateTime()
    , severity : Int = 100
  ) : AddPolicyInstance = {
    val details = EventLog.withContent(PolicyInstanceEventLog.toXml(
        addDiff.policyTemplateName, 
        variableRootSection,
        addDiff.pi, "add"
    ))
    
    AddPolicyInstance(id, principal, details, creationDate, severity)
  }
}

final case class DeletePolicyInstance(
    override val id : Option[Int] = None
  , override val principal : EventActor
  , override val details : NodeSeq
  , override val creationDate : DateTime = new DateTime()
  , override val severity : Int = 100
) extends PolicyInstanceEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = "PolicyInstanceDeleted"
  override val eventLogCategory = PolicyInstanceLogCategory
  override def copySetCause(causeId:Int) = this
}

object DeletePolicyInstance {
  def fromDiff(    
      id : Option[Int] = None
    , principal : EventActor
    , deleteDiff:DeletePolicyInstanceDiff
    , variableRootSection: SectionSpec
    , creationDate : DateTime = new DateTime()
    , severity : Int = 100
  ) : DeletePolicyInstance = {
    val details = EventLog.withContent(PolicyInstanceEventLog.toXml(
        deleteDiff.policyTemplateName,
        variableRootSection, 
        deleteDiff.pi, "delete"
    ))

    DeletePolicyInstance(id, principal, details, creationDate, severity)
  }
}

final case class ModifyPolicyInstance(
    override val id : Option[Int] = None
  , override val principal : EventActor
  , override val details : NodeSeq
  , override val creationDate : DateTime = new DateTime()
  , override val severity : Int = 100
) extends PolicyInstanceEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = "PolicyInstanceModified"
  override val eventLogCategory = PolicyInstanceLogCategory
  override def copySetCause(causeId:Int) = this
}

object ModifyPolicyInstance {
  def fromDiff(    
      id : Option[Int] = None
    , principal : EventActor
    , modifyDiff:ModifyPolicyInstanceDiff
    , creationDate : DateTime = new DateTime()
    , severity : Int = 100
  ) : ModifyPolicyInstance = {
    val details = EventLog.withContent{
      scala.xml.Utility.trim(
      <policyInstance changeType="modify" fileFormat={PolicyInstanceEventLog.xmlVersion}>
        <id>{modifyDiff.id.value}</id>
        <policyTemplateName>{modifyDiff.policyTemplateName.value}</policyTemplateName>
        <displayName>{modifyDiff.name}</displayName>{
          modifyDiff.modName.map(x => SimpleDiff.stringToXml(<name/>, x) ) ++
          modifyDiff.modPolicyTemplateVersion.map(x => SimpleDiff.toXml[PolicyVersion](<policyTemplateVersion/>, x){ v =>
            Text(v.toString)
          } ) ++
          modifyDiff.modParameters.map(x => SimpleDiff.toXml[SectionVal](<parameters/>, x){ sv =>
            SectionVal.toXml(sv)
          } ) ++
          modifyDiff.modShortDescription.map(x => SimpleDiff.stringToXml(<shortDescription/>, x ) ) ++
          modifyDiff.modLongDescription.map(x => SimpleDiff.stringToXml(<longDescription/>, x ) ) ++
          modifyDiff.modPriority.map(x => SimpleDiff.intToXml(<priority/>, x ) ) ++
          modifyDiff.modIsActivated.map(x => SimpleDiff.booleanToXml(<isActivated/>, x ) ) ++
          modifyDiff.modIsSystem.map(x => SimpleDiff.booleanToXml(<isSystem/>, x ) )
        }
      </policyInstance>)
    }
    ModifyPolicyInstance(id, principal, details, creationDate, severity)
  }
}
