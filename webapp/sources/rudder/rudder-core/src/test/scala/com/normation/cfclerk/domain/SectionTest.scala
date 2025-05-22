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

import com.normation.cfclerk.xmlparsers.*
import com.normation.cfclerk.xmlparsers.CfclerkXmlConstants.*
import com.normation.utils.XmlSafe
import java.io.FileNotFoundException
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import org.xml.sax.SAXParseException
import scala.xml.*

@RunWith(classOf[JUnitRunner])
class SectionTest extends Specification {

  // we are testing error cases, so we don't want to output error log for them
  org.slf4j.LoggerFactory
    .getLogger("com.normation.cfclerk.xmlparsers")
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.OFF)

  val doc: Elem = readFile("testSections.xml")
  def sectionsTag(example: String): Node = (doc \\ "examples" \ example \ "SECTIONS").head

  val sectionSpecParser = new SectionSpecParser(new VariableSpecParser)

  val sectionParser: SectionParser = SectionParser(sectionSpecParser)

  ///// test on the well formed example /////

  val rootSectionsOk: SectionSpec =
    sectionParser.parseXml(sectionsTag("ok")).getOrElse(throw new IllegalArgumentException("This must be valid for test!"))

  "the test sections <ok>" should {
    val vars  = "A" :: "B" :: "C" :: "D" :: "E" :: "F" :: Nil
    val sects = SECTION_ROOT_NAME :: "sectA" :: "sect1" :: "sect2" ::
      "sect3" :: "emptySect" :: "component" :: "sectF" :: Nil

    "have variables %s".format(vars.mkString("[", ",", "]")) in {
      vars === rootSectionsOk.getAllVariables.map(_.name)
    }

    "have sections %s".format(sects.mkString("[", ",", "]")) in {
      sects === rootSectionsOk.getAllSections.map(_.name)
    }

  }

  "section named sect1" should {
    implicit val section = getUniqueSection("sect1")
    containNbVariables(4)
    beMultivalued
    beHighPriority
  }

  "section named sect2" should {
    implicit val section = getUniqueSection("sect2")
    haveNbChildren(2)
    // all children are variables
    containNbVariables(2)
    beHighPriority
  }

  "section named sect3" should {
    implicit val section = getUniqueSection("sect3")
    haveNbChildren(1)
    // all children are variables
    containNbVariables(1)
    beLowPriority
  }

  "section named emptySect" should {
    implicit val section = getUniqueSection("emptySect")
    beEmpty
    haveNbChildren(0)
  }

  "section named component" should {
    implicit val section = getUniqueSection("component")
    beEmpty
    haveNbChildren(0)

    "be a component" in {
      section.isComponent must beTrue
    }

    "have component key C" in {
      section.componentKey === Some("C")
    }
  }

  private def getUniqueSection(name: String): SectionSpec = {
    implicit val sects = rootSectionsOk.filterByName(name)
    beUnique

    implicit val sectChild = sects.head
    beSection

    sectChild match {
      case sect: SectionSpec => sect
      case _ => sys.error("Have not a section should not happen")
    }
  }

  private def beEmpty(implicit section: SectionSpec) = {
    "be empty" in {
      section.children.size mustEqual 0
    }
  }

  private def haveNbChildren(nbChildren: Int)(implicit section: SectionSpec) = {
    "have %d children".format(nbChildren) in {
      section.children.size mustEqual nbChildren
    }
  }

  private def containNbVariables(nbVariables: Int)(implicit section: SectionSpec) = {
    "contain %d variables".format(nbVariables) in {
      section.getAllVariables.size mustEqual nbVariables
    }
  }

  private def beUnique(implicit children: Seq[SectionChildSpec]) = {
    "be unique" in {
      children.size mustEqual 1
    }
  }

  private def beSection(implicit sectionChild: SectionChildSpec) = {
    "be a section" in {
      sectionChild.isInstanceOf[SectionSpec]
    }
  }

  private def beMultivalued(implicit section: SectionSpec) = {
    "be multivalued" in {
      section.isMultivalued
    }
  }

  private def beHighPriority(implicit section: SectionSpec) = {
    "be of high priority (default) " in {
      section.displayPriority mustEqual HighDisplayPriority
    }
  }

  private def beLowPriority(implicit section: SectionSpec) = {
    "be of low priority (non default) " in {
      section.displayPriority mustEqual LowDisplayPriority
    }
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

final case class SectionParser(sectionSpecParser: SectionSpecParser) {
  val id         = new TechniqueId(new TechniqueName("test-TechniqueId"), TechniqueVersionHelper("1.0"))
  val policyName = "test-policyName"

  def parseXml(elt: Node): Either[LoadTechniqueError, SectionSpec] = {
    sectionSpecParser.parseSectionsInPolicy(elt, id, policyName)
  }
}
