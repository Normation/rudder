/*
*************************************************************************************
* Copyright 2017 Normation SAS
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

import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.services.nodes.NodeInfoServiceCachedImpl
import com.normation.utils.StringUuidGenerator
import monix.execution.Scheduler.{ global => scheduler }
import scala.concurrent.duration._
import com.normation.rudder.domain.logger.ScheduledJobLogger

import com.normation.zio._

/**
 * A naive scheduler which checks every N seconds if inventories are updated.
 * If so, I will trigger a promise generation.
 */
class CheckInventoryUpdate(
    nodeInfoCacheImpl   : NodeInfoServiceCachedImpl
  , asyncDeploymentAgent: AsyncDeploymentActor
  , uuidGen             : StringUuidGenerator
  , updateInterval      : FiniteDuration
) {

  val logger = ScheduledJobLogger

  //start batch
  if(updateInterval < 1.second) {
    logger.info(s"Disable automatic check for node inventories main information updates (update interval less than 1s)")
  } else {
    logger.trace(s"***** starting check of node main inventories information update to trigger policy generation, every ${updateInterval.toString()} *****")
  }

  scheduler.scheduleWithFixedDelay(30.second , updateInterval) {
    if(!nodeInfoCacheImpl.isUpToDate().runNow) {
      logger.info("Update in node inventories main information detected: triggering a policy generation")
      asyncDeploymentAgent ! ManualStartDeployment(ModificationId(uuidGen.newUuid), RudderEventActor, "Main inventory information of at least one node were updated")
    } else {
      logger.trace("No update in node inventories main information detected")
    }
  }
}

