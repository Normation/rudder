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

package com.normation.rudder.domain.queries

import com.unboundid.ldap.sdk.{DN,Filter}
import com.normation.ldap.sdk._
import com.normation.inventory.ldap.core._
import LDAPConstants._
import BuildFilter._
import scala.collection.{SortedMap,SortedSet}
import com.normation.rudder.services.queries.SpecialFilter


/*
 * Here we define all data needed logic by the to create the search
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

class DitQueryData(dit:InventoryDit) {      
  private val peObjectCriterion = ObjectCriterion(OC_PE, Seq(
    Criterion(A_MACHINE_UUID, StringComparator),
//    Criterion(A_MACHINE_DN, StringComparator), //we don't want to search on that
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
    Criterion(A_NODE_UUID, StringComparator),
//    Criterion(A_NODE_DN, StringComparator),
//    Criterion(A_NAME, StringComparator),
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
      Criterion(A_MACHINE_UUID, StringComparator),
      Criterion(A_NAME, StringComparator),
      Criterion(A_DESCRIPTION, StringComparator),
      Criterion(A_MB_UUID, StringComparator)
      //TODO : physical ?
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
      Criterion(A_STORAGE_NAME, MemoryComparator),
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
      Criterion(A_PROCESSOR_FAMILLY, StringComparator)
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
      Criterion("OS",OstypeComparator),
      Criterion(A_OS_NAME,OsNameComparator),
      Criterion(A_OS_VERSION, OrderedStringComparator), 
      Criterion(A_OS_SERVICE_PACK, OrderedStringComparator), 
      Criterion(A_OS_KERNEL_VERSION , OrderedStringComparator), 
      Criterion(A_NODE_UUID, StringComparator), 
      Criterion(A_HOSTNAME, StringComparator),
      //Criterion(A_DESCRIPTION, StringComparator),
      Criterion(A_OS_RAM, MemoryComparator), 
      Criterion(A_OS_SWAP, MemoryComparator), 
      Criterion(A_AGENTS_NAME, AgentComparator),
      Criterion(A_ACCOUNT, StringComparator),
      Criterion(A_LIST_OF_IP, StringComparator),
      Criterion(A_ROOT_USER, StringComparator),
      Criterion(A_INVENTORY_DATE, DateComparator),
      Criterion(A_POLICY_SERVER_UUID, StringComparator)
//      Criterion(A_PKEYS, StringComparator)
//      Criterion(A_SOFTWARE_DN, StringComparator),
//      Criterion(A_CONTAINER_DN, StringComparator),
//      Criterion(A_HOSTED_VM_DN, StringComparator)
    )),
    ObjectCriterion(OC_SOFTWARE, Seq(
      Criterion(A_NAME, StringComparator),
      Criterion(A_DESCRIPTION, StringComparator),
      Criterion(A_SOFT_VERSION, StringComparator), 
      Criterion(A_RELEASE_DATE, DateComparator), 
      Criterion(A_EDITOR, EditorComparator)) ++
      licenseObjectCriterion.criteria
    ),
    ObjectCriterion(OC_NET_IF,  leObjectCriterion.criteria ++ Seq(
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
    ObjectCriterion(OC_FS,  leObjectCriterion.criteria ++ Seq(
      Criterion(A_MOUNT_POINT, StringComparator),
      Criterion(A_FILE_COUNT, StringComparator),
      Criterion(A_FREE_SPACE, MemoryComparator),
      Criterion(A_TOTAL_SPACE, MemoryComparator)
    ))/*,
    ObjectCriterion(OC_GROUP_OF_DNS,Seq(
      Criterion(A_NAME,GroupOfDnsComparator)
    ))*/ // Hidding a code difficult to import
  )
    
  val criteriaMap : SortedMap[String,ObjectCriterion] = SortedMap[String,ObjectCriterion]() ++ (criteriaSet map { crit => (crit.objectType,crit) })

  /* 
   * Mapping datas for LDAP query processor
   * 
   * Here, we store what are the LDAP URL for each type, 
   * how join are made between them, etc.
   * 
   */

  val A_DN ="1.1"

  /**
   * selectAttribute is the attribute that will be used in the
   * returned entry to do the Join. 
   * It has two roles:
   * - added to the filter to request less attributes
   * - used for the join
   */
  sealed  abstract class LDAPJoinElement(val selectAttribute:String) 
  final case class AttributeJoin(override val selectAttribute:String) extends LDAPJoinElement(selectAttribute)
  final case object DNJoin extends LDAPJoinElement(A_DN)
  final case object ParentDNJoin extends LDAPJoinElement(A_DN)
//  case class QueryJoin(query:Query) extends LDAPJoinElement
  

/*
 * * "baseDn" of the object type to search for
 * * "scope" to use to retrieve object
 * * "filter" a base filter to use, default ALL
 * * "attribute" is the join attribute, default entry's DN
 */
