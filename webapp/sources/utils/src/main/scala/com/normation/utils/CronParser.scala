package com.normation.utils

import com.normation.errors.PureResult
import com.normation.errors.SystemError
import cron4s.Cron
import cron4s.CronExpr
import cron4s.lib.javatime._
import cron4s.toDateTimeCronOps
import java.time.OffsetDateTime
import zio.Schedule
import zio.Schedule.Decision
import zio.UIO

/**
 * An utility library that parses a cron expression and make it available as a ZIO Schedule
 */
object CronParser {

  val DISABLED = "@never"

  implicit class CronParser(val s: String) extends AnyVal {
    def toCronEither: Either[Throwable, CronExpr] = {
      Cron.parse(s)
    }

    def toCron: PureResult[CronExpr] = {
      toCronEither.left.map(err => SystemError(s"Error when parsing cron expression: '${s}'", err))
    }

    /*
     * A version of the parser that will interpret the special value
     * DISABLED as "do not parse"
     */
    def toOptCron = {
      s.toLowerCase() match {
        case DISABLED => Right(None)
        case cron     => cron.toCron.map(Some(_))
      }
    }
  }

  implicit class CronConverter(c: CronExpr) {
    def toSchedule = {
      def loop(now: OffsetDateTime, out: Any): UIO[Decision[Any, Any, Any]] = {
        c.next(now) match {
          case Some(next) => UIO(Decision.Continue((), next, loop))
          case None       => UIO(Decision.Done(()))
        }
      }
      Schedule(loop)
    }
  }
}
