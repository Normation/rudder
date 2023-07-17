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

package com.normation.rudder.inventory

import better.files.File
import com.normation.box.IOManaged
import com.normation.errors._
import com.normation.errors.Chained
import com.normation.errors.IOResult
import com.normation.inventory.domain.CertifiedKey
import com.normation.inventory.domain.Inventory
import com.normation.inventory.domain.InventoryProcessingLogger
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.SecurityToken
import com.normation.inventory.services.provisioning.InventoryDigestServiceV1
import com.normation.inventory.services.provisioning.InventoryParser
import com.normation.inventory.services.provisioning.InventorySaver
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.hooks.HookEnvPairs
import com.normation.rudder.hooks.PureHooksLogger
import com.normation.rudder.hooks.RunHooks
import com.normation.utils.DateFormaterService
import com.normation.zio._
import com.normation.zio.ZioRuntime
import java.io.InputStream
import java.nio.file.NoSuchFileException
import java.security.{PublicKey => JavaSecPubKey}
import org.joda.time.DateTime
import org.joda.time.Duration
import org.joda.time.format.PeriodFormat
import scala.annotation.nowarn
import zio._
import zio.syntax._

/*
 * The interface between whatever event and the actual saving process.
 * We only expose a blocking interface.
 */
trait ProcessInventoryService {
  def saveInventoryBlocking(info: InventoryPair): UIO[InventoryProcessStatus]
}

/*
 * Default implementation for the process inventory service is just
 * chaining the save and the move of inventory files
 */
class DefaultProcessInventoryService(
    inventoryProcessor: InventoryProcessor,
    inventoryMover:     InventoryMover
) extends ProcessInventoryService {

  override def saveInventoryBlocking(pair: InventoryPair): UIO[InventoryProcessStatus] = {
    val info = pair.toSaveInventoryInfo
    for {
      _ <- InventoryProcessingLogger.trace(s"Query blocking save of '${info.fileName}'")
      x <- inventoryProcessor.saveInventoryInternal(info)
      _ <- inventoryMover.moveFiles(pair.inventory, pair.signature, x)
      _ <- InventoryProcessingLogger.trace(s"Query blocking save of '${info.fileName}': done")
    } yield x
  }
}

/*
 * A pair of an inventory file and its signature.
 * It's the interface used by the ProcessInventoryService
 */
final case class InventoryPair(inventory: File, signature: File) {
  def toSaveInventoryInfo = SaveInventoryInfo(
    inventory.name,
    InventoryProcessingUtils.makeManagedStream(inventory, "inventory"),
    InventoryProcessingUtils.makeManagedStream(signature, "signature"),
    InventoryProcessingUtils.makeFileExists(inventory)
  )
}

/*
 * Data needed to save an inventory.
 * We need to use input streams because it's sometimes all
 * we have (in test for ex).
 */
final case class SaveInventoryInfo(
    fileName:  String,
    inventory: IOManaged[InputStream],
    signature: IOManaged[InputStream],
    exists:    IOManaged[Boolean]
)

sealed trait InventoryProcessStatus {
  def inventoryName: String
  def nodeId:        NodeId

}
object InventoryProcessStatus {
  final case class Saved(inventoryName: String, nodeId: NodeId)                         extends InventoryProcessStatus
  final case class SignatureInvalid(inventoryName: String, nodeId: NodeId)              extends InventoryProcessStatus
  final case class SaveError(inventoryName: String, nodeId: NodeId, error: RudderError) extends InventoryProcessStatus
}

