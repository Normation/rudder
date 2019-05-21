/*
*************************************************************************************
* Copyright 2018 Normation SAS
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

package com.normation.rudder.services.queries

import com.normation.inventory.domain.NodeId
import com.normation.inventory.ldap.core.LDAPConstants.A_MACHINE_UUID
import com.normation.inventory.ldap.core.LDAPConstants.OC_MACHINE
import com.normation.rudder.domain.RudderLDAPConstants.A_NODE_GROUP_UUID
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.queries.Criterion
import com.normation.rudder.domain.queries.CriterionLine
import com.normation.rudder.domain.queries.Equals
import com.normation.rudder.domain.queries.ExactStringComparator
import com.normation.rudder.domain.queries.ObjectCriterion
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.domain.queries.StringComparator
import com.normation.rudder.domain.queries.Or
import com.normation.rudder.domain.queries.And
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Failure
import net.liftweb.common.Full
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Test pending node policies with group of groups
 */
@RunWith(classOf[JUnitRunner])
class TestPendingNodePolicies extends Specification {

  /*
   * Given these groups, dynamic if not precised:
   * ( A -> B means A has a query with B as a subgroup)
   *
   *  A  ----> B
   *   `       |
   *    `      v
   *     `---> C (1)
   *  D (1)
   *
   *  A, B, C, D => OK
   *
   *  E -> F(static)
   *
   *  E -> NOK
   *
   *  // and
   *  G ---> H (0) NOK
   *  I ---> J (1) OK
   *  // or
   *  K ---> L (0) NOK
   *  M ---> N (1) OK
   *
   *  // also test with exactly one criteria (ie 0 criteria remaining if subgroup are excluded)
   *  O --> P (1) OK
   */

  val groupCriterion = ObjectCriterion("group", Seq(
    Criterion(A_NODE_GROUP_UUID, ExactStringComparator)
  ))

  //a query line for sub group
  def sub(g: NodeGroup) = CriterionLine(groupCriterion, groupCriterion.criteria.head, Equals, g.id.value)
  // a random query that will be added as dummy content - query checker will returns pre-defined things
  val cl = CriterionLine(
      ObjectCriterion(OC_MACHINE, Seq(Criterion(A_MACHINE_UUID, StringComparator)))
    , Criterion(A_MACHINE_UUID, StringComparator)
    , Equals
    , "dummy"
  )

  //the node that we will try to accept
  val node = NodeId("node")

  def orQuery     (g: NodeGroup) = Query(null, Or , List(cl, sub(g), cl))
  def andQuery    (g: NodeGroup) = Query(null, And, List(cl, sub(g), cl))
  def onlySubQuery(g: NodeGroup) = Query(null, And, List(sub(g)))
  val dummyQuery0 = Query(null, And, List(cl)) // will return 0 node
  val dummyQuery1 = Query(null, Or , List(cl)) // will return 1 node

  def ng(id: String, q: Query, dyn: Boolean = true) =
    NodeGroup(NodeGroupId(id), id, id, Some(q), dyn, Set(), true, false)

  // groups
  val c = ng("c", dummyQuery1) // ok
  val b = ng("b", andQuery(c)) // ok
  val a = ng("a", andQuery(b)) // ok

  val d = ng("d", dummyQuery1) // ok

  val f = ng("f", dummyQuery1, false) // no
  val e = ng("e", andQuery(f)) // no

  val h = ng("h", dummyQuery0) // no
  val g = ng("g", andQuery(h)) // no even if remaning query returns node
  val j = ng("j", dummyQuery1) // ok
  val i = ng("i", andQuery(j)) // ok

  val l = ng("l", dummyQuery0) // no
  val k = ng("k", orQuery(l) ) // ok b/c remaning query returns node
  val n = ng("n", dummyQuery1) // ok
  val m = ng("m", orQuery(n) ) // ok
  val pp = ng("p", dummyQuery1) // ok ('p' is already taken apparently)
  val o = ng("o", onlySubQuery(pp) ) // ok



  // a fake dyn group service
  val getDynGroups = new DynGroupService {
    override def getAllDynGroups(): Box[Seq[NodeGroup]] = Full(List(
      a, b, c, d, e, /*f, static */ g, h, i, j, k, l, m, n, o, pp
    ))
  }

  // a fake query checker
  val queryChecker = new QueryChecker {
    override def check(query: Query, nodeIds: Seq[NodeId]): Box[Seq[NodeId]] = {
      // make a 0 criteria request raise an error like LDAP would do,
      // see: https://www.rudder-project.org/redmine/issues/12338
      if(query.criteria.isEmpty) {
        Failure(s"Trying to perform an LDAP search on 0 criteria: error")
      } else {
        Full((query match {
          case x if(x == dummyQuery0) => Set.empty[NodeId]
          case x if(x == dummyQuery1) => Set(node)
          case x                      => Set(node)
        }).intersect(nodeIds.toSet).toSeq)
      }
    }
  }

  val checkGroups = new CheckPendingNodeInDynGroups(queryChecker)

  "Looking for dyn-group for node" should {
    "find awaited groups" in {
      val groups = (for {
        x <- getDynGroups.getAllDynGroups()
        y <- checkGroups.findDynGroups(Set(node), x.toList)
      } yield y) match {
        case eb: EmptyBox =>
          val e = eb ?~! "Error when processing dyngroups"
          e.rootExceptionCause.foreach(ex => ex.printStackTrace())
          throw new RuntimeException(e.messageChain)
        case Full(res) =>
          res.apply(node).map(_.value).sorted
      }
      groups must containTheSameElementsAs(List("a", "b", "c", "d", "i", "j", "k", "m", "n", "o", "p"))
    }
  }
}
