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
import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.apidata.JsonQueryObjects.JQGlobalParameter
import com.normation.rudder.apidata.JsonResponseObjects.JRGlobalParameter
import com.normation.rudder.apidata.ZioJsonExtractor
import com.normation.rudder.apidata.implicits.*
import com.normation.rudder.domain.properties.*
import com.normation.rudder.facts.nodes.ChangeContext
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.repository.RoParameterRepository
import com.normation.rudder.rest.ApiModuleProvider
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.ParameterApi as API
import com.normation.rudder.rest.implicits.*
import com.normation.rudder.services.workflows.ChangeRequestService
import com.normation.rudder.services.workflows.GlobalParamChangeRequest
import com.normation.rudder.services.workflows.GlobalParamModAction
import com.normation.rudder.services.workflows.WorkflowLevelService
import com.normation.utils.StringUuidGenerator
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import org.joda.time.DateTime
import zio.syntax.*

class ParameterApi(
    zioJsonExtractor: ZioJsonExtractor,
    service:          ParameterApiService14
) extends LiftApiModuleProvider[API] {

  def schemas: ApiModuleProvider[API] = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map {
      case API.ListParameters   => ListParameters
      case API.CreateParameter  => CreateParameter
      case API.DeleteParameter  => DeleteParameter
      case API.ParameterDetails => ParameterDetails
      case API.UpdateParameter  => UpdateParameter
    }
  }

  object ListParameters extends LiftApiModule0 {
    val schema:                                                                                                API.ListParameters.type = API.ListParameters
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse            = {
      service.listParameters().toLiftResponseList(params, schema)
    }
  }

  object ParameterDetails extends LiftApiModuleString {
    val schema: API.ParameterDetails.type = API.ParameterDetails
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      service.parameterDetails(id).toLiftResponseOne(params, schema, s => Some(s.id))
    }
  }

  object CreateParameter extends LiftApiModule0 {
    val schema:                                                                                                API.CreateParameter.type = API.CreateParameter
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse             = {
      implicit val qc: QueryContext = authzToken.qc
      (for {
        restParam <-
          zioJsonExtractor.extractGlobalParam(req).chainError(s"Could not extract a global parameter from request").toIO
        result    <- service.createParameter(restParam, params, authzToken.qc.actor)
      } yield {
        result
      }).toLiftResponseOne(params, schema, s => Some(s.id))
    }
  }

  object DeleteParameter extends LiftApiModuleString {
    val schema: API.DeleteParameter.type = API.DeleteParameter
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      service.deleteParameter(id, params, authzToken.qc.actor).toLiftResponseOne(params, schema, s => Some(s.id))
    }
  }

  object UpdateParameter extends LiftApiModuleString {
    val schema: API.UpdateParameter.type = API.UpdateParameter
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      (for {
        restParam <- zioJsonExtractor.extractGlobalParam(req).chainError(s"Could not extract parameter from request.").toIO
        result    <- service.updateParameter(restParam.copy(id = Some(id)), params, authzToken.qc.actor)
      } yield {
        result
      }).toLiftResponseOne(params, schema, s => Some(s.id))
    }
  }

}

class ParameterApiService14(
    readParameter:        RoParameterRepository,
    uuidGen:              StringUuidGenerator,
    workflowLevelService: WorkflowLevelService
) {

  private def createChangeRequest(
      diff:      ChangeRequestGlobalParameterDiff,
      parameter: GlobalParameter,
      change:    GlobalParamChangeRequest,
      params:    DefaultParams,
      actor:     EventActor
  )(implicit qc: QueryContext): IOResult[JRGlobalParameter] = {
    implicit val cc: ChangeContext =
      ChangeContext(ModificationId(uuidGen.newUuid), actor, new DateTime(), params.reason, None, qc.nodePerms)
    for {
      workflow <- workflowLevelService.getForGlobalParam(actor, change)
      cr        = ChangeRequestService.createChangeRequestFromGlobalParameter(
                    params.changeRequestName.getOrElse(s"${change.action.name} parameter '${parameter.name}' from API"),
                    params.changeRequestDescription.getOrElse(""),
                    parameter,
                    change.previousGlobalParam,
                    diff,
                    actor,
                    params.reason
                  )
      id       <- workflow.startWorkflow(cr)
    } yield {
      val optCrId = if (workflow.needExternalValidation()) Some(id) else None
      JRGlobalParameter.fromGlobalParameter(parameter, optCrId)
    }
  }

  def listParameters(): IOResult[Seq[JRGlobalParameter]] = {
    readParameter
      .getAllGlobalParameters()
      .map(_.filter(_.visibility == Visibility.Displayed).sortBy(_.name).map(JRGlobalParameter.fromGlobalParameter(_, None)))
  }

  def parameterDetails(id: String): IOResult[JRGlobalParameter] = {
    readParameter
      .getGlobalParameter(id)
      .notOptional(s"Could not find Parameter ${id}")
      .map(
        JRGlobalParameter.fromGlobalParameter(_, None)
      )
  }

  def createParameter(restParameter: JQGlobalParameter, params: DefaultParams, actor: EventActor)(implicit
      qc: QueryContext
  ): IOResult[JRGlobalParameter] = {
    import GenericProperty.*
    val baseParameter = GlobalParameter.apply("", GitVersion.DEFAULT_REV, "".toConfigValue, None, "", None, Visibility.default)
    val parameter     = restParameter.updateParameter(baseParameter)
    val diff          = AddGlobalParameterDiff(parameter)
    for {
      _   <- restParameter.id.notOptional(s"'id' is mandatory but was empty")
      cr   = GlobalParamChangeRequest(GlobalParamModAction.Create, None)
      res <- createChangeRequest(diff, parameter, cr, params, actor)
    } yield res
  }

  def updateParameter(restParameter: JQGlobalParameter, params: DefaultParams, actor: EventActor)(implicit
      qc: QueryContext
  ): IOResult[JRGlobalParameter] = {
    for {
      id          <- restParameter.id.notOptional("Parameter name is mandatory for update")
      param       <- readParameter.getGlobalParameter(id).notOptional(s"Could not find Parameter '${id}''")
      updatedParam = restParameter.updateParameter(param)
      diff         = ModifyToGlobalParameterDiff(updatedParam)
      change       = GlobalParamChangeRequest(GlobalParamModAction.Update, Some(param))
      result      <- createChangeRequest(diff, updatedParam, change, params, actor)
    } yield {
      result
    }
  }

  def deleteParameter(id: String, params: DefaultParams, actor: EventActor)(implicit
      qc: QueryContext
  ): IOResult[JRGlobalParameter] = {
    readParameter.getGlobalParameter(id).flatMap {
      case None            => // already deleted
        JRGlobalParameter.empty(id).succeed
      case Some(parameter) =>
        val diff   = DeleteGlobalParameterDiff(parameter)
        val change = GlobalParamChangeRequest(GlobalParamModAction.Delete, Some(parameter))
        createChangeRequest(diff, parameter, change, params, actor)
    }
  }

}
