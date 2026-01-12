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

package com.normation.rudder.repository.jdbc

import cats.data.NonEmptyList
import com.normation.eventlog.*
import com.normation.rudder.db.DBCommon
import com.normation.rudder.domain.eventlog.*
import doobie.*
import doobie.specs2.analysisspec.IOChecker
import java.time.*
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.xml.*

/**
 *
 * Test reporting service:
 *
 */
@RunWith(classOf[JUnitRunner])
class EventLogJdbcRepositoryTest extends Specification with IOChecker with DBCommon {
  self =>

  sequential

  def transactor: Transactor[cats.effect.IO] = doobie.xaio

  check(EventLogJdbcRepository.getLastEventByChangeRequestSQL("/", Nil))
  check(EventLogJdbcRepository.getLastEventByChangeRequestSQL("/", ChangeRequestLogsFilter.eventList))

  check(EventLogJdbcRepository.getEventLogCountSQL(None))
  check(EventLogJdbcRepository.getEventLogCountSQL(Some(defaultFilter)))
  check(
    EventLogJdbcRepository.getEventLogCountSQL(
      Some(defaultFilter.copy(search = Some(EventLogRequest.Search("inventory"))))
    )
  )
  check(EventLogJdbcRepository.getEventLogCountSQL(Some(defaultFilter.copy(startDate = Some(Instant.now())))))
  check(EventLogJdbcRepository.getEventLogCountSQL(Some(defaultFilter.copy(endDate = Some(Instant.now())))))
  check(
    EventLogJdbcRepository.getEventLogCountSQL(
      Some(defaultFilter.copy(startDate = Some(Instant.now()), endDate = Some(Instant.now())))
    )
  )
  check(
    EventLogJdbcRepository.getEventLogCountSQL(
      Some(defaultFilter.copy(principal = Some(EventLogRequest.PrincipalFilter(None, None))))
    )
  )
  check(
    EventLogJdbcRepository.getEventLogCountSQL(
      Some(
        defaultFilter.copy(principal = Some(EventLogRequest.PrincipalFilter(Some(NonEmptyList.of(EventActor("rudder"))), None)))
      )
    )
  )
  check(
    EventLogJdbcRepository.getEventLogCountSQL(
      Some(
        defaultFilter.copy(principal = Some(EventLogRequest.PrincipalFilter(None, Some(NonEmptyList.of(EventActor("rudder"))))))
      )
    )
  )
  check(
    EventLogJdbcRepository.getEventLogCountSQL(
      Some(
        defaultFilter.copy(search = Some(EventLogRequest.Search(value = "inventory")))
      )
    )
  )
  check(EventLogJdbcRepository.getEventLogByCriteriaSQL(None))
  check(EventLogJdbcRepository.getEventLogByCriteriaSQL(Some(defaultFilter)))
  check(
    EventLogJdbcRepository.saveEventLogSQL(
      ModificationId("f231ea1f-ed66-4666-837d-1d79558702b8"),
      AcceptNodeEventLog(
        EventLogDetails(
          id = Some(1),
          modificationId = Some(ModificationId("mytest")),
          principal = RudderEventActor,
          creationDate = Instant.now,
          cause = Some(1),
          severity = 2,
          reason = Some("reason"),
          details = EventLog.withContent(scala.xml.Utility.trim(<hello>world</hello>))
        )
      ),
      EventLog.withContent(scala.xml.Utility.trim(<hello>world</hello>))
    )
  )

  def defaultFilter = EventLogRequest(0, 10, None, None, None, None, None)

}
