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
import com.normation.rudder.repository.WoNodeRepository
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.web.model.CurrentUser
import com.normation.utils.StringUuidGenerator
import net.liftweb.common._
import net.liftweb.json._
import net.liftweb.json.JsonDSL._
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.nodes.Node
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Arrays

import scalaj.http.Http
import com.normation.rudder.domain.nodes.CompareProperties
import scalaj.http.HttpOptions
import monix.eval.Task
import scalaj.http.HttpConstants

class NodeApiService8 (
    nodeRepository : WoNodeRepository
  , nodeInfoService: NodeInfoService
  , uuidGen        : StringUuidGenerator
  , asyncRegenerate: AsyncDeploymentAgent
  , relayApiEndpoint: String
) extends Loggable {

  def updateRestNode(nodeId: NodeId, restNode: RestNode, actor : EventActor, reason : Option[String]) : Box[Node] = {

    val modId = ModificationId(uuidGen.newUuid)

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
        out.write(s"Error when trying to contact internal remote-run API: ${e.getMessage}".getBytes(StandardCharsets.UTF_8))
        out.flush()
    }
  }

  def remoteRunRequest(nodeId: NodeId, classes : List[String], keepOutput : Boolean, asynchronous : Boolean) = {
    val url = s"${relayApiEndpoint}/remote-run/nodes/${nodeId.value}"
    val params =
      ( "classes"     , classes.mkString(",") ) ::
      ( "keep_output" , keepOutput.toString   ) ::
      ( "asynchronous", asynchronous.toString ) ::
      Nil
    // We currently bypass verification on certificate
    // We should add an option to allow the user to define a certificate in configuration file
    val options = HttpOptions.allowUnsafeSSL :: Nil

    Http(url).params(params).options(options).postForm
  }

  def runNode(nodeId: NodeId, classes : List[String]) : Box[OutputStream => Unit] = {
    import monix.execution.Scheduler.Implicits.global
    val request = remoteRunRequest(nodeId,classes,true,true)

    val in = new PipedInputStream(pipeSize)
    val out = new PipedOutputStream(in)

    val response = Task( request.exec{ case (status,headers,is) =>
      if (status >= 200 && status < 300) {
        runResponse(is)(out)
      } else {
        out.write((s"Error ${status} occured when contacting internal remote-run API to apply " +
                  s"classes on Node '${nodeId.value}': \n${HttpConstants.readString(is)}\n\n").getBytes)
        out.flush
      }
    })
    response.runAsync.onComplete { _ => out.close() }
    Full(runResponse(in))
  }

  def runAllNodes(classes : List[String]) : Box[JValue] = {

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

           val result = request.asString
           if (result.isSuccess) {
             "Started"
           } else {
             s"An error occured when applying policy on Node '${node.id.value}', cause is: ${result.body}"
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
