package com.normation.inventory.ldap.core

import com.normation.errors.*
import com.normation.inventory.domain.InventoryProcessingLogger
import com.normation.inventory.services.core.ReadOnlySoftwareDAO
import com.normation.inventory.services.core.WriteOnlySoftwareDAO
import com.normation.utils.DateFormaterService
import com.normation.zio.*
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import zio.*
import zio.syntax.*

trait SoftwareService {
  def deleteUnreferencedSoftware(): UIO[Int]
}

class SoftwareServiceImpl(
    readOnlySoftware:  ReadOnlySoftwareDAO,
    writeOnlySoftware: WriteOnlySoftwareDAO,
    softwareDIT:       InventoryDit
) extends SoftwareService {

  /** Delete all unreferenced softwares
    * First search in software, and then in nodes, so that if a node arrives in between (new inventory)
    * its software wont be deleted
    */
  def deleteUnreferencedSoftware(): UIO[Int] = {
    val prog = for {
      _            <- InventoryProcessingLogger.info(s"[purge unreferenced software] Start gathering information about node's software")
      t1           <- currentTimeMillis
      allSoftwares <- readOnlySoftware.getAllSoftwareIds()
      t2           <- currentTimeMillis
      _            <-
        InventoryProcessingLogger.debug(
          s"[purge unreferenced software] All softwares id in ou=software fetched: ${allSoftwares.size} softwares id in ${t2 - t1}ms"
        )

      allNodesSoftwares <- readOnlySoftware.getSoftwaresForAllNodes()
      t3                <- currentTimeMillis
      _                 <-
        InventoryProcessingLogger.debug(
          s"[purge unreferenced software] All softwares id in nodes fetched: ${allNodesSoftwares.size} softwares id in ${t3 - t2}ms"
        )

      extraSoftware = allSoftwares -- allNodesSoftwares
      _            <- if (extraSoftware.size <= 0) {
                        InventoryProcessingLogger.info(
                          s"[purge unreferenced software] Found ${extraSoftware.size} unreferenced software in ou=software: nothing to do"
                        )
                      } else {
                        for {
                          _  <-
                            InventoryProcessingLogger.info(
                              s"[purge unreferenced software] Found ${extraSoftware.size} unreferenced software in ou=software, going to delete them"
                            )
                          _  <- InventoryProcessingLogger.ifDebugEnabled {
                                  import better.files.*
                                  import better.files.Dsl.*
                                  (for {
                                    f <- IOResult.attempt {
                                           val dir = File("/var/rudder/tmp/purgeSoftware")
                                           dir.createDirectories()
                                           File(
                                             dir,
                                             s"${DateFormaterService.serialize(DateTime.now(DateTimeZone.UTC))}-unreferenced-software-dns.txt"
                                           )
                                         }
                                    _ <- IOResult.attempt {
                                           extraSoftware.foreach(x => (f << softwareDIT.SOFTWARE.SOFT.dn(x).toString))
                                         }
                                    _ <-
                                      InventoryProcessingLogger.debug(
                                        s"[purge unreferenced software] List of unreferenced software DN available in file: ${f.pathAsString}"
                                      )
                                  } yield ()).catchAll(err => {
                                    InventoryProcessingLogger.error(
                                      s"Error while writting unreference software DN in debug file: ${err.fullMsg}"
                                    )
                                  })
                                }
                          _  <- writeOnlySoftware.deleteSoftwares(extraSoftware.toSeq)
                          t4 <- currentTimeMillis
                          _  <- InventoryProcessingLogger.timing.info(
                                  s"[purge unreferenced software] Deleted ${extraSoftware.size} software in ${t4 - t3}ms"
                                )
                        } yield ()
                      }
    } yield {
      extraSoftware.size
    }
    prog.catchAll(err => {
      InventoryProcessingLogger.error(
        s"[purge unreferenced software] Error when purging unreferenced software: ${err.fullMsg}"
      ) *> 0.succeed
    })
  }
}
