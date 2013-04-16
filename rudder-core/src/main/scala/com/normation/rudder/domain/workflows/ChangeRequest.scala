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

package com.normation.rudder.domain.workflows

import org.joda.time.DateTime
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.nodes._
import com.normation.eventlog.EventActor
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import net.liftweb.common.Failure
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.SectionSpec
import com.normation.eventlog.EventLog
import com.normation.rudder.domain.eventlog.ChangeRequestLogCategory
import com.normation.eventlog.ModificationId



/*
 * Question:
 * - do we need a ChangeRequestDraft object?
 *   With a different ID type, perhaps different
 *   draft type, etc. ?
 */


case class ChangeRequestId(value:Int) {
  override def toString = value.toString
}


//a container for changer request infos
final case class ChangeRequestInfo(
    name        : String
  , description : String
)

object ChangeRequest {

  def updateInfo[T <: ChangeRequest](cr:T, newInfo:ChangeRequestInfo): T = {
    cr match {
      case x:ConfigurationChangeRequest =>
        x.copy(info = newInfo).asInstanceOf[T]
      case x:RollbackChangeRequest =>
        x.copy(info = newInfo).asInstanceOf[T]
    }
  }

  def updateId[T <: ChangeRequest](cr:T, newId:ChangeRequestId): T = {
    cr match {
      case x:ConfigurationChangeRequest =>
        x.copy(id = newId).asInstanceOf[T]
      case x:RollbackChangeRequest =>
        x.copy(id = newId).asInstanceOf[T]
    }
  }

  /*
   * Replace the change request mod id by a new one.
   * If you don't want to lose the old modId, you have to check it before.
   */
  def setModId[T <: ChangeRequest](cr:T, modId:ModificationId): T = {
    cr match {
      case x:ConfigurationChangeRequest =>
        x.copy(modId = Some(modId)).asInstanceOf[T]
      case x:RollbackChangeRequest =>
        x.copy(modId = Some(modId)).asInstanceOf[T]
    }
  }
}

sealed trait ChangeRequest {

  def id : ChangeRequestId

  /*
   * A modification id is linked to a change request when the change request
   * is deployed. This modification id will be used for every change in the change
   * request. There will be only one commit for the change request and rollbacking
   * a change request means rollback everything
   */
  def modId : Option[ModificationId]

  def info : ChangeRequestInfo

}




////////////////////////////////////////
///// Some types of change request /////
////////////////////////////////////////

/**
 * A global configuration change request.
 * Can modify any number of Directives,
 * Rules and Group.
 */
case class ConfigurationChangeRequest(
    id         : ChangeRequestId
  , modId      : Option[ModificationId]
  , info       : ChangeRequestInfo
  , directives : Map[DirectiveId, DirectiveChanges]
  , nodeGroups : Map[NodeGroupId, NodeGroupChanges]
  , rules      : Map[RuleId, RuleChanges]
  // ... TODO: complete for groups and rules
) extends ChangeRequest


case class RollbackChangeRequest(
    id         : ChangeRequestId
  , modId      : Option[ModificationId]
  , info       : ChangeRequestInfo
  , rollback   : Null // TODO: rollback change request
) extends ChangeRequest


//////////////////////////////////
///// example for directives /////
//////////////////////////////////


sealed trait ChangeItem[DIFF] {
  def actor       : EventActor
  def creationDate: DateTime
  def reason      : Option[String]
  def diff        : DIFF
}

// A change for the given type is either the value
// of the item from the "current" environment or
// a diff.
// More preciselly, we have sequence of change related
// to an initial state (which can be empty).
sealed trait Change[T, DIFF, T_CHANGE <: ChangeItem[DIFF]] {
  // A change for the given type is either the value
  // of the item from the "current" environment or
  // a diff.
  // More preciselly, we have sequence of change related
  // to an initial state (which can be empty).

  //we have at least one such sequence
  def initialState: Option[T]
  def firstChange: T_CHANGE
  //the most recent change come in tail
  //(so that firstChange :: nextChanges is ordered
  def nextChanges: Seq[T_CHANGE]

  //get the composition of all change,
  //the actual change between initialState
  //and last change
  //it's a box because we can have inconsistant
  //states, like modify withou an initial state
  def change: Box[T_CHANGE]
}


/**
 * A list of modification on a given item (directive, etc).
 * The parametrisation is as follow:
 * - T is the item type ;
 * - T_DIFF is the type of the eventLog with the diff for
 *   that object.
 * As the class is sealed, you can see implementation example
 * below.
 *
 * Younger generation are on head.
 */
sealed trait Changes[T, DIFF, T_CHANGE <: ChangeItem[DIFF]] {

  // A change for the given type is either the value
  // of the item from the "current" environment or
  // a diff.
  // More preciselly, we have sequence of change related
  // to an initial state (which can be empty).

  //we have at least one such sequence
  def changes: Change[T, DIFF, T_CHANGE]

  //older changes
  def changeHistory: Seq[Change[T, DIFF, T_CHANGE]]

  //TODO: we want to be able to compose diff so that
  //we are able to have a "final" view of the Diff.
  //for example: Add(title, desc), Mod(title2), Mod(description2)
  // => Add(title2, description2)

}

