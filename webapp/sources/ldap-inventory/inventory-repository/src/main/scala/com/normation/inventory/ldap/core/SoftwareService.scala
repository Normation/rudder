package com.normation.inventory.ldap.core


import com.normation.inventory.domain.InventoryLogger
import com.normation.inventory.services.core.ReadOnlySoftwareDAO
import com.normation.inventory.services.core.WriteOnlySoftwareDAO
import com.normation.errors._
import com.normation.zio._

trait SoftwareService {
  def deleteUnreferencedSoftware() : IOResult[Seq[String]]
}

class SoftwareServiceImpl(
    readOnlySoftware    : ReadOnlySoftwareDAO
  , writeOnlySoftware   : WriteOnlySoftwareDAO)
extends SoftwareService {

  /** Delete all unreferenced softwares
    * First search in software, and then in nodes, so that if a node arrives in between (new inventory)
    * its software wont be deleted
    */
  def deleteUnreferencedSoftware() : IOResult[Seq[String]] = {
    val t1 = System.currentTimeMillis
    for {
      allSoftwares      <- readOnlySoftware.getAllSoftwareIds()
      t2                <- currentTimeMillis
      _                 <- InventoryLogger.debug(s"All softwares id in ou=software fetched: ${allSoftwares.size} softwares id in ${t2 - t1}ms")

      allNodesSoftwares <- readOnlySoftware.getSoftwaresForAllNodes()
      t3                <- currentTimeMillis
      _                 <- InventoryLogger.debug(s"All softwares id in nodes fetched: ${allNodesSoftwares.size} softwares id in ${t3 - t2}ms")


      extraSoftware     =  allSoftwares -- allNodesSoftwares
      _                 <- InventoryLogger.debug(s"Found ${extraSoftware.size} unreferenced software in ou=software, going to delete them")

      deletedSoftware   <- writeOnlySoftware.deleteSoftwares(extraSoftware.toSeq)
      t4                <- currentTimeMillis
      _                 <- InventoryLogger.debug(s"Deleted ${deletedSoftware.size} software in ${t4 - t3}ms")
    } yield {
      deletedSoftware.map(x => x.toString).toSeq
    }
  }
}
