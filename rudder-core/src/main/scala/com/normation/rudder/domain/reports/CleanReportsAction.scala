package com.normation.rudder.domain.reports

import com.normation.rudder.domain.logger.ReportLogger
import org.joda.time.DateTime
import net.liftweb.http.js._
import net.liftweb.http.js.JsCmds._
import net.liftweb.common._
import com.normation.rudder.services.system.DatabaseManager
import com.normation.rudder.batch._


/*
 * Clean action definition, this can be used for more usage in the future
 */
abstract class CleanReportAction {
  def name     : String
  def past     : String
  def continue : String
  def actor    : DatabaseCleanerActor
  def actorIsIdle : Boolean  = actor.isIdle
  def progress : String = if (actor.isIdle) "idle" else "in progress"
  def act (date : DateTime) : Box[Int]
  // This method ask for a cleaning
  def ask (date : DateTime) : String = {
    if (actorIsIdle) {
      actor ! ManualLaunch(date)
      "The %s process has started and is in progress".format(name)
    }
   else
     "The %s process is already in progress, and so was not relaunched".format(name)
  }

  val logger = ReportLogger
}

case class ArchiveAction(dbManager:DatabaseManager,dbCleaner : AutomaticReportsCleaning) extends CleanReportAction {
  val name = "archive"
  val past = "archived"
  val continue = "archiving"
  def act(date:DateTime) = dbManager.archiveEntries(date)
  val actor = dbCleaner.archiver
}

case class DeleteAction(dbManager:DatabaseManager,dbCleaner : AutomaticReportsCleaning) extends CleanReportAction {
  val name = "delete"
  val past = "deleted"
  val continue = "deleting"
  def act(date:DateTime) = dbManager.deleteEntries(date)
  val actor = dbCleaner.deleter
}
