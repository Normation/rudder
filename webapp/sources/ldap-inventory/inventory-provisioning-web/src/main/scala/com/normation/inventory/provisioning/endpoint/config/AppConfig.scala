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

package com.normation.inventory.provisioning.endpoint
package config

//domain and generic interfaces
import com.normation.inventory.services.provisioning._
import com.normation.inventory.provisioning.fusion._
import com.normation.inventory.ldap.core._
import com.normation.inventory.ldap.provisioning._
import com.normation.ldap.sdk._
import com.normation.ldap.ldif.DefaultLDIFFileLogger
import com.unboundid.ldif.LDIFChangeRecord
import org.springframework.context.annotation.{Bean, Configuration, Import}
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.multipart.commons.CommonsMultipartResolver
import com.normation.utils.{StringUuidGenerator, StringUuidGeneratorImpl}
import org.slf4j.LoggerFactory
import com.normation.inventory.ldap.provisioning.PendingNodeIfNodeWasRemoved
import java.security.Security
import java.util.concurrent.TimeUnit

import com.normation.errors.IOResult
import com.normation.zio.ZioRuntime
import com.unboundid.ldap.sdk.DN
import org.bouncycastle.jce.provider.BouncyCastleProvider

@Configuration
@Import(Array(classOf[PropertyPlaceholderConfig]))
class AppConfig {

  // Set security provider with bouncy castle one
  Security.addProvider(new BouncyCastleProvider());

  val logger = LoggerFactory.getLogger(classOf[AppConfig])

  @Value("${ldap.host}")
  var SERVER = ""

  @Value("${ldap.port}")
  var PORT = 389

  @Value("${ldap.authdn}")
  var AUTH_DN = ""
  @Value("${ldap.authpw}")
  var AUTH_PW = ""

  @Value("${ldif.tracelog.rootdir}")
  var LDIF_TRACELOG_ROOT_DIR = ""

  @Value("${ldap.inventories.accepted.basedn}")
  var ACCEPTED_INVENTORIES_DN = ""

  @Value("${ldap.inventories.pending.basedn}")
  var PENDING_INVENTORIES_DN = ""

  @Value("${ldap.inventories.removed.basedn}")
  var REMOVED_INVENTORIES_DN = ""

  @Value("${ldap.inventories.software.basedn}")
  var SOFTWARE_INVENTORIES_DN = ""

  @Value("${waiting.inventory.queue.size}")
  var WAITING_QUEUE_SIZE = 50

  // the number of inventories parsed in parallel.
  // It also limits the number of inventories proccessed in
  // parallel, because obviously you need to parse the inventory before saving it.
  // Minimum 1, 1x mean "0.5x number of cores"
  @Value("${inventory.parse.parallelization:0.5x}")
  var MAX_PARSE_PARALLEL = "0.5x"


  @Value("${inventories.root.directory}")
  var INVENTORY_ROOT_DIR = ""

  @Value("${inventories.watcher.enable}")
  var WATCHER_ENABLE = "true"

  @Value("${inventories.watcher.waitForSignatureDuration}")
  var WATCHER_WAIT_FOR_SIG = 10 // in seconds

  //TODO: only have a root DN here !
  @Bean
  def acceptedNodesDit = new InventoryDit(new DN(ACCEPTED_INVENTORIES_DN), new DN(SOFTWARE_INVENTORIES_DN), "Accepted Servers")

  @Bean
  def pendingNodesDit = new InventoryDit(new DN(PENDING_INVENTORIES_DN), new DN(SOFTWARE_INVENTORIES_DN), "Pending Servers")

  @Bean
  def removedNodesDit = new InventoryDit(new DN(REMOVED_INVENTORIES_DN), new DN(SOFTWARE_INVENTORIES_DN), "Removed Servers")

  @Bean
  def inventoryDitService = new InventoryDitServiceImpl(pendingNodesDit,acceptedNodesDit, removedNodesDit)

