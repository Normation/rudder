/*
*************************************************************************************
* Copyright 2019 Normation SAS
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

package com.normation.rudder.services.reports

import com.normation.cfclerk.domain.TechniqueVersionHelper
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.reports.AggregatedStatusReport
import com.normation.rudder.domain.reports.DirectiveStatusReport
import com.normation.rudder.domain.reports.NodeStatusReport
import com.normation.rudder.domain.reports.OverridenPolicy
import com.normation.rudder.domain.reports.RuleNodeStatusReport
import com.normation.rudder.domain.reports.RuleStatusReport
import com.normation.rudder.domain.reports.RunComplianceInfo
import com.normation.rudder.services.policies.PolicyId

import org.joda.time.DateTime
import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._



@RunWith(classOf[JUnitRunner])
class ReportingServiceUtilsTest extends Specification {

  /*
   * Check that "skipped" are correctly constructed. See
   * https://issues.rudder.io/issues/16280 and parents
   */
  val node1 = NodeId("node1")
  val node2 = NodeId("node2")
  val rule1 = RuleId(RuleUid("rule1"))
  val rule2 = RuleId(RuleUid("rule2"))
  val rule3 = RuleId(RuleUid("rule3"))
  val dir1  = DirectiveId(DirectiveUid("dir1"))
  val dir2  = DirectiveId(DirectiveUid("dir2"))
  val dir3  = DirectiveId(DirectiveUid("dir3"))

  val expiration = new DateTime(0) // not used

  val noOverrides = Nil
  def dirReport(id: DirectiveId) = (id, DirectiveStatusReport(id, Nil))
  def rnReport(nodeId: NodeId, ruleId: RuleId, directives: DirectiveId*) = {
    RuleNodeStatusReport(nodeId, ruleId, None, None, directives.map(dirReport _).toMap, expiration)
  }

  // a case where the same directive is on two rules
  def thisOverrideThatOn(overrider: RuleId, overridden: RuleId, directive: DirectiveId) = {
    OverridenPolicy(
        PolicyId(overridden, directive, TechniqueVersionHelper("1.0")) //this one is
      , PolicyId(overrider , directive, TechniqueVersionHelper("1.0")) //overriden by that one
    )
  }
  // a case where two directive from the same unique technique are on two rules
  def thisOverrideThatOn2(overrider: RuleId, directiver: DirectiveId, overridden: RuleId, directiven: DirectiveId) = {
    OverridenPolicy(
        PolicyId(overridden, directiven, TechniqueVersionHelper("1.0")) //this one is
      , PolicyId(overrider , directiver, TechniqueVersionHelper("1.0")) //overridden by that one
    )
  }


  // a matcher which compare two RuleNodeStatusReporst
  implicit class SameRuleReportMatcher(report1: RuleStatusReport) {
    def isSameReportAs(report2: RuleStatusReport) = {
      (report1.forRule === report2.forRule) and
      (report1.overrides === report2.overrides) and
      (report1.report.isSameReportAs(report2.report))
    }
  }

  // for aggregated status reports, we just compare directive list
  implicit class AggregatedReportMatcher(report1: AggregatedStatusReport) {
    def isSameReportAs(report2: AggregatedStatusReport) = {
      report1.directives.keySet === report2.directives.keySet
    }
  }

  /*
   * rule1/dir1 is applied on node1 and node2 and is both here (node1) and skipped (node2)
   */
  "a rule can not overrides itself" in {
    val reports = List(
      NodeStatusReport(node1, NoRunNoExpectedReport, RunComplianceInfo.OK
        , List()
        , Set(rnReport(node1, rule1, dir1))
      )
    , NodeStatusReport(node2, NoRunNoExpectedReport, RunComplianceInfo.OK
        , List(thisOverrideThatOn(rule2, rule1, dir1))
        , Set()
      )
    ).map(r => (r.nodeId, r)).toMap

    ReportingServiceUtils.buildRuleStatusReport(rule1, reports).isSameReportAs(
      RuleStatusReport(rule1, List(rnReport(node1, rule1, dir1)), noOverrides)
    )
  }

  /*
   * rule1/dir1 on node1 is overriden (and node has nothing) => skipped
   */
  "only overriden leads to skip" in {
    val reports = List(
      NodeStatusReport(node1, NoRunNoExpectedReport, RunComplianceInfo.OK
        , List(thisOverrideThatOn(rule2, rule1, dir1))
        , Set()
      )
    ).map(r => (r.nodeId, r)).toMap

    ReportingServiceUtils.buildRuleStatusReport(rule1, reports).isSameReportAs(
      RuleStatusReport(rule1, List(), List(thisOverrideThatOn(rule2, rule1, dir1)))
    )
  }
  "directives on other rules are not kept in overrides" in {
    val reports = List(
      NodeStatusReport(node1, NoRunNoExpectedReport, RunComplianceInfo.OK
        , List(thisOverrideThatOn(rule2, rule3, dir1))
        , Set()
      )
    ).map(r => (r.nodeId, r)).toMap

    ReportingServiceUtils.buildRuleStatusReport(rule1, reports).isSameReportAs(
      RuleStatusReport(rule1, List(), List())
    )
  }

  /*
   * Two nodes, and two rules, and two directives from the same unique technique:
   * - rule1/dir1 is applied everywhere,
   * - rule2/dir2 is applied only on node2 and override rule1/dir1 on that node
   *
   * => neither rule1 nor rule2 have skipped
   */
  "a rule not overriden on all nodes is not written overriden" in {
    val reports = List(
      NodeStatusReport(node1, NoRunNoExpectedReport, RunComplianceInfo.OK
        , List()
        , Set(rnReport(node1, rule1, dir1))
      )
    , NodeStatusReport(node2, NoRunNoExpectedReport, RunComplianceInfo.OK
        , List(thisOverrideThatOn2(rule2, dir2, rule1, dir1))
        , Set(rnReport(node2, rule2, dir2))
      )
    ).map(r => (r.nodeId, r)).toMap

    ReportingServiceUtils.buildRuleStatusReport(rule1, reports).isSameReportAs(
      RuleStatusReport(rule1, List(rnReport(node1, rule1, dir1)), noOverrides)
    ) and ReportingServiceUtils.buildRuleStatusReport(rule2, reports).isSameReportAs(
      RuleStatusReport(rule2, List(rnReport(node2, rule2, dir2)), noOverrides)
    )
  }

  /*
   * More complexe for one node:
   * - 3 directives: dir1 (most prioritary), dir2 and dir3 (less)
   * - 3 rules: rule1 has dir2, dir3 (skipped),  rule2 has all 3 (so dir1 ok, other skipped), rule3 has all 3 (skipped)
   * There is no ducplication of reports.
   */
  "a rule not overriden on all nodes is not written overriden" in {
    val reports = List(
      NodeStatusReport(node1, NoRunNoExpectedReport, RunComplianceInfo.OK
        , List(
              // on rule1, both dir2 and dir3 are overridden by rule2/dir1
              thisOverrideThatOn2(rule2, dir1, rule1, dir2)
            , thisOverrideThatOn2(rule2, dir1, rule1, dir3)
              // on rule2, both dir2 and dir3 are overridden by rule2/dir1
            , thisOverrideThatOn2(rule2, dir1, rule2, dir2)
            , thisOverrideThatOn2(rule2, dir1, rule2, dir3)
              // on rule3, dir2, dir2 and dir3 are overridden by rule2/dir1
            , thisOverrideThatOn2(rule2, dir1, rule3, dir1)
            , thisOverrideThatOn2(rule2, dir1, rule3, dir2)
            , thisOverrideThatOn2(rule2, dir1, rule3, dir3)
              // and these one are real but should not be kept to avoid having several time the same "skipped"
            , thisOverrideThatOn2(rule1, dir2, rule1, dir3)
            , thisOverrideThatOn2(rule2, dir2, rule2, dir3)
            , thisOverrideThatOn2(rule3, dir1, rule3, dir2)
            , thisOverrideThatOn2(rule3, dir1, rule3, dir3)
            , thisOverrideThatOn2(rule3, dir2, rule3, dir3)
          )
          // only one expected report: dir1 on rule2
        , Set(rnReport(node1, rule2, dir1))
      )
    ).map(r => (r.nodeId, r)).toMap

    ReportingServiceUtils.buildRuleStatusReport(rule1, reports).isSameReportAs(
      RuleStatusReport(rule1, List(), List(thisOverrideThatOn2(rule2, dir1, rule1, dir2), thisOverrideThatOn2(rule2, dir1, rule1, dir3)))
    ) and ReportingServiceUtils.buildRuleStatusReport(rule2, reports).isSameReportAs(
      RuleStatusReport(rule2, List(rnReport(node1, rule2, dir1)), List(thisOverrideThatOn2(rule2, dir1, rule2, dir2), thisOverrideThatOn2(rule2, dir1, rule2, dir3)))
    ) and ReportingServiceUtils.buildRuleStatusReport(rule3, reports).isSameReportAs(
      RuleStatusReport(rule3, List(), List(thisOverrideThatOn2(rule2, dir1, rule3, dir1), thisOverrideThatOn2(rule2, dir1, rule3, dir2), thisOverrideThatOn2(rule2, dir1, rule3, dir3)))
    )
  }

}
