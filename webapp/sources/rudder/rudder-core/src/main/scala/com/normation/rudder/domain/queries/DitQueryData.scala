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

package com.normation.rudder.domain.queries

import com.unboundid.ldap.sdk.{DN, Filter}
import com.normation.ldap.sdk._
import com.normation.inventory.ldap.core._
import LDAPConstants._
import BuildFilter._
import com.normation.errors.PureResult

import scala.collection.SortedMap
import com.normation.rudder.services.queries.SpecialFilter
import com.normation.utils.HashcodeCaching
import com.normation.rudder.domain.NodeDit
import com.normation.rudder.domain.RudderLDAPConstants.{A_NODE_GROUP_UUID, A_NODE_PROPERTY, A_STATE, OC_RUDDER_NODE_GROUP}
import com.normation.rudder.domain.RudderDit
import net.liftweb.common.Box

import com.normation.errors._

/*
 * Here we define all data needed logic by the webapp to create the search
 * form.
 *
 * The search form is organized in 4 levels :
 * - ObjectCriterion, which is a container for criterion definition
 *   ex: Node (for criterion on node LDAP attribute)
 * - Criterion, which describe what and how query a given attribute
 *   ex: Criterion on attribute "A_SERIAL_NUMBER" in peObjectCriterion use a StringComparator
 * - Comparator, which have two fields: the comparator type, and optionally a value to compare to
 *   ex: StringComparator has four comparators (exists, not exists, equals, not equals), the
 *       last two taking an argument.
 *
 */

/*
 * Type of DN on which we can make join
 *
 */
sealed abstract class DnType
case object QueryMachineDn extends DnType
case object QueryNodeDn extends DnType
case object QuerySoftwareDn extends DnType

/*
 * Mapping data for LDAP query processor
 *
 * Here, we store what are the LDAP URL for each type,
 * how join are made between them, etc.
 *
 */

/**
 * selectAttribute is the attribute that will be used in the
 * returned entry to do the Join.
 * It has two roles:
 * - added to the filter to request less attributes
 * - used for the join
 */
sealed  abstract class LDAPJoinElement(val selectAttribute:String)
final case object DNJoin extends LDAPJoinElement("1.1") with HashcodeCaching
final case object ParentDNJoin extends LDAPJoinElement("1.1") with HashcodeCaching
final case object NodeDnJoin extends LDAPJoinElement(A_NODE_UUID) with HashcodeCaching
//  case class QueryJoin(query:Query) extends LDAPJoinElement

//that class represent the base filter for an object type.
//it's special because it MUST always be ANDED to any
//request for that object type.
final case class LDAPObjectTypeFilter(value: Filter)

class DitQueryData(dit: InventoryDit, nodeDit: NodeDit, rudderDit: RudderDit, getGroups: () => IOResult[Seq[SubGroupChoice]]) {
  private val peObjectCriterion = ObjectCriterion(OC_PE, Seq(
    //Criterion(A_MACHINE_UUID, StringComparator),
    //Criterion(A_MACHINE_DN, StringComparator), //we don't want to search on that
    Criterion(A_DESCRIPTION, StringComparator),
    Criterion(A_MODEL, StringComparator),
    Criterion(A_SERIAL_NUMBER, StringComparator),
    Criterion(A_FIRMWARE, StringComparator),
    Criterion(A_QUANTITY, LongComparator),
    Criterion(A_SME_TYPE, StringComparator),
    Criterion(A_STATUS, LongComparator),
    Criterion(A_MANUFACTURER, StringComparator)
  ))

  private val leObjectCriterion = ObjectCriterion(OC_LE, Seq(
// Criterion(A_NODE_UUID, StringComparator),
// Criterion(A_NODE_DN, StringComparator),
// Criterion(A_NAME, StringComparator),
    Criterion(A_DESCRIPTION, StringComparator)
  ))

  private val licenseObjectCriterion = ObjectCriterion("licence", Seq(
    Criterion(A_LICENSE_EXP, DateComparator),
    Criterion(A_LICENSE_NAME, StringComparator),
    Criterion(A_LICENSE_PRODUCT_ID, StringComparator),
    Criterion(A_LICENSE_PRODUCT_KEY, StringComparator)
  ));

