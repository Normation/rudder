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
import com.normation.GitVersion
import com.normation.GitVersion.Revision
import com.normation.rudder.rule.category.RuleCategoryId

final case class RuleUid(value: String) extends AnyVal {
  def debugString: String = value
  def serialize:   String = value
}

final case class RuleId(uid: RuleUid, rev: Revision = GitVersion.DEFAULT_REV) {
  def debugString: String = serialize

  def serialize: String = rev match {
    case GitVersion.DEFAULT_REV => uid.value
    case rev                    => s"${uid.value}+${rev.value}"
  }
}

object RuleId {

  // parse a directiveId which was serialize by "id.serialize"
  def parse(s: String): Either[String, RuleId] = {
    GitVersion.parseUidRev(s).map {
      case (id, rev) =>
        RuleId(RuleUid(id), rev)
    }
  }
}

/**
 * A rule is a binding between a set of directives
 * and some target (group of node, etc) on which applying
 * these directives.
 *
 * A rule may be stored in a pending state, for
 * example if it is not fully initialized.
 * In that case, it *MUST* be considered desactivated, whatever
 * the isEnabledField say.
 */
final case class Rule(
    id:         RuleId,
    name:       String,
    categoryId: RuleCategoryId, // is not mandatory, but if not present, rule is disabled

    targets: Set[RuleTarget] = Set(), // is not mandatory, but if not present, rule is disabled

    directiveIds:     Set[DirectiveId] = Set(),
    shortDescription: String = "",
    longDescription:  String = "",
    isEnabledStatus:  Boolean = false,
    isSystem:         Boolean = false,
    /*
     * Optionally, Rule can have Tags
     */
    tags:             Tags = Tags(Set())
) {
  // system object must ALWAYS be ENABLED.
  def isEnabled: Boolean = isSystem || (isEnabledStatus && !targets.isEmpty && !directiveIds.isEmpty)
}
