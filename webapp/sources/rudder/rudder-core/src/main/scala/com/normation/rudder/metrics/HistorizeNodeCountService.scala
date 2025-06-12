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

import better.files.*
import com.normation.errors.*
import com.normation.rudder.domain.logger.ScheduledJobLoggerPure
import com.normation.rudder.domain.nodes.NodeState.Ignored
import com.normation.rudder.domain.reports.ComplianceLevel
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.facts.nodes.SelectNodeStatus
import com.normation.rudder.git.GitRepositoryProvider
import com.normation.rudder.git.GitRepositoryProviderImpl
import com.normation.rudder.services.reports.ReportingService
import com.normation.zio.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.QuoteMode
import org.eclipse.jgit.revwalk.RevCommit
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import org.joda.time.format.ISODateTimeFormat
import zio.*

/*
 * First part describe API of main services, then there is default implementation
 */
/**
 * This class retrieve information about number of node used
 */
trait FetchDataService {

  /**
   * Retrieve node information that must be logged frequently, typically number of nodes (pending, by policy mode, etc)
   */
  def getFrequentNodeMetric(): IOResult[FrequentNodeMetrics]
}

trait WriteLogService {

  /**
   *  Write a log for given metric corresponding to given date
   */
  def write(date: DateTime, metrics: FrequentNodeMetrics): IOResult[Unit]
}

/**
 * The service which persist logs in git (optionnaly with signature)
 */
trait CommitLogService {

  /**
   * Commit with the specified message and return corresponding Git commit id.
   */
  def commitLog(cause: String): IOResult[RevCommit]
}

/**
 * The whole service: periodically get data and save them in
 * base, and in git.
 * A timezone can be specidied. By default, system one is used
 */
class HistorizeNodeCountService(
    dataService: FetchDataService,
    writer:      WriteLogService,
    gitLog:      CommitLogService,
    timeZone:    DateTimeZone = DateTimeZone.getDefault()
) {

  /**
   * The main logic: fetch data, write them in file, and commit them in git.
   */
  def fetchDataAndLog(cause: String): IOResult[RevCommit] = {
    for {
      start <- currentTimeMillis
      data  <- dataService.getFrequentNodeMetric()
      now   <- ZIO.clockWith(_.currentTime(TimeUnit.MILLISECONDS).map(new DateTime(_, timeZone)))
      _     <- writer.write(now, data)
      c     <- gitLog.commitLog(cause)
      end   <- currentTimeMillis
      _     <- ScheduledJobLoggerPure.metrics.trace(s"Node count historization done in ${end - start} ms")
    } yield c
  }

  /**
   * A version that handle all errors and log them (for use in batch).
   */
  def scheduledLog(cause: String): UIO[Unit] = {
    fetchDataAndLog(cause).unit.catchAll(err => ScheduledJobLoggerPure.metrics.error(err.fullMsg))
  }

}

/////////////////// implementations ///////////////////

/**
 * Default implementation of `FetchDataService` is just a call to relevant rudder service to get node and reporting
 * info (to know if node actual policy mode)
 */
class FetchDataServiceImpl(nodeFactRepo: NodeFactRepository, reportingService: ReportingService) extends FetchDataService {

  def getFrequentNodeMetric(): IOResult[FrequentNodeMetrics] = {
    // a method that returns "e" if compliance is enforce only,
    // "a" for audit, "b" for both
    def mode(c: ComplianceLevel): Mode = {
      val a = c.success + c.repaired + c.error + c.notApplicable
      val e = c.compliant + c.auditNotApplicable + c.nonCompliant + c.auditError
      (a, e) match {
        case (0, 0) => Mode.None
        case (0, _) => Mode.Enforce
        case (_, 0) => Mode.Audit
        case (_, _) => Mode.Mixed
      }
    }

    for {
      accepted   <- nodeFactRepo.getAll()(using QueryContext.systemQC, SelectNodeStatus.Accepted)
      pending    <- nodeFactRepo.getAll()(using QueryContext.systemQC, SelectNodeStatus.Pending)
      compliance <- reportingService.getUserNodeStatusReports()(using QueryContext.systemQC)
    } yield {
      val modes = compliance.values.groupMapReduce(r => mode(r.compliance))(_ => 1)(_ + _)
      FrequentNodeMetrics(
        pending.size,
        accepted.size,
        modes.getOrElse(Mode.Audit, 0),
        modes.getOrElse(Mode.Enforce, 0),
        modes.getOrElse(Mode.Mixed, 0),
        accepted.count { case (_, n) => n.rudderSettings.state == Ignored }
      )
    }
  }
}

/**
 * We write frequent node information into a CSV file.
 * The file name is based on current date and follows pattern `nodes-xxxxxxx`
 * with `xxxxxxx` a date pattern given in parameter (by default, `yyyy-MM`)
 */
