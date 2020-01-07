/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.rudder.migration

import scala.xml.Elem



import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import Migration_5_DATA_ChangeRequest._
import Migration_5_DATA_Rule._
import Migration_6_DATA_ChangeRequest._
import Migration_6_DATA_Rule._
import net.liftweb.common.Box
import net.liftweb.common.Full
import net.liftweb.common.Loggable


/**
 * Test individual event log data migration
 */
@RunWith(classOf[JUnitRunner])
class TestXmlMigration extends Specification with Loggable {

  val migration = XmlMigration_5_6

  def compare(b:Box[Elem], e:Elem) = {
    val x = b match {
      case Full(x) => x
      case _ => throw new IllegalArgumentException("bad test")
    }
    scala.xml.Utility.trim(x) must beEqualTo(scala.xml.Utility.trim(e))
  }

  "rule migration from fileFormat '5' to '6'" should {
    "correctly rewrite add" in {
      compare(XmlMigration_5_6.other(rule_add_5) , rule_add_6)
    }
    "correctly rewrite modify" in {
      compare(XmlMigration_5_6.other(rule_modify_5), rule_modify_6)
    }
    "correctly rewrite delete" in {
      compare(XmlMigration_5_6.other(rule_delete_5), rule_delete_6)
    }
  }

  "change request migration from fileFormat '5' to '6'" should {
    "correctly rewrite add" in {
      compare(XmlMigration_5_6.changeRequest(cr_rule_change_5) , cr_rule_change_6)
    }
  }
}

