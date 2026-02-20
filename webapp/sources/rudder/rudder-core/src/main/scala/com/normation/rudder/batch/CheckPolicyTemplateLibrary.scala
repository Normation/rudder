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

package com.normation.rudder.batch

import com.normation.cfclerk.services.UpdateTechniqueLibrary
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.Constants.TECHLIB_MINIMUM_UPDATE_INTERVAL
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.domain.logger.ScheduledJobLogger
import com.normation.rudder.ncf.ReadEditorTechniqueCheckResult
import com.normation.utils.StringUuidGenerator
import com.normation.zio.UnsafeRun
import net.liftweb.actor.LAPinger
import net.liftweb.actor.SpecializedLiftActor
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.ISODateTimeFormat

final case class StartLibUpdate(actor: EventActor)

/**
 * A class that periodically check if the Technique Library was updated.
 *
 * updateInterval has a special semantic:
 * - for 0 or a negative value, does not start the updater
 * - for updateInterval between 1 and a minimum value, use the minimum value
 * - else, use the given value.
 */
class CheckTechniqueLibrary(
    policyPackageUpdater:     UpdateTechniqueLibrary,
    readTechniqueCheckResult: ReadEditorTechniqueCheckResult,
    uuidGen:                  StringUuidGenerator,
    updateInterval:           Int // in minutes
) {

  private val propertyName = "rudder.batch.techniqueLibrary.updateInterval"
  private val logger       = ScheduledJobLogger

  // start batch
  if (updateInterval < 1) {
    logger.info(s"Disable automatic Technique library updates since property ${propertyName} is 0 or negative")
  } else {
    logger.trace("***** starting Technique Library Update batch *****")
    val actor = new LAUpdateTechLibManager
    // Do not run at start up, as it may have been done in startup check already. Only register next run
    LAPinger.schedule(actor, StartLibUpdate(RudderEventActor), updateInterval * 1000L * 60)
  }

  ////////////////////////////////////////////////////////////////
  //////////////////// implementation details ////////////////////
  ////////////////////////////////////////////////////////////////

  private class LAUpdateTechLibManager extends SpecializedLiftActor[StartLibUpdate] {
    updateManager =>

    val logger = ScheduledJobLogger

    private val isAutomatic        = updateInterval > 0
    private val realUpdateInterval = {
      if (updateInterval < TECHLIB_MINIMUM_UPDATE_INTERVAL && isAutomatic) {
        logger.warn(s"Value '${updateInterval}' for ${propertyName} is too small, using '${TECHLIB_MINIMUM_UPDATE_INTERVAL}'")
        TECHLIB_MINIMUM_UPDATE_INTERVAL
      } else {
        updateInterval
      }
    }

    override protected def messageHandler: PartialFunction[StartLibUpdate, Unit] = {
      //
      // Ask for a new dynamic group update
      //
      case StartLibUpdate(actor) =>
        // schedule next update if it's automatic, in minutes
        if (isAutomatic) {
          LAPinger.schedule(this, StartLibUpdate(actor), realUpdateInterval * 1000L * 60)
        } // don't need an else part : we have to do nothing and the else return Unit

        logger.trace("***** Start a new update")
        policyPackageUpdater.update(
          ModificationId(uuidGen.newUuid),
          actor,
          Some(s"Automatic batch update at ${DateTime.now(DateTimeZone.UTC).toString(ISODateTimeFormat.basicDateTime())}")
        ) match {
          case Full(t) =>
            logger.trace(s"***** udpate successful for ${t.size} techniques")
            // Update techniques compilation status even if there are no updated techniques : compilation status may have been updated
            readTechniqueCheckResult.get().runNow
          case eb: EmptyBox =>
            val msg = (eb ?~! ("An error occured while updating")).messageChain
            logger.warn(
              s"Techniques library reload failed and will be retrying in ${updateInterval} minutes, cause is: ${msg}"
            )
        }

    }
  }
}