object WriteNodeCSV {
  def make(
      directoryPath:  String,
      csvSeparator:   Char,
      fileDateFormat: String = "yyyy-MM"
  ): ZIO[Any, SystemError, WriteNodeCSV] = {
    val base = File(directoryPath)

    // Check parent directory exists (or can be created) and is writable
    IOResult.attempt {
      if (base.exists) {
        if (base.isDirectory) {
          if (base.isWritable) {
            ()
          } else {
            Unexpected(s"Metric directory '${directoryPath}' isn't writable. Please make it writable.'")
          }
        } else {
          Unexpected(s"Metric directory '${directoryPath}' isn't a directory. It must be a directory.'")
        }
      } else {
        base.createDirectories()
      }
    } *>
    // check that fileDateFormat is OK
    IOResult.attempt(DateTimeFormat.forPattern(fileDateFormat)).map(formatter => new WriteNodeCSV(base, csvSeparator, formatter))
  }
}

/**
 * The service in charge of writing CSV log file.
 * Path of
 * Expected format is:
 * -
 */
class WriteNodeCSV(
    baseDdirectory: File,
    csvSeparator:   Char,
    fileDateFormat: DateTimeFormatter
) extends WriteLogService {

  val csvFormat: CSVFormat =
    CSVFormat.DEFAULT.builder().setDelimiter(csvSeparator).setQuoteMode(QuoteMode.ALL).setRecordSeparator("").get()

  /**
   * We want to write to file node-YYYY-MM with local
   * date/time, so that the switches happens at midnight local hour
   */
  def filename(date: DateTime): String = "nodes-" + date.toString(fileDateFormat)

  /**
   * Format data in one line of CSV according to parameters.
   */
  def csv(date: DateTime, metrics: FrequentNodeMetrics): String = {
    csvFormat.format((date.toString(ISODateTimeFormat.dateTimeNoMillis()) :: metrics.asList)*) + "\n"
  }

  /**
   *  Write a log for given metric corresponding to given date
   */
  def write(date: DateTime, metrics: FrequentNodeMetrics): IOResult[Unit] = {
    // write in file
    val f = File(baseDdirectory, filename(date))
    for {
      _ <- ZIO.whenZIO(IOResult.attempt(!f.exists)) {
             IOResult.attempt(
               f.writeText(FrequentNodeMetrics.csvHeaders(csvSeparator) + "\n")(using
                 File.OpenOptions.default,
                 StandardCharsets.UTF_8
               )
             )
           }
      _ <- IOResult.attempt(f.writeText(csv(date, metrics))(using File.OpenOptions.append, StandardCharsets.UTF_8))
    } yield ()
  }
}

/**
 *  Default implementation for committing information in a git repo passed as argument.
 *  The commit can be asked to be signed by setting `signCommit` to true.
 */
class CommitLogServiceImpl(val gitRepo: GitRepositoryProvider, val commitInfo: Ref[CommitInformation]) extends CommitLogService {

  def commitLog(cause: String): IOResult[RevCommit] = {
    commitInfo.get.flatMap { info =>
      val add = IOResult.attempt {
        gitRepo.git.add().addFilepattern(".").setUpdate(false).call()
        gitRepo.git
          .commit()
          .setCommitter(info.name, info.email.getOrElse(""))
          .setMessage("Log node metrics: " + cause)
          .setSign(info.sign)
          .call()
      }
      add.catchAll { ex =>
        // it may be because repo has a stalled .lock file. Nobody else than rudder should write/read here,
        // so in that case, try to delete it. Then in all case, try to reset, and try again
        // if it's not enough, let the error pop
        val lock = gitRepo.rootDirectory / ".git/index.lock"
        IOResult.attempt(lock.exists).flatMap(exists => if (exists) IOResult.attempt(lock.delete()) else ZIO.unit) *> IOResult
          .attempt(gitRepo.git.reset().call()) *> add
      }
    }
  }
}

object CommitLogServiceImpl {
  import com.normation.rudder.domain.eventlog.RudderEventActor

  /*
   * gitRootPath
   */
  def make(gitRoot: String): IOResult[CommitLogServiceImpl] = {
    /*
     * Check if repository exists. If not, create it.
     * By default, commit are not signed
     */
    for {
      _    <- IOResult.attempt(File(gitRoot).createDirectoryIfNotExists(createParents = true))
      // by default, commit by rudder system user with no signature
      info <- Ref.make(CommitInformation(RudderEventActor.name, None, sign = false))
      repo <- GitRepositoryProviderImpl.make(gitRoot)
    } yield {
      new CommitLogServiceImpl(repo, info)
    }
  }
}
