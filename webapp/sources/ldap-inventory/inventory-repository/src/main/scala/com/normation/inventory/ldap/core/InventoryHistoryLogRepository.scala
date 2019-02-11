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

package com.normation.inventory.ldap.core

import java.io.File
import java.io.FileNotFoundException

import com.normation.history.impl._
import com.normation.errors._
import com.normation.inventory.domain._
import com.normation.ldap.sdk.LDAPEntry
import com.unboundid.ldap.sdk.Entry
import com.unboundid.ldif._
import scalaz.zio._

/**
 * A service that write and read ServerAndMachine inventory data to/from file.
 * The file is actually an LDIF file in which each entry is serialized in its
 * LDIFEntry format.
 */
class FullInventoryFileMarshalling(
    fromLdapEntries : FullInventoryFromLdapEntries,
    mapper:InventoryMapper
) extends FileMarshalling[FullInventory] {

  def fromFile(in:File) : IOResult[FullInventory] = {
    import scala.collection.mutable.Buffer
    IO.bracket(Task.effect(new LDIFReader(in)).mapError(e => InventoryError.System(e.getMessage)))(r => UIO(r.close)) { reader =>
      (Task.effect {
        val buf = Buffer[Entry]()
        var e : Entry = null
        do {
          e = reader.readEntry
          if(null != e) buf += e
        } while(null != e)
        buf
      } mapError {
        case e : LDIFException => InventoryError.System(e.getMessage)
        case e : FileNotFoundException => InventoryError.System((s"History file '${in.getAbsolutePath}' was not found. It was likelly deleted"))
      }).flatMap { buf =>
        fromLdapEntries.fromLdapEntries(buf.map(e => new LDAPEntry(e)))
      }
    }
  }

  def toFile(out:File, data: FullInventory) : IOResult[FullInventory] = {
    ZIO.bracket(Task.effect(new LDIFWriter(out)).mapError(e => InventoryError.System(e.getMessage)))(is => Task.effect(is.close).run) { printer =>
      (Task.effect {
        mapper.treeFromNode(data.node).toLDIFRecords.foreach { r => printer.writeLDIFRecord(r) }
        data.machine.foreach { m =>
          mapper.treeFromMachine(m).toLDIFRecords.foreach { r => printer.writeLDIFRecord(r) }
        }
        data
      }) mapError  { e =>
        InventoryError.System(e.getMessage)
      }
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