  protected val criteriaSet = Set(
    ObjectCriterion(OC_MACHINE, Seq(
      Criterion("machineType", MachineComparator),
      Criterion(A_MACHINE_UUID, StringComparator),
      Criterion(A_NAME, StringComparator),
      Criterion(A_DESCRIPTION, StringComparator),
      Criterion(A_MB_UUID, StringComparator),
      Criterion(A_MANUFACTURER, StringComparator),
      Criterion(A_SERIAL_NUMBER, StringComparator)
    )),
    ObjectCriterion(OC_MEMORY, peObjectCriterion.criteria ++ Seq(
      Criterion(A_MEMORY_SLOT_NUMBER, LongComparator),
      Criterion(A_NAME, StringComparator),
      Criterion(A_MEMORY_CAPACITY, MemoryComparator),
      Criterion(A_MEMORY_CAPTION, StringComparator),
      Criterion(A_MEMORY_SPEED, LongComparator),
      Criterion(A_MEMORY_TYPE, StringComparator)
    )),
    ObjectCriterion(OC_STORAGE, peObjectCriterion.criteria ++ Seq(
      Criterion(A_STORAGE_NAME, StringComparator),
      Criterion(A_STORAGE_SIZE, MemoryComparator),
      Criterion(A_STORAGE_FIRMWARE, StringComparator)
    )),
    ObjectCriterion(OC_BIOS, peObjectCriterion.criteria ++ Seq(
      Criterion(A_BIOS_NAME, StringComparator),
      Criterion(A_RELEASE_DATE, StringComparator),
      Criterion(A_EDITOR, StringComparator),
      Criterion(A_SOFT_VERSION, StringComparator)) ++
      licenseObjectCriterion.criteria
    ),
    ObjectCriterion(OC_CONTROLLER, peObjectCriterion.criteria ++ Seq(
      Criterion(A_CONTROLLER_NAME, StringComparator)
    )),
    ObjectCriterion(OC_PORT, peObjectCriterion.criteria ++ Seq(
      Criterion(A_PORT_NAME, StringComparator)
    )),
    ObjectCriterion(OC_PROCESSOR, peObjectCriterion.criteria ++ Seq(
      Criterion(A_PROCESSOR_NAME, StringComparator),
      Criterion(A_PROCESSOR_SPEED, LongComparator),
      Criterion(A_PROCESSOR_STEPPING, StringComparator),
      Criterion(A_PROCESSOR_FAMILLY, StringComparator),
      Criterion(A_PROCESSOR_FAMILY_NAME, StringComparator),
      Criterion(A_THREAD, StringComparator),
      Criterion(A_CORE, StringComparator)
    )),
    ObjectCriterion(OC_SLOT, peObjectCriterion.criteria++ Seq(
      Criterion(A_SLOT_NAME, StringComparator)
    )),
    ObjectCriterion(OC_SOUND, peObjectCriterion.criteria++ Seq(
      Criterion(A_SOUND_NAME, StringComparator)
    )),
    ObjectCriterion(OC_VIDEO, peObjectCriterion.criteria ++ Seq(
      Criterion(A_VIDEO_NAME, StringComparator),
      Criterion(A_VIDEO_CHIPSET, StringComparator),
      Criterion(A_VIDEO_RESOLUTION, StringComparator),
      Criterion(A_MEMORY_CAPACITY, MemoryComparator)
    )),
    ObjectCriterion(OC_NODE, Seq(
        Criterion("OS",OstypeComparator)
      , Criterion(A_NODE_UUID, StringComparator)
      , Criterion(A_HOSTNAME, StringComparator)
      , Criterion(A_OS_NAME,OsNameComparator)
      , Criterion(A_OS_FULL_NAME, OrderedStringComparator)
      , Criterion(A_OS_VERSION, OrderedStringComparator)
      , Criterion(A_OS_SERVICE_PACK, OrderedStringComparator)
      , Criterion(A_OS_KERNEL_VERSION , OrderedStringComparator)
      , Criterion(A_ARCH, StringComparator)
      , Criterion(A_SERVER_ROLE, StringComparator)
      , Criterion(A_STATE, NodeStateComparator, Some("rudderNode"))
      , Criterion(A_OS_RAM, MemoryComparator)
      , Criterion(A_OS_SWAP, MemoryComparator)
      , Criterion(A_AGENTS_NAME, AgentComparator)
      , Criterion(A_ACCOUNT, StringComparator)
      , Criterion(A_LIST_OF_IP, StringComparator)
      , Criterion(A_ROOT_USER, StringComparator)
      , Criterion(A_INVENTORY_DATE, DateComparator)
      , Criterion(A_POLICY_SERVER_UUID, StringComparator)
    )),
    ObjectCriterion(OC_SOFTWARE, Seq(
      Criterion(A_NAME, StringComparator),
      Criterion(A_DESCRIPTION, StringComparator),
      Criterion(A_SOFT_VERSION, StringComparator),
      Criterion(A_RELEASE_DATE, DateComparator),
      Criterion(A_EDITOR, EditorComparator)) ++
      licenseObjectCriterion.criteria
    ),
    ObjectCriterion(OC_NET_IF, leObjectCriterion.criteria ++ Seq(
      Criterion(A_NETWORK_NAME, StringComparator),
      Criterion(A_NETIF_ADDRESS, StringComparator),
      Criterion(A_NETIF_DHCP, StringComparator),
      Criterion(A_NETIF_GATEWAY, StringComparator),
      Criterion(A_NETIF_MASK, StringComparator),
      Criterion(A_NETIF_SUBNET, StringComparator),
      Criterion(A_NETIF_MAC, StringComparator),
      Criterion(A_NETIF_TYPE, StringComparator),
      Criterion(A_NETIF_TYPE_MIB, StringComparator)
    )),
    ObjectCriterion(OC_FS, leObjectCriterion.criteria ++ Seq(
      Criterion(A_MOUNT_POINT, StringComparator),
      Criterion(A_FILE_COUNT, StringComparator),
      Criterion(A_FREE_SPACE, MemoryComparator),
      Criterion(A_TOTAL_SPACE, MemoryComparator)
    )),
    ObjectCriterion(A_PROCESS, Seq(
      Criterion("pid", JsonFixedKeyComparator(A_PROCESS, "pid", false)),
      Criterion("commandName", JsonFixedKeyComparator(A_PROCESS, "commandName", true)),
      Criterion("cpuUsage", JsonFixedKeyComparator(A_PROCESS, "cpuUsage",false)),
      Criterion("memory", JsonFixedKeyComparator(A_PROCESS, "memory",false)),
      Criterion("tty", JsonFixedKeyComparator(A_PROCESS, "tty", true)),
      Criterion("virtualMemory", JsonFixedKeyComparator(A_PROCESS, "virtualMemory", false)),
      Criterion("started", JsonFixedKeyComparator(A_PROCESS, "started", true)),
      Criterion("user", JsonFixedKeyComparator(A_PROCESS, "user", true))
    )),
    ObjectCriterion(OC_VM_INFO, leObjectCriterion.criteria ++ Seq(
      Criterion(A_VM_TYPE, StringComparator),
      Criterion(A_VM_OWNER, StringComparator),
      Criterion(A_VM_STATUS, StringComparator),
      Criterion(A_VM_CPU, LongComparator),
      Criterion(A_VM_MEMORY, LongComparator),
      Criterion(A_VM_ID, StringComparator),
      Criterion(A_VM_SUBSYSTEM, StringComparator),
      Criterion(A_VM_NAME, StringComparator)
    )),
    ObjectCriterion(A_EV, Seq(
      Criterion("name.value", NameValueComparator(A_EV) )
    ))
  , ObjectCriterion(A_NODE_PROPERTY, Seq(
      Criterion("name.value", NodePropertyComparator(A_NODE_PROPERTY) )
    ))
  , ObjectCriterion("group", Seq(
      Criterion(A_NODE_GROUP_UUID, new SubGroupComparator(getGroups))
    ))
  )

