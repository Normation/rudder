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

import com.normation.GitVersion
import com.normation.rudder.apidata.JsonResponseObjects.JRDirective
import com.normation.rudder.apidata.RestDataSerializer
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.batch.AsyncDeploymentActor
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.RudderLDAPConstants
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.rudder.domain.policies.ChangeRequestDirectiveDiff
import com.normation.rudder.domain.policies.DeleteDirectiveDiff
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.ModifyToDirectiveDiff
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.WoDirectiveRepository
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest.RestUtils
import com.normation.rudder.rest.data._
import com.normation.rudder.rest.{DirectiveApi => API}
import com.normation.rudder.services.workflows.ChangeRequestService
import com.normation.rudder.services.workflows.DGModAction
import com.normation.rudder.services.workflows.DirectiveChangeRequest
import com.normation.rudder.services.workflows.WorkflowLevelService
import com.normation.rudder.web.model.DirectiveEditor
import com.normation.rudder.web.services.DirectiveEditorService
import com.normation.utils.Control._
import com.normation.utils.StringUuidGenerator
import net.liftweb.common._
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.json.JArray
import net.liftweb.json.JsonAST.JValue
import com.normation.box._
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.errors._
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.configuration.ConfigurationRepository
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.repository.FullActiveTechniqueCategory
import zio._
import zio.syntax._
import com.normation.rudder.rest._
import com.normation.rudder.apidata.JsonQueryObjects._
import com.normation.rudder.apidata.JsonResponseObjects._
import com.normation.rudder.apidata.ZioJsonExtractor
import com.normation.rudder.apidata.implicits._
import com.normation.rudder.rest.implicits._
import com.softwaremill.quicklens._


