/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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

package com.normation.inventory.provisioning.fusion

import com.normation.errors.*
import com.normation.inventory.provisioning.fusion.OptText.optText
import com.normation.inventory.services.provisioning.*
import java.time.*
import java.time.format.DateTimeFormatter
import scala.xml.NodeSeq

class PreInventoryParserCheckInventoryAge(maxBeforeNow: Duration, maxAfterNow: Duration) extends PreInventoryParser {
  override val name = "post_process_inventory:check_inventory_age"

  /**
   * We don't want to accept inventories;
   * - older than maxBeforeNow
   * - more in the future than maxAfterNow
   *
   * Age is deducted from the attribute:
   * - ACCESSLOG > LOGDATE, which is a datetime in local server time, without timezone.
   * - OPERATINGSYSTEM > TIMEZONE > TIMEZONE that may not be there, for more fun.
   *
   * If we are not able to parse the timezone, then assume UTC because it's at least a known error.
   * If logdate is missing or not parsable, assume the inventory is out of range (it will likely
   * be refused further one).
   */
  override def apply(inventory: NodeSeq): IOResult[NodeSeq] = {
    val now  = OffsetDateTime.now()
    val date = PreInventoryParserCheckInventoryAge.extracDateValue(inventory)

    (for {
      d <- PreInventoryParserCheckInventoryAge.parseInventoryDate(date)
      _ <- PreInventoryParserCheckInventoryAge.checkDate(d, now, maxBeforeNow, maxAfterNow)
    } yield inventory).toIO
  }

}

object PreInventoryParserCheckInventoryAge {

  val dateTimeFormat       = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
  val offsetDateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssZ")

  def extracDateValue(inventory: NodeSeq): String = {
    val logdate  = optText(inventory \\ "ACCESSLOG" \ "LOGDATE").getOrElse("")
    val timezone = optText(inventory \\ "OPERATINGSYSTEM" \ "TIMEZONE" \ "OFFSET").getOrElse("")

    logdate + timezone
  }

  // try to parse the inventory date in format YYYY-MM-dd HH:mm:ssZ
  // ex UTC: 2023-11-16 10:46:35+0000
  // ex Europe/Paris: 2023-11-16 11:46:12+0100
  def parseOffsetDateTime(date: String): PureResult[OffsetDateTime] = {
    PureResult.attempt(s"Error when parsing date '${date}' as a time-zoned date with offset")(
      OffsetDateTime.parse(date, offsetDateTimeFormat)
    )
  }

  // try to parse the inventory date in format: YYYY-MM-dd HH:mm:ss
  // Since in that case, we don't have timezone information, we need to chose arbitrary,
  // and we chose UTC so that it's easier to identify problem when they arise.
  def parseLocalDateTime(date: String): PureResult[OffsetDateTime] = {
    PureResult
      .attempt(s"Error when parsing date '${date}' as a local date time")(
        LocalDateTime.parse(date, dateTimeFormat)
      )
      .map(OffsetDateTime.of(_, ZoneOffset.UTC))
  }

  def parseInventoryDate(date: String): PureResult[OffsetDateTime] = {
    parseOffsetDateTime(date) match {
      case Left(_)  => parseLocalDateTime(date)
      case Right(d) => Right(d)
    }
  }

  // check that date is in window between [now-maxBeforeNow, now+maxAfterNow]
  def checkDate(date: OffsetDateTime, now: OffsetDateTime, maxBeforeNow: Duration, maxAfterNow: Duration): PureResult[Unit] = {
    val pastLimit   = now.minus(maxBeforeNow)
    val futureLimit = now.plus(maxAfterNow)

    val beforePastLimit  = Inconsistency(s"Inventory is too old, refusing (inventory date is before '${pastLimit.toString}')")
    val afterFutureLimit = Inconsistency(
      s"Inventory is too far in the future, refusing (inventory date is after '${futureLimit.toString}')"
    )

    (date.isBefore(pastLimit), date.isAfter(futureLimit)) match {
      case (false, false) => Right(())
      case (true, _)      => Left(beforePastLimit)
      case (_, true)      => Left(afterFutureLimit)
    }
  }
}
