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

import com.normation.cfclerk.domain.Technique
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.batch.AsyncDeploymentAgent
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.RudderLDAPConstants
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.rudder.domain.policies.ChangeRequestDirectiveDiff
import com.normation.rudder.domain.policies.DeleteDirectiveDiff
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.ModifyToDirectiveDiff
import com.normation.rudder.domain.policies.SectionVal
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.WoDirectiveRepository
import com.normation.rudder.services.workflows.ChangeRequestService
import com.normation.rudder.services.workflows.WorkflowService
import com.normation.rudder.web.model.DirectiveEditor
import com.normation.rudder.web.rest.RestUtils
import com.normation.rudder.web.rest.RestUtils.getActor
import com.normation.rudder.web.rest.RestUtils.toJsonError
import com.normation.rudder.web.rest.RestUtils.toJsonResponse
import com.normation.rudder.web.services.DirectiveEditorService
import com.normation.rudder.web.rest.RestExtractorService
import com.normation.utils.Control._
import com.normation.utils.StringUuidGenerator
import net.liftweb.common._
import net.liftweb.http.Req
import net.liftweb.json.JArray
import net.liftweb.json.JValue
import net.liftweb.json.JsonDSL._
import com.normation.rudder.web.rest.RestDataSerializer
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.services.TechniqueRepository

