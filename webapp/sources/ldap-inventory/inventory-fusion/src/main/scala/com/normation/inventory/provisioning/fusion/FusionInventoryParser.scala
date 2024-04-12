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

package com.normation.inventory.provisioning.fusion

import com.normation.errors.*
import com.normation.inventory.domain.*
import com.normation.inventory.domain.InventoryError.Inconsistency
import com.normation.inventory.domain.NodeTimezone
import com.normation.inventory.domain.VmType.*
import com.normation.inventory.services.provisioning.*
import com.normation.utils.HostnameRegex
import com.normation.utils.StringUuidGenerator
import com.softwaremill.quicklens.*
import java.net.InetAddress
import java.util.Locale
import net.liftweb.json.JsonAST.JString
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import scala.xml.*
import zio.*
import zio.syntax.*

class FusionInventoryParser(
    uuidGen:                      StringUuidGenerator,
    rootParsingExtensions:        List[FusionInventoryParserExtension] = Nil,
    contentParsingExtensions:     List[FusionInventoryParserExtension] = Nil,
    biosDateFormat:               String = "MM/dd/yyyy",
    slotMemoryUnit:               String = "Mo",
    ramUnit:                      String = "Mo",
    swapUnit:                     String = "Mo",
    fsSpaceUnit:                  String = "Mo",
    lastLoggedUserDatetimeFormat: String = "EEE MMM dd HH:mm",
    ignoreProcesses:              Boolean = false
) extends XmlInventoryParser {

  import OptText.optText

  val userLoginDateTimeFormat: DateTimeFormatter =
    DateTimeFormat.forPattern(lastLoggedUserDatetimeFormat).withLocale(Locale.ENGLISH)
  val biosDateTimeFormat:      DateTimeFormatter = DateTimeFormat.forPattern(biosDateFormat).withLocale(Locale.ENGLISH)

  // extremely specialized convert used for optional field only, that
  // log the error in place of using a box
  private[this] def convert[T](input: Option[String], tag: String, format: String, conv: String => T): Option[T] = {
    try {
      input.map(s => conv(s))
    } catch {
      case ex: Exception =>
        InventoryProcessingLogger.logEffect.warn(
          s"Ignoring '${tag}' content because it can't be converted to ${format}. Error is: ${ex.getMessage}"
        )
        None
    }
  }

  // same as above, but handle conversion to int
  private[this] def optInt(n: NodeSeq, tag: String):    Option[Int]    =
    convert(optText(n \ tag), tag, "Int", java.lang.Integer.parseInt)
  private[this] def optFloat(n: NodeSeq, tag: String):  Option[Float]  =
    convert(optText(n \ tag), tag, "Float", java.lang.Float.parseFloat)
  private[this] def optDouble(n: NodeSeq, tag: String): Option[Double] =
    convert(optText(n \ tag), tag, "Double", java.lang.Double.parseDouble)

  private def optTextHead(n: NodeSeq): Option[String] = for {
    head <- n.headOption
    text <- optText(head)
  } yield {
    text
  }

  def parseDate(n: NodeSeq, fmt: DateTimeFormatter): Option[DateTime] = {
    val date = optText(n).getOrElse("Unknown")
    try {
      Some(DateTime.parse(date, fmt))
    } catch {
      case e: IllegalArgumentException =>
        InventoryProcessingLogger.logEffect.debug(s"error when parsing node '${n}', value: ${date}")
        None
    }
  }

  override def fromXmlDoc(inventoryName: String, doc: NodeSeq): IOResult[Inventory] = {

    // hostname is a little special and may fail
    (for {
      base                        <- getHostname(doc)
      (hostname, customProperties) = base
      /*
       * Fusion Inventory gives a device id, but we don't exactly understand
       * how that id is generated and when/if it changes.
       * At least, the presence of that tag is a good indicator
       * that we have an actual Fusion Inventory file
       */
      deviceId                    <-
        optText(doc \ "DEVICEID").notOptional("The XML does not seems to be a Fusion Inventory file (no device id found)")
    } yield {

      var inventory = {

        // init a node inventory
        /*
         * It is not the role of the inventory parsing to assign UUID to node
         * and machine, we have special rules for that:
         * - node id is handled in RudderNodeIdParsing
         * - policy server is handled in RudderPolicyServerParsing
         * - machine id is handled in RudderMachineIdParsing
         */
        val node = NodeInventory(
          NodeSummary(
            NodeId("dummy-node-id"),
            PendingInventory,
            "dummy-root",
            hostname,
            UnknownOS(),
            NodeId("dummy-policy-server"),
            UndefinedKey
          )
        )

        // create a machine used as a template to modify - used a fake UUID that will be change latter
        val machine = MachineInventory(MachineUuid("dummy-machine-id"), PendingInventory, PhysicalMachineType)

        // idems for VMs and applications
        val vms          = List[MachineInventory]()
        val applications = List[Software]()
        val version      = processVersion(doc \\ ("Request") \ ("VERSIONCLIENT"))

        Inventory(
          inventoryName,
          deviceId,
          node,
          machine,
          version,
          vms.toSeq,
          applications,
          doc
        )
      }

      /*
       * Insert bios, manufacturer and system serial in the inventory
       * If Manufacturer or System Serial Number is already defined, skip them and write
       * a warn log
       */
      def addBiosInfosIntoInventory(
          bios:               Option[Bios],
          manufacturer:       Option[Manufacturer],
          systemSerialNumber: Option[String]
      ): Unit = {
        bios.foreach(x => inventory = inventory.modify(_.machine.bios).using(x +: _))

        manufacturer.foreach { x =>
          inventory.machine.manufacturer match {
            case None                       => // can update the manufacturer
              inventory = inventory.modify(_.machine.manufacturer).setTo(Some(x))
            case Some(existingManufacturer) => // cannot update it
              InventoryProcessingLogger.logEffect.warn(
                s"Duplicate Machine Manufacturer definition in the inventory: s{existingManufacturer} is the current value, skipping the other value ${x.name}"
              )
          }
        }
        systemSerialNumber.foreach { x =>
          inventory.machine.systemSerialNumber match {
            case None                             => // can update the System Serial Number
              inventory = inventory.modify(_.machine.systemSerialNumber).setTo(Some(x))
            case Some(existingSystemSerialNumber) => // cannot update it
              InventoryProcessingLogger.logEffect.warn(
                s"Duplicate System Serial Number definition in the inventory: s{existingSystemSerialNumber} is the current value, skipping the other value ${x}"
              )
          }
        }
      }

      /*
       * and now, actually parse !
       * Each line is a partial function of the form: Node => Unit
       */
      for (e <- (doc \\ "REQUEST").head.child) {
        e.label match {
          case "CONTENT" =>
            for (elt <- e.head.child) {
              elt.label match {
                case "ACCESSLOG"       =>
                  processAccessLog(elt).foreach(x => {
                    inventory =
                      inventory.modify(_.node.inventoryDate).setTo(Some(x)).modify(_.machine.inventoryDate).setTo(Some(x))
                  })
                case "BATTERIES"       => // TODO not sure about that
                case "BIOS"            =>
                  val (bios, manufacturer, systemSerialNumber) = processBios(elt)
                  addBiosInfosIntoInventory(bios, manufacturer, systemSerialNumber)
                case "CONTROLLERS"     =>
                  processController(elt).foreach(x => inventory = inventory.modify(_.machine.controllers).using(x +: _))
                case "CPUS"            => processCpu(elt).foreach(x => inventory = inventory.modify(_.machine.processors).using(x +: _))
                case "DRIVES"          =>
                  processFileSystem(elt).foreach(x => inventory = inventory.modify(_.node.fileSystems).using(x +: _))
                case "ENVS"            =>
                  processEnvironmentVariable(elt).foreach { x =>
                    inventory = inventory.modify(_.node.environmentVariables).using(x +: _)
                  }
                case "HARDWARE"        =>
                  processHardware(elt, inventory).foreach(x => inventory = inventory.copy(node = x._1, machine = x._2))
                case "INPUTS"          => // TODO keyborad, mouse, speakers
                case "LOCAL_USERS"     =>
                  processLocalAccount(elt).foreach { x =>
                    inventory = inventory.modify(_.node.accounts).using(a => (x +: a).distinct)
                  }
                case "MEMORIES"        =>
                  processMemory(elt).foreach(x => inventory = inventory.modify(_.machine.memories).using(x +: _))
                case "NETWORKS"        =>
                  processNetworks(elt).foreach(x => inventory = inventory.modify(_.node.networks).using(x +: _))
                case "OPERATINGSYSTEM" => inventory = processOsDetails(elt, inventory, e)
                case "PORTS"           => processPort(elt).foreach(x => inventory = inventory.modify(_.machine.ports).using(x +: _))
                case "PROCESSES"       =>
                  if (ignoreProcesses) ()
                  else
                    processProcesses(elt).foreach(x => inventory = inventory.modify(_.node.processes).using(x +: _))
                case "SLOTS"           => processSlot(elt).foreach(x => inventory = inventory.modify(_.machine.slots).using(x +: _))
                case "SOFTWARES"       => inventory = inventory.modify(_.applications).using(s => processSoftware(elt) +: s)
                case "SOUNDS"          => processSound(elt).foreach(x => inventory = inventory.modify(_.machine.sounds).using(x +: _))
                case "STORAGES"        =>
                  processStorage(elt).foreach(x => inventory = inventory.modify(_.machine.storages).using(x +: _))
                case "USBDEVICES"      => // TODO only digits for them, not sure we want to keep that as it is.
                case "VIDEOS"          => processVideo(elt).foreach(x => inventory = inventory.modify(_.machine.videos).using(x +: _))
                case "VIRTUALMACHINES" => processVms(elt).foreach(x => inventory = inventory.modify(_.node.vms).using(x +: _))
                case "SOFTWAREUPDATES" =>
                  processSoftwareUpdates(elt).foreach(x => inventory = inventory.modify(_.node.softwareUpdates).using(x :: _))
                case x                 =>
                  contentParsingExtensions.find(pf => pf.isDefinedAt((elt, inventory))).foreach { pf =>
                    inventory = pf((elt, inventory))
                  }
              }
            }
          case x         =>
            rootParsingExtensions.find(pf => pf.isDefinedAt((e, inventory))).foreach(pf => inventory = pf((e, inventory)))
        }
      }

      val demuxed                  = demux(inventory)
      // add all software ids to node
      val inventoryWithSoftwareIds = demuxed.modify(_.node.softwareIds).setTo(demuxed.applications.map(_.id))
      (inventoryWithSoftwareIds, customProperties)
    }).flatMap {
      case (inventoryWithSoftwareIds, customProperties) =>
        // <RUDDER> elements parsing must be done after software processing, because we get agent version from them
        processRudderElement(doc \\ "RUDDER", inventoryWithSoftwareIds, customProperties)
    }
  }

  // the whole content of the CUSTOM_PROPERTIES attribute should be valid JSON Array
  def processCustomProperties(xml: NodeSeq): List[CustomProperty] = {
    import net.liftweb.json.*

    parseOpt(xml.text) match {
      case None       => Nil
      case Some(json) =>
        json match { // only Json Array is OK
          case JArray(values) =>
            // each values must be an object, with each key a property name (and values... it depends :)
            values.flatMap {
              case JObject(fields) => fields.map(f => CustomProperty(f.name, f.value))
              case _               => Nil
            }
          case x              => Nil
        }
    }
  }

  // Use RUDDER/HOSTNAME first and if missing OS/FQDN ; also check for override in custom properties
  def getHostname(xml: NodeSeq): IOResult[(String, List[CustomProperty])] = {

    val CUSTOM_PROPERTY_OVERRIDE_HOSTNAME = "rudder_override_hostname"
    val CUSTOM_PROPERTY_ORIGINAL_HOSTNAME = "rudder_original_hostname"

    val invalidList = "localhost" :: "127.0.0.1" :: "::1" :: Nil
    def validHostname(hostname: String): Boolean = {

      /* Invalid cases are:
       * * hostname is null
       * * hostname is empty
       * * hostname is one of the invalid list
       */
      !(hostname == null || hostname.isEmpty() || invalidList.contains(hostname) || HostnameRegex.checkHostname(hostname).isLeft)
    }

    val customProperties   = processCustomProperties(xml \\ "RUDDER" \ "CUSTOM_PROPERTIES")
    // we take the first non-empty and valid hostname
    val sortedAlternatives = {
      optTextHead(xml \\ "RUDDER" \ "HOSTNAME") ::
      optTextHead(xml \\ "OPERATINGSYSTEM" \ "FQDN") ::
      Nil
    }

    val origHostname = sortedAlternatives.collectFirst { case Some(fqdn) if validHostname(fqdn) => fqdn }

    customProperties.collectFirst {
      case CustomProperty(CUSTOM_PROPERTY_OVERRIDE_HOSTNAME, JString(x)) if validHostname(x) => x
    } match {
      case Some(x) =>
        val orig = CustomProperty(CUSTOM_PROPERTY_ORIGINAL_HOSTNAME, JString(origHostname.getOrElse("none")))
        (x, orig :: customProperties).succeed
      case None    =>
        origHostname match {
          case Some(hostname) if validHostname(hostname) =>
            (hostname, customProperties).succeed
          case x                                         =>
            InventoryError
              .Inconsistency(
                s"Hostname could not be found in inventory (RUDDER/HOSTNAME [${x.getOrElse("")}] " +
                s"and custom overriding property '${CUSTOM_PROPERTY_OVERRIDE_HOSTNAME}' is not defined or is not a string " +
                s"and OPERATINGSYSTEM/FQDN [${x.getOrElse("")}] are missing or invalid: hostname " +
                s"can't be one of '${invalidList.mkString("','")}'"
              )
              .fail
        }
    }
  }

  /**
   * Parse Rudder Tag. In Rudder 4.3 and above, we don't need to parse anything but the <RUDDER> that looks like:
   *
   * <RUDDER>
   *   <AGENT>
   *     <AGENT_NAME>cfengine-community</AGENT_NAME>
   *     <AGENT_CERT>....</AGENT_CERT>
   *     <OWNER>root</OWNER>
   *     <POLICY_SERVER_HOSTNAME>127.0.0.1</POLICY_SERVER_HOSTNAME>
   *     <POLICY_SERVER_UUID>root</POLICY_SERVER_UUID>
   *   </AGENT>
   *   <AGENT_CAPABILITIES>
   *     <AGENT_CAPABILITY>cfengine</AGENT_CAPABILITY>
   *     ...
   *   </AGENT_CAPABILITIES>
   *   <HOSTNAME>rudder-snapshot.normation.com</HOSTNAME>
   *   <SERVER_ROLES>
   *     <SERVER_ROLE>rudder-server-root</SERVER_ROLE>
   *     ...
   *   </SERVER_ROLES>
   *   <UUID>root</UUID>
   * </RUDDER>
   *
   * Because it is fully supported since Rudder 4.1 (so migration are OK).
   */
  def processRudderElement(xml: NodeSeq, inventory: Inventory, customProperties: List[CustomProperty]): IOResult[Inventory] = {

    // Check that a seq contains only one or identical values, if not fails
    def uniqueValueInSeq[T](seq: Seq[T], errorMessage: String): IOResult[T] = {
      seq.distinct match {
        case entry if entry.lengthCompare(1) == 0 => entry.head.succeed
        case entry                                => InventoryError.Inconsistency(s"${errorMessage} (${entry.size} value(s) found in place of exactly 1)").fail
      }
    }

    // parse the sub list of AGENT_CAPABILITIES/AGENT_CAPABILITY, ignore other elements
    // note: agent capabilities should per agent to be really useful.
    def processAgentCapabilities(xml: NodeSeq): Set[AgentCapability] = {
      (xml \ "AGENT_CAPABILITIES" \ "AGENT_CAPABILITY").flatMap(e => optText(e).map(s => AgentCapability(s.toLowerCase))).toSet
    }

    // as a temporary solution, we are getting information from packages

    def findAgent(software: Seq[Software], agentType: AgentType): Option[AgentVersion] = {
      val agentSoftName = agentType.inventorySoftwareName.toLowerCase()
      for {
        soft    <- software.find(_.name.map(_.toLowerCase() contains agentSoftName).getOrElse(false))
        version <- soft.version
      } yield {
        AgentVersion(agentType.toAgentVersionName(version.value))
      }
    }

    /*
     * Process agents. We want to keep the maximum number of agents,
     * ie if there's two agents, and XML for one is not valid, still
     * keep the other.
     *
     * We build a list of Option[Agent] (but we log on console if an
     * agent is ignored).
     *
     */
    val agentList = ZIO.foreach((xml \\ "AGENT").toList) { agentXML =>
      val agent = for {
        agentName    <- optText(agentXML \ "AGENT_NAME").notOptional(
                          "could not parse agent name (tag AGENT_NAME) from Rudder specific inventory"
                        )
        rawAgentType <- ZIO.fromEither(AgentType.fromValue(agentName))
        agentType    <- if (rawAgentType == AgentType.CfeEnterprise) {
                          Inconsistency(
                            "CFEngine Enterprise/Nova agents are not supported anymore"
                          ).fail
                        } else { rawAgentType.succeed }

        rootUser       <-
          optText(agentXML \\ "OWNER").notOptional("could not parse rudder user (tag OWNER) from rudder specific inventory")
        policyServerId <- optText(agentXML \\ "POLICY_SERVER_UUID").notOptional(
                            "could not parse policy server id (tag POLICY_SERVER_UUID) from specific inventory"
                          )
        optCert         = optText(agentXML \ "AGENT_CERT")
        securityToken  <-
          optCert match {
            case Some(cert) => Certificate(cert).succeed
            case None       =>
              Inconsistency(
                "could not parse agent security token (tag AGENT_CERT), which is mandatory"
              ).fail
          }

      } yield {

        val version = findAgent(inventory.applications, agentType)

        Some((AgentInfo(agentType, version, securityToken, Set()), rootUser, policyServerId))
      }

      agent.catchAll { eb =>
        val e = Chained(s"Error when parsing an <RUDDER><AGENT> entry in '${inventory.name}', that agent will be ignored.", eb)
        InventoryProcessingLogger.error(e.fullMsg) *> None.succeed
      }
    }

    // to keep things consistent with previous behavior in 6.x, we don't fail if there is no rudder tag (perhaps it
    // has an impact on root server init).
    // We still fail on several rudder tag, which always was buggy
    val checkNumberOfRudderTag: IOResult[Unit] = {
      val size = xml.size
      if (size == 1) ZIO.unit
      else if (size < 1) {
        // even if we don't fail to keep behavior, we at leat log the problem.
        InventoryProcessingLogger.error(
          s"This rudder inventory does not have any <rudder> tag defined. This tag is mandatory."
        ) *> ZIO.unit
      } else {
        Inconsistency(s"A rudder inventory must have exactly one <rudder> tag, found ${size}").fail
      }
    }

    checkNumberOfRudderTag *> (
      (for {
        agents         <- agentList.map(_.flatten)
        agentOK        <- ZIO.when(agents.size < 1) {
                            Inconsistency(
                              s"No <AGENT> entry was correctly defined in <RUDDER> extension tag (missing or see previous errors)"
                            ).fail
                          }
        uuid           <- optText(xml \ "UUID").notOptional("could not parse uuid (tag UUID) from rudder specific inventory")
        rootUser       <- uniqueValueInSeq(agents.map(_._2), "could not parse rudder user (tag OWNER) from rudder specific inventory")
        policyServerId <-
          uniqueValueInSeq(agents.map(_._3), "could not parse policy server id (tag POLICY_SERVER_UUID) from specific inventory")
        // hostname is a special case processed in `processHostname`
        // capabilties should be per agent
        capabilities    = processAgentCapabilities(xml)
      } yield {
        (inventory
          .modify(_.node.main.rootUser)
          .setTo(rootUser)
          .modify(_.node.main.policyServerId)
          .setTo(NodeId(policyServerId))
          .modify(_.node.main.id)
          .setTo(NodeId(uuid))
          .modify(_.machine.id.value)
          .setTo(IdGenerator.md5Hash(uuid))
          .modify(_.node.agents)
          .setTo(agents.map(_._1.copy(capabilities = capabilities)))
          .modify(_.node.customProperties)
          .setTo(customProperties))
      }) catchAll { eb =>
        val fail = Chained(
          s"Error when parsing <RUDDER> extention node in inventory inventory with name '${inventory.name}'. Rudder extension attribute won't be available in inventory.",
          eb
        )
        InventoryProcessingLogger.error(fail.fullMsg) *> inventory.succeed
      }
    )
  }

  /**
   * This method look into all list of components to search for duplicates and remove
   * them, updating the "quantity" field accordingly.
   *
   * The processing of memories is a little special. In place of adjusting the quantity,
   * it updates the slotNumber (two memories can not share the same slot), setting position
   * in the list as key. And yes, that may be unstable from one inventory to the next one.
   */
  private[this] def demux(inventory: Inventory): Inventory = {
    // how can that be better ?
    var r = inventory
    r = r
      .modify(_.machine.bios)
      .setTo(inventory.machine.bios.groupBy(identity).map { case (x, seq) => x.copy(quantity = seq.size) }.toSeq)
    r = r
      .modify(_.machine.controllers)
      .setTo(inventory.machine.controllers.groupBy(identity).map { case (x, seq) => x.copy(quantity = seq.size) }.toSeq)
    r = r.modify(_.machine.memories).setTo(demuxMemories(inventory.machine.memories))
    r = r
      .modify(_.machine.ports)
      .setTo(inventory.machine.ports.groupBy(identity).map { case (x, seq) => x.copy(quantity = seq.size) }.toSeq)
    // Here we decided to take the first processor of the result of the groupBy name, some information could be missing
    // from other CPU in the list, this shouldn't be problematic (https://issues.rudder.io/issues/19988)
    r = r
      .modify(_.machine.processors)
      .setTo(inventory.machine.processors.groupBy(_.name).toList.map(_._2).map { case x => x.head.copy(quantity = x.size) }.toSeq)
    r = r
      .modify(_.machine.slots)
      .setTo(inventory.machine.slots.groupBy(identity).map { case (x, seq) => x.copy(quantity = seq.size) }.toSeq)
    r = r
      .modify(_.machine.sounds)
      .setTo(inventory.machine.sounds.groupBy(identity).map { case (x, seq) => x.copy(quantity = seq.size) }.toSeq)
    r = r
      .modify(_.machine.storages)
      .setTo(inventory.machine.storages.groupBy(identity).map { case (x, seq) => x.copy(quantity = seq.size) }.toSeq)
    r = r
      .modify(_.machine.videos)
      .setTo(inventory.machine.videos.groupBy(identity).map { case (x, seq) => x.copy(quantity = seq.size) }.toSeq)
    // node part
    r = demuxSameInterfaceDifferentIp(r)
    r = r.copy(applications =
      inventory.applications.groupBy(app => (app.name, app.version)).map { case (x, seq) => seq.head }.toSeq
    ) // seq.head is ok since its the result of groupBy
    r
  }

  private[this] def demuxMemories(
      memories: Seq[com.normation.inventory.domain.MemorySlot]
  ): Seq[com.normation.inventory.domain.MemorySlot] = {
    val duplicatedSlotsAndMemory =
      memories.groupBy(_.slotNumber).filter { case (x, seq) => x != DUMMY_MEM_SLOT_NUMBER && seq.size > 1 }
    val nonValideSlotNumber      = memories.filter(mem => mem.slotNumber == DUMMY_MEM_SLOT_NUMBER)
    val validSlotNumbers         = memories.filter(mem =>
      mem.slotNumber != DUMMY_MEM_SLOT_NUMBER
      && !duplicatedSlotsAndMemory.exists { case (i, _) => mem.slotNumber == i }
    )

    // set negative slotNumbers for duplicated and non valid
    val getNegative = (duplicatedSlotsAndMemory.flatMap(_._2) ++ nonValideSlotNumber).zipWithIndex.map {
      case (mem, i) => mem.copy(slotNumber = (-i - 1).toString)
    }
    validSlotNumbers ++ getNegative
  }

  /**
   * One interface can have several IP/mask/gateway/subnets. We have to
   * merge them when it's the case.
   */
  private[this] def demuxSameInterfaceDifferentIp(inventory: Inventory): Inventory = {
    val nets = inventory.node.networks
      .groupBy(_.name)
      .map {
        case (interface, networks) =>
          val referenceInterface = networks.head // we have at least one due to the groupBy
          val ips                = networks.flatMap(_.ifAddresses)
          val masks              = networks.flatMap(_.ifMask).distinct
          val gateways           = networks.flatMap(_.ifGateway).distinct
          val subnets            = networks.flatMap(_.ifSubnet).distinct
          val uniqIps            = ips.distinct
          if (uniqIps.size < ips.size) {
            InventoryProcessingLogger.logEffect.error(
              s"Network interface '${interface}' appears several time with same IPs. Taking only the first one, it is likelly a bug in fusion inventory."
            )
          }
          referenceInterface.copy(ifAddresses = uniqIps, ifMask = masks, ifGateway = gateways, ifSubnet = subnets)
      }
      .toSeq

    inventory.copy(node = inventory.node.copy(networks = nets))
  }

  /*
   * Try to normalize ARCH. We want to provide to the user something common: i686, x86_64, ppc64, etc.
   * We are going to use https://stackoverflow.com/questions/15036909/clang-how-to-list-supported-target-architectures
   * as reference names.
   * Fusion provides // we change to:
   * - amd64 => x86_64
   * - if OS == AIX => ppc64
   * - PowerPC => ppc (AIX already taken care of, and AFAIK we don't have any mean to be more precise, even with CPU info)
   * - PowerPC (other cases) => ppc
   * - 32-bit / 64-bit (windows) => x86 / x86_64
   * - things with x86_64 / x86 / i*86 in their name => x86_64 / x86 / i*86
   * - IA-64, i[3-9]86, x86, x86_64, arm.*, other => identical [lower case]
   */
  private[this] def normalizeArch(os: OsDetails)(s: String): String = {
    val ix86   = """.*(i[3-9]86).*""".r
    val x86    = """.*(x86|32-bit).*""".r
    val x86_64 = """.*(x86_64|amd64|64-bit).*""".r
    val aix    = """.*(aix).*""".r

    if (os.os == AixOS) {
      "ppc64"
    } else {
      s.toLowerCase() match {
        case "powerpc" => "ppc"
        // x64_64 must be check before x86 regex
        case x86_64(_) => "x86_64"
        case x86(_)    => "x86"
        case ix86(x)   => x
        case aix(_)    => "ppc64"
        case x         => x
      }
    }
  }

  // ******************************************** //
  // parsing implementation details for each tags //
  // ******************************************** //

  def processHardware(xml: NodeSeq, inventory: Inventory): Option[(NodeInventory, MachineInventory)] = {
    /*
     *
     * *** Operating System infos ***
     * ARCHNAME : architecture type.
     *      We want i686, x86_64, armv7l, etc
     * VMSYSTEM : The virtualization technologie used if the machine is a virtual machine.
     *      Can be: Physical (default), Xen, VirtualBox, Virtual Machine, VMware, QEMU, SolarisZone, Aix LPAR, Hyper-V
     *
     * MEMORY : RAM for that OS
     *      Ex: "512"
     * SWAP : The swap space in MB.
     *      Ex: "512"
     *
     * *** user last login ***
     * LASTLOGGEDUSER : The login of the last logged user.
     *      Ex: "jdoe"
     * DATELASTLOGGEDUSER : date of last user login.
     *      Ex: "Wed Mar 30 12:39"
     *
     * *** ip/networking ***
     * DEFAULTGATEWAY : default gateway
     *      Ex: "88.190.19.1"
     * DNS
     *      Ex: "88.190.254.14"
     * IPADDR : ip addresses, separated by "/"
     *      Ex: "88.190.19.131/10.94.94.46"
     * NAME : short version of the hostname (without domain)
     *      Ex: "dedibox-2" (when hostname is "dedibox-2.normation.com"
     *
     * *** diverse ***
     * ETIME : The time needed to run the inventory on the agent side. (Unit ?)
     *      Ex: "3"
     * TYPE :
     * DESCRIPTION : ???
     *      Ex: x86_64/00-00-00 00:47:51
     * UUID : mother board UUID (system id) - in 4.1, start to use it again, since it become more reliable
     *        than our MACHINEID tag.
     *
     * *** Windows only ***
     * ==> processed by OsDetails
     * WINOWNER
     * WINPRODID
     * WINPRODKEY
     * WINCOMPANY
     * WINLANG
     *
     * Ignored / deprecated
     *
     * version : OS version => in <OPERATINGSYSTEM>
     *      Ex: 2.6.32-5-amd64
     * OSCOMMENTS : Service Pack on Windows, kernel build date on Linux => in <OPERATINGSYSTEM>
     *      Ex: "#1 SMP Mon Mar 7 21:35:22 UTC 2011"
     * OSNAME : OS long description => in <OPERATINGSYSTEM>
     *      Ex: "Debian GNU/Linux 6.0.1 (squeeze)"
     * WORKGROUP : Windows workgroup or Linux host (or resolv.conf ?) => in <OPERATINGSYSTEM>
     *      Ex: "normation.com"
     *
     * USERID : The current user list, '/' is the delemiter. This field is
     *          deprecated, you should use the USERS section instead.
     *
     * PROCESSORT : processor description. Deprecated, OCS only.
     * PROCESSORN : number of processor. Deprecated.
     * PROCESSORS : The processor speed in MHz. Deprecated, see CPUS instead.
     * CHECKSUM : Deprecated, OCS only.
     * USERDOMAIN : This field is deprecated, you should use the USERS section instead.
     *
     */

    // update machine VM type
    val newMachine = (optText(xml \\ "VMSYSTEM") match {
      case None    => inventory.machine
      case Some(x) =>
        x.toLowerCase match {
          case "physical"        => inventory.machine.copy(machineType = PhysicalMachineType)
          case "xen"             => inventory.machine.copy(machineType = VirtualMachineType(Xen))
          case "virtualbox"      => inventory.machine.copy(machineType = VirtualMachineType(VirtualBox))
          case "virtual machine" => inventory.machine.copy(machineType = VirtualMachineType(UnknownVmType))
          case "vmware"          => inventory.machine.copy(machineType = VirtualMachineType(VMWare))
          case "qemu"            => inventory.machine.copy(machineType = VirtualMachineType(QEmu))
          case "solariszone"     => inventory.machine.copy(machineType = VirtualMachineType(SolarisZone))
          case "aix_lpar"        => inventory.machine.copy(machineType = VirtualMachineType(AixLPAR))
          case "hyper-v"         => inventory.machine.copy(machineType = VirtualMachineType(HyperV))
          case "bsdjail"         => inventory.machine.copy(machineType = VirtualMachineType(BSDJail))
          case "virtuozzo"       => inventory.machine.copy(machineType = VirtualMachineType(Virtuozzo))
          case "openvz"          => inventory.machine.copy(machineType = VirtualMachineType(OpenVZ))
          case "lxc"             => inventory.machine.copy(machineType = VirtualMachineType(LXC))
          case _                 => inventory.machine.copy(machineType = VirtualMachineType(UnknownVmType))
        }
    }).copy(mbUuid = optText(xml \\ "UUID").map(MotherBoardUuid.apply(_)).orElse(inventory.machine.mbUuid))

    //    s.name((h\"NAME") text) // what to do with that ?
    val newNode = inventory.node.copy(
      description = optText(xml \\ "OSCOMMENTS"),
      name = optText(xml \\ "NAME"),
      ram = optText(xml \\ "MEMORY").map(m => MemorySize(m + ramUnit)),
      swap = optText(xml \\ "SWAP").map(m => MemorySize(m + swapUnit)), // update arch ONLY if it is not yet defined

      archDescription =
        inventory.node.archDescription.orElse(optText(xml \\ "ARCHNAME").map(normalizeArch(inventory.node.main.osDetails))),
      lastLoggedUser = optText(xml \\ "LASTLOGGEDUSER"),
      lastLoggedUserTime = {
        try {
          optText(xml \\ "DATELASTLOGGEDUSER").map(date => userLoginDateTimeFormat.parseDateTime(date))
        } catch {
          case e: IllegalArgumentException =>
            InventoryProcessingLogger.logEffect.warn(
              "Error when parsing date for last user loggin. Awaited format is %s, found: %s".format(
                lastLoggedUserDatetimeFormat,
                (xml \\ "DATELASTLOGGEDUSER").text
              )
            )
            None
        }
      }
    )
    Some((newNode, newMachine))
  }

  def processOsDetails(xml: NodeSeq, inventory: Inventory, contentNode: NodeSeq): Inventory = {
    /*
     * ARCH           : operating system arch (i686, x86_64...)
     *
     * FULL_NAME      : full os description string
     *                  SUSE Linux Enterprise Server 11 (x86_64)
     * KERNEL_NAME    : the type of OS
     *                  "linux", "mswin32", etc
     * KERNEL_VERSION : the full version of the kernel
     *                  "2.6.32.12-0.7-default"
     * NAME           : name os the os / distribution
     *                  "windows", "debian", etc
     * SERVICE_PACK   : a service pack if it exists
     *                  (for Windows, SUSE, etc)
     * VERSION        : version of the os
     *                  "5.08", "11.04", "N/A" for windows
     * FQDN           : the fully qualified hostname
     */
    val osDetail: OsDetails = {
      val osType = optText(xml \\ "KERNEL_NAME").getOrElse("")
      val osName = optText(xml \\ "NAME").getOrElse("")

      val fullName      = optText(xml \\ "FULL_NAME").getOrElse("")
      val kernelVersion = new Version(optText(xml \\ "KERNEL_VERSION").getOrElse("N/A"))

      // format for AIX service pack used by fusion is xx-YYWW =>
      // First part two digit (xx) are the sp,
      // Second part is the date of the release of the SP ( yeat and week of year
      val aixSPPattern = "(\\d{2})-\\d{4}".r
      val SPText       = optText(xml \\ "SERVICE_PACK").map {
        // first digit can be 0, remove it by transforming in Int
        case aixSPPattern(sp) => s"${sp.toInt}"
        // Not matching keep value
        case sp               => sp
      }
      val servicePack  = SPText

      val version = new Version(optText(xml \\ "VERSION").getOrElse("N/A"))

      // find os type, and name
      val detectedOs: OsType = ParseOSType.getType(osType, osName, fullName)

      // find details and enhance for Windows and AIX case
      ParseOSType.getDetails(detectedOs, fullName, version, servicePack, kernelVersion) match {
        case w: Windows =>
          w.copy(
            userDomain = optText(contentNode \\ "USERDOMAIN"),
            registrationCompany = optText(contentNode \\ "WINCOMPANY"),
            productId = optText(contentNode \\ "WINPRODID"),
            productKey = optText(contentNode \\ "WINPRODKEY")
          )

        case a: Aix =>
          // Aix version is stocked in HARDWARE -> OSVERSION,
          // but we move that value in OPERATING_SYSTEM => KERNEL_VERSION (see checkKernelVersion in PreInventoryParserCheckConsistency.scala )
          // If We are on aix we should use that value instead of the one stored in OPERATING_SYSTEM => VERSION

          // aix Version format is decomposed into 3 fields: Major (M), minor(m) and Technology level (T)
          // the format is Mmxx-TT (like 5300-12 => 5.3.12)
          val aixVersionPattern = "(\\d)(\\d)\\d\\d-(\\d{2})".r
          val versionText       = optText(xml \\ "KERNEL_VERSION").getOrElse("N/A") match {
            case aixVersionPattern(major, minor, techLevel) =>
              // first digit in Technology can be 0, remove it by transforming in Int
              s"${major}.${minor}.${techLevel.toInt}"
            // Not matching, keep value
            case v                                          => v
          }
          val aixVersion        = new Version(versionText)

          a.copy(version = aixVersion)

        // other cases are left unchanged
        case x => x
      }
    }
    // for timezone, if any is missing then None
    val timezone = (optText(xml \ "TIMEZONE" \ "NAME"), optText(xml \ "TIMEZONE" \ "OFFSET")) match {
      case (Some(name), Some(offset)) => Some(NodeTimezone(name, offset))
      case _                          => None
    }

    // for arch, we want to keep the value only in the case where OPERATINGSYSTEM/ARCH is missing
    val arch = optText(xml \\ "ARCH").map(normalizeArch(inventory.node.main.osDetails)).orElse(inventory.node.archDescription)

    (inventory
      .modify(_.node.main.osDetails)
      .setTo(osDetail)
      .modify(_.node.timezone)
      .setTo(timezone)
      .modify(_.node.archDescription)
      .setTo(arch))
  }

  /**
   * We only keep name in local user account
   */
  def processLocalAccount(d: Node): Option[String] = {
    // on linux, the interesting field is LOGIN, on Windows, it's NAME (and there is no Login)
    // (on linux, NAME contains strange things like "Rudder,,,")
    optText(d \ "LOGIN").orElse(optText(d \ "NAME"))
  }

  /**
   * The key used to identify filesystem is in order:
   * - the mount_point describe in TYPE (yes...), for Linux system, for example "/home", etc
   * - the volume letter for Windows ("A", etc)
   * - the volumn, if nothing else is available
   */
  def processFileSystem(d: NodeSeq): Option[FileSystem] = {
    val mount_point = optText(d \ "TYPE")
    val letter      = optText(d \ "LETTER")
    val volume      = optText(d \ "VOLUMN")
    // volum or letter is mandatory
    letter.orElse(mount_point).orElse(volume) match {
      case None             =>
        InventoryProcessingLogger.logEffect.debug("Ignoring FileSystem entry because missing tag TYPE and LETTER")
        InventoryProcessingLogger.logEffect.debug(d.toString())
        None
      case Some(mountPoint) =>
        Some(
          FileSystem(
            mountPoint = mountPoint,
            name = optText(d \ "FILESYSTEM"),
            freeSpace = optText(d \ "FREE").map(m => MemorySize(m + fsSpaceUnit)),
            totalSpace = optText(d \ "TOTAL").map(m => MemorySize(m + fsSpaceUnit)),
            fileCount = optInt(d, "NUMFILES")
          )
        )
    }
  }

  def processNetworks(n: NodeSeq): Option[Network] = {
    import com.normation.inventory.domain.InetAddressUtils.*
    // in fusion inventory, we may have several IP address separated by comma
    def getAddresses(addressString: String): Seq[InetAddress] = {
      for {
        addr <- addressString.split(",")
        a    <- InetAddressUtils.getAddressByName(addr)
      } yield a
    }.toSeq

    /*
     * Normally, Fusion put in DESCRIPTION the interface name (eth0, etc).
     * This is missing for Android, so we are going to use the TYPE
     * (wifi, ethernet, etc) as an identifier in that case.
     */

    optText(n \ "DESCRIPTION").orElse(optText(n \ "TYPE")) match {
      case None       =>
        InventoryProcessingLogger.logEffect.debug("Ignoring entry Network because tag DESCRIPTION is empty")
        InventoryProcessingLogger.logEffect.debug(n.toString())
        None
      case Some(desc) =>
        // in a single NETWORK element, we can have both IPV4 and IPV6
        // variant for address, gateway, subnet, mask
        def getBothAddresses(xml: NodeSeq, ipv4Attr: String, ipv6Attr: String): Seq[InetAddress] = {
          val ipv4 = optText(xml \ ipv4Attr) match {
            case None    => Seq()
            case Some(a) => getAddresses(a)
          }
          val ipv6 = optText(xml \ ipv6Attr) match {
            case None    => Seq()
            case Some(a) =>
              // Ipv6 addresses from fusion inventory may have a / part we need to remove it to get valid ipv6 address
              val ip = a takeWhile { _ != '/' }
              getAddresses(ip)
          }

          ipv4 ++ ipv6
        }

        Some(
          Network(
            name = desc,
            ifAddresses = getBothAddresses(n, "IPADDRESS", "IPADDRESS6"),
            ifDhcp = optText(n \ "IPDHCP").flatMap(getAddressByName(_)),
            ifGateway = getBothAddresses(n, "IPGATEWAY", "IPGATEWAY6"),
            ifMask = getBothAddresses(n, "IPMASK", "IPMASK6"),
            ifSubnet = getBothAddresses(n, "IPSUBNET", "IPSUBNET6"),
            macAddress = optText(n \ "MACADDR"),
            status = optText(n \ "STATUS"),
            ifType = optText(n \ "TYPE"),
            typeMib = optText(n \ "TYPEMIB"),
            speed = optText(n \ "SPEED")
          )
        )
    }
  }

  def processSoftware(s: NodeSeq): Software = {
    Software(
      id = SoftwareUuid(uuidGen.newUuid),
      name = optText(s \ "NAME"),
      version = optText(s \ "VERSION").map(x => new Version(x)),
      description = optText(s \ "COMMENTS"),
      editor = optText(s \ "PUBLISHER").map(e => new SoftwareEditor(e)),
      sourceName = optText(s \ "SOURCE_NAME"),
      sourceVersion = optText(s \ "SOURCE_VERSION").map(x => new Version(x))
    )
  }

  /*
     we have a list of UPDATE elements:
      <UPDATE>
        <NAME>rudder-agent</NAME>
        <ARCH>x86_64</ARCH>
        <FROM>yum</FROM>
        <VERSION>7.0.1.release.EL.7</VERSION>
      </UPDATE>
   */
  def processSoftwareUpdates(e: NodeSeq): Option[SoftwareUpdate] = {
    def getOrError(e: NodeSeq, f: String) = {
      optTextHead(e \ f).notOptionalPure(s"Missing mandatory tag under SOTWAREUPDATES: '${f}'")
    }
    (for {
      n   <- getOrError(e, "NAME")
      v    = optText(e \ "VERSION")
      a    = optText(e \ "ARCH")
      f    = optText(e \ "FROM")
      k    = optText(e \ "KIND")
               .map(s => SoftwareUpdateKind.parse(s).getOrElse(SoftwareUpdateKind.Other(s)))
               .getOrElse(SoftwareUpdateKind.None)
      s    = optText(e \ "SOURCE")
      d    = optText(e \ "DESCRIPTION")
      sev  = optText(e \ "SEVERITY").map(s => SoftwareUpdateSeverity.parse(s).getOrElse(SoftwareUpdateSeverity.Other(s)))
      date = optText(e \ "DATE")
      ids  = (e \ "ID").toList.flatMap(_.text.split(","))
    } yield {
      val idss = if (ids.isEmpty) None else Some(ids)
      // date should be normalized, but in case of error, report and set to None
      val dd   = date.flatMap(x => {
        JsonSerializers.parseSoftwareUpdateDateTime(x) match {
          case Left(err)    =>
            InventoryProcessingLogger.info(s"Error when parsing date for software update ${n}: ${err}")
            None
          case Right(value) =>
            Some(value)
        }
      })
      SoftwareUpdate(n, v, a, f, k, s, d, sev, dd, idss)
    }) match {
      case Left(err)    =>
        InventoryProcessingLogger.logEffect.warn(s"Ignoring malformed software update: '${err.fullMsg}': ${e}")
        None
      case Right(value) =>
        Some(value)
    }
  }

  /**
   * Process the bios, and return its, plus the system manufacturer and the system serial number
   */
  def processBios(b: NodeSeq): (Option[Bios], Option[Manufacturer], Option[String]) = {
    // Fetching the manufacturer. It should be in SMANUFACTURER, except if it is
    // empty, or the value is "Not available". In these case, the correct entry is MMANUFACTURER
    val systemManufacturer = (optText(b \ "SMANUFACTURER") match {
      case None                  => optText(b \ "MMANUFACTURER")
      case Some("Not available") => optText(b \ "MMANUFACTURER")
      case Some(text)            => Some(text)
    }).map(m => new Manufacturer(m))

    val systemSerialNumber = optText(b \ "SSN")

    val bios = optText(b \ "SMODEL") match {
      case None        =>
        InventoryProcessingLogger.logEffect.debug("Ignoring entry Bios because SMODEL is empty")
        InventoryProcessingLogger.logEffect.debug(b.toString())
        None
      case Some(model) =>
        val date = {
          try {
            optText(b \ "BDATE").map(d => biosDateTimeFormat.parseDateTime(d))
          } catch {
            case e: IllegalArgumentException =>
              InventoryProcessingLogger.logEffect.warn(
                "Error when parsing date for Bios. Awaited format is %s, found: %s".format(biosDateFormat, (b \ "BDATE").text)
              )
              None
          }
        }

        Some(
          Bios(
            name = model,
            releaseDate = date,
            editor = optText(b \ "BMANUFACTURER").map(s => new SoftwareEditor(s)),
            version = optText(b \ "BVERSION").map(v => new Version(v))
          )
        )
    }

    (bios, systemManufacturer, systemSerialNumber)
  }

  def processController(c: NodeSeq): Option[Controller] = {
    optText(c \ "NAME") match {
      case None       =>
        InventoryProcessingLogger.logEffect.debug("Ignoring entry Controller because tag NAME is empty")
        InventoryProcessingLogger.logEffect.debug(c.toString())
        None
      case Some(name) =>
        Some(
          Controller(
            name = name,
            manufacturer = optText(c \ "MANUFACTURER").map(m => new Manufacturer(m)),
            cType = optText(c \ "TYPE")
          )
        )
    }
  }

  private[this] val DUMMY_MEM_SLOT_NUMBER = "DUMMY"

  /**
   * For memory, the numslot is used for key.
   * On several embeded devices (Android especially), there is no
   * such information, so we use a false numslot, that will be
   * change after the full processing of the inventory.
   */
  def processMemory(m: NodeSeq): Option[MemorySlot] = {
    // add memory. Add all slots, but add capacity other than numSlot only for full slot
    val slot = optText(m \ "NUMSLOTS") match {
      case None       =>
        InventoryProcessingLogger.logEffect.debug("Memory is missing tag NUMSLOTS, assigning a negative value for num slot")
        InventoryProcessingLogger.logEffect.debug(m.toString())
        DUMMY_MEM_SLOT_NUMBER
      case Some(slot) => slot
    }

    (m \ "CAPACITY").text.toLowerCase match {
      case "" | "no" =>
        Some(MemorySlot(slot))
      case c         =>
        Some(
          MemorySlot(
            slotNumber = slot,
            capacity = Some(MemorySize(c + slotMemoryUnit)),
            caption = optText(m \ "CAPTION"),
            description = optText(m \ "DESCRIPTION"),
            serialNumber = optText(m \ "SERIALNUMBER"),
            speed = optText(m \ "SPEED"),
            memType = optText(m \ "TYPE")
          )
        )
    }
  }

  def processPort(p: NodeSeq): Option[Port] = {
    optText(p \ "NAME") match {
      case None       =>
        InventoryProcessingLogger.logEffect.debug("Ignoring entry Port because tag NAME is empty")
        InventoryProcessingLogger.logEffect.debug(p.toString())
        None
      case Some(name) =>
        /*It seems that CAPTION and DESCRIPTION
         * play the same role in turn, so we have to
         * check which one is "None" and take the other.
         * In case both are different from none, just aggregate
         * with the pattern: "CAPTION || DESCRIPTION"
         */
        val none        = Some("None")
        val l           = optText(p \ "CAPTION") :: optText(p \ "DESCRIPTION") :: Nil
        val description = l.filter(_ != none).distinct match {
          case Some(a) :: Some(b) :: t => Some(a + "||" + b)
          case x :: t                  => x
          case _                       => None
        }

        /*
         * Name may be not empty, but equals to "Not Specified".
         * In such a case, we append "(TYPE)" to allows to have
         * 'Not Specified (USB)', 'Not Specified (SATA)', etc.
         */
        val ptype    = optText(p \ "TYPE")
        val realName = {
          if (name.toLowerCase.trim == "not specified")
            name + ptype.map(" (" + _ + ")").getOrElse("")
          else name
        }
        Some(
          Port(
            name = realName,
            description = description,
            pType = ptype
          )
        )
    }
  }

  def processSlot(s: NodeSeq): Option[Slot] = {
    /*
     * Depending if we are on a vm, windows, linux,
     * other things, the identifier may be
     * NAME or DESIGNATION
     * So we take the one defined if only one is,
     * or concatenate the two
     */
    val name = (optText(s \ "NAME") :: optText(s \ "DESIGNATION") :: Nil).filter(_.isDefined) match {
      case Some(x) :: Some(y) :: _ => Some(x + " - " + y)
      case Some(x) :: _            => Some(x)
      case _                       => None
    }

    name match {
      case None     =>
        InventoryProcessingLogger.logEffect.debug("Ignoring entry Slot because tags NAME and DESIGNATION are empty")
        InventoryProcessingLogger.logEffect.debug(s.toString())
        None
      case Some(sl) =>
        Some(
          Slot(
            name = sl,
            description = optText(s \ "DESCRIPTION"),
            status = optText(s \ "STATUS")
          )
        )
    }
  }

  def processSound(s: NodeSeq): Option[Sound] = {
    /*
     * We take the name+manufacturer as key as it
     * seems to be the most complete information
     */
    val name = (optText(s \ "NAME") :: optText(s \ "MANUFACTURER") :: Nil).filter(_.isDefined) match {
      case Some(x) :: Some(y) :: _ => Some(x + " - " + y)
      case Some(x) :: _            => Some(x)
      case _                       => None
    }

    name match {
      case None     =>
        InventoryProcessingLogger.logEffect.debug("Ignoring entry Sound because tags NAME and MANUFACTURER are empty")
        InventoryProcessingLogger.logEffect.debug(s.toString())
        None
      case Some(so) =>
        Some(
          Sound(
            name = so,
            description = optText(s \ "DESCRIPTION")
          )
        )
    }
  }

  def processStorage(s: NodeSeq): Option[Storage] = {
    /*
     * It seems that the only things mostly
     * stable between windows/linux/vm
     * is the model
     */
    optText(s \ "NAME") match {
      case None       =>
        InventoryProcessingLogger.logEffect.debug("Ignoring entry Storage because tag NAME is empty")
        InventoryProcessingLogger.logEffect.debug(s.toString())
        None
      case Some(name) =>
        Some(
          Storage(
            name = name,
            description = optText(s \ "DESCRIPTION"),
            size = optText(s \ "DISKSIZE").map(x => MemorySize(x + "Mo")),
            firmware = optText(s \ "FIRMWARE"),
            manufacturer = optText(s \ "MANUFACTURER").map(x => new Manufacturer(x)),
            model = optText(s \ "MODEL"),
            serialNumber = optText(s \ "SERIALNUMBER"),
            sType = optText(s \ "TYPE")
          )
        )
    }
  }

  /**
   * Process the Video.
   * The name is used by default for the key.
   * If there is no name but one resolution, we use that for the key.
   * Return None in other case.
   */
  def processVideo(v: NodeSeq): Option[Video] = {
    optText(v \ "NAME").orElse(optText(v \ "RESOLUTION")) match {
      case None       =>
        InventoryProcessingLogger.logEffect.debug("Ignoring entry Video because tag NAME is empty")
        InventoryProcessingLogger.logEffect.debug(v.toString())
        None
      case Some(name) =>
        Some(
          Video(
            name = name,
            description = None,
            chipset = optText(v \ "CHIPSET"),
            memory = optText(v \ "MEMORY").map(s => MemorySize(s + "Mo")),
            resolution = optText(v \ "RESOLUTION")
          )
        )
    }
  }

  def processCpu(c: NodeSeq): Option[Processor] = {
    optText(c \ "NAME") match {
      case None       =>
        InventoryProcessingLogger.logEffect.debug("Ignoring entry CPU because tag MANIFACTURER and ARCH are empty")
        InventoryProcessingLogger.logEffect.debug(c.toString())
        None
      case Some(name) =>
        Some(
          Processor(
            manufacturer = optText(c \ "MANUFACTURER").map(new Manufacturer(_)),
            arch = optText(c \ "ARCH"),
            name = name,
            speed = optFloat(c, "SPEED").map(_.toInt),
            externalClock = optFloat(c, "EXTERNAL_CLOCK"),
            core = optInt(c, "CORE"),
            thread = optInt(c, "THREAD"),
            cpuid = optText(c \ "ID"),
            stepping = optInt(c, "STEPPING"),
            model = optInt(c, "MODEL"),
            family = optInt(c, "FAMILYNUMBER"),
            familyName = optText(c \ "FAMILYNAME")
          )
        )
    }
  }

  def processEnvironmentVariable(ev: NodeSeq): Option[EnvironmentVariable] = {
    // we need to keep the key exactly as it is, see https://issues.rudder.io/issues/20984
    (ev \ "KEY").text match {
      case null | "" =>
        InventoryProcessingLogger.logEffect.debug("Ignoring entry Envs because tag KEY is empty")
        InventoryProcessingLogger.logEffect.debug(ev.toString())
        None
      case key       => // even accept only blank key, if the OS accepted it
        Some(
          EnvironmentVariable(
            name = key,
            value = optText(ev \ "VAL")
          )
        )
    }
  }

  def processVms(vm: NodeSeq): Option[VirtualMachine] = {
    optText(vm \ "UUID") match {
      case None       =>
        InventoryProcessingLogger.logEffect.debug("Ignoring entry VirtualMachine because tag UUID is empty")
        InventoryProcessingLogger.logEffect.debug(vm.toString())
        None
      case Some(uuid) =>
        Some(
          VirtualMachine(
            vmtype = optText(vm \ "VMTYPE"),
            subsystem = optText(vm \ "SUBSYSTEM"),
            owner = optText(vm \ "OWNER"),
            name = optText(vm \ "NAME"),
            status = optText(vm \ "STATUS"),
            vcpu = optInt(vm, "VCPU"),
            memory = optText(vm \ "MEMORY"),
            uuid = new MachineUuid(uuid)
          )
        )
    }
  }

  def processProcesses(proc: NodeSeq): Option[Process] = {
    optInt(proc, "PID") match {
      case None      =>
        InventoryProcessingLogger.logEffect.debug("Ignoring entry Process because tag PID is invalid")
        InventoryProcessingLogger.logEffect.debug(proc.toString())
        None
      case Some(pid) =>
        Some(
          Process(
            pid = pid,
            cpuUsage = optFloat(proc, "CPUUSAGE"),
            memory = optFloat(proc, "MEM"),
            commandName = optText(proc \ "CMD"),
            started = optText(proc \ "STARTED"),
            tty = optText(proc \ "TTY"),
            user = optText(proc \ "USER"),
            virtualMemory = optDouble(proc, "VIRTUALMEMORY")
          )
        )
    }
  }

  def processAccessLog(accessLog: NodeSeq): Option[DateTime] = {
    val fmt = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss")
    optText(accessLog \ "LOGDATE") match {
      case None       => None;
      case Some(date) =>
        try {
          Some(DateTime.parse(date, fmt))
        } catch {
          case e: IllegalArgumentException =>
            InventoryProcessingLogger.logEffect.warn("error when parsing ACCESSLOG, reason %s".format(e.getMessage()))
            None
        }
    }
  }

  def processVersion(vers: NodeSeq): Option[Version] = {
    optText(vers) match {
      case None       => None;
      case Some(vers) => Some(new Version(vers))
    }
  }
}

object OptText {
  /*
   * A method that retrieve the text value of an XML and
   * clean it:
   * - remove leading/trailing spaces ;
   * - remove multiple space
   */
  def optText(n: NodeSeq): Option[String] = n.text match {
    case null | "" => None
    case s         =>
      s.trim().replaceAll("""[\p{Blank}]+""", " ") match {
        case ""   => None
        case text => Some(text)
      }
  }
}
