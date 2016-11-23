/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

package com.normation.rudder.web.rest.directive

import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.web.rest.RestUtils.toJsonError
import com.normation.rudder.web.rest.RestExtractorService
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.http.rest.RestHelper
import net.liftweb.json.JString
import com.normation.rudder.web.rest.ApiVersion
import net.liftweb.http.NotFoundResponse
import com.normation.rudder.domain.policies.DirectiveId
import net.liftweb.json.JsonAST.JValue
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.web.rest.RestUtils
import com.normation.utils.StringUuidGenerator
import net.liftweb.common.Failure

class DirectiveAPI2 (
    readDirective : RoDirectiveRepository
  , restExtractor : RestExtractorService
  , apiV2         : DirectiveAPIService2
  , uuidGen       : StringUuidGenerator
) extends DirectiveAPI with Loggable{

  val dataName = "directives"

  def response ( function : Box[JValue], req : Req, errorMessage : String, id : Option[String])(implicit action : String) : LiftResponse = {
    RestUtils.response(restExtractor, dataName, id)(function, req, errorMessage)
  }

  type ActionType = RestUtils.ActionType
  def actionResponse ( function : Box[ActionType], req : Req, errorMessage : String, id : Option[String])(implicit action : String) : LiftResponse = {
    RestUtils.actionResponse(restExtractor, dataName, uuidGen, id)(function, req, errorMessage)
  }

  type WorkflowType = RestUtils.WorkflowType
  def workflowResponse ( function : WorkflowType, req : Req, errorMessage : String, id : Option[String], defaultName : String)(implicit action : String) : LiftResponse = {
    RestUtils.workflowResponse(restExtractor, dataName, uuidGen, id)(function, req, errorMessage, defaultName)
  }

  override def requestDispatch(apiVersion: ApiVersion) : PartialFunction[Req, () => Box[LiftResponse]] = {

    case Get(Nil, req) => {
      implicit val action = "listDirectives"
      response(apiV2.listDirectives(), req, "Could not fetch list of Directives", None)
    }

    case Get(id :: Nil, req) => {
      implicit val action = "directiveDetails"
      response(apiV2.directiveDetails(DirectiveId(id)), req, s"Could not find Directive '$id' details", Some(id))
    }

    case Put(Nil, req) => {
      implicit var action = "createDirective"
      for {
        restDirective <- restExtractor.extractDirective(req) ?~! s"Could not extract values from request."
        directiveId <- restExtractor.extractId(req)(x => Full(DirectiveId(x))).map(_.getOrElse(DirectiveId(uuidGen.newUuid)))
        optCloneId <- restExtractor.extractString("source")(req)(x => Full(DirectiveId(x)))
        result = optCloneId match {
          case None =>
            apiV2.createDirective(directiveId,restDirective)
          case Some(cloneId) =>
            action = "cloneDirective"
            apiV2.cloneDirective(directiveId, restDirective, cloneId)
        }
      } yield {
        val id = Some(directiveId.value)
        actionResponse(result, req, "Could not create Directives", id)
      }
    }

    case Delete(id :: Nil, req) => {
      implicit val action = "deleteDirective"
      for {
        result <- apiV2.deleteDirective(DirectiveId(id))
      } yield {
        workflowResponse(result,req, s"Could not delete Directive '$id'", Some(id),s"Delete Directive '${id}' from API")
      }
    }

    case Post(id:: "check" :: Nil, req) => {
      val directiveId = DirectiveId(id)
      implicit val action = "checkDirective"
      for {
        restDirective <- restExtractor.extractDirective(req) ?~! s"Could not extract values from request."
        result = apiV2.checkDirective(directiveId,restDirective)
      } yield {
        response(result, req, s"Could not check Directive '${id}' update", Some(id))
      }
    }

    case Post(id:: Nil, req) => {
      val directiveId = DirectiveId(id)
      implicit val action = "updateDirective"
      for {
        restDirective <- restExtractor.extractDirective(req) ?~! s"Could not extract values from request."
        result <- apiV2.updateDirective(directiveId,restDirective)
      } yield {
        workflowResponse(result, req, s"Could not update Directive '${id}'", Some(id), s"Update Directive '${id}' from API")
      }
    }
  }
}
