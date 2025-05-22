/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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

package com.normation.utils

import com.normation.NamedZioLogger
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory
import scala.xml.Elem
import scala.xml.factory.XMLLoader

object XmlSafe extends XMLLoader[Elem] {
  private object ApplicationLogger extends NamedZioLogger {
    parent =>
    def loggerName = "application"
  }

  /*
   * Create a safe sax parser using https://cheatsheetseries.owasp.org/cheatsheets/XML_External_Entity_Prevention_Cheat_Sheet.html#jaxp-documentbuilderfactory-saxparserfactory-and-dom4j
   * guiding lines.
   */
  private val saxParserFactory: SAXParserFactory = {
    try {
      val f = SAXParserFactory.newInstance()
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
      f.setXIncludeAware(false)
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      // this one is from Scala XML implementation of new SAX parser, so keeping it to avoid surprises
      f.setNamespaceAware(false)
      f
    } catch {
      case ex: Throwable =>
        ApplicationLogger.logEffect.error(
          s"Can't create a safe XML parser. Your JVM environment may not be supported. " +
          s"Please contact application developers. Error is: ${ex.getMessage}"
        )
        // stop Rudder
        throw new RuntimeException(ex)
    }
  }

  // def because we must have a fresh instance of parser each time
  override def parser: SAXParser = saxParserFactory.newSAXParser()

}