  @Bean
  def inventoryMapper = new InventoryMapper(inventoryDitService, pendingNodesDit, acceptedNodesDit, removedNodesDit)

  /*
   * Implementation of thePipelined report unmarshaller
   */
  @Bean
  def pipelinedReportUnmarshaller : ReportUnmarshaller = {
    val fusionReportParser = {
      new FusionReportUnmarshaller(
          uuidGenerator
        , rootParsingExtensions    = Nil
        , contentParsingExtensions = Nil
      )
    }

    new DefaultReportUnmarshaller(
     fusionReportParser,
     Seq(
         new PreUnmarshallCheckConsistency
     )
    )
  }

  @Bean
  def roLdapConnectionProvider = new ROPooledSimpleAuthConnectionProvider(
      authDn = AUTH_DN
    , authPw = AUTH_PW
    , host = SERVER
    , port = PORT
    , ldifFileLogger = new DefaultLDIFFileLogger(ldifTraceRootDir = LDIF_TRACELOG_ROOT_DIR)
    , blockingModule = ZioRuntime.Environment
  )

  @Bean
  def rwLdapConnectionProvider = new RWPooledSimpleAuthConnectionProvider(
      authDn = AUTH_DN
    , authPw = AUTH_PW
    , host = SERVER
    , port = PORT
    , ldifFileLogger = new DefaultLDIFFileLogger(ldifTraceRootDir = LDIF_TRACELOG_ROOT_DIR)
    , blockingModule = ZioRuntime.Environment
  )

  @Bean
  def uuidGenerator : StringUuidGenerator = new StringUuidGeneratorImpl

  @Bean
  def ldifReportLogger = new DefaultLDIFReportLogger(LDIF_TRACELOG_ROOT_DIR)

  @Bean
  def preCommitLogReport = new LogReportPreCommit(inventoryMapper,ldifReportLogger)

  @Bean
  def fullInventoryRepository = new FullInventoryRepositoryImpl(
      inventoryDitService
    , inventoryMapper
    , rwLdapConnectionProvider
  )

  @Bean
  def postCommitLogger = new PostCommitLogger(ldifReportLogger)

  @Bean
  def acceptPendingMachineIfServerIsAccepted = new AcceptPendingMachineIfServerIsAccepted(
      fullInventoryRepository
  )

  @Bean
  def pendingNodeIfNodeWasRemoved = new PendingNodeIfNodeWasRemoved(
      fullInventoryRepository
  )

  @Bean
  def serverFinder() : NodeInventoryDNFinderAction =
    new NodeInventoryDNFinderService(Seq(
        //start by trying to use an already given UUID
        NamedNodeInventoryDNFinderAction("use_existing_id", new UseExistingNodeIdFinder(inventoryDitService,roLdapConnectionProvider,acceptedNodesDit.BASE_DN.getParent))
    ))

  @Bean
  def vmFinder() : MachineDNFinderAction =
    new MachineDNFinderService(Seq(
        //start by trying to use an already given UUID
        NamedMachineDNFinderAction("use_existing_id", new UseExistingMachineIdFinder(inventoryDitService,roLdapConnectionProvider,acceptedNodesDit.BASE_DN.getParent))
        //look if it's in the accepted inventories
      , NamedMachineDNFinderAction("check_mother_board_uuid_accepted", new FromMotherBoardUuidIdFinder(roLdapConnectionProvider,acceptedNodesDit,inventoryDitService))
        //see if it's in the "pending" branch
      , NamedMachineDNFinderAction("check_mother_board_uuid_pending", new FromMotherBoardUuidIdFinder(roLdapConnectionProvider,pendingNodesDit,inventoryDitService))
        //see if it's in the "removed" branch
      , NamedMachineDNFinderAction("check_mother_board_uuid_removed", new FromMotherBoardUuidIdFinder(roLdapConnectionProvider,removedNodesDit,inventoryDitService))
    ))

  @Bean
  def softwareFinder() : SoftwareDNFinderAction = new NameAndVersionIdFinder(
      "check_name_and_version"
    , roLdapConnectionProvider
    , inventoryMapper
    , acceptedNodesDit
  )

