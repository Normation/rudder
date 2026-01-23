/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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

package com.normation.rudder.web.services

import com.normation.GitVersion
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.PolicyModeOverrides
import net.liftweb.common.Loggable
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ComputePolicyModeTest extends Specification with Loggable {
  def directive(id: String, policyMode: Option[PolicyMode]): Directive = {
    Directive(
      DirectiveId(DirectiveUid(id), GitVersion.DEFAULT_REV),
      TechniqueVersion.parse("1.0").getOrElse(throw new IllegalArgumentException(s"Bad technique version")),
      Map(),
      s"directive ${id}",
      "",
      policyMode,
      "",
      security = None
    )
  }

  val audit1: Directive = directive("audit1", Some(PolicyMode.Audit))

  "When everything is default" >> {
    "Empty everything leads to default" in {
      ComputePolicyMode
        .ruleMode(GlobalPolicyMode(PolicyMode.Enforce, PolicyModeOverrides.Always), Set(), Set())
        .name must beEqualTo("enforce")
    }
    "Directive in audit and no node leads to audit" in {
      ComputePolicyMode
        .ruleMode(GlobalPolicyMode(PolicyMode.Enforce, PolicyModeOverrides.Always), Set(audit1), Set())
        .name must beEqualTo("audit")
    }
    "Directive in audit and node in default leads to audit" in {
      ComputePolicyMode
        .ruleMode(GlobalPolicyMode(PolicyMode.Enforce, PolicyModeOverrides.Always), Set(audit1), Set(None))
        .name must beEqualTo("audit")
    }
  }

}
