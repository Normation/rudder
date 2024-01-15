/*
 *************************************************************************************
 * Copyright 2014 Normation SAS
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

package com.normation.rudder.services.policies

import com.normation.errors.Inconsistency
import java.time.Duration
import java.time.LocalTime
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Test Node schedule computation
 */

@RunWith(classOf[JUnitRunner])
class TestComputeSchedule extends Specification {

  "A schedule with 5 minutes interval" should {
    "be the default one when starting at 0:00" in {
      ComputeSchedule.computeSchedule(0, 0, 5) must beEqualTo(
        Right(
          (
            LocalTime.of(0, 0),
            """"Min00", "Min05", "Min10", "Min15", "Min20", "Min25", "Min30", "Min35", "Min40", "Min45", "Min50", "Min55""""
          )
        )
      )
    }

    "be the default one when starting at 4:00 because interval < start hour so we start hour % interval" in {
      ComputeSchedule.computeSchedule(4, 0, 5) must beEqualTo(
        Right(
          (
            LocalTime.of(0, 0),
            """"Min00", "Min05", "Min10", "Min15", "Min20", "Min25", "Min30", "Min35", "Min40", "Min45", "Min50", "Min55""""
          )
        )
      )
    }

    "be the default one (midnight) of by 2 minutes when starting at 4:02 with an interval of 2 min" in {
      ComputeSchedule.computeSchedule(4, 2, 5) must beEqualTo(
        Right(
          (
            LocalTime.of(0, 2),
            """"Min02", "Min07", "Min12", "Min17", "Min22", "Min27", "Min32", "Min37", "Min42", "Min47", "Min52", "Min57""""
          )
        )
      )
    }
  }

  "A schedule with non trivial interval" should {
    "fail if a mix of hours and minutes" in {
      ComputeSchedule.computeSchedule(0, 0, 63) must beEqualTo(
        Left(
          Inconsistency(
            "Agent execution interval can only be defined as minutes (less than 60) or complete hours, (1 hours 3 minutes is not supported)"
          )
        )
      )
    }

    "be every hours if defined with an interval of 1 hour" in {
      ComputeSchedule.computeSchedule(0, 0, 60) must beEqualTo(
        Right(
          (
            LocalTime.of(0, 0),
            """"Hr00.Min00", "Hr01.Min00", "Hr02.Min00", "Hr03.Min00", "Hr04.Min00", "Hr05.Min00", "Hr06.Min00", "Hr07.Min00", "Hr08.Min00", "Hr09.Min00", "Hr10.Min00", "Hr11.Min00", "Hr12.Min00", "Hr13.Min00", "Hr14.Min00", "Hr15.Min00", "Hr16.Min00", "Hr17.Min00", "Hr18.Min00", "Hr19.Min00", "Hr20.Min00", "Hr21.Min00", "Hr22.Min00", "Hr23.Min00""""
          )
        )
      )
    }
    "be every two hours if defined with an interval of 2 hours, and off by some minutes, but starts at 1 because 3%2" in {
      ComputeSchedule.computeSchedule(3, 12, 120) must beEqualTo(
        Right(
          (
            LocalTime.of(1, 12),
            """"Hr01.Min12", "Hr03.Min12", "Hr05.Min12", "Hr07.Min12", "Hr09.Min12", "Hr11.Min12", "Hr13.Min12", "Hr15.Min12", "Hr17.Min12", "Hr19.Min12", "Hr21.Min12", "Hr23.Min12""""
          )
        )
      )
    }
    "be every 4 hours if defined with an interval of 4h with the same start time if it's lower than interval" in {
      ComputeSchedule.computeSchedule(3, 12, 240) must beEqualTo(
        Right(
          (
            LocalTime.of(3, 12),
            """"Hr03.Min12", "Hr07.Min12", "Hr11.Min12", "Hr15.Min12", "Hr19.Min12", "Hr23.Min12""""
          )
        )
      )
    }
  }

  "The pre-computed splay-timed hour" should {
    val fiveMinutes = Duration.ofMinutes(5)

    val uuid1        = "365ceac5-a766-48f1-83d9-283f867b2d3d"
    // the random splay from that uuid1: 2min 8s for interval 5min
    val uuid1Splay   = Duration.ofSeconds(128)
    // for 6h interval: 3h 37m 8s
    val uuid1Splay6h = Duration.ofSeconds(3 * 3600 + 37 * 60 + 8)

    val uuid2      = "6daff2fe-0e49-4200-8b8f-0724b59c9dca"
    // the random splay from that uuid2: 1min 1s
    val uuid2Splay = Duration.ofSeconds(61)

    "be 0 when nodeId is empty" in {
      ComputeSchedule.computeSplayTime("", fiveMinutes, fiveMinutes) must beEqualTo(Duration.ofSeconds(0))
    }

    "be stable for a given uuid" in {
      val d1 = ComputeSchedule.computeSplayTime(uuid1, fiveMinutes, fiveMinutes)
      val d2 = ComputeSchedule.computeSplayTime(uuid1, fiveMinutes, fiveMinutes)

      (d1 === uuid1Splay) and (d2 === uuid1Splay)
    }

    "adapt to bigger interval and bigger splay time" in {
      ComputeSchedule.computeSplayTime(uuid1, Duration.ofHours(6), Duration.ofHours(6)) === uuid1Splay6h
    }

    "be different for different uuids" in {
      val d1 = ComputeSchedule.computeSplayTime(uuid1, fiveMinutes, fiveMinutes)
      val d2 = ComputeSchedule.computeSplayTime(uuid2, fiveMinutes, fiveMinutes)

      (d1 === uuid1Splay) and (d2 === uuid2Splay)
    }

    "leads to the correct string" in {
      ComputeSchedule.formatStartTime(
        ComputeSchedule.getSplayedStartTime(uuid1, LocalTime.of(3, 0), fiveMinutes, fiveMinutes)
      ) === "03:02:08"
    }

    "if max splaytime is 0, then we return then start hour" in {
      ComputeSchedule.formatStartTime(
        ComputeSchedule.getSplayedStartTime(uuid1, LocalTime.of(3, 0), fiveMinutes, Duration.ofSeconds(0))
      ) === "03:00:00"
    }

    "if the splay time is bigger than the interval, then the interval length is taken" in {
      ComputeSchedule.computeSplayTime(uuid1, fiveMinutes, Duration.ofMinutes(10)) === uuid1Splay
    }

    "if the splay time is shorter than the interval, then the splay time length is taken" in {
      ComputeSchedule.computeSplayTime(uuid1, Duration.ofMinutes(10), fiveMinutes) === uuid1Splay
    }
  }
}
