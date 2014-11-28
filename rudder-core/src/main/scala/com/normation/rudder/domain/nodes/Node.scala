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

package com.normation.rudder.domain.nodes

import com.normation.inventory.domain.NodeId
import com.normation.utils.HashcodeCaching
import com.normation.rudder.reports.ReportingConfiguration
import net.liftweb.json.JsonAST.JObject
import org.joda.time.DateTime

/**
 * The entry point for a REGISTERED node in Rudder.
 *
 * This is independant from inventory, and can exist without one.
 *
 */
case class Node(
    id                        : NodeId
  , name                      : String
  , description               : String
  , isBroken                  : Boolean
  , isSystem                  : Boolean
  , isPolicyServer            : Boolean
  , creationDate              : DateTime
  , nodeReportingConfiguration: ReportingConfiguration
  , properties                : Seq[NodeProperty]
) extends HashcodeCaching



case class NodeProperty(name: String, value: String)

object JsonSerialisation {

  import net.liftweb.json.JsonDSL._
  import net.liftweb.json._


  implicit class JsonNodeProperty(x: NodeProperty) {
    def toLdapJson(): JObject = (
        ( "name"  , x.name  )
      ~ ( "value" , x.value )
    )
  }

  implicit class JsonNodeProperties(props: Seq[NodeProperty]) {
    import net.liftweb.json.Serialization.write
    implicit val formats = DefaultFormats

    private[this] def json(x: NodeProperty): JObject = (
        ( "name"  , x.name  )
      ~ ( "value" , x.value )
    )

    def toApiJson(): JArray = {
      JArray(props.map(json(_)).toList)
    }
  }

  def unserializeLdapNodeProperty(value:String): NodeProperty = {
    import net.liftweb.json.JsonParser._
    implicit val formats = DefaultFormats

    parse(value).extract[NodeProperty]
  }

}

