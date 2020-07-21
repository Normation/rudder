/*
*************************************************************************************
* Copyright 2020 Normation SAS
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

package com.normation.rudder.metrics


/**
 * Information about commit:
 * - commit name,
 * - email,
 * - sign commit.
 *
 * We will identify each rudder instance with its own id (and email) for signature.
 * Else, just use `Rudder system account <email not set>` like
 * in configuration-repository
 */
final case class CommitInformation(name: String, email: Option[String], sign: Boolean)

/**
 * Base information to retrieve for frequent log historisation about nodes.
 * These information are typically written every 10~20 min, so they shouldn't be slow
 * to fetch and short to write (no node configuration here!)
 * As a rule of thumb, every 10 min means: 6/h, 144/d, 4320/M, 52560/y
 */
final case class FrequentNodeMetrics(
    pending    : Int
  , accepted   : Int
  , modeAudit  : Int // node with at least one audit
  , modeEnforce: Int // node with at least one enforce (excepting system directivces)
  , modeMixed  : Int
)

/**
 * Utility methods about `FrequentNodeMetrics` data to format them correctly in CSV
 */
object FrequentNodeMetrics {
  val csvHeaderNames = List("Date", "Pending", "Accepted", "Audit Mode", "Enforce Mode", "Mixed Mode")

  // add double quotes to a string
  private def q(s: String) = '"'.toString + s + '"'.toString


  def csvHeaders(sep: String = ",") = csvHeaderNames.map(q).mkString(sep)

  implicit class FormatFrequentNodeMetrics(m: FrequentNodeMetrics) {
    def csv(sep: String = ",") = m.productIterator.map(x => q(x.toString)).mkString(sep)
  }

}


/**
 * We want to log accepted node count function of their policy mode. We want the actual policy
 * mode, ie the one reported to take into account case where mode is set agent side.
 * So we have 4 modes, 3 standards and a "none" when we don't have reports for that node.
 */
sealed trait Mode
object Mode {
  final case object Enforce extends Mode
  final case object Audit   extends Mode
  final case object Mixed   extends Mode
  final case object None    extends Mode //error, pending, no answer...
}
