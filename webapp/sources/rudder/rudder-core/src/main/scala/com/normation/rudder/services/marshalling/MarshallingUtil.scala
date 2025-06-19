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

package com.normation.rudder.services.marshalling

import scala.xml.Elem
import scala.xml.MetaData
import scala.xml.NodeSeq
import scala.xml.Null
import scala.xml.TopScope
import scala.xml.UnprefixedAttribute

object MarshallingUtil {

  def createTrimedElem(label: String, fileFormat: String)(children: NodeSeq): Elem = {
    // scala XML is lying, the contract is to call trim and
    // returned an Elem, not a Node.
    // See scala.xml.Utility#trim implementation.
    scala.xml.Utility.trim(createElem(label, fileFormat)(children)).asInstanceOf[Elem]
  }

  def createTrimedElem(label: String, fileFormat: String, changeType: String)(children: NodeSeq): Elem = {
    scala.xml.Utility.trim(createElem(label, fileFormat, changeType)(children)).asInstanceOf[Elem]
  }

  private def createElem(label: String, fileFormat: String)(children: NodeSeq): Elem = {
    val attributes = new UnprefixedAttribute("fileFormat", fileFormat, Null)
    createElem(label, attributes)(children)
  }

  private def createElem(label: String, fileFormat: String, changeType: String)(children: NodeSeq): Elem = {
    val attributes =
      new UnprefixedAttribute("fileFormat", fileFormat, Null).append(new UnprefixedAttribute("changeType", changeType, Null))
    createElem(label, attributes)(children)
  }

  private def createElem(label: String, attributes: MetaData)(children: NodeSeq): Elem = {
    Elem(null, label, attributes, TopScope, minimizeEmpty = false, child = children*)
  }
}
