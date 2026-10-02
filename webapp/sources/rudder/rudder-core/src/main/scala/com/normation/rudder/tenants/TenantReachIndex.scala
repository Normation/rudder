/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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

package com.normation.rudder.tenants

import scala.collection.concurrent.TrieMap

/*
 * An utility class that allows to know what tenant tag object in the collection can be reached
 * from a given tenant, in an efficient way. The main use case is for global parameters, that
 * need to be scoped but are not bound to a node at first.
 *
 * Untagged and `open` parameters scope to `All`, so an installation without tenants gets every
 * parameter on every node.
 *
 * This is not an opaque type so that we can have the memoization.
 */
final class TenantReachIndex[A] private (byReach: List[(TenantReach, List[A])]) {

  private val memo = TrieMap.empty[Option[SecurityTag], List[A]]

  def reaching(tag: Option[SecurityTag]): List[A] = {
    memo.getOrElseUpdate(tag, byReach.flatMap { case (reach, as) => if (reach.reaches(tag)) as else Nil })
  }
}

object TenantReachIndex {

  def apply[A](tagged: Iterable[(Option[SecurityTag], A)]): TenantReachIndex[A] = {
    new TenantReachIndex(tagged.groupBy(_._1).toList.map { case (tag, as) => (TenantReach.of(tag), as.map(_._2).toList) })
  }
}
