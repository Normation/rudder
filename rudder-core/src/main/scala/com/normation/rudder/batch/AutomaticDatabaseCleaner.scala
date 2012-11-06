/*
*************************************************************************************
* Copyright 2012 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.batch

import net.liftweb.actor.{LiftActor, LAPinger}
import com.normation.rudder.services.system.DatabaseManager
import net.liftweb.common._
import org.joda.time._
import com.normation.rudder.domain.logger.ReportLogger


object FrequencyBuilder {

  val minParam  = "rudder.batch.reportsCleaner.runtime.minute"
  val hourParam = "rudder.batch.reportsCleaner.runtime.hour"
  val dayParam  = "rudder.batch.reportsCleaner.runtime.day"
  val freqParam = "rudder.batch.reportsCleaner.frequency"

  def build(kind:String,min:Int,hour:Int,day:String):Box[CleanFrequency] = {
    kind.toLowerCase() match {
      case "hourly" => buildHourly(min)
      case "daily"  => buildDaily(min,hour)
      case "weekly" => buildWeekly(min,hour,day)
      case _ =>     Failure("%s is not correctly set, value is %s".format(freqParam,kind))
    }

  }
  def buildHourly(min:Int):Box[CleanFrequency] = {

    if (min >= 0 && min <= 59)
      Full(Hourly(min))
    else
      Failure("%s is not correctly set, value is %d, should be in [0-59]".format(minParam,min))
  }

  def buildDaily(min:Int,hour:Int):Box[CleanFrequency] = {

    if (min >= 0 && min <= 59)
      if(hour >= 0 && hour <= 23)
        Full(Daily(hour,min))
      else
        Failure("%s is not correctly set, value is %d, should be in [0-23]".format(hourParam,hour))
    else
      Failure("%s is not correctly set, value is %d, should be in [0-59]".format(minParam,min))
  }

    def buildWeekly(min:Int,hour:Int,day:String):Option[CleanFrequency] = {

    if (min >= 0 && min <= 59)
      if(hour >= 0 && hour <= 23)
        day.toLowerCase() match {
          case "monday"    => Full(Weekly(DateTimeConstants.MONDAY,hour,min))
          case "tuesday"   => Full(Weekly(DateTimeConstants.TUESDAY,hour,min))
          case "wednesday" => Full(Weekly(DateTimeConstants.WEDNESDAY,hour,min))
          case "thursday"  => Full(Weekly(DateTimeConstants.THURSDAY,hour,min))
          case "friday"    => Full(Weekly(DateTimeConstants.FRIDAY,hour,min))
          case "saturday"  => Full(Weekly(DateTimeConstants.SATURDAY,hour,min))
          case "sunday"    => Full(Weekly(DateTimeConstants.SUNDAY,hour,min))
          case _           => Failure("%s is not correctly set, value is %s".format(dayParam,day))
      }
      else
        Failure("%s is not correctly set, value is %d, should be in [0-23]".format(hourParam,hour))
    else
      Failure("%s is not correctly set, value is %d, should be in [0-59]".format(minParam,min))
  }
}

trait CleanFrequency {

  def check(date:DateTime):Boolean = {
    val target = checker(date)
    target.equals(date)
  }

  def checker(now: DateTime):DateTime

  def next:DateTime

}

case class Hourly(min:Int) extends CleanFrequency{

  def checker(date:DateTime):DateTime = date.withMinuteOfHour(min)

  def next:DateTime = {
    val now = DateTime.now()
    if (now.isBefore(checker(now)))
      checker(now)
    else
      checker(now).plusHours(1)
  }

  override def toString:String = "Every hour past %d minutes".format(min)

}

case class Daily(hour:Int,min:Int) extends CleanFrequency{

  def checker(date:DateTime):DateTime = date.withMinuteOfHour(min).withHourOfDay(hour)

  def next:DateTime = {
    val now = DateTime.now()
    if (now.isBefore(checker(now)))
      checker(now)
    else
      checker(now).plusDays(1)
  }

  override def toString:String = "Every day at %d:%d".format(hour,min)

}

case class Weekly(day:Int,hour:Int,min:Int) extends CleanFrequency{

  def checker(date:DateTime):DateTime = date.withMinuteOfHour(min).withHourOfDay(hour).withDayOfWeek(day)

  def next:DateTime = {
    val now = DateTime.now()
    if (now.isBefore(checker(now)))
      checker(now)
    else
      checker(now).plusWeeks(1)
  }


  override def toString:String = {
    val res = day match {
      case DateTimeConstants.MONDAY    => "Monday"
      case DateTimeConstants.TUESDAY   => "Tuesday"
      case DateTimeConstants.WEDNESDAY => "Wednesday"
      case DateTimeConstants.THURSDAY  => "Thursday"
      case DateTimeConstants.FRIDAY    => "Friday"
      case DateTimeConstants.SATURDAY  => "Saturday"
      case DateTimeConstants.SUNDAY    => "Sunday"
    }
    "Every %s at %d:%d".format(res,hour,min)
  }

}

//states into which the cleaner process can be
sealed trait CleanerState
//the process is idle
case object IdleCleaner extends CleanerState
//an update is currently cleaning the databases
case object ActiveCleaner extends CleanerState

//Messages the cleaner can receive
// Ask to clean database (need to be in active state)
case class CleanDatabase
// Ask to check if cleaning has to be launched (need to be in idle state)
case class CheckLaunch

/**
 * A class that periodically check if the Database has to be cleaned.
 *
 * for now, Archive and delete run at same frequency
 * age before archive and delete are expressed in days
 * negative or zero age means to not run the dbcleaner
 * Archive don't run if it ttl is more than Delete one
 */
