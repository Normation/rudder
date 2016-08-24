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

package com.normation.rudder.web.snippet.administration

import net.liftweb._
import http._
import common._
import util.Helpers._
import js._
import JsCmds._
import JE._
import scala.xml.NodeSeq
import collection.mutable.Buffer
import com.normation.utils.NetUtils.isValidNetwork
import com.normation.rudder.domain.Constants
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.services.policies.nodeconfig.NodeConfigurationService
import com.normation.rudder.batch.{AsyncDeploymentAgent,AutomaticStartDeployment}
import com.normation.rudder.domain.eventlog.ClearCacheEventLog
import com.normation.eventlog.EventLogDetails
import com.normation.eventlog.EventLog
import com.normation.rudder.repository.EventLogRepository
import com.normation.eventlog.ModificationId
import com.normation.utils.StringUuidGenerator
import bootstrap.liftweb.RudderConfig


class ClearCache extends DispatchSnippet with Loggable {

  private[this] val nodeConfigurationService = RudderConfig.nodeConfigurationService
  private[this] val asyncDeploymentAgent     = RudderConfig.asyncDeploymentAgent
  private[this] val eventLogRepository       = RudderConfig.eventLogRepository
  private[this] val uuidGen                  = RudderConfig.stringUuidGenerator
  private[this] val clearableCache           = RudderConfig.clearableCache

  def dispatch = {
    case "render" => clearCache
  }


  def clearCache : IdMemoizeTransform = SHtml.idMemoize { outerXml =>

    // our process method returns a
    // JsCmd which will be sent back to the browser
    // as part of the response
    def process(): JsCmd = {
      //clear errors
      S.clearCurrentNotices

      val modId = ModificationId(uuidGen.newUuid)

      //clear agentRun cache
      clearableCache.foreach { _.clearCache }

      //clear node configuration cache
      nodeConfigurationService.deleteAllNodeConfigurations match {
        case empty:EmptyBox =>
          val e = empty ?~! "Error while clearing caches"
          S.error(e.messageChain)
        case Full(set) =>
          eventLogRepository.saveEventLog(modId,
              ClearCacheEventLog(
                EventLogDetails(
                    modificationId = Some(modId)
                  , principal = CurrentUser.getActor
                  , details = EventLog.emptyDetails
                  , reason = None))) match {
            case eb:EmptyBox =>
              val e = eb ?~! "Error when logging the cache event"
              logger.error(e.messageChain)
              logger.debug(e.exceptionChain)
            case _ => //ok
          }
          logger.debug("Deleting node configurations on user clear cache request")
          asyncDeploymentAgent ! AutomaticStartDeployment(modId, CurrentUser.getActor)
          S.notice("clearCacheNotice","Caches were successfully cleared")
      }

      Replace("clearCacheForm", outerXml.applyAgain)
    }

    //process the list of networks
    "#clearCacheButton" #> {
      SHtml.ajaxSubmit("Clear caches", process _) ++ Script(OnLoad(JsRaw(""" correctButtons(); """)))
    }
  }
}