case class DirectiveAPIService2 (
    readDirective        : RoDirectiveRepository
  , writeDirective       : WoDirectiveRepository
  , uuidGen              : StringUuidGenerator
  , asyncDeploymentAgent : AsyncDeploymentAgent
  , changeRequestService : ChangeRequestService
  , workflowService      : WorkflowService
  , restExtractor        : RestExtractorService
  , workflowEnabled      : () => Box[Boolean]
  , editorService        : DirectiveEditorService
  , restDataSerializer   : RestDataSerializer
  , techniqueRepository  : TechniqueRepository
  ) extends Loggable {

  def serialize(technique : Technique, directive:Directive, crId : Option[ChangeRequestId] = None) = restDataSerializer.serializeDirective(technique, directive, crId)

  private[this] def createChangeRequestAndAnswer (
      id              : String
    , diff            : ChangeRequestDirectiveDiff
    , technique       : Technique
    , activeTechnique : ActiveTechnique
    , directive       : Directive
    , initialState    : Option[Directive]
    , actor           : EventActor
    , req             : Req
    , act             : String
  ) (implicit action : String, prettify : Boolean) = {

    ( for {
        reason <- restExtractor.extractReason(req.params)
        crName <- restExtractor.extractChangeRequestName(req.params).map(_.getOrElse(s"${act} Directive ${directive.name} from API"))
        crDescription = restExtractor.extractChangeRequestDescription(req.params)
        cr <- changeRequestService.createChangeRequestFromDirective(
                  crName
                , crDescription
                , technique.id.name
                , technique.rootSection
                , directive.id
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
            val jsonDirective = ("directives" -> JArray(List(serialize(technique, directive, optCrId))))
            toJsonResponse(Some(id), jsonDirective)
          case eb : EmptyBox =>
            val fail = eb ?~ (s"Could not check workflow property" )
            val msg = s"Change request creation failed, cause is: ${fail.msg}."
            toJsonError(Some(id), msg)
        }
      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not save changes on Directive ${id}" )
        val msg = s"Change request creation failed, cause is: ${fail.msg}."
        toJsonError(Some(id), msg)
    }
  }

  def listDirectives(req : Req) = {
    implicit val action = "listDirectives"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    readDirective.getFullDirectiveLibrary match {
      case Full(fullLibrary) =>
        val atDirectives = fullLibrary.allDirectives.values.filter(!_._2.isSystem)
        val res = ( for {
          (activeTechnique, directive) <- atDirectives
          activeTechniqueId = TechniqueId(activeTechnique.techniqueName, directive.techniqueVersion)
          technique         <- Box(techniqueRepository.get(activeTechniqueId)) ?~! "No Technique with ID=%s found in reference library.".format(activeTechniqueId)
        } yield {
          serialize(technique,directive)
        } )
        toJsonResponse(None, ( "directives" -> JArray(res.toList)))
      case eb: EmptyBox =>
        val message = (eb ?~ ("Could not fetch Directives")).msg
        toJsonError(None, message)
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
      ( for {
        reason   <- restExtractor.extractReason(req.params)
        saveDiff <- writeDirective.saveDirective(activeTechnique.id, newDirective, modId, actor, reason)
      } yield {
        saveDiff
      } ) match {
        case Full(saveDiff) =>
          // We need to deploy only if there is a saveDiff, that says that a deployment is needed
          if (saveDiff.map(_.needDeployment).getOrElse(false)) {
            asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor)
          }
          val jsonDirective = List(serialize(technique,newDirective))
          toJsonResponse(Some(directiveId.value), ("directives" -> JArray(jsonDirective)))

        case eb:EmptyBox =>
          val fail = eb ?~ (s"Could not save Directive ${directiveId.value}" )
          val message = s"Could not create Directive ${newDirective.name} (id:${directiveId.value}) cause is: ${fail.msg}."
          toJsonError(Some(directiveId.value), message)
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

                    restExtractor.checkTechniqueVersion(technique.id.name, restDirective.techniqueVersion) match {
                      case Full(version) =>
                        actualDirectiveCreation(restDirective.copy(enabled = Some(false),techniqueVersion = version),sourceDirective.copy(id=directiveId),activeTechnique,technique)
                      case eb:EmptyBox =>
                        val fail = eb ?~ (s"Could not find technique version" )
                        val message = s"Could not create Directive ${name} (id:${directiveId.value}) based on Directive ${sourceId} : cause is: ${fail.msg}."
                        toJsonError(Some(directiveId.value), message)
                    }
                    // disable rest Directive if cloning
                    actualDirectiveCreation(restDirective.copy(enabled = Some(false)),sourceDirective.copy(id=directiveId),activeTechnique,technique)
                  case eb:EmptyBox =>
                    val fail = eb ?~ (s"Could not find Directive ${sourceId}" )
                    val message = s"Could not create Directive ${name} (id:${directiveId.value}) based on Directive ${sourceId} : cause is: ${fail.msg}."
                    toJsonError(Some(directiveId.value), message)
                }

              // Create a new Directive
              case None =>
                // If enable is missing in parameter consider it to true
                val defaultEnabled = restDirective.enabled.getOrElse(true)
                restExtractor.extractTechnique(restDirective.techniqueName, restDirective.techniqueVersion) match {
                  case Full(technique) =>
                    readDirective.getActiveTechnique(technique.id.name) match {
                      case Full(Some(activeTechnique)) =>
                        val baseDirective = Directive(directiveId,technique.id.version,Map(),name,"", _isEnabled = true)
                        actualDirectiveCreation(restDirective,baseDirective,activeTechnique,technique)
                      case Full(None) =>
                        toJsonError(Some(directiveId.value), "Could not create Directive because the technique was not found")

                      case eb:EmptyBox =>
                        val fail = eb ?~ (s"Could not save Directive ${directiveId.value}" )
                        val message = s"Could not create Directive cause is: ${fail.msg}."
                        toJsonError(Some(directiveId.value), message)
                    }
                  case eb:EmptyBox =>
                    val fail = eb ?~ (s"Could not save Directive ${directiveId.value}" )
                    val message = s"Could not create Directive cause is: ${fail.msg}."
                    toJsonError(Some(directiveId.value), message)
                }

              // More than one source, make an error
              case _ =>
                val message = s"Could not create Directive ${name} (id:${directiveId.value}) based on an already existing Directive, cause is : too many values for source parameter."
                toJsonError(Some(directiveId.value), message)
            }

          case None =>
            val message =  s"Could not get create a Directive details because there is no value as display name."
            toJsonError(Some(directiveId.value), message)
        }

      case eb : EmptyBox =>
        val fail = eb ?~ (s"Could extract values from request" )
        val message = s"Could not create Directive ${directiveId.value} cause is: ${fail.msg}."
        toJsonError(Some(directiveId.value), message)
    }
  }

  def directiveDetails(id:String, req:Req) = {
    implicit val action = "directiveDetails"
    implicit val prettify = restExtractor.extractPrettify(req.params)

    readDirective.getDirectiveWithContext(DirectiveId(id)) match {
      case Full((technique,activeTechnique,directive)) =>
        val jsonDirective = List(serialize(technique,directive))
        toJsonResponse(Some(id),("directives" -> JArray(jsonDirective)))
      case eb:EmptyBox =>
        val fail = eb ?~!(s"Could not find Directive ${id}" )
        val message=  s"Could not get Directive ${id} details cause is: ${fail.msg}."
        toJsonError(Some(id), message)
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
        createChangeRequestAndAnswer(
            id
          , deleteDirectiveDiff
          , technique
          , activeTechnique
          , directive
          , Some(directive)
          , actor
          , req
          , "Delete"
        )

      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not find Directive ${directiveId.value}" )
        val message = s"Could not delete Directive ${directiveId.value} cause is: ${fail.msg}."
        toJsonError(Some(directiveId.value), message)
    }
  }

  def updateDirective(id: String, req: Req, restValues : Box[RestDirective]) = {

    // A function to check if Variables passed as parameter are correct
    def checkParameters (paramEditor : DirectiveEditor) (parameterValues : (String,Seq[String])) = {
      try {
        val s = Seq((paramEditor.variableSpecs(parameterValues._1).toVariable(parameterValues._2)))
            RudderLDAPConstants.variableToSeq(s)
            Full(parameterValues)
      }
      catch {
      case e: Exception => Failure(s"Parameter '${parameterValues._1}' value is not valid, values are: ${parameterValues._2.mkString("[ ", ", ", " ]")} : ${e.getMessage()}")
      }
    }

    implicit val action = "updateDirective"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val modId = ModificationId(uuidGen.newUuid)
    val actor = getActor(req)
    val directiveId = DirectiveId(id)

    ( for {
      restDirective <- restValues ?~ (s"Could not extract values from request" )
      // Find existing directive
      (oldTechnique, activeTechnique, oldDirective) <- readDirective.getDirectiveWithContext(directiveId) ?~ (s"Could not find Directive ${directiveId.value}" )

      // Check if Technique version is changed (migration)
      updatedTechniqueId = TechniqueId(oldTechnique.id.name, restDirective.techniqueVersion.getOrElse(oldTechnique.id.version))
      updatedTechnique <- (
                            if (updatedTechniqueId.version == oldTechnique.id.version) {
                              Full(oldTechnique)
                            } else {
                              Box(techniqueRepository.get(updatedTechniqueId))
                            }
                          ) ?~ s"Could not find technique ${updatedTechniqueId.name} with version ${updatedTechniqueId.version.toString}."

       updatedDirective = restDirective.updateDirective(oldDirective)
       // Check parameters of the new Directive with the current technique version, It will check that parameters are ok with the new technique
       newParameters   <- ( for {
                              // Two step process, could be simplified
                              paramEditor <- editorService.get(updatedTechniqueId, directiveId, updatedDirective.parameters)
                              checkedParameters <- sequence (paramEditor.mapValueSeq.toSeq)( checkParameters(paramEditor))
                            } yield { checkedParameters.toMap }
                          ) ?~ (s"Error with directive Parameters" )
       // Parameters are OK, update them we have our final directive
       directiveWithNewParameters = updatedDirective.copy(parameters = newParameters)
       diff = ModifyToDirectiveDiff(updatedTechniqueId.name,directiveWithNewParameters,oldTechnique.rootSection)

    } yield {
      createChangeRequestAndAnswer(
          id
        , diff
        , updatedTechnique
        , activeTechnique
        , directiveWithNewParameters
        , Some(oldDirective)
        , actor
        , req
        , "Update"
      )
    } ) match {
      case Full(response) => response
      case eb: EmptyBox =>
        val fail = eb ?~  s" An error occured during request "
        val message = s"Could not modify Directive ${directiveId.value} cause is: ${fail.msg}."
        toJsonError(Some(directiveId.value), message)
    }
  }

}
