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

import com.normation.inventory.domain.CertifiedKey
import com.normation.inventory.domain.Inventory
import com.normation.inventory.domain.InventoryProcessingLogger
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.SecurityToken
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.services.core.FullInventoryRepository
import com.normation.inventory.services.provisioning.InventoryDigestServiceV1
import com.normation.inventory.services.provisioning.InventoryParser
import com.normation.inventory.services.provisioning.InventorySaver
import com.normation.rudder.domain.logger.ApplicationLogger

import com.unboundid.ldif.LDIFChangeRecord
import org.joda.time.Duration
import org.joda.time.format.PeriodFormat

import java.io.InputStream
import java.security.{PublicKey => JavaSecPubKey}

import zio._
import zio.syntax._
import com.normation.errors.Chained
import com.normation.errors.IOResult
import com.normation.errors.SystemError
import com.normation.errors._
import com.normation.zio.ZioRuntime
import com.normation.zio._


sealed trait InventoryProcessStatus {
  def inventoryName: String
  def nodeId       : NodeId

}
final object InventoryProcessStatus {
  final case class Accepted        (inventoryName: String, nodeId: NodeId) extends InventoryProcessStatus
  final case class QueueFull       (inventoryName: String, nodeId: NodeId) extends InventoryProcessStatus
  final case class SignatureInvalid(inventoryName: String, nodeId: NodeId) extends InventoryProcessStatus
  final case class MissingSignature(inventoryName: String, nodeId: NodeId) extends InventoryProcessStatus
}


object StatusLog {
  implicit class LogMessage(status: InventoryProcessStatus) {
    def msg: String = status match {
      case InventoryProcessStatus.MissingSignature(inventoryName, nodeId) =>
        s"Rejecting Inventory '${inventoryName}' for Node '${nodeId.value}' because its signature is missing. " +
        s"You can go back to unsigned state by running the following command on the Rudder Server: " +
        s"'/opt/rudder/bin/rudder-keys reset-status ${nodeId.value}'"

      case InventoryProcessStatus.SignatureInvalid(inventoryName, nodeId) =>
        s"Rejecting Inventory '${inventoryName}' for Node '${nodeId.value}' because the Inventory signature is " +
        s"not valid: the Inventory was not signed with the same agent key as the one saved within Rudder for that Node. If " +
        s"you updated the agent key on this node, you can update the key stored within Rudder with the https://docs.rudder.io/api/#api-Nodes-updateNode" +
        s"api (look for 'agentKey' property). The key path depends of your OS, on linux it's: '/var/rudder/cfengine-community/ppkeys/localhost.pub'. It is " +
        s"also contained in the <AGENT_CERT> value of inventory (you can extract public key with `openssl x509 -pubkey -noout -in - << EOF " +
        s"-----BEGIN CERTIFICATE----- .... -----END CERTIFICATE----- EOF`). " +
        s"If you did not change the key, please ensure that the node sending that inventory is actually the node registered " +
        s"within Rudder"

      case InventoryProcessStatus.QueueFull(inventoryName, nodeId) =>
        s"Rejecting Inventory '${inventoryName}' for Node '${nodeId.value}' because processing queue is full."

      case InventoryProcessStatus.Accepted(inventoryName, nodeId) =>
        s"Inventory '${inventoryName}' for Node '${nodeId.value}' added to processing queue."
    }
  }
}



