/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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
package com.normation.rudder.rest

import com.normation.cfclerk.domain.RootTechniqueCategory
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueResourceId
import com.normation.cfclerk.services.TechniqueReader
import com.normation.cfclerk.services.TechniquesInfo
import com.normation.cfclerk.services.TechniquesLibraryUpdateType
import com.normation.errors.IOResult
import com.normation.rudder.MockRules
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.rest.lift.JRuleCategories
import com.normation.rudder.rest.lift.MergePolicy
import com.normation.rudder.rest.lift.PolicyArchive
import com.normation.rudder.rest.lift.RuleCategoryArchive
import com.normation.rudder.rest.lift.SaveArchiveServicebyRepo
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.utils.StringUuidGenerator
import io.scalaland.chimney.syntax.*
import java.io.InputStream
import net.liftweb.actor.MockLiftActor
import org.junit.runner.RunWith
import scala.collection.SortedSet
import zio.Chunk
import zio.Scope
import zio.test.*
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class SaveArchiveServiceTest extends ZIOSpecDefault {
  import SaveArchiveServiceTest.*

  implicit val qc: QueryContext = QueryContext.testQC

  val mockRules = new MockRules()

  val rootRuleCategory            = mockRules.rootRuleCategory
  val ruleCategory2               = RuleCategory(RuleCategoryId("category2"), "Category 2", "", Nil)
  val rootRuleCategoryWithCat2    = {
    rootRuleCategory.copy(childs = List(ruleCategory2))
  }
  val rootRuleCategoryWithCat12   = {
    rootRuleCategory.copy(childs = rootRuleCategory.childs :+ ruleCategory2)
  }
  val rootRuleCategoryWithSubcat2 = {
    val newCat = RuleCategory(
      RuleCategoryId("parent-of-nested-category2"),
      "New parent for category 2",
      "",
      List(ruleCategory2)
    )
    rootRuleCategory.copy(childs = rootRuleCategory.childs :+ newCat)
  }

  override def spec: Spec[TestEnvironment & Scope, Any] = {
    suite("SaveArchiveService")(
      testSaveRuleCategories("save initial root rule category idempotently")(
        PolicyArchive.empty.copy(ruleCats = Chunk.single(rootRuleCategory.transformInto[RuleCategoryArchive])),
        rootRuleCategory.transformInto[JRuleCategories]
      ) @@ TestAspect.repeats(2),
      testSaveRuleCategories("keep old rule category when tree is empty")(
        PolicyArchive.empty.copy(ruleCats = Chunk.single(rootRuleCategory.copy(childs = Nil).transformInto[RuleCategoryArchive])),
        rootRuleCategory.transformInto[JRuleCategories]
      ),
      testSaveRuleCategories("save new rule category2")(
        PolicyArchive.empty.copy(ruleCats = Chunk.single(rootRuleCategoryWithCat2.transformInto[RuleCategoryArchive])),
        rootRuleCategoryWithCat12.transformInto[JRuleCategories]
      ),
      testSaveRuleCategories("move nested rule category2")(
        PolicyArchive.empty.copy(ruleCats = Chunk.single(rootRuleCategoryWithSubcat2.transformInto[RuleCategoryArchive])),
        rootRuleCategoryWithSubcat2.transformInto[JRuleCategories]
      )
    ) @@ TestAspect.sequential
  }

  /**
   * Test the result using JRuleCategories, which sorts children by id
   */
  private def testSaveRuleCategories(label: String)(policyArchive: PolicyArchive, expectedRoot: JRuleCategories) = test(label) {
    val ruleCategoryRepo = mockRules.ruleCategoryRepo
    val archiveSaver     = new SaveArchiveServicebyRepo(
      null,
      dummyTechniqueReader,
      null,
      null,
      null,
      null,
      null,
      null,
      ruleCategoryRepo,
      ruleCategoryRepo,
      null,
      mockActor,
      dummyUuidGen
    )
    for {
      _    <- archiveSaver.save(policyArchive, MergePolicy.KeepRuleTargets)
      root <- ruleCategoryRepo.getRootCategory().map(_.transformInto[JRuleCategories])
    } yield {
      assertTrue(root == expectedRoot)
    }
  }

}

private object SaveArchiveServiceTest {
  private val mockActor    = new MockLiftActor
  private val dummyUuidGen = new StringUuidGenerator { override def newUuid: String = "not used" }

  private val dummyTechniqueReader = new TechniqueReader {
    override def readTechniques: TechniquesInfo = TechniquesInfo(
      RootTechniqueCategory("name", "description", Set.empty, SortedSet.empty),
      "rev",
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty
    )

    override def getMetadataContent[T](techniqueId: TechniqueId)(useIt: Option[InputStream] => IOResult[T]): IOResult[T] = ???
    override def getResourceContent[T](techniqueResourceId: TechniqueResourceId, postfixName: Option[String])(
        useIt: Option[InputStream] => IOResult[T]
    ): IOResult[T] = ???
    override def getModifiedTechniques: Map[TechniqueName, TechniquesLibraryUpdateType] = ???
    override def needReload(): Boolean = ???
  }
}