  val criteriaMap : SortedMap[String,ObjectCriterion] = SortedMap[String,ObjectCriterion]() ++ (criteriaSet map { crit => (crit.objectType,crit) })

/*
 * * "baseDn" of the object type to search for
 * * "scope" to use to retrieve object
 * * "filter" a base filter to use, default ALL
 * * "attribute" is the join attribute, default entry's DN
 */
case class LDAPObjectType(
    baseDn        : DN
  , scope         : SearchScope
  , objectFilter  : LDAPObjectTypeFilter
  , filter        : Option[Filter]
  , join          : LDAPJoinElement
  , specialFilters: Set[(CriterionComposition, SpecialFilter)] = Set()
) extends HashcodeCaching

  //template query for each object type

  def objectTypes = {
    def LAOT = LDAPObjectType
    def LAOTF = LDAPObjectTypeFilter
    Map(
      "software"                       -> LAOT(dit.SOFTWARE.dn, One, LAOTF(ALL), None, DNJoin)
    , "node"                           -> LAOT(dit.NODES.dn, One, LAOTF(ALL), None, DNJoin)
    , "rudderNode"                     -> LAOT(nodeDit.NODES.dn, One, LAOTF(ALL), None, DNJoin)
    , "nodeAndPolicyServer"            -> LAOT(dit.NODES.dn, One, LAOTF(ALL), None, DNJoin)
    , "serializedNodeProperty"         -> LAOT(nodeDit.NODES.dn, One, LAOTF(ALL),None,  DNJoin)
    , "networkInterfaceLogicalElement" -> LAOT(dit.NODES.dn, Sub, LAOTF(IS(OC_NET_IF)), None, ParentDNJoin)
    , "process"                        -> LAOT(dit.NODES.dn, One, LAOTF(ALL), None, DNJoin)
    , "virtualMachineLogicalElement"   -> LAOT(dit.NODES.dn, Sub, LAOTF(IS(OC_VM_INFO)), None, ParentDNJoin)
    , "environmentVariable"            -> LAOT(dit.NODES.dn, One, LAOTF(ALL),None,  DNJoin)
    , "networkInterfaceLogicalElement" -> LAOT(dit.NODES.dn, Sub, LAOTF(IS(OC_NET_IF)), None, ParentDNJoin)
    , "fileSystemLogicalElement"       -> LAOT(dit.NODES.dn, Sub, LAOTF(IS(OC_FS)), None, ParentDNJoin)
    , "machine"                        -> LAOT(dit.MACHINES.dn, One, LAOTF(ALL), None, DNJoin)
    , "processorPhysicalElement"       -> LAOT(dit.MACHINES.dn, Sub, LAOTF(IS(OC_PROCESSOR)), None, ParentDNJoin)
    , "memoryPhysicalElement"          -> LAOT(dit.MACHINES.dn, Sub, LAOTF(IS(OC_MEMORY)), None, ParentDNJoin)
    , "storagePhysicalElement"         -> LAOT(dit.MACHINES.dn, Sub, LAOTF(IS(OC_STORAGE)), None, ParentDNJoin)
    , "biosPhysicalElement"            -> LAOT(dit.MACHINES.dn, Sub, LAOTF(IS(OC_BIOS)), None, ParentDNJoin)
    , "controllerPhysicalElement"      -> LAOT(dit.MACHINES.dn, Sub, LAOTF(IS(OC_CONTROLLER)), None, ParentDNJoin)
    , "portPhysicalElement"            -> LAOT(dit.MACHINES.dn, Sub, LAOTF(IS(OC_PORT)), None, ParentDNJoin)
    , "slotPhysicalElement"            -> LAOT(dit.MACHINES.dn, Sub, LAOTF(IS(OC_SLOT)), None, ParentDNJoin)
    , "soundCardPhysicalElement"       -> LAOT(dit.MACHINES.dn, Sub, LAOTF(IS(OC_SOUND)), None, ParentDNJoin)
    , "videoCardPhysicalElement"       -> LAOT(dit.MACHINES.dn, Sub, LAOTF(IS(OC_VIDEO)), None, ParentDNJoin)
    , "group"                          -> LAOT(rudderDit.GROUP.dn, Sub, LAOTF(IS(OC_RUDDER_NODE_GROUP)), None, NodeDnJoin)
  )

  }

