/*
*************************************************************************************
* Copyright 2017 Normation SAS
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

package bootstrap.liftweb.checks

import bootstrap.liftweb.BootstrapChecks
import com.normation.ldap.ldif.LDIFNoopChangeRecord
import com.normation.ldap.sdk.BuildFilter
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.LDAPEntry
import com.normation.ldap.sdk.RwLDAPConnection
import com.unboundid.ldap.sdk.Attribute
import com.unboundid.ldap.sdk.DN
import com.unboundid.ldif.LDIFChangeRecord
import com.unboundid.ldif.LDIFReader
import javax.servlet.UnavailableException
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full

import scala.util.control.NonFatal
import com.unboundid.ldap.sdk.RDN
import java.io.File
import java.io.FileInputStream

import bootstrap.liftweb.BootraspLogger

import com.normation.errors._
import scalaz.zio._
import scalaz.zio.syntax._
import com.normation.box._

/**
 * The goal of that check is to help migration of Rudder version < 4.2
 * to the agent-specific target for system rules.
 * It check that:
 *   - group "hasPolicyServer-*" include a check on the agent,
 *   - group "all-nodes-with-cfengine-agent" is present and well defined
 *   - rule inventory-all has target "all-nodes-with-cfengine-agent"
 *
 * We are reading from bootstrap.ldif & init-policy-server.ldif to not
 * duplicate the code.
 */

object CheckCfengineSystemRuleTargets {

  /*
   * An utility method which given two entries, one wanted and one existing,
   * compare the two and make de update if needed.
   * Factor out because of the ton of logs to write each time.
   */
  def compare(con: RwLDAPConnection, wanted: LDAPEntry, existing: LDAPEntry): IOResult[LDIFChangeRecord] = {
    // the list of attribute we don't want to copy from templates:
    // here, we reset all nodeId already present because there is not reason to force recalculating
    // that group (you can't have anything but cfengine agent yet)
    val keepAttrs = "serial" :: "nodeId" :: "acceptationTimestamp" :: Nil

    //arf, mutation
    keepAttrs.foreach { attr =>
      wanted -= attr
      existing -= attr
    }
    if(existing == wanted) {
      LDIFNoopChangeRecord(existing.dn).succeed
    } else {
      con.save(wanted, true, keepAttrs).flatMap { mod =>
        (if(mod != LDIFNoopChangeRecord(wanted.dn)) {
          BootraspLogger.logPure.info(s"Updating system configuration stored in entry '${wanted.dn.toString}': ${mod}")
        } else {
          UIO.unit
        }) *> mod.succeed
      }
    }
  }

}

