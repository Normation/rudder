/*
*************************************************************************************
* Copyright 2011-2013 Normation SAS
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

package com.normation.rudder.services.workflows

import org.joda.time.DateTime
import com.normation.cfclerk.domain.TechniqueName
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.policies.{ AddDirectiveDiff, Directive, ModifyToDirectiveDiff }
import com.normation.rudder.domain.workflows._
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.domain.workflows.ChangeRequestInfo
import com.normation.cfclerk.domain.SectionSpec
import net.liftweb.common.Loggable
import com.normation.rudder.domain.policies.DeleteDirectiveDiff
import com.normation.rudder.domain.policies.ChangeRequestDirectiveDiff
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.nodes.NodeGroupDiff
import com.normation.rudder.domain.nodes.ChangeRequestNodeGroupDiff
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.ChangeRequestRuleDiff
import com.normation.rudder.repository.RoChangeRequestRepository
import com.normation.rudder.repository.WoChangeRequestRepository
import com.normation.rudder.services.eventlog.ChangeRequestEventLogService
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.eventlog.AddChangeRequestDiff
import net.liftweb.common.Full
import net.liftweb.common.EmptyBox
import net.liftweb.common.Box
import com.normation.rudder.domain.eventlog.ChangeRequestDiff
import com.normation.rudder.domain.eventlog.AddChangeRequestDiff
import com.normation.rudder.domain.eventlog.ModifyToChangeRequestDiff
import com.normation.rudder.domain.eventlog.DeleteChangeRequestDiff
import com.normation.rudder.domain.eventlog.AddChangeRequestDiff
import com.normation.rudder.domain.eventlog.ModifyToChangeRequestDiff
import com.normation.rudder.domain.eventlog.ChangeRequestEventLog



/**
 * A service that handle all the logic about how
 * a change request is created / updated from basic parts.
 */
trait ChangeRequestService {

  def createChangeRequestFromDirective(
      changeRequestName: String
    , changeRequestDesc: String
    , techniqueName    : TechniqueName
    , rootSection      : SectionSpec
    , directiveId      : DirectiveId
    , originalDirective: Option[Directive]
    , diff             : ChangeRequestDirectiveDiff
    , actor            : EventActor
    , reason           : Option[String]
  ) : Box[ChangeRequest]

  def createChangeRequestFromRule(
      changeRequestName: String
    , changeRequestDesc: String
    , rule             : Rule
    , originalRule     : Option[Rule]
    , diff             : ChangeRequestRuleDiff
    , actor            : EventActor
    , reason           : Option[String]
  ) : Box[ChangeRequest]

  def createChangeRequestFromNodeGroup(
      changeRequestName: String
    , changeRequestDesc: String
    , nodeGroup        : NodeGroup
    , originalNodeGroup: Option[NodeGroup]
    , diff             : ChangeRequestNodeGroupDiff
    , actor            : EventActor
    , reason           : Option[String]
  ) : Box[ChangeRequest]

  def updateChangeRequestInfo(
      oldChangeRequest : ChangeRequest
    , newInfo          : ChangeRequestInfo
    , actor            : EventActor
    , reason           : Option[String]
  ) : Box[ChangeRequest]

  def get(id:ChangeRequestId) : Box[Option[ChangeRequest]]

  def getLastLog(id:ChangeRequestId) : Box[Option[ChangeRequestEventLog]]
}


