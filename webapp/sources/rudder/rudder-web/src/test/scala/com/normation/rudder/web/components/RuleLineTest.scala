/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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

package com.normation.rudder.web.components

import com.normation.rudder.MockGlobalParam
import com.normation.rudder.MockNodeGroups
import com.normation.rudder.MockNodes
import com.normation.rudder.MockRules
import com.normation.rudder.domain.policies.*
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.web.components.RuleGrid.*
import net.liftweb.http.SHtml
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class RuleLineTest extends Specification {

  val mockRules  = new MockRules()
  val mockNodes  = new MockNodes
  val mockGroups = new MockNodeGroups(mockNodes, new MockGlobalParam)

  import com.normation.utils.SimpleStatus.*

  val callback: Rule => Option[AnonFunc] = (r: Rule) => {
    val ajax = SHtml.makeAjaxCall(JsRaw("'" + "cb" + "=' + encodeURIComponent(" + RedirectTo(r.id.serialize).toJsCmd + ")"))
    Some(AnonFunc("action", ajax))
  }

  def catName(id: RuleCategoryId): String = id.value match {
    case "rootRuleCategory" => "root rule category"
    case _                  => id.value
  }

  "OKLine serialisation" >> {
    val lines = List(
      OKLine(
        mockRules.rules.rpmRule,
        "enabled",
        "the mode is enabled",
        nodeIsEmpty = false,
        FullyApplied,
        Seq(DirectiveStatus("vim", Enabled, Enabled)),
        Set(mockGroups.groupsTargetInfos(2).toTargetInfo)
      )
    )

    RuleGrid.getRulesData(lines, None, callback, catName).toJson.toJsCmd.replaceAll("\n", "") ===
    """[{"name": "50. Deploy PLOP STACK", "id": "rule2", "description": "global config for all nodes",
      | "applying": false, "category": "root rule category", "status": "In application", "trClass": "",
      | "policyMode": "enabled", "explanation": "the mode is enabled", "tags": "{}", "tagsDisplayed": [],
      | "callback": function(action) {lift.ajax('cb=' + encodeURIComponent(window.location = "rule2";), null, null, null);}
      |}]""".stripMargin.replaceAll("\n", "")
  }

  "Error Line serialisation" >> {
    val lines = List(ErrorLine(mockRules.rules.rpmRule, "enabled", "the mode is enabled", nodeIsEmpty = false))

    RuleGrid.getRulesData(lines, None, callback, catName).toJson.toJsCmd.replaceAll("\n", "") ===
    """[{"name": "50. Deploy PLOP STACK", "id": "rule2", "description": "global config for all nodes",
      | "applying": false, "category": "root rule category", "status": "N/A", "trClass": " error",
      | "policyMode": "enabled", "explanation": "the mode is enabled", "tags": "{}", "tagsDisplayed": [],
      | "callback": function(action) {lift.ajax('cb=' + encodeURIComponent(window.location = "rule2";), null, null, null);}
      |}]""".stripMargin.replaceAll("\n", "")
  }

  /*
   * The category is a path of user-provided category names, and it is the one field that
   * DataTables assigns with `td.innerHTML` (no fnCreatedCell), so it must go out HTML-escaped.
   * The others must go out verbatim, because their sinks build text nodes: escaping them here
   * would double-encode them, and escapeHtml4 also entity-encodes non-ASCII, which would turn
   * every accented rule name into `&eacute;`-style noise. See RuleLine.json for the full mapping.
   */
  "the category is HTML-escaped, and the fields rendered as text nodes are not" >> {
    val payload = "<script>alert(1)</script>"
    val rule    = Rule(
      RuleId(RuleUid("rule-xss")),
      name = payload,
      categoryId = RuleCategoryId("cat-xss"),
      shortDescription = payload,
      security = None
    )
    val lines   = List(ErrorLine(rule, "enabled", payload, nodeIsEmpty = false))

    RuleGrid.getRulesData(lines, None, callback, _ => payload).toJson.toJsCmd.replaceAll("\n", "") ===
    """[{"name": "<script>alert(1)</script>", "id": "rule-xss", "description": "<script>alert(1)</script>",
      | "applying": false, "category": "&lt;script&gt;alert(1)&lt;/script&gt;", "status": "N/A", "trClass": " error",
      | "policyMode": "enabled", "explanation": "<script>alert(1)</script>", "tags": "{}", "tagsDisplayed": [],
      | "callback": function(action) {lift.ajax('cb=' + encodeURIComponent(window.location = "rule-xss";), null, null, null);}
      |}]""".stripMargin.replaceAll("\n", "")
  }
}