object StatusLog {
  implicit class LogMessage(status: InventoryProcessStatus) {
    def msg: String = status match {
      case InventoryProcessStatus.SignatureInvalid(inventoryName, nodeId) =>
        s"Rejecting Inventory '${inventoryName}' for Node '${nodeId.value}' because the Inventory signature is " +
        s"not valid: the Inventory was not signed with the same agent key as the one saved within Rudder for that Node. If " +
        s"you updated the agent key on this node, you can update the key stored within Rudder with the https://docs.rudder.io/api/#api-Nodes-updateNode" +
        s"api (look for 'agentKey' property). The key path depends of your OS, on linux it's: '/var/rudder/cfengine-community/ppkeys/localhost.pub'. It is " +
        s"also contained in the <AGENT_CERT> value of inventory (you can extract public key with `openssl x509 -pubkey -noout -in - << EOF " +
        s"-----BEGIN CERTIFICATE----- .... -----END CERTIFICATE----- EOF`). " +
        s"If you did not change the key, please ensure that the node sending that inventory is actually the node registered " +
        s"within Rudder"

      case InventoryProcessStatus.Saved(inventoryName, nodeId) =>
        s"Inventory '${inventoryName}' for Node '${nodeId.value}' was correctly saved"

      case InventoryProcessStatus.SaveError(inventoryName, nodeId, err) =>
        s"Inventory '${inventoryName}' for Node '${nodeId.value}' failed to be saved in Rudder. Cause was: ${err.fullMsg}"
    }
  }
}

