/*
 *************************************************************************************
 * Copyright 2019 Normation SAS
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

package com.normation.cfclerk.xmlparsers

import com.normation.cfclerk.domain.LoadTechniqueError
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersionHelper
import com.normation.cfclerk.services.impl.SystemVariableSpecServiceImpl
import com.normation.inventory.domain.AgentType
import com.normation.utils.XmlSafe
import java.io.FileNotFoundException
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import org.xml.sax.SAXParseException
import scala.xml.*

@RunWith(classOf[JUnitRunner])
class ParseTechniqueTest extends Specification {

  val techniqueParser: TechniqueParser = {
    val varParser = new VariableSpecParser
    new TechniqueParser(varParser, new SectionSpecParser(varParser), new SystemVariableSpecServiceImpl())
  }

  val id: TechniqueId = TechniqueId(TechniqueName("test"), TechniqueVersionHelper("1.0"))

  "When a technique has both root bundles/tmls and a 'cfengine-community' agent block, parsing should fails" >> {
    techniqueParser.parseXml(readFile("parseTechnique/technique_one_default_one_agent_id.xml"), id) must
    beLeft[LoadTechniqueError].like {
      case e =>
        e.fullMsg must =~("these agent configurations are declared several times: 'cfengine-community' ")
    }
  }

  "When a technique has two 'cfengine-community' agent block, parsing should fails" >> {
    techniqueParser.parseXml(readFile("parseTechnique/technique_2_agent_id.xml"), id) must
    beLeft[LoadTechniqueError].like {
      case e =>
        e.fullMsg must =~("these agent configurations are declared several times: 'cfengine-community' ")
    }
  }

  "A technique with an explicit agent block declared and no root bundles/tmls elements must have exactly one agent type (no default one added)" >> {
    val technique = techniqueParser
      .parseXml(readFile("parseTechnique/technique_1_agent_no_default.xml"), id)
      .getOrElse(throw new RuntimeException("That part should not fail"))
    technique.agentConfigs.map(_.agentType) must containTheSameElementsAs(List(AgentType.CfeCommunity))
  }

  private def readFile(fileName: String): Elem = {
    val doc = {
      try {
        XmlSafe.load(ClassLoader.getSystemResourceAsStream(fileName))
      } catch {
        case e: SAXParseException              => throw new Exception("Unexpected issue (unvalid xml?) with " + fileName)
        case e: java.net.MalformedURLException => throw new FileNotFoundException("%s file not found".format(fileName))
      }
    }
    if (doc.isEmpty) {
      throw new Exception("Unexpected issue (unvalid xml?) with the %s file".format(fileName))
    }
    doc
  }

}
