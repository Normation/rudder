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

package com.normation.rudder.rest.lift

import better.files.File
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.ncf.TechniqueWriter
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.ApiVersion
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest.{NcfApi => API}
import com.normation.utils.StringUuidGenerator
import net.liftweb.common.Box
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.json.JsonAST.JValue
import org.eclipse.jgit.api.Git
import com.normation.box._
import com.normation.errors.IOResult
import com.normation.rudder.ncf.ResourceFile
import com.normation.rudder.ncf.ResourceFileState
import net.liftweb.json.JsonAST.JArray
import com.normation.rudder.ncf.CheckConstraint

class NcfApi(
    techniqueWriter     : TechniqueWriter
  , restExtractorService: RestExtractorService
  , uuidGen             : StringUuidGenerator
) extends LiftApiModuleProvider[API] with Loggable{

  import com.normation.rudder.rest.RestUtils._
  val dataName = "techniques"

  def resp ( function : Box[JValue], req : Req, errorMessage : String)( action : String)(implicit dataName : String) : LiftResponse = {
    response(restExtractorService, dataName,None)(function, req, errorMessage)
  }

  def actionResp ( function : Box[ActionType], req : Req, errorMessage : String, actor: EventActor)(implicit action : String) : LiftResponse = {
    actionResponse2(restExtractorService, dataName, uuidGen, None)(function, req, errorMessage)(action, actor)
  }


  def schemas = API
  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => e match {
        case API.UpdateTechnique => UpdateTechnique
        case API.CreateTechnique => CreateTechnique
        case API.GetResources    => GetResources
        case API.ParameterCheck  => ParameterCheck
    })
  }

  object GetResources extends LiftApiModule {
    val schema = API.GetResources
    val restExtractor = restExtractorService
    implicit val dataName = "resources"
    def process(version: ApiVersion, path: ApiPath, techniqueInfo: (String,String), req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {

      val resourcesPath = s"techniques/ncf_techniques/${techniqueInfo._1}/${techniqueInfo._2}/resources"

      val resourceDir = File(s"/var/rudder/configuration-repository/${resourcesPath}")

      def getAllFiles (file : File):List[String]  = {
        if (file.exists) {
          if (file.isRegularFile) {
            logger.info(file.name)
            resourceDir.relativize(file).toString :: Nil
          } else {
            file.children.toList.flatMap(getAllFiles)
          }
        } else {
          Nil
        }
      }

      import scala.collection.JavaConverters._
      import net.liftweb.json.JsonDSL._
      import ResourceFileState._


      def toResource(fullPath : String, state : ResourceFileState) = {
        ResourceFile(fullPath.stripPrefix(s"${resourcesPath}/"), state)
      }
      def serializeResourceWithState( resource : ResourceFile) = {
          (("name" -> resource.path) ~ ("state" -> resource.state.value))
      }
      def getRessourcesStatus = {

        for {

          git      <- IOResult.effect("Error when opening configuration-repository") {
                        Git.open(File(s"/var/rudder/configuration-repository").toJava)
                      }
          status   <- IOResult.effect(s"Error when getting status of resource files of technique ${techniqueInfo._1}/${techniqueInfo._2}") {
                        git.status().addPath(resourcesPath).call()
                      }
          allFiles <- IOResult.effect(s"Error when getting all resource files of technique ${techniqueInfo._1}/${techniqueInfo._2} ") {
                        getAllFiles(resourceDir)
                      }
        } yield {

          // New files not added
          val added = status.getUntracked.asScala.toList.map(toResource(_,New))
          // Files modified and not added
          val modified = status.getModified.asScala.toList.map(toResource(_,Modified))
          // Files deleted but not removed from git
          val removed = status.getMissing.asScala.toList.map(toResource(_,Deleted))

          val filesNotCommitted = modified ::: added ::: removed

          // We want to get all files from the resource directory and remove all added/modified/deleted files so we can have the list of all files not modified
          val untouched = allFiles.filterNot(f => filesNotCommitted.exists(_.path == f)).map(ResourceFile(_, Untouched))

          // Create a new list with all a
          JArray((filesNotCommitted ::: untouched).map(serializeResourceWithState))
        }
      }

      resp(getRessourcesStatus.toBox, req, "Could not get resource state of technique")("techniqueResources")
    }
  }
  object UpdateTechnique extends LiftApiModule0 {
    val schema = API.UpdateTechnique
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val modId = ModificationId(uuidGen.newUuid)
      val response =
        for {
          json      <- req.json ?~! "No JSON data sent"
          methods   <- restExtractor.extractGenericMethod(json \ "methods")
          methodMap =  methods.map(m => (m.id,m)).toMap
          technique <- restExtractor.extractNcfTechnique(json \ "technique", methodMap, false)
          allDone   <- techniqueWriter.writeAll(technique, methodMap, modId, authzToken.actor ).toBox
        } yield {
          json
        }
      val wrapper : ActionType = {
        case _ => response
      }
      actionResp(Full(wrapper), req, "Could not update ncf technique", authzToken.actor)("UpdateTechnique")
    }
  }

  object ParameterCheck extends LiftApiModule0 {
    val schema = API.ParameterCheck
    val restExtractor = restExtractorService
    implicit val dataName = "parameterCheck"
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {

      import com.normation.rudder.ncf.Constraint._
      import net.liftweb.json.JsonDSL._
      val response =
        for {
          json                  <- req.json ?~! "No JSON data sent"
          (value,constraints)   <- restExtractor.extractParameterCheck(json)
          check                 =  CheckConstraint.check(constraints,value)
        } yield {
          check match {
            case NOK(cause) => ("result" -> false) ~ ("errors" -> cause.toList)
            case OK         => ("result" -> true) ~ ("errors" -> Nil)
          }
        }
      resp(response, req, "Could not check parameter constraint")("checkParameter")
    }
  }


  object CreateTechnique extends LiftApiModule0 {
    val schema = API.CreateTechnique
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val modId = ModificationId(uuidGen.newUuid)
      val response =
        for {
          json      <- req.json ?~! "No JSON data sent"
          methods   <- restExtractor.extractGenericMethod(json \ "methods")
          methodMap = methods.map(m => (m.id,m)).toMap
          technique <- restExtractor.extractNcfTechnique(json \ "technique", methodMap, true)
          allDone   <- techniqueWriter.writeAll(technique, methodMap, modId, authzToken.actor).toBox
        } yield {
          json
        }
      val wrapper : ActionType = {
        case _ => response
      }
      actionResp(Full(wrapper), req, "Could not create ncf technique", authzToken.actor)("CreateTechnique")
    }
  }
}
