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

package com.normation.inventory.provisioning
package fusion

import org.junit._
import org.junit.Assert._
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import com.normation.utils.StringUuidGeneratorImpl
import scala.xml.XML
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import java.io.File

@RunWith(classOf[BlockJUnit4ClassRunner])
class TestReportParsing {

  val parser = new FusionReportUnmarshaller(
      new StringUuidGeneratorImpl()
  )



  @Test
  def testTwoIpsForEth0() {

    val is = this.getClass.getClassLoader.getResourceAsStream("fusion-report/centos-with-two-ip-for-one-interface.ocs")
    assertNotNull(is)

    val report = parser.fromXml("report", is) match {
      case Full(e) => e
      case eb:EmptyBox =>
        val e = eb ?~! "Parsing error"
        e.rootExceptionCause match {
          case Full(ex) => throw new Exception(e.messageChain, ex)
          case _ => throw new Exception(e.messageChain)
        }
    }

    assertTrue("We should have two IPs for eth0",
      report.node.networks.find( _.name == "eth0").get.ifAddresses.size == 2
    )

  }

}
