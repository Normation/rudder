package com.normation.inventory.ldap.core


import com.normation.inventory.services.core.{ReadOnlySoftwareDAO, WriteOnlySoftwareDAO}
import net.liftweb.common.Loggable
import net.liftweb.common._


trait SoftwareService {
  def deleteUnreferencedSoftware() : Box[Seq[String]]
}

class SoftwareServiceImpl(
    readOnlySoftware    : ReadOnlySoftwareDAO
  , writeOnlySoftware   : WriteOnlySoftwareDAO)
extends SoftwareService with Loggable {

  /** Delete all unreferenced softwares
    * First search in software, and then in nodes, so that if a node arrives in between (new inventory)
    * its software wont be deleted
    */
  def deleteUnreferencedSoftware() : Box[Seq[String]] = {
    val t1 = System.currentTimeMillis
    for {
      allSoftwares      <- readOnlySoftware.getAllSoftwareIds()
      t2                = System.currentTimeMillis()
      _                 = logger.debug(s"All softwares id in ou=software fetched: ${allSoftwares.size} softwares id in ${t2-t1}ms")

      allNodesSoftwares <- readOnlySoftware.getSoftwaresForAllNodes()
      t3                = System.currentTimeMillis()
      _                 = logger.debug(s"All softwares id in nodes fetched: ${allNodesSoftwares.size} softwares id in ${t3-t2}ms")


      extraSoftware     = allSoftwares -- allNodesSoftwares
      _                 = logger.debug(s"Found ${extraSoftware.size} unreferenced software in ou=software, going to delete them")

      deletedSoftware   <- writeOnlySoftware.deleteSoftwares(extraSoftware.toSeq)
      t4                = System.currentTimeMillis()
      _                 = logger.debug(s"Deleted ${deletedSoftware.size} software in ${t4-t3}ms")


    } yield {
      deletedSoftware.map(x => x.toString).toSeq
    }
  }
}
