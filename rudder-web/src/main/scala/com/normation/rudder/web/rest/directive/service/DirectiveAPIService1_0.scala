package com.normation.rudder.web.rest.directive.service

import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.WoDirectiveRepository
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.services.workflows._
import com.normation.rudder.batch.AsyncDeploymentAgent
import com.normation.rudder.web.services.rest.RestExtractorService
import com.normation.rudder.domain.policies._
import com.normation.eventlog.EventActor
import net.liftweb.common._
import com.normation.rudder.web.rest.RestUtils._
import net.liftweb.json._
import net.liftweb.json.JsonDSL._
import com.normation.rudder.web.rest._
import net.liftweb.http.Req
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.eventlog.ModificationId
import com.normation.cfclerk.domain.Technique
import net.liftweb.common.Box.box2Iterable
import com.normation.rudder.web.rest.directive._

case class DirectiveAPIService1_0 (
    readDirective        : RoDirectiveRepository
  , writeDirective       : WoDirectiveRepository
  , uuidGen              : StringUuidGenerator
  , asyncDeploymentAgent : AsyncDeploymentAgent
  , changeRequestService : ChangeRequestService
  , workflowService      : WorkflowService
  , restExtractor        : RestExtractorService
  , workflowEnabled      : Boolean
  ) extends Loggable {


  private[this] def createChangeRequestAndAnswer (
      id              : String
    , diff            : ChangeRequestDirectiveDiff
    , technique       : Technique
    , activeTechnique : ActiveTechnique
    , directive       : Directive
    , initialtState   : Option[Directive]
    , actor           : EventActor
    , message         : String
  ) (implicit action : String, prettify : Boolean) = {
    ( for {

        cr <- changeRequestService.createChangeRequestFromDirective(
                  message
                , message
                , technique.id.name
                , technique.rootSection
                , directive.id
                , initialtState
                , diff
                , actor
                , None
              )
        wfStarted <- workflowService.startWorkflow(cr.id, actor, None)
      } yield {
        cr.id
      }
    ) match {
      case Full(x) =>
        val jsonDirective = ("directives" -> JArray(List(toJSON(technique, activeTechnique, directive))))
        toJsonResponse(id, jsonDirective, RestOk)
      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not save changes on Directive ${id}" )
        val msg = s"${message} failed, cause is: ${fail.msg}."
        toJsonResponse(id, msg, RestError)
    }
  }

  def listDirectives(req : Req) = {
    implicit val action = "listDirectives"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    readDirective.getAll(false) match {
      case Full(directives) =>
        val res = ( for {
          directive <- directives
          (technique,activeTechnique,_) <- readDirective.getDirectiveWithContext(directive.id)
        } yield {
          toJSON(technique,activeTechnique,directive)
        } )
        toJsonResponse("N/A", ( "directives" -> JArray(res.toList)))
      case eb: EmptyBox =>
        val message = (eb ?~ ("Could not fetch Directives")).msg
        toJsonResponse("N/A", message, RestError)
    }
  }

  def createDirective(restDirective: Box[RestDirective], req:Req) = {
    implicit val action = "createDirective"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val modId = ModificationId(uuidGen.newUuid)
    val actor = RestUtils.getActor(req)
    val directiveId = DirectiveId(req.param("id").getOrElse(uuidGen.newUuid))

    def actualDirectiveCreation(restDirective : RestDirective, baseDirective : Directive, activeTechnique: ActiveTechnique, technique : Technique) = {
      val newDirective = restDirective.updateDirective( baseDirective )
      writeDirective.saveDirective(activeTechnique.id, newDirective, modId, actor, None) match {
        case Full(x) =>
          asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor)
          val jsonDirective = List(toJSON(technique,activeTechnique,newDirective))
          toJsonResponse(directiveId.value, ("directives" -> JArray(jsonDirective)), RestOk)

        case eb:EmptyBox =>
          val fail = eb ?~ (s"Could not save Directive ${directiveId.value}" )
          val message = s"Could not create Directive ${newDirective.name} (id:${directiveId.value}) cause is: ${fail.msg}."
          toJsonResponse(directiveId.value, message, RestError)
      }
    }

    restDirective match {
      case Full(restDirective) =>
        restDirective.name match {
          case Some(name) =>
            req.params.get("source") match {
              // Cloning
              case Some(sourceId :: Nil) =>
                readDirective.getDirectiveWithContext(DirectiveId(sourceId)) match {
                  case Full((technique,activeTechnique,sourceDirective)) =>

                    restExtractor.extractTechniqueVersion(req.params,technique.id.name) match {
                      case Full(version) =>
                        actualDirectiveCreation(restDirective.copy(enabled = Some(false),techniqueVersion = version),sourceDirective.copy(id=directiveId),activeTechnique,technique)
                      case eb:EmptyBox =>
                        val fail = eb ?~ (s"Could not find technique version" )
                        val message = s"Could not create Directive ${name} (id:${directiveId.value}) based on Directive ${sourceId} : cause is: ${fail.msg}."
                        toJsonResponse(directiveId.value, message, RestError)
                    }
                    // disable rest Directive if cloning
                    actualDirectiveCreation(restDirective.copy(enabled = Some(false)),sourceDirective.copy(id=directiveId),activeTechnique,technique)
                  case eb:EmptyBox =>
                    val fail = eb ?~ (s"Could not find Directive ${sourceId}" )
                    val message = s"Could not create Directive ${name} (id:${directiveId.value}) based on Directive ${sourceId} : cause is: ${fail.msg}."
                    toJsonResponse(directiveId.value, message, RestError)
                }

              // Create a new Directive
              case None =>
                // If enable is missing in parameter consider it to true
                val defaultEnabled = restDirective.enabled.getOrElse(true)

                // if only the name parameter is set, consider it to be enabled
                // if not if workflow are enabled, consider it to be disabled
                // if there is no workflow, use the value used as parameter (default to true)
                // code extract :
                /*re
                 * if (restDirective.onlyName) true
                 * else if (workflowEnabled) false
                 * else defaultEnabled
                 */
                restExtractor.extractTechnique(req.params) match {
                  case Full(technique) =>
                    readDirective.getActiveTechnique(technique.id.name) match {
                      case Full(activeTechnique) =>
                        val baseDirective = Directive(directiveId,technique.id.version,Map(),name,"")
                        actualDirectiveCreation(restDirective,baseDirective,activeTechnique,technique)
                      case eb:EmptyBox =>
                        val fail = eb ?~ (s"Could not save Directive ${directiveId.value}" )
                        val message = s"Could not create Directive cause is: ${fail.msg}."
                        toJsonResponse(directiveId.value, message, RestError)
                    }
                  case eb:EmptyBox =>
                    val fail = eb ?~ (s"Could not save Directive ${directiveId.value}" )
                    val message = s"Could not create Directive cause is: ${fail.msg}."
                    toJsonResponse(directiveId.value, message, RestError)
                }


              // More than one source, make an error
              case _ =>
                val message = s"Could not create Directive ${name} (id:${directiveId.value}) based on an already existing Directive, cause is : too many values for source parameter."
                toJsonResponse(directiveId.value, message, RestError)
            }

          case None =>
            val message =  s"Could not get create a Directive details because there is no value as display name."
            toJsonResponse(directiveId.value, message, RestError)
        }

      case eb : EmptyBox =>
        val fail = eb ?~ (s"Could extract values from request" )
        val message = s"Could not create Directive ${directiveId.value} cause is: ${fail.msg}."
        toJsonResponse(directiveId.value, message, RestError)
    }
  }

  def directiveDetails(id:String, req:Req) = {
    implicit val action = "directiveDetails"
    implicit val prettify = restExtractor.extractPrettify(req.params)

    readDirective.getDirectiveWithContext(DirectiveId(id)) match {
      case Full((technique,activeTechnique,directive)) =>
        val jsonDirective = List(toJSON(technique,activeTechnique,directive))
        toJsonResponse(id,("directives" -> JArray(jsonDirective)))
      case eb:EmptyBox =>
        val fail = eb ?~!(s"Could not find Directive ${id}" )
        val message=  s"Could not get Directive ${id} details cause is: ${fail.msg}."
        toJsonResponse(id, message, RestError)
    }
  }

  def deleteDirective(id:String, req:Req) = {
    implicit val action = "deleteDirective"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val modId = ModificationId(uuidGen.newUuid)
    val actor = RestUtils.getActor(req)
    val directiveId = DirectiveId(id)

    readDirective.getDirectiveWithContext(directiveId) match {
      case Full((technique,activeTechnique,directive)) =>
        val deleteDirectiveDiff = DeleteDirectiveDiff(technique.id.name,directive)
        val message = s"Delete Directive ${directive.name} ${id} from API "
        createChangeRequestAndAnswer(id, deleteDirectiveDiff,technique, activeTechnique, directive, Some(directive), actor, message)

      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not find Directive ${directiveId.value}" )
        val message = s"Could not delete Directive ${directiveId.value} cause is: ${fail.msg}."
        toJsonResponse(directiveId.value, message, RestError)
    }
  }

  def updateDirective(id: String, req: Req, restValues : Box[RestDirective]) = {
    implicit val action = "updateDirective"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val modId = ModificationId(uuidGen.newUuid)
    val actor = getActor(req)
    val directiveId = DirectiveId(id)

    readDirective.getDirectiveWithContext(directiveId) match {
      case Full((technique,activeTechnique,directive)) =>
        restValues match {
          case Full(restDirective) =>
            restExtractor.extractTechniqueVersion(req.params, technique.id.name) match {
              case Full(version) =>
                val updatedDirective = restDirective.copy(techniqueVersion = version).updateDirective(directive)
                val diff = ModifyToDirectiveDiff(technique.id.name,updatedDirective,technique.rootSection)
                val message = s"Modify Directive ${directive.name} ${id} from API "
                createChangeRequestAndAnswer(id, diff, technique, activeTechnique, updatedDirective, Some(directive), actor, message)
              case eb:EmptyBox =>
                val fail = eb ?~ (s"Could not find technique version" )
                val message = s"Could not update Directive ${directive.name} (id:${directiveId.value}): cause is: ${fail.msg}."
                toJsonResponse(directiveId.value, message, RestError)
            }


          case eb : EmptyBox =>
            val fail = eb ?~ (s"Could extract values from request" )
            val message = s"Could not modify Directive ${directiveId.value} cause is: ${fail.msg}."
            toJsonResponse(directiveId.value, message, RestError)
        }

      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not find Directive ${directiveId.value}" )
        val message = s"Could not modify Directive ${directiveId.value} cause is: ${fail.msg}."
        toJsonResponse(directiveId.value, message, RestError)
    }

  }


  def toJSON (technique:Technique, activeTechnique:ActiveTechnique , directive : Directive): JValue = {

    ("id" -> directive.id.value) ~
    ("displayName" -> directive.name) ~
    ("shortDescription" -> directive.shortDescription) ~
    ("longDescription" -> directive.longDescription) ~
    ("techniqueName" -> technique.id.name.value) ~
    ("techniqueVersion" -> directive.techniqueVersion.toString) ~
    ("parameters" -> SectionVal.toJSON(SectionVal.directiveValToSectionVal(technique.rootSection, directive.parameters)) )~
    ("priority" -> directive.priority) ~
    ("isEnabled" -> directive.isEnabled ) ~
    ("isSystem" -> directive.isSystem )

  }
}