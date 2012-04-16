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

import net.liftweb.common._
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import Migration_10_2_DATA_Other._
import Migration_10_2_DATA_Group._
import Migration_10_2_DATA_Directive._
import Migration_10_2_DATA_Rule._
import scala.xml.Elem
import com.normation.utils.XmlUtils


/**
 * Test individual event log data migration
 */
@RunWith(classOf[JUnitRunner])
class TestXmlMigration_10_2 extends Specification with Loggable {
  
  val migration = new XmlMigration_10_2
  
  def compare(b:Box[Elem], e:Elem) = {
    val Full(x) = b
    XmlUtils.trim(x) must beEqualTo(XmlUtils.trim(e))
  }
    
  "rule migration from fileFormat '1.0' to '2'" should {
    "correctly rewrite add" in {
      compare(migration.rule(rule_add_10) , rule_add_2)
    }
    "correctly rewrite modify" in {
      compare(migration.rule(rule_modify_10), rule_modify_2)
    }
    "correctly rewrite delete" in {
      compare(migration.rule(rule_delete_10), rule_delete_2)
    }
  }
    
  "directive migration from fileFormat '1.0' to '2'" should {
    "correctly rewrite add" in {
      compare(migration.directive(directive_add_10), directive_add_2)
    }
    "correctly rewrite modify" in {
      compare(migration.directive(directive_modify_10), directive_modify_2)
    }
    "correctly rewrite delete" in {
      compare(migration.directive(directive_delete_10), directive_delete_2)
    }
  }
  
  "nodeGroup migration from fileFormat '1.0' to '2'" should {
    "correctly rewrite add" in {
      compare(migration.nodeGroup(nodeGroup_add_10), nodeGroup_add_2)
    }
    "correctly rewrite modify" in {
      compare(migration.nodeGroup(nodeGroup_modify_10), nodeGroup_modify_2)
    }
    "correctly rewrite delete" in {
      compare(migration.nodeGroup(nodeGroup_delete_10), nodeGroup_delete_2)
    }
  }
  
  "other migration from fileFormat '1.0' to '2'" should {
    "correctly rewrite 'add deployment status'" in {
      compare(migration.addPendingDeployment(addPendingDeployment_10), addPendingDeployment_2)
    }
 
// introduced in 2.4 ?
//    "correctly rewrite pending deployment status" in {
//      migration.deploymentStatus(deploymentStatus_10) must beEqualTo(Full(deploymentStatus_2))      
//    }
    
    "correctly rewrite node acceptation status" in {
      compare(migration.node(node_accept_10), node_accept_2)
    }
  }
}