case class LDAPObjectType(
    baseDn        : DN
  , scope         : SearchScope 
  , filter        : Filter
  , join          : LDAPJoinElement
  , specialFilters: Set[(CriterionComposition, SpecialFilter)] = Set()
)

  //template query for each object type
  def objectTypes = Map(
    "software" -> LDAPObjectType(dit.SOFTWARE.dn, One, ALL, DNJoin),
    "node" -> LDAPObjectType(dit.NODES.dn, One, ALL, DNJoin),
    "networkInterfaceLogicalElement" -> LDAPObjectType(dit.NODES.dn, Sub, IS(OC_NET_IF), ParentDNJoin),
    "fileSystemLogicalElement" -> LDAPObjectType(dit.NODES.dn, Sub, IS(OC_FS), ParentDNJoin),
    "machine" -> LDAPObjectType(dit.MACHINES.dn, One, ALL, DNJoin),
    "processorPhysicalElement" -> LDAPObjectType(dit.MACHINES.dn, Sub, IS(OC_PROCESSOR), ParentDNJoin),
    "memoryPhysicalElement" -> LDAPObjectType(dit.MACHINES.dn, Sub, IS(OC_MEMORY), ParentDNJoin),
    "storagePhysicalElement" -> LDAPObjectType(dit.MACHINES.dn, Sub, IS(OC_STORAGE), ParentDNJoin),
    "biosPhysicalElement" -> LDAPObjectType(dit.MACHINES.dn, Sub, IS(OC_BIOS), ParentDNJoin),
    "controllerPhysicalElement" -> LDAPObjectType(dit.MACHINES.dn, Sub, IS(OC_CONTROLLER), ParentDNJoin),
    "portPhysicalElement" -> LDAPObjectType(dit.MACHINES.dn, Sub, IS(OC_PORT), ParentDNJoin),
    "slotPhysicalElement" -> LDAPObjectType(dit.MACHINES.dn, Sub, IS(OC_SLOT), ParentDNJoin),
    "soundCardPhysicalElement" -> LDAPObjectType(dit.MACHINES.dn, Sub, IS(OC_SOUND), ParentDNJoin),
    "videoCardPhysicalElement" -> LDAPObjectType(dit.MACHINES.dn, Sub, IS(OC_VIDEO), ParentDNJoin)
    //,"groupOfDns" -> LDAPObjectType(dit.GROUPS.dn, Sub, EQ(A_OC,OC_GROUP_OF_DNS), A_DN)
  )
  
  //"kind" of each object type
  val objectDnTypes = Map(
    "software" -> QuerySoftwareDn,
    "node" -> QueryNodeDn,
    "networkInterfaceLogicalElement" -> QueryNodeDn,
    "fileSystemLogicalElement" -> QueryNodeDn,
    "machine" -> QueryMachineDn,
    "processorPhysicalElement" -> QueryMachineDn,
    "memoryPhysicalElement" -> QueryMachineDn,
    "storagePhysicalElement" -> QueryMachineDn,
    "biosPhysicalElement" -> QueryMachineDn,
    "controllerPhysicalElement" -> QueryMachineDn,
    "portPhysicalElement" -> QueryMachineDn,
    "slotPhysicalElement" -> QueryMachineDn,
    "soundCardPhysicalElement" -> QueryMachineDn,
    "videoCardPhysicalElement" -> QueryMachineDn
    //,"groupOfDns" -> QueryServerDn
  )

  //Join attribute between Kind
  //special attribute: dn ( rdn, parentdn ?)
  //Entries MUST NOT HAVE attributes named with a special name (dn...)
  //Join attribute MUST BE DNs
  val joinAttributes = Map(
    "software" -> DNJoin,
    "node" -> DNJoin,
    "networkInterfaceLogicalElement" -> ParentDNJoin,
    "fileSystemLogicalElement" -> ParentDNJoin,
    "machine" -> DNJoin,
    "processorPhysicalElement" -> ParentDNJoin,
    "memoryPhysicalElement" -> ParentDNJoin,
    "storagePhysicalElement" -> ParentDNJoin,
    "biosPhysicalElement" -> ParentDNJoin,
    "controllerPhysicalElement" -> ParentDNJoin,
    "portPhysicalElement" -> ParentDNJoin,
    "slotPhysicalElement" -> ParentDNJoin,
    "soundCardPhysicalElement" -> ParentDNJoin,
    "videoCardPhysicalElement" -> ParentDNJoin
    //,"groupOfDns" -> A_MEMBER
  )

  //how do you create a filter from a DN,
  //when you want to query such an object
  val serverJoinFilters = Map[DnType, DN => Filter](
    QueryNodeDn -> ((dn:DN) => (EQ(A_NODE_UUID, dn.getRDN.getAttributeValues()(0)))),
    QuerySoftwareDn -> ((dn:DN) => (EQ(A_SOFTWARE_DN, dn.toString))),
    QueryMachineDn -> ((dn:DN) => (EQ(A_CONTAINER_DN, dn.toString)))
  )

  //that must always hold
  require(objectTypes.keySet == objectDnTypes.keySet, "Opbject type definition inconsistent between objectTypes and objectDnTypes")
  require(objectTypes.keySet == joinAttributes.keySet, "Opbject type definition inconsistent between objectTypes and joinAttributes")
  require(objectTypes.keySet == joinAttributes.keySet, "Opbject type definition inconsistent between objectTypes and joinAttributes")


}