  //We only know how to query NODES for now: special word for it.
  val nodeObjectTypes = objectTypes("node")

  //"kind" of each object type
  val objectDnTypes  : Map[String,DnType] =
    Map (
        "software"                       -> QuerySoftwareDn
      , "node"                           -> QueryNodeDn
      , "rudderNode"                     -> QueryNodeDn
      , "nodeAndPolicyServer"            -> QueryNodeDn
      , "serializedNodeProperty"         -> QueryNodeDn
      , "networkInterfaceLogicalElement" -> QueryNodeDn
      , "fileSystemLogicalElement"       -> QueryNodeDn
      , "process"                        -> QueryNodeDn
      , "virtualMachineLogicalElement"   -> QueryNodeDn
      , "environmentVariable"            -> QueryNodeDn
      , "machine"                        -> QueryMachineDn
      , "processorPhysicalElement"       -> QueryMachineDn
      , "memoryPhysicalElement"          -> QueryMachineDn
      , "storagePhysicalElement"         -> QueryMachineDn
      , "biosPhysicalElement"            -> QueryMachineDn
      , "controllerPhysicalElement"      -> QueryMachineDn
      , "portPhysicalElement"            -> QueryMachineDn
      , "slotPhysicalElement"            -> QueryMachineDn
      , "soundCardPhysicalElement"       -> QueryMachineDn
      , "videoCardPhysicalElement"       -> QueryMachineDn
      , "group"                          -> QueryNodeDn
    )

