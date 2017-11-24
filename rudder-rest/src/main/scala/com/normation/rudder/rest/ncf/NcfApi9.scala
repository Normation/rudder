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

package com.normation.rudder.rest.ncf

import net.liftweb.common.Box
import com.normation.rudder.rest.RestExtractorService
import net.liftweb.http.rest.RestHelper
import com.normation.rudder.rest.RestAPI
import net.liftweb.common.Loggable
import net.liftweb.json.JsonAST.JValue
import net.liftweb.http.Req
import net.liftweb.http.LiftResponse
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.rest.ApiVersion
import com.normation.rudder.ncf.TechniqueWriter
import net.liftweb.common.Full
import com.normation.eventlog.ModificationId
import com.normation.rudder.rest.RestUtils
import com.normation.rudder.service.user.UserService
import com.normation.rudder.AuthorizationType

class NcfApi9(
                 techniqueWriter : TechniqueWriter
  ,              restExtractor   : RestExtractorService
  ,              uuidGen         : StringUuidGenerator
  , implicit val userService     : UserService
) extends RestHelper with RestAPI with Loggable {
 val kind = "ncf"

  import com.normation.rudder.ncf.ResultHelper.resultToBox
  import com.normation.rudder.rest.RestUtils._
  val dataName = "techniques"

  def resp ( function : Box[JValue], req : Req, errorMessage : String)(implicit action : String) : LiftResponse = {
    response(restExtractor, dataName,None)(function, req, errorMessage)
  }

  def actionResp ( function : Box[ActionType], req : Req, errorMessage : String)(implicit action : String) : LiftResponse = {
    actionResponse(restExtractor, dataName, uuidGen, None)(function, req, errorMessage)
  }

  override def checkSecure : PartialFunction[Req, Boolean] = {
    case Get(_,_) =>
      userService.getCurrentUser.checkRights(AuthorizationType.Read("technique"))
    case Post(_,_) | Put(_,_) | Delete(_,_) =>
      userService.getCurrentUser.checkRights(AuthorizationType.Write("technique")) || userService.getCurrentUser.checkRights(AuthorizationType.Edit("technique"))
    case _=> false
  }

  def requestDispatch(apiVersion: ApiVersion) : PartialFunction[Req, () => Box[LiftResponse]] = {
    case Nil JsonPost body -> req => {
      val modId = ModificationId(uuidGen.newUuid)
      val actor = RestUtils.getActor(req)
      val response = for {
        json      <- req.json ?~! "No JSON data sent"
        methods   <- restExtractor.extractGenericMethod(json)
        methodMap = methods.map(m => (m.id,m)).toMap
        technique <- restExtractor.extractNcfTechnique(json \ "technique", methodMap)
        allDone   <- techniqueWriter.writeAll(technique, methodMap, modId, actor )
      } yield {
        json
      }
      val wrapper : ActionType = {
        case _ => response
      }
      actionResp(Full(wrapper), req, "Could not update ncf technique")("UpdateTechnique")
    }

    case Nil JsonPut body -> req => {
      val modId = ModificationId(uuidGen.newUuid)
      val actor = RestUtils.getActor(req)
      val response = for {
        json      <- req.json ?~! "No JSON data sent"
        methods   <- restExtractor.extractGenericMethod(json)
        methodMap = methods.map(m => (m.id,m)).toMap
        technique <- restExtractor.extractNcfTechnique(json \ "technique", methodMap)
        allDone   <- techniqueWriter.writeAll(technique, methodMap, modId, actor)
      } yield {
        json
      }
      val wrapper : ActionType = {
        case _ => response
      }
      actionResp(Full(wrapper), req, "Could not create ncf technique")("CreateTechnique")
    }
  }
}
