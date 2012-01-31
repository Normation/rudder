/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

import com.normation.cfclerk.services.UpdatePolicyTemplateLibrary
import com.normation.rudder.domain.Constants.PTLIB_MINIMUM_UPDATE_INTERVAL
import net.liftweb.actor.{LiftActor, LAPinger}
import net.liftweb.common.Loggable
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.log.RudderEventActor

case class StartLibUpdate(actor: EventActor)

/**
 * A class that periodically check if PTLib was updated.
 * 
 * updateInterval has a special semantic:
 * - for 0 or a negative value, does not start the updater
 * - for updateInterval between 1 and a minimum value, use the minimum value
 * - else, use the given value. 
 */
class CheckPolicyTemplateLibrary(
    policyPackageUpdater: UpdatePolicyTemplateLibrary
  , asyncDeploymentAgent: AsyncDeploymentAgent
  , updateInterval      : Int // in secondes
) extends Loggable {
  
  private val propertyName = "rudder.batch.ptlib.updateInterval"
   
  //start batch
  if(updateInterval < 1) {
    logger.info("Disable dynamic group updates sinces property %s is 0 or negative".format(propertyName))
  } else {
    logger.trace("***** starting PT library Update batch *****")
    (new LAUpdatePTLibManager) ! StartLibUpdate(RudderEventActor)
  }

  ////////////////////////////////////////////////////////////////
  //////////////////// implementation details ////////////////////
  ////////////////////////////////////////////////////////////////
  
  private class LAUpdatePTLibManager extends LiftActor with Loggable {
    updateManager => 
        
    private[this] var realUpdateInterval = {
      if(updateInterval < PTLIB_MINIMUM_UPDATE_INTERVAL) {
        logger.warn("Value '%s' for %s is too small, using '%s'".format(
           updateInterval, propertyName, PTLIB_MINIMUM_UPDATE_INTERVAL 
        ))
        PTLIB_MINIMUM_UPDATE_INTERVAL
      } else {
        updateInterval
      }
    }
    
    override protected def messageHandler = {
      //
      //Ask for a new dynamic group update
      //
      case StartLibUpdate(actor) => 
        //schedule next update, in minutes
        LAPinger.schedule(this, StartLibUpdate, realUpdateInterval*1000)      
        logger.trace("***** Start a new update")
        policyPackageUpdater.update(actor)
      case _ => 
        logger.error("Ignoring start update dynamic group request because one other update still processing".format())
    }
  }
}