  //Join attribute between Kind
  //special attribute: dn ( rdn, parentdn ?)
  //Entries MUST NOT HAVE attributes named with a special name (dn...)
  //Join attribute MUST BE DNs
  val joinAttributes = Map(
      "software"                       -> DNJoin
    , "node"                           -> DNJoin
    , "rudderNode"                     -> DNJoin
    , "nodeAndPolicyServer"            -> DNJoin
    , "serializedNodeProperty"         -> DNJoin
    , "networkInterfaceLogicalElement" -> ParentDNJoin
    , "fileSystemLogicalElement"       -> ParentDNJoin
    , "process"                        -> DNJoin
    , "virtualMachineLogicalElement"   -> ParentDNJoin
    , "environmentVariable"            -> DNJoin
    , "machine"                        -> DNJoin
    , "processorPhysicalElement"       -> ParentDNJoin
    , "memoryPhysicalElement"          -> ParentDNJoin
    , "storagePhysicalElement"         -> ParentDNJoin
    , "biosPhysicalElement"            -> ParentDNJoin
    , "controllerPhysicalElement"      -> ParentDNJoin
    , "portPhysicalElement"            -> ParentDNJoin
    , "slotPhysicalElement"            -> ParentDNJoin
    , "soundCardPhysicalElement"       -> ParentDNJoin
    , "videoCardPhysicalElement"       -> ParentDNJoin
    , "group"                          -> NodeDnJoin
  )

  //how do you create a filter from a DN,
  //when you want to query such an object
  val nodeJoinFilters = Map[DnType, DN => Filter](
    QueryNodeDn -> ((dn:DN) => (EQ(A_NODE_UUID, dn.getRDN.getAttributeValues()(0)))),
    QuerySoftwareDn -> ((dn:DN) => (EQ(A_SOFTWARE_DN, dn.toString))),
    QueryMachineDn -> ((dn:DN) => (EQ(A_CONTAINER_DN, dn.toString)))
  )

  //that must always hold
  require(objectTypes.keySet == objectDnTypes.keySet, "Opbject type definition inconsistent between objectTypes and objectDnTypes")
  require(objectTypes.keySet == joinAttributes.keySet, "Opbject type definition inconsistent between objectTypes and joinAttributes")

}
