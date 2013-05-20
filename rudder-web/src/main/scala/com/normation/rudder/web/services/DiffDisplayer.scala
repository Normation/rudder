/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

package com.normation.rudder.web.services

import scala.xml.NodeSeq
import com.normation.rudder.domain.policies.DirectiveId
import bootstrap.liftweb.RudderConfig
import net.liftweb.http.S
import com.normation.rudder.web.model.JsInitContextLinkUtil._
import scala.xml.Text
import net.liftweb.http.SHtml
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.repository.FullNodeGroupCategory



trait DiffItem[T] {

  def display(implicit displayer : T => NodeSeq) : NodeSeq

}

case class Added[T](
    value:T
) extends DiffItem[T] {
  val newValue = Some(value)

  def display(implicit displayer : T => NodeSeq) : NodeSeq =
    <li style="background:none repeat scroll 0 0 #D6FFD6; list-style-type:none">
    +&nbsp;{displayer(value)}
    </li>
}

case class Deleted[T](
    value:T
) extends DiffItem[T] {

  def display(implicit displayer : T => NodeSeq) : NodeSeq =
    <li style="background:none repeat scroll 0 0 #FFD6D6; list-style-type:none">
    -&nbsp;&nbsp;{displayer(value)}
    </li>
}

case class Unchanged[T](
    value:T
) extends DiffItem[T] {

  def display(implicit displayer : T => NodeSeq) : NodeSeq =
    <li style="list-style-type:none">
    &nbsp;&nbsp;&nbsp;{displayer(value)}
    </li>
}

// Not used yet, but for later use
case class Modified[T](
    oldValue:T
  , newValue:T
) extends DiffItem[T] {

  private[this] val delete = Deleted(oldValue)
  private[this] val add    = Added(oldValue)

  def display(implicit displayer : T => NodeSeq) : NodeSeq =
  delete.display ++ add.display
}

object DiffDisplayer {

  //Directive targets Displayer
  private[this] val roDirectiveRepo = RudderConfig.roDirectiveRepository
  private[this] implicit def displayDirective(directiveId: DirectiveId) = {
    <span> Directive {createDirectiveLink(directiveId)}</span>
  }
  def displayDirectiveChangeList (
          oldDirectives:Seq[DirectiveId]
        , newDirectives:Seq[DirectiveId]
  ) : NodeSeq = {

    // First, find unchanged and deleted (have find no clean way to make a 3 way partition)
    val (unchanged,deleted) = oldDirectives.partition(newDirectives.contains)
    // Get the added ones
    val added = newDirectives.filterNot(unchanged.contains).map(Added(_))
    val deletedMap = deleted.map(Deleted(_))
    val unchangedMap = unchanged.map(Unchanged(_))

    // Finally mix all maps together in one and display it
    val changeMap:Seq[DiffItem[DirectiveId]] = deletedMap ++ unchangedMap ++ added
    <ul style="padding-left:10px">
      { for {
          change <- changeMap
        } yield {
          // Implicit used here (displayDirective)
          change.display
      } }
    </ul>
  }

  //Node groups targets Displayer
  private[this] val roNodeGroupRepository = RudderConfig.roNodeGroupRepository


  // Almost the same as display Directive see comments there for more details
  def displayRuleTargets (
          oldTargets:Seq[RuleTarget]
        , newTargets:Seq[RuleTarget]
        , groupLib: FullNodeGroupCategory
  ) : NodeSeq = {

    implicit def displayNodeGroup(target: RuleTarget) : NodeSeq= {
      target match {
        case GroupTarget(nodeGroupId) =>
          <span> Group {createGroupLink(nodeGroupId)}</span>
        case x => groupLib.allTargets.get(x).map{ targetInfo =>
            <span>
              {targetInfo.name}
              {if (targetInfo.isSystem) <span class="greyscala">(System)</span>}
            </span>
          }.getOrElse(<span> {x.target}</span>)
      }
    }

    val (unchanged,deleted) = oldTargets.partition(newTargets.contains)
    val added = newTargets.filterNot(unchanged.contains).map(Added(_))
    val deletedMap = deleted.map(Deleted(_))
    val unchangedMap = unchanged.map(Unchanged(_))

    val changeMap:Seq[DiffItem[RuleTarget]] = deletedMap ++ unchangedMap ++ added
    <ul style="padding-left:10px">
      { for {
          change <- changeMap
        } yield {
          // Implicit used here (displayNodeGroup)
          change.display
        }
      }
    </ul>
  }

}