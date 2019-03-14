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

import com.normation.inventory.domain._
import com.normation.inventory.domain.AgentType._
import com.normation.inventory.domain.NodeTimezone
import com.normation.inventory.services.provisioning._
import com.normation.utils.StringUuidGenerator
import java.net.InetAddress
import java.util.Locale
import net.liftweb.common._
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import scala.xml._

class FusionReportUnmarshaller(
    uuidGen:StringUuidGenerator,
    rootParsingExtensions:List[FusionReportParsingExtension] = Nil,
    contentParsingExtensions:List[FusionReportParsingExtension] = Nil,
    biosDateFormat : String = "MM/dd/yyyy",
    slotMemoryUnit : String = "Mo",
    ramUnit : String = "Mo",
    swapUnit : String = "Mo",
    fsSpaceUnit : String = "Mo",
    lastLoggedUserDatetimeFormat : String = "EEE MMM dd HH:mm"
) extends ParsedReportUnmarshaller with Loggable {

  import OptText.optText

  val userLoginDateTimeFormat = DateTimeFormat.forPattern(lastLoggedUserDatetimeFormat).withLocale(Locale.ENGLISH)
  val biosDateTimeFormat = DateTimeFormat.forPattern(biosDateFormat).withLocale(Locale.ENGLISH)

  //extremelly specialized convert used for optional field only, that
  //log the error in place of using a box
  private[this] def convert[T](input: Option[String], tag: String, format: String, conv: String => T): Option[T] = {
    try {
      input.map( s => conv(s))
    } catch {
      case ex: Exception =>
        logger.warn(s"Ignoring '${tag}' content because it can't be converted to ${format}. Error is: ${ex.getMessage}")
        None
    }
  }

  //same as above, but handle conversion to int
  private[this] def optInt   (n:NodeSeq, tag: String): Option[Int]    = convert(optText(n \ tag), tag, "Int"   , java.lang.Integer.parseInt)
  private[this] def optFloat (n:NodeSeq, tag: String): Option[Float]  = convert(optText(n \ tag), tag, "Float" , java.lang.Float.parseFloat)
  private[this] def optDouble(n:NodeSeq, tag: String): Option[Double] = convert(optText(n \ tag), tag, "Double", java.lang.Double.parseDouble)

  private def optTextHead(n:NodeSeq) : Option[String] = for {
    head <- n.headOption
    text <- optText(head)
  } yield {
    text
  }

  def parseDate(n:NodeSeq,fmt:DateTimeFormatter) = {
    val date = optText(n).getOrElse("Unknown")
    try {
      Some(DateTime.parse(date,fmt))
    } catch {
      case e : IllegalArgumentException =>
        logger.debug("error when parsing node %s, value %s ".format(n,date))
      None
  } }

  override def fromXmlDoc(reportName:String, doc:NodeSeq) : Box[InventoryReport] = {

    // hostname is a little special and may fail
    for {
      hostname <- getHostname(doc)
    } yield {

      var report = {
        /*
         * Fusion Inventory gives a device id, but we don't exactly understand
         * how that id is generated and when/if it changes.
         * At least, the presence of that tag is a good indicator
         * that we have an actual Fusion Inventory report
         */
        val deviceId = optText(doc \ "DEVICEID").getOrElse {
          return Failure("The XML does not seems to be a Fusion Inventory report (no device id found)")
        }

        //init a node inventory
        /*
         * It is not the role of the report parsing to assign UUID to node
         * and machine, we have special rules for that:
         * - node id is handled in RudderNodeIdParsing
         * - policy server is handled in RudderPolicyServerParsing
         * - machine id is handled in RudderMachineIdParsing
         */
        val node = NodeInventory(
          NodeSummary(
              NodeId("dummy-node-id")
            , PendingInventory
            , "dummy-root"
            , hostname
            , UnknownOS()
            , NodeId("dummy-policy-server")
            , UndefinedKey
          )
        )

        //create a machine used as a template to modify - used a fake UUID that will be change latter
        val machine = MachineInventory(MachineUuid("dummy-machine-id"), PendingInventory, PhysicalMachineType)

        //idems for VMs and applications
        val vms = List[MachineInventory]()
        val applications =  List[Software]()
        val version= processVersion(doc\\("Request")\("VERSIONCLIENT"));
        InventoryReport(
          reportName,
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
        def addBiosInfosIntoReport(
            bios              : Option[Bios]
          , manufacturer      : Option[Manufacturer]
          , systemSerialNumber: Option[String]) : Unit = {
          bios.foreach {x => report = report.copy( machine = report.machine.copy( bios = x +: report.machine.bios ) ) }

          manufacturer.foreach{ x =>
            report.machine.manufacturer match {
              case None => // can update the manufacturer
                report = report.copy(machine = report.machine.copy(manufacturer = Some(x)))
              case Some(existingManufacturer) => //cannot update it
                logger.warn(s"Duplicate Machine Manufacturer definition in the inventory: s{existingManufacturer} is the current value, skipping the other value ${x}")
            }
          }
          systemSerialNumber.foreach{ x =>
            report.machine.systemSerialNumber match {
              case None => // can update the System Serial Number
                report = report.copy(machine = report.machine.copy(systemSerialNumber = Some(x)))
              case Some(existingSystemSerialNumber) => //cannot update it
                logger.warn(s"Duplicate System Serial Number definition in the inventory: s{existingSystemSerialNumber} is the current value, skipping the other value ${x}")
            }
          }
        }

      /*
       * and now, actually parse !
       * Each line is a partial function of the form: Node => Unit
       */
      for(e <- (doc \\ "REQUEST").head.child) { e.label match {
        case "CONTENT" => for( elt <- e.head.child ) { elt.label match {
          case "ACCESSLOG"   => processAccessLog(elt).foreach(x => report = report.copy ( node = report.node.copy( inventoryDate = Some(x) ), machine = report.machine.copy ( inventoryDate = Some(x) ) ))
          case "BATTERIES"   => //TODO not sure about that
          case "BIOS"        => val (bios, manufacturer, systemSerialNumber) = processBios(elt)
                                addBiosInfosIntoReport(bios, manufacturer, systemSerialNumber)
          case "CONTROLLERS" => processController(elt).foreach { x => report = report.copy( machine = report.machine.copy( controllers = x +: report.machine.controllers ) ) }
          case "CPUS"        => processCpu(elt).foreach { x => report = report.copy( machine = report.machine.copy( processors = x +: report.machine.processors ) ) }
          case "DRIVES"      => processFileSystem(elt).foreach { x => report = report.copy( node = report.node.copy( fileSystems = x +: report.node.fileSystems ) ) }
          case "ENVS"        => processEnvironmentVariable(elt).foreach {x => report = report.copy(node = report.node.copy (environmentVariables = x +: report.node.environmentVariables ) ) }
          case "HARDWARE"    =>  processHardware(elt, report).foreach(x => report = report.copy(node = x._1, machine = x._2))
          case "INPUTS"      => //TODO keyborad, mouse, speakers
          case "LOCAL_USERS" => processLocalAccount(elt).foreach { x => report = report.copy(node = report.node.copy( accounts = (x +: report.node.accounts).distinct)) }
          case "MEMORIES"    => processMemory(elt).foreach { x => report = report.copy( machine = report.machine.copy( memories = x +: report.machine.memories ) ) }
          case "NETWORKS"    => processNetworks(elt).foreach { x => report = report.copy( node = report.node.copy( networks = x +: report.node.networks ) ) }
          case "OPERATINGSYSTEM" => report = processOsDetails(elt, report, e)
          case "PORTS"       => processPort(elt).foreach { x => report = report.copy( machine = report.machine.copy( ports = x +: report.machine.ports ) ) }
          case "PROCESSES"   => processProcesses(elt).foreach { x => report = report.copy( node = report.node.copy ( processes = x +: report.node.processes))}
          case "SLOTS"       => processSlot(elt).foreach { x => report = report.copy( machine = report.machine.copy( slots = x +: report.machine.slots ) ) }
          case "SOFTWARES"   => report = report.copy( applications  = processSoftware(elt) +: report.applications )
          case "SOUNDS"      => processSound(elt).foreach { x => report = report.copy( machine = report.machine.copy( sounds = x +: report.machine.sounds ) ) }
          case "STORAGES"    => processStorage(elt).foreach { x => report = report.copy( machine = report.machine.copy( storages = x +: report.machine.storages ) ) }
          case "USBDEVICES"  => //TODO only digits for them, not sure we want to keep that as it is.
          case "VIDEOS"      => processVideo(elt).foreach { x => report = report.copy( machine = report.machine.copy( videos = x +: report.machine.videos ) ) }
          case "VIRTUALMACHINES" => processVms(elt).foreach { x =>  report = report.copy(node  = report.node.copy( vms = x +: report.node.vms) ) }
          case x => contentParsingExtensions.find {
              pf => pf.isDefinedAt((elt,report))
            }.foreach { pf =>
              report = pf((elt,report))
            }
        } }
        case x => rootParsingExtensions.find {
            pf => pf.isDefinedAt((e,report))
          }.foreach { pf =>
            report = pf((e,report))
          }
      } }

      val demuxed = demux(report)
      val reportWithSoftwareIds = demuxed.copy(
         //add all software ids to node
        node = demuxed.node.copy(
            softwareIds = demuxed.applications.map( _.id )
        )
      )
      // <RUDDER> elements parsing must be done after software processing, because we get agent version from them
      processRudderElement(doc \\ "RUDDER", reportWithSoftwareIds)
    }
  }

  // Use RUDDER/HOSTNAME first and if missing OS/FQDN
  def getHostname(xml : NodeSeq): Box[String] = {
    def validHostname( hostname : String ) : Boolean = {
      val invalidList = "localhost" :: "127.0.0.1" :: "::1" :: Nil
      /* Invalid cases are:
       * * hostname is null
       * * hostname is empty
       * * hostname is one of the invalid list
       */
      ! ( hostname == null || hostname.isEmpty() || invalidList.contains(hostname))
    }

    (optTextHead(xml \\ "RUDDER" \ "HOSTNAME"), optTextHead(xml \\ "OPERATINGSYSTEM" \ "FQDN")) match {
      // Rudder tag
      case (Some(hostname), _             ) if validHostname(hostname) =>
        Full(hostname)

      // OS tag
      case (_             , Some(hostname)) if validHostname(hostname) =>
        Full(hostname)
      case (None          , None          )                            =>
        Failure("Hostname could not be found in inventory (RUDDER/HOSTNAME and OPERATINGSYSTEM/FQDN are missing)")
    }
  }

  /**
   * Parse Rudder Tag. In Rudder 4.3 and above, we don't need to parse anything but the <RUDDER> that looks like:
   * <RUDDER>
   *   <AGENT>
   *     <AGENT_NAME>cfengine-community</AGENT_NAME>
   *     <CFENGINE_KEY>....</CFENGINE_KEY>
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
  def processRudderElement(xml: NodeSeq, report: InventoryReport) : InventoryReport  = {
    // From an option and error message creates an option,
    // transform correctly option in a for comprehension
    def boxFromOption[T]( opt : Option[T], errorMessage : String) : Box[T] = {
      val box :  Box[T] = opt
      box ?~! errorMessage
    }

    // Check that a seq contains only one or identical values, if not fails
    def uniqueValueInSeq[T]( seq: Seq[T], errorMessage : String) : Box[T] = {
      seq.distinct match {
        case entry if entry.size != 1 => Failure(s"${errorMessage} (${entry.size} value(s) found in place of exactly 1)")
        case entry if entry.size == 1 => Full(entry.head)
      }
    }

    //parse the sub list of SERVER_ROLES/SERVER_ROLE, ignore other elements
    def processServerRoles(xml:NodeSeq) : Seq[ServerRole] = {
      (xml \ "SERVER_ROLES" \ "SERVER_ROLE").flatMap(e => optText(e).map(ServerRole(_)))
    }

    // as a temporary solution, we are getting information from packages

    def findAgent(software: Seq[Software], agentType: AgentType): Option[AgentVersion] = {
      val agentSoftName = agentType.inventorySoftwareName.toLowerCase()
      for {
        soft    <- software.find{_.name.map(_.toLowerCase() contains agentSoftName).getOrElse(false)}
        version <- soft.version
      } yield {
        AgentVersion(agentType.toAgentVersionName(version.value))
      }
    }

    //the whole content of the CUSTOM_PROPERTIES attribute should be valid JSON Array
    def processCustomProperties(xml:NodeSeq) : List[CustomProperty] = {
      import net.liftweb.json._

      parseOpt(xml.text) match {
        case None       => Nil
        case Some(json) => json match { // only Json Array is OK
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

    // Fetch all the agents configuration
    val agents = (xml \\ "AGENT").flatMap { agentXML =>
      val agent = for {
         agentName <- boxFromOption(optText(agentXML \ "AGENT_NAME"), "could not parse agent name (tag AGENT_NAME) from rudder specific inventory")
         agentType <- (AgentType.fromValue(agentName))

         rootUser  <- boxFromOption(optText(agentXML \\ "OWNER") ,"could not parse rudder user (tag OWNER) from rudder specific inventory")
         policyServerId <- boxFromOption(optText(agentXML \\ "POLICY_SERVER_UUID") ,"could not parse policy server id (tag POLICY_SERVER_UUID) from specific inventory")

         optCert = optText(agentXML \ "AGENT_CERT")
         optKey  = optText(agentXML \ "AGENT_KEY").orElse(optText(agentXML \ "CFENGINE_KEY"))
         securityToken : SecurityToken <- agentType match {
           case Dsc => optCert match {
             case Some(cert) => Full(Certificate(cert))
             case None => Failure("could not parse agent certificate (tag AGENT_CERT), which is mandatory for dsc agent")
           }
           case _         =>
             (optCert,optKey) match {
               case (Some(cert), _        ) => Full(Certificate(cert))
               case (None      , Some(key)) => Full(PublicKey(key))
               case (None      , None     ) => Failure("could not parse agent security Token (tag AGENT_KEY or CFENGINE_KEY or AGENT_CERT), which is mandatory for cfengine agent")
             }
         }

      } yield {

        val version = findAgent(report.applications, agentType)

        (AgentInfo(agentType,version,securityToken), rootUser, policyServerId)
      }
      agent match {
        case eb: EmptyBox =>
          val e = eb ?~! s"Error when parsing an <RUDDER><AGENT> entry in '${report.name}', that agent will be ignored."
          logger.error(e.messageChain)
          e
        case Full(x) => Full(x)
      }
    }

    ( for {
        agentOK        <- if(agents.size < 1) {
                            Failure(s"No <AGENT> entry was correctly defined in <RUDDER> extension tag (missing or see previous errors)")
                          } else {
                            Full("ok")
                          }
        uuid           <- boxFromOption(optText(xml \ "UUID"), "could not parse uuid (tag UUID) from rudder specific inventory")
        rootUser       <- uniqueValueInSeq(agents.map(_._2), "could not parse rudder user (tag OWNER) from rudder specific inventory")
        policyServerId <- uniqueValueInSeq(agents.map(_._3), "could not parse policy server id (tag POLICY_SERVER_UUID) from specific inventory")
        serverRoles    =  processServerRoles(xml).toSet
        // Node Custom properties from agent hooks
        customProperties =  processCustomProperties(xml \ "CUSTOM_PROPERTIES")
        // hostname is a special case processed in `processHostname`
      } yield {

        report.copy (
          node = report.node.copy (
              main = report.node.main.copy (
                  rootUser = rootUser
                , policyServerId = NodeId(policyServerId)
                , id = NodeId(uuid)
              )
            , agents = agents.map(_._1)
            , customProperties = customProperties
            , serverRoles = serverRoles
          )
        )
    } ) match {
      case Full(report) => report
      case eb:EmptyBox =>
        val fail = eb ?~! s"Error when parsing <RUDDER> extention node in inventory report with name '${report.name}'. Rudder extension attribute won't be available in report."
        logger.error(fail.messageChain)
        report
    }
  }

  /**
   * This method look into all list of components to search for duplicates and remove
   * them, updating the "quantity" field accordingly.
   *
   * The processing of memories is a little special. In place of adjusting the quantity,
   * it updates the slotNumber (two memories can not share the same slot), setting position
   * in the list as key. And yes, that may be unstable from one inventory to the next one.
   */
  private[this] def demux(report:InventoryReport) : InventoryReport = {
    //how can that be better ?
    var r = report
    r = r.copy(machine = r.machine.copy( bios = report.machine.bios.groupBy(identity).map { case (x,seq) => x.copy(quantity = seq.size) }.toSeq ) )
    r = r.copy(machine = r.machine.copy( controllers = report.machine.controllers.groupBy(identity).map { case (x,seq) => x.copy(quantity = seq.size) }.toSeq ) )
    r = r.copy(machine = r.machine.copy( memories = demuxMemories(report.machine.memories) ) )
    r = r.copy(machine = r.machine.copy( ports = report.machine.ports.groupBy(identity).map { case (x,seq) => x.copy(quantity = seq.size) }.toSeq ) )
    r = r.copy(machine = r.machine.copy( processors = report.machine.processors.groupBy(identity).map { case (x,seq) => x.copy(quantity = seq.size) }.toSeq ) )
    r = r.copy(machine = r.machine.copy( slots = report.machine.slots.groupBy(identity).map { case (x,seq) => x.copy(quantity = seq.size) }.toSeq ) )
    r = r.copy(machine = r.machine.copy( sounds = report.machine.sounds.groupBy(identity).map { case (x,seq) => x.copy(quantity = seq.size) }.toSeq ) )
    r = r.copy(machine = r.machine.copy( storages = report.machine.storages.groupBy(identity).map { case (x,seq) => x.copy(quantity = seq.size) }.toSeq ) )
    r = r.copy(machine = r.machine.copy( videos = report.machine.videos.groupBy(identity).map { case (x,seq) => x.copy(quantity = seq.size) }.toSeq ) )
    //node part
    r = demuxSameInterfaceDifferentIp(r)
    r = r.copy(applications = report.applications.groupBy( app => (app.name, app.version)).map { case (x,seq) => seq.head }.toSeq ) //seq.head is ok since its the result of groupBy
    r
  }

  private[this] def demuxMemories(memories:Seq[com.normation.inventory.domain.MemorySlot]) : Seq[com.normation.inventory.domain.MemorySlot] = {
    val duplicatedSlotsAndMemory = memories.groupBy( _.slotNumber).filter { case(x,seq) => x != DUMMY_MEM_SLOT_NUMBER && seq.size > 1 }
    val nonValideSlotNumber = memories.filter(mem => mem.slotNumber == DUMMY_MEM_SLOT_NUMBER)
    val validSlotNumbers = memories.filter(mem =>
                                mem.slotNumber != DUMMY_MEM_SLOT_NUMBER
                             && !duplicatedSlotsAndMemory.exists { case (i,_) => mem.slotNumber == i }
                           )

    //set negative slotNumbers for duplicated and non valid
    val getNegative = (duplicatedSlotsAndMemory.flatMap( _._2) ++ nonValideSlotNumber).zipWithIndex.map {
      case(mem,i) => mem.copy(slotNumber = (-i-1).toString )
    }
    validSlotNumbers++getNegative
  }

  /**
   * One interface can have several IP/mask/gateway/subnets. We have to
   * merge them when it's the case.
   */
  private[this] def demuxSameInterfaceDifferentIp(report: InventoryReport) : InventoryReport = {
    val nets = report.node.networks.groupBy( _.name ).map { case (interface, networks) =>
      val referenceInterface = networks.head //we have at least one due to the groupBy
      val ips = networks.flatMap( _.ifAddresses )
      val masks = networks.flatMap( _.ifMask ).distinct
      val gateways = networks.flatMap( _.ifGateway ).distinct
      val subnets = networks.flatMap( _.ifSubnet ).distinct
      val uniqIps = ips.distinct
      if(uniqIps.size < ips.size) {
        logger.error(s"Network interface '${interface}' appears several time with same IPs. Taking only the first one, it is likelly a bug in fusion inventory.")
      }
      referenceInterface.copy(ifAddresses = uniqIps, ifMask = masks, ifGateway = gateways, ifSubnet = subnets )
    }.toSeq

    report.copy(node = report.node.copy(networks = nets))
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

    if(os.os == AixOS) {
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

  def processHardware(xml:NodeSeq, report:InventoryReport) : Option[(NodeInventory,MachineInventory)] = {
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

    //update machine VM type
    val newMachine = (optText(xml\\"VMSYSTEM") match {
      case None => report.machine
      case Some(x) => x.toLowerCase match {
        case "physical" => report.machine.copy(machineType = PhysicalMachineType)
        case "xen" => report.machine.copy(machineType = VirtualMachineType(Xen) )
        case "virtualbox" => report.machine.copy(machineType = VirtualMachineType(VirtualBox) )
        case "virtual machine" => report.machine.copy(machineType = VirtualMachineType(UnknownVmType) )
        case "vmware" => report.machine.copy(machineType = VirtualMachineType(VMWare) )
        case "qemu" => report.machine.copy(machineType = VirtualMachineType(QEmu) )
        case "solariszone" => report.machine.copy(machineType = VirtualMachineType(SolarisZone) )
        case "aix_lpar" => report.machine.copy(machineType = VirtualMachineType(AixLPAR) )
        case "hyper-v" => report.machine.copy(machineType = VirtualMachineType(HyperV) )
        case "bsdjail" => report.machine.copy(machineType = VirtualMachineType(BSDJail) )
        case _ => report.machine.copy(machineType = VirtualMachineType(UnknownVmType) )
      }
    }).copy(mbUuid = optText(xml \\"UUID").map(MotherBoardUuid.apply(_)).orElse(report.machine.mbUuid))

     //    s.name((h\"NAME") text) // what to do with that ?
    val newNode = report.node.copy(
        description = optText(xml\\"OSCOMMENTS")
      , name = optText(xml\\"NAME")
      , ram = optText(xml\\"MEMORY").map(m => MemorySize(m + ramUnit))
      , swap = optText(xml\\"SWAP").map(m=> MemorySize(m + swapUnit))
        // update arch ONLY if it is not yet defined
      , archDescription = report.node.archDescription.orElse(optText(xml\\"ARCHNAME").map(normalizeArch(report.node.main.osDetails)))
      , lastLoggedUser = optText(xml\\"LASTLOGGEDUSER")
      , lastLoggedUserTime = try {
          optText(xml\\"DATELASTLOGGEDUSER").map(date => userLoginDateTimeFormat.parseDateTime(date) )
        } catch {
          case e:IllegalArgumentException =>
            logger.warn("Error when parsing date for last user loggin. Awaited format is %s, found: %s".format(lastLoggedUserDatetimeFormat,(xml\\"DATELASTLOGGEDUSER").text))
            None
        }
    )
    Some((newNode, newMachine))
  }

  def processOsDetails(xml:NodeSeq, report:InventoryReport, contentNode:NodeSeq) : InventoryReport = {
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
       *                  (for Windows, SuSE, etc)
       * VERSION        : version of the os
       *                  "5.08", "11.04", "N/A" for windows
       * FQDN           : the fully qualified hostname
       */
    val osDetail:OsDetails = {
      val osType = optText(xml\\"KERNEL_NAME").getOrElse("").toLowerCase
      val osName = optText(xml\\"NAME").getOrElse("").toLowerCase

      val fullName = optText(xml\\"FULL_NAME").getOrElse("")
      val kernelVersion = new Version(optText(xml\\"KERNEL_VERSION").getOrElse("N/A"))

      // format for AIX service pack used by fusion is xx-YYWW =>
      // First part two digit (xx) are the sp,
      // Second part is the date of the release of the SP ( yeat and week of year
      val aixSPPattern = "(\\d{2})-\\d{4}".r
      val SPText = optText(xml\\"SERVICE_PACK").map{
        // first digit can be 0, remove it by transforming in Int
        case aixSPPattern(sp) => s"${sp.toInt}"
        // Not matching keep value
        case sp => sp
      }
      val servicePack = SPText

      val version = new Version(optText(xml\\"VERSION").getOrElse("N/A"))

      //find os type, and name
      val detectedOs : OsType = (osType, osName) match {
        case ("mswin32", _ ) =>
          val x = fullName.toLowerCase
          //in windows, relevant information are in the fullName string
          if     (x contains  "xp"     )   WindowsXP
          else if(x contains  "vista"  )   WindowsVista
          else if(x contains  "seven"  )   WindowsSeven
          else if(x contains  "10"  )      Windows10
          else if(x contains  "2000"   )   Windows2000
          else if(x contains  "2003"   )   Windows2003
          else if(x contains  "2008 r2")   Windows2008R2 //must be before 2008 for obvious reason
          else if(x contains  "2008"   )   Windows2008
          else if(x contains  "2012 r2")   Windows2012R2
          else if(x contains  "2012"   )   Windows2012
          else if(x contains  "2016 r2")   Windows2016R2
          else if(x contains  "2016"   )   Windows2016
          else if(x contains  "2019"   )   Windows2019
          else                             UnknownWindowsType

        case ("linux"  , x ) =>
          if     (x contains "debian"    ) Debian
          else if(x contains "ubuntu"    ) Ubuntu
          else if(x contains "redhat"    ) Redhat
          else if(x contains "centos"    ) Centos
          else if(x contains "fedora"    ) Fedora
          else if(x contains "suse"      ) Suse
          else if(x contains "android"   ) Android
          else if(x contains "oracle"    ) Oracle
          else if(x contains "scientific") Scientific
          else if(x contains "slackware" ) Slackware
          else if(x contains "mint"      ) Mint
          else                          UnknownLinuxType

        case("solaris", _) => SolarisOS

        case("aix", _) => AixOS

        case ("freebsd", _) => FreeBSD

        case _  => UnknownOSType
      }

      detectedOs match {
        case w:WindowsType =>

          Windows(
              os = w
            , fullName = fullName
            , version = version
            , servicePack = servicePack
            , kernelVersion = kernelVersion
            , userDomain = optText(contentNode\\"USERDOMAIN")
            , registrationCompany = optText(contentNode\\"WINCOMPANY")
            , productId = optText(contentNode\\"WINPRODID")
            , productKey = optText(contentNode\\"WINPRODKEY")
          )

        case distrib:LinuxType =>
          Linux(
              os = distrib
            , fullName = fullName
            , version = version
            , servicePack = servicePack
            , kernelVersion = kernelVersion
          )

        case FreeBSD =>
          Bsd(
              os = FreeBSD
            , fullName = fullName
            , version = version
            , servicePack = servicePack
            , kernelVersion = kernelVersion
          )

        case SolarisOS =>
          Solaris(
              fullName = fullName
            , version = version
            , servicePack = servicePack
            , kernelVersion = kernelVersion
          )

        case AixOS =>

          // Aix version is stocked in HARDWARE -> OSVERSION,
          // but we move that value in OPERATING_SYSTEM => KERNEL_VERSION (see checkKernelVersion in PreUnmarshallCheckConsistency.scala )
          // If We are on aix we should use that value instead of the one stored in OPERATING_SYSTEM => VERSION

          // aix Version format is decomposed into 3 fields: Major (M), minor(m) and Technology level (T)
          // the format is Mmxx-TT (like 5300-12 => 5.3.12)
          val aixVersionPattern = "(\\d)(\\d)\\d\\d-(\\d{2})".r
          val versionText = optText(xml\\"KERNEL_VERSION").getOrElse("N/A") match {
            case aixVersionPattern(major,minor,techLevel) =>
            // first digit in Technology can be 0, remove it by transforming in Int
              s"${major}.${minor}.${techLevel.toInt}"
            // Not matching, keep value
            case v => v
          }
          val aixVersion = new Version(versionText)

          Aix(
              fullName = fullName
            , version = aixVersion
            , servicePack = servicePack
            , kernelVersion = kernelVersion
          )

        case _  => UnknownOS(fullName, version, servicePack, kernelVersion)
      }
    }
      //for timezone, if any is missing then None
    val timezone = ( optText(xml \ "TIMEZONE" \ "NAME"), optText(xml \ "TIMEZONE" \ "OFFSET") ) match {
      case ( Some(name), Some(offset) ) => Some(NodeTimezone(name, offset))
      case _                            => None
    }

    // for arch, we want to keep the value only in the case where OPERATINGSYSTEM/ARCH is missing
    val arch = optText(xml\\"ARCH").map(normalizeArch(report.node.main.osDetails)).orElse(report.node.archDescription)

    report.copy( node = report.node.copyWithMain(m => m.copy (osDetails = osDetail) ).copy(timezone = timezone, archDescription = arch ) )

  }

  /**
   * We only keep name in local user account
   */
  def processLocalAccount(d: Node) : Option[String] = {
    optText(d\"LOGIN")
  }

  /**
   * The key used to identify filesystem is in order:
   * - the mount_point describe in TYPE (yes...), for Linux system, for example "/home", etc
   * - the volume letter for Windows ("A", etc)
   * - the volumn, if nothing else is available
   */
  def processFileSystem(d: NodeSeq) : Option[FileSystem] = {
    val mount_point = optText(d\"TYPE")
    val letter = optText(d\"LETTER")
    val volume = optText(d\"VOLUMN")
    //volum or letter is mandatory
    letter.orElse(mount_point).orElse(volume) match {
      case None =>
        logger.debug("Ignoring FileSystem entry because missing tag TYPE and LETTER")
        logger.debug(d)
        None
      case Some(mountPoint) =>
        Some(FileSystem(
            mountPoint = mountPoint
          , name       = optText(d\"FILESYSTEM")
          , freeSpace  = optText(d\"FREE").map(m => MemorySize(m + fsSpaceUnit))
          , totalSpace = optText(d\"TOTAL").map(m => MemorySize(m + fsSpaceUnit))
          , fileCount  = optInt(d, "NUMFILES")
        ) )
    }
  }

  def processNetworks(n : NodeSeq) : Option[Network] = {
    import com.normation.inventory.domain.InetAddressUtils._
    //in fusion report, we may have several IP address separated by comma
    def getAddresses(addressString : String) : Seq[InetAddress] = {
      for {
        addr <- addressString.split(",")
        a <- InetAddressUtils.getAddressByName(addr)
      } yield a
    }.toSeq

    /*
     * Normally, Fusion put in DESCRIPTION the interface name (eth0, etc).
     * This is missing for Android, so we are going to use the TYPE
     * (wifi, ethernet, etc) as an identifier in that case.
     */

    optText(n\"DESCRIPTION").orElse(optText(n\"TYPE")) match {
      case None =>
        logger.debug("Ignoring entry Network because tag DESCRIPTION is empty")
        logger.debug(n)
        None
      case Some(desc) =>
        // in a single NETWORK element, we can have both IPV4 and IPV6
        // variant for address, gateway, subnet, mask
        def getBothAddresses(xml: NodeSeq, ipv4Attr: String, ipv6Attr: String): Seq[InetAddress] = {
          val ipv4 =  optText(xml\ipv4Attr) match {
              case None => Seq()
              case Some(a) => getAddresses(a)
            }
          val ipv6 = optText(xml\ipv6Attr) match {
              case None => Seq()
              case Some(a) =>
                // Ipv6 addresses from fusion inventory may have a / part we need to remove it to get valid ipv6 address
                val ip = a takeWhile { _ != '/' }
                getAddresses(ip)
            }

          ipv4 ++ ipv6
        }

        Some( Network(
            name = desc
          , ifAddresses = getBothAddresses(n, "IPADDRESS", "IPADDRESS6")
          , ifDhcp = optText(n\"IPDHCP").flatMap(getAddressByName( _ ))
          , ifGateway = getBothAddresses(n, "IPGATEWAY", "IPGATEWAY6")
          , ifMask = getBothAddresses(n, "IPMASK", "IPMASK6")
          , ifSubnet = getBothAddresses(n, "IPSUBNET", "IPSUBNET6")
          , macAddress = optText(n\"MACADDR")
          , status = optText(n\"STATUS")
          , ifType = optText(n\"TYPE")
          , typeMib = optText(n\"TYPEMIB")
          , speed = optText(n\"SPEED")
        ) )
    }
  }

  def processSoftware(s : NodeSeq) : Software = Software(
        id = SoftwareUuid(uuidGen.newUuid)
      , name = optText(s\"NAME")
      , version = optText(s\"VERSION").map(x => new Version(x) )
      , description = optText(s\"COMMENTS")
      , editor = optText(s\"PUBLISHER").map(e => new SoftwareEditor(e))
    )

  /**
   * Process the bios, and return its, plus the system manufacturer and the system serial number
   */
  def processBios(b : NodeSeq) : (Option[Bios], Option[Manufacturer], Option[String]) = {
    // Fetching the manufacturer. It should be in SMANUFACTURER, except if it is
    // empty, or the value is "Not available". In these case, the correct entry is MMANUFACTURER
    val systemManufacturer = (optText(b\"SMANUFACTURER") match {
      case None => optText(b\"MMANUFACTURER")
      case Some("Not available") => optText(b\"MMANUFACTURER")
      case Some(text) => Some(text)
    }).map(m => new Manufacturer(m))

    val systemSerialNumber = optText(b\"SSN")

    val bios = optText(b\"SMODEL") match {
      case None =>
        logger.debug("Ignoring entry Bios because SMODEL is empty")
        logger.debug(b)
        None
      case Some(model) =>
        val date = try {
          optText(b\"BDATE").map(d => biosDateTimeFormat.parseDateTime(d))
        } catch {
          case e:IllegalArgumentException =>
            logger.warn("Error when parsing date for Bios. Awaited format is %s, found: %s".format(biosDateFormat,(b\"BDATE").text))
            None
        }

        Some( Bios(
            name = model
          , releaseDate = date
          , editor = optText(b\"BMANUFACTURER").map(s => new SoftwareEditor(s))
          , version = optText(b\"BVERSION").map(v => new Version(v) )
        ))
    }

    (bios, systemManufacturer, systemSerialNumber)
  }

  def processController(c: NodeSeq) : Option[Controller] = {
    optText(c\"NAME") match {
      case None =>
        logger.debug("Ignoring entry Controller because tag NAME is empty")
        logger.debug(c)
        None
      case Some(name) =>
        Some(Controller(
            name = name
          , manufacturer = optText(c\"MANUFACTURER").map(m => new Manufacturer(m))
          , cType = optText(c\"TYPE")
        ) )
    }
  }

  private[this] val DUMMY_MEM_SLOT_NUMBER = "DUMMY"

  /**
   * For memory, the numslot is used for key.
   * On several embeded devices (Android especially), there is no
   * such information, so we use a false numslot, that will be
   * change after the full processing of the inventory.
   */
  def processMemory(m : NodeSeq) : Option[MemorySlot] = {
    //add memory. Add all slots, but add capacity other than numSlot only for full slot
    val slot = optText(m\"NUMSLOTS") match {
      case None =>
        logger.debug("Memory is missing tag NUMSLOTS, assigning a negative value for num slot")
        logger.debug(m)
        DUMMY_MEM_SLOT_NUMBER
      case Some(slot) =>  slot
    }

        (m\"CAPACITY").text.toLowerCase match {
          case "" | "no" =>
            Some(MemorySlot(slot))
          case c =>
            Some( MemorySlot(
                slotNumber = slot
              , capacity = Some(MemorySize(c+ slotMemoryUnit ))
              , caption = optText(m\"CAPTION")
              , description = optText(m\"DESCRIPTION")
              , serialNumber = optText(m\"SERIALNUMBER")
              , speed = optText(m\"SPEED")
              , memType = optText(m\"TYPE")
            ) )
        }
  }

  def processPort(p : NodeSeq) : Option[Port] = {
    optText(p\"NAME") match {
      case None =>
        logger.debug("Ignoring entry Port because tag NAME is empty")
        logger.debug(p)
        None
      case Some(name) =>
        /*It seems that CAPTION and DESCRIPTION
         * play the same role in turn, so we have to
         * check which one is "None" and take the other.
         * In case both are different from none, just aggregate
         * with the pattern: "CAPTION || DESCRIPTION"
         */
        val none = Some("None")
        val l = optText(p\"CAPTION")::optText(p\"DESCRIPTION")::Nil
        val description = l.filter(_ != none).distinct match {
          case Some(a)::Some(b)::t => Some(a + "||" + b)
          case x::t => x
          case _ => None
        }

        /*
         * Name may be not empty, but equals to "Not Specified".
         * In such a case, we append "(TYPE)" to allows to have
         * 'Not Specified (USB)', 'Not Specified (SATA)', etc.
         */
        val ptype = optText(p\"TYPE")
        val realName = {
              if(name.toLowerCase.trim == "not specified")
                name + ptype.map(" (" + _ +")").getOrElse("")
              else name
            }
        Some( Port(
            name = realName
          , description = description
          , pType = ptype
        ) )
    }
  }

  def processSlot(s : NodeSeq) : Option[Slot] = {
    /*
     * Depending if we are on a vm, windows, linux,
     * other things, the identifier may be
     * NAME or DESIGNATION
     * So we take the one defined if only one is,
     * or concatenate the two
     */
    val name = (optText(s\"NAME")::optText(s\"DESIGNATION")::Nil).filter(_.isDefined) match {
      case Some(x)::Some(y)::_ => Some(x + " - " + y)
      case Some(x)::_ => Some(x)
      case _ => None
    }

    name match {
      case None =>
        logger.debug("Ignoring entry Slot because tags NAME and DESIGNATION are empty")
        logger.debug(s)
        None
      case Some(sl) =>
        Some( Slot (
            name = sl
          , description = optText(s\"DESCRIPTION")
          , status = optText(s\"STATUS")
        ) )
    }
  }

  def processSound(s : NodeSeq) : Option[Sound] = {
    /*
     * We take the name+manufacturer as key as it
     * seems to be the most complete information
     */
    val name = (optText(s\"NAME")::optText(s\"MANUFACTURER")::Nil).filter(_.isDefined) match {
      case Some(x)::Some(y)::_ => Some(x + " - " + y)
      case Some(x)::_ => Some(x)
      case _ => None
    }

    name match {
      case None =>
        logger.debug("Ignoring entry Sound because tags NAME and MANUFACTURER are empty")
        logger.debug(s)
        None
      case Some(so) =>
        Some( Sound (
            name = so
          , description = optText(s\"DESCRIPTION")
        ) )
    }
  }

  def processStorage(s : NodeSeq) : Option[Storage] = {
    /*
     * It seems that the only things mostly
     * stable between windows/linux/vm
     * is the model
     */
    optText(s\"NAME") match {
      case None =>
        logger.debug("Ignoring entry Storage because tag NAME is empty")
        logger.debug(s)
        None
      case Some(name) =>
        Some( Storage(
            name = name
          , description = optText(s\"DESCRIPTION")
          , size = optText(s\"DISKSIZE").map(x => MemorySize(x  + "Mo"))
          , firmware = optText(s\"FIRMWARE")
          , manufacturer = optText(s\"MANUFACTURER").map(x => new Manufacturer(x))
          , model = optText(s\"MODEL")
          , serialNumber = optText(s\"SERIALNUMBER")
          , sType = optText(s\"TYPE")
        ) )
    }
  }

  /**
   * Process the Video.
   * The name is used by default for the key.
   * If there is no name but one resolution, we use that for the key.
   * Return None in other case.
   */
  def processVideo(v : NodeSeq) : Option[Video] = {
    optText(v\"NAME").orElse(optText(v\"RESOLUTION")) match {
      case None =>
        logger.debug("Ignoring entry Video because tag NAME is empty")
        logger.debug(v)
        None
      case Some(name) => Some( Video(
          name        = name
        , description = None
        , chipset     = optText(v\"CHIPSET")
        , memory      = optText(v\"MEMORY").map(s => MemorySize(s + "Mo"))
        , resolution  = optText(v\"RESOLUTION")
        ) )
    }
  }

  def processCpu(c : NodeSeq) : Option[Processor] = {
    optText(c\"NAME") match{
      case None =>
        logger.debug("Ignoring entry CPU because tag MANIFACTURER and ARCH are empty")
        logger.debug(c)
        None
      case Some(name) =>
        Some (
            Processor (
                manufacturer    = optText(c\"MANUFACTURER").map(new Manufacturer(_))
                , arch          = optText(c\"ARCH")
                , name          = name
                , speed         = optFloat(c, "SPEED").map(_.toInt)
                , externalClock = optFloat(c, "EXTERNAL_CLOCK")
                , core          = optInt(c, "CORE")
                , thread        = optInt(c, "THREAD")
                , cpuid         = optText(c \ "ID")
                , stepping      = optInt(c, "STEPPING")
                , model         = optInt(c, "MODEL")
                , family        = optInt(c, "FAMILYNUMBER")
                , familyName    = optText(c \ "FAMILYNAME")
                ) )
    }
  }

  def processEnvironmentVariable(ev : NodeSeq) : Option[EnvironmentVariable] = {
    optText(ev\"KEY")  match {
    case None =>
      logger.debug("Ignoring entry Envs because tag KEY is empty")
      logger.debug(ev)
      None
    case Some(key) =>
      Some (
          EnvironmentVariable (
              name = key
              , value = optText(ev\"VAL")
              ) )
    }
  }

  def processVms(vm : NodeSeq) : Option[VirtualMachine] = {
    optText(vm\"UUID") match {
    case None =>
      logger.debug("Ignoring entry VirtualMachine because tag UUID is empty")
      logger.debug(vm)
      None
    case Some(uuid) =>
        Some(
        VirtualMachine (
              vmtype    = optText(vm\"VMTYPE")
            , subsystem = optText(vm\"SUBSYSTEM")
            , owner     = optText(vm\"OWNER")
            , name      = optText(vm\"NAME")
            , status    = optText(vm\"STATUS")
            , vcpu      = optInt(vm, "VCPU")
            , memory    = optText(vm\"MEMORY")
            , uuid      = new MachineUuid(uuid)
            ) )
    }
  }

  def processProcesses(proc : NodeSeq) : Option[Process] = {
     optInt(proc, "PID") match {
    case None =>
      logger.debug("Ignoring entry Process because tag PID is invalid")
      logger.debug(proc)
      None
    case Some(pid) =>
      Some (
        Process(
              pid   = pid
            , cpuUsage      = optFloat(proc, "CPUUSAGE")
            , memory        = optFloat(proc, "MEM")
            , commandName   = optText(proc\"CMD")
            , started       = optText(proc\"STARTED")
            , tty           = optText(proc\"TTY")
            , user          = optText(proc\"USER")
            , virtualMemory = optDouble(proc, "VIRTUALMEMORY")
            ) )
    }
  }

  def processAccessLog (accessLog : NodeSeq) : Option[DateTime] = {
     val fmt = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss")
   optText(accessLog\"LOGDATE") match {
     case None => None;
     case Some(date) => try {
         Some(DateTime.parse(date,fmt))
       } catch {
         case e:IllegalArgumentException => logger.warn("error when parsing ACCESSLOG, reason %s".format(e.getMessage()))
             None
       }
   }
  }

   def processVersion (vers : NodeSeq) : Option[Version] = {
   optText(vers) match {
     case None => None;
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
  def optText(n:NodeSeq) = n.text match {
    case null | "" => None
    case s => s.trim().replaceAll("""[\p{Blank}]+"""," ") match {
      case ""   => None
      case text => Some(text)
    }
  }
}
