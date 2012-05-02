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

package com.normation.inventory.ldap.core

import com.unboundid.ldap.sdk.{RDN,DN}
import com.normation.ldap.sdk.LDAPEntry


import LDAPConstants._
import com.normation.utils.Utils.nonEmpty
import com.normation.inventory.domain._
import net.liftweb.common._
import com.normation.utils.HashcodeCaching


/**
 * A DIT is only composed with ENTRY
 */
abstract class ENTRY[T<:Product](val rdnAttribute:T, val rdnValue: T) {
  /**
   * Build an RDN with the RDN attribute and the N values
   * Only expose to subclass, as the "toString" is a little fishy
   * and so implementation should take care of the "toStringibility"
   * of rdn values (for example, it won't work well with JPEG...)
   */
  protected def rdn(rdnValues:T) : RDN = new RDN(
    rdnAttribute.productIterator.map(_.toString).toArray, 
    rdnValues.productIterator.map(_.toString).toArray
  )
  
  /*
   * Note: this abstract entry can not have a "parent DN" as the parent
   * ENTRY, if exsits, may have a variable RDN. 
   * Thing for example to a group member entry whose parent entry, the
   * group, is created at run time. 
   * 
   * Hum. In fact, it should not be a problem, such variable entries
   * should be "def" in place of static val, and in fact should be
   * what is now called the model... and if it is done like that, we
   * could zap model, but we loose static methods. 
   */
}

/**
 * Abstract version of a DIT
 * A DIT always has a BASE_DN
 */
trait AbstractDit {
  val BASE_DN:DN
  private[this] val ditEntries = scala.collection.mutable.Buffer[LDAPEntry]()
  
  /**
   * Register an entry to be available in the default DIT structure (what means that if
   * that entry is missing from the target directory, the software won't be able to work)
   * @param entry
   */
  def register(entry: LDAPEntry) : Unit = ditEntries.append(entry)
  
  /**
   * Find all required entries for that DIT structure. These entries and only
   * these entries have to be present in the LDAP directory to be able to use
   * data from that DIT.
   * Parent entries of the DIT roots are not part of the list of returned entries,
   * specifically the entry for base DN is not returned. 
   */
  def getDITEntries = ditEntries.toSeq
  
  /**
   * Build an id of type T from a dn, checking that 
   * the parent dn match some other parent dn (generally defined
   * from the position in the DIT). 
   */
  protected def buildId[T](dn:DN, parentDn:DN, fid: String=>T) : Option[T] = {
    if(dn.getParent == parentDn) {
      dn.getRDN.getAttributeValues()(0) match {
        case null => None
        case v => Some(fid(v))
      }
    } else None
  }

  require(BASE_DN.getRDNs.size > 0,"The base DN of your DIT can't be empty")
  
}

/**
 * An inventory DIT that represent the structure of the inventory
 * object. 
 * @param BASE_DN
 *   the DN under which Machine, Server, Machine elements and Server elements OUs are
 * @param SOFTWARE_BASE_DN
 *   the DN under which Software OU is
 */
case class InventoryDit(val BASE_DN:DN, val SOFTWARE_BASE_DN:DN, val name:String) extends AbstractDit with HashcodeCaching {
  dit =>
  
  implicit val DIT = dit
    
  val SOFTWARE = new OU("Software", SOFTWARE_BASE_DN) { software =>
    val SOFT = new UUID_ENTRY[SoftwareUuid](OC_SOFTWARE, A_SOFTWARE_UUID, software.dn) {
      
        def idFromDN(dn:DN) : Box[SoftwareUuid] = {
          if(dn.getParent == software.dn) {
            val rdn = dn.getRDN
            if(!rdn.isMultiValued && rdn.getAttributeNames()(0) == A_SOFTWARE_UUID) {
              Full(SoftwareUuid(rdn.getAttributeValues()(0)))
            } else {
              Failure("Unexpected RDN for a software ID")
            }
          } else Failure("DN %s does not belong to software inventories DN %s".format(dn,software.dn))
        }
    }
  }
  
  val NODES = new OU("Nodes", BASE_DN) { servers =>

    val NODE = new UUID_ENTRY[NodeId](OC_NODE, A_NODE_UUID, servers.dn) {

      def genericModel(id:NodeId) : LDAPEntry = {
        val mod = model(id)
        mod += (A_OC,OC.objectClassNames(OC_NODE).toSeq:_*)
        mod
      }

      def linuxModel(id:NodeId) : LDAPEntry = {
        val mod = model(id)
        mod += (A_OC,OC.objectClassNames(OC_LINUX_NODE).toSeq:_*)
        mod
      }
      def windowsModel(id:NodeId) : LDAPEntry = {
        val mod = model(id)
        mod += (A_OC,OC.objectClassNames(OC_WINDOWS_NODE).toSeq:_*)
        mod
      }

      def dn(uuid:String) = new DN(this.rdn(uuid), servers.dn) 
        def idFromDN(dn:DN) : Box[NodeId] = {
          if(dn.getParent == servers.dn) {
            val rdn = dn.getRDN
            if(!rdn.isMultiValued && rdn.getAttributeNames()(0) == A_NODE_UUID) {
              Full(NodeId(rdn.getAttributeValues()(0)))
            } else {
              Failure("Unexpected RDN for a node ID")
            }
          } else Failure("DN %s does not belong to server inventories DN %s".format(dn,servers.dn))
        }
    }

    val NETWORK = new NODE_ELT(NODE,OC_NET_IF,A_NETWORK_NAME, NODE)
    val FILESYSTEM = new NODE_ELT(NODE,OC_FS,A_MOUNT_POINT, NODE)
    val VM = new NODE_ELT(NODE,OC_VM_INFO,A_VM_ID, NODE)
  }
  
  val MACHINES = new OU("Machines", BASE_DN) { machines =>  
    val MACHINE = new UUID_ENTRY[MachineUuid](OC_MACHINE,A_MACHINE_UUID,machines.dn) {
      
        def idFromDN(dn:DN) : Box[MachineUuid] = {
          if(dn.getParent == machines.dn) {
            val rdn = dn.getRDN
            if(!rdn.isMultiValued && rdn.getAttributeNames()(0) == A_MACHINE_UUID) {
              Full(MachineUuid(rdn.getAttributeValues()(0)))
            } else {
              Failure("Unexpected RDN for a machine ID")
            }
          } else Failure("DN %s does not belong to machine inventories DN %s".format(dn,machines.dn))
        }
    }
    
    val BIOS = new MACHINE_ELT(MACHINE,OC_BIOS,A_BIOS_NAME, MACHINE)
    val CONTROLLER = new MACHINE_ELT(MACHINE,OC_CONTROLLER,A_CONTROLLER_NAME, MACHINE)
    val CPU = new MACHINE_ELT(MACHINE,OC_PROCESSOR,A_PROCESSOR_NAME, MACHINE)
    val MEMORY = new MACHINE_ELT(MACHINE,OC_MEMORY,A_MEMORY_SLOT_NUMBER, MACHINE)
    val PORT = new MACHINE_ELT(MACHINE,OC_PORT,A_PORT_NAME, MACHINE)
    val SLOT = new MACHINE_ELT(MACHINE,OC_SLOT,A_SLOT_NAME, MACHINE)
    val SOUND = new MACHINE_ELT(MACHINE,OC_SOUND,A_SOUND_NAME, MACHINE)
    val STORAGE = new MACHINE_ELT(MACHINE,OC_STORAGE,A_STORAGE_NAME, MACHINE)
    val VIDEO = new MACHINE_ELT(MACHINE,OC_VIDEO,A_VIDEO_NAME, MACHINE)

  }

}


