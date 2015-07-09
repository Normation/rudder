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

package com.normation.cfclerk.xmlparsers
import scala.xml._
import net.liftweb.common._
import com.normation.cfclerk.domain._
import CfclerkXmlConstants._
import com.normation.cfclerk.exceptions.ParsingException

/**
 * @author vincent
 * Marker <compatible> parser
 */
object CompatibleParser {

  /**
   * Parse a <compatible> marker
   * @param node example :
   * <COMPATIBLE>
   * <OS>Ubuntu</OS>
   * <OS>debian-5</OS>
   * <AGENT version=">= 3.5">cfengine-community</AGENT>
   * </COMPATIBLE>
   * @return A compatible variable corresponding to the entry node
   */
  def parseXml(node: Node): Compatible = {
    if(node.label != COMPAT_TAG) throw new ParsingException("CompatibleParser was expecting a <%s> node and get:\n%s".format(COMPAT_TAG, node))
    val os = node \ COMPAT_OS map (n =>
      OperatingSystem(n.text, (n \ "@version").text))
    val agents = node \ COMPAT_AGENT map (n =>
      Agent(n.text, (n \ "@version").text))
    Compatible(os, agents)
  }
}