class InventoryProcessor(
    unmarshaller:    InventoryParser,
    inventorySaver:  InventorySaver[_],
    val maxParallel: Long,
    digestService:   InventoryDigestServiceV1,
    checkAliveLdap:  () => IOResult[Unit]
) {
  def logDirPerm(dir: File, name: String) = {
    if (dir.isDirectory && dir.isWritable) {
      InventoryProcessingLogger.logEffect.debug(s"${name} inventories directory [ok]: ${dir.pathAsString}")
    } else {
      InventoryProcessingLogger.logEffect.error(
        s"${name} inventories directory: ${dir.pathAsString} is not writable. Please check existence and file permission."
      )
    }
  }

  ApplicationLogger.info(s"Configure inventory processing with parallelism of '${maxParallel}'")

  // we want to limit the number of inventories concurrently parsed
  lazy val concurrentInventoryProcessingSemaphore = ZioRuntime.unsafeRun(Semaphore.make(maxParallel))

  /*
   * If blocking is true, that method won't complete until there is room in the backend queue.
   * Be careful to handle that case carefully with overflow strategy to avoid blocking
   * everything.
   * When non blocking, the return value will tell is the value was accepted.
   */
  def saveInventoryInternal(info: SaveInventoryInfo): UIO[InventoryProcessStatus] = {
    @nowarn("msg=a type was inferred to be `Any`")
    def saveWithSignature(
        inventory:          Inventory,
        publicKey:          JavaSecPubKey,
        newInventoryStream: IOManaged[InputStream],
        newSignature:       IOManaged[InputStream]
    ): IOResult[InventoryProcessStatus] = {
      ZIO.scoped(
        newInventoryStream.flatMap(inventoryStream => {
          ZIO.scoped(
            newSignature.flatMap(signatureStream => {
              for {
                digest  <- digestService.parse(signatureStream)
                checked <- digestService.check(publicKey, digest, inventoryStream)
                saved   <- if (checked) {
                             // Signature is valid, send it to save engine
                             // Set the keyStatus to Certified
                             // For now we set the status to certified since we want pending inventories to have their inventory signed
                             // When we will have a 'pending' status for keys we should set that value instead of certified
                             val certified =
                               inventory.copy(node = inventory.node.copyWithMain(main => main.copy(keyStatus = CertifiedKey)))
                             saveInventory(certified)
                           } else {
                             // Signature is not valid, reject inventory
                             InventoryProcessStatus.SignatureInvalid(inventory.name, inventory.node.main.id).succeed
                           }
              } yield {
                saved
              }
            })
          )
        })
      )
    }

    def parseSafe(newInventoryStream: IOManaged[InputStream], inventoryFileName: String): IOResult[Inventory] = {
      ZIO.scoped(
        newInventoryStream.flatMap(is => {
          for {
            r <- unmarshaller.fromXml(inventoryFileName, is)
          } yield {
            // use the provided file name as inventory name, else it's a generic one setted by fusion (like "inventory")
            r.copy(name = inventoryFileName)
          }
        })
      )
    }

    // actual inventory processing logic
    val processLogic = concurrentInventoryProcessingSemaphore.withPermit(for {
      start        <- currentTimeMillis
      _            <- InventoryProcessingLogger.debug(s"Enter pre-processed inventory for ${info.fileName}")
      inventory    <- (
                        InventoryProcessingLogger.debug(s"Start parsing inventory '${info.fileName}'") *>
                        parseSafe(info.inventory, info.fileName).chainError("Can't parse the input inventory, aborting") <*
                        InventoryProcessingLogger.trace(s"Parsing done for inventory '${info.fileName}'")
                      )
      secPair      <- digestService
                        .getKey(inventory)
                        .chainError(s"Error when trying to check inventory key for Node '${inventory.node.main.id.value}'")
      parsed       <- digestService.parseSecurityToken(secPair._1)
      _            <- parsed.subject match {
                        case None       => ZIO.unit
                        case Some(list) => SecurityToken.checkCertificateSubject(inventory.node.main.id, list)
                      }
      afterParsing <- currentTimeMillis
      inventoryName = inventory.name
      nodeId        = inventory.node.main.id
      _            <- InventoryProcessingLogger.debug(s"Inventory '${inventory.name}' parsed in ${PeriodFormat.getDefault
                          .print(new Duration(afterParsing, java.lang.System.currentTimeMillis).toPeriod)} ms, now saving")
      saved        <- saveWithSignature(inventory, parsed.publicKey, info.inventory, info.signature).chainError(
                        "Error when trying to check inventory signature"
                      )
      _            <- InventoryProcessingLogger.debug(
                        s"Inventory '${inventory.name}' for node '${inventory.node.main.id.value}' pre-processed in ${PeriodFormat.getDefault
                            .print(new Duration(start, java.lang.System.currentTimeMillis).toPeriod)} ms"
                      )
    } yield {
      saved
    })

    // guard against missing files: as there may be a latency between file added to buffer / file processed, we
    // need to check again here.
    ZIO
      .scoped[Any](
        info.exists.flatMap(exists => {
          for {
            res <- if (exists) processLogic
                   else {
                     InventoryProcessingLogger.trace(s"File '${info.fileName}' was deleted, skipping") *>
                     InventoryProcessStatus.Saved(info.fileName, NodeId("skipped")).succeed
                   }
          } yield res
        })
      )
      .catchAll(err => {
        val fail = Chained(s"Error when trying to process inventory '${info.fileName}'", err)
        InventoryProcessingLogger
          .error(fail.fullMsg) *> InventoryProcessStatus.SaveError(info.fileName, NodeId("unknown"), fail).succeed
      })
  }

  /**
   * Encapsulate the logic to finally save a new inventory in LDAP backend.
   */
  def saveInventory(inventory: Inventory): UIO[InventoryProcessStatus] = {
    checkAliveLdap().either.flatMap {
      case Left(err) =>
        val full =
          Chained(s"There is an error with the LDAP backend preventing acceptation of inventory '${inventory.name}'", err)
        InventoryProcessStatus.SaveError(inventory.name, inventory.node.main.id, full).succeed

      case Right(_) =>
        for {
          _     <- InventoryProcessingLogger.trace(
                     s"Start post processing of inventory '${inventory.name}' for node '${inventory.node.main.id.value}'"
                   )
          start <- currentTimeMillis
          saved <- inventorySaver.save(inventory).chainError("Can't merge inventory in LDAP directory, aborting").either
          res   <- saved match {
                     case Left(err) =>
                       InventoryProcessingLogger.error(s"Error when trying to process inventory: ${err.fullMsg}") *>
                       InventoryProcessStatus.SaveError(inventory.name, inventory.node.main.id, err).succeed
                     case Right(_)  =>
                       InventoryProcessingLogger.debug("Inventory saved.") *>
                       InventoryProcessStatus.Saved(inventory.name, inventory.node.main.id).succeed
                   }
          end   <- currentTimeMillis
          _     <-
            InventoryProcessingLogger.info(
              s"Inventory '${inventory.name}' for node '${inventory.node.main.hostname}' [${inventory.node.main.id.value}] (signature:${inventory.node.main.keyStatus.value}) " +
              s"processed in ${PeriodFormat.getDefault.print(new Duration(start, end).toPeriod)}"
            )
        } yield res
    }
  }

}

