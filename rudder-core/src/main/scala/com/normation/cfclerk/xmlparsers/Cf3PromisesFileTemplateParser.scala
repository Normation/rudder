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

import com.normation.cfclerk.domain._
import com.normation.cfclerk.exceptions.ParsingException
import net.liftweb.common.Loggable
import scala.xml._
import CfclerkXmlConstants._
import CfclerkXmlConstants._

/**
 * Parse a template file tag in metadata.xml.
 *
 * The tag looks like:
 * <TML name="someIdentification">
 *   <OUTPATH>some_out_path_name</OUTPATH> (optional, default to "techniqueId/templateName.cf")
 *   <INCLUDED>true</INCLUDED> (optional, default to true)
 * </TML>
 */
class Cf3PromisesFileTemplateParser extends Loggable {

  def parseXml(techniqueId: TechniqueId, node: Node): Cf3PromisesFileTemplate = {
    if(node.label != PROMISE_TEMPLATE) throw new ParsingException("Error: try to parse a <%s> node, but actually get: %s".format(PROMISE_TEMPLATE, node))

    val name = node.attribute(PROMISE_TEMPLATE_NAME) match {
      case Some(n) if (n.size == 1) => n.text
      case _ => throw new ParsingException("Error when parsing node %s. Template name is not defined".format(node))
    }

    val outPath = (node \ PROMISE_TEMPLATE_OUTPATH).text match {
      case "" => //set to "techniqueId/templateName.cf
        "%s/%s/%s.cf".format(techniqueId.name.value, techniqueId.version.toString, name)
      case path => path
    }

    logger.trace("Reading node %s for policy %s".format(name, techniqueId))
    val included = !((node \ PROMISE_TEMPLATE_INCLUDED).text == "false")
    logger.trace("Node %s included status is %s".format(name, included))

    Cf3PromisesFileTemplate( Cf3PromisesFileTemplateId(techniqueId, name), included, outPath )
  }
}