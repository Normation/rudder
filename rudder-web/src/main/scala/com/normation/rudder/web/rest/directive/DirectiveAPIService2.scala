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

case class DirectiveAPIService2 (
    readDirective        : RoDirectiveRepository
  , writeDirective       : WoDirectiveRepository
  , uuidGen              : StringUuidGenerator
  , asyncDeploymentAgent : AsyncDeploymentAgent
  , changeRequestService : ChangeRequestService
  , workflowService      : WorkflowService
  , restExtractor        : RestExtractorService
  , workflowEnabled      : Boolean
  , editorService        : DirectiveEditorService
  ) extends Loggable {


  private[this] def createChangeRequestAndAnswer (
      id              : String
    , diff            : ChangeRequestDirectiveDiff
    , technique       : Technique
    , activeTechnique : ActiveTechnique
    , directive       : Directive
    , initialtState   : Option[Directive]
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
                , initialtState
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
        val optCrId = if (workflowEnabled) Some(crId) else None
        val jsonDirective = ("directives" -> JArray(List(toJSON(technique, activeTechnique, directive, optCrId))))
        toJsonResponse(Some(id), jsonDirective)
      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not save changes on Directive ${id}" )
        val msg = s"Change request creation failed, cause is: ${fail.msg}."
        toJsonError(Some(id), msg)
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
          asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor)
          val jsonDirective = List(toJSON(technique,activeTechnique,newDirective))
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

                    restExtractor.extractTechniqueVersion(req.params,technique.id.name) match {
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
                restExtractor.extractTechnique(req.params) match {
                  case Full(technique) =>
                    readDirective.getActiveTechnique(technique.id.name) match {
                      case Full(activeTechnique) =>
                        val baseDirective = Directive(directiveId,technique.id.version,Map(),name,"")
                        actualDirectiveCreation(restDirective,baseDirective,activeTechnique,technique)
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
        val jsonDirective = List(toJSON(technique,activeTechnique,directive))
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
    def checkParameters (paramEditor : DirectiveEditor) (vars : (String,Seq[String])) = {
      try {
        val s = Seq((paramEditor.variableSpecs(vars._1).toVariable(vars._2)))
            RudderLDAPConstants.variableToSeq(s)
            Full("OK")
      }
      catch {
      case e: Exception => Failure(s"Error with one directive parameter : ${e.getMessage()}")
      }
    }

    implicit val action = "updateDirective"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val modId = ModificationId(uuidGen.newUuid)
    val actor = getActor(req)
    val directiveId = DirectiveId(id)

    //Find existing directive
    readDirective.getDirectiveWithContext(directiveId) match {
      case Full((technique,activeTechnique,directive)) =>
        restValues match {
          case Full(restDirective) =>
            // Technique Version has to be extracted separetly (it needs to know which technique we are using
            restExtractor.extractTechniqueVersion(req.params, technique.id.name) match {
              case Full(version) =>
                // Update Technique
                val updatedDirective = restDirective.copy(techniqueVersion = version).updateDirective(directive)
                // check Parameters value
                val paramCheck = for {
                  paramEditor <- editorService.get(technique.id, directiveId, updatedDirective.parameters)
                  check <- sequence (paramEditor.mapValueSeq.toSeq)( checkParameters(paramEditor))
                } yield {
                  check
                }
                paramCheck match {
                  case Full(_) =>
                    val diff = ModifyToDirectiveDiff(technique.id.name,updatedDirective,technique.rootSection)
                    createChangeRequestAndAnswer(
                        id
                      , diff
                      , technique
                      , activeTechnique
                      , updatedDirective
                      , Some(directive)
                      , actor
                      , req
                      , "Update"
                    )

                  case eb:EmptyBox =>
                    val fail = eb ?~ (s"Error with directive Parameter" )
                    val message = s"Could not update Directive ${directive.name} (id:${directiveId.value}): cause is: ${fail.msg}."
                    toJsonError(Some(directiveId.value), message)
                }
              case eb:EmptyBox =>
                val fail = eb ?~ (s"Could not find technique version" )
                val message = s"Could not update Directive ${directive.name} (id:${directiveId.value}): cause is: ${fail.msg}."
                toJsonError(Some(directiveId.value), message)
            }


          case eb : EmptyBox =>
            val fail = eb ?~ (s"Could extract values from request" )
            val message = s"Could not modify Directive ${directiveId.value} cause is: ${fail.msg}."
            toJsonError(Some(directiveId.value), message)
        }

      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not find Directive ${directiveId.value}" )
        val message = s"Could not modify Directive ${directiveId.value} cause is: ${fail.msg}."
        toJsonError(Some(directiveId.value), message)
    }

  }


  def toJSON (technique:Technique, activeTechnique:ActiveTechnique , directive : Directive, crId: Option[ChangeRequestId] = None): JValue = {

    def directiveToJSON(sv:SectionVal, sectionName:String = SectionVal.ROOT_SECTION_NAME): JValue = {
      import net.liftweb.json.JsonDSL._

      val variables = sv.variables.toSeq.sortBy(_._1).map { case (variable,value) =>
            ("var" ->
                ("name" -> variable)
              ~ ("value" -> value)
            )
      }

      val section =
          for {
            (sectionName, sectionIterations) <- sv.sections.toSeq.sortBy(_._1)
            sectionValue                     <- sectionIterations
          } yield {
            directiveToJSON(sectionValue,sectionName)
          }

      ("section" ->
          ("name" -> sectionName)
        ~ ("vars" ->  (if (variables.isEmpty) None else Some(variables)))
        ~ ("sections" -> (if (section.isEmpty) None else Some(section)))
      )
    }


    ("changeRequestId" -> crId.map(_.value.toString)) ~
    ("id" -> directive.id.value) ~
    ("displayName" -> directive.name) ~
    ("shortDescription" -> directive.shortDescription) ~
    ("longDescription" -> directive.longDescription) ~
    ("techniqueName" -> technique.id.name.value) ~
    ("techniqueVersion" -> directive.techniqueVersion.toString) ~
    ("parameters" -> directiveToJSON(SectionVal.directiveValToSectionVal(technique.rootSection, directive.parameters)) )~
    ("priority" -> directive.priority) ~
    ("isEnabled" -> directive.isEnabled ) ~
    ("isSystem" -> directive.isSystem )

  }
}