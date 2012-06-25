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

package com.normation.inventory.provisioning.fusion

import scala.xml._
import com.normation.inventory.domain._
import net.liftweb.common._
import java.security.MessageDigest
import com.normation.utils.UuidRegex

///////////// <CONTENT> /////////////

/**
* <USERSLIST>
*/
object RudderUserListParsing extends FusionReportParsingExtension {
  def processUserList(xml:Node) : Seq[String] = {
    (xml \ "USER").flatMap(e => optText(e))
  }
  override def isDefinedAt(x:(Node,InventoryReport)) = x._1.label == "USERSLIST"
  override def apply(x:(Node,InventoryReport)) : InventoryReport = {
    x._2.copy( node = x._2.node.copy( accounts = x._2.node.accounts ++ processUserList(x._1) ) )
  }
}

///////////// ROOT+1 /////////////

/**
* <UUID>
*/
object RudderNodeIdParsing extends FusionReportParsingExtension {
  override def isDefinedAt(x:(Node,InventoryReport)) = { x._1.label == "UUID" }
  override def apply(x:(Node,InventoryReport)) : InventoryReport = {
    optText(x._1) match {
      case None => x._2
      case Some(id) => x._2.copy( node = x._2.node.copyWithMain { m => m.copy(id = NodeId(id)) } )
    }
  }
}

/**
* <POLICY_SERVER>
*/
object RudderPolicyServerParsing extends FusionReportParsingExtension {
  override def isDefinedAt(x:(Node,InventoryReport)) = { x._1.label == "POLICY_SERVER" }
  override def apply(x:(Node,InventoryReport)) : InventoryReport = {
    optText(x._1) match {
      case None => x._2
      case Some(ps) => x._2.copy( node = x._2.node.copyWithMain { m => m.copy(policyServerId = NodeId(ps)) } )
    }
  }
}

/**
* <MACHINEID>
*
* Must be executed after <UUID> parsing rule.
*
* If <MACHINEID> is not present or its content is not a valid UUID,
* define the machine ID based on the md5 of node ID.
*/
object RudderMachineIdParsing extends FusionReportParsingExtension {
  private[this] def buildMachineId(report:InventoryReport) = {
    val md5 = MessageDigest.getInstance("MD5").digest(report.node.main.id.value.getBytes)
    val id = (md5.map(0xFF & _).map { "%02x".format(_) }.foldLeft(""){_ + _}).toLowerCase
    
    "%s-%s-%s-%s-%s".format(
        id.substring(0,8)
      , id.substring(8,12)
      , id.substring(12,16)
      , id.substring(16,20)
      , id.substring(20)
      )
  }
  
  override def isDefinedAt(x:(Node,InventoryReport)) = { x._1.label == "MACHINEID" }
  override def apply(x:(Node,InventoryReport)) : InventoryReport = {
    val machineId = (optText(x._1) match {
      case None => buildMachineId(x._2)
      case Some(id) => if(UuidRegex.isValid(id)) id else buildMachineId(x._2)
    })
    x._2.copy( machine = x._2.machine.copy( mbUuid = Some(MotherBoardUuid(machineId) ) ) )
  }
}

object RudderCpuParsing extends FusionReportParsingExtension with Loggable {
  override def isDefinedAt(x:(Node,InventoryReport)) = { x._1.label == "PROCESSORS" }
  override def apply(x:(Node,InventoryReport)) : InventoryReport = {
    val procs = processProcessors(x._1).map( proc => x._2.machine.processors.map(oldproc => if(oldproc.name==proc.name){oldproc.copy(stepping = proc.stepping, model = proc.model, family= proc.family)} else oldproc)).flatten
    x._2.copy( machine = x._2.machine.copy( processors = procs ) )
  }
  def processProcessors(xml:NodeSeq) : Seq[Processor] = {
    val buf = scala.collection.mutable.Buffer[Processor]()
    (xml \ "PROCESSOR").zipWithIndex.map { case (p,i) =>
      optText(p\"NAME").map { x => x } match {
        case None =>
          logger.debug("Ignoring Processor entry because NAME tag is empty")
          logger.debug(p)
        case Some(name) =>
          val cpu = Processor(
                manufacturer = optText(p\"MANUFACTURER").map(new Manufacturer(_))
              , arch = optText(p\"ARCH")
              , name = name
              , speed = optText(p\"FREQUENCY").map(_.toFloat.toInt)
              , externalClock = optText(p\"EXTERNAL_CLOCK").map(_.toFloat)
              , core = optText(p\"CORE").map(_.toInt)
              , thread = optText(p\"THREAD").map(_.toInt)
              , cpuid = optText(p\"ID")
              , stepping = optText(p\"STEPPING").map(_.toInt)
              , model = optText(p\"MODEL").map(_.toInt)
              , family = optText(p\"FAMILY").map(_.toInt)
          )
          buf += cpu
      }
    }
    buf.toSeq
  }
}

/**
* <CFKEY>
*/
class RudderPublicKeyParsing(keyNormalizer:PrintedKeyNormalizer) extends FusionReportParsingExtension {
  override def isDefinedAt(x:(Node,InventoryReport)) = { x._1.label == "CFKEY" }
  override def apply(x:(Node,InventoryReport)) : InventoryReport = {
    optText(x._1) match {
      case None => x._2
      case Some(key) => x._2.copy( node = x._2.node.copy( publicKeys = (new PublicKey(keyNormalizer(key) ) +: x._2.node.publicKeys ) ) )
    }
  }
}

/**
* <USER>
*/
object RudderRootUserParsing extends FusionReportParsingExtension {
  override def isDefinedAt(x:(Node,InventoryReport)) = { x._1.label == "USER" }
  override def apply(x:(Node,InventoryReport)) : InventoryReport = {
    optText(x._1) match {
      case None => x._2
      case Some(u) => x._2.copy( node = x._2.node.copyWithMain{ m => m.copy( rootUser = u ) } )
    }
  }
}


/**
* <AGENTSNAME>
*/
object RudderAgentNameParsing extends FusionReportParsingExtension with Loggable {
  override def isDefinedAt(x:(Node,InventoryReport)) = { x._1.label == "AGENTSNAME" }
  override def apply(x:(Node,InventoryReport)) : InventoryReport = {
    x._2.copy( node = x._2.node.copy( agentNames = x._2.node.agentNames ++ processAgentName(x._1) ) )
  }
  def processAgentName(xml:NodeSeq) : Seq[AgentType] = {
    (xml \ "AGENTNAME").flatMap(e => optText(e).flatMap( a =>
      AgentType.fromValue(a) match {
        case Full(x) => Full(x)
        case e:EmptyBox =>
          logger.error("Ignore agent type '%s': unknown value. Authorized values are %s".format(a, AgentType.allValues.mkString(", ")))
          Empty
      }
    ) )
  }
}


/**
* <HOSTNAME>
*/
object RudderHostnameParsing extends FusionReportParsingExtension {
  override def isDefinedAt(x:(Node,InventoryReport)) = { x._1.label == "HOSTNAME" }
  override def apply(x:(Node,InventoryReport)) : InventoryReport = {
    optText(x._1) match {
      case None => x._2
      case Some(e) => x._2.copy( node = x._2.node.copyWithMain { m => m.copy( hostname = e) } )
    }
  }
}




