/*
*************************************************************************************
* Copyright 2016 Normation SAS
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

import java.nio.charset.StandardCharsets

import com.normation.errors.IOResult
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import zio._
import zio.duration._
import zio.test.environment._
import better.files._
import org.joda.time.DateTimeZone
import zio.clock.Clock

@RunWith(classOf[JUnitRunner])
class NodeCountHistorizationTest extends Specification with BeforeAfter  {


  // a fast aggregation service
  class TestAggregateDataService(r: Ref[FrequentNodeMetrics]) extends FetchDataService {
    override def getFrequentNodeMetric(): IOResult[FrequentNodeMetrics] = {
      r.get
    }
  }

  lazy val startDate = DateTime.now()
  lazy val rootDir = s"/tmp/rudder-test-nodecount/${startDate.toString(ISODateTimeFormat.dateTime())}"

  val rudder = CommitInformation(
      "rudder-a32c5441-daa5-4244-8792-17f1a43cd9bd"
    , Some("rudder+a32c5441-daa5-4244-8792-17f1a43cd9bd@rudder.io")
    , false
  )

  override def before: Any = {
    File(rootDir).createDirectories()
  }

  override def after: Any = {
    if(System.getProperty("tests.clean.tmp") != "false") {
      File(rootDir).delete()
    }
  }


  def makeService(rootDir: String, refMetrics: Ref[FrequentNodeMetrics], zclock: Clock) = for {
    gitLogger <- CommitLogServiceImpl.make(rootDir)
    writer    <- WriteNodeCSV.make(rootDir, ";", "yyyy-MM")
  } yield {
    new HistorizeNodeCountService(
        new TestAggregateDataService(refMetrics)
      , writer
      , gitLogger
      , zclock
      , DateTimeZone.UTC // never change log line
    )
  }

  "Calling one time fetchDataAndLog should initialize everything and write log" >> {
    val t = 1584676176000L.millis // 2020-03-20 3:49:36
    val prog = ZIO.accessM[Clock with TestClock] { testClock =>
      for {
        refMetrics <- Ref.make(FrequentNodeMetrics(1,2,3,4,5))
        _          <- testClock.get[TestClock.Service].setTime(t)
        _          <- testClock.get[Clock.Service].sleep(t)
        service    <- makeService(rootDir, refMetrics, testClock)
        commit     <- service.fetchDataAndLog("initilize logs")
      } yield commit
    }.provideLayer(testEnvironment)
    Runtime.default.unsafeRun(prog)
    val lines = File(rootDir, "nodes-2020-03").lines(StandardCharsets.UTF_8).toVector

    (lines.size must beEqualTo(2)) and (
      lines(1) must beEqualTo(""""2020-03-20T03:49:36Z";"1";"2";"3";"4";"5"""")
    )
  }

}





