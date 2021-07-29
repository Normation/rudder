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

import com.normation.GitVersion
import com.normation.eventlog.EventActor
import com.normation.rudder.UserService
import com.normation.rudder.domain.properties._
import com.normation.rudder.repository.RoParameterRepository
import com.normation.rudder.repository.WoParameterRepository
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.ApiVersion
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.RestDataSerializer
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest.RestUtils
import com.normation.rudder.rest.RestUtils.getActor
import com.normation.rudder.rest.RestUtils.toJsonError
import com.normation.rudder.rest.RestUtils.toJsonResponse
import com.normation.rudder.rest.data.RestParameter
import com.normation.rudder.rest.{ParameterApi => API}
import com.normation.rudder.services.workflows.ChangeRequestService
import com.normation.rudder.services.workflows.GlobalParamChangeRequest
import com.normation.rudder.services.workflows.GlobalParamModAction
import com.normation.rudder.services.workflows.WorkflowLevelService
import com.normation.utils.StringUuidGenerator
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.json.JArray
import net.liftweb.json.JString
import net.liftweb.json.JsonDSL._
import com.normation.errors.IOResult
import com.normation.rudder.rest.JsonQueryObjects.JQGlobalParameter
import com.normation.rudder.rest.JsonResponseObjects.JRGlobalParameter
import com.normation.box._
import com.normation.errors._
import com.normation.rudder.domain.properties.ChangeRequestGlobalParameterDiff
import com.normation.rudder.domain.properties.GenericProperty
import zio.syntax._
import com.normation.rudder.rest._
import com.normation.rudder.rest.implicits._


class ParameterApi (
    restExtractorService: RestExtractorService
  , zioJsonExtractor    : ZioJsonExtractor
  , serviceV2           : ParameterApiService2
  , serviceV14          : ParameterApiService14
) extends LiftApiModuleProvider[API] {

  def schemas = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => e match {
      case API.ListParameters   => ChooseApi0(ListParameters  , ListParametersV14)
      case API.CreateParameter  => ChooseApi0(CreateParameter , CreateParameterV14)
      case API.DeleteParameter  => ChooseApiN(DeleteParameter , DeleteParameterV14)
      case API.ParameterDetails => ChooseApiN(ParameterDetails, ParameterDetailsV14)
      case API.UpdateParameter  => ChooseApiN(UpdateParameter , UpdateParameterV14)
    }).toList
  }


  object ListParameters extends LiftApiModule0 {
    val schema = API.ListParameters
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      serviceV2.listParameters(req)
    }
  }

  object CreateParameter extends LiftApiModule0 {
    val schema = API.CreateParameter
    val restExtractor = restExtractorService
      implicit val action = "createParameter"
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val prettify = params.prettify

      if(req.json_?) {
        req.json match {
          case Full(json) =>
            val restParameter = restExtractor.extractParameterFromJSON(json)
            restExtractor.extractParameterNameFromJSON(json) match {
              case Full(parameterName) =>
                serviceV2.createParameter(restParameter, parameterName, req)
              case eb : EmptyBox =>
                val fail = eb ?~ (s"Could extract parameter id from request" )
                val message = s"Could not create Parameter cause is: ${fail.msg}."
                toJsonError(None, message)
            }
          case eb:EmptyBox=>
            toJsonError(None, JString("No Json data sent"))
        }
      } else {
        val restParameter = restExtractor.extractParameter(req.params)
        restExtractor.extractParameterName(req.params) match {
          case Full(parameterName) =>
            serviceV2.createParameter(restParameter, parameterName, req)
          case eb : EmptyBox =>
            val fail = eb ?~ (s"Could extract parameter id from request" )
            val message = s"Could not create Parameter cause is: ${fail.msg}."
            toJsonError(None, message)
        }
      }
    }
  }


  object ParameterDetails extends LiftApiModuleString {
    val schema = API.ParameterDetails
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      serviceV2.parameterDetails(id, req)
    }
  }

  object DeleteParameter extends LiftApiModuleString {
    val schema = API.DeleteParameter
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      serviceV2.deleteParameter(id, req)
    }
  }

  object UpdateParameter extends LiftApiModuleString {
    val schema = API.UpdateParameter
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val action = "updateParameter"
      implicit val prettify = params.prettify
      if(req.json_?) {
        req.json match {
          case Full(arg) =>
            val restParameter = restExtractor.extractParameterFromJSON(arg)
            serviceV2.updateParameter(id,req,restParameter)
          case eb:EmptyBox=>
            toJsonError(None, JString("No Json data sent"))
        }
      } else {
        val restParameter = restExtractor.extractParameter(req.params)
        serviceV2.updateParameter(id,req,restParameter)
      }
    }
  }

  object ListParametersV14 extends LiftApiModule0 {
    val schema = API.ListParameters
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      serviceV14.listParameters().toLiftResponseList(params, schema)
    }
  }

  object ParameterDetailsV14 extends LiftApiModuleString {
    val schema = API.ParameterDetails
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      serviceV14.parameterDetails(id).toLiftResponseOne(params, schema, _.id)
    }
  }

  object CreateParameterV14 extends LiftApiModule0 {
    val schema = API.CreateParameter
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {

      (for {
        restParam <- zioJsonExtractor.extractGlobalParam(req).chainError(s"Could not extract a global parameter from request").toIO
        result    <- serviceV14.createParameter(restParam, params, authzToken.actor)
      } yield {
        result
      }).toLiftResponseOne(params, schema, _.id)
    }
  }



  object DeleteParameterV14 extends LiftApiModuleString {
    val schema = API.DeleteParameter
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      serviceV14.deleteParameter(id, params, authzToken.actor).toLiftResponseOne(params, schema, _.id)
    }
  }

  object UpdateParameterV14 extends LiftApiModuleString {
    val schema = API.UpdateParameter
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      (for {
        restParam <- zioJsonExtractor.extractGlobalParam(req).chainError(s"Could not extract parameter from request.").toIO
        result   <- serviceV14.updateParameter(restParam.copy(id = Some(id)), params, authzToken.actor)
      } yield {
        result
      }).toLiftResponseOne(params, schema, _.id)
    }
  }

}

