/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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

package bootstrap.liftweb.checks.endconfig.migration

import bootstrap.liftweb.BootstrapChecks
import bootstrap.liftweb.BootstrapLogger
import com.normation.errors
import com.normation.errors.IOResult
import com.normation.eventlog.EventActor
import com.normation.inventory.domain.FullInventory
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.logger.MigrationLoggerPure
import com.normation.rudder.facts.nodes.NodeFact
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.services.nodes.history.HistoryLogRepository
import com.normation.rudder.services.nodes.history.impl.FactLog
import com.normation.rudder.services.nodes.history.impl.FactLogData
import com.normation.rudder.services.nodes.history.impl.InventoryHistoryDelete
import com.normation.rudder.services.nodes.history.impl.InventoryHistoryLogRepository
import com.normation.rudder.tenants.QueryContext
import com.normation.zio.*
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import zio.*

/*
 * Before Rudder 8.0, we used to save the state of node when accepted in an LDIF file under
 *   /var/rudder/inventories/historical/${nodeid}/${iso-date-time-of-acceptation}
 * Since 8.0, we store them in the nodefacts tables, which is created in the process.
 * Ths migration is convergent and asynchronous:
 * - it does not block boot
 * - it can be interrupted and restarted afterward
 * During migration, user won't be able to access the history inventory of the node.
 *
 * The migration is also a good time to clean-up that directory, and actually an empty directory will signal
 * end of migration:
 * - for each directory under `/var/node/historical/`,
 * - if the node still exist OR if the event is less than KEEP_REFUSED_DURATION, add it in the base else nothing
 * - delete the directory
 */
class MigrateNodeAcceptationInventories(
    nodeFactRepo:      NodeFactRepository,
    fileLogRepository: InventoryHistoryLogRepository,
    jdbcLogRepository: HistoryLogRepository[NodeId, DateTime, FactLogData, FactLog] & InventoryHistoryDelete,
    MAX_KEEP_REFUSED:  Duration
) extends BootstrapChecks {

  val msg = "old inventory accept/refuse facts to 'NodeFacts' database table"

  val migrationActor: EventActor = EventActor("rudder-migration")

  override def description: String =
    "Check if data from /var/rudder/inventories/historical are migrated"

  /*
   * Save a full inventory as a node fact in postgresql.
   */
  def saveInDB(id: NodeId, date: DateTime, data: FullInventory, deleted: Boolean): ZIO[Any, errors.RudderError, Option[Unit]] = {
    jdbcLogRepository.save(
      id,
      FactLogData(NodeFact.newFromFullInventory(data, None), migrationActor, data.node.main.status),
      date
    ) *> ZIO.when(deleted) {
      jdbcLogRepository.saveDeleteEvent(id, DateTime.now(DateTimeZone.UTC), migrationActor)
    }
  }

  /*
   * We got the list of all available ids in the fileLog.
   * For each one:
   * - check if the node exists.
   *    - if not,
   *        - and if the node log is more than MAX_KEEP_REFUSED, do nothing
   *        - if younger, then migrate it and then dele
   *   - if exists, then migrate
   * - in all cases, delete the node log directory
   * => migrate if node exists or history younger than MAX_KEEP_REFUSED
   */
  def migrateOne(now: DateTime)(nodeId: NodeId): IOResult[Unit] = {
    def purgeLogFile(nodeid: NodeId): UIO[Unit] = {
      (for {
        f <- fileLogRepository.getFile(nodeId)
        _ <- IOResult.attempt(FileUtils.deleteDirectory(f))
      } yield ()).catchAll(err => {
        MigrationLoggerPure.error(
          s"Error when purging file-based node inventory snapshot of accept/refuse event for '${nodeId.value}': ${err.fullMsg}"
        )
      })
    }

    fileLogRepository.versions(nodeId).flatMap {
      _.headOption match {
        case None    => ZIO.unit
        case Some(v) =>
          fileLogRepository.get(nodeId, v).flatMap {
            case None    => ZIO.unit
            case Some(l) =>
              for {
                opt <- nodeFactRepo.get(nodeId)(using QueryContext.systemQC)
                _   <- ZIO.when(opt.isDefined || l.datetime.plus(MAX_KEEP_REFUSED.toMillis).isAfter(now)) {
                         saveInDB(nodeId, l.datetime, l.data, !opt.isDefined)
                       }
              } yield ()
          }
      }
    } *> purgeLogFile(nodeId)
  }

  def migrateAll(now: DateTime): ZIO[Any, errors.RudderError, Unit] = {
    for {
      ids <- fileLogRepository.getIds
      _   <- BootstrapLogger.info(s"Migrating '${ids.size}' ${msg}")
      _   <- ZIO.foreach(ids)(migrateOne(now))
      _   <- BootstrapLogger.info(s"Migration of old accept/refuse facts done")
    } yield ()
  }

  override def checks(): Unit = {
    val prog = {
      for {
        _ <- migrateAll(DateTime.now(DateTimeZone.UTC))
      } yield ()
    }

    // Actually run the migration async to avoid blocking for that.
    // There is no need to have it sync.
    prog.catchAll(err => BootstrapLogger.error(s"Error when trying to migrate ${msg}: ${err.fullMsg}")).forkDaemon.runNow
  }

}
