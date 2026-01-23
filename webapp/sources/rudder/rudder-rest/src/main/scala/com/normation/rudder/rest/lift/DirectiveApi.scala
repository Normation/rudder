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
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.apidata.JsonQueryObjects.*
import com.normation.rudder.apidata.JsonResponseObjects.*
import com.normation.rudder.apidata.ZioJsonExtractor
import com.normation.rudder.apidata.implicits.*
import com.normation.rudder.batch.AsyncDeploymentActor
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.configuration.ConfigurationRepository
import com.normation.rudder.domain.RudderLDAPConstants
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.rudder.domain.policies.ChangeRequestDirectiveDiff
import com.normation.rudder.domain.policies.DeleteDirectiveDiff
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.ModifyToDirectiveDiff
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.WoDirectiveRepository
import com.normation.rudder.rest.{DirectiveApi as API, *}
import com.normation.rudder.rest.data.*
import com.normation.rudder.rest.syntax.*
import com.normation.rudder.services.workflows.ChangeRequestService
import com.normation.rudder.services.workflows.DGModAction
import com.normation.rudder.services.workflows.DirectiveChangeRequest
import com.normation.rudder.services.workflows.WorkflowLevelService
import com.normation.rudder.tenants.ChangeContext
import com.normation.rudder.web.model.DirectiveEditor
import com.normation.rudder.web.services.DirectiveEditorService
import com.normation.utils.Control.*
import com.normation.utils.StringUuidGenerator
import com.softwaremill.quicklens.*
import net.liftweb.common.*
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import zio.*
import zio.syntax.*

class DirectiveApi(
    zioJsonExtractor: ZioJsonExtractor,
    uuidGen:          StringUuidGenerator,
    service:          DirectiveApiService14
) extends LiftApiModuleProvider[API] {

  def schemas: ApiModuleProvider[API] = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map {
      case API.DirectiveTree      => DirectiveTree
      case API.ListDirectives     => ListDirective
      case API.DirectiveDetails   => DirectiveDetails
      case API.DirectiveRevisions => DirectiveRevisions
      case API.CreateDirective    => CreateDirective
      case API.UpdateDirective    => UpdateDirective
      case API.DeleteDirective    => DeleteDirective
      case API.CheckDirective     => CheckDirective
    }
  }

  object ListDirective extends LiftApiModule0 {
    val schema:                                                                                                API.ListDirectives.type = API.ListDirectives
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse            = {
      service.listDirectives().toLiftResponseList(params, schema)
    }
  }
  object DirectiveTree extends LiftApiModule0 {
    val schema:                                                                                                API.DirectiveTree.type = API.DirectiveTree
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse           = {
      (for {
        includeSystem <- zioJsonExtractor.extractIncludeSystem(req).toIO
        res           <- service.directiveTree(includeSystem.getOrElse(false))
      } yield {
        res
      }).toLiftResponseOne(params, schema, _ => None)
    }
  }

  object DirectiveDetails extends LiftApiModuleString {
    val schema: API.DirectiveDetails.type = API.DirectiveDetails
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      (for {
        did <- DirectiveId.parse(id).toIO
        res <- service.directiveDetails(did)
      } yield res).toLiftResponseOne(params, schema, d => Some(d.id))
    }
  }

  object DirectiveRevisions extends LiftApiModuleString {
    val schema: API.DirectiveRevisions.type = API.DirectiveRevisions
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      service.directiveRevisions(DirectiveUid(id)).toLiftResponseList(params, schema)
    }
  }

  object CreateDirective extends LiftApiModule0 {
    val schema:                                                                                                API.CreateDirective.type = API.CreateDirective
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse             = {

      implicit val cc: ChangeContext = authzToken.qc.newCC(params.reason)

      (for {
        restDirective <-
          zioJsonExtractor.extractDirective(req).chainError(s"Could not extract directive parameters from request").toIO
        result        <- service.createOrCloneDirective(
                           restDirective,
                           restDirective.id.map(_.uid).getOrElse(DirectiveUid(uuidGen.newUuid)),
                           restDirective.source,
                           params
                         )
      } yield {
        val action = if (restDirective.source.nonEmpty) "cloneDirective" else schema.name
        (RudderJsonResponse.ResponseSchema(action, schema.dataContainer), result)
      }).toLiftResponseOneMap(params, RudderJsonResponse.ResponseSchema.fromSchema(schema), x => (x._1, x._2, Some(x._2.id)))

    }
  }

  object UpdateDirective extends LiftApiModuleString {
    val schema: API.UpdateDirective.type = API.UpdateDirective
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        sid:        String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val cc: ChangeContext = authzToken.qc.newCC(params.reason)

      (for {
        id            <- DirectiveId.parse(sid).toIO
        restDirective <- zioJsonExtractor.extractDirective(req).chainError(s"Could not extract a directive from request.").toIO
        result        <- service.updateDirective(restDirective.copy(id = Some(id)), params, authzToken.qc.actor)
      } yield {
        result
      }).toLiftResponseOne(params, schema, s => Some(s.id))
    }
  }

  object DeleteDirective extends LiftApiModuleString {
    val schema: API.DeleteDirective.type = API.DeleteDirective
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val cc: ChangeContext = authzToken.qc.newCC(params.reason)
      service.deleteDirective(DirectiveUid(id), params, authzToken.qc.actor).toLiftResponseOne(params, schema, s => Some(s.id))
    }
  }

  object CheckDirective extends LiftApiModuleString {
    val schema: API.CheckDirective.type = API.CheckDirective
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      val directiveId = DirectiveUid(id)
      (for {
        restDirective <- zioJsonExtractor.extractDirective(req).chainError(s"Could not extract values from request.").toIO
        result        <- service.checkDirective(directiveId, restDirective)
      } yield {
        result
      }).toLiftResponseOne(params, schema, s => Some(s.id))
    }
  }
}

