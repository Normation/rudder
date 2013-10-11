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

package com.normation.rudder.web.rest.changeRequest

import net.liftweb.common._
import net.liftweb.http.Req
import net.liftweb.json.JArray
import net.liftweb.json.JValue
import net.liftweb.json.JsonDSL._
import net.liftweb.json.JsonAST._
import com.normation.rudder.repository._
import com.normation.rudder.services.workflows._
import com.normation.rudder.web.rest._
import com.normation.rudder.domain.workflows._
import com.normation.utils.Control.boxSequence
import com.normation.rudder.web.rest.RestUtils._
import net.liftweb.json.JsonDSL._
import com.normation.rudder.domain.policies._
import com.normation.cfclerk.domain._
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.rudder.services.modification.DiffService
import net.liftweb.http.LiftResponse

case class ChangeRequestAPIService3 (
    readChangeRequest    : RoChangeRequestRepository
  , writeChangeRequest   : WoChangeRequestRepository
  , readWorkflow         : RoWorkflowRepository
  , writeWorkflow        : WoWorkflowRepository
  , readTechnique        : TechniqueRepository
  , changeRequestService : ChangeRequestService
  , workflowService      : WorkflowService
  , commitRepository     : CommitAndDeployChangeRequestService
  , restExtractor        : RestExtractorService
  , restDataSerializer   : RestDataSerializer
  , workflowEnabled      : Boolean
  ) extends Loggable {

  
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

  def checkWorkflow = {
    if (workflowEnabled)
      Full("Ok")
    else
      Failure("workflow disabled")
  }

  def serialize(cr : ChangeRequest, status: WorkflowNodeId) = {
    val isAcceptable = commitRepository.isMergeable(cr.id)
    restDataSerializer.serializeCR(cr,status,isAcceptable)
  }
  // While there is no authorisation on API, they got all rights.
  private[this] def apiUserRights = Seq("deployer","validator")

  def listChangeRequests(req : Req, statuses: Seq[WorkflowNodeId]) : LiftResponse = {
    implicit val action = "listChangeRequests"
    implicit val prettify = restExtractor.extractPrettify(req.params)

    def listChangeRequestsByStatus(status : WorkflowNodeId)  = {
      
      for {
        crIds <- readWorkflow.getAllByState(status) ?~ ("Could not fetch ChangeRequests")
        crs   <- boxSequence(crIds.map(readChangeRequest.get)).map(_.flatten) ?~ ("Could not fetch ChangeRequests")
      } yield {
    	val result = JArray(crs.map(serialize(_,status)).toList)
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
    

  }

  def changeRequestDetails(id:ChangeRequestId, req:Req) = {
    implicit val action = "changeRequestDetails"
    implicit val prettify = restExtractor.extractPrettify(req.params)

    checkWorkflow match {

      case Full(_) =>
        val answer = for {
          optCr <- readChangeRequest.get(id) ?~!(s"Could not find ChangeRequest ${id}" )
          changeRequest <-optCr.map(Full(_)).getOrElse(Failure(s"Could not get ChangeRequest ${id} details cause is: change request with id ${id} does not exist."))
          status <- readWorkflow.getStateOfChangeRequest(id) ?~!(s"Could not find ChangeRequest ${id} status" )
        } yield {
        	val jsonChangeRequest = List(serialize(changeRequest,status))
    	    toJsonResponse(Some(id.value.toString),("changeRequests" -> JArray(jsonChangeRequest)))
        } 
        unboxAnswer("find", id, answer)
      case eb:EmptyBox =>
        disabledWorkflowAnswer(None)
    }
  }

  def declineChangeRequest(id:ChangeRequestId, req:Req) = {

    val actor = RestUtils.getActor(req)
    implicit val action = "declineChangeRequest"
    implicit val prettify = restExtractor.extractPrettify(req.params)

    def actualRefuse(changeRequest : ChangeRequest, step:WorkflowNodeId) = {
      val backSteps = workflowService.findBackSteps(apiUserRights, step, false)
      val optStep = backSteps.find(_._1 == WorkflowNodeId("Cancelled"))
      val answer = for {
        (_,func) <- optStep.map(Full(_)).
        		      getOrElse(Failure(s"Could not decline ChangeRequest ${id} details cause is: could not decline ChangeRequest ${id}, because status '${step.value}' cannot be cancelled."))
        reason   <- restExtractor.extractReason(req.params)  ?~ "There was an error while extracting reason message"
        result   <- func(id,actor,reason) ?~!(s"Could not decline ChangeRequest ${id}" )
      } yield {
    	  val jsonChangeRequest = List(serialize(changeRequest,result))
          toJsonResponse(Some(id.value.toString),("changeRequests" -> JArray(jsonChangeRequest)))
      }
      unboxAnswer("decline", id, answer)
    }
    
    checkWorkflow match {
      case Full(_) =>
        val answer =
          for {
            optCR <- readChangeRequest.get(id) ?~!(s"Could not find ChangeRequest ${id}" )
            changeRequest <- optCR.map(Full(_)).getOrElse(Failure(s"Could not decline ChangeRequest ${id} details cause is: change request with id ${id} does not exist."))
            currentState  <- readWorkflow.getStateOfChangeRequest(id)  ?~!(s"Could not find actual state of ChangeRequest ${id}" )
          } yield {
            actualRefuse(changeRequest, currentState)
          } 
        unboxAnswer("decline", id, answer) 

      case eb:EmptyBox =>
        disabledWorkflowAnswer(None)
    }
  }



  def acceptChangeRequest(id: ChangeRequestId, targetStep : WorkflowNodeId, req: Req) = {

    val actor = RestUtils.getActor(req)
    implicit val action = "acceptChangeRequest"
    implicit val prettify = restExtractor.extractPrettify(req.params)

    def actualAccept(changeRequest : ChangeRequest, step:WorkflowNodeId) = {
      val nextSteps = workflowService.findNextSteps(apiUserRights, step, false)
      val optStep = nextSteps.actions.find(_._1 == targetStep)
      val answer = for {
        (_,func) <- optStep.map(Full(_)).
        		      getOrElse(Failure(s"Could not accept ChangeRequest ${id} details cause is: you could not send Change Request from '${step.value}' to '${targetStep.value}'."))
        reason   <- restExtractor.extractReason(req.params)  ?~ "There was an error while extracting reason message"
        result   <- func(id,actor,reason) ?~!(s"Could not accept ChangeRequest ${id}" )
      } yield {
    	  val jsonChangeRequest = List(serialize(changeRequest,result))
          toJsonResponse(Some(id.value.toString),("changeRequests" -> JArray(jsonChangeRequest)))
      }
      unboxAnswer("accept", id, answer)
    }
    
    checkWorkflow match {
      case Full(_) =>
        val answer =
          for {
            optCR <- readChangeRequest.get(id) ?~!(s"Could not find ChangeRequest ${id}" )
            changeRequest <- optCR.map(Full(_)).getOrElse(Failure(s"Could not accedpt ChangeRequest ${id} details cause is: change request with id ${id} does not exist."))
            currentState  <- readWorkflow.getStateOfChangeRequest(id)  ?~!(s"Could not find actual state of ChangeRequest ${id}" )
          } yield {
            currentState.value match {
              case "Pending validation" =>
                actualAccept(changeRequest,currentState)
              case "Pending deployment" =>
                actualAccept(changeRequest,currentState)
              case "Cancelled"  =>
                val message=  s"Could not accept ChangeRequest ${id} details cause is: ChangeRequest ${id} has already been cancelled."
                toJsonError(Some(id.value.toString), message)
              case "Deployed"   =>
                val message=  s"Could not accept ChangeRequest ${id} details cause is: ChangeRequest ${id} has already been deployed."
                toJsonError(Some(id.value.toString), message)
            }
          } 
        unboxAnswer("decline", id, answer) 
      case eb:EmptyBox =>
        disabledWorkflowAnswer(None)
    }
  }

  def updateChangeRequest(id : ChangeRequestId, apiInfo : APIChangeRequestInfo, req:Req) = {
    implicit val action = "updateChangeRequest"
    val actor = RestUtils.getActor(req)
    implicit val prettify = restExtractor.extractPrettify(req.params)
    
    def updateInfo(changeRequest : ChangeRequest, status : WorkflowNodeId) = {
      val newInfo = apiInfo.updateCrInfo(changeRequest.info)
      if (changeRequest.info == newInfo) {
          val message=  s"Could not update ChangeRequest ${id} details cause is: No changes to save."
          toJsonError(Some(id.value.toString), message)
      } else {
        val newCR = ChangeRequest.updateInfo(changeRequest, newInfo)
        writeChangeRequest.updateChangeRequest(newCR, actor, None) match {
          case Full(cr) =>
            val jsonChangeRequest = List(serialize(cr,status))
            toJsonResponse(Some(id.value.toString),("changeRequests" -> JArray(jsonChangeRequest)))
          case eb : EmptyBox =>
          	val fail = eb ?~!(s"Could not update ChangeRequest ${id}" )
            val message=  s"Could not update ChangeRequest ${id} details cause is: ${fail.messageChain}."
            toJsonError(Some(id.value.toString), message)
        }
      }
    }
    
    checkWorkflow match {
      case Full(_) =>
        val answer = for {
          optCr <- readChangeRequest.get(id) ?~!(s"Could not find ChangeRequest ${id}" )
          changeRequest <- optCr.map(Full(_)).getOrElse(Failure(s"Could not update ChangeRequest ${id} details cause is: change request with id ${id} does not exist."))
          status <- readWorkflow.getStateOfChangeRequest(id) ?~!(s"Could not find ChangeRequest ${id} status" )
        } yield {
          updateInfo(changeRequest,status)
        }
        unboxAnswer("update", id, answer)
      case eb:EmptyBox =>
        disabledWorkflowAnswer(None)
    }
  }

}

case class APIChangeRequestInfo (
    name        : Option[String]
  , description : Option[String]
) {
  def updateCrInfo(crInfo : ChangeRequestInfo) : ChangeRequestInfo = {
    crInfo.copy(
        name        = name.getOrElse(crInfo.name)
      , description = description.getOrElse(crInfo.description)
    )
  }
}

