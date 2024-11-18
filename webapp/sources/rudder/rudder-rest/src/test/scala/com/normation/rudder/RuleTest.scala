/*
 *************************************************************************************
 * Copyright 2022 Normation SAS
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

package com.normation.rudder

import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.rest.RestTestSetUp2
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.rule.category.RuleCategoryId
import net.liftweb.common.Loggable
import zio.Scope
import zio.ZIO
import zio.ZLayer
import zio.test.*
import zio.test.Assertion.*
import zio.test.ZIOSpecDefault

object RuleTest extends ZIOSpecDefault with Loggable {

  val cat1:   RuleCategory       = RuleCategory(RuleCategoryId("cat1"), "", "", List.empty)
  val cat4:   RuleCategory       = RuleCategory(
    RuleCategoryId("cat4"),
    "",
    "",
    List(
      RuleCategory(
        RuleCategoryId("subcat4"),
        "",
        "",
        List(
          RuleCategory(RuleCategoryId("subsubcat4"), "", "", List.empty)
        )
      )
    )
  )
  val subCat: List[RuleCategory] = {
    List(
      cat1,
      RuleCategory(
        RuleCategoryId("cat2"),
        "",
        "",
        List(
          RuleCategory(RuleCategoryId("cat3"), "", "", List.empty),
          cat4
        )
      )
    )
  }

  val root: RuleCategory = RuleCategory(
    RuleCategoryId("rootRuleCategory"),
    name = "Root category",
    description = "base root category",
    childs = subCat,
    isSystem = true
  )

  val spec: Spec[TestEnvironment & Scope, Throwable] = (suite("Testing rule utility tools")(
    test("List all categories and subcategories") {
      val listCatIds = Set(
        RuleCategoryId("rootRuleCategory"),
        RuleCategoryId("cat1"),
        RuleCategoryId("cat2"),
        RuleCategoryId("cat3"),
        RuleCategoryId("cat4"),
        RuleCategoryId("subcat4"),
        RuleCategoryId("subsubcat4")
      )
      for {
        restTestSetUp <- ZIO.service[RestTestSetUp2]
        actual         = restTestSetUp.ruleApiService14.listCategoriesId(root)
      } yield assert(actual)(equalTo(listCatIds))
    },
    test("List all categories and subcategories without root") {
      val listCatIds = Set(
        RuleCategoryId("cat4"),
        RuleCategoryId("subcat4"),
        RuleCategoryId("subsubcat4")
      )
      for {
        restTestSetUp <- ZIO.service[RestTestSetUp2]
        actual         = restTestSetUp.ruleApiService14.listCategoriesId(cat4)
      } yield assert(actual)(equalTo(listCatIds))
    },
    test("List only root category") {
      val listCatIds = Set(RuleCategoryId("rootRuleCategory"))
      for {
        restTestSetUp <- ZIO.service[RestTestSetUp2]
        actual         = restTestSetUp.ruleApiService14.listCategoriesId(root.copy(childs = List.empty))
      } yield assert(actual)(equalTo(listCatIds))
    },
    test("Find missing categories") {
      val rules = List(
        Rule(RuleId(RuleUid("rule1")), "", RuleCategoryId("missing-cat1")),
        Rule(RuleId(RuleUid("rule2")), "", RuleCategoryId("missing-cat2")),
        Rule(RuleId(RuleUid("rule3")), "", RuleCategoryId("cat3")),
        Rule(RuleId(RuleUid("rule4")), "", RuleCategoryId("cat4")),
        Rule(RuleId(RuleUid("rule5")), "", RuleCategoryId("cat1"))
      )
      val cat1  = RuleCategory(
        RuleCategoryId("missing-cat1"),
        name = "<missing-cat1>",
        description = s"Category missing-cat1 has been deleted, please move rules to available categories",
        childs = List(),
        isSystem = false
      )
      val cat2  = RuleCategory(
        RuleCategoryId("missing-cat2"),
        name = "<missing-cat2>",
        description = s"Category missing-cat2 has been deleted, please move rules to available categories",
        childs = List(),
        isSystem = false
      )
      for {
        restTestSetUp <- ZIO.service[RestTestSetUp2]
        actual         = restTestSetUp.ruleApiService14.getMissingCategories(root, rules)
      } yield assert(actual)(equalTo(Set(cat1, cat2)))
    },
    test("Find no missing categories") {
      val rules = List(
        Rule(RuleId(RuleUid("rule1")), "", RuleCategoryId("rootRuleCategory")),
        Rule(RuleId(RuleUid("rule2")), "", RuleCategoryId("cat1")),
        Rule(RuleId(RuleUid("rule3")), "", RuleCategoryId("cat3")),
        Rule(RuleId(RuleUid("rule4")), "", RuleCategoryId("cat4")),
        Rule(RuleId(RuleUid("rule5")), "", RuleCategoryId("cat1")),
        Rule(RuleId(RuleUid("rule6")), "", RuleCategoryId("subcat4")),
        Rule(RuleId(RuleUid("rule7")), "", RuleCategoryId("subsubcat4"))
      )
      for {
        restTestSetUp <- ZIO.service[RestTestSetUp2]
        actual         = restTestSetUp.ruleApiService14.getMissingCategories(root, rules)
      } yield assert(actual)(isEmpty)
    }
  ).provideSome[Scope](ZLayer.succeed(logger), RestTestSetUp2.layer)) @@ TestAspect.sequential

}