class InventoryProcessor(
    unmarshaller       : InventoryParser
  , inventorySaver     : InventorySaver[Seq[LDIFChangeRecord]]
  , val maxQueueSize   : Int
  , val maxParallel    : Long
  , repo               : FullInventoryRepository[Seq[LDIFChangeRecord]]
  , digestService      : InventoryDigestServiceV1
  , checkAliveLdap     : () => IOResult[Unit]
  , nodeInventoryDit   : InventoryDit
) {

  ApplicationLogger.info(s"INFO Configure inventory processing with parallelism of '${maxParallel}' and queue size of '${maxQueueSize}'")

  // we want to limit the number of inventories concurrently parsed
  lazy val xmlParsingSemaphore = ZioRuntime.unsafeRun(Semaphore.make(maxParallel))

  /*
   * We manage inventories buffering with a bounded queue.
   * That Queue is blocking, so any caller should be careful to
   * put in front of it a Sliding or Dropping queue of size one
   * in fron of any "offer" call to make it non blocking.
   */
  protected lazy val blockingQueue = ZQueue.bounded[Inventory](maxQueueSize).runNow
  def addInventoryToQueue(inventory: Inventory): UIO[Unit] = {
    for {
      _ <- InventoryProcessingLogger.trace(s"Blocking add '${inventory.name}' to backend processing queue")
      _ <- blockingQueue.offer(inventory)
      _ <- InventoryProcessingLogger.trace(s"Blocking add '${inventory.name}' to backend processing queue: done")
    } yield ()
  }

  /*
   * This latch is to here to provide an non-blocking, dropping behavior to blockingQueue:
   * - it just `addInventoryToQueue` (which is blocking) in a loop
   * - it drops new requests when an other one is already here (which means that
   *   `addInventoryToQueue` from the previous action is still blocked)
   */
  protected lazy val latch         = ZQueue.dropping[Inventory](1).runNow
  //start latch, it just forwards to the blocking queue when there is room
  latch.take.flatMap(addInventoryToQueue(_)).forever.forkDaemon.runNow

  def currentQueueSize: Int = ZioRuntime.unsafeRun(blockingQueue.size)

  def isQueueFull: IOResult[Boolean] = {
    // Get the current size, the remaining of the answer is based on that.
    // The "+1" is because the fiber waiting for inventory give 1 slot,
    // if don't "+1", we start at -1 when no inventories are here.

    //must be coherent with "can do", current = 49 < max = 50 => not saturated
    blockingQueue.size.map(_ + 1 >= maxQueueSize)
  }

  /*
   * Saving inventorys is a loop which consume items from queue
   */

  def loop(): UIO[Nothing] = {
    blockingQueue.take.flatMap(r =>
      InventoryProcessingLogger.trace(s"Took inventory '${r.name}' from backend queue and saving it") *>
      saveInventory(r)
    ).forever
  }

  // start the inventory processing
  ZioRuntime.unsafeRun(loop().forkDaemon)

  /*
   * This is a non blocking call to saveInventory that will try to send to back-end and
   * return immediately with an error status if it didn't.
   */
  // we need something that can produce the stream several time because we are reading the file several time - not very
  // optimized :/
  def saveInventory(info: SaveInventoryInfo): IOResult[InventoryProcessStatus] = {
    for {
      _ <- InventoryProcessingLogger.trace(s"Query async save of '${info.fileName}'")
      x <- saveInventoryInternal(info, blocking = false)
      _ <- InventoryProcessingLogger.trace(s"Query async save of '${info.fileName}': done")
    } yield x
  }

  def saveInventoryBlocking(info: SaveInventoryInfo): IOResult[InventoryProcessStatus] = {
    for {
      _ <- InventoryProcessingLogger.trace(s"Query blocking save of '${info.fileName}'")
      x <- saveInventoryInternal(info, blocking = true)
      _ <- InventoryProcessingLogger.trace(s"Query blocking save of '${info.fileName}': done")
    } yield x
  }

  /*
   * If blocking is true, that method won't complete until there is room in the backend queue.
   * Be careful to handle that case carefully with overflow strategy to avoid blocking
   * everything.
   * When non blocking, the return value will tell is the value was accepted.
   */
  def saveInventoryInternal(info: SaveInventoryInfo, blocking: Boolean): IOResult[InventoryProcessStatus] = {
    def saveWithSignature(inventory: Inventory, publicKey: JavaSecPubKey, newInventoryStream: () => InputStream, newSignature: () => InputStream): IOResult[InventoryProcessStatus] = {
      ZIO.bracket(Task.effect(newInventoryStream()).mapError(SystemError(s"Error when reading inventory file '${inventory.name}'", _)))(is => Task.effect(is.close()).run) { inventoryStream =>
        ZIO.bracket(Task.effect(newSignature()).mapError(SystemError(s"Error when reading signature for inventory file '${inventory.name}'", _)))(is => Task.effect(is.close()).run) { signatureStream =>

          for {
            digest  <- digestService.parse(signatureStream)
            checked <- digestService.check(publicKey, digest, inventoryStream)
            saved   <- if (checked) {
                         // Signature is valid, send it to save engine
                         // Set the keyStatus to Certified
                         // For now we set the status to certified since we want pending inventories to have their inventory signed
                         // When we will have a 'pending' status for keys we should set that value instead of certified
                         val certified = inventory.copy(node = inventory.node.copyWithMain(main => main.copy(keyStatus = CertifiedKey)))
                         checkQueueAndSave(certified, blocking)
                       } else {
                         // Signature is not valid, reject inventory
                         InventoryProcessStatus.SignatureInvalid(inventory.name, inventory.node.main.id).succeed
                       }
           } yield {
             saved
           }
        }
      }
    }

    def parseSafe(newInventoryStream: () => InputStream, inventoryFileName: String): IOResult[Inventory] = {
      ZIO.bracket(Task.effect(newInventoryStream()).mapError(SystemError(s"Error when trying to read inventory file '${inventoryFileName}'", _)) )(
        is => Task.effect(is.close()).run
      ){ is =>
        for {
          r <- unmarshaller.fromXml(inventoryFileName, is)
        } yield {
          // use the provided file name as inventory name, else it's a generic one setted by fusion (like "inventory")
          r.copy(name = inventoryFileName)
        }
      }
    }

    // actuall inventory processing logic
    val processLogic = (for {
      start        <- currentTimeMillis
      qsize        <- blockingQueue.size
      qsaturated   <- isQueueFull
      _            <- InventoryProcessingLogger.debug(s"Enter pre-processed inventory for ${info.fileName} with blocking='${blocking}' and current backend queue size=${qsize} (saturated=${qsaturated})")
      inventory    <- xmlParsingSemaphore.withPermit(
                        InventoryProcessingLogger.debug(s"Start parsing inventory '${info.fileName}'") *>
                        parseSafe(info.inventoryStream, info.fileName).chainError("Can't parse the input inventory, aborting") <*
                        InventoryProcessingLogger.trace(s"Parsing done for inventory '${info.fileName}'")
                      )
      secPair      <- digestService.getKey(inventory).chainError(s"Error when trying to check inventory key for Node '${inventory.node.main.id.value}'")
      parsed       <- digestService.parseSecurityToken(secPair._1)
      _            <- parsed.subject match {
                        case None       => UIO.unit
                        case Some(list) => SecurityToken.checkCertificateSubject(inventory.node.main.id, list)
                      }
      afterParsing =  System.currentTimeMillis()
      inventoryName= inventory.name
      nodeId       = inventory.node.main.id
      _            =  InventoryProcessingLogger.debug(s"Inventory '${inventory.name}' parsed in ${PeriodFormat.getDefault.print(new Duration(afterParsing, System.currentTimeMillis).toPeriod)} ms, now saving")
      saved        <- info.optSignatureStream match { // Do we have a signature ?
                        // Signature here, check it
                        case Some(sig) =>
                          saveWithSignature(inventory, parsed.publicKey, info.inventoryStream, sig).chainError("Error when trying to check inventory signature")

                        // There is no Signature
                        case None =>
                          Inconsistency(s"Error, inventory '${inventory.name}' has no signature, which is not supported anymore in Rudder 6.0. " +
                                        s"Please check that your node's agent is compatible with that version.").fail
                      }
      _            <- InventoryProcessingLogger.debug(s"Inventory '${inventory.name}' for node '${inventory.node.main.id.value}' pre-processed in ${PeriodFormat.getDefault.print(new Duration(start, System.currentTimeMillis).toPeriod)} ms")
    } yield {
      saved
    }).foldM(
      err => {
        val fail = Chained(s"Error when trying to process inventory '${info.fileName}'", err)
        InventoryProcessingLogger.error(fail.fullMsg) *> err.fail
      }
    , status => {
        import com.normation.rudder.inventory.StatusLog.LogMessage
        (status match {
          case InventoryProcessStatus.MissingSignature(_,_) => InventoryProcessingLogger.error(status.msg)
          case InventoryProcessStatus.SignatureInvalid(_,_) => InventoryProcessingLogger.error(status.msg)
          case InventoryProcessStatus.QueueFull(_,_)        => InventoryProcessingLogger.warn(status.msg)
          case InventoryProcessStatus.Accepted(_,_)         => InventoryProcessingLogger.trace(status.msg)
        }) *> status.succeed
      }
    )

    // guard against missing files: as there may be a latency between file added to buffer / file processed, we
    // need to check again here.
    for {
      exists <- info.testExits
      res    <- if(exists) processLogic
                else InventoryProcessingLogger.trace(s"File '${info.fileName}' was deleted, skipping") *>
                     InventoryProcessStatus.Accepted(info.fileName, NodeId("skipped")).succeed
    } yield res
  }

  /*
   * Before actually trying to save, check that LDAP is up to at least
   * avoid the case where we are telling the use "everything is fine"
   * but just fail after.
   */
  def checkQueueAndSave(inventory: Inventory, blocking: Boolean): IOResult[InventoryProcessStatus] = {

    (for {
      _     <- checkAliveLdap()
      res   <- if(blocking) {
                 // that's block. And so inventory is always accepted in the end
                 addInventoryToQueue(inventory) *> InventoryProcessStatus.Accepted(inventory.name, inventory.node.main.id).succeed
               } else {
                 for {
                   canDo <- latch.offer(inventory)
                   res   <- if(canDo) {
                              InventoryProcessStatus.Accepted(inventory.name, inventory.node.main.id).succeed
                            } else {
                              InventoryProcessStatus.QueueFull(inventory.name, inventory.node.main.id).succeed
                            }
                 } yield res
              }
    } yield {
      res
    }).catchAll { err =>
      val e = Chained(s"There is an error with the LDAP backend preventing acceptation of inventory '${inventory.name}'", err)
      InventoryProcessingLogger.error(err.fullMsg) *> e.fail
    }
  }

  /**
   * Encapsulate the logic to process new incoming inventories.
   *
   * The processing is successlly synchrone and monothreaded,
   * asynchronicity and multithreading are managed in the caller.
   *
   * It is not the consumer that manage the queue size, it only
   * decrease it when it terminates a processing.
   *
   */
  def saveInventory(inventory:Inventory): UIO[Unit] = {
    for {
      _     <- InventoryProcessingLogger.trace(s"Start post processing of inventory '${inventory.name}' for node '${inventory.node.main.id.value}'")
      start <- UIO(System.currentTimeMillis)
      saved <- inventorySaver.save(inventory).chainError("Can't merge inventory in LDAP directory, aborting").either
      _     <- saved match {
                 case Left(err) =>
                   InventoryProcessingLogger.error(s"Error when trying to process inventory: ${err.fullMsg}")
                 case Right(_) =>
                   InventoryProcessingLogger.debug("Inventory saved.")
               }
      _      <- InventoryProcessingLogger.info(s"Inventory '${inventory.name}' for node '${inventory.node.main.hostname}' [${inventory.node.main.id.value}] (signature:${inventory.node.main.keyStatus.value}) "+
                s"processed in ${PeriodFormat.getDefault.print(new Duration(start, System.currentTimeMillis).toPeriod)}")
    } yield ()
  }

}


