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

import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._

import scala.xml.XML
import com.normation.inventory.services.provisioning.PreUnmarshall
import java.io.InputStream

import com.normation.errors.RudderError
import com.normation.errors.RudderResult
import com.normation.errors.SystemError
import com.normation.zio.ZioRuntime

import scala.xml.NodeSeq
import scalaz.zio._
import scalaz.zio.syntax._

/**
 * A simple test class to check that the demo data file is up to date
 * with the schema (there may still be a desynchronization if both
 * demo-data, test data and test schema for UnboundID are not synchronized
 * with OpenLDAP Schema).
 */
@RunWith(classOf[JUnitRunner])
class TestPreUnmarshaller extends Specification {

  private[this] implicit class TestParser(pre: PreUnmarshall) {

    def fromXml(checkName:String,is:InputStream) : RudderResult[NodeSeq] = {
      Task.effect(XML.load(is)).mapError(SystemError("error in test", _))
    }

    def check(checkRelativePath: String): Either[RudderError, NodeSeq] = {
      ZioRuntime.unsafeRun((ZIO.bracket {
        Task.effect {
          val url = this.getClass.getClassLoader.getResource(checkRelativePath)
          if(null == url) throw new NullPointerException(s"Resource with relative path '${checkRelativePath}' is null (missing resource? Spelling? Permissions?)")
          url.openStream()
        }.mapError(e => SystemError("error in text", e))
      } { is =>
       Task.effect(is.close).run
      } {
        is => fromXml("check", is).flatMap(pre.apply)
      }).either)
    }
  }
  val post = new PreUnmarshallCheckConsistency

  "Pre inventory check should be ok" should {
     "With a valid inventory on Linux" in {
        val linux = post.check("fusion-report/signed_inventory.ocs")
        linux.toOption must not beNone
     }

    "With a valid inventory in Windows" in {
      val windows = post.check("fusion-report/WIN-AI8CLNPLOV5-2014-06-20-18-15-49.ocs")
      windows.toOption must not beNone
    }
  }

  "Pre inventory check should fail" should {
     "When there is no fqdn defined" in {
        val noFQDN = post.check("fusion-report/centos.no_rudder_extension.no_fqdn")
        noFQDN.toOption must beNone
     }

    "When there is no security token defined" in {
      val noSecurityToken = post.check("fusion-report/debian-no-security-token.ocs")
      noSecurityToken.swap.getOrElse(throw new Exception("For test")).fullMsg must beMatching(""".*\QMissing security token attribute (RUDDER/AGENT/CFENGINE_KEY or RUDDER/AGENT/AGENT_CERT)\E.*""".r)
    }
  }

  "A report without OS/NAME but with KERNEL_NAME" should { "work" in {
    val report = post.check("fusion-report/only-kernel-name-0034fbbe-4b52-4212-9535-1f1a952c6f36.ocs")
    report.toOption must not beNone
  } }

}
