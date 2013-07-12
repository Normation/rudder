/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.migration

import scala.xml.Elem
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import com.normation.utils.XmlUtils
import Migration_2_DATA_Directive.directive_add_2
import Migration_2_DATA_Directive.directive_delete_2
import Migration_2_DATA_Directive.directive_modify_2
import Migration_2_DATA_Group.nodeGroup_add_2
import Migration_2_DATA_Group.nodeGroup_delete_2
import Migration_2_DATA_Group.nodeGroup_modify_2
import Migration_2_DATA_Other.addPendingDeployment_2
import Migration_2_DATA_Other.node_accept_2
import Migration_2_DATA_Rule.rule_add_2
import Migration_2_DATA_Rule.rule_delete_2
import Migration_2_DATA_Rule.rule_modify_2
import Migration_3_DATA_Directive.directive_add_3
import Migration_3_DATA_Directive.directive_delete_3
import Migration_3_DATA_Directive.directive_modify_3
import Migration_3_DATA_Group.nodeGroup_add_3
import Migration_3_DATA_Group.nodeGroup_delete_3
import Migration_3_DATA_Group.nodeGroup_modify_3
import Migration_3_DATA_Other.addPendingDeployment_3
import Migration_3_DATA_Other.node_accept_3
import Migration_3_DATA_Rule.rule_add_3
import Migration_3_DATA_Rule.rule_delete_3
import Migration_3_DATA_Rule.rule_modify_3
import net.liftweb.common.Box
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import org.specs2.runner.JUnitRunner


/**
 * Test individual event log data migration
 */
@RunWith(classOf[JUnitRunner])
class TestXmlMigration_2_3 extends Specification with Loggable {

  val migration = new XmlMigration_2_3

  def compare(b:Box[Elem], e:Elem) = {
    val Full(x) = b
    XmlUtils.trim(x) must beEqualTo(XmlUtils.trim(e))
  }

  "rule migration from fileFormat '2' to '3'" should {
    "correctly rewrite add" in {
      compare(migration.rule(rule_add_2) , rule_add_3)
    }
    "correctly rewrite modify" in {
      compare(migration.rule(rule_modify_2), rule_modify_3)
    }
    "correctly rewrite delete" in {
      compare(migration.rule(rule_delete_2), rule_delete_3)
    }
  }

  "directive migration from fileFormat '2' to '3'" should {
    "correctly rewrite add" in {
      compare(migration.other(directive_add_2), directive_add_3)
    }
    "correctly rewrite modify" in {
      compare(migration.other(directive_modify_2), directive_modify_3)
    }
    "correctly rewrite delete" in {
      compare(migration.other(directive_delete_2), directive_delete_3)
    }
  }

  "nodeGroup migration from fileFormat '2' to '3'" should {
    "correctly rewrite add" in {
      compare(migration.other(nodeGroup_add_2), nodeGroup_add_3)
    }
    "correctly rewrite modify" in {
      compare(migration.other(nodeGroup_modify_2), nodeGroup_modify_3)
    }
    "correctly rewrite delete" in {
      compare(migration.other(nodeGroup_delete_2), nodeGroup_delete_3)
    }
  }

  "other migration from fileFormat '2' to '3'" should {
    "correctly rewrite 'add deployment status'" in {
      compare(migration.other(addPendingDeployment_2), addPendingDeployment_3)
    }

// introduced in 2.4 ?
//    "correctly rewrite pending deployment status" in {
//      migration.deploymentStatus(deploymentStatus_10) must beEqualTo(Full(deploymentStatus_2))
//    }

    "correctly rewrite node acceptation status" in {
      compare(migration.other(node_accept_2), node_accept_3)
    }
  }
}