class DirectiveApi (
    readDirective       : RoDirectiveRepository
  , restExtractorService: RestExtractorService
  , zioJsonExtractor    : ZioJsonExtractor
  , uuidGen             : StringUuidGenerator
  , serviceV2           : DirectiveApiService2
  , serviceV14          : DirectiveApiService14
) extends LiftApiModuleProvider[API] {

  private val dataName = "directives"

  def schemas = API

  def response ( function : Box[JValue], req : Req, errorMessage : String, id : Option[String])(implicit action : String) : LiftResponse = {
    RestUtils.response(restExtractorService, dataName, id)(function, req, errorMessage)
  }

  type ActionType = RestUtils.ActionType
  def actionResponse ( function : Box[ActionType], req : Req, errorMessage : String, id : Option[String], actor: EventActor)(implicit action : String) : LiftResponse = {
    RestUtils.actionResponse2(restExtractorService, dataName, uuidGen, id)(function, req, errorMessage)(action, actor)
  }

  type WorkflowType = RestUtils.WorkflowType
  def workflowResponse ( function : Box[WorkflowType], req : Req, errorMessage : String, id : Option[String], defaultName : String, actor: EventActor)(implicit action : String) : LiftResponse = {
    RestUtils.workflowResponse2(restExtractorService, dataName, uuidGen, id)(function, req, errorMessage, defaultName)(action, actor)
  }


  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => e match {
        case API.DirectiveTree      => DirectiveTree
        case API.ListDirectives     => ChooseApi0(ListDirective    , ListDirectiveV14   )
        case API.DirectiveDetails   => ChooseApiN(DirectiveDetails , DirectiveDetailsV14)
        case API.DirectiveRevisions => DirectiveRevisionsV14
        case API.CreateDirective    => ChooseApi0(CreateDirective  , CreateDirectiveV14 )
        case API.UpdateDirective    => ChooseApiN(UpdateDirective  , UpdateDirectiveV14 )
        case API.DeleteDirective    => ChooseApiN(DeleteDirective  , DeleteDirectiveV14 )
        case API.CheckDirective     => ChooseApiN(CheckDirective   , CheckDirectiveV14  )
    }).toList
  }

  object ListDirective extends LiftApiModule0 {
    val schema = API.ListDirectives
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val action = "listDirectives"
      response(serviceV2.listDirectives(), req, "Could not fetch list of Directives", None)
    }
  }

  object DirectiveDetails extends LiftApiModuleString {
    val schema = API.DirectiveDetails
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val action = "directiveDetails"
      response(serviceV2.directiveDetails(DirectiveUid(id)), req, s"Could not find Directive '$id' details", Some(id))
    }
  }

  object CreateDirective extends LiftApiModule0 {
    val schema = API.CreateDirective
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      var action = "createDirective"
      val id = restExtractor.extractId(req)(x => Full(DirectiveUid(x))).map(_.getOrElse(DirectiveUid(uuidGen.newUuid)))
      val response = for {
        restDirective <- restExtractor.extractDirective(req) ?~! s"Could not extract values from request"
        directiveId <- id
        optCloneId <- restExtractor.extractString("source")(req)(x => Full(DirectiveUid(x)))
        result <- optCloneId match {
          case None =>
            serviceV2.createDirective(directiveId,restDirective)
          case Some(cloneId) =>
            action = "cloneDirective"
            serviceV2.cloneDirective(directiveId, restDirective, cloneId)
        }
      } yield {
        result
      }

      actionResponse(response, req, "Could not create Directive", id.map(_.value), authzToken.actor)(action)
    }
  }


  object DeleteDirective extends LiftApiModuleString {
    val schema = API.DeleteDirective
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val action = "deleteDirective"
      workflowResponse(serviceV2.deleteDirective(DirectiveUid(id)),req, s"Could not delete Directive '$id'", Some(id),s"Delete Directive '${id}' from API", authzToken.actor)
    }
  }

  object CheckDirective extends LiftApiModuleString {
    val schema = API.CheckDirective
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val directiveId = DirectiveUid(id)
      implicit val action = "checkDirective"
      val result = for {
        restDirective <- restExtractor.extractDirective(req) ?~! s"Could not extract values from request."
        result        <- serviceV2.checkDirective(directiveId,restDirective)
      } yield {
        result
      }
      response(result, req, s"Could not check Directive '${id}' update", Some(id))
    }
  }

  object UpdateDirective extends LiftApiModuleString {
    val schema = API.UpdateDirective
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val directiveId = DirectiveUid(id)
      implicit val action = "updateDirective"
      val response = for {
        restDirective <- restExtractor.extractDirective(req) ?~! s"Could not extract values from request."
        result <- serviceV2.updateDirective(directiveId,restDirective)
      } yield {
        result
      }

      workflowResponse(response, req, s"Could not update Directive '${id}'", Some(id), s"Update Directive '${id}' from API", authzToken.actor)
    }
  }

  object ListDirectiveV14 extends LiftApiModule0 {
    val schema = API.ListDirectives
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken) = {
      serviceV14.listDirectives().toLiftResponseList(params, schema)
    }
  }
  object DirectiveTree extends LiftApiModule0 {
    val schema = API.DirectiveTree
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      (for {
        includeSystem <- restExtractorService.extractBoolean("includeSystem")(req)(identity).toIO
        res <- serviceV14.directiveTree(includeSystem.getOrElse(false))
      } yield {
        res
      }).toLiftResponseOne(params, schema, _ => None)
    }
  }

  object DirectiveDetailsV14 extends LiftApiModuleString {
    val schema = API.DirectiveDetails
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      (for {
        did <- DirectiveId.parse(id).toIO
        res <- serviceV14.directiveDetails(did)
      } yield res).toLiftResponseOne(params, schema, d => Some(d.id))
    }
  }

  object DirectiveRevisionsV14 extends LiftApiModuleString {
    val schema = API.DirectiveRevisions
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      serviceV14.directiveRevisions(DirectiveUid(id)).toLiftResponseList(params, schema)
    }
  }


  object CreateDirectiveV14 extends LiftApiModule0 {
    val schema = API.CreateDirective
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {

      (for {
        restDirective <- zioJsonExtractor.extractDirective(req).chainError(s"Could not extract directive parameters from request").toIO
        result        <- serviceV14.createOrCloneDirective(
                             restDirective
                           , restDirective.id.map(_.uid).getOrElse(DirectiveUid(uuidGen.newUuid))
                           , restDirective.source
                           , params
                           , authzToken.actor
                         )
      } yield {
        val action = if (restDirective.source.nonEmpty) "cloneDirective" else schema.name
        (RudderJsonResponse.ResponseSchema(action, schema.dataContainer), result)
      }).toLiftResponseOneMap(params, RudderJsonResponse.ResponseSchema.fromSchema(schema), x => (x._1, x._2, Some(x._2.id) ))

    }
  }

  object UpdateDirectiveV14 extends LiftApiModuleString {
    val schema = API.UpdateDirective
    def process(version: ApiVersion, path: ApiPath, sid: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      (for {
        id            <- DirectiveId.parse(sid).toIO
        restDirective <- zioJsonExtractor.extractDirective(req).chainError(s"Could not extract a directive from request.").toIO
        result        <- serviceV14.updateDirective(restDirective.copy(id = Some(id)), params, authzToken.actor)
      } yield {
        result
      }).toLiftResponseOne(params, schema, s => Some(s.id))
    }
  }

  object DeleteDirectiveV14 extends LiftApiModuleString {
    val schema = API.DeleteDirective
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      serviceV14.deleteDirective(DirectiveUid(id), params, authzToken.actor).toLiftResponseOne(params, schema, s => Some(s.id))
    }
  }

  object CheckDirectiveV14 extends LiftApiModuleString {
    val schema = API.CheckDirective
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val directiveId = DirectiveUid(id)
      (for {
        restDirective <- zioJsonExtractor.extractDirective(req).chainError(s"Could not extract values from request.").toIO
        result        <- serviceV14.checkDirective(directiveId, restDirective)
      } yield {
        result
      }).toLiftResponseOne(params, schema, s => Some(s.id))
    }
  }
}


