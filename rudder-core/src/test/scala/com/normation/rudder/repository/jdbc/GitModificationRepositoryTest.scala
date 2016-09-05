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
import com.normation.eventlog.ModificationId
import com.normation.rudder.db.DB
import com.normation.rudder.migration.DBCommon
import com.normation.rudder.repository.GitCommitId

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner

import net.liftweb.common.Box
import net.liftweb.common.Full

import scalaz.{Failure => _, _}, Scalaz._
import doobie.imports._
import scalaz.concurrent.Task

/**
 *
 * Test on database.
 *
 */
@RunWith(classOf[JUnitRunner])
class GitModificationRepositoryTest extends DBCommon with BoxSpecMatcher {

  val repos = new GitModificationRepositoryImpl(doobie)
  import doobie._

  implicit def toCommitId(s: String) = GitCommitId(s)
  implicit def toModId(s: String) = ModificationId(s)

  sequential

  type GET = Box[Option[GitCommitId]]
  type ADD = Box[DB.GitCommitJoin]

  "Git modification repo" should {

    "found nothing at start" in {
      sql"select gitcommit from gitcommit".query[String].list.transact(xa).run must beEmpty
    }

    "be able to add commits" in {

      val res = Vector(
          repos.addCommit("g1", "m1")
        , repos.addCommit("g2", "m2")
        , repos.addCommit("g3", "m3")

          //this one has two commit id for the same modid
        , repos.addCommit("g41", "m4")
        , repos.addCommit("g42", "m4")
      )

      (res must contain((y:ADD) => y.mustFull).foreach) and
      (sql"select count(*) from gitcommit".query[Long].unique.transact(xa).run === 5)

    }

    "find back a commit by" in {
      repos.getCommits("m3") mustFullEq(Some(GitCommitId("g3")) )
    }

    "not find back a non existing commit" in {
      repos.getCommits("badId") mustFullEq(None)
    }

    "produce an error when several commits were added" in {
      repos.getCommits("m4") mustFails
    }

  }
}