class InventoryFailedHook(
    HOOKS_D:               String,
    HOOKS_IGNORE_SUFFIXES: List[String]
) {
  import scala.jdk.CollectionConverters._

  def runHooks(file: File): UIO[Unit] = {
    (for {
      systemEnv <- IOResult.attempt(java.lang.System.getenv.asScala.toSeq).map(seq => HookEnvPairs.build(seq: _*))
      hooks     <- RunHooks.getHooksPure(HOOKS_D + "/node-inventory-received-failed", HOOKS_IGNORE_SUFFIXES)
      _         <- for {
                     timeHooks0 <- currentTimeMillis
                     res        <- RunHooks.asyncRun(
                                     hooks,
                                     HookEnvPairs.build(
                                       ("RUDDER_INVENTORY_PATH", file.pathAsString)
                                     ),
                                     systemEnv,
                                     1.minutes // warn if a hook took more than a minute
                                   )
                     timeHooks1 <- currentTimeMillis
                     _          <- PureHooksLogger.trace(s"Inventory failed hooks ran in ${timeHooks1 - timeHooks0} ms")
                   } yield ()
    } yield ()).catchAll(err => PureHooksLogger.error(err.fullMsg))
  }
}

/*
 * This part is in charge of moving inventory files once they are processed and
 * trigger hooks.
 */
trait InventoryMoveWhenProcessed {
  def moveFiles(inventory: File, signature: File, result: InventoryProcessStatus): UIO[Unit]
}

/*
 * This class managed what need to be done after inventories are saved
 * in LDAP
 */
class InventoryMover(
    receivedInventoryPath: String,
    failedInventoryPath:   String,
    failedHook:            InventoryFailedHook
) extends InventoryMoveWhenProcessed {

  val received = File(receivedInventoryPath)
  InventoryProcessingUtils.logDirPerm(received, "Received")
  val failed   = File(failedInventoryPath)
  InventoryProcessingUtils.logDirPerm(failed, "Failed")

  // we don't manage race condition very well, so we have cases where
  // we can have two things trying to move
  def safeMove[T](file: File, chunk: => T): UIO[Unit] = {
    ZIO.attempt { chunk; () }.catchAll {
      case ex: NoSuchFileException => // ignore
        InventoryProcessingLogger.debug(
          s"Ignored exception '${ex.getClass.getSimpleName} ${ex.getMessage}'. The file '${file.pathAsString}' was correctly handled."
        )
      case ex =>
        InventoryProcessingLogger.error(
          s"Exception caught when processing inventory file '${file.pathAsString}': ${ex.getClass.getSimpleName} ${ex.getMessage}"
        )
    }
  }

  /*
   * When the inventory failed, we want to write a log file with the error near it:
   * /var/rudder/inventories/failed/
   *  - nodeXXX-uuid.ocs
   *  - nodeXXX-uuid.ocs.reject-YYYYMMdd-HHmmss.log
   *  - nodeXXX-uuid.ocs.sign
   */
  def writeErrorLogFile(failedInventoryPath: File, result: InventoryProcessStatus): UIO[Unit] = {
    import com.normation.rudder.inventory.StatusLog._
    val date       = DateFormaterService.serialize(DateTime.now())
    val rejectPath = failedInventoryPath.pathAsString + s".reject-${date}.log"

    // this must never lead to a bubbling failure
    IOResult
      .attempt(File(rejectPath).writeText(s"""${date}
                                             |${result.msg.replaceAll("; cause was:", "\ncause was:")}\n""".stripMargin))
      .catchAll(err => InventoryProcessingLogger.error(s"Error when writing rejection log file ${rejectPath}: ${err.fullMsg}"))
      .unit
  }

  def moveFiles(inventory: File, signature: File, result: InventoryProcessStatus): UIO[Unit] = {
    result match {
      case InventoryProcessStatus.Saved(_, _)                                               =>
        // move to received dir
        safeMove(signature, signature.moveTo(received / signature.name)(File.CopyOptions(overwrite = true))) *>
        safeMove(inventory, inventory.moveTo(received / inventory.name)(File.CopyOptions(overwrite = true)))
      case _: InventoryProcessStatus.SignatureInvalid | _: InventoryProcessStatus.SaveError =>
        val failedName = failed / inventory.name
        safeMove(signature, signature.moveTo(failed / signature.name)(File.CopyOptions(overwrite = true))) *>
        safeMove(inventory, inventory.moveTo(failedName)(File.CopyOptions(overwrite = true))) *>
        writeErrorLogFile(failedName, result) *>
        failedHook.runHooks(failed / inventory.name)
    }
  }
}