// utility methods used in both service
object DirectiveApiService {
  def extractTechnique(techniqueRepository: TechniqueRepository, optTechniqueName: Option[TechniqueName], opTechniqueVersion: Option[TechniqueVersion]): PureResult[Technique] = {
    (optTechniqueName, opTechniqueVersion) match {
      case (None               , _            ) =>
        Left(Inconsistency("techniqueName should not be empty"))
      case (Some(techniqueName), None         ) =>
          techniqueRepository.getLastTechniqueByName(techniqueName).notOptionalPure( s"Error while fetching last version of technique ${techniqueName}")
      case (Some(techniqueName), Some(version)) =>
        techniqueRepository.get(TechniqueId(techniqueName, version)) match {
          case Some(technique) => Right(technique)
          case None => Left(Inconsistency(s" Technique ${techniqueName} version ${version} is not a valid Technique"))
        }
    }
  }

}

class DirectiveApiService2 (
    readDirective        : RoDirectiveRepository
  , writeDirective       : WoDirectiveRepository
  , uuidGen              : StringUuidGenerator
  , asyncDeploymentAgent : AsyncDeploymentActor
  , workflowLevelService : WorkflowLevelService
  , restExtractor        : RestExtractorService
  , editorService        : DirectiveEditorService
  , restDataSerializer   : RestDataSerializer
  , techniqueRepository  : TechniqueRepository
  ) extends Loggable {

  def serialize = restDataSerializer.serializeDirective _

  private[this] def createChangeRequestAndAnswer (
      diff            : ChangeRequestDirectiveDiff
    , technique       : Technique
    , activeTechnique : ActiveTechnique
    , directive       : Directive
    , initialState    : Option[Directive]
    , action          : DGModAction
  ) (actor : EventActor, reason : Option[String], crName : String, crDescription : String) : Box[JValue] = {
    val change = DirectiveChangeRequest(action, technique.id.name, activeTechnique.id, technique.rootSection, directive, initialState, Nil, Nil)

    for {
      workflow  <- workflowLevelService.getForDirective(actor, change)
      cr        =  ChangeRequestService.createChangeRequestFromDirective(
                       crName
                     , crDescription
                     , technique.id.name
                     , Some(technique.rootSection)
                     , directive.id
                     , initialState
                     , diff
                     , actor
                     , reason
                  )
      id        <- workflow.startWorkflow(cr, actor, None) ?~! s"Could not start workflow for change request creation on Directive '${directive.name}'"
        optCrId = if (workflow.needExternalValidation()) Some(id) else None
        jsonDirective = JArray(List(serialize(technique, directive, optCrId)))
      } yield {
        jsonDirective
      }
  }

  def listDirectives() : Box[JValue] = {
    for {
      fullLibrary <- readDirective.getFullDirectiveLibrary().toBox ?~! "Could not fetch Directives"
      atDirectives = fullLibrary.allDirectives.values.filter(!_._2.isSystem)
      serializedDirectives = ( for {
          (activeTechnique, directive) <- atDirectives
          activeTechniqueId = TechniqueId(activeTechnique.techniqueName, directive.techniqueVersion)
          technique         <- Box(techniqueRepository.get(activeTechniqueId)) ?~! "No Technique with ID=%s found in reference library.".format(activeTechniqueId)
        } yield {
          serialize(technique,directive,None)
        } )
    } yield {
      JArray(serializedDirectives.toList)
    }
  }

  private[this] def actualDirectiveCreation (restDirective : RestDirective, baseDirective : Directive, activeTechnique: ActiveTechnique, technique : Technique)
  ( actor : EventActor, modId: ModificationId, reason : Option[String]) : Box[JValue] = {
    val newDirective = restDirective.updateDirective( baseDirective )
    val modId = ModificationId(uuidGen.newUuid)
    for {
      // Check if a directive exists with the current id
      _ <- readDirective.getDirective(newDirective.id.uid).toBox match {
        case Full(None) =>
          Full("ok")
        case Full(Some(_)) =>
          Failure(s"Cannot create a new Directive with id '${newDirective.id.uid.value}' already exists")
        case eb : EmptyBox =>
          val fail = eb ?~! s"Error when checking existence of directive with id ${newDirective.id.uid.value}, before trying to create it"
          fail
      }

       // Check parameters of the new Directive
       _   <- ( for {
                  // Two step process, could be simplified
                  paramEditor <- editorService.get(technique.id, newDirective.id.uid, newDirective.parameters)
                  checkedParameters <- sequence (paramEditor.mapValueSeq.toSeq)( checkParameters(paramEditor))
                } yield { checkedParameters.toMap }
              ) ?~ (s"Error with directive Parameters" )

      saveDiff <- writeDirective.saveDirective(activeTechnique.id, newDirective, modId, actor, reason).toBox  ?~! (s"Could not save Directive ${newDirective.id.uid.value}" )
    } yield {
      // We need to deploy only if there is a saveDiff, that says that a deployment is needed
      if (saveDiff.map(_.needDeployment).getOrElse(false)) {
        asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor)
      }
      val jsonDirective = List(serialize(technique,newDirective, None))
      JArray(jsonDirective)
    }
  }

  def cloneDirective(directiveId: DirectiveUid, restDirective: RestDirective, source: DirectiveUid) : Box[( EventActor,ModificationId,  Option[String]) => Box[JValue]] = {
    def checkTechniqueVersion (techniqueRepository: TechniqueRepository, techniqueName: TechniqueName, techniqueVersion: Option[TechniqueVersion]): PureResult[Option[TechniqueVersion]]  = {
      techniqueVersion match {
        case Some(version) =>
          techniqueRepository.getTechniqueVersions(techniqueName).find(_ == version) match {
            case Some(version) => Right(Some(version))
            case None => Left(Inconsistency(s" version ${version} of Technique ${techniqueName}  is not valid"))
          }
        case None => Right(None)
      }
    }
    for {
      name                                        <- Box(restDirective.name) ?~! s"Directive name is not defined in request data."
      (technique,activeTechnique,sourceDirective) <- readDirective.getDirectiveWithContext(source).notOptional(s"Cannot find Directive ${source.value} to use as clone base.").toBox
      version                                     <- checkTechniqueVersion(techniqueRepository, technique.id.name, restDirective.techniqueVersion).chainError(s"Cannot find a valid technique version" ).toBox
      newDirective                                =  restDirective.copy(enabled = Some(false),techniqueVersion = version)
      baseDirective                               =  sourceDirective.modify(_.id.uid).setTo(directiveId)
    } yield {
      actualDirectiveCreation(newDirective,baseDirective,activeTechnique,technique) _
    }
  }

  def createDirective(directiveId: DirectiveUid, restDirective: RestDirective) : Box[( EventActor,ModificationId,  Option[String]) => Box[JValue]] = {
    for {
      name            <- Box(restDirective.name) ?~! s"Directive name is not defined in request data."
      technique       <- DirectiveApiService.extractTechnique(techniqueRepository, restDirective.techniqueName, restDirective.techniqueVersion).chainError(
                           s"Technique is not correctly defined in request data.").toBox
      activeTechnique <- readDirective.getActiveTechnique(technique.id.name).notOptional(s"Technique ${technique.id.serialize} cannot be found.").toBox
      baseDirective   =  Directive(DirectiveId(directiveId, GitVersion.DEFAULT_REV), technique.id.version,Map(),name,"",None, _isEnabled = true)
      result          =  actualDirectiveCreation(restDirective,baseDirective,activeTechnique,technique) _
    } yield {
      result
    }
  }

  def directiveDetails(id : DirectiveUid) : Box[JValue] = {
    for {
      (technique,activeTechnique,directive) <- readDirective.getDirectiveWithContext(id).notOptional(s"Could not find Directive ${id.value}").toBox
    } yield {
      JArray(List(serialize(technique,directive, None)))
    }
  }

  def deleteDirective(id:DirectiveUid) = {
    for {
      (technique,activeTechnique,directive) <- readDirective.getDirectiveWithContext(id).notOptional(s"Could not find Directive ${id.value}").toBox
      deleteDirectiveDiff = DeleteDirectiveDiff(technique.id.name, directive)
      result = createChangeRequestAndAnswer(
            deleteDirectiveDiff
          , technique
          , activeTechnique
          , directive
          , Some(directive)
          , DGModAction.Delete
        ) _
    } yield {
      result
    }
  }

  // A function to check if Variables passed as parameter are correct
  private[this] def checkParameters (paramEditor : DirectiveEditor) (parameterValues : (String,Seq[String])) = {
    try {
      val s = Seq((paramEditor.variableSpecs(parameterValues._1).toVariable(parameterValues._2)))
          RudderLDAPConstants.variableToSeq(s)
          Full(parameterValues)
    } catch {
      case e: Exception => Failure(s"Parameter '${parameterValues._1}' value is not valid, values are: ${parameterValues._2.mkString("[ ", ", ", " ]")} : ${e.getMessage()}")
    }
  }

  private[this] def updateDirectiveModel(directiveId: DirectiveUid, restDirective: RestDirective) = {
    for {
     (oldTechnique, activeTechnique, oldDirective) <- readDirective.getDirectiveWithContext(directiveId).notOptional(s"Could not find Directive ${directiveId.value}").toBox

      // Check if Technique version is changed (migration)
      updatedTechniqueId = TechniqueId(oldTechnique.id.name, restDirective.techniqueVersion.getOrElse(oldTechnique.id.version))
      updatedTechnique <- (
                            if (updatedTechniqueId.version == oldTechnique.id.version) {
                              Full(oldTechnique)
                            } else {
                              Box(techniqueRepository.get(updatedTechniqueId))
                            }
                          ) ?~ s"Could not find technique ${updatedTechniqueId.name.value} with version ${updatedTechniqueId.version.serialize}."

       updatedDirective = restDirective.updateDirective(oldDirective)
       // Check parameters of the new Directive with the current technique version, It will check that parameters are ok with the new technique
       newParameters   <- ( for {
                              // Two step process, could be simplified
                              paramEditor <- editorService.get(updatedTechniqueId, directiveId, updatedDirective.parameters)
                              checkedParameters <- sequence (paramEditor.mapValueSeq.toSeq)( checkParameters(paramEditor))
                            } yield { checkedParameters.toMap }
                          ) ?~ (s"Error with directive Parameters" )
    } yield {
      val beforeState = DirectiveState(oldTechnique,oldDirective)
      val afterState  =  DirectiveState(updatedTechnique,updatedDirective.copy(parameters = newParameters))
      DirectiveUpdate(activeTechnique,beforeState, afterState)
    }
  }

  def checkDirective(directiveId: DirectiveUid, restDirective: RestDirective) : Box[JValue] = {
    for {
      directiveUpdate <- updateDirectiveModel(directiveId, restDirective)
    } yield {
      val updatedTechnique = directiveUpdate.after.technique
      val updatedDirective = directiveUpdate.after.directive
      val jsonDirective = List(serialize(updatedTechnique,updatedDirective, None))
      JArray(jsonDirective)
    }
  }

  def updateDirective(directiveId: DirectiveUid, restDirective: RestDirective) = {
    for {
      directiveUpdate <- updateDirectiveModel(directiveId, restDirective)

      updatedTechnique = directiveUpdate.after.technique
      updatedDirective = directiveUpdate.after.directive
      oldTechnique     = directiveUpdate.before.technique
      oldDirective     = directiveUpdate.before.directive
      diff = ModifyToDirectiveDiff(updatedTechnique.id.name,updatedDirective, Some(oldTechnique.rootSection))
    } yield {
        createChangeRequestAndAnswer(
            diff
          , updatedTechnique
          , directiveUpdate.activeTechnique
          , updatedDirective
          , Some(oldDirective)
          , DGModAction.Update
        ) _
    }
  }
}

