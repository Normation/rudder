package com.normation.rudder.services

import com.normation.eventlog.{EventActor, EventLog, EventLogDetails, ModificationId}
import com.normation.rudder.repository.CachedRepository
import com.normation.rudder.batch.{AsyncDeploymentActor, AutomaticStartDeployment}
import com.normation.rudder.domain.eventlog.ClearCacheEventLog
import net.liftweb.common.{EmptyBox, Full, Loggable}
import net.liftweb.http.S
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.services.policies.nodeconfig.NodeConfigurationHashRepository
import com.normation.utils.StringUuidGenerator
import net.liftweb.common.Box

import com.normation.box._

trait ClearCacheService {

  /*
  * This method only clear the "node configuration" cache. That will
  * force a full regeneration of all policies
  */
 def clearNodeConfigurationCache(storeEvent: Boolean, actor: EventActor) : Box[Unit]

  /*
  * This method clear all caches, which are:
  * - node configurations (force full regen)
  * - cachedAgentRunRepository
  * - recentChangesService
  * - reportingServiceImpl
  * - nodeInfoServiceImpl
  */
 def action(actor: EventActor) : Box[String]
}

class ClearCacheServiceImpl(
      nodeConfigurationService : NodeConfigurationHashRepository
    , asyncDeploymentAgent     : AsyncDeploymentActor
    , eventLogRepository       : EventLogRepository
    , uuidGen                  : StringUuidGenerator
    , clearableCache           : Seq[CachedRepository]
) extends ClearCacheService with Loggable {

  def clearNodeConfigurationCache(storeEvent: Boolean = true, actor: EventActor) = {
    nodeConfigurationService.deleteAllNodeConfigurations match {
      case eb: EmptyBox =>
        (eb ?~! "Error while clearing node configuration cache")
      case Full(set) =>
        if (storeEvent) {
          val modId = ModificationId(uuidGen.newUuid)
          eventLogRepository.saveEventLog(
            modId
            , ClearCacheEventLog(
              EventLogDetails(
                modificationId = Some(modId)
                , principal = actor
                , details = EventLog.emptyDetails
                , reason = Some("Node configuration cache deleted on user request")
              )
            )
          ) match {
            case eb: EmptyBox =>
              val e = eb ?~! "Error when logging the cache event"
              logger.error(e.messageChain)
              logger.debug(e.exceptionChain)
            case _ => //ok
          }
          logger.debug("Deleting node configurations on user clear cache request")
          asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)
        }
        Full(set)
    }
  }

  def action(actor: EventActor) = {

    S.clearCurrentNotices
    val modId = ModificationId(uuidGen.newUuid)

    //clear agentRun cache
    clearableCache.foreach {_.clearCache}

    //clear node configuration cache
    (for {
      set <- clearNodeConfigurationCache(storeEvent = false, actor)
      _   <-  eventLogRepository.saveEventLog(
                 modId
               , ClearCacheEventLog(
                   EventLogDetails(
                       modificationId = Some(modId)
                     , principal = actor
                     , details = EventLog.emptyDetails
                     , reason = Some("Clearing cache for: node configuration, recent changes, compliance and node info at user request")
                   )
                 )
               ).toBox
    } yield {
      set
    }) match {
      case eb: EmptyBox =>
        val e = eb ?~! "Error when clearing caches"
        logger.error(e.messageChain)
        logger.debug(e.exceptionChain)
        e
      case Full(set) => //ok
        logger.debug("Deleting node configurations on user clear cache request")
        asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)
        Full("ok")
    }
  }
}
