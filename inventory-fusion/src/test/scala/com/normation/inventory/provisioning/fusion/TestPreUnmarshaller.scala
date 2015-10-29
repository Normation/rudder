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
import scala.xml.XML
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import java.io.File
import com.normation.inventory.domain.COMMUNITY_AGENT
import com.normation.inventory.domain.NOVA_AGENT
import com.normation.inventory.domain.Windows
import com.normation.inventory.domain.Windows2012
import com.normation.inventory.services.provisioning.PreUnmarshall
import java.io.InputStream
import org.xml.sax.SAXParseException
import scala.xml.NodeSeq


/**
 * A simple test class to check that the demo data file is up to date
 * with the schema (there may still be a desynchronization if both
 * demo-data, test data and test schema for UnboundID are not synchronized
 * with OpenLDAP Schema).
 */
@RunWith(classOf[JUnitRunner])
class TestPreUnmarshaller extends Specification {

  private[this] implicit class TestParser(pre: PreUnmarshall) {

    def fromXml(checkName:String,is:InputStream) : Box[NodeSeq] = {
    (try {
      Full(XML.load(is))
    } catch {
      case e:SAXParseException => Failure("Cannot parse uploaded file as an XML Fusion Inventory check",Full(e),Empty)
    }) match {
      case f@Failure(m,e,c) => f
      case Full(doc) if(doc.isEmpty) => Failure("Fusion Inventory check seem's to be empty")
      case Empty => Failure("Fusion Inventory check seem's to be empty")
      case Full(x) => Full(x)
    }
  }

    def check(checkRelativePath: String): Box[NodeSeq] = {
      import java.net.URL
      val url = this.getClass.getClassLoader.getResource(checkRelativePath)
      if(null == url) throw new NullPointerException(s"Resource with relative path '${checkRelativePath}' is null (missing resource? Spelling? Permissions?)")
      val is = url.openStream()

      val check = fromXml("check", is) match {
        case Full(e) => pre(e)
        case eb:EmptyBox =>
          val e = eb ?~! "Parsing error"
          e.rootExceptionCause match {
            case Full(ex) => throw new Exception(e.messageChain, ex)
            case _ => throw new Exception(e.messageChain)
          }
      }
      is.close()
      check
    }
  }
  val post = new PostUnmarshallCheckConsistency

  "Post inventory check should be ok" should {
     "With a valid inventory on Linux" in {
        val linux = post.check("fusion-report/signed_inventory.ocs")
        linux.toOption must not beNone
     }


    "With a valid inventory in Windows" in {
      val windows = post.check("fusion-report/WIN-AI8CLNPLOV5-2014-06-20-18-15-49.ocs")
      windows.toOption must not beNone
    }
  }

  "Post inventory check should fail" should {
     "When there is no fqdn defined" in {
        val noFQDN = post.check("fusion-report/centos.no_rudder_extension.no_fqdn")
        noFQDN.toOption must beNone
     }
  }
}
