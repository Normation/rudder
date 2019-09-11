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

package com.normation.rudder.web.components

import org.joda.time.DateTime
import org.joda.time.format.PeriodFormatterBuilder
import org.joda.time.Duration
import org.joda.time.chrono.ISOChronology
import org.joda.time.format.DateTimeFormat
import net.liftweb.common.Box
import net.liftweb.util.Helpers

object DateFormaterService {

  // Default dateFormat
  private[this] val dateFormat = "yyyy-MM-dd HH:mm"

  def getFormatedDate(date : DateTime) : String = {
    date.toString(dateFormat)
  }

  def parseDate(date : String) : Box[DateTime] = {
      Helpers.tryo {DateTimeFormat.forPattern(dateFormat).parseDateTime(date) }
  }
  private[this] val periodFormatter = new PeriodFormatterBuilder().
    appendDays().
    appendSuffix(" day", " days").
    appendSeparator(", ").
    appendHours().
    appendSuffix(" hour", " hours").
    appendSeparator(", ").
    appendMinutes().
    appendSuffix(" min", " min").
    appendSeparator(", ").
    appendSeconds().
    appendSuffix(" s", " s")
    .toFormatter()

  def formatPeriod(duration:Duration) : String = {
    if(duration.getMillis < 1000) "less than 1 s"
    else periodFormatter.print(duration.toPeriod(ISOChronology.getInstanceUTC))
  }

  def getFormatedPeriod(start : DateTime, end : DateTime) : String = {
    formatPeriod(new Duration(start,end))
  }
}