class DirectiveApiService14 (
    readDirective       : RoDirectiveRepository
  , configRepository    : ConfigurationRepository
  , writeDirective      : WoDirectiveRepository
  , uuidGen             : StringUuidGenerator
  , asyncDeploymentAgent: AsyncDeploymentActor
  , workflowLevelService: WorkflowLevelService
  , editorService       : DirectiveEditorService
  , restDataSerializer  : RestDataSerializer
  , techniqueRepository : TechniqueRepository
  ) {

  def serialize = restDataSerializer.serializeDirective _

  def directiveTree(includeSystem : Boolean) : IOResult[JRDirectiveTreeCategory] = {
    def filterSystem(cat : FullActiveTechniqueCategory) : FullActiveTechniqueCategory = {
      cat.copy(
          subCategories    = cat.subCategories.filter(c => includeSystem || !c.isSystem).map(filterSystem).sortBy(_.name)
        , activeTechniques = cat.activeTechniques.filter(c => includeSystem || !c.isSystem).map(t => t.copy(directives = t.directives.filterNot(_.isSystem).sortBy(_.name))).sortBy(_.techniqueName.value)
      )
    }
    for {
      root <- readDirective.getFullDirectiveLibrary()
    } yield {
      JRDirectiveTreeCategory.fromActiveTechniqueCategory(filterSystem(root))
    }
  }
  def listDirectives(): IOResult[List[JRDirective]] = {
    for {
      fullLibrary <- readDirective.getFullDirectiveLibrary().chainError("Could not fetch Directives")
      directives  <- ZIO.foreach(fullLibrary.allDirectives.values.filter(!_._2.isSystem).toList.sortBy(_._2.id.debugString)) { case (activeTechnique, directive) =>
                       val activeTechniqueId = TechniqueId(activeTechnique.techniqueName, directive.techniqueVersion)
                       techniqueRepository.get(activeTechniqueId).notOptional(s"No Technique with ID '${activeTechniqueId.debugString}' found in reference library.").map(t =>
                         JRDirective.fromDirective(t, directive, None)
                       )
                     }
    } yield {
      directives
    }
  }

  def getTechniqueWithVersion(optTechniqueName: Option[TechniqueName], opTechniqueVersion: Option[TechniqueVersion]): IOResult[Technique] = {
    // we need at least a name.
    // It version is not specified, use last version of technique, last revision, even if revision is specified (if someone want something precise, he must give the main version)
    // If both version and revision are specified, revision from techniqueRevision field is prefered.
    (optTechniqueName, opTechniqueVersion) match {
      case (None               , _            ) =>
        Inconsistency("techniqueName should not be empty").fail
      case (Some(techniqueName), None         ) => // get last version of technique, with last revision in that case
          IOResult.attempt(techniqueRepository.getLastTechniqueByName(techniqueName)).notOptional( s"Error while fetching last version of technique ${techniqueName.value}")
      case (Some(techniqueName), Some(version)) =>
        val id = TechniqueId(techniqueName, version)
        configRepository.getTechnique(id).notOptional(s" Technique '${techniqueName.value}' version '${version.serialize}' is not a valid Technique").map(_._2)
    }
  }

  def createOrCloneDirective(restDirective: JQDirective, directiveId: DirectiveUid, source: Option[DirectiveId], params: DefaultParams, actor: EventActor): IOResult[JRDirective] = {
    def actualDirectiveCreation(restDirective: JQDirective, baseDirective: Directive, activeTechnique: ActiveTechnique, technique: Technique, params: DefaultParams, actor: EventActor): IOResult[JRDirective] = {
      val newDirective = restDirective.updateDirective(baseDirective)
      val modId = ModificationId(uuidGen.newUuid)
      for {
        // Check if a directive exists with the current id
        _     <- readDirective.getDirective(newDirective.id.uid).flatMap {
                   case None => ZIO.unit
                   case Some(_) => Inconsistency(s"Cannot create a new Directive with id '${newDirective.id.uid.value}' already exists").fail
                 }
         // Check parameters of the new Directive
         _    <- ( for {
                     // Two step process, could be simplified
                     paramEditor       <- editorService.get(technique.id, newDirective.id.uid, newDirective.parameters)
                     checkedParameters <- sequence (paramEditor.mapValueSeq.toSeq)( checkParameters(paramEditor))
                   } yield { checkedParameters.toMap }
                 ).toIO.chainError(s"Error with directive Parameters" )
        saved <- writeDirective.saveDirective(activeTechnique.id, newDirective, modId, actor, params.reason).chainError(s"Could not save Directive ${newDirective.id.uid.value}" )
        // We need to deploy only if there is a saveDiff, that says that a deployment is needed
        _     <- ZIO.when(saved.map(_.needDeployment).getOrElse(false)) {
                   IOResult.attempt(asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor))
                 }
      } yield {
        JRDirective.fromDirective(technique,newDirective, None)
      }
    }
    source match {
      case Some(cloneId) =>
        for {
          name          <- restDirective.displayName.checkMandatory(_.size > 3, v => "'displayName' is mandatory and must be at least 3 char long")
          ad            <- configRepository.getDirective(cloneId).notOptional(s"Can not find directive to clone: ${cloneId.debugString}")
          // technique version: by default, directive one. It techniqueVersion is given, use that. If techniqueRevision is specified, use it.
          techVersion   =  restDirective.techniqueVersion.getOrElse(ad.directive.techniqueVersion)
          techId        =  TechniqueId(ad.activeTechnique.techniqueName, techVersion)
          tuple         <- configRepository.getTechnique(techId).notOptional(s"Technique with ID '${techId.debugString}' was not found")
          (_,technique) =  tuple
          newDirective  =  restDirective.copy(enabled = Some(false), techniqueVersion = Some(techId.version))
          baseDirective =  ad.directive.modify(_.id.uid).setTo(directiveId)
          result        <- actualDirectiveCreation(newDirective, baseDirective, ad.activeTechnique, technique, params, actor)
        } yield {
          result
        }
      case None =>
        for {
          name            <- restDirective.displayName.checkMandatory(_.size > 3, v => "'displayName' is mandatory and must be at least 3 char long")
          technique       <- getTechniqueWithVersion(restDirective.techniqueName, restDirective.techniqueVersion).chainError(s"Technique is not correctly defined in request data.")
          activeTechnique <- readDirective.getActiveTechnique(technique.id.name).notOptional(s"Technique ${technique.id.name} cannot be found.")
          baseDirective   =  Directive(DirectiveId(directiveId, GitVersion.DEFAULT_REV), technique.id.version, Map(), name, "", None, _isEnabled = true)
          result          <- actualDirectiveCreation(restDirective, baseDirective, activeTechnique, technique, params, actor)
        } yield {
          result
        }
    }
  }

  private
  def createChangeRequest(diff: ChangeRequestDirectiveDiff, technique: Technique, change: DirectiveChangeRequest, params: DefaultParams, actor: EventActor) = {
    for {
      workflow  <- workflowLevelService.getForDirective(actor, change).toIO
      cr        =  ChangeRequestService.createChangeRequestFromDirective(
                       params.changeRequestName.getOrElse(s"${change.action.name} directive '${change.newDirective.name}' (${change.newDirective.id.uid.value}) from API")
                     , params.changeRequestDescription.getOrElse("")
                     , change.techniqueName
                     , Some(change.sectionSpec)
                     , change.newDirective.id
                     , change.previousDirective
                     , diff
                     , actor
                     , params.reason
                   )
      id        <- workflow.startWorkflow(cr, actor, None).toIO.chainError(s"Could not start workflow for change request creation on Directive '${change.newDirective.name}'")
    } yield {
      val optCrId = if (workflow.needExternalValidation()) Some(id) else None
      JRDirective.fromDirective(technique, change.newDirective, optCrId)
    }
  }

  def updateDirective(restDirective: JQDirective, params: DefaultParams, actor: EventActor): IOResult[JRDirective] = {
    for {
      id               <- restDirective.id.notOptional(s"Directive id is mandatory in update")
      directiveUpdate  <- updateDirectiveModel(id.uid, restDirective)
      updatedTechnique =  directiveUpdate.after.technique
      updatedDirective =  directiveUpdate.after.directive
      oldTechnique     =  directiveUpdate.before.technique
      oldDirective     =  directiveUpdate.before.directive
      diff             =  ModifyToDirectiveDiff(updatedTechnique.id.name,updatedDirective,Some(oldTechnique.rootSection))
      change           =  DirectiveChangeRequest(DGModAction.Update, updatedTechnique.id.name, directiveUpdate.activeTechnique.id, updatedTechnique.rootSection, updatedDirective, Some(oldDirective), Nil, Nil)
      result           <- createChangeRequest(diff, updatedTechnique, change, params, actor)
    } yield result
  }

  def deleteDirective(id: DirectiveUid, params: DefaultParams, actor: EventActor): IOResult[JRDirective] = {
          // TODO: manage rev
    readDirective.getDirectiveWithContext(id).flatMap {
      case Some((technique, activeTechnique, directive)) =>
        val change = DirectiveChangeRequest(DGModAction.Delete, technique.id.name, activeTechnique.id, technique.rootSection, directive, Some(directive), Nil, Nil)
        createChangeRequest(DeleteDirectiveDiff(technique.id.name,directive), technique, change, params, actor)
      case None =>
        JRDirective.empty(id.value).succeed
    }
  }


  def directiveDetails(id: DirectiveId): IOResult[JRDirective] = {
    for {
      at <- configRepository.getDirective(id).notOptional(s"Directive with id '${id.debugString}' was not found")
      tid = TechniqueId(at.activeTechnique.techniqueName, at.directive.techniqueVersion)
      t  <- configRepository.getTechnique(tid).notOptional(s"Technique with id '${tid.debugString}' was not found")
    } yield {
      JRDirective.fromDirective(t._2, at.directive, None)
    }
  }

  /*
   * List available revision for given directive
   */
  def directiveRevisions(uid: DirectiveUid): IOResult[List[JRRevisionInfo]] = {
    configRepository.getDirectiveRevision(uid).map(_.map(JRRevisionInfo.fromRevisionInfo))
  }

  // A function to check if Variables passed as parameter are correct
  private[this] def checkParameters (paramEditor: DirectiveEditor) (parameterValues : (String,Seq[String])) = {
    try {
      val s = Seq((paramEditor.variableSpecs(parameterValues._1).toVariable(parameterValues._2)))
          RudderLDAPConstants.variableToSeq(s)
          Full(parameterValues)
    } catch {
      case e: Exception => Failure(s"Parameter '${parameterValues._1}' value is not valid, values are: ${parameterValues._2.mkString("[ ", ", ", " ]")} : ${e.getMessage()}")
    }
  }

  private[this] def updateDirectiveModel(directiveId: DirectiveUid, restDirective: JQDirective): IOResult[DirectiveUpdate] = {
    for {
     triple              <- readDirective.getDirectiveWithContext(directiveId).notOptional(s"Could not find Directive ${directiveId.value}")
     (oldTechnique, activeTechnique, oldDirective) = triple
      // Check if Technique version is changed (migration)
      updatedTechniqueId =  TechniqueId(oldTechnique.id.name, restDirective.techniqueVersion.getOrElse(oldTechnique.id.version))
      updatedTechnique   <- if (updatedTechniqueId.version == oldTechnique.id.version) {
                              oldTechnique.succeed
                            } else {
                              techniqueRepository.get(updatedTechniqueId).notOptional(s"Could not find technique ${updatedTechniqueId.name.value} with version ${updatedTechniqueId.version.debugString}.")
                            }
       updatedDirective  = restDirective.updateDirective(oldDirective)
       // Check parameters of the new Directive with the current technique version, It will check that parameters are ok with the new technique
       newParameters     <- (for {
                              // Two step process, could be simplified
                              paramEditor       <- editorService.get(updatedTechniqueId, directiveId, updatedDirective.parameters).toIO
                              checkedParameters <- ZIO.foreach(paramEditor.mapValueSeq.toList)(p => (checkParameters(paramEditor)(p)).toIO )
                            } yield { checkedParameters.toMap }).chainError(s"Error with directive Parameters")
    } yield {
      val beforeState = DirectiveState(oldTechnique,oldDirective)
      val afterState  = DirectiveState(updatedTechnique,updatedDirective.copy(parameters = newParameters))
      DirectiveUpdate(activeTechnique,beforeState, afterState)
    }
  }

  def checkDirective(directiveId: DirectiveUid, restDirective: JQDirective) : IOResult[JRDirective] = {
    for {
      directiveUpdate <- updateDirectiveModel(directiveId, restDirective)
    } yield {
      val updatedTechnique = directiveUpdate.after.technique
      val updatedDirective = directiveUpdate.after.directive
      JRDirective.fromDirective(updatedTechnique, updatedDirective, None)
    }
  }
}

