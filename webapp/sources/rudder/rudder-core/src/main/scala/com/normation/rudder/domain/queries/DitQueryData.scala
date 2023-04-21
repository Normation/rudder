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

import com.normation.inventory.ldap.core._
import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.ldap.sdk._
import com.normation.ldap.sdk.BuildFilter._
import com.normation.rudder.domain.NodeDit
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants.OC_RUDDER_NODE_GROUP
import com.normation.rudder.services.queries.SpecialFilter
import com.unboundid.ldap.sdk.DN
import com.unboundid.ldap.sdk.Filter
import scala.collection.SortedMap

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
case object QueryMachineDn  extends DnType
case object QueryNodeDn     extends DnType
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
sealed abstract class LDAPJoinElement(val selectAttribute: String)
case object DNJoin       extends LDAPJoinElement("1.1")
case object ParentDNJoin extends LDAPJoinElement("1.1")
case object NodeDnJoin   extends LDAPJoinElement(A_NODE_UUID)
//  case class QueryJoin(query:Query) extends LDAPJoinElement

//that class represent the base filter for an object type.
//it's special because it MUST always be ANDED to any
//request for that object type.
final case class LDAPObjectTypeFilter(value: Filter) extends AnyVal

class DitQueryData(dit: InventoryDit, nodeDit: NodeDit, rudderDit: RudderDit, criteria: NodeQueryCriteriaData) {

  val criteriaMap: SortedMap[String, ObjectCriterion] = criteria.criteriaMap

  /*
   * * "baseDn" of the object type to search for
   * * "scope" to use to retrieve object
   * * "filter" a base filter to use, default ALL
   * * "attribute" is the join attribute, default entry's DN
   */
  case class LDAPObjectType(
      baseDn:         DN,
      scope:          SearchScope,
      objectFilter:   LDAPObjectTypeFilter,
      filter:         Option[Filter],
      join:           LDAPJoinElement,
      specialFilters: Set[(CriterionComposition, SpecialFilter)] = Set()
  )

  // template query for each object type

  def objectTypes = {
    def LAOT  = LDAPObjectType
    def LAOTF = LDAPObjectTypeFilter
    Map(
      "software"                       -> LAOT(dit.SOFTWARE.dn, One, LAOTF(ALL), None, DNJoin),
      "node"                           -> LAOT(dit.NODES.dn, One, LAOTF(ALL), None, DNJoin),
      "rudderNode"                     -> LAOT(nodeDit.NODES.dn, One, LAOTF(ALL), None, DNJoin),
      "nodeAndPolicyServer"            -> LAOT(dit.NODES.dn, One, LAOTF(ALL), None, DNJoin),
      "serializedNodeProperty"         -> LAOT(nodeDit.NODES.dn, One, LAOTF(ALL), None, DNJoin),
      "networkInterfaceLogicalElement" -> LAOT(dit.NODES.dn, Sub, LAOTF(IS(OC_NET_IF)), None, ParentDNJoin),
      "process"                        -> LAOT(dit.NODES.dn, One, LAOTF(ALL), None, DNJoin),
      "virtualMachineLogicalElement"   -> LAOT(dit.NODES.dn, Sub, LAOTF(IS(OC_VM_INFO)), None, ParentDNJoin),
      "environmentVariable"            -> LAOT(dit.NODES.dn, One, LAOTF(ALL), None, DNJoin),
      "networkInterfaceLogicalElement" -> LAOT(dit.NODES.dn, Sub, LAOTF(IS(OC_NET_IF)), None, ParentDNJoin),
      "fileSystemLogicalElement"       -> LAOT(dit.NODES.dn, Sub, LAOTF(IS(OC_FS)), None, ParentDNJoin),
      "machine"                        -> LAOT(dit.MACHINES.dn, One, LAOTF(ALL), None, DNJoin),
      "processorPhysicalElement"       -> LAOT(dit.MACHINES.dn, Sub, LAOTF(IS(OC_PROCESSOR)), None, ParentDNJoin),
      "memoryPhysicalElement"          -> LAOT(dit.MACHINES.dn, Sub, LAOTF(IS(OC_MEMORY)), None, ParentDNJoin),
      "storagePhysicalElement"         -> LAOT(dit.MACHINES.dn, Sub, LAOTF(IS(OC_STORAGE)), None, ParentDNJoin),
      "biosPhysicalElement"            -> LAOT(dit.MACHINES.dn, Sub, LAOTF(IS(OC_BIOS)), None, ParentDNJoin),
      "controllerPhysicalElement"      -> LAOT(dit.MACHINES.dn, Sub, LAOTF(IS(OC_CONTROLLER)), None, ParentDNJoin),
      "portPhysicalElement"            -> LAOT(dit.MACHINES.dn, Sub, LAOTF(IS(OC_PORT)), None, ParentDNJoin),
      "slotPhysicalElement"            -> LAOT(dit.MACHINES.dn, Sub, LAOTF(IS(OC_SLOT)), None, ParentDNJoin),
      "soundCardPhysicalElement"       -> LAOT(dit.MACHINES.dn, Sub, LAOTF(IS(OC_SOUND)), None, ParentDNJoin),
      "videoCardPhysicalElement"       -> LAOT(dit.MACHINES.dn, Sub, LAOTF(IS(OC_VIDEO)), None, ParentDNJoin),
      "group"                          -> LAOT(rudderDit.GROUP.dn, Sub, LAOTF(IS(OC_RUDDER_NODE_GROUP)), None, NodeDnJoin)
    )

  }

