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

package com.normation.rudder.rest.lift

import com.normation.cfclerk.services.TechniqueRepository
import com.normation.rudder.domain.workflows.ChangeRequest
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.domain.workflows.WorkflowNodeId
import com.normation.rudder.repository.RoChangeRequestRepository
import com.normation.rudder.repository.RoWorkflowRepository
import com.normation.rudder.repository.WoChangeRequestRepository
import com.normation.rudder.repository.WoWorkflowRepository
import com.normation.rudder.rest.RestUtils._
import com.normation.rudder.rest.RestUtils.toJsonError
import com.normation.rudder.rest.ApiModuleProvider
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.ApiVersion
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.RestDataSerializer
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest.{ChangeRequestApi => API}
import com.normation.rudder.services.workflows.ChangeRequestService
import com.normation.rudder.services.workflows.CommitAndDeployChangeRequestService
import com.normation.rudder.services.workflows.WorkflowService
import com.normation.utils.Control.boxSequence
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.json.JString
import net.liftweb.json.JsonAST.JArray
import net.liftweb.json.JsonAST.JValue
import net.liftweb.json.JsonDSL._

class ChangeRequestApi (
    restExtractorService : RestExtractorService
  , readChangeRequest    : RoChangeRequestRepository
  , writeChangeRequest   : WoChangeRequestRepository
  , readWorkflow         : RoWorkflowRepository
  , writeWorkflow        : WoWorkflowRepository
  , readTechnique        : TechniqueRepository
  , changeRequestService : ChangeRequestService
  , workflowService      : WorkflowService
  , commitRepository     : CommitAndDeployChangeRequestService
  , restDataSerializer   : RestDataSerializer
  , workflowEnabled      : () => Box[Boolean]
) extends LiftApiModuleProvider[API] {

  override def schemas: ApiModuleProvider[API] = API

  def checkWorkflow = {
    if (workflowEnabled().getOrElse(false))
      Full("Ok")
    else
      Failure("workflow disabled")
  }

  def serialize(cr : ChangeRequest, status: WorkflowNodeId, version: ApiVersion) = {
    val isAcceptable = commitRepository.isMergeable(cr.id)
    restDataSerializer.serializeCR(cr, status,isAcceptable,version)
  }
  private[this] def unboxAnswer(actionName:String, id : ChangeRequestId, boxedAnwser : Box[LiftResponse]) (implicit action : String, prettify : Boolean) = {
    boxedAnwser match {
        case Full(response) => response
        case eb:EmptyBox    =>
          val fail = eb ?~!(s"Could not $actionName ChangeRequest ${id}" )
            val message=  s"Could not $actionName ChangeRequest ${id} details cause is: ${fail.messageChain}."
            toJsonError(Some(id.value.toString), message)
    }
  }

  private[this] def disabledWorkflowAnswer (crId : Option[String]) (implicit action : String, prettify : Boolean) = {
    toJsonError(crId, "Workflow are disabled in Rudder, change request API is not available")
  }

  // While there is no authorisation on API, they got all rights.
  private[this] def apiUserRights = Seq("deployer","validator")

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => e match {
        case API.ListChangeRequests  => ListChangeRequests
        case API.ChangeRequestsDetails => ChangeRequestsDetails
        case API.DeclineRequestsDetails  => DeclineRequestsDetails
        case API.AcceptRequestsDetails => AcceptRequestsDetails
        case API.UpdateRequestsDetails => UpdateRequestsDetails
    }).toList
  }

  object ListChangeRequests extends LiftApiModule0 {
    val schema = API.ListChangeRequests
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      restExtractor.extractWorkflowStatus(req.params) match {
        case Full(statuses) =>
          implicit val action = "listChangeRequests"
          implicit val prettify = restExtractor.extractPrettify(req.params)

          def listChangeRequestsByStatus(status : WorkflowNodeId)  = {

            for {
              crIds <- readWorkflow.getAllByState(status) ?~ ("Could not fetch ChangeRequests")
              crs   <- boxSequence(crIds.map(readChangeRequest.get)).map(_.flatten) ?~ ("Could not fetch ChangeRequests")
            } yield {
            val result = JArray(crs.map(serialize(_,status, version)).toList)
            Full(result)
            }
          }
          def concatenateJArray(a:JArray,b:JArray) : JArray = {
           JArray(a.arr ++ b.arr)
          }

          checkWorkflow match {
            case Full(_) =>
              (for {
                res     <- boxSequence(statuses.map(listChangeRequestsByStatus)) ?~ ("Could not fetch ChangeRequests")
                results <- boxSequence(res) ?~ ("Could not fetch ChangeRequests") ?~ ("Could not fetch ChangeRequests")
              } yield {
                val res : JValue = (results :\ JArray(List())) (concatenateJArray)
                toJsonResponse(None, res)
              }) match {
                case Full(response) =>
                  response
                case eb:EmptyBox =>
                  val fail = eb ?~ ("Could not fetch ChangeRequests")
                  toJsonError(None, fail.messageChain)
              }
            case eb:EmptyBox =>
              disabledWorkflowAnswer(None)
           }

        case eb : EmptyBox =>
          toJsonError(None, JString("No parameter 'status' sent"))("listChangeRequests",restExtractor.extractPrettify(req.params))
      }
    }
  }

  object ChangeRequestsDetails extends LiftApiModule {
    val schema = API.ChangeRequestsDetails
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, sid: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      try {
        implicit val action = "changeRequestDetails"
        implicit val prettify = restExtractor.extractPrettify(req.params)

        val id = ChangeRequestId(sid.toInt)

        checkWorkflow match {

          case Full(_) =>
            val answer = for {
              optCr         <- readChangeRequest.get(id) ?~!(s"Could not find ChangeRequest ${id}" )
              changeRequest <- optCr.map(Full(_)).getOrElse(Failure(s"Could not get ChangeRequest ${id} details cause is: change request with id ${id} does not exist."))
              status        <- readWorkflow.getStateOfChangeRequest(id) ?~!(s"Could not find ChangeRequest ${id} status" )
            } yield {
              val jsonChangeRequest = List(serialize(changeRequest, status, version))
              toJsonResponse(Some(id.value.toString),("changeRequests" -> JArray(jsonChangeRequest)))
            }
            unboxAnswer("find", id, answer)
          case eb:EmptyBox =>
            disabledWorkflowAnswer(None)
        }
      } catch {
        case e : Exception =>
          toJsonError(None, JString(s"'${sid}' is not a valid change request id (need to be an integer)"))("changeRequestDetails",restExtractor.extractPrettify(req.params))
      }
    }
  }

  object DeclineRequestsDetails extends LiftApiModule {
    val schema = API.DeclineRequestsDetails
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val action = "declineChangeRequest"
      implicit val prettify = restExtractor.extractPrettify(req.params)
      try {
        val crId = ChangeRequestId(id.toInt)
        def actualRefuse(changeRequest : ChangeRequest, step:WorkflowNodeId) = {
          val backSteps = workflowService.findBackSteps(apiUserRights, step, false)
          val optStep = backSteps.find(_._1 == WorkflowNodeId("Cancelled"))
          val answer = for {
            (_,func) <- optStep.map(Full(_)).
                      getOrElse(Failure(s"Could not decline ChangeRequest ${id} details cause is: could not decline ChangeRequest ${id}, because status '${step.value}' cannot be cancelled."))
            reason   <- restExtractor.extractReason(req)  ?~ "There was an error while extracting reason message"
            result   <- func(crId,authzToken.actor,reason) ?~!(s"Could not decline ChangeRequest ${id}" )
          } yield {
            val jsonChangeRequest = List(serialize(changeRequest,result,version))
              toJsonResponse(Some(id.toString),("changeRequests" -> JArray(jsonChangeRequest)))
          }
          unboxAnswer("decline", crId, answer)
        }

        checkWorkflow match {
          case Full(_) =>
            val answer =
              for {
                optCR <- readChangeRequest.get(crId) ?~!(s"Could not find ChangeRequest ${id}" )
                changeRequest <- optCR.map(Full(_)).getOrElse(Failure(s"Could not decline ChangeRequest ${id} details cause is: change request with id ${id} does not exist."))
                currentState  <- readWorkflow.getStateOfChangeRequest(crId)  ?~!(s"Could not find actual state of ChangeRequest ${id}" )
              } yield {
                actualRefuse(changeRequest, currentState)
              }
            unboxAnswer("decline", crId, answer)

          case eb:EmptyBox =>
            disabledWorkflowAnswer(None)
        }
      } catch {
        case e : Exception =>
          toJsonError(None, JString(s"${id} is not a valid change request id (need to be an integer)"))("declineChangeRequest",restExtractor.extractPrettify(req.params))
      }
    }
  }

  object AcceptRequestsDetails extends LiftApiModule {
    val schema = API.AcceptRequestsDetails
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val action = "acceptChangeRequest"
      implicit val prettify = restExtractor.extractPrettify(req.params)
      restExtractor.extractWorkflowTargetStatus(req.params) match {
        case Full(targetStep) =>
          try {
           val crId = ChangeRequestId(id.toInt)
            def actualAccept(changeRequest : ChangeRequest, step:WorkflowNodeId) = {
              val nextSteps = workflowService.findNextSteps(apiUserRights, step, false)
              val optStep = nextSteps.actions.find(_._1 == targetStep)
              val answer = for {
                (_,func) <- optStep.map(Full(_)).
                          getOrElse(Failure(s"Could not accept ChangeRequest ${id} details cause is: you could not send Change Request from '${step.value}' to '${targetStep.value}'."))
                reason   <- restExtractor.extractReason(req)  ?~ "There was an error while extracting reason message"
                result   <- func(crId,authzToken.actor,reason) ?~!(s"Could not accept ChangeRequest ${id}" )
              } yield {
                val jsonChangeRequest = List(serialize(changeRequest,result,version))
                  toJsonResponse(Some(id),("changeRequests" -> JArray(jsonChangeRequest)))
              }
              unboxAnswer("accept", crId, answer)
            }

            checkWorkflow match {
              case Full(_) =>
                val answer =
                  for {
                    optCR <- readChangeRequest.get(crId) ?~!(s"Could not find ChangeRequest ${id}" )
                    changeRequest <- optCR.map(Full(_)).getOrElse(Failure(s"Could not accedpt ChangeRequest ${id} details cause is: change request with id ${id} does not exist."))
                    currentState  <- readWorkflow.getStateOfChangeRequest(crId)  ?~!(s"Could not find actual state of ChangeRequest ${id}" )
                  } yield {
                    currentState.value match {
                      case "Pending validation" =>
                        actualAccept(changeRequest,currentState)
                      case "Pending deployment" =>
                        actualAccept(changeRequest,currentState)
                      case "Cancelled"  =>
                        val message=  s"Could not accept ChangeRequest ${id} details cause is: ChangeRequest ${id} has already been cancelled."
                        toJsonError(Some(id), message)
                      case "Deployed"   =>
                        val message=  s"Could not accept ChangeRequest ${id} details cause is: ChangeRequest ${id} has already been deployed."
                        toJsonError(Some(id), message)
                    }
                  }
                unboxAnswer("decline", crId, answer)
              case eb:EmptyBox =>
                disabledWorkflowAnswer(None)
            }

          } catch {
            case e : Exception =>
              toJsonError(None, JString(s"${id} is not a valid change request id (need to be an integer)"))("acceptChangeRequest",restExtractor.extractPrettify(req.params))
          }

        case eb : EmptyBox =>
          val fail = eb ?~ "Not valid 'status' parameter sent"
          val message=  s"Could not accept ChangeRequest ${id} details cause is: ${fail.messageChain}."
          toJsonError(None, JString(message))("acceptChangeRequest",restExtractor.extractPrettify(req.params))
      }
    }
  }

  object UpdateRequestsDetails extends LiftApiModule {
    val schema = API.UpdateRequestsDetails
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      restExtractor.extractChangeRequestInfo(req.params) match {
        case Full(apiInfo) =>
          implicit val action = "updateChangeRequest"
          implicit val prettify = restExtractor.extractPrettify(req.params)

          def updateInfo(changeRequest : ChangeRequest, status : WorkflowNodeId) = {
            val newInfo = apiInfo.updateCrInfo(changeRequest.info)
            if (changeRequest.info == newInfo) {
                val message=  s"Could not update ChangeRequest ${id} details cause is: No changes to save."
                toJsonError(Some(id), message)
            } else {
              val newCR = ChangeRequest.updateInfo(changeRequest, newInfo)
              writeChangeRequest.updateChangeRequest(newCR, authzToken.actor, None) match {
                case Full(cr) =>
                  val jsonChangeRequest = List(serialize(cr,status,version))
                  toJsonResponse(Some(id),("changeRequests" -> JArray(jsonChangeRequest)))
                case eb : EmptyBox =>
                  val fail = eb ?~!(s"Could not update ChangeRequest ${id}" )
                  val message=  s"Could not update ChangeRequest ${id} details cause is: ${fail.messageChain}."
                  toJsonError(Some(id), message)
              }
            }
          }

          val crId = ChangeRequestId(id.toInt)
          checkWorkflow match {
            case Full(_) =>
              val answer = for {
                optCr <- readChangeRequest.get(crId) ?~!(s"Could not find ChangeRequest ${id}" )
                changeRequest <- optCr.map(Full(_)).getOrElse(Failure(s"Could not update ChangeRequest ${id} details cause is: change request with id ${id} does not exist."))
                status <- readWorkflow.getStateOfChangeRequest(crId) ?~!(s"Could not find ChangeRequest ${id} status" )
              } yield {
                updateInfo(changeRequest,status)
              }
              unboxAnswer("update", crId, answer)
            case eb:EmptyBox =>
              disabledWorkflowAnswer(None)
          }
        case eb : EmptyBox =>
          val fail = eb ?~!(s"No parameters sent to update change request" )
          val message=  s"Could not update ChangeRequest ${id} details cause is: ${fail.messageChain}."
          toJsonError(None, JString(message))("updateChangeRequest",restExtractor.extractPrettify(req.params))
      }
    }
  }

}
