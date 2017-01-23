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
import scalaj.http.Http
import com.normation.rudder.domain.nodes.CompareProperties

class NodeApiService8 (
    nodeRepository : WoNodeRepository
  , nodeInfoService: NodeInfoService
  , uuidGen        : StringUuidGenerator
  , asyncRegenerate: AsyncDeploymentAgent
  , relayApiEndpoint: String
) extends Loggable {

  def updateRestNode(nodeId: NodeId, restNode: RestNode, actor : EventActor, reason : Option[String]) : Box[Node] = {

    val modId = ModificationId(uuidGen.newUuid)
    val propNames = restNode.properties.getOrElse(Nil).map( _.name ).toSet

    for {
      node           <- nodeInfoService.getNode(nodeId)
      newProperties  <- CompareProperties.updateProperties(node.properties, restNode.properties)
      updated        =  node.copy(properties = newProperties, policyMode = restNode.policyMode.getOrElse(node.policyMode))
      saved          <- if(updated == node) Full(node)
                        else nodeRepository.updateNode(updated, modId, actor, reason)
    } yield {
      if(node != updated) {
        asyncRegenerate ! AutomaticStartDeployment(ModificationId(uuidGen.newUuid), CurrentUser.getActor)
      }
      saved
    }
  }

  // buffer size for file I/O
  private[this] val pipeSize = 4096

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

  def remoteRunRequest(nodeId: NodeId, classes : List[String], keepOutput : Boolean, asynchronous : Boolean) = {
    val url = s"${relayApiEndpoint}/remote-run/nodes/${nodeId.value}"
    val params =
      ("classes"      , classes.mkString(",") ) ::
      ( "keep_output" , keepOutput.toString   ) ::
      ( "asynchronous", asynchronous.toString ) ::
      Nil

    Http(url).params( params ).postForm
  }

  def runNode(nodeId: NodeId, classes : List[String]) : Box[OutputStream => Unit] = {

    val request = remoteRunRequest(nodeId,classes,true,false)
    import net.liftweb.util.Helpers.tryo
    for {
       httpResponse <- tryo { request.execute { runResponse } }
       response <- if (httpResponse.isSuccess ) {
         Full(httpResponse.body)
       } else {
         Failure(s"An error occured when applying policy on Node '${nodeId.value}'")
       }
     } yield {

       response
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

         val request = remoteRunRequest(node.id, classes, false, true)
         val commandRun = {

           if (request.asString.isSuccess) {
             "Started"
           } else {
             s"An error occured when applying policy on Node '${node.id.value}'"
           }
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
