/*
*************************************************************************************
* Copyright 2016 Normation SAS
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

package com.normation.rudder.repository.jdbc

import com.normation.BoxSpecMatcher
import com.normation.rudder.db.DB
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.db.DBCommon
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.services.eventlog.HistorizationServiceImpl
import com.normation.rudder.services.policies.NodeConfigData

import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner

/**
 *
 * Test on database.
 *
 */
@RunWith(classOf[JUnitRunner])
class HistorizationRepositoryTest extends DBCommon with BoxSpecMatcher  {

  lazy val repos = new HistorizationJdbcRepository(doobie)
  lazy val service = new HistorizationServiceImpl(repos)



  sequential

  "Basic add and close for nodes" should {

    "found nothing at begining" in {
      repos.getAllOpenedNodes must haveSize[Seq[DB.SerializedNodes[Long]]](0)
    }

    "be able to add and found" in {
      val op1 = repos.updateNodes(Seq(NodeConfigData.node1), Seq())
      val op2 = repos.getAllOpenedNodes

      (op1 === ()) and (op2.size === 1) and (op2.head.nodeId === "node1")
    }

    "be able to close and found new ones" in {
      val op1 = service.updateNodes(Set(NodeConfigData.node2)).openOrThrowException("that test should not throw")
      val op2 = repos.getAllOpenedNodes

      (op1 === ()) and (op2.size === 1) and (op2.head.nodeId === "node2")
    }

    "check that policy servers are ignored (not sure why)" in {
      val op1 = service.updateNodes(Set(NodeConfigData.root)).openOrThrowException("that test should not throw")
      val op2 = repos.getAllOpenedNodes

      (op1 === ()) and (op2.size === 0)
    }

  }


  "Basic add and close for groups" should {

    //build a full category based on the groups id from NodeConfigDate
    def buildCategory(groups: List[NodeGroupId]) = FullNodeGroupCategory(NodeGroupCategoryId("test_root"), "", "", Nil
      , groups.map(g => NodeConfigData.fullRuleTargetInfos.getOrElse(g, throw new Exception(s"Missing group with ID '${g}' in NodeConfigDate, for tests")))
    )

    "found nothing at begining" in {
      repos.getAllOpenedGroups() must haveSize[Seq[(DB.SerializedGroups[Long], Seq[DB.SerializedGroupsNodes])]](0)
    }

    "be able to add and found" in {
      val op1 = repos.updateGroups(Seq(NodeConfigData.g1), Seq())
      val op2 = repos.getAllOpenedGroups()

      (op1 === ()) and (op2.size === 1) and (op2.head._1.groupId === "1")
    }

    "be able to close and found new ones" in {
      val op1 = service.updateGroups(buildCategory(NodeConfigData.g2.id :: NodeConfigData.g3.id :: Nil)).openOrThrowException("that test should not throw")
      val op2 = repos.getAllOpenedGroups()

      (op1 === ()) and (op2.size === 2) and (op2.head._1.groupId === "2")
    }

  }

  "Basic add and close for directives" should {

    "found nothing at begining" in {
      repos.getAllOpenedDirectives() must haveSize[Seq[DB.SerializedDirectives[Long]]](0)
    }

    "be able to add and found" in {
      val op1 = repos.updateDirectives(Seq((NodeConfigData.d1, NodeConfigData.fat1.toActiveTechnique, NodeConfigData.t1)), Seq())
      val op2 = repos.getAllOpenedDirectives()

      (op1 === ()) and (op2.size === 1) and (op2.head.directiveId === "d1")
    }

    "be able to close and found new ones" in {
      val op1 = service.updateDirectiveNames(NodeConfigData.directives).openOrThrowException("that test should not throw")
      val op2 = repos.getAllOpenedDirectives()

      (op1 === ()) and (op2.size === 2) and (op2.sortBy(_.directiveId).last.directiveId === "d2")
    }

  }

  "Basic add and close for rules" should {

    "found nothing at begining" in {
      repos.getAllOpenedRules() must haveSize[Seq[Rule]](0)
    }

    "be able to add and found" in {
      val op1 = repos.updateRules(Seq(NodeConfigData.r1), Seq())
      val op2 = repos.getAllOpenedRules()

      (op1 === ()) and (op2.size === 1) and (op2.head.id.value === "r1")
    }

    "be able to close and found new ones" in {
      val op1 = service.updatesRuleNames(NodeConfigData.r2 :: Nil).openOrThrowException("that test should not throw")
      val op2 = repos.getAllOpenedRules()

      (op1 === ()) and (op2.size === 1) and (op2.head.id.value === "r2")
    }

  }

}


