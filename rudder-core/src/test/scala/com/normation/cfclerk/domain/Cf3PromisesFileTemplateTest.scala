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

import org.springframework.test.context.ContextConfiguration
import org.springframework.beans.factory.annotation.Autowired
import junit.framework.TestSuite
import com.normation.cfclerk.services._
import com.normation.cfclerk.services.impl._
import org.junit.Test
import org.junit._
import org.junit.Assert._
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import com.normation.cfclerk.xmlparsers._
import net.liftweb.common._
import scala.collection.mutable._
import scala.xml._
import java.io.FileInputStream
import org.xml.sax.SAXParseException
import org.springframework.test.context.junit4._
import java.io.FileNotFoundException

@RunWith(classOf[SpringJUnit4ClassRunner])
@ContextConfiguration(Array("file:src/test/resources/spring-config-test.xml"))
class Cf3PromisesFileTemplateTest {

  @Autowired
  val tmlParser : Cf3PromisesFileTemplateParser = null

  val filenameTemplate = "testCf3PromisesFileTemplate.xml"

  val doc =
    try {
      XML.load(ClassLoader.getSystemResourceAsStream(filenameTemplate))
    } catch {
      case e:SAXParseException => throw new Exception("Unexpected issue (unvalid xml?) with %s".format(filenameTemplate) )
      case e : java.net.MalformedURLException => throw new FileNotFoundException("%s file not found ".format(filenameTemplate) )
    }

  if(doc.isEmpty) {
    throw new Exception("Unexpected issue (unvalid xml?) with the %s file ".format(filenameTemplate) )
  }

  val askedBundle = Cf3PromisesFileTemplateId(TechniqueId(TechniqueName(""), TechniqueVersion("1.0")),"three")

  val tmlDependencies = new Cf3PromisesFileWriterServiceImpl(new DummyTechniqueRepository(), new SystemVariableSpecServiceImpl() )

  @Test
  def parseDoc() {
    val templateMap = new HashMap[Cf3PromisesFileTemplateId, Cf3PromisesFileTemplate]

    for (elt <- (doc \\"TMLS"\ "TML")) {
      val tml = tmlParser.parseXml(TechniqueId(TechniqueName(""), TechniqueVersion("1.0")), elt)
      templateMap += tml.id -> tml
    }
    assert(templateMap.size==3)
  }
  /*
  @Test
  def orderBundles {
    val templateMap = new HashMap[Cf3PromisesFileTemplateId, Tml]

    for (elt <- (doc \\"TMLS"\ "TML")) {
      val tml = Tml.parseXml("", elt)

      templateMap += tml.name -> tml
    }

    assert(templateMap.size==3)

    val tmls = tmlDependencies.manageDependencies(Set(askedBundle), templateMap)
    assert(tmls.size==3)

    assert(tmls(0) == Cf3PromisesFileTemplateId("","one"))
    assert(tmls(1) == Cf3PromisesFileTemplateId("","two"))
    assert(tmls(2) == Cf3PromisesFileTemplateId("","three"))
  }
  */
}
