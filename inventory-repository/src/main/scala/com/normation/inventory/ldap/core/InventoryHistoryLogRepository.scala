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

import com.normation.inventory.domain._
import com.normation.history.impl._
import java.io.File
import com.normation.ldap.sdk.LDAPEntry
import com.unboundid.ldap.sdk.Entry
import com.unboundid.ldif._
import net.liftweb.common._

/**
 * A service that write and read ServerAndMachine inventory data to/from file.
 * The file is actually an LDIF file in which each entry is serialized in its
 * LDIFEntry format.
 */
class FullInventoryFileMarshalling(
    fromLdapEntries : FullInventoryFromLdapEntries,
    mapper:InventoryMapper
) extends FileMarshalling[FullInventory] {

  def fromFile(in:File) : Box[FullInventory] = {
    import scala.collection.mutable.Buffer
    var reader:LDIFReader = null
    try {
      reader = new LDIFReader(in)
      val buf = Buffer[Entry]()
      var e : Entry = null
      do {
        e = reader.readEntry
        if(null != e) buf += e
      } while(null != e)
      fromLdapEntries.fromLdapEntries(buf.map(e => new LDAPEntry(e)))
    } catch {
      case e : LDIFException => Failure(e.getMessage,Full(e),Empty)
    } finally {
      if(null != reader) reader.close
    }
  }

  def toFile(out:File, data: FullInventory) : Box[FullInventory] = {
    var printer:LDIFWriter = null
    try {
      printer = new LDIFWriter(out)
      mapper.treeFromNode(data.node).toLDIFRecords.foreach { r => printer.writeLDIFRecord(r) }
      data.machine.foreach { m =>
        mapper.treeFromMachine(m).toLDIFRecords.foreach { r => printer.writeLDIFRecord(r) }
      }
      Full(data)
    } catch {
      case e:Exception => Failure(e.getMessage,Full(e),Empty)
    } finally {
      if(null != printer) printer.close
    }
  }
}

object NodeIdConverter extends IdToFilenameConverter[NodeId] {
  override def idToFilename(id:NodeId) : String = id.value
  override def filenameToId(name:String) : NodeId = NodeId(name)
}

/**
 * History of Inventory reports (server and machine inventory,
 * not software inventory)
 * They are saved on filesystem in their LDIFRecord representation
 */
class InventoryHistoryLogRepository(
  override val rootDir:String,
  override val marshaller:FullInventoryFileMarshalling
) extends FileHistoryLogRepository[NodeId,FullInventory](rootDir,marshaller,NodeIdConverter)