  @Bean
  def automaticMerger() : PreCommit = new UuidMergerPreCommit(
      uuidGenerator
    , acceptedNodesDit
    , serverFinder
    , vmFinder
    , softwareFinder
  )

   lazy val preCommitPipeline : Seq[PreCommit] = (
    CheckOsType ::
    automaticMerger ::
    CheckMachineName ::
    new LastInventoryDate() ::
    AddIpValues ::
    preCommitLogReport ::
    Nil
  )

  lazy val postCommitPipeline : Seq[PostCommit[Seq[LDIFChangeRecord]]]= (
    (
      pendingNodeIfNodeWasRemoved ::
      acceptPendingMachineIfServerIsAccepted ::
      postCommitLogger ::
      Nil
    )
  )

  @Bean
  def reportSaver = new DefaultReportSaver(
      rwLdapConnectionProvider
    , acceptedNodesDit
    , inventoryMapper
    , preCommitPipeline
    , postCommitPipeline
  )

  /*
   * configure the file handler
   */
  @Bean
  def multipartResolver() = {
    val c = new CommonsMultipartResolver()
    c.setMaxUploadSize(10000000)
    c
  }

  /*
   * A method that check LDAP health status.
   * It must be quick and simple.
   */
  val checkLdapAlive: () => IOResult[Unit] = {
    () =>
    for {
      con <- rwLdapConnectionProvider
      res <- con.get(pendingNodesDit.NODES.dn, "1.1")
    } yield {
      ()
    }
  }

  @Bean
  def inventoryProcessor() = {
    val maxParallel = try {
      val user = if(MAX_PARSE_PARALLEL.endsWith("x")) {
        val xx = MAX_PARSE_PARALLEL.substring(0, MAX_PARSE_PARALLEL.size-1)
        java.lang.Double.parseDouble(xx) * Runtime.getRuntime.availableProcessors()
      } else {
        java.lang.Double.parseDouble(MAX_PARSE_PARALLEL)
      }
      Math.max(1, user).toLong
    } catch {
      case ex: Exception =>
        // logs are not available here
        println(s"ERROR Error when parsing configuration properties for the parallelisation of inventory processing. " +
                s"Expecting a positive integer or number of time the avaiblable processors. Default to '0.5x': " +
                s"inventory.parse.parallelization=${MAX_PARSE_PARALLEL}")
        Math.max(1, Math.ceil(Runtime.getRuntime.availableProcessors().toDouble/2).toLong)
    }
    new InventoryProcessor(
      pipelinedReportUnmarshaller
      , reportSaver
      , WAITING_QUEUE_SIZE
      , maxParallel
      , fullInventoryRepository
      , new InventoryDigestServiceV1(fullInventoryRepository)
      , checkLdapAlive
      , pendingNodesDit
    )
  }

  @Bean
  def inventoryWatcher() = {
    val watcher = new InventoryFileWatcher(
        inventoryProcessor()
      , INVENTORY_ROOT_DIR + "/incoming"
      , INVENTORY_ROOT_DIR + "/accepted-nodes-updates"
      , INVENTORY_ROOT_DIR + "/received"
      , INVENTORY_ROOT_DIR + "/failed"
      , zio.duration.Duration(WATCHER_WAIT_FOR_SIG.toLong, TimeUnit.SECONDS)
      , ".sign"
    )
    WATCHER_ENABLE.trim.toLowerCase match {
      case "true" => watcher.startWatcher()
      case _ => // don't start
        InventoryProcessingLogger.debug(s"Not automatically incoming inventory watcher because 'inventories.watcher.enable'=${WATCHER_ENABLE}")
    }

    watcher
  }

  /*
   * The REST end point where OCSi report are
   * uploaded
   */
  @Bean
  def springApplication() : FusionReportEndpoint = {
    new FusionReportEndpoint(inventoryProcessor(), inventoryWatcher())
  }
}
