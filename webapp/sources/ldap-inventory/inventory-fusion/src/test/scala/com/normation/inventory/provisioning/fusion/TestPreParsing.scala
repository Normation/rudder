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

package com.normation.inventory.provisioning.fusion

import com.normation.errors.*
import com.normation.inventory.services.provisioning.PreInventoryParser
import com.normation.utils.XmlSafe
import com.normation.zio.*
import java.io.InputStream
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import scala.xml.NodeSeq
import zio.*

/**
 * A simple test class to check that the demo data file is up to date
 * with the schema (there may still be a desynchronization if both
 * demo-data, test data and test schema for UnboundID are not synchronized
 * with OpenLDAP Schema).
 */
@RunWith(classOf[JUnitRunner])
class TestPreParsing extends Specification {

  implicit private class TestParser(pre: PreInventoryParser) {

    def fromXml(checkName: String, is: InputStream): IOResult[NodeSeq] = {
      ZIO.attempt(XmlSafe.load(is)).mapError(SystemError("error in test", _))
    }

    def check(checkRelativePath: String): Either[RudderError, NodeSeq] = {
      (ZIO.acquireReleaseWith {
        IOResult.attempt {
          val url = this.getClass.getClassLoader.getResource(checkRelativePath)
          if (null == url) {
            throw new NullPointerException(
              s"Resource with relative path '${checkRelativePath}' is null (missing resource? Spelling? Permissions?)"
            )
          }
          url.openStream()
        }
      }(is => effectUioUnit(is.close))(is => fromXml("check", is).flatMap[Any, RudderError, NodeSeq](pre.apply))).either.runNow
    }
  }
  val post = new PreInventoryParserCheckConsistency

  "Pre inventory check should be ok" should {
    "With a valid inventory on Linux" in {
      val linux = post.check("fusion-inventories/signed_inventory.ocs")
      linux must beRight
    }

    "With a valid inventory in Windows" in {
      val windows = post.check("fusion-inventories/WIN-AI8CLNPLOV5-2014-06-20-18-15-49.ocs")
      windows must beRight
    }
  }

  "Pre inventory check should fail" should {
    "When there is no fqdn defined" in {
      val noFQDN = post.check("fusion-inventories/centos.no_rudder_extension.no_fqdn")
      noFQDN must beLeft
    }

    "When there is no security token defined" in {
      val noSecurityToken = post.check("fusion-inventories/rudder-tag/debian-no-security-token.ocs")
      noSecurityToken.swap.getOrElse(throw new Exception("For test")).fullMsg must beMatching(
        """.*\QMissing security token attribute (RUDDER/AGENT/AGENT_CERT)\E.*""".r
      )
    }
  }

  "An inventory without OS/NAME but with KERNEL_NAME should work" in {
    val inventories = post.check("fusion-inventories/only-kernel-name-0034fbbe-4b52-4212-9535-1f1a952c6f36.ocs")
    inventories must beRight
  }

  "AIX version should be correctly set in OPERATINSYSTEM" in {
    val aix = post
      .check("fusion-inventories/sovma136-2014-02-10-07-13-43.ocs")
      .map(xml => (xml \\ "OPERATINGSYSTEM" \ "KERNEL_VERSION").text)
    aix must beRight[String](===("5300-12"))
  }
}
