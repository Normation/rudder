/*
 *************************************************************************************
 * Copyright 2013 Normation SAS
 *************************************************************************************
 *
 * This file is part of Rudder.
 *
 * Rudder is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * In accordance with the terms of section 7 (7. Additional Terms.) of
 * the GNU General Public License version 3, the copyright holders add
 * the following Additional permissions:
 * Notwithstanding to the terms of section 5 (5. Conveying Modified Source
 * Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
 * Public License version 3, when you create a Related Module, this
 * Related Module is not considered as a part of the work and may be
 * distributed under the license agreement of your choice.
 * A "Related Module" means a set of sources files including their
 * documentation that, without modification of the Source Code, enables
 * supplementary functions or services in addition to those offered by
 * the Software.
 *
 * Rudder is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

 *
 *************************************************************************************
 */

package com.normation.rudder.web.services

import com.normation.rudder.domain.policies.*
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.rule.category.RuleCategoryService
import com.normation.rudder.tenants.QueryContext
import com.normation.rudder.web.model.LinkUtil
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.util.Helpers.*
import scala.xml.Elem
import scala.xml.NodeSeq
import scala.xml.Text

trait DiffItem[T] {

  def display(implicit displayer: T => NodeSeq): NodeSeq

}

final case class Added[T](
    value: T
) extends DiffItem[T] {
  val newValue: Some[T] = Some(value)

  def display(implicit displayer: T => NodeSeq): NodeSeq = {
    <li style="background:none repeat scroll 0 0 #D6FFD6; list-style-type:none">
      +&nbsp;{displayer(value)}
    </li>
  }
}

final case class Deleted[T](
    value: T
) extends DiffItem[T] {

  def display(implicit displayer: T => NodeSeq): NodeSeq = {
    <li style="background:none repeat scroll 0 0 #FFD6D6; list-style-type:none">
      -&nbsp;&nbsp;{displayer(value)}
    </li>
  }
}

final case class Unchanged[T](
    value: T
) extends DiffItem[T] {

  def display(implicit displayer: T => NodeSeq): NodeSeq = {
    <li style="list-style-type:none">
      &nbsp;&nbsp;&nbsp;{displayer(value)}
    </li>
  }
}

// Not used yet, but for later use
final case class Modified[T](
    oldValue: T,
    newValue: T
) extends DiffItem[T] {

  private val delete = Deleted(oldValue)
  private val add    = Added(oldValue)

  def display(implicit displayer: T => NodeSeq): NodeSeq =
    delete.display ++ add.display
}

object DiffDisplayer {
  def liModDetailsXML(id: String, name: String) = {
    <div id={id}><b>{name}</b>
      <ul>
        <li class="deleted"></li>
        <li class="added"></li>
      </ul>
    </div>
  }

  def mapSimpleDiffT[T](opt: Option[SimpleDiff[T]], t: T => String) = opt.map { diff =>
    ".deleted *" #> t(diff.oldValue) &
    ".added *" #> t(diff.newValue)
  }

  def mapSimpleDiff[T](opt: Option[SimpleDiff[T]]) = mapSimpleDiffT(opt, (x: T) => x.toString)

  def mapSimpleDiff[T](opt: Option[SimpleDiff[T]], id: DirectiveId) = opt.map { diff =>
    ".deleted *" #> diff.oldValue.toString &
    ".added *" #> diff.newValue.toString &
    "#directiveID" #> id.serialize
  }

  def mapComplexDiff[T](opt: Option[SimpleDiff[T]], title: String)(display: T => NodeSeq) = {
    opt match {
      case None       => NodeSeq.Empty
      case Some(diff) =>
        <div id={title}><b>{title}:</b>
          <ul>
            <li class="deleted">-{display(diff.oldValue)}</li>
            <li class="added">+{display(diff.newValue)}</li>
          </ul>
          </div>
    }
  }
  def displaySimpleDiffT[T](
      diff: Option[SimpleDiff[T]],
      name: String
  )(display: T => String): NodeSeq = mapComplexDiff(diff, name)(display.andThen(Text(_)))

  def displaySimpleDiff(
      diff:    Option[SimpleDiff[String]],
      name:    String,
      default: String
  ): NodeSeq = displaySimpleDiff(diff, name).getOrElse(Text(default))

  def displaySimpleDiff(
      diff: Option[SimpleDiff[String]],
      name: String
  ): Option[NodeSeq] = diff.map(value => displayFormDiff(value, name))

  def displayFormDiff(
      diff: SimpleDiff[String],
      name: String
  ): NodeSeq = {
    <b>{name}:</b> ++
    <ul>
      <li class="deleted">-{diff.oldValue}</li>
      <li class="added">+{diff.newValue}</li>
    </ul>
  }
}

class DiffDisplayer(linkUtil: LinkUtil) extends Loggable {

