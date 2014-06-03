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

package com.normation.inventory.provisioning.fusion

import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import com.normation.utils.StringUuidGeneratorImpl
import net.liftweb.common._
import com.normation.inventory.domain.InventoryReport
import com.normation.inventory.domain.ServerRole


/**
 * A simple test class to check that the demo data file is up to date
 * with the schema (there may still be a desynchronization if both
 * demo-data, test data and test schema for UnboundID are not synchronized
 * with OpenLDAP Schema).
 */
@RunWith(classOf[JUnitRunner])
class TestReportParsing extends Specification {




  private[this] implicit class TestParser(parser: FusionReportUnmarshaller) {
    def parse(reportRelativePath: String): InventoryReport = {
      import java.net.URL
      val url = this.getClass.getClassLoader.getResource(reportRelativePath)
      if(null == url) throw new NullPointerException(s"Resource with relative path '${reportRelativePath}' is null (missing resource? Spelling? Permissions?)")

      val is = url.openStream()

      val report = parser.fromXml("report", is) match {
        case Full(e) => e
        case eb:EmptyBox =>
          val e = eb ?~! "Parsing error"
          e.rootExceptionCause match {
            case Full(ex) => throw new Exception(e.messageChain, ex)
            case _ => throw new Exception(e.messageChain)
          }
      }
      is.close()
      report
    }
  }


  "Machine with two ips for one interfaces" should {

    val parser = new FusionReportUnmarshaller(
        new StringUuidGeneratorImpl()
    )
    val report = parser.parse("fusion-report/centos-with-two-ip-for-one-interface.ocs")

    "lead to a node with two ips for eth0" in {
      report.node.networks.find( _.name == "eth0").get.ifAddresses.size must beEqualTo(2)
    }
  }

  "A node with Rudder roles" should {

    val parser = new FusionReportUnmarshaller(
        new StringUuidGeneratorImpl
      , rootParsingExtensions = RudderServerRoleParsing ::Nil
    )
    val report = parser.parse("fusion-report/node-with-server-role-attribute.ocs")

    "correctly add roles"in {
      report.node.serverRoles must contain(exactly(ServerRole("magikal_node")))
    }
  }

}
