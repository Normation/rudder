package com.normation.rudder.domain.reports

import com.normation.rudder.domain.logger.ReportLogger
import org.joda.time.DateTime
import net.liftweb.http.js._
import net.liftweb.http.js.JsCmds._
import net.liftweb.common._
import com.normation.rudder.services.system.DatabaseManager


/*
 * Clean action definition, this can be used for more usage in the future
 */
case class CleanReportAction(name:String,past:String,continue:String,act:DateTime => Box[Int]) {
  val logger = ReportLogger
  def cleanReports(date:DateTime) : JsCmd = {
    logger.info("%s all reports before %s".format(continue,date))
    try {
      act(date) match {

        case eb:EmptyBox =>
          val e = eb ?~! "An error occured while %s reports".format(continue.toLowerCase)
          val eToPrint = e.failureChain.map( _.msg ).mkString("", ": ", "")
          logger.error(eToPrint)
          Alert(eToPrint)

        case Full(result) =>
          logger.info("Correctly %s %d reports".format(past.toLowerCase,result))
          Alert("Correctly %s %d reports".format(past.toLowerCase,result))

      }
    } catch {
      case e: Exception => logger.error("Could not %s reports".format(name.toLowerCase), e)
        Alert("An error occured while %s reports".format(continue.toLowerCase))
    }
  }
}

case class ArchiveAction(dbManager:DatabaseManager) extends CleanReportAction("Archive","Archived","Archiving",dbManager.archiveEntries _)

case class DeleteAction(dbManager:DatabaseManager) extends CleanReportAction("Delete","Deleted","Deleting",dbManager.deleteEntries _)
