package com.normation.rudder.domain.reports

import com.normation.rudder.batch._
import com.normation.rudder.domain.logger.ReportLogger
import com.normation.rudder.services.system.DatabaseManager
import com.normation.rudder.services.system.DeleteCommand
import net.liftweb.common._
import org.joda.time.DateTime

/*
 * This file defines data structures to store action
 * about archiving and cleaning reports.
 */

sealed trait CleanReportAction {
  def name:        String
  def past:        String
  def continue:    String
  def actor:       DatabaseCleanerActor
  def actorIsIdle: Boolean = actor.isIdle
  def progress:    String  = if (actor.isIdle) "idle" else "in progress"
  def act(reports: DeleteCommand.Reports, complianceLevel: Option[DeleteCommand.ComplianceLevel]): Box[Int]
  // This method ask for a cleaning
  def ask(date: DateTime): String = {
    if (actorIsIdle) {
      actor ! ManualLaunch(date)
      "The %s process has started and is in progress".format(name)
    } else {
      "The %s process is already in progress, and so was not relaunched".format(name)
    }
  }

  val logger = ReportLogger
}

final case class ArchiveAction(dbManager: DatabaseManager, dbCleaner: AutomaticReportsCleaning) extends CleanReportAction {
  val name     = "archive"
  val past     = "archived"
  val continue = "archiving"
  def act(reports: DeleteCommand.Reports, complianceLevel: Option[DeleteCommand.ComplianceLevel]): Box[Int] =
    dbManager.archiveEntries(reports.date)
  lazy val actor = dbCleaner.archiver
}

final case class DeleteAction(dbManager: DatabaseManager, dbCleaner: AutomaticReportsCleaning) extends CleanReportAction {
  val name     = "delete"
  val past     = "deleted"
  val continue = "deleting"
  def act(reports: DeleteCommand.Reports, complianceLevel: Option[DeleteCommand.ComplianceLevel]): Box[Int] =
    dbManager.deleteEntries(reports, complianceLevel)
  lazy val actor = dbCleaner.deleter
}
