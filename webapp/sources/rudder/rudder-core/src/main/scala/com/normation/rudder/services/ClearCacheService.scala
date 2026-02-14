package com.normation.rudder.services

import com.normation.errors.Chained
import com.normation.errors.IOResult
import com.normation.eventlog.EventActor
import com.normation.eventlog.EventLog
import com.normation.eventlog.EventLogDetails
import com.normation.eventlog.ModificationId
import com.normation.rudder.batch.AsyncDeploymentActor
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.batch.ManualStartDeployment
import com.normation.rudder.domain.eventlog.ClearCacheEventLog
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.repository.CachedRepository
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.services.policies.nodeconfig.NodeConfigurationHashRepository
import com.normation.utils.StringUuidGenerator
import net.liftweb.http.S
import zio.*
import zio.syntax.*

trait ClearCacheService {

  /*
   * This method only clear the "node configuration" cache. That will
   * force a full regeneration of all policies
   */
  def clearNodeConfigurationCache(storeEvent: Boolean, actor: EventActor): IOResult[Unit]

  /*
   * This method clear all caches, which are:
   * - node configurations (force full regen)
   * - cachedAgentRunRepository
   * - recentChangesService
   * - reportingServiceImpl
   */
  def action(actor: EventActor): IOResult[Unit]
}

class ClearCacheServiceImpl(
    nodeConfigurationService: NodeConfigurationHashRepository,
    asyncDeploymentAgent:     AsyncDeploymentActor,
    eventLogRepository:       EventLogRepository,
    uuidGen:                  StringUuidGenerator,
    clearableCache:           Seq[CachedRepository]
) extends ClearCacheService {

  override def clearNodeConfigurationCache(storeEvent: Boolean = true, actor: EventActor): IOResult[Unit] = {
    for {
      _ <- nodeConfigurationService.deleteAllNodeConfigurations().chainError("Error while clearing node configuration cache")
      _ <- ZIO.when(storeEvent) {
             val modId = ModificationId(uuidGen.newUuid)
             for {
               _ <- eventLogRepository
                      .saveEventLog(
                        modId,
                        ClearCacheEventLog(
                          EventLogDetails(
                            modificationId = Some(modId),
                            principal = actor,
                            details = EventLog.emptyDetails,
                            reason = Some("Node configuration cache deleted on user request")
                          )
                        )
                      )
                      .tapError(err => ApplicationLoggerPure.error(s"Error when logging cache event: ${err.fullMsg}"))
               _ <- ApplicationLoggerPure.debug("Deleting node configurations on user clear cache request")
               _ <- IOResult.attempt(
                      asyncDeploymentAgent ! ManualStartDeployment(
                        modId,
                        actor,
                        "Trigger policy generation after clearing configuration cache"
                      )
                    )
             } yield ()
           }

    } yield ()
  }

  override def action(actor: EventActor): IOResult[Unit] = {
    val modId = ModificationId(uuidGen.newUuid)

    // clear node configuration cache
    (for {
      _ <- IOResult.attempt(S.clearCurrentNotices)
      // clear agentRun cache
      _ <- IOResult.attempt(clearableCache.foreach(_.clearCache()))
      _ <- clearNodeConfigurationCache(storeEvent = false, actor)
      _ <- eventLogRepository.saveEventLog(
             modId,
             ClearCacheEventLog(
               EventLogDetails(
                 modificationId = Some(modId),
                 principal = actor,
                 details = EventLog.emptyDetails,
                 reason = Some("Clearing cache for: node configuration, recent changes, compliance and node info at user request")
               )
             )
           )
      _ <- ApplicationLoggerPure.debug("Deleting node configurations on user clear cache request")
      _ <- IOResult.attempt(asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor))
    } yield ()).catchAll { f =>
      val e = Chained("Error when clearing caches", f)
      ApplicationLoggerPure.error(e.fullMsg) *> e.fail
    }
  }
}