/*
 * Plombing to be able to define the DIT as it was
 * (what is an Entry, an OU, a MACHINE_ELT, etc)
 */



/**
 * A special case of ENTRY whose RDN is built from one Attribute
 */
abstract class ENTRY1(override val rdnAttribute:Tuple1[String],override val rdnValue:Tuple1[String] = new Tuple1("")) extends ENTRY[Tuple1[String]](rdnAttribute,rdnValue) {
  def this(rdnAttribute:String, rdnValue:String) = this(Tuple1(rdnAttribute),Tuple1(rdnValue))
  def this(rdnAttribute:String) = this(Tuple1(rdnAttribute))
  
  def rdn(rdnValue:String) : RDN = super.rdn(Tuple1(rdnValue))
}

/**
 * A special case of ENTRY whose RDN is built from two Attributes
 */
//abstract class ENTRY2(override val rdnAttribute:(String,String),override val rdnValue:(String,String) =("","")) extends ENTRY[Tuple2[String,String]](rdnAttribute,rdnValue) {
//  def rdn(rdnValue1:String,rdnValue2:String) : RDN = this.rdn((rdnValue1,rdnValue2))
//}

/**
 * A special vase of ENTRY whose RDN is built from one
 * attribute and that attribute is also an UUID object
 */
class UUID_ENTRY[U <: Uuid](val entryObjectClass:String,val rdnAttributeName:String, val parentDN:DN) extends 
  ENTRY1(Tuple1(rdnAttributeName),Tuple1("")) {
  
  def rdn(u:U) = new RDN(rdnAttributeName,u.value)
  
  def dn(u:U) = new DN(rdn(u),parentDN)
  
  def model(uuid:U) : LDAPEntry = {
    val mod = LDAPEntry(dn(uuid))
    mod +=! (A_OC, OC.objectClassNames(entryObjectClass).toSeq:_*)
    mod
  }
}

/**
 * An Organizational Unit
 */
class OU(ouName:String,parentDn:DN)(implicit dit:AbstractDit) extends ENTRY1("ou",ouName) {
  ou =>
  
  dit.register(ou.model)
  
  lazy val rdn : RDN = this.rdn(this.rdnValue._1)
  lazy val dn = new DN(rdn, parentDn)
  def model() : LDAPEntry = {
    val mod = LDAPEntry(dn)
    mod +=! (A_OC, OC.objectClassNames(OC_OU).toSeq:_*)
    mod
  }
}

/**
 * A special case of ENTRY which represent and element (specialization for
 * physical or logical element)
 * They are expected to be under a root element, whose DN can be calculated from
 * UUID and parent DN
 */
class ELT[U <: Uuid](eltObjectClass:String, override val rdnAttribute:Tuple1[String], parentEntry:UUID_ENTRY[U]) extends ENTRY1(rdnAttribute._1,"") {
  def dn(uuid:U,y:String) = new DN(rdn(y), parentEntry.dn(uuid))
  def model( uuid:U, y:String ) : LDAPEntry = {
    val mod = LDAPEntry(new DN(rdn(y), parentEntry.dn(uuid)))
    mod +=! (A_OC, OC.objectClassNames(eltObjectClass).toSeq:_*)
    mod
  }
}

class NODE_ELT(server:UUID_ENTRY[NodeId], eltObjectClass:String, val attributeName:String, nodeParentEntry:UUID_ENTRY[NodeId]) extends
  ELT[NodeId](eltObjectClass,Tuple1(attributeName), nodeParentEntry) {}
  
class MACHINE_ELT(machine:UUID_ENTRY[MachineUuid], eltObjectClass:String, val attributeName:String, machineParentEntry:UUID_ENTRY[MachineUuid]) extends
  ELT[MachineUuid](eltObjectClass,Tuple1(attributeName),machineParentEntry) {}

