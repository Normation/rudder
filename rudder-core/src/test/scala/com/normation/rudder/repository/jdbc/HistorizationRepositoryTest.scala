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

import scala.concurrent.Await
import scala.util.Try

import com.normation.BoxSpecMatcher
import com.normation.eventlog.ModificationId
import com.normation.rudder.db.DB
import com.normation.rudder.migration.DBCommon
import com.normation.rudder.repository.GitCommitId

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner

import net.liftweb.common.Box
import net.liftweb.common.Full
import com.normation.rudder.services.eventlog.HistorizationServiceImpl
import com.normation.rudder.services.policies.NodeConfigData
import org.specs2.specification.mutable.ExecutionEnvironment
import org.specs2.concurrent.ExecutionEnv

/**
 *
 * Test on database.
 *
 */
@RunWith(classOf[JUnitRunner])
class HistorizationRepositoryTest extends DBCommon with BoxSpecMatcher with ExecutionEnvironment {
def is(implicit ee: ExecutionEnv) = {

  import schema.api._

  val repos = new HistorizationJdbcRepository(schema)
  val service = new HistorizationServiceImpl(repos)


  val node1 = NodeConfigData.node1
  val node2 = NodeConfigData.node2

  sequential

  "Basic add and close for nodes" should {

    "found nothing at begining" in {
      repos.getAllOpenedNodes must haveSize[Seq[DB.SerializedNodes]](0).await
    }

    "be able to add and found" in {
      val op1 = Await.result(repos.updateNodes(Seq(node1), Seq()), MAX_TIME)
      val op2 = Await.result(repos.getAllOpenedNodes, MAX_TIME)

      (op1 === ()) and (op2.size === 1) and (op2.head.nodeId === "node1")
    }

    "be able to close and found new ones" in {
      val op1 = service.updateNodes(Set(node2)).openOrThrowException("that test should not throw")
      val op2 = Await.result(repos.getAllOpenedNodes, MAX_TIME)

      (op1 === ()) and (op2.size === 1) and (op2.head.nodeId === "node2")
    }
  }
} }
