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

package com.normation.inventory.domain

/**
 * A size for some chunk of memory in octets.
 * The idea is to build a rather intelligent class
 * with a number of byte, but also string inputs like
 * 244 Mo, 23 kB, etc
 */
final case class MemorySize(size: Long) extends AnyVal {
  override def toString(): String = "%s B".format(size)

  def toStringMo: String = {
    val (value, unit) = MemorySize.prettyMo(this)
    value + " " + unit
  }
}

object MemorySize {

  val ord: Ordering[MemorySize] = _.size compareTo _.size

  /*
   * We should accept:
   * - no decimal numbers
   * (all means 1234)
   * - "1234"
   * - "1 234"
   * - "1_234"
   * - "1234_"
   * - "1234 "
   * But not (because it should always start with a digit):
   * - "_1_234"
   * - " 1234"
   *
   */
  private val num_r     = """(\d[\d_ ]*)""".r
  private val numunit_r = """(\d[\d_ ]*) *([\p{Alpha}]{0,2})""".r
  val Ko                = 1024L

  private def clean(s: String): Long = s.replaceAll(" ", "").replaceAll("_", "").toLong

  /**
   * Parse a string representation of a memory
   * Known representation are:
   * - a number: interpreted as a number of octets ;
   * - number[ ]unit with unit in "b,o,i,kb,ko,ki,mb,mo,mi,gb,go,gi,
   *   tb,to,ti,pb,po,pi (case does not matter) : the given amount
   *   of memory in unit (o: octet, b:Byte (NOT bits)), i = byte with
   *   binary prefix.
   *   A factor 1000 is applied to Bytes and Octets, a factor 1024 to
   *   Octet with binary prefix
   *
   */
  def parse(s: String): Option[Long] = {
    s match {
      case num_r(x)        => Some(clean(x))
      case numunit_r(x, y) =>
        y.toLowerCase match {
          case "b" | "o" | ""    => Some(clean(x))
          case "ko" | "kb" | "k" => Some(Ko * clean(x))
          case "mo" | "mb" | "m" => Some(Ko * Ko * clean(x))
          case "go" | "gb" | "g" => Some(Ko * Ko * Ko * clean(x))
          case "to" | "tb" | "t" => Some(Ko * Ko * Ko * Ko * clean(x))
          case "po" | "pb" | "p" => Some(Ko * Ko * Ko * Ko * Ko * clean(x))
          case "eo" | "eb" | "e" => Some(Ko * Ko * Ko * Ko * Ko * Ko * clean(x))
          case "zo" | "zb" | "z" => Some(Ko * Ko * Ko * Ko * Ko * Ko * Ko * clean(x))
          case "yo" | "yb" | "y" => Some(Ko * Ko * Ko * Ko * Ko * Ko * Ko * Ko * clean(x))
          case _                 => None
        }
      case _               => None
    }
  }

  def apply(s: String): MemorySize = {
    new MemorySize(parse(s).getOrElse(-1))
  }

  def opt(s: String): Option[MemorySize] = parse(s) map (new MemorySize(_))

  /**
   * Print the memory size in its string representation,
   * with what is considered "a good unit for the number"
   * A good unit is one so that the rounding error is less
   * than 1%, for ex:
   * 512000 => 512 kB because 1MB is almost 50%error
   *
   * It returns a string representation of the value and a string representation of the unit
   * to let it be i18n-able
   */
  private def prettyMo(m: MemorySize): (String, String) = {
    val x = prettyPrint(m, "B" :: "kB" :: "MB" :: "GB" :: "TB" :: "PB" :: "EB" :: "ZB" :: "YB" :: Nil)
    (x._1.bigDecimal.stripTrailingZeros.toPlainString, x._2)
  }

  /**
   * Get the size in megabyte as a Long of the MemorySize passed as parameter
   * compared to prettyMo it always return a size in megabyte and does not display the trailing unit
   */
  def sizeMb(m: MemorySize): Long = {
    val size: BigDecimal = m.size
    val mb = size / Ko / Ko
    if (mb < 1) {
      0
    } else {
      mb.toLong
    }
  }

  /**
   * @param m : The memory quantity to pretty print
   * @param units : The list of available units. Can not be null.
   */
  private def prettyPrint(m: MemorySize, units: List[String]): (BigDecimal, String) = { // return the value and the unit
    val pres3 = new java.math.MathContext(3)

    def round(m: BigDecimal): BigDecimal = {
      if (m < 1) {
        throw new IllegalArgumentException(
          "Could not round number strictly smaller than one. Seems to be an algo error, check with the dev."
        )
      } else if (m < 1000) m.round(pres3)
      else BigDecimal(m.toBigInt)
    }

    @scala.annotation.tailrec
    def rec(m: BigDecimal, u: List[String]): (BigDecimal, String) = u match {
      case Nil       =>
        throw new IllegalArgumentException(
          "At list one unit have to be provided for the memory pretty printer. Look around in the class where the dev don't use that method correctly."
        )
      case h :: Nil  => // no bigger units, stop here
        (round(m), h)
      case h :: tail =>
        if (m < 1) { // should not happen, return 0
          (0, h)
        } else {
          val pe = m / 1024
          if (pe < 1) (round(m), h)
          else rec(pe, tail)
        }
    }
    rec(m.size, units)
  }
}
