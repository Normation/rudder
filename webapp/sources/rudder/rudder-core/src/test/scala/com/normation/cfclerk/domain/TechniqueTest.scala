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

package com.normation.cfclerk.domain

import java.io.FileNotFoundException
import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import org.xml.sax.SAXParseException
import scala.xml._
import com.normation.cfclerk.xmlparsers._
import scala.xml._
import com.normation.cfclerk.services.impl.SystemVariableSpecServiceImpl

@RunWith(classOf[JUnitRunner])
class TechniqueTest extends Specification {

  val techniqueParser = {
    val varParser = new VariableSpecParser
    new TechniqueParser(varParser, new SectionSpecParser(varParser), new SystemVariableSpecServiceImpl())
  }

  val id = TechniqueId(TechniqueName("foo"), TechniqueVersion("1.0"))

  val technique = techniqueParser.parseXml(readFile("testTechnique.xml"), id).getOrElse(throw new IllegalArgumentException("Technique XML must be valid for test"))


  "Compatible OS and Agents" should {
    val xml = <COMPATIBLE><OS>debian-5</OS><OS>windowsXP</OS><AGENT version=">= 3.5">cfengine-community</AGENT></COMPATIBLE>
    val os = List(OperatingSystem("debian-5"), OperatingSystem("windowsXP"))
    val agents = List(Agent("cfengine-community", ">= 3.5"))
    val compatible = Compatible(os, agents)
    "works so that parsing : " + xml + " yields " + compatible.toString in {
      techniqueParser.parseCompatibleTag(xml) === Right(compatible)
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

    "have bundle list: 'bundle1,bundle2' for each agent" in {
      technique.agentConfigs.map( _.bundlesequence.map( _.value ))  must contain( beEqualTo(Seq("bundle1","bundle2"))).foreach
    }

    "have templates 'tml1, tml2, tml3' for each agent" in {
      technique.agentConfigs.map( _.templates.map( _.id.name )) must contain( beEqualTo( Seq("tml1", "tml2", "tml3"))).foreach
    }

    def getTemplateByid(technique: Technique, name: String) = {
      technique.agentConfigs.head.templates.find( _.id == TechniqueResourceIdByName(technique.id, name)).getOrElse(throw new Exception(s"Test must contain resource '${name}'"))
    }

    "'tml1' is included and has default outpath" in {
      val tml = getTemplateByid(technique, "tml1")
      tml.included === true and tml.outPath === "foo/1.0/tml1.cf"
    }

    "'tml2' is included and has tml2.bar outpath" in {
      val tml = getTemplateByid(technique, "tml2")
      tml.included === true and tml.outPath === "tml2.bar"
    }

    "'tml3' is not included and has default outpath" in {
      val tml = getTemplateByid(technique, "tml3")
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

