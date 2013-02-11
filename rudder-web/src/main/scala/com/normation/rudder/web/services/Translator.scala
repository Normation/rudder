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

package com.normation.rudder.web.services

import com.normation.utils.Utils.isEmpty
import scala.collection.mutable.{ Map => MutMap }
import net.liftweb.common._
import org.apache.commons.io.FilenameUtils

class Serializer[T](techniques: (String, T => String)*) {
  //all the known properties for that type
  private val propReg: MutMap[String, T => String] = MutMap()
  techniques foreach { case (prop, t) => add(prop, t) }

  def add(prop: String, t: T => String): Unit = propReg += (prop.toLowerCase -> t)

  def add(kv: (String, T => String)): Unit = add(kv._1, kv._2)

  def get(prop: String): Option[T => String] = propReg.get(prop.toLowerCase)

  def apply(prop: String, value: T): Option[String] = propReg.get(prop) map { f =>
    f(value)
  }

  def values = propReg.valuesIterator.toSeq

  /**
   * Get the list of all defined properties
   */
  def properties = propReg.keysIterator.toSeq.sortWith(_ < _)

  def iterator: Iterator[(String, T => String)] = propReg.iterator
}

class Unserializer[T](techniques: (String, String => Box[T])*) {
  //all the known properties for that type
  private val propReg: MutMap[String, String => Box[T]] = MutMap()
  techniques foreach { case (prop, t) => add(prop, t) }

  def add(prop: String, t: String => Box[T]): Unit = propReg += (prop.toLowerCase -> t)

  def add(kv: (String, String => Box[T])): Unit = add(kv._1, kv._2)

  def get(prop: String): Option[String => Box[T]] = propReg.get(prop.toLowerCase)

  def apply(prop: String, value: String): Box[T] = propReg.get(prop) flatMap { f =>
    f(value)
  }

  def values = propReg.valuesIterator.toSeq

  /**
   * Get the list of all defined properties
   */
  def properties = propReg.keysIterator.toSeq.sortWith(_ < _)

  def iterator: Iterator[(String, String => Box[T])] = propReg.iterator
}

/*
 * A register of all methods available for
 * the given type to transform it to a string.
 *
 * For example, a Translator[File] may have
 * an entry "filename" in its register
 * that gives a function that transform
 * file in its filename.
 * The returned method is always of the
 * type T => String (we only know how
 * to transform complex object to string)
 */
class Translator[T](val to: Serializer[T], val from: Unserializer[T])

class Translators {

  private val reg: MutMap[Manifest[_], Translator[_]] = MutMap()

  /**
   * Add a translator for the given type.
   * If a translator of the same type already exists in the
   * translator register, properties of both translator are merged.
   * If two properties overlap, the last added wins.
   * @param t
   * @param m
   */
  def add[T](t: Translator[T])(implicit m: Manifest[T]): Unit = {
    get(m) match {
      case None => reg += (m -> t)
      case Some(existing) => {
        t.to.iterator foreach { existing.to.add(_) }
        t.from.iterator foreach { existing.from.add(_) }
        reg += (m -> existing)
      }
    }
  }

  def get[T](implicit m: Manifest[T]): Option[Translator[T]] = {
    reg.get(m) match {
      case Some(t: Translator[T]) => Some[Translator[T]](t)
      case _ => None
    }
  }
}

/*
 * Common translator implementations
 *
 */
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormatter
import java.io.File
import com.normation.rudder.web.model.FilePerms

object StringTranslator extends Translator[String](
  new Serializer("self" -> { s: String => s }),
  new Unserializer("self" -> { s: String => Full(s) }))

//datetime translator
class DateTimeTranslator(
  dateFormatter: DateTimeFormatter,
  timeFormatter: DateTimeFormatter,
  datetimeFormatter: Option[DateTimeFormatter] = None) extends Translator[DateTime](
  new Serializer(
    "self" -> { x =>
      datetimeFormatter match {
        case Some(dtf) => x.toString(dtf)
        case None => x.toString
      }
    },
    "date" -> { x => x.toString(dateFormatter) },
    "time" -> { x => x.toString(timeFormatter) }),
  new Unserializer(
    "self" -> { x =>
      try {
        datetimeFormatter match {
          case Some(dtf) => Full(dtf.parseDateTime(x))
          case None => Full(new DateTime(x))
        }
      } catch {
        case e: IllegalArgumentException =>
          Failure(e.getMessage)
      }
    }))

object FilePermsTranslator extends Translator[FilePerms](
  new Serializer(
    "self" -> { x => if (null == x) "000" else x.octal },
    "user" -> { x => if (null == x) "0" else x.u.octal },
    "group" -> { x => if (null == x) "0" else x.g.octal },
    "other" -> { x => if (null == x) "0" else x.o.octal }),
  new Unserializer(
    "self" -> { x => FilePerms(x) }))

object SelectFieldTranslator extends Translator[Seq[String]](
  new Serializer(
    "self" -> { s => s.mkString(",")}),
  new Unserializer(
    "self" -> { s => Some(s.split(","):Seq[String]) }))

//file translator
object FileTranslator extends Translator[File](
  new Serializer(
    "self" -> { x => if (null == x) "" else x.getPath },
    "filename" -> { x => if (null == x) "" else x.getName },
    "size" -> { x => if (null == x) "-1" else x.length.toString }),
  new Unserializer(
    "self" -> { x => if (isEmpty(x)) Empty else Full(new File(x)) }))

object DestinationFileTranslator extends Translator[String](
  new Serializer(
    "destinationname" -> { x => FilenameUtils.getName(x) },
    "destinationfolder" -> { x => FilenameUtils.getFullPathNoEndSeparator(x) }),
  new Unserializer())