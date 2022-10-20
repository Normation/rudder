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
import com.normation.rudder.rest.RestTest
import com.normation.rudder.rest.RestTestSetUp
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.rule.category.RuleCategoryId
import net.liftweb.common.Loggable
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class RuleTest extends Specification with Loggable {

  val restTestSetUp = RestTestSetUp.newEnv
  val restTest      = new RestTest(restTestSetUp.liftRules)

  val cat1   = RuleCategory(RuleCategoryId("cat1"), "", "", List.empty)
  val cat4   = RuleCategory(
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
  val subCat = {
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

  val root = RuleCategory(
    RuleCategoryId("rootRuleCategory"),
    "Root category",
    "base root category",
    subCat,
    true
  )

  "Testing rule utility tools" should {
    "List all categories and subcategories" in {
      val listCatIds = Set(
        RuleCategoryId("rootRuleCategory"),
        RuleCategoryId("cat1"),
        RuleCategoryId("cat2"),
        RuleCategoryId("cat3"),
        RuleCategoryId("cat4"),
        RuleCategoryId("subcat4"),
        RuleCategoryId("subsubcat4")
      )
      restTestSetUp.ruleApiService14.listCategoriesId(root) shouldEqual (listCatIds)
    }

    "List all categories and subcategories without root" in {
      val listCatIds = Set(
        RuleCategoryId("cat4"),
        RuleCategoryId("subcat4"),
        RuleCategoryId("subsubcat4")
      )
      restTestSetUp.ruleApiService14.listCategoriesId(cat4) shouldEqual (listCatIds)
    }

    "List only root category" in {
      val listCatIds = Set(RuleCategoryId("rootRuleCategory"))
      restTestSetUp.ruleApiService14.listCategoriesId(root.copy(childs = List.empty)) shouldEqual (listCatIds)
    }

    "Find missing categories" in {
      val rules = List(
        Rule(RuleId(RuleUid("rule1")), "", RuleCategoryId("missing-cat1")),
        Rule(RuleId(RuleUid("rule2")), "", RuleCategoryId("missing-cat2")),
        Rule(RuleId(RuleUid("rule3")), "", RuleCategoryId("cat3")),
        Rule(RuleId(RuleUid("rule4")), "", RuleCategoryId("cat4")),
        Rule(RuleId(RuleUid("rule5")), "", RuleCategoryId("cat1"))
      )
      val cat1  = RuleCategory(
        RuleCategoryId("missing-cat1"),
        "<missing-cat1>",
        s"Category missing-cat1 has been deleted, please move rules to available categories",
        List(),
        false
      )
      val cat2  = RuleCategory(
        RuleCategoryId("missing-cat2"),
        "<missing-cat2>",
        s"Category missing-cat2 has been deleted, please move rules to available categories",
        List(),
        false
      )
      restTestSetUp.ruleApiService14.getMissingCategories(root, rules) shouldEqual (Set(cat1, cat2))
    }

    "Find no missing categories" in {
      val rules = List(
        Rule(RuleId(RuleUid("rule1")), "", RuleCategoryId("rootRuleCategory")),
        Rule(RuleId(RuleUid("rule2")), "", RuleCategoryId("cat1")),
        Rule(RuleId(RuleUid("rule3")), "", RuleCategoryId("cat3")),
        Rule(RuleId(RuleUid("rule4")), "", RuleCategoryId("cat4")),
        Rule(RuleId(RuleUid("rule5")), "", RuleCategoryId("cat1")),
        Rule(RuleId(RuleUid("rule6")), "", RuleCategoryId("subcat4")),
        Rule(RuleId(RuleUid("rule7")), "", RuleCategoryId("subsubcat4"))
      )
      restTestSetUp.ruleApiService14.getMissingCategories(root, rules) shouldEqual (Set.empty)
    }
  }
}
