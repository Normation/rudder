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

package com.normation.rudder.domain.policies

import com.normation.utils.HashcodeCaching
import scala.xml._

/**
 * Define a "simple diff value holder", a container for the
 * old and new value of something.
 */

final case class SimpleDiff[T](oldValue:T, newValue:T) extends HashcodeCaching

final object SimpleDiff {
  def toXml[T](eltTag:Elem, diff:SimpleDiff[T])(serialize:T => NodeSeq) : NodeSeq = {
    eltTag.copy (
      child = <from>{serialize(diff.oldValue)}</from><to>{serialize(diff.newValue)}</to>
    )
  }

  def stringToXml(eltTag:Elem, diff:SimpleDiff[String]) = toXml[String](eltTag,diff)( s => Text(s))
  def booleanToXml(eltTag:Elem, diff:SimpleDiff[Boolean]) = toXml[Boolean](eltTag,diff)( s => Text(s.toString))
  def intToXml(eltTag:Elem, diff:SimpleDiff[Int]) = toXml[Int](eltTag,diff)( s => Text(s.toString))
  def floatToXml(eltTag:Elem, diff:SimpleDiff[Float]) = toXml[Float](eltTag,diff)( s => Text(s.toString))
  def longToXml(eltTag:Elem, diff:SimpleDiff[Long]) = toXml[Long](eltTag,diff)( s => Text(s.toString))
  def doubleToXml(eltTag:Elem, diff:SimpleDiff[Double]) = toXml[Double](eltTag,diff)( s => Text(s.toString))
}