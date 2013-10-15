/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

package com.normation.rudder.web.rest.parameter

import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.batch.AsyncDeploymentAgent
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.parameters._
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.repository.RoParameterRepository
import com.normation.rudder.repository.WoParameterRepository
import com.normation.rudder.services.workflows.ChangeRequestService
import com.normation.rudder.services.workflows.WorkflowService
import com.normation.rudder.web.rest.RestUtils
import com.normation.rudder.web.rest.RestUtils.getActor
import com.normation.rudder.web.rest.RestUtils.toJsonError
import com.normation.rudder.web.rest.RestUtils.toJsonResponse
import com.normation.rudder.web.rest.RestExtractorService
import com.normation.utils.StringUuidGenerator
import net.liftweb.common.Box
import net.liftweb.common.Box.box2Option
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import net.liftweb.http.Req
import net.liftweb.json.JArray
import net.liftweb.json.JValue
import net.liftweb.json.JsonDSL._
import net.liftweb.common.Loggable
import com.normation.rudder.web.rest.RestDataSerializer

case class ParameterApiService2 (
    readParameter        : RoParameterRepository
  , writeParameter       : WoParameterRepository
  , uuidGen              : StringUuidGenerator
  , changeRequestService : ChangeRequestService
  , workflowService      : WorkflowService
  , restExtractor        : RestExtractorService
  , workflowEnabled      : () => Box[Boolean]
  , restDataSerializer   : RestDataSerializer
  ) extends Loggable {

  import restDataSerializer.{ serializeParameter => serialize}

  private[this] def createChangeRequestAndAnswer (
      id           : String
    , diff         : ChangeRequestGlobalParameterDiff
    , parameter    : GlobalParameter
    , initialState : Option[GlobalParameter]
    , actor        : EventActor
    , req          : Req
    , act          : String
  ) (implicit action : String, prettify : Boolean) = {

    logger.info(restExtractor.extractReason(req.params))
    ( for {
        reason <- restExtractor.extractReason(req.params)
        crName <- restExtractor.extractChangeRequestName(req.params).map(_.getOrElse(s"${act} Parameter ${parameter.name} from API"))
        crDescription = restExtractor.extractChangeRequestDescription(req.params)
        cr     <- changeRequestService.createChangeRequestFromGlobalParameter(
                  crName
                , crDescription
                , parameter
                , initialState
                , diff
                , actor
                , reason
              )
        wfStarted <- workflowService.startWorkflow(cr.id, actor, None)
      } yield {
        cr.id
      }
    ) match {
      case Full(crId) =>
        workflowEnabled() match {
          case Full(enabled) =>
            val optCrId = if (enabled) Some(crId) else None
            val jsonParameter = List(serialize(parameter,optCrId))
            toJsonResponse(Some(id), ("parameters" -> JArray(jsonParameter)))
          case eb : EmptyBox =>
            val fail = eb ?~ (s"Could not check workflow property" )
            val msg = s"Change request creation failed, cause is: ${fail.msg}."
            toJsonError(Some(id), msg)
        }
      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not save changes on Parameter ${id}" )
        val msg = s"${act} failed, cause is: ${fail.msg}."
        toJsonError(Some(id), msg)
    }
  }

  def listParameters(req : Req) = {
    implicit val action = "listParameters"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    readParameter.getAllGlobalParameters match {
      case Full(parameters) =>
        toJsonResponse(None, ( "parameters" -> JArray(parameters.map(serialize(_,None)).toList)))
      case eb: EmptyBox =>
        val message = (eb ?~ ("Could not fetch Parameters")).msg
        toJsonError(None, message)
    }
  }

  def createParameter(restParameter: Box[RestParameter], parameterName : ParameterName, req:Req) = {
    implicit val action = "createParameter"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val modId = ModificationId(uuidGen.newUuid)
    val actor = RestUtils.getActor(req)

    restParameter match {
      case Full(restParameter) =>
            val parameter = restParameter.updateParameter(GlobalParameter(parameterName,"","",false))

            val diff = AddGlobalParameterDiff(parameter)
            createChangeRequestAndAnswer(
                parameterName.value
              , diff
              , parameter
              , None
              , actor
              , req
              , "Create"
            )

      case eb : EmptyBox =>
        val fail = eb ?~ (s"Could extract values from request" )
        val message = s"Could not create Parameter ${parameterName.value} cause is: ${fail.msg}."
        toJsonError(Some(parameterName.value), message)
    }
  }

  def parameterDetails(id:String, req:Req) = {
    implicit val action = "parameterDetails"
    implicit val prettify = restExtractor.extractPrettify(req.params)

    readParameter.getGlobalParameter(ParameterName(id)) match {
      case Full(parameter) =>
        val jsonParameter = List(serialize(parameter,None))
        toJsonResponse(Some(id),("parameters" -> JArray(jsonParameter)))
      case eb:EmptyBox =>
        val fail = eb ?~!(s"Could not find Parameter ${id}" )
        val message=  s"Could not get Parameter ${id} details cause is: ${fail.msg}."
        toJsonError(Some(id), message)
    }
  }

  def deleteParameter(id:String, req:Req) = {
    implicit val action = "deleteParameter"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val modId = ModificationId(uuidGen.newUuid)
    val actor = RestUtils.getActor(req)
    val parameterId = ParameterName(id)

    readParameter.getGlobalParameter(parameterId) match {
      case Full(parameter) =>
        val deleteParameterDiff = DeleteGlobalParameterDiff(parameter)
        createChangeRequestAndAnswer(id, deleteParameterDiff, parameter, Some(parameter), actor, req, "Delete")

      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not find Parameter ${parameterId.value}" )
        val message = s"Could not delete Parameter ${parameterId.value} cause is: ${fail.msg}."
        toJsonError(Some(parameterId.value), message)
    }
  }

  def updateParameter(id: String, req: Req, restValues : Box[RestParameter]) = {
    implicit val action = "updateParameter"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val modId = ModificationId(uuidGen.newUuid)
    val actor = getActor(req)
    val parameterId = ParameterName(id)
    logger.info(req)
    readParameter.getGlobalParameter(parameterId) match {
      case Full(parameter) =>
        restValues match {
          case Full(restParameter) =>
            val updatedParameter = restParameter.updateParameter(parameter)
            val diff = ModifyToGlobalParameterDiff(updatedParameter)
            createChangeRequestAndAnswer(id, diff, updatedParameter, Some(parameter), actor, req, "Update")

          case eb : EmptyBox =>
            val fail = eb ?~ (s"Could extract values from request" )
            val message = s"Could not modify Parameter ${parameterId.value} cause is: ${fail.msg}."
            toJsonError(Some(parameterId.value), message)
        }

      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not find Parameter ${parameterId.value}" )
        val message = s"Could not modify Parameter ${parameterId.value} cause is: ${fail.msg}."
        toJsonError(Some(parameterId.value), message)
    }
  }

}