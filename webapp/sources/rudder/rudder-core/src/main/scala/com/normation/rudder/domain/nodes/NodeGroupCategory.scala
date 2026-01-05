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

package com.normation.rudder.domain.nodes

import com.normation.rudder.domain.categories.ItemCategory
import com.normation.rudder.domain.policies.RuleTargetInfo
import com.normation.rudder.tenants.HasSecurityTag
import com.normation.rudder.tenants.SecurityTag

/**
 * The Id for the server group category
 */
final case class NodeGroupCategoryId(value: String) extends AnyVal

/**
 * A server group category is quite similar to a Technique category :
 * an id
 * a name
 * a description
 * some subcategories
 * some items
 */
final case class NodeGroupCategory(
    id:          NodeGroupCategoryId,
    name:        String,
    description: String,
    children:    List[NodeGroupCategoryId],
    items:       List[RuleTargetInfo],
    isSystem:    Boolean,
    security:    Option[SecurityTag]
) extends ItemCategory[NodeGroupCategoryId, RuleTargetInfo]

object NodeGroupCategory {
  given HasSecurityTag[NodeGroupCategory] with {
    extension (a: NodeGroupCategory) {
      override def security: Option[SecurityTag] = a.security
      override def debugId:  String              = a.id.value
      override def updateSecurityContext(security: Option[SecurityTag]): NodeGroupCategory = a.copy(security = security)
    }
  }
}