case class AutomaticDatabaseCleaning(
  dbManager      : DatabaseManager
  , deletettl      : Int // in days
  , archivettl     : Int // in days
  , freq : CleanFrequency
) extends Loggable {
  override val logger = ReportLogger
  //check if automatic reports archiving has to be started
  if(archivettl < 1) {
    val propertyName = "rudder.batch.techniqueLibrary.updateInterval"
    logger.info("Disable automatic database archive sinces property %s is 0 or negative".format(propertyName))
  } else {
    // don't launch automatic report archiving if reports would have already been deleted by automatic reports deleting
    if ((archivettl < deletettl ) && (deletettl > 0)) {
      logger.trace("***** starting Automatic Archive Reports batch *****")
      (new LADatabaseCleaner(dbManager.archiveEntries,archivettl,"archiv")) ! CheckLaunch
    }
    else
      logger.info("Disable automatic archive since archive maximum age is older than delete maximum age")
  }

  if(deletettl < 1) {
    val propertyName = "rudder.batch.techniqueLibrary.updateInterval"
    logger.info("Disable automatic database deletion sinces property %s is 0 or negative".format(propertyName))
  } else {
    logger.trace("***** starting Automatic Delete Reports batch *****")
    (new LADatabaseCleaner(dbManager.deleteEntries,deletettl,"delet")) ! CheckLaunch
  }


  ////////////////////////////////////////////////////////////////
  //////////////////// implementation details ////////////////////
  ////////////////////////////////////////////////////////////////

  private class LADatabaseCleaner(cleanaction:(DateTime)=> Box[Int],ttl:Int,action:String) extends LiftActor with Loggable {
    updateManager =>

    override val logger = ReportLogger
    private var currentState: CleanerState = IdleCleaner
    private var lastRun: DateTime = DateTime.now()

    override protected def messageHandler = {
      /*
       * Ask to check if need to be launched
       * If idle   => check
       * If active => do nothing
       * always register to LAPinger
       */
      case CheckLaunch =>
        //schedule next check, every minute
        LAPinger.schedule(this, CheckLaunch, 1000L*60)
        currentState match {

          case IdleCleaner =>

            logger.trace("***** Check launch *****")
            if(freq.check(DateTime.now)){
              logger.trace("***** Automatic %ser entering in active State *****".format(action))
              currentState = ActiveCleaner
              (this) ! CleanDatabase
            }

          case ActiveCleaner => ()

        }
      /*
       * Ask to clean Database
       * If idle   => do nothing
       * If active => clean database
       */
      case CleanDatabase =>
        currentState match {

          case ActiveCleaner =>
            val now = DateTime.now
            logger.trace("***** %se Database *****".format(action))
            logger.info("Automatic start %sing".format(action))
            val res = cleanaction(now.minusDays(ttl))
            res match {
              case eb:EmptyBox =>
                // Error while cleaning, should launch again
                logger.error("Error while processing database %sing %s ".format(action,eb))
                logger.error("Relaunching automatic %sing process".format(action))
                (this) ! CleanDatabase
              case Full(res) =>
                if (res==0)
                  logger.info("Automatic reports %ser has nothing to %se".format(action,action))
                else
                  logger.info("Automatic reports %ser has %sed %d reports".format(action,action,res))
                lastRun=DateTime.now
                currentState = IdleCleaner
            }


          case IdleCleaner => ()
        }

      case _ =>
        logger.error("Wrong message for automatic database cleaner ")

    }
  }
}