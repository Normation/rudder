/*
*************************************************************************************
* Copyright 2016 Normation SAS
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

package com.normation.rudder.web.rest.node

import com.normation.eventlog.ModificationId

import com.normation.inventory.domain._
import com.normation.rudder.batch.AsyncDeploymentAgent
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.nodes.JsonSerialisation._
import com.normation.rudder.domain.nodes.NodeProperty
import com.normation.rudder.repository.WoNodeRepository
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.web.rest.RestExtractorService
import com.normation.rudder.web.rest.RestUtils._
import com.normation.rudder.web.rest.RestUtils
import com.normation.utils.StringUuidGenerator

import net.liftweb.common._
import net.liftweb.http.Req
import net.liftweb.json._
import net.liftweb.json.JsonDSL._
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.nodes.Node
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import com.zaxxer.nuprocess.NuAbstractProcessHandler
import java.nio.ByteBuffer
import java.io.PipedInputStream
import java.io.PipedOutputStream
import com.zaxxer.nuprocess.NuProcessBuilder
import java.util.Arrays
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.datasources.DataSourceRepository

class NodeApiService8 (
    nodeRepository : WoNodeRepository
  , nodeInfoService: NodeInfoService
  , uuidGen        : StringUuidGenerator
  , datasourceRepo : DataSourceRepository
  , asyncRegenerate: AsyncDeploymentAgent
) extends Loggable {

  def updateRestNode(nodeId: NodeId, restNode: RestNode, actor : EventActor, reason : Option[String]) : Box[Node] = {

    val modId = ModificationId(uuidGen.newUuid)
    val propNames = restNode.properties.getOrElse(Nil).map( _.name ).toSet

    for {
      //it is forbiden to set properties owned by datasources
      datasourcesIds <- datasourceRepo.getAllIds
      badNames       =  datasourcesIds.map( _.value).intersect(propNames)
      ok             <- if(badNames.isEmpty) {
                          Full("ok")
                        } else {
                          Failure(s"You are trying to update the following properties which are owned by data sources and can not be interactively modified: '${badNames.mkString("', '")}'")
                        }
      node           <- nodeInfoService.getNode(nodeId)
      updated        =  node.copy(properties = CompareProperties.updateProperties(node.properties, restNode.properties), policyMode = restNode.policyMode.getOrElse(node.policyMode))
      saved          <- if(updated == node) Full(node)
                        else nodeRepository.updateNode(updated, modId, actor, reason)
    } yield {
      if(node != updated) {
        asyncRegenerate ! AutomaticStartDeployment(ModificationId(uuidGen.newUuid), CurrentUser.getActor)
      }
      saved
    }
  }

  // We use ressource on the local machine, so we can have a more important pipe
  private[this] val pipeSize = 4096

  private[this] def classOption(classes : List[String]) : List[String] = {
     classes match {
        case Nil => Nil
        case classes => "-D" :: classes.mkString(",") :: Nil
      }
  }

  private[this] def runAction (node: NodeInfo) = {
    (if ( node.id.value == "root" ) "agent" else "remote") :: "run" :: (if ( node.id.value == "root" ) Nil else node.hostname :: Nil)
  }
  def runResponse(in : InputStream)(out : OutputStream) = {
    val bytes : Array[Byte] = new Array(pipeSize)
    val zero = 0.toByte
    var read = 0
    try {
      while (read != -1) {
        Arrays.fill(bytes,zero)
        read = in.read(bytes)
        out.write(bytes)
        out.flush()
      }

    } catch {
      case e : IOException =>
        // should we log ? Should we end ?
        // No need to close output or inputs
        // Output will be managed by  Lift / java servlet response
        // Input is a Piped input stream and therefore, use only in memory ressources (no network, no database ...)
        // And we already close the other side of the pipe, a case that PipedInputStream handles
    }
  }

  private[this] class RunHandler (out : Option[OutputStream]) extends NuAbstractProcessHandler {

    // set output (stdout or stderr) buffer to our stream
    // Synchronized so outputs are not mixed
    def onOut(buf: ByteBuffer, isClosed : Boolean) = {
      out match {
        case Some(out) =>
          val bytes : Array[Byte] = new Array(buf.remaining())
          buf.get(bytes)
          out.synchronized {
            out.write(bytes)
            out.flush()
          }
        case None => // Nothing to do for now
      }
    }

    // On exit will be called at the end of the process, or if any error/exception occurs, so we can close our outputstream here
    override def onExit( statusCode : Int) = {
      out.map(_.close())
    }

    override def onStdout(buf: ByteBuffer, isClosed : Boolean) = onOut(buf,isClosed)

    override def onStderr(buf: ByteBuffer, isClosed : Boolean) = onOut(buf,isClosed)
  }

  def runNode(nodeId: NodeId, classes : List[String]) : Box[OutputStream => Unit] = {

    import net.liftweb.util.Helpers.tryo
    for {
      node <- nodeInfoService.getNodeInfo(nodeId).flatMap {node => Box(node) ?~! s"Could not find node '${nodeId.value}' informations" }
      in = new PipedInputStream(pipeSize)
      out = new PipedOutputStream(in)
      _ <- try {
             import scala.collection.JavaConversions._
             val pb = new NuProcessBuilder("/usr/bin/rudder" :: runAction(node) ::: classOption(classes))
             val handler = new RunHandler(Some(out))
             pb.setProcessListener(handler)
             Full(pb.start())
           } catch {
             case e : Throwable =>
               out.close()
               Failure(s"An error occured when applying policy on Node '${node.id.value}', cause is: ${e.getMessage}")
           }
    } yield {
      runResponse(in)
    }
  }

  def runAllNodes(classes : List[String]) : Box[JValue] = {

    import net.liftweb.util.Helpers.tryo
    for {
      nodes <- nodeInfoService.getAll() ?~! s"Could not find nodes informations"

    } yield {

      val res =
      for {
        node <- nodes.values.toList
      } yield {
        {
         val commandRun = {
           JString(
             try {
               import scala.collection.JavaConversions._
               val pb = new NuProcessBuilder("/usr/bin/rudder" :: runAction(node) ::: classOption(classes))
               val handler = new RunHandler(None)
               pb.setProcessListener(handler)
               pb.start()
               "Started"
             } catch {
               case e : Throwable =>
               s"An error occured when applying policy on Node '${node.id.value}', cause is: ${e.getMessage}"
             }
           )
         }
         ( ( "id" -> node.id.value)
         ~ ( "hostname" -> node.hostname)
         ~ ( "result"   -> commandRun)
         )

        }
      }
      JArray(res)
    }
  }

}