class ChangeRequestServiceImpl(
    roChangeRequestRepository    : RoChangeRequestRepository
  , woChangeRequestRepository    : WoChangeRequestRepository
  , changeRequestEventLogService : ChangeRequestEventLogService
  , uuidGen                      : StringUuidGenerator
) extends ChangeRequestService with Loggable {

  private[this] def saveAndLogChangeRequest(diff:ChangeRequestDiff,actor:EventActor,reason:Option[String]) = {
    val changeRequest = diff.changeRequest
    // We need to remap back to the original type to fetch the id of the CR created
    val save = diff match {
      case add:AddChangeRequestDiff         => woChangeRequestRepository.createChangeRequest(diff.changeRequest, actor, reason).map(AddChangeRequestDiff(_))
      case modify:ModifyToChangeRequestDiff => woChangeRequestRepository.updateChangeRequest(changeRequest, actor, reason).map(x => modify) // For modification the id is already correct
      case delete:DeleteChangeRequestDiff   => woChangeRequestRepository.deleteChangeRequest(changeRequest.id, actor, reason).map(DeleteChangeRequestDiff(_))
    }

    for {
    saved  <- save ?~! s"could not save change request ${changeRequest.info.name}"
    modId  =  ModificationId(uuidGen.newUuid)
    logged <- changeRequestEventLogService.saveChangeRequestLog(modId, actor, saved, reason) ?~!
                s"could not save event log for change request ${saved.changeRequest.id} creation"
    } yield { saved.changeRequest }
  }

  def get(id:ChangeRequestId) : Box[Option[ChangeRequest]] = roChangeRequestRepository.get(id)

  def getLastLog(id:ChangeRequestId) : Box[Option[ChangeRequestEventLog]] = changeRequestEventLogService.getLastLog(id)

  def updateChangeRequestInfo(
      oldChangeRequest : ChangeRequest
    , newInfo          : ChangeRequestInfo
    , actor            : EventActor
    , reason           : Option[String]
  ) : Box[ChangeRequest] = {
    val newCr = ChangeRequest.updateInfo(oldChangeRequest, newInfo)
    saveAndLogChangeRequest(ModifyToChangeRequestDiff(newCr,oldChangeRequest), actor, reason)
  }

  def createChangeRequestFromDirective(
      changeRequestName: String
    , changeRequestDesc: String
    , techniqueName    : TechniqueName
    , rootSection      : SectionSpec
    , directiveId      : DirectiveId
    , originalDirective: Option[Directive]
    , diff             : ChangeRequestDirectiveDiff
    , actor            : EventActor
    , reason           : Option[String]
  ) : Box[ChangeRequest] = {

    val initialState = originalDirective match {
      case None =>  None
      case Some(x) => Some((techniqueName, x, rootSection))
    }
    val change = DirectiveChange(
                     initialState = initialState
                   , firstChange = DirectiveChangeItem(actor, DateTime.now, reason, diff)
                   , Seq()
                 )
    logger.debug(change)
    val changeRequest =  ConfigurationChangeRequest(
        ChangeRequestId(0)
      , None
      , ChangeRequestInfo(
            changeRequestName
          , changeRequestDesc
        )
      , Map(directiveId -> DirectiveChanges(change, Seq()))
      , Map()
      , Map()
    )
    saveAndLogChangeRequest(AddChangeRequestDiff(changeRequest), actor, reason)
  }

  def createChangeRequestFromRule(
      changeRequestName: String
    , changeRequestDesc: String
    , rule             : Rule
    , originalRule     : Option[Rule]
    , diff             : ChangeRequestRuleDiff
    , actor            : EventActor
    , reason           : Option[String]
  ) : Box[ChangeRequest] = {
   val change = RuleChange(
                     initialState = originalRule
                   , firstChange = RuleChangeItem(actor, DateTime.now, reason, diff)
                   , Seq()
                 )
    logger.debug(change)
   val changeRequest = ConfigurationChangeRequest(
        ChangeRequestId(0)
      , None
      , ChangeRequestInfo(
            changeRequestName
          , changeRequestDesc
        )
      , Map()
      , Map()
      , Map(rule.id -> RuleChanges(change, Seq()))
    )
    saveAndLogChangeRequest(AddChangeRequestDiff(changeRequest), actor, reason)
  }

  def createChangeRequestFromNodeGroup(
      changeRequestName: String
    , changeRequestDesc: String
    , nodeGroup        : NodeGroup
    , originalNodeGroup: Option[NodeGroup]
    , diff             : ChangeRequestNodeGroupDiff
    , actor            : EventActor
    , reason           : Option[String]
  ) : Box[ChangeRequest] = {

    val change = NodeGroupChange(
                     initialState = originalNodeGroup
                   , firstChange = NodeGroupChangeItem(actor, DateTime.now, reason, diff)
                   , Seq()
                 )
    logger.debug(change)
   val changeRequest = ConfigurationChangeRequest(
        ChangeRequestId(0)
      , None
      , ChangeRequestInfo(
            changeRequestName
          , changeRequestDesc
        )
      , Map()
      , Map(nodeGroup.id -> NodeGroupChanges(change,Seq()))
      , Map()
    )
    saveAndLogChangeRequest(AddChangeRequestDiff(changeRequest), actor, reason)
  }


}