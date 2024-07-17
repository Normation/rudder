/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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

package com.normation.inventory.domain

import com.normation.errors.IOResult
import com.normation.inventory.domain.InventoryError.Deserialisation
import com.normation.inventory.services.provisioning.XmlInventoryParser
import com.normation.zio.*
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import scala.xml.NodeSeq
import zio.syntax.*

/**
 * Test properties about agent type, especially regarding serialisation
 */
@RunWith(classOf[JUnitRunner])
class XmlParsingTest extends Specification {

  val badXml = {
    """<?xml version="1.0" encoding="UTF-8" ?>
      |<!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
      |<REQUEST>
      |  <DEVICEID>agent-linux-2024-07-02-16-10-52</DEVICEID>
      |  <QUERY>INVENTORY</QUERY>
      |</REQUEST>""".stripMargin
  }

  val goodXml = {
    """<?xml version="1.0" encoding="UTF-8" ?>
      |<REQUEST>
      |  <DEVICEID>agent-linux-2024-07-02-16-10-52</DEVICEID>
      |  <QUERY>INVENTORY</QUERY>
      |</REQUEST>""".stripMargin
  }

  val xmlInventoryParser: XmlInventoryParser = new XmlInventoryParser {
    override def fromXmlDoc(inventoryName: String, xml: NodeSeq): IOResult[Inventory] = {
      Inventory(null, null, null, null, null, null, null, null).succeed
    }
  }

  "Parsing simple inventory should succeed" >> {
    xmlInventoryParser
      .fromXml("good inventory", new ByteArrayInputStream(goodXml.getBytes(StandardCharsets.UTF_8)))
      .either
      .runNow must beRight
  }

  "Parsing inventory with dynamic dtd should fail" >> {
    xmlInventoryParser
      .fromXml("bad inventory", new ByteArrayInputStream(badXml.getBytes(StandardCharsets.UTF_8)))
      .either
      .runNow must beLeft(beAnInstanceOf[Deserialisation])
  }
}