case class DirectiveChangeItem(
  //no ID: that object does not have any meaning outside
  // a change request
    actor       : EventActor
  , creationDate: DateTime
  , reason      : Option[String]
  , diff        : ChangeRequestDirectiveDiff
) extends ChangeItem[ChangeRequestDirectiveDiff]


case class DirectiveChange(
    val initialState: Option[(TechniqueName, Directive, SectionSpec)]
  , val firstChange: DirectiveChangeItem
  , val nextChanges: Seq[DirectiveChangeItem]
) extends Change[(TechniqueName, Directive, SectionSpec), ChangeRequestDirectiveDiff, DirectiveChangeItem] {
  private[this] def recChange(
      previousState: Box[DirectiveChangeItem]
    , nexts:List[DirectiveChangeItem]): Box[DirectiveChangeItem] = {
    previousState match {
      case eb:EmptyBox => eb
      case Full(x) => nexts match {
        case Nil => Full(x)
        case h :: tail => (x.diff,h.diff) match {
          case ( _ , a:AddDirectiveDiff) => Failure("Trying to add an already existing Direcive (in the context of that change)")
          case (a:AddDirectiveDiff, _) => recChange(Full(h), tail)
          case (d:DeleteDirectiveDiff, _) => Failure("Trying to delete a non existing Directive (in the context of that change request)")
          case (m:ModifyToDirectiveDiff, _) => recChange(Full(h), tail)
        }
      }
    }
  }

  val change = {
    val allChanges = firstChange :: nextChanges.toList
    (initialState, firstChange.diff) match {
      case (None, a:AddDirectiveDiff) => recChange(Full(firstChange), nextChanges.toList)
      case (None, _) => Failure("Trying to modify or delete a non existing Directive (in the context of that change request)")
      case (Some((tn,d,rs)), x) => recChange(Full(firstChange.copy( diff = ModifyToDirectiveDiff(tn,d,rs))), allChanges)
    }
  }
}

case class DirectiveChanges(
    val changes: DirectiveChange
  , val changeHistory: Seq[DirectiveChange]
)extends Changes[(TechniqueName, Directive, SectionSpec), ChangeRequestDirectiveDiff, DirectiveChangeItem]


//////////////////// Node Part //////////////////////////////////////////////

case class NodeGroupChangeItem(
  //no ID: that object does not have any meaning outside
  // a change request
    actor       : EventActor
  , creationDate: DateTime
  , reason      : Option[String]
  , diff        : ChangeRequestNodeGroupDiff
) extends ChangeItem[ChangeRequestNodeGroupDiff]

case class NodeGroupChange(
    val initialState: Option[NodeGroup]
  , val firstChange : NodeGroupChangeItem
  , val nextChanges : Seq[NodeGroupChangeItem]
) extends Change[NodeGroup, ChangeRequestNodeGroupDiff, NodeGroupChangeItem] {
  private[this] def recChange(
      previousState : Box[NodeGroupChangeItem]
    , nexts         : List[NodeGroupChangeItem]) :  Box[NodeGroupChangeItem]  = {
    previousState match {
      case eb:EmptyBox => eb
      case Full(x) => nexts match {
        case Nil => Full(x) // no other changes
        case h :: tail => (x.diff,h.diff) match {
          case ( _ , a:AddNodeGroupDiff) => Failure("Trying to add an already existing NodeGroup (in the context of that change)")
          case (a:AddNodeGroupDiff, _) => recChange(Full(h), tail)
          case (d:DeleteNodeGroupDiff, _) => Failure("Trying to apply changes to a deleted NodeGroup (in the context of that change request)")
          case (m:ModifyToNodeGroupDiff, _) => recChange(Full(h), tail)
        }
      }
    }
  }

  // compute the change from the initial state to the end of the change request
  def change = {
    val allChanges = firstChange :: nextChanges.toList
    (initialState, firstChange.diff) match {
      case (None, a:AddNodeGroupDiff) => recChange(Full(firstChange), nextChanges.toList)
      case (None, _) => Failure("Trying to modify or delete a non existing Node Group (in the context of that change request)")
      case (Some(nodeGroupChange), x) => recChange(Full(firstChange.copy( diff = ModifyToNodeGroupDiff(nodeGroupChange))), allChanges)
    }
  }
}

case class NodeGroupChanges(
    val changes: NodeGroupChange
  , val changeHistory: Seq[NodeGroupChange]
)extends Changes[NodeGroup, ChangeRequestNodeGroupDiff, NodeGroupChangeItem]

case class RuleChangeItem(
  //no ID: that object does not have any meaning outside
  // a change request
    actor       : EventActor
  , creationDate: DateTime
  , reason      : Option[String]
  , diff        : ChangeRequestRuleDiff
) extends ChangeItem[ChangeRequestRuleDiff]

case class RuleChange(
    val initialState: Option[Rule]
  , val firstChange: RuleChangeItem
  , val nextChanges: Seq[RuleChangeItem]
) extends Change[Rule, ChangeRequestRuleDiff, RuleChangeItem] {

  val change = Full(firstChange)
}

case class RuleChanges(
    val changes: RuleChange
  , val changeHistory: Seq[RuleChange]
)extends Changes[Rule, ChangeRequestRuleDiff, RuleChangeItem]