class CheckCfengineSystemRuleTargets(
    ldap          : LDAPConnectionProvider[RwLDAPConnection]
) extends BootstrapChecks {
  import CheckCfengineSystemRuleTargets.compare

  override val description = "Check that system group / directive / rules for Rudder 4.2 are agent-specific"

  def FAIL(msg:String) = {
    BootraspLogger.logEffect.error(msg)
    throw new UnavailableException(msg)
  }

  override def checks() : Unit = {
    val (entries, paths) = {
      val bootstrapLDIFs = ("bootstrap.ldif" :: "init-policy-server.ldif" :: Nil)
      val rudderConfigBasePath = "/opt/rudder/share/"

      //we need to check for entries first in the standard rudder config path, then as fallback on classpath
      import scala.collection.JavaConverters._
      //build pair of (entries, pathFromWhereEntriesCome)
      val pairs = bootstrapLDIFs.map{ ldif =>
        val file = new File(rudderConfigBasePath, ldif)
        val (is, path) = if(file.exists && file.canRead) {
          (new FileInputStream(file), file.getAbsolutePath)
        } else {
          (this.getClass.getClassLoader.getResourceAsStream("ldap/" + ldif), "classpath:ldap/"+ldif)
        }
        try {
          (LDIFReader.readEntries(is).asScala, path)
        } catch {
          case NonFatal(ex) =>
            //not sure if we should stop Rudder start-up in that case by throwing a boot exception, but it seems likely
            FAIL(s"Error when trying to read bootstrap data from '${path}' about system configuration: ${ex.getMessage}")
        }
      }
      (pairs.map(_._1).flatten.map { e => (e.getParsedDN, e) }.toMap, pairs.map(_._2))
    }

    def failMissingEntryMsg(dn: DN, optAttr: Option[String] = None) = {
      FAIL(s"Missing required system entry in bootstrap data [${paths.mkString(";")}] (likely a bug, please report): '${dn.toString}'${optAttr.map(a => "-> " + a).getOrElse("")}")
    }

    //we must have the following entries in the ldif
    // system group for all node managed by CFEngine agent
    val cfeGroupDN = new DN("nodeGroupId=all-nodes-with-cfengine-agent,groupCategoryId=SystemGroups,groupCategoryId=GroupRoot,ou=Rudder,cn=rudder-configuration")
    val cfeGroup = LDAPEntry(entries.getOrElse(cfeGroupDN, failMissingEntryMsg(cfeGroupDN)))

    // inventory-all rule
    val cfeInventoryAllDN = new DN("ruleId=inventory-all,ou=Rules,ou=Rudder,cn=rudder-configuration")
    val cfeInventoryAll = LDAPEntry(entries.getOrElse(cfeInventoryAllDN, failMissingEntryMsg(cfeInventoryAllDN)))

    val systemGroupDN = new DN("groupCategoryId=SystemGroups,groupCategoryId=GroupRoot,ou=Rudder,cn=rudder-configuration")
    def hasPolicyServerDN(uuid: String) = new DN(new RDN(s"nodeGroupId", s"hasPolicyServer-${uuid}"), systemGroupDN)
    val hasPolicyServerTemplateEntry = entries.getOrElse(hasPolicyServerDN("root"), failMissingEntryMsg(hasPolicyServerDN("root")))
    val queryAttr = "jsonNodeGroupQuery"
    val hasPolicyServerGroupQueryTemplate = hasPolicyServerTemplateEntry.getAttribute(queryAttr) match {
      case null => failMissingEntryMsg(hasPolicyServerDN("root"), Some(queryAttr))
      case attr => attr.getValue
    }
    def hasPolicyServer(uuid: String): LDAPEntry = {
      val entry = hasPolicyServerTemplateEntry.duplicate()
      // use '"' in the replace to make clearer that we search for "root" which is the exact value we want to replace to "node-actual-uuid"
      // (but not someting-root-something for ex)
      entry.setAttribute(new Attribute("jsonNodeGroupQuery", hasPolicyServerGroupQueryTemplate.replaceAll('"'+"root"+'"', '"'+uuid+'"')))
      // also change the DN/RDN of the group to the correct one
      entry.setAttribute("nodeGroupId", s"hasPolicyServer-${uuid}")
      // Set correct name and description
      entry.setAttribute("cn", s"All classic Nodes managed by ${uuid} policy server")
      entry.setAttribute("description", s"All classic Nodes known by Rudder directly connected to the ${uuid} server. This group exists only as internal purpose and should not be used to configure nodes.")

      entry.setDN(hasPolicyServerDN(uuid))


      LDAPEntry(entry)
    }

    def updateSimpleEntries(con: RwLDAPConnection, entries: List[LDAPEntry]): IOResult[Seq[LDIFChangeRecord]] = {
      ZIO.foreach(entries) { wanted =>
        con.get(wanted.dn) foldM (
          err =>
          // try to repare in case of failure / empty
            con.save(wanted, true)
        , opt => opt match {
          case None        => con.save(wanted, true)
          case Some(entry) => compare(con, wanted, entry)
        })
      }
    }

    def updateHasPolicyServer(con: RwLDAPConnection): IOResult[Seq[LDIFChangeRecord]] = {
      val hasPolicyServerRegEx = "hasPolicyServer-(.+)".r

      for {
        entries <- con.searchOne(systemGroupDN, BuildFilter.SUB("nodeGroupId", "hasPolicyServer", null, null))
        done    <- ZIO.foreach(entries) { entry =>
                    //by search construction, we do have attribute nodeGroupId with one value at least
                    entry("nodeGroupId") match {
                      case Some(hasPolicyServerRegEx(uuid)) =>
                        val wanted = hasPolicyServer(uuid)
                        compare(con, wanted, entry)
                      case _ =>
                        BootraspLogger.logPure.warn(s"Ignoring entry '${entry.dn.toString}' when updating 'hasPolicyServer-*' system groups: name is not well formed") *>
                        LDIFNoopChangeRecord(entry.dn).succeed
                    }
        }
      } yield {
        done
      }
    }

    //actually check the interesting parts
    (for {
      con <- ldap
      // check that group hasPolicyServer-UUID are correct
      hasPolicyGroups <- updateHasPolicyServer(con)
      // check that other simple entries are correct
      simpleEntries   <- updateSimpleEntries(con, cfeGroup :: cfeInventoryAll :: Nil)
    } yield {
      hasPolicyGroups
    }).toBox match {
      case eb: EmptyBox =>
        val e = eb ?~! "Error when updating system configuration for CFEngine based agent"
        e.rootExceptionCause.foreach { ex =>
          BootraspLogger.logEffect.debug("Exception was: ", ex)
        }
        FAIL(e.messageChain)

      case Full(_) => //ok
    }
  }
}