  // We only know how to query NODES for now: special word for it.
  val nodeObjectTypes = objectTypes("node")

  // "kind" of each object type
  val objectDnTypes: Map[String, DnType] = {
    Map(
      "software"                       -> QuerySoftwareDn,
      "node"                           -> QueryNodeDn,
      "rudderNode"                     -> QueryNodeDn,
      "nodeAndPolicyServer"            -> QueryNodeDn,
      "serializedNodeProperty"         -> QueryNodeDn,
      "networkInterfaceLogicalElement" -> QueryNodeDn,
      "fileSystemLogicalElement"       -> QueryNodeDn,
      "process"                        -> QueryNodeDn,
      "virtualMachineLogicalElement"   -> QueryNodeDn,
      "environmentVariable"            -> QueryNodeDn,
      "machine"                        -> QueryMachineDn,
      "processorPhysicalElement"       -> QueryMachineDn,
      "memoryPhysicalElement"          -> QueryMachineDn,
      "storagePhysicalElement"         -> QueryMachineDn,
      "biosPhysicalElement"            -> QueryMachineDn,
      "controllerPhysicalElement"      -> QueryMachineDn,
      "portPhysicalElement"            -> QueryMachineDn,
      "slotPhysicalElement"            -> QueryMachineDn,
      "soundCardPhysicalElement"       -> QueryMachineDn,
      "videoCardPhysicalElement"       -> QueryMachineDn,
      "group"                          -> QueryNodeDn
    )
  }

  // Join attribute between Kind
  // special attribute: dn ( rdn, parentdn ?)
  // Entries MUST NOT HAVE attributes named with a special name (dn...)
  // Join attribute MUST BE DNs
  val joinAttributes = Map(
    "software"                       -> DNJoin,
    "node"                           -> DNJoin,
    "rudderNode"                     -> DNJoin,
    "nodeAndPolicyServer"            -> DNJoin,
    "serializedNodeProperty"         -> DNJoin,
    "networkInterfaceLogicalElement" -> ParentDNJoin,
    "fileSystemLogicalElement"       -> ParentDNJoin,
    "process"                        -> DNJoin,
    "virtualMachineLogicalElement"   -> ParentDNJoin,
    "environmentVariable"            -> DNJoin,
    "machine"                        -> DNJoin,
    "processorPhysicalElement"       -> ParentDNJoin,
    "memoryPhysicalElement"          -> ParentDNJoin,
    "storagePhysicalElement"         -> ParentDNJoin,
    "biosPhysicalElement"            -> ParentDNJoin,
    "controllerPhysicalElement"      -> ParentDNJoin,
    "portPhysicalElement"            -> ParentDNJoin,
    "slotPhysicalElement"            -> ParentDNJoin,
    "soundCardPhysicalElement"       -> ParentDNJoin,
    "videoCardPhysicalElement"       -> ParentDNJoin,
    "group"                          -> NodeDnJoin
  )

  // how do you create a filter from a DN,
  // when you want to query such an object
  val nodeJoinFilters = Map[DnType, DN => Filter](
    QueryNodeDn     -> ((dn: DN) => (EQ(A_NODE_UUID, dn.getRDN.getAttributeValues()(0)))),
    QuerySoftwareDn -> ((dn: DN) => (EQ(A_SOFTWARE_DN, dn.toString))),
    QueryMachineDn  -> ((dn: DN) => (EQ(A_CONTAINER_DN, dn.toString)))
  )

  // that must always hold
  require(
    objectTypes.keySet == objectDnTypes.keySet,
    "Opbject type definition inconsistent between objectTypes and objectDnTypes"
  )
  require(
    objectTypes.keySet == joinAttributes.keySet,
    "Opbject type definition inconsistent between objectTypes and joinAttributes"
  )

}
