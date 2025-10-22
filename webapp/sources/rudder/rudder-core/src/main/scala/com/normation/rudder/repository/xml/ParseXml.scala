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

package com.normation.rudder.repository.xml

import com.normation.errors.*
import com.normation.utils.XmlSafe
import java.io.InputStream
import org.xml.sax.SAXParseException
import scala.xml.Elem
import zio.*
import zio.syntax.*

object ParseXml {

  /**
   * Parse the file denoted by input stream (filePath is only
   * for explicit error messages)
   */
  def apply(is: InputStream, filePath: Option[String] = None): IOResult[Elem] = {
    val name = filePath.getOrElse("[unknown]")
    for {
      doc <- ZIO.attempt(XmlSafe.load(is)).catchAll {
               case e: SAXParseException              =>
                 SystemError(s"Unexpected issue with the XML file ${name}: ${e.getMessage}", e).fail
               case e: java.net.MalformedURLException =>
                 SystemError("XML file not found: " + name, e).fail
               case e =>
                 SystemError(s"Error during parsing of XML file '${name}'", e).fail
             }
      _   <- ZIO.when(doc.isEmpty) {
               Unexpected(s"Error when parsing XML file: '${name}': the parsed document is empty").fail
             }
    } yield {
      doc
    }
  }
}
