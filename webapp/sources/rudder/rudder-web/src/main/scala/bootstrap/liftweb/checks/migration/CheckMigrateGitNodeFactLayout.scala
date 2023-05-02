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

package bootstrap.liftweb.checks.migration

import better.files.File
import bootstrap.liftweb.BootstrapChecks
import bootstrap.liftweb.BootstrapLogger
import com.normation.errors.IOResult
import com.normation.inventory.domain.AcceptedInventory
import com.normation.inventory.domain.InventoryDataLogger
import com.normation.inventory.domain.PendingInventory
import com.normation.rudder.facts.nodes.GitNodeFactRepositoryImpl
import com.normation.zio._
import zio._

/*
 * This class handles the migration of the layout of git repository for node facts.
 * It adds in level of subdirectories under /pending and /accepted with the first letter
 * of the node UUIDs so that to spread nodes on them, dividing by 16 the max number of nodes in any
 * given directories.
 * So for a target of 30k nodes, we expect less than max 2k nodes in a directory, which remains a
 * number which can be handled by ls and other command line tools (even with 30k, we are in the realm
 * of digits where performance is not a problem for Git or Linux filesystem).
 *
 * The NodeFactRepository must not be started before that migration is done, so we have a promise
 * to guard it.
 */
class CheckMigrateGitNodeFactLayout(
    gitFactRepo:         GitNodeFactRepositoryImpl,
    nodeFactLayoutGuard: Promise[Nothing, Unit]
) extends BootstrapChecks {

  override def description: String = "Check if node fact git repository has the layout with intermediate directories"

  override def checks(): Unit = {
    val statusNames = List(PendingInventory.name, AcceptedInventory.name)

    // don't use gitRepo.getItemDirectory since it creates the base dir
    val baseNodesFile = gitFactRepo.gitRepo.rootDirectory / gitFactRepo.relativePath

    val migration = IOResult
      .attempt(baseNodesFile.exists)
      .flatMap(if (_) {
        // in the case of a migration or existing instance:
        // - in all cases check for the directories and/or create them
        // - then look for any json directly in /nodes/curentStatus and migrate
        BootstrapLogger.debug(s"Node fact repository directories exist, looking for json file to migrate") *>
        ZIO.foreach(statusNames) { status =>
          ZIO.foreach(gitFactRepo.shardingDirNames) { d =>
            IOResult.attempt((baseNodesFile / status / d.toString).createDirectoryIfNotExists())
          } *> migrateNodes(baseNodesFile / status, gitFactRepo.shardingDirNames)
        } *> commit()
      } else { // if the directory .../nodes does not exists, then we are in a new instance, ok
        BootstrapLogger.debug(s"Node fact repository directories don't exist yet, no migration needed") *>
        ZIO.unit
      }) *> nodeFactLayoutGuard.succeed(())

    migration.unit.runNow
  }

  /*
   * Commit changes if any
   */
  private[migration] def commit(): IOResult[Unit] = {
    IOResult
      .attempt(gitFactRepo.gitRepo.git.status().call())
      .flatMap(s => {
        if (s.isClean) {
          ZIO.unit
        } else {
          gitFactRepo.gitRepo.semaphore.withPermit(
            IOResult.attempt(
              gitFactRepo.gitRepo.git
                .commit()
                .setCommitter(gitFactRepo.committer)
                .setMessage("Migration to the new node fact repository layout with intermediate directories for sharding")
            )
          )
        }
      })
  }

  /*
   * Migrate nodeFacts in baseFile into their subdirectories.
   * We are only migrating files whose name is UUID.json, other, like root.json, can remain
   * directly under accepted.
   *
   * We only commit ONE TIME for all nodes in a given status.
   */
  private[migration] def migrateNodes(baseDir: File, midDirectoryNames: Seq[Char]): IOResult[Unit] = {
    BootstrapLogger.debug(s"Processing files in ${baseDir.pathAsString}") *>
    IOResult.attempt {
      baseDir.collectChildren(
        f =>
          f.extension(includeDot = true, includeAll = true) == Some(".json") && midDirectoryNames.contains(
            f.name.head
          ),
        1
      )
    }
      .map(_.toSeq) // the toSeq is important here, nio stream and ZIO don't fit together. We would need a ZIO stream
      .flatMap { nodes =>
        if (nodes.size <= 0) ZIO.unit
        else {
          for {
            _ <-
              BootstrapLogger.debug(s"Moving ${nodes.size} files to new node fact repository layout in ${baseDir.pathAsString}")
            _ <- ZIO
                   .foreach(nodes) { n =>
                     val mid  = n.name.head.toLower
                     val dest = baseDir / mid.toString / n.name
                     if (midDirectoryNames.contains(mid)) {

                       // move file, update git, don't commit yet
                       InventoryDataLogger.debug(s"Move inventory file from ${n.pathAsString} to ${dest.pathAsString}") *>
                       IOResult.attempt {
                         n.moveTo(dest)(File.CopyOptions(overwrite = true)) // not sure, perhaps we would like to ignore the case where a node is already in dest ?
                         gitFactRepo.gitRepo.git.rm().addFilepattern(gitFactRepo.toGitPath(n.toJava)).call()
                         gitFactRepo.gitRepo.git.add().addFilepattern(gitFactRepo.toGitPath(dest.toJava)).call()
                       }
                     } else {
                       ZIO.unit
                     }
                   }
                   .unit
          } yield ()
        }
      }
  }
}
