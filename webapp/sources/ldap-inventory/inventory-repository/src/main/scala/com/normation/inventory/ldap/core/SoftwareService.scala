package com.normation.inventory.ldap.core


import com.normation.inventory.domain.InventoryProcessingLogger
import com.normation.inventory.services.core.ReadOnlySoftwareDAO
import com.normation.inventory.services.core.WriteOnlySoftwareDAO
import com.normation.errors._
import com.normation.utils.DateFormaterService
import com.normation.zio._
import org.joda.time.DateTime
import zio._
import zio.syntax._

trait SoftwareService {
  def deleteUnreferencedSoftware() : UIO[Int]
}

class SoftwareServiceImpl(
    readOnlySoftware : ReadOnlySoftwareDAO
  , writeOnlySoftware: WriteOnlySoftwareDAO
  , softwareDIT      : InventoryDit
) extends SoftwareService {

  /** Delete all unreferenced softwares
    * First search in software, and then in nodes, so that if a node arrives in between (new inventory)
    * its software wont be deleted
    */
  def deleteUnreferencedSoftware() : UIO[Int] = {
    val prog = for {
      _                 <- InventoryProcessingLogger.info(s"[purge unreferenced software] Start gathering information about node's software")
      t1                <- currentTimeMillis
      allSoftwares      <- readOnlySoftware.getAllSoftwareIds()
      t2                <- currentTimeMillis
      _                 <- InventoryProcessingLogger.debug(s"[purge unreferenced software] All softwares id in ou=software fetched: ${allSoftwares.size} softwares id in ${t2 - t1}ms")

      allNodesSoftwares <- readOnlySoftware.getSoftwaresForAllNodes()
      t3                <- currentTimeMillis
      _                 <- InventoryProcessingLogger.debug(s"[purge unreferenced software] All softwares id in nodes fetched: ${allNodesSoftwares.size} softwares id in ${t3 - t2}ms")


      extraSoftware     =  allSoftwares -- allNodesSoftwares
      _                 <- if(extraSoftware.size <= 0) {
                          InventoryProcessingLogger.info(s"[purge unreferenced software] Found ${extraSoftware.size} unreferenced software in ou=software: nothing to do")
                        } else for {
                          _  <- InventoryProcessingLogger.info(s"[purge unreferenced software] Found ${extraSoftware.size} unreferenced software in ou=software, going to delete them")
                          _  <- InventoryProcessingLogger.ifDebugEnabled {
                                  import better.files._
                                  import better.files.Dsl._
                                  (for {
                                    f <- IOResult.attempt {
                                           val dir = File("/var/rudder/tmp/purgeSoftware")
                                           dir.createDirectories()
                                           File(dir, s"${DateFormaterService.serialize(DateTime.now())}-unreferenced-software-dns.txt")
                                         }
                                    _ <- IOResult.attempt {
                                           extraSoftware.foreach(x => (f  << softwareDIT.SOFTWARE.SOFT.dn(x).toString))
                                         }
                                    _ <- InventoryProcessingLogger.debug(s"[purge unreferenced software] List of unreferenced software DN available in file: ${f.pathAsString}")
                                  } yield ()).catchAll(err => InventoryProcessingLogger.error(s"Error while writting unreference software DN in debug file: ${err.fullMsg}"))
                                }
                          _  <- writeOnlySoftware.deleteSoftwares(extraSoftware.toSeq)
                          t4 <- currentTimeMillis
                          _  <- InventoryProcessingLogger.timing.info(s"[purge unreferenced software] Deleted ${extraSoftware.size} software in ${t4 - t3}ms")
                        } yield ()
    } yield {
      extraSoftware.size
    }
    prog.catchAll(err =>
      InventoryProcessingLogger.error(s"[purge unreferenced software] Error when purging unreferenced software: ${err.fullMsg}") *> 0.succeed
    )
  }
}