class ParameterApiService2 (
    readParameter        : RoParameterRepository
  , writeParameter       : WoParameterRepository
  , uuidGen              : StringUuidGenerator
  , workflowLevelService : WorkflowLevelService
  , restExtractor        : RestExtractorService
  , restDataSerializer   : RestDataSerializer
) ( implicit userService : UserService )
extends Loggable {

  import restDataSerializer.{serializeParameter => serialize}

  private[this] def createChangeRequestAndAnswer (
      id           : String
    , diff         : ChangeRequestGlobalParameterDiff
    , parameter    : GlobalParameter
    , initialState : Option[GlobalParameter]
    , actor        : EventActor
    , req          : Req
    , act          : GlobalParamModAction
  ) (implicit action : String, prettify : Boolean) = {

    val change = GlobalParamChangeRequest(act, initialState)
    logger.info(restExtractor.extractReason(req))
    ( for {
        reason   <- restExtractor.extractReason(req)
        crName   <- restExtractor.extractChangeRequestName(req).map(_.getOrElse(s"${act} Parameter ${parameter.name} from API"))
        workflow <- workflowLevelService.getForGlobalParam(actor, change)
        cr       =  ChangeRequestService.createChangeRequestFromGlobalParameter(
                         crName
                       , restExtractor.extractChangeRequestDescription(req)
                       , parameter
                       , initialState
                       , diff
                       , actor
                       , reason
                    )
        id       <- workflow.startWorkflow(cr, actor, reason)
      } yield {
        (id, workflow)
      }
    ) match {
      case Full((crId, workflow)) =>
        val optCrId = if (workflow.needExternalValidation()) Some(crId) else None
        val jsonParameter = List(serialize(parameter,optCrId))
        toJsonResponse(Some(id), ("parameters" -> JArray(jsonParameter)))
      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not save changes on Parameter ${id}" )
        val msg = s"${act} failed, cause is: ${fail.msg}."
        toJsonError(Some(id), msg)
    }
  }

  def listParameters(req : Req) = {
    implicit val action = "listParameters"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    readParameter.getAllGlobalParameters().toBox match {
      case Full(parameters) =>
        toJsonResponse(None, ( "parameters" -> JArray(parameters.map(serialize(_,None)).toList)))
      case eb: EmptyBox =>
        val message = (eb ?~ ("Could not fetch Parameters")).msg
        toJsonError(None, message)
    }
  }

  def createParameter(restParameter: Box[RestParameter], parameterName : String, req:Req) = {
    implicit val action = "createParameter"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val actor = RestUtils.getActor(req)

    restParameter match {
      case Full(restParameter) =>
        import GenericProperty._
        val parameter = restParameter.updateParameter(GlobalParameter(parameterName, GitVersion.defaultRev,"".toConfigValue,None,"",None))

        val diff = AddGlobalParameterDiff(parameter)
        createChangeRequestAndAnswer(
            parameterName
          , diff
          , parameter
          , None
          , actor
          , req
          , GlobalParamModAction.Create
        )

      case eb : EmptyBox =>
        val fail = eb ?~ (s"Could extract values from request" )
        val message = s"Could not create Parameter ${parameterName} cause is: ${fail.msg}."
        toJsonError(Some(parameterName), message)
    }
  }

  def parameterDetails(id:String, req:Req) = {
    implicit val action = "parameterDetails"
    implicit val prettify = restExtractor.extractPrettify(req.params)

    readParameter.getGlobalParameter(id).notOptional(s"Could not find Parameter ${id}").toBox match {
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
    val actor = RestUtils.getActor(req)
    val parameterId = id

    readParameter.getGlobalParameter(parameterId).notOptional(s"Could not find Parameter ${id}").toBox match {
      case Full(parameter) =>
        val deleteParameterDiff = DeleteGlobalParameterDiff(parameter)
        createChangeRequestAndAnswer(id, deleteParameterDiff, parameter, Some(parameter), actor, req, GlobalParamModAction.Delete)

      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not find Parameter ${parameterId}" )
        val message = s"Could not delete Parameter ${parameterId} cause is: ${fail.msg}."
        toJsonError(Some(parameterId), message)
    }
  }

  def updateParameter(id: String, req: Req, restValues : Box[RestParameter]) = {
    implicit val action = "updateParameter"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val actor = getActor(req)
    val parameterId = id
    logger.info(req)
    readParameter.getGlobalParameter(parameterId).notOptional(s"Could not find Parameter ${id}").toBox match {
      case Full(parameter) =>
        restValues match {
          case Full(restParameter) =>
            val updatedParameter = restParameter.updateParameter(parameter)
            val diff = ModifyToGlobalParameterDiff(updatedParameter)
            createChangeRequestAndAnswer(id, diff, updatedParameter, Some(parameter), actor, req, GlobalParamModAction.Update)

          case eb : EmptyBox =>
            val fail = eb ?~ (s"Could extract values from request" )
            val message = s"Could not modify Parameter ${parameterId} cause is: ${fail.msg}."
            toJsonError(Some(parameterId), message)
        }

      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not find Parameter ${parameterId}" )
        val message = s"Could not modify Parameter ${parameterId} cause is: ${fail.msg}."
        toJsonError(Some(parameterId), message)
    }
  }

}

class ParameterApiService14 (
    readParameter        : RoParameterRepository
  , writeParameter       : WoParameterRepository
  , uuidGen              : StringUuidGenerator
  , workflowLevelService : WorkflowLevelService
) {

  private[this] def createChangeRequest (
      diff     : ChangeRequestGlobalParameterDiff
    , parameter: GlobalParameter
    , change   : GlobalParamChangeRequest
    , params   : DefaultParams
    , actor    : EventActor
  ): Box[JRGlobalParameter] = {
    for {
      workflow <- workflowLevelService.getForGlobalParam(actor, change)
      cr       =  ChangeRequestService.createChangeRequestFromGlobalParameter(
                       params.changeRequestName.getOrElse(s"${change.action.name} parameter '${parameter.name}' from API")
                     , params.changeRequestDescription.getOrElse("")
                     , parameter
                     , change.previousGlobalParam
                     , diff
                     , actor
                     , params.reason
                  )
      id       <- workflow.startWorkflow(cr, actor, params.reason)
    } yield {
      val optCrId = if(workflow.needExternalValidation()) Some(id) else None
      JRGlobalParameter.fromGlobalParameter(parameter, optCrId)
    }
  }

  def listParameters(): IOResult[Seq[JRGlobalParameter]] = {
    readParameter.getAllGlobalParameters().map(_.map(JRGlobalParameter.fromGlobalParameter(_, None)))
  }

  def parameterDetails(id: String): IOResult[JRGlobalParameter] = {
    readParameter.getGlobalParameter(id).notOptional(s"Could not find Parameter ${id}").map(
      JRGlobalParameter.fromGlobalParameter(_, None)
    )
  }

  def createParameter(restParameter: JQGlobalParameter, params: DefaultParams, actor: EventActor): IOResult[JRGlobalParameter] = {
    import GenericProperty._
    val baseParameter = GlobalParameter.apply("", GitVersion.defaultRev, "".toConfigValue, None,"",None)
    val parameter = restParameter.updateParameter(baseParameter)
    val diff = AddGlobalParameterDiff(parameter)
    val p = GenericProperty.patternName // pattern for parameter ID
    for {
      _    <- restParameter.id.checkMandatory(v => p.matcher(v).matches(), v => s"'id' is mandatory and must match pattern '${p.pattern()}' but was: '${v}'")
      cr   =  GlobalParamChangeRequest(GlobalParamModAction.Create, None)
      res  <- createChangeRequest(diff, parameter, cr, params, actor).toIO
    } yield res
  }

  def updateParameter(restParameter: JQGlobalParameter, params: DefaultParams, actor: EventActor): IOResult[JRGlobalParameter] = {
    for {
      id           <- restParameter.id.notOptional("Parameter name is mandatory for update")
      param        <- readParameter.getGlobalParameter(id).notOptional(s"Could not find Parameter '${id}''")
      updatedParam =  restParameter.updateParameter(param)
      diff         =  ModifyToGlobalParameterDiff(updatedParam)
      change       =  GlobalParamChangeRequest(GlobalParamModAction.Update, Some(param))
      result       <- createChangeRequest(diff, updatedParam, change, params, actor).toIO
    } yield {
      result
    }
  }

  def deleteParameter(id: String, params: DefaultParams, actor: EventActor): IOResult[JRGlobalParameter] = {
    readParameter.getGlobalParameter(id).flatMap {
      case None => //already deleted
        JRGlobalParameter.empty(id).succeed
      case Some(parameter) =>
        val diff = DeleteGlobalParameterDiff(parameter)
        val change = GlobalParamChangeRequest(GlobalParamModAction.Delete, Some(parameter))
        createChangeRequest(diff, parameter, change, params, actor).toIO
    }
  }

}
