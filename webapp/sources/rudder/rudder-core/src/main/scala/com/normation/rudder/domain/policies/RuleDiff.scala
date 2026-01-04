/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
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

package com.normation.rudder.domain.policies

import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.tenants.SecurityTag

/**
 * That file define "diff" object between rules.
 */

sealed trait RuleDiff extends TriggerDeploymentDiff

//for change request, with add type tag to RuleDiff
sealed trait ChangeRequestRuleDiff {
  def rule: Rule
}

final case class AddRuleDiff(rule: Rule) extends RuleDiff with ChangeRequestRuleDiff {
  def needDeployment: Boolean = true
}

final case class DeleteRuleDiff(rule: Rule) extends RuleDiff with ChangeRequestRuleDiff {
  def needDeployment: Boolean = true
}

final case class ModifyRuleDiff(
    id:   RuleId,
    name: String, // keep the name around to be able to display it as it was at that time

    modName:              Option[SimpleDiff[String]] = None,
    modSerial:            Option[SimpleDiff[Int]] = None,
    modTarget:            Option[SimpleDiff[Set[RuleTarget]]] = None,
    modDirectiveIds:      Option[SimpleDiff[Set[DirectiveId]]] = None,
    modShortDescription:  Option[SimpleDiff[String]] = None,
    modLongDescription:   Option[SimpleDiff[String]] = None,
    modreasons:           Option[SimpleDiff[String]] = None,
    modIsActivatedStatus: Option[SimpleDiff[Boolean]] = None,
    modIsSystem:          Option[SimpleDiff[Boolean]] = None,
    modCategory:          Option[SimpleDiff[RuleCategoryId]] = None,
    modTags:              Option[SimpleDiff[Set[Tag]]] = None,
    modSecurityTag:       Option[SimpleDiff[Option[SecurityTag]]] = None
) extends RuleDiff {
  def needDeployment: Boolean = {
    modSerial.isDefined || modTarget.isDefined || modDirectiveIds.isDefined ||
    modIsActivatedStatus.isDefined || modName.isDefined || modSecurityTag.isDefined
  }
}

final case class ModifyToRuleDiff(
    rule: Rule
) extends RuleDiff with ChangeRequestRuleDiff {
  // This case is undecidable, so it is always true
  def needDeployment: Boolean = true
}
