/*
 *************************************************************************************
 * Copyright 2020 Normation SAS
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
package com.normation.rudder.rule.category

import com.normation.eventlog.EventMetadata
import com.normation.eventlog.ModificationId
import com.normation.rudder.MockRules
import com.normation.rudder.TestActor
import com.normation.rudder.facts.nodes.MockLdapFactStorage
import com.normation.rudder.repository.ldap.ZioTReentrantLock
import com.normation.rudder.services.user.PersonIdentService
import com.normation.utils.StringUuidGenerator
import com.normation.zio.UnsafeRun
import org.eclipse.jgit.lib.PersonIdent
import org.junit.runner.RunWith
import zio.Scope
import zio.UIO
import zio.ZIO
import zio.test.*
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class RuleCategoryMockRepositoryTest extends RuleCategoryRepositoryTest {

  val mockRules = new MockRules()

  override val repository:   WoRuleCategoryRepository = mockRules.ruleCategoryRepo
  override val roRepository: RoRuleCategoryRepository = mockRules.ruleCategoryRepo
  override val initialRoot:  RuleCategory             = mockRules.rootRuleCategory

}

@RunWith(classOf[ZTestJUnitRunner])
class RuleCategoryLDAPRepositoryTest extends RuleCategoryRepositoryTest {
  import RuleCategoryRepositoryTest.*

  val mockLdap                     = new MockLdapFactStorage()
  val roLDAPRuleCategoryRepository = {
    new RoLDAPRuleCategoryRepository(
      mockLdap.rudderDit,
      mockLdap.ldapRo,
      mockLdap.ldapMapper,
      new ZioTReentrantLock("rules-lock")
    )
  }

  val woLDAPRuleCategoryRepository = {
    new WoLDAPRuleCategoryRepository(
      roLDAPRuleCategoryRepository,
      mockLdap.ldap,
      dummyUuidGen,
      null,
      dummyPersonIdentService,
      autoExportOnModify = false
    )
  }

  override val repository:   WoRuleCategoryRepository = woLDAPRuleCategoryRepository
  override val roRepository: RoRuleCategoryRepository = roLDAPRuleCategoryRepository
  override val initialRoot:  RuleCategory             = roLDAPRuleCategoryRepository.getRootCategory().runNow

}

abstract class RuleCategoryRepositoryTest extends ZIOSpecDefault {
  import RuleCategoryRepositoryTest.*

  def repository:   WoRuleCategoryRepository
  def roRepository: RoRuleCategoryRepository

  private def testClassName: String = repository.getClass.getSimpleName

  def initialRoot: RuleCategory
  val ruleCategory2         = RuleCategory(RuleCategoryId("category2"), "Category 2", "", Nil)
  val ruleCategoryParentOf2 = {
    RuleCategory(
      RuleCategoryId("parent-of-nested-category2"),
      "New parent for category 2",
      "",
      List(ruleCategory2)
    )
  }

  override def spec: Spec[TestEnvironment & Scope, Any] = {
    suite(s"Rule category repository '${testClassName}'")(
      test("create category")(
        for {
          _       <- repository.create(ruleCategory2, initialRoot.id, eventMetadata.modId, eventMetadata.actor, eventMetadata.msg)
          newRoot <- roRepository.getRootCategory()
        } yield {
          assertTrue(!initialRoot.contains(ruleCategory2.id)) &&
          assertTrue(newRoot.childs.contains(ruleCategory2))
        }
      ),
      test("error when creating root category")(
        assertZIO(
          repository
            .create(initialRoot, initialRoot.id, eventMetadata.modId, eventMetadata.actor, eventMetadata.msg)
            .either
        )(Assertion.isLeft(Assertion.assertion("root already exists")(_.fullMsg.contains("exists"))))
      ),
      test("error when creating already existing category")(
        assertZIO(
          repository
            .create(ruleCategory2, initialRoot.id, eventMetadata.modId, eventMetadata.actor, eventMetadata.msg)
            .either
        )(Assertion.isLeft(Assertion.assertion("given category already exists")(_.fullMsg.contains("exists"))))
      ),
      test("create a new parent and move existing category")(
        for {
          _ <-
            repository
              .create(ruleCategoryParentOf2, initialRoot.id, eventMetadata.modId, eventMetadata.actor, eventMetadata.msg)
          _ <-
            repository
              .updateAndMove(ruleCategory2, ruleCategoryParentOf2.id, eventMetadata.modId, eventMetadata.actor, eventMetadata.msg)

          newRoot <- roRepository.getRootCategory()
        } yield {
          assertTrue(newRoot.childs.find(_.id == ruleCategoryParentOf2.id).exists(_.contains(ruleCategory2.id))) &&
          assertTrue(newRoot.contains(ruleCategory2.id)) &&
          // TODO: this does not work for Mock test : the root is replaced by the "ruleCategoryParentOf2", and gets the root as children now :(
          assertTrue(newRoot.allChildren == initialRoot.childs.map(_.id).toSet + ruleCategory2.id + ruleCategoryParentOf2.id)
        }
      )
    ) @@ TestAspect.sequential
  }

}
private object RuleCategoryRepositoryTest {
  val eventMetadata: EventMetadata = EventMetadata(ModificationId.dummy, TestActor.actor, None)

  val dummyUuidGen: StringUuidGenerator = new StringUuidGenerator { override def newUuid: String = "" }

  val dummyPersonIdentService: PersonIdentService = new PersonIdentService {
    override def getPersonIdentOrDefault(username: String): UIO[PersonIdent] = ZIO.dieMessage("unused")
  }
}
