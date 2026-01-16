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

package com.normation.rudder.domain.policies

import scala.xml.*

/**
 * Define a "simple diff value holder", a container for the
 * old and new value of something.
 */

final case class SimpleDiff[T](oldValue: T, newValue: T) {
  def map[U](f: T => U): SimpleDiff[U] = SimpleDiff(f(oldValue), f(newValue))
}

object SimpleDiff {
  def toXml[T](eltTag: Elem, diff: SimpleDiff[T])(serialize: T => NodeSeq): NodeSeq = {
    eltTag.copy(
      child = <from>{serialize(diff.oldValue)}</from><to>{serialize(diff.newValue)}</to>
    )
  }

  def stringToXml(eltTag:  Elem, diff: SimpleDiff[String]):  NodeSeq = toXml[String](eltTag, diff)(s => Text(s))
  def booleanToXml(eltTag: Elem, diff: SimpleDiff[Boolean]): NodeSeq = toXml[Boolean](eltTag, diff)(s => Text(s.toString))
  def intToXml(eltTag:     Elem, diff: SimpleDiff[Int]):     NodeSeq = toXml[Int](eltTag, diff)(s => Text(s.toString))
  def floatToXml(eltTag:   Elem, diff: SimpleDiff[Float]):   NodeSeq = toXml[Float](eltTag, diff)(s => Text(s.toString))
  def longToXml(eltTag:    Elem, diff: SimpleDiff[Long]):    NodeSeq = toXml[Long](eltTag, diff)(s => Text(s.toString))
  def doubleToXml(eltTag:  Elem, diff: SimpleDiff[Double]):  NodeSeq = toXml[Double](eltTag, diff)(s => Text(s.toString))

  def optionToXml[T](eltTag: Elem, diff: SimpleDiff[Option[T]])(serialize: T => NodeSeq): NodeSeq = {
    eltTag.copy(
      child = { diff.oldValue.map(v => <from>{serialize(v)}</from>) }.toList ++
        { diff.newValue.map(v => <to>{serialize(v)}</to>) }.toList
    )
  }

  def createDiff[T, U](oldValue: T, newValue: T)(f: T => U): Option[SimpleDiff[U]] = {
    val realOldValue = f(oldValue)
    val realNewValue = f(newValue)
    if (realOldValue == realNewValue) { None }
    else { Some(SimpleDiff(realOldValue, realNewValue)) }
  }
}
