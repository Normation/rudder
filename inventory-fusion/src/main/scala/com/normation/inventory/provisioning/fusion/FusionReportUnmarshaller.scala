/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.inventory.provisioning
package fusion

import com.normation.inventory.domain._
import com.normation.inventory.provisioning.fusion._
import java.io.InputStream
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import java.util.Locale
import com.normation.utils.StringUuidGenerator
import com.normation.utils.Utils._
import java.net.InetAddress
import scala.xml._
import org.xml.sax.SAXParseException
import net.liftweb.common._
import com.normation.inventory.domain.InventoryConstants._
import com.normation.inventory.services.provisioning._
import org.joda.time.format.DateTimeFormatter


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
) extends ReportUnmarshaller with Loggable {
  
  val userLoginDateTimeFormat = DateTimeFormat.forPattern(lastLoggedUserDatetimeFormat).withLocale(Locale.ENGLISH)
  val biosDateTimeFormat = DateTimeFormat.forPattern(biosDateFormat).withLocale(Locale.ENGLISH)
    
  /*
   * A method that retrieve the text value of an XML and
   * clean it:
   * - remove leading/trailing spaces ;
   * - remove multiple space
   */
  private def optText(n:NodeSeq) = n.text match {
    case null | "" => None
    case s => Some(s.trim().replaceAll("""[\p{Blank}]+"""," "))
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

  override def fromXml(reportName:String,is : InputStream) : Box[InventoryReport] = {
    
    val doc:NodeSeq = (try {
      Full(XML.load(is))
    } catch {
      case e:SAXParseException => Failure("Cannot parse uploaded file as an XML Fusion Inventory report",Full(e),Empty)
    }) match {
      case f@Failure(m,e,c) => return f
      case Full(doc) if(doc.isEmpty) => return Failure("Fusion Inventory report seem's to be empty")
      case Empty => return Failure("Fusion Inventory report seem's to be empty")
      case Full(x) => x //ok, continue
    }

     var report =  {
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
      val node = NodeInventory( NodeSummary(
          NodeId(uuidGen.newUuid),
          PendingInventory, "dummy-root", "dummy-hostname",
          UnknownOS(),
          NodeId("dummy-policy-server")
      ) )
      
      //create a machine used as a template to modify
      val machine = MachineInventory(MachineUuid(uuidGen.newUuid), PendingInventory, PhysicalMachineType)
      
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
     * and now, actually parse !
     * Each line is a partial function of the form: Node => Unit
     */
    for(e <- (doc \\ "REQUEST").head.child) { e.label match {
      case "CONTENT" => for( elt <- e.head.child ) { elt.label match {
        case "ACCESSLOG"   => processAccessLog(elt).foreach(x => report = report.copy ( node = report.node.copy( inventoryDate = Some(x) ), machine = report.machine.copy ( inventoryDate = Some(x) ) ))
        case "BATTERIES"   => //TODO not sure about that
        case "BIOS"        => processBios(elt).foreach {x => report = report.copy( machine = report.machine.copy( bios = x +: report.machine.bios ) ) }
        case "CONTROLLERS" => processController(elt).foreach { x => report = report.copy( machine = report.machine.copy( controllers = x +: report.machine.controllers ) ) }
        case "CPUS"        => processCpu(elt).foreach { x => report = report.copy( machine = report.machine.copy( processors = x +: report.machine.processors ) ) }
        case "DRIVES"      => processFileSystem(elt).foreach { x => report = report.copy( node = report.node.copy( fileSystems = x +: report.node.fileSystems ) ) }
        case "ENVS"        => processEnvironmentVariable(elt).foreach {x => report = report.copy(node = report.node.copy (environmentVariables = x +: report.node.environmentVariables ) ) }
        case "HARDWARE"    =>  processHardware(elt, report).foreach(x => report = report.copy(node = x._1, machine = x._2))
        case "OPERATINGSYSTEM" => report = processOsDetails(elt, report, e)
        case "INPUTS"      => //TODO keyborad, mouse, speakers
        case "MEMORIES"    => processMemory(elt).foreach { x => report = report.copy( machine = report.machine.copy( memories = x +: report.machine.memories ) ) }
        case "NETWORKS"    => processNetworks(elt).foreach { x => report = report.copy( node = report.node.copy( networks = x +: report.node.networks ) ) }
        case "PORTS"       => processPort(elt).foreach { x => report = report.copy( machine = report.machine.copy( ports = x +: report.machine.ports ) ) }
        case "PROCESSES"   => processProcesses(elt).foreach { x => report = report.copy( node = report.node.copy ( processes = x +: report.node.processes))}
        case "SLOTS"       => processSlot(elt).foreach { x => report = report.copy( machine = report.machine.copy( slots = x +: report.machine.slots ) ) }
        case "SOFTWARES"   => report = report.copy( applications  = processSoftware(elt) +: report.applications )
        case "SOUNDS"      => processSound(elt).foreach { x => report = report.copy( machine = report.machine.copy( sounds = x +: report.machine.sounds ) ) }
        case "STORAGES"    => processStorage(elt).foreach { x => report = report.copy( machine = report.machine.copy( storages = x +: report.machine.storages ) ) }
        case "USBDEVICES"  => //TODO only digits for them, not sure we want to keep that as it is.
        case "USERS"       => //TODO Not sure what is it (only one login ? a logged user ?)
        case "VIDEOS"      => processVideo(elt).foreach { x => report = report.copy( machine = report.machine.copy( videos = x +: report.machine.videos ) ) }
        case "VIRTUALMACHINES" => processVms(elt).foreach { x =>  report = report.copy(node  = report.node.copy( vms = x +: report.node.vms) ) }
     // done previously :    case "VERSIONCLIENT" => report = report.copy( version = processVersion(elt))
        case x => contentParsingExtensions.find {
            pf => pf.isDefinedAt(elt,report)
          }.foreach { pf =>
            report = pf(elt,report)
          }
      } }
      case x => rootParsingExtensions.find {
          pf => pf.isDefinedAt(e,report)
        }.foreach { pf =>
          report = pf(e,report)
        }
    } }
    val fullReport = report.copy(
       //add all VMs and software ids to node
      node = report.node.copy( 
          softwareIds = report.applications.map( _.id ) 
      )
    )
    
    val demuxed = demux(fullReport)
    Full(demuxed)
  }
  
  /**
   * This method look into all alist of components to search for duplicates and remove
   * them, updating the "quantity" field accordingly
   */
  private[this] def demux(report:InventoryReport) : InventoryReport = {
    //how can that be better ?
    var r = report 
    r = r.copy(machine = r.machine.copy( bios = report.machine.bios.groupBy(identity).map { case (x,seq) => x.copy(quantity = seq.size) }.toSeq ) )
    r = r.copy(machine = r.machine.copy( controllers = report.machine.controllers.groupBy(identity).map { case (x,seq) => x.copy(quantity = seq.size) }.toSeq ) )
    r = r.copy(machine = r.machine.copy( memories = report.machine.memories.groupBy(identity).map { case (x,seq) => x.copy(quantity = seq.size) }.toSeq ) )
    r = r.copy(machine = r.machine.copy( ports = report.machine.ports.groupBy(identity).map { case (x,seq) => x.copy(quantity = seq.size) }.toSeq ) )
    r = r.copy(machine = r.machine.copy( processors = report.machine.processors.groupBy(identity).map { case (x,seq) => x.copy(quantity = seq.size) }.toSeq ) )
    r = r.copy(machine = r.machine.copy( slots = report.machine.slots.groupBy(identity).map { case (x,seq) => x.copy(quantity = seq.size) }.toSeq ) )
    r = r.copy(machine = r.machine.copy( sounds = report.machine.sounds.groupBy(identity).map { case (x,seq) => x.copy(quantity = seq.size) }.toSeq ) )
    r = r.copy(machine = r.machine.copy( storages = report.machine.storages.groupBy(identity).map { case (x,seq) => x.copy(quantity = seq.size) }.toSeq ) )
    r = r.copy(machine = r.machine.copy( videos = report.machine.videos.groupBy(identity).map { case (x,seq) => x.copy(quantity = seq.size) }.toSeq ) )
    r
  }
  
  
  // ******************************************** //
  // parsing implementation details for each tags //
  // ******************************************** //

  def processHardware(xml:NodeSeq, report:InventoryReport) : Option[(NodeInventory,MachineInventory)] = {
      /*
       * 
       * *** Operating System infos ***
       * ARCHNAME : architecture type. 
       *      Ex: "x86_64-linux-gnu-thread-multi"
       * VMSYSTEM : The virtualization technologie used if the machine is a virtual machine.
       *      Can be: Physical (default), Xen, VirtualBox, Virtual Machine, VMware, QEMU, SolarisZone
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
       * UUID : mother board UUID (system id)
       *      Ex: "4454C4C-4D00-104B-8047-C2C04F30354A"
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

    
     val machinewithUUID = report.machine.copy(id = (MachineUuid(optText(xml\\"UUID").get)))
    //update machine VM type
    val newMachine = optText(xml\\"VMSYSTEM") match {
      case None => machinewithUUID
      case Some(x) => x.toLowerCase match {
        case "physical" => machinewithUUID.copy(machineType = PhysicalMachineType)
        case "xen" => machinewithUUID.copy(machineType = VirtualMachineType(Xen) )
        case "virtualbox" => machinewithUUID.copy(machineType = VirtualMachineType(VirtualBox) )
        case "virtual machine" => machinewithUUID.copy(machineType = VirtualMachineType(UnknownVmType) )
        case "vmware" => machinewithUUID.copy(machineType = VirtualMachineType(VMWare) )
        case "qemu" => machinewithUUID.copy(machineType = VirtualMachineType(QEmu) )
        case "solariszone" => machinewithUUID.copy(machineType = VirtualMachineType(SolarisZone) )
      }
    }
        
     //    s.name((h\"NAME") text) // what to do with that ?
    val newNode = report.node.copy(
        description = optText(xml\\"OSCOMMENTS")
      , name = optText(xml\\"NAME")
      , ram = optText(xml\\"MEMORY").map(m => MemorySize(m + ramUnit))
      , swap = optText(xml\\"SWAP").map(m=> MemorySize(m + swapUnit))
      , archDescription = optText(xml\\"ARCHNAME")
      , lastLoggedUser = optText(xml\\"LASTLOGGEDUSER")
      , machineId = Some((MachineUuid(optText(xml\\"UUID").get),PendingInventory))
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
       */
    val osDetail:OsDetails = {
      val osType = optText(xml\\"KERNEL_NAME").getOrElse("").toLowerCase
      val osName = optText(xml\\"NAME").getOrElse("").toLowerCase
      
      val fullName = optText(xml\\"FULL_NAME").getOrElse("")
      val kernelVersion = new Version(optText(xml\\"KERNEL_VERSION").getOrElse("N/A"))
      val servicePack = optText(xml\\"SERVICE_PACK")
      val version = new Version(optText(xml\\"VERSION").getOrElse("N/A"))

      //find os type, and name
      val detectedOs : OsType = (osType, osName) match {
        case ("mswin32", _ ) => 
          val x = fullName.toLowerCase
          //in windows, relevant information are in the fullName string
          if     (x contains  "xp"     )   WindowsXP
          else if(x contains  "vista"  )   WindowsVista
          else if(x contains  "seven"  )   WindowsSeven
          else if(x contains  "2000"   )   Windows2000
          else if(x contains  "2003"   )   Windows2003
          else if(x contains  "2008 r2")   Windows2008R2 //must be before 2008 for obvious reason
          else if(x contains  "2008"   )   Windows2008
          else                             UnknownWindowsType

        case ("linux"  , x ) => 
          if     (x contains "debian") Debian
          else if(x contains "ubuntu") Ubuntu
          else if(x contains "redhat") Redhat
          else if(x contains "centos") Centos
          else if(x contains "fedora") Fedora
          else if(x contains "suse"  ) Suse
          else                         UnknownLinuxType
        //be sure to handle the validity of that case further down the processing pipeline, 
        //for example in PostUnMarshallCheckConsistency
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
        
        case _  => UnknownOS(fullName, version, servicePack, kernelVersion)
      }
    }
    
    report.copy( node = report.node.copyWithMain(m => m.copy (osDetails = osDetail ) ) )
    
  }
  
  def processFileSystem(d: NodeSeq) : Option[FileSystem] = {
    val mount_point = optText(d\"TYPE")
    val letter = optText(d\"LETTER")
    //volum or letter is mandatory
    letter.orElse(mount_point) match {
      case None =>
        logger.debug("Ignoring FileSystem entry because missing tag TYPE and LETTER")
        logger.debug(d)
        None
      case Some(mountPoint) => 
        Some(FileSystem(
            mountPoint = mountPoint
          , name = optText(d\"FILESYSTEM")
          , freeSpace = optText(d\"FREE").map(m => MemorySize(m + fsSpaceUnit))
          , totalSpace = optText(d\"TOTAL").map(m => MemorySize(m + fsSpaceUnit))
          , fileCount = optText(d\"NUMFILES").map(n => n.toInt)
        ) )
    }
  }  

  def processNetworks(n : NodeSeq) : Option[Network] = {
    import InetAddressUtils._
    //in fusion report, we may have several IP address separated by comma
    def getAddresses(addressString : String) : Seq[InetAddress] = {
      for {
        addr <- addressString.split(",")
        a <- InetAddressUtils.getAddressByName(addr)
      } yield a 
    }.toSeq
    
    optText(n\"DESCRIPTION") match {
      case None =>
        logger.debug("Ignoring entry Network because tag DESCRIPTION is empty")
        logger.debug(n)
        None
      case Some(desc) =>
        Some( Network(
            name = desc
          , ifAddresses = optText(n\"IPADDRESS") match {
              case None => Seq()
              case Some(a) => getAddresses(a)
            }
          , ifDhcp = optText(n\"IPDHCP").flatMap(getAddressByName( _ ))
          , ifGateway = optText(n\"IPGATEWAY").flatMap(getAddressByName( _ ))
          , ifMask = optText(n\"IPMASK").flatMap(getAddressByName( _ ))
          , ifSubnet = optText(n\"IPSUBNET").flatMap(getAddressByName( _ ))
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

  def processBios(b : NodeSeq) : Option[Bios] = {
    optText(b\"SMODEL") match { 
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
  
  def processMemory(m : NodeSeq) : Option[MemorySlot] = {
    //add memory. Add all slots, but add capacity other than numSlot only for full slot
    optText(m\"NUMSLOTS") match { 
      case None =>
        logger.debug("Ignoring entry Memory because tag NUSLOTS is emtpy")
        logger.debug(m)
        None
      case Some(slot) =>  
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
   * Process the Video. Return None if there are no name, Some(video) otherwise
   */
  def processVideo(v : NodeSeq) : Option[Video] = {
    optText(v\"NAME") match {
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
                , speed         = optText(c\"SPEED").map(_.toFloat.toInt)
                , externalClock = optText(c\"EXTERNAL_CLOCK").map(_.toFloat)
                , core          = optText(c\"CORE").map(_.toInt)
                , thread        = optText(c\"THREAD").map(_.toInt)
                , cpuid         = optText(c\"ID")
                , stepping      = optText(c\"STEPPING").map(_.toInt)
                , model         = optText(c\"MODEL").map(_.toInt)
                , family        = optText(c\"FAMILYNUMBER").map(_.toInt)
                , familyName    = optText(c\"FAMILYNAME")
                ) )
    }
  }

  def processEnvironmentVariable(ev : NodeSeq) : Option[EnvironmentVariable] = {
    optText(ev\"KEY")	match {
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
	          , vcpu      = optText(vm\"VCPU").map(_.toInt)
	          , memory    = optText(vm\"MEMORY")
	          , uuid      = new MachineUuid(uuid)
	          ) )
	  }
  }

  def processProcesses(proc : NodeSeq) : Option[Process] = {
    val fmt = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm")
	  optText(proc\"PID") match {
	  case None =>
	    logger.debug("Ignoring entry Process because tag PID is empty")
	    logger.debug(proc)
	    None
	  case Some(pid) =>
	    Some (
	      Process(
	            pid   = pid.toInt
	          , cpuUsage      = optText(proc\"CPUUSAGE").map(_.toFloat)
	          , memory        = optText(proc\"MEM").map(_.toFloat)
	          , commandName   = optText(proc\"CMD")
	          , started       = parseDate(proc\"STARTED",fmt)
	          , tty           = optText(proc\"TTY")
	          , user          = optText(proc\"USER")
	          , virtualMemory = optText(proc\"VIRTUALMEMORY").map(_.toInt)
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
