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

import enumeratum.EnumEntry
import magnolia1.CaseClass
import magnolia1.Derivation
import magnolia1.SealedTrait
import scala.language.experimental.macros

/**
 * Generic CSV typeclass using magnolia derivation, from examples :
 * A primitive value maps to a singleton list, case classes map to a list with the same shape
 */
trait Csv[A] {
  def apply(a: A): List[String]
}

object Csv extends Derivation[Csv] {
  import org.apache.commons.csv.*
  import org.apache.commons.lang3.StringUtils

  def empty: List[String] = List("")

  /**
   * Proof of header for CSV instances
   */
  trait Header[A] {
    def header: List[String]
  }

  object Header extends Derivation[Header] {
    // Derive headers names using the parameters name, as camelcase
    def join[A](ctx: CaseClass[Header, A]): Header[A] = new Header[A] {
      def header: List[String] = {
        ctx.parameters
          .map(p => StringUtils.capitalize(StringUtils.splitByCharacterTypeCamelCase(p.label).mkString(" ")))
          .toList
      }
    }

    // by default a sum-type is a column, for one-hot encoding the Header and Csv instance must be defined explicitly
    def split[A](ctx: SealedTrait[Header, A]): Header[A] = new Header[A] {
      def header: List[String] = List(ctx.toString)
    }

    // instances that must exist, but that make no sense unless the `deriveHeader` macro is used on a case class

    given headerAnyRef[A <: AnyRef]: Header[A] = new Header[A] {
      def header: List[String] = Nil
    }
    given headerAnyVal[A <: AnyVal]: Header[A] = new Header[A] {
      def header: List[String] = Nil
    }
  }

  def join[A](ctx: CaseClass[Csv, A]): Csv[A] =
    (a: A) => ctx.parameters.foldLeft(List[String]())((acc, p) => acc ++ p.typeclass(p.deref(a)))

  // User must define an instance (probably a single column) for their subtypes (for one-hot encoding, the header must also be changed accordingly)
  def split[A](ctx: SealedTrait[Csv, A]): Csv[A] = (a: A) => ctx.choose(a)(sub => sub.typeclass(sub.cast(a)))

  // Common instances
  // TODO: change to "instances", make priority
  given Csv[String] = (a: String) => List(a)

  given Csv[Int] = (a: Int) => List(a.toString)

  given [A](using Csv[A]): Csv[Option[A]] = (a: Option[A]) => a.fold(empty)(summon[Csv[A]].apply)

  given [A <: EnumEntry]: Csv[A] = (a: A) => List(a.entryName)

  def instance[A](f: A => String): Csv[A] = (a: A) => summon[Csv[String]].apply(f(a))

  // TODO: change to "syntax"
  /**
   * The CSV output with its headers and lines for results
   */
  extension [A: Csv: Csv.Header](results: Seq[A]) {
    def toCsv: String = {
      val header   = summon[Header[A]].header
      val instance = summon[Csv[A]]
      val out      = new StringBuilder()
      csvFormat.printRecord(out.underlying, header*)
      results.foreach(l => csvFormat.printRecord(out.underlying, instance.apply(l)*))
      out.toString
    }
  }

  // use "," , quote everything with ", line separator is \n
  def csvFormat: CSVFormat = CSVFormat.DEFAULT.builder().setQuoteMode(QuoteMode.ALL).setRecordSeparator("\n").get()

}
