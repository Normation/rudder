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

package com.normation.cfclerk.domain

import java.io.FileNotFoundException
import org.junit._
import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import org.xml.sax.SAXParseException
import scala.xml._
import com.normation.cfclerk.xmlparsers._
import CfclerkXmlConstants._
import scala.xml._
import net.liftweb.common._
import com.normation.utils.XmlUtils._
import org.joda.time.{ Days => _, _ }
import org.joda.time.format._
import org.slf4j.{ Logger, LoggerFactory }
import com.normation.cfclerk.exceptions.ParsingException
import com.normation.cfclerk.services.impl.SystemVariableSpecServiceImpl

@RunWith(classOf[JUnitRunner])
class TechniqueTest extends Specification {
  val doc = readFile("testTechnique.xml")

  val techniqueParser = {
    val varParser = new VariableSpecParser
    new TechniqueParser(varParser, new SectionSpecParser(varParser), new SystemVariableSpecServiceImpl())
  }

  val id = TechniqueId(TechniqueName("foo"), TechniqueVersion("1.0"))

  val technique = techniqueParser.parseXml(doc, id, true)


  "Compatible OS and Agents" should {
    val xml = <COMPATIBLE><OS>debian-5</OS><OS>windowsXP</OS><AGENT version=">= 3.5">cfengine-community</AGENT></COMPATIBLE>
    val os = List(OperatingSystem("debian-5"), OperatingSystem("windowsXP"))
    val agents = List(Agent("cfengine-community", ">= 3.5"))
    val compatible = Compatible(os, agents)
    "works so that parsing : " + xml + " yields " + compatible.toString in {
      techniqueParser.parseCompatibleTag(xml) === compatible
    }
  }

  "The technique described" should {

    "have name 'Test technique'" in {
      technique.name === "Test technique"
    }

    "have description 'A test technique'" in {
      technique.description === "A test technique"
    }

    "be a system technique" in {
      technique.isSystem === true
    }

    "not be multiinstance" in {
      technique.isMultiInstance === false
    }

    "provides a template file for its expected reports" in {
      technique.providesExpectedReports == true
    }

    "have bundle list: 'bundle1,bundle2'" in {
      technique.bundlesequence.map( _.name ) === Seq("bundle1","bundle2")
    }

    "have templates 'tml1, tml2, tml3'" in {
      technique.templates.map( _.id.name ) === Seq("tml1", "tml2", "tml3")
    }

    "'tml1' is included and has default outpath" in {
      val tml = technique.templatesMap(TechniqueResourceIdByName(id,"tml1"))
      tml.included === true and tml.outPath === "foo/1.0/tml1.cf"
    }

    "'tml2' is included and has tml2.bar outpath" in {
      val tml = technique.templatesMap(TechniqueResourceIdByName(id,"tml2"))
      tml.included === true and tml.outPath === "tml2.bar"
    }

    "'tml3' is not included and has default outpath" in {
      val tml = technique.templatesMap(TechniqueResourceIdByName(id,"tml3"))
      tml.included === false and tml.outPath === "foo/1.0/tml3.cf"
    }

    "system vars are 'BUNDLELIST,COMMUNITY'" in {
      technique.systemVariableSpecs.map( _.name ) === Set("BUNDLELIST","COMMUNITY")
    }

    "tracking variable is bound to A" in {
      technique.trackerVariableSpec.boundingVariable === Some("A")
    }

    "section 'common' is monovalued, not a componed, and has a variable A" in {
      val section = technique.rootSection.getAllSections.find( _.name == "common").get
      val varA = section.children.head.asInstanceOf[InputVariableSpec]
      section.isComponent === false and section.isMultivalued === false and
      section.children.size === 1 and varA.name === "A"
    }

    "section 'section2' is multivalued, a componed bounded to its variable B" in {
      val section = technique.rootSection.getAllSections.find( _.name == "section2").get
      val varB = section.children.head.asInstanceOf[InputVariableSpec]
      section.isComponent === true and section.isMultivalued === true and
      section.componentKey === Some("B") and
      section.children.size === 1 and varB.name === "B"
    }
  }


  private[this] def readFile(fileName:String) : Elem = {
    val doc =
      try {
        XML.load(ClassLoader.getSystemResourceAsStream(fileName))
      } catch {
        case e: SAXParseException => throw new Exception("Unexpected issue (unvalid xml?) with " + fileName)
        case e: java.net.MalformedURLException => throw new FileNotFoundException("%s file not found".format(fileName))
      }
    if (doc.isEmpty) {
      throw new Exception("Unexpected issue (unvalid xml?) with the %s file".format(fileName))
    }
    doc
  }

}