// utility methods used in both service
object DirectiveApiService {
  def extractTechnique(
      techniqueRepository: TechniqueRepository,
      optTechniqueName:    Option[TechniqueName],
      opTechniqueVersion:  Option[TechniqueVersion]
  ): PureResult[Technique] = {
    (optTechniqueName, opTechniqueVersion) match {
      case (None, _)                            =>
        Left(Inconsistency("techniqueName should not be empty"))
      case (Some(techniqueName), None)          =>
        techniqueRepository
          .getLastTechniqueByName(techniqueName)
          .notOptionalPure(s"Error while fetching last version of technique ${techniqueName}")
      case (Some(techniqueName), Some(version)) =>
        techniqueRepository.get(TechniqueId(techniqueName, version)) match {
          case Some(technique) => Right(technique)
          case None            => Left(Inconsistency(s" Technique ${techniqueName} version ${version} is not a valid Technique"))
        }
    }
  }

}

class DirectiveApiService14(
    readDirective:        RoDirectiveRepository,
    configRepository:     ConfigurationRepository,
    writeDirective:       WoDirectiveRepository,
    uuidGen:              StringUuidGenerator,
    asyncDeploymentAgent: AsyncDeploymentActor,
    workflowLevelService: WorkflowLevelService,
    editorService:        DirectiveEditorService,
    techniqueRepository:  TechniqueRepository
) {

  def directiveTree(includeSystem: Boolean): IOResult[JRDirectiveTreeCategory] = {
    def filterSystem(cat: FullActiveTechniqueCategory): FullActiveTechniqueCategory = {
      cat.copy(
        subCategories = cat.subCategories.filter(c => includeSystem || !c.isSystem).map(filterSystem).sortBy(_.name),
        activeTechniques = cat.activeTechniques
          .filter(c => includeSystem || c.policyTypes.isBase)
          .map(t => t.copy(directives = t.directives.filterNot(_.isSystem).sortBy(_.name)))
          .sortBy(_.techniqueName.value)
      )
    }
    for {
      root <- readDirective.getFullDirectiveLibrary()
    } yield {
      JRDirectiveTreeCategory.fromActiveTechniqueCategory(filterSystem(root))
    }
  }
  def listDirectives():                      IOResult[List[JRDirective]]       = {
    for {
      fullLibrary <- readDirective.getFullDirectiveLibrary().chainError("Could not fetch Directives")
      directives  <- ZIO.foreach(fullLibrary.allDirectives.values.filter(!_._2.isSystem).toList.sortBy(_._2.id.debugString)) {
                       case (activeTechnique, directive) =>
                         val activeTechniqueId = TechniqueId(activeTechnique.techniqueName, directive.techniqueVersion)
                         techniqueRepository
                           .get(activeTechniqueId)
                           .notOptional(s"No Technique with ID '${activeTechniqueId.debugString}' found in reference library.")
                           .map(t => JRDirective.fromDirective(t, directive, None))
                     }
    } yield {
      directives
    }
  }

  def getTechniqueWithVersion(
      optTechniqueName:   Option[TechniqueName],
      opTechniqueVersion: Option[TechniqueVersion]
  ): IOResult[Technique] = {
    // we need at least a name.
    // If version is not specified, use last version of technique, last revision, even if revision is specified (if someone want something precise, they must give the main version)
    // If both version and revision are specified, revision from techniqueRevision field is prefered.
    (optTechniqueName, opTechniqueVersion) match {
      case (None, _)                            =>
        Inconsistency("techniqueName should not be empty").fail
      case (Some(techniqueName), None)          => // get last version of technique, with last revision in that case
        IOResult
          .attempt(techniqueRepository.getLastTechniqueByName(techniqueName))
          .notOptional(s"Error while fetching last version of technique ${techniqueName.value}")
      case (Some(techniqueName), Some(version)) =>
        val id = TechniqueId(techniqueName, version)
        configRepository
          .getTechnique(id)
          .notOptional(s" Technique '${techniqueName.value}' version '${version.serialize}' is not a valid Technique")
          .map(_._2)
    }
  }

  def createOrCloneDirective(
      restDirective: JQDirective,
      directiveId:   DirectiveUid,
      source:        Option[DirectiveId],
      params:        DefaultParams
  )(implicit cc: ChangeContext): IOResult[JRDirective] = {
    def actualDirectiveCreation(
        restDirective:   JQDirective,
        baseDirective:   Directive,
        activeTechnique: ActiveTechnique,
        technique:       Technique,
        params:          DefaultParams
    )(implicit cc: ChangeContext): IOResult[JRDirective] = {
      val newDirective = restDirective.updateDirective(baseDirective)
      val modId        = ModificationId(uuidGen.newUuid)
      for {
        // Check if a directive exists with the current id
        _     <- readDirective.getDirective(newDirective.id.uid).flatMap {
                   case None    => ZIO.unit
                   case Some(_) =>
                     Inconsistency(s"Cannot create a new Directive with id '${newDirective.id.uid.value}' already exists").fail
                 }
        // Check parameters of the new Directive
        _     <- (for {
                   // Two steps process, could be simplified
                   paramEditor       <- editorService.get(technique.id, newDirective.id.uid, newDirective.parameters)
                   checkedParameters <- traverse(paramEditor.mapValueSeq.toSeq)(checkParameters(paramEditor))
                 } yield { checkedParameters.toMap }).toIO.chainError(s"Error with directive Parameters")
        saved <- writeDirective
                   .saveDirective(activeTechnique.id, newDirective, modId, cc.actor, params.reason)
                   .chainError(s"Could not save Directive ${newDirective.id.uid.value}")
        // We need to deploy only if there is a saveDiff, that says that a deployment is needed
        _     <- ZIO.when(saved.map(_.needDeployment).getOrElse(false)) {
                   IOResult.attempt(asyncDeploymentAgent ! AutomaticStartDeployment(modId, cc.actor))
                 }
      } yield {
        JRDirective.fromDirective(technique, newDirective, None)
      }
    }
    source match {
      case Some(cloneId) =>
        for {
          _             <- restDirective.displayName.checkMandatory(
                             _.size > 3,
                             _ => "'displayName' is mandatory and must be at least 3 char long"
                           )
          ad            <- configRepository.getDirective(cloneId).notOptional(s"Can not find directive to clone: ${cloneId.debugString}")
          // technique version: by default, directive one. It techniqueVersion is given, use that. If techniqueRevision is specified, use it.
          techVersion    = restDirective.techniqueVersion.getOrElse(ad.directive.techniqueVersion)
          techId         = TechniqueId(ad.activeTechnique.techniqueName, techVersion)
          tuple         <- configRepository.getTechnique(techId).notOptional(s"Technique with ID '${techId.debugString}' was not found")
          (_, technique) = tuple
          newDirective   = restDirective.copy(enabled = Some(false), techniqueVersion = Some(techId.version))
          baseDirective  = ad.directive.modify(_.id.uid).setTo(directiveId)
          result        <- actualDirectiveCreation(newDirective, baseDirective, ad.activeTechnique, technique, params)
        } yield {
          result
        }
      case None          =>
        for {
          name            <- restDirective.displayName.checkMandatory(
                               _.size > 3,
                               _ => "'displayName' is mandatory and must be at least 3 char long"
                             )
          technique       <- getTechniqueWithVersion(restDirective.techniqueName, restDirective.techniqueVersion).chainError(
                               s"Technique is not correctly defined in request data."
                             )
          activeTechnique <-
            readDirective.getActiveTechnique(technique.id.name).notOptional(s"Technique ${technique.id.name} cannot be found.")
          baseDirective    = Directive(
                               DirectiveId(directiveId, GitVersion.DEFAULT_REV),
                               technique.id.version,
                               Map(),
                               name,
                               "",
                               None,
                               _isEnabled = true,
                               security = cc.accessGrant.toSecurityTag
                             )
          result          <- actualDirectiveCreation(restDirective, baseDirective, activeTechnique, technique, params)
        } yield {
          result
        }
    }
  }

  private def createChangeRequest(
      diff:      ChangeRequestDirectiveDiff,
      technique: Technique,
      change:    DirectiveChangeRequest,
      params:    DefaultParams
  )(implicit cc: ChangeContext) = {
    for {
      workflow <- workflowLevelService.getForDirective(cc.actor, change)
      cr        = ChangeRequestService.createChangeRequestFromDirective(
                    params.changeRequestName.getOrElse(
                      s"${change.action.name} directive '${change.newDirective.name}' (${change.newDirective.id.uid.value}) from API"
                    ),
                    params.changeRequestDescription.getOrElse(""),
                    change.techniqueName,
                    Some(change.sectionSpec),
                    change.newDirective.id,
                    change.previousDirective,
                    diff,
                    cc.actor,
                    params.reason
                  )
      id       <- workflow
                    .startWorkflow(cr)
                    .chainError(s"Could not start workflow for change request creation on Directive '${change.newDirective.name}'")
    } yield {
      val optCrId = if (workflow.needExternalValidation()) Some(id) else None
      JRDirective.fromDirective(technique, change.newDirective, optCrId)
    }
  }

  def updateDirective(restDirective: JQDirective, params: DefaultParams, actor: EventActor)(implicit
      cc: ChangeContext
  ): IOResult[JRDirective] = {
    for {
      id              <- restDirective.id.notOptional(s"Directive id is mandatory in update")
      directiveUpdate <- updateDirectiveModel(id.uid, restDirective)
      updatedTechnique = directiveUpdate.after.technique
      updatedDirective = directiveUpdate.after.directive
      oldTechnique     = directiveUpdate.before.technique
      oldDirective     = directiveUpdate.before.directive
      diff             = ModifyToDirectiveDiff(updatedTechnique.id.name, updatedDirective, Some(oldTechnique.rootSection))
      change           = DirectiveChangeRequest(
                           DGModAction.Update,
                           updatedTechnique.id.name,
                           directiveUpdate.activeTechnique.id,
                           updatedTechnique.rootSection,
                           updatedDirective,
                           Some(oldDirective),
                           Nil,
                           Nil
                         )
      result          <- createChangeRequest(diff, updatedTechnique, change, params)
    } yield result
  }

  def deleteDirective(id: DirectiveUid, params: DefaultParams, actor: EventActor)(implicit
      cc: ChangeContext
  ): IOResult[JRDirective] = {
    // TODO: manage rev
    readDirective.getDirectiveWithContext(id).flatMap {
      case Some((technique, activeTechnique, directive)) =>
        val change = DirectiveChangeRequest(
          DGModAction.Delete,
          technique.id.name,
          activeTechnique.id,
          technique.rootSection,
          directive,
          Some(directive),
          Nil,
          Nil
        )
        createChangeRequest(DeleteDirectiveDiff(technique.id.name, directive), technique, change, params)
      case None                                          =>
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
  private def checkParameters(paramEditor: DirectiveEditor)(parameterValues: (String, Seq[String])) = {
    try {
      val s = Seq((paramEditor.variableSpecs(parameterValues._1).toVariable(parameterValues._2)))
      RudderLDAPConstants.variableToSeq(s)
      Full(parameterValues)
    } catch {
      case e: Exception =>
        Failure(s"Parameter '${parameterValues._1}' value is not valid, values are: ${parameterValues._2
            .mkString("[ ", ", ", " ]")} : ${e.getMessage()}")
    }
  }

  private def updateDirectiveModel(directiveId: DirectiveUid, restDirective: JQDirective): IOResult[DirectiveUpdate] = {
    for {
      triple                                       <- readDirective.getDirectiveWithContext(directiveId).notOptional(s"Could not find Directive ${directiveId.value}")
      (oldTechnique, activeTechnique, oldDirective) = triple
      // Check if Technique version is changed (migration)
      updatedTechniqueId                            = TechniqueId(oldTechnique.id.name, restDirective.techniqueVersion.getOrElse(oldTechnique.id.version))
      updatedTechnique                             <- if (updatedTechniqueId.version == oldTechnique.id.version) {
                                                        oldTechnique.succeed
                                                      } else {
                                                        techniqueRepository
                                                          .get(updatedTechniqueId)
                                                          .notOptional(
                                                            s"Could not find technique ${updatedTechniqueId.name.value} with version ${updatedTechniqueId.version.debugString}."
                                                          )
                                                      }
      updatedDirective                              = restDirective.updateDirective(oldDirective)
      // Check parameters of the new Directive with the current technique version, It will check that parameters are ok with the new technique
      newParameters                                <- (for {
                                                        // Two step process, could be simplified
                                                        paramEditor       <- editorService.get(updatedTechniqueId, directiveId, updatedDirective.parameters).toIO
                                                        checkedParameters <-
                                                          ZIO.foreach(paramEditor.mapValueSeq.toList)(p => (checkParameters(paramEditor)(p)).toIO)
                                                      } yield { checkedParameters.toMap }).chainError(s"Error with directive Parameters")
    } yield {
      val beforeState = DirectiveState(oldTechnique, oldDirective)
      val afterState  = DirectiveState(updatedTechnique, updatedDirective.copy(parameters = newParameters))
      DirectiveUpdate(activeTechnique, beforeState, afterState)
    }
  }

  def checkDirective(directiveId: DirectiveUid, restDirective: JQDirective): IOResult[JRDirective] = {
    for {
      directiveUpdate <- updateDirectiveModel(directiveId, restDirective)
    } yield {
      val updatedTechnique = directiveUpdate.after.technique
      val updatedDirective = directiveUpdate.after.directive
      JRDirective.fromDirective(updatedTechnique, updatedDirective, None)
    }
  }
}