  implicit private def displayDirective(directiveId: DirectiveId): Elem = {
    <span> Directive {linkUtil.createDirectiveLink(directiveId.uid)}</span>
  }
  def displayDirectiveChangeList(
      oldDirectives: Seq[DirectiveId],
      newDirectives: Seq[DirectiveId]
  ): NodeSeq = {

    // First, find unchanged and deleted (have find no clean way to make a 3 way partition)
    val (unchanged, deleted) = oldDirectives.partition(newDirectives.contains)
    // Get the added ones
    val added                = newDirectives.filterNot(unchanged.contains).map(Added(_))
    val deletedMap           = deleted.map(Deleted(_))
    val unchangedMap         = unchanged.map(Unchanged(_))

    // Finally mix all maps together in one and display it
    val changeMap: Seq[DiffItem[DirectiveId]] = deletedMap ++ unchangedMap ++ added
    <ul style="padding-left:10px">
      {
      for {
        change <- changeMap
      } yield {
        // Implicit used here (displayDirective)
        change.display
      }
    }
    </ul>
  }

  // Almost the same as display Directive see comments there for more details
  def displayRuleTargets(
      oldTargets: Seq[RuleTarget],
      newTargets: Seq[RuleTarget],
      groupLib:   FullNodeGroupCategory
  )(implicit qc: QueryContext): NodeSeq = {

    implicit def displayNodeGroup(target: RuleTarget): NodeSeq = {
      target match {
        case TargetUnion(targets)                =>
          <span> all Nodes from: <ul>{targets.map(t => <li>{displayNodeGroup(t)}</li>)}</ul> </span>
        case TargetIntersection(targets)         =>
          <span> Nodes that belongs to all these groups: <ul>{targets.map(t => <li>{displayNodeGroup(t)}</li>)}</ul> </span>
        case TargetExclusion(included, excluded) =>
          <span> Include {displayNodeGroup(included)} </span>
              <br/><span> Exclude {displayNodeGroup(excluded)} </span>

        case GroupTarget(nodeGroupId) =>
          <span> Group {linkUtil.createGroupLink(nodeGroupId)}</span>
        case x                        =>
          groupLib.allTargets
            .get(x)
            .map { targetInfo =>
              <span>
            {targetInfo.name}
            {if (targetInfo.isSystem) <span class="text-secondary">(System)</span>}
          </span>
            }
            .getOrElse(<span> {x.target}</span>)
      }
    }

    (oldTargets, newTargets) match {
      case (Seq(TargetExclusion(newIncluded, newExcluded)), Seq(TargetExclusion(oldIncluded, oldExcluded))) =>
        def displayKind(kind: TargetComposition): NodeSeq = {
          kind match {
            case _: TargetUnion        =>
              <span> all Nodes from: </span>
            case _: TargetIntersection =>
              <span> Nodes that belongs to all these groups:</span>
          }
        }

        val includedKind    = {
          ((newIncluded, oldIncluded) match {
            case (_: TargetUnion, _: TargetUnion) | (_: TargetIntersection, _: TargetIntersection) =>
              Seq(Unchanged(newIncluded))
            case _                                                                                 =>
              (Seq(Deleted(oldIncluded), Added(newIncluded)))
          }).flatMap(_.display(using displayKind))
        }
        val excludedKind    = {
          ((newExcluded, oldExcluded) match {
            case (_: TargetUnion, _: TargetUnion) | (_: TargetIntersection, _: TargetIntersection) =>
              Seq(Unchanged(newExcluded))
            case _                                                                                 =>
              (Seq(Deleted(oldExcluded), Added(newExcluded)))
          }).flatMap(_.display(using displayKind))
        }
        val includedTargets = displayRuleTargets(newIncluded.targets.toSeq, oldIncluded.targets.toSeq, groupLib)
        val excludedTargets = displayRuleTargets(newExcluded.targets.toSeq, oldExcluded.targets.toSeq, groupLib)
        <span> Include</span> ++ includedKind ++ includedTargets ++
        <span> Exclude</span> ++ excludedKind ++ excludedTargets

      case (_, _) =>
        val (unchanged, deleted) = oldTargets.partition(newTargets.contains)
        val added                = newTargets.filterNot(unchanged.contains).map(Added(_))
        val deletedMap           = deleted.map(Deleted(_))
        val unchangedMap         = unchanged.map(Unchanged(_))

        val changeMap: Seq[DiffItem[RuleTarget]] = deletedMap ++ unchangedMap ++ added
        <ul style="padding-left:10px">
          {
          for {
            change <- changeMap
          } yield {
            // Implicit used here (displayNodeGroup)
            change.display
          }
        }
        </ul>
    }
  }

  //
  private val ruleCategoryService = new RuleCategoryService()

  def displayRuleCategory(
      rootCategory: RuleCategory,
      oldCategory:  RuleCategoryId,
      newCategory:  Option[RuleCategoryId]
  ): Elem = {

    def getCategoryFullName(category: RuleCategoryId) = {
      ruleCategoryService.shortFqdn(rootCategory, category) match {
        case Full(fqdn) => fqdn
        case eb: EmptyBox =>
          logger.error(s"Error while looking for category ${category.value}")
          category.value
      }
    }
    implicit def displayRuleCategory(ruleCategoryId: RuleCategoryId): Elem = {
      <span>{getCategoryFullName(ruleCategoryId)}</span>
    }

    newCategory match {
      case Some(newCategory) =>
        val changes = Seq(Deleted(oldCategory), Added(newCategory))
        <ul style="padding-left:10px">
          {
          for {
            change <- changes
          } yield {
            // Implicit used here (displayRuleCategory)
            change.display
          }
        }
        </ul>
      case None              => displayRuleCategory(oldCategory)

    }
  }
}
