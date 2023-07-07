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

import better.files.File
import com.normation.box._
import com.normation.cfclerk.domain._
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.errors._
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.apidata.JsonResponseObjects._
import com.normation.rudder.apidata.RestDataSerializer
import com.normation.rudder.apidata.implicits._
import com.normation.rudder.domain.logger.ApiLoggerPure
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.ncf._
import com.normation.rudder.ncf.BundleName
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.xml.TechniqueRevisionRepository
import com.normation.rudder.rest.{TechniqueApi => API, _}
import com.normation.rudder.rest.RestUtils.ActionType
import com.normation.rudder.rest.RestUtils.actionResponse2
import com.normation.rudder.rest.RestUtils.response
import com.normation.rudder.rest.implicits._
import com.normation.utils.ParseVersion
import com.normation.utils.StringUuidGenerator
import com.normation.utils.Version
import net.liftweb.common._
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.json.JsonAST._
import scala.collection.SortedMap
import zio._
import zio.json.ast.Json
import zio.json.ast.Json.Str
import zio.syntax._

class TechniqueApi(
    restExtractorService: RestExtractorService,
    apiV6:                TechniqueAPIService6,
    serviceV14:           TechniqueAPIService14,
    techniqueWriter:      TechniqueWriter,
    techniqueReader:      TechniqueReader,
    techniqueRepository:  TechniqueRepository,
    techniqueSerializer:  TechniqueSerializer,
    uuidGen:              StringUuidGenerator,
    resourceFileService:  ResourceFileService,
    configRepoPath:       String
) extends LiftApiModuleProvider[API] {

  import techniqueSerializer._
  import zio.json._
  def schemas = API

  val dataName = "techniques"
  def resp(function: Box[JValue], req: Req, errorMessage: String)(action: String)(implicit dataName: String): LiftResponse = {
    response(restExtractorService, dataName, None)(function, req, errorMessage)
  }

  def actionResp(function: Box[ActionType], req: Req, errorMessage: String, actor: EventActor)(implicit
      action:              String
  ): LiftResponse = {
    actionResponse2(restExtractorService, dataName, uuidGen, None)(function, req, errorMessage)(action, actor)
  }

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints
      .map(e => {
        e match {
          case API.GetTechniques             => ChooseApi0(ListTechniques, GetTechniques)
          case API.ListTechniques            => ListTechniquesV14
          case API.ListTechniquesDirectives  => ChooseApiN(ListTechniquesDirectives, ListTechniquesDirectivesV14)
          case API.ListTechniqueDirectives   => ChooseApiN(ListTechniqueDirectives, ListTechniqueDirectivesV14)
          case API.TechniqueRevisions        => TechniqueRevisions
          case API.UpdateTechnique           => UpdateTechnique
          case API.CreateTechnique           => CreateTechnique
          case API.GetResources              => new GetResources[API.GetResources.type](false, API.GetResources)
          case API.GetNewResources           => new GetResources[API.GetNewResources.type](true, API.GetNewResources)
          case API.DeleteTechnique           => DeleteTechnique
          case API.GetMethods                => GetMethods
          case API.UpdateMethods             => UpdateMethods
          case API.UpdateTechniques          => UpdateTechniques
          case API.GetAllTechniqueCategories => GetAllTechniqueCategories
          case API.GetTechniqueAllVersion    => GetTechniqueDetailsAllVersion
          case API.GetTechnique              => GetTechnique
        }
      })
      .toList
  }

  class GetResources[T <: TwoParam](newTechnique: Boolean, val schema: T) extends LiftApiModule {

    val restExtractor     = restExtractorService
    implicit val dataName = "resources"
    def process(
        version:       ApiVersion,
        path:          ApiPath,
        techniqueInfo: (String, String),
        req:           Req,
        params:        DefaultParams,
        authzToken:    AuthzToken
    ): LiftResponse = {

      import net.liftweb.json.JsonDSL._
      import zio.syntax._

      def serializeResourceWithState(resource: ResourceFile) = {
        (("path" -> resource.path) ~ ("state" -> resource.state.value))
      }

      val action = if (newTechnique) { "newTechniqueResources" }
      else { "techniqueResources" }

      val resources = {
        (if (newTechnique) {
           resourceFileService.getResourcesFromDir(
             s"workspace/${techniqueInfo._1}/${techniqueInfo._2}/resources",
             techniqueInfo._1,
             techniqueInfo._2
           )
         } else {
           for {
             optTechnique <- techniqueReader.readTechniquesMetadataFile.map(_._1.find(_.id.value == techniqueInfo._1))
             resources    <-
               optTechnique
                 .map(resourceFileService.getResources)
                 .getOrElse(Inconsistency(s"No technique found when looking for technique '${techniqueInfo._1}' resources").fail)
           } yield {
             resources
           }
         }).map(r => JArray(r.map(serializeResourceWithState)))
      }

      resp(resources.toBox, req, "Could not get resource state of technique")(action)
    }
  }

  object DeleteTechnique extends LiftApiModule        {
    val schema: TwoParam = API.DeleteTechnique
    val restExtractor     = restExtractorService
    implicit val dataName = "techniques"

    def process(
        version:       ApiVersion,
        path:          ApiPath,
        techniqueInfo: (String, String),
        req:           Req,
        params:        DefaultParams,
        authzToken:    AuthzToken
    ): LiftResponse = {

      val modId = ModificationId(uuidGen.newUuid)

      val content = {
        for {
          force <- restExtractorService.extractBoolean("force")(req)(identity) map (_.getOrElse(false))
          _     <- techniqueWriter.deleteTechnique(techniqueInfo._1, techniqueInfo._2, force, modId, authzToken.actor).toBox
        } yield {
          import net.liftweb.json.JsonDSL._
          (("id"       -> techniqueInfo._1)
          ~ ("version" -> techniqueInfo._2))
        }
      }

      resp(content, req, "delete technique")("deleteTechnique")

    }
  }
  object UpdateTechnique extends LiftApiModuleString2 {
    val schema: TwoParam = API.UpdateTechnique
    val restExtractor = restExtractorService
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        nv:         (String, String),
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      val modId = ModificationId(uuidGen.newUuid)

      // copied from `Req.forcedBodyAsJson`
      def r  = """; *charset=(.*)""".r
      def r2 = """[^=]*$""".r
      def charset: String = req.contentType.flatMap(ct => r.findFirstIn(ct).flatMap(r2.findFirstIn)).getOrElse("UTF-8")

      // end copy
      val response = {
        for {
          technique        <-
            req.body match {
              case eb: EmptyBox => Unexpected((eb ?~! "error when accessing request body").messageChain).fail
              case Full(bytes) => new String(bytes, charset).fromJson[EditorTechnique].toIO
            }
          methodMap        <- techniqueReader.getMethodsMetadata
          updatedTechnique <- techniqueWriter.writeTechniqueAndUpdateLib(technique, methodMap, modId, authzToken.actor)
          json             <- updatedTechnique.toJsonAST.toIO
        } yield {
          json
        }
      }
      response.toLiftResponseOne(params, schema, _ => None)
    }
  }

  object GetTechniques extends LiftApiModule0 {
    val schema            = API.GetTechniques
    implicit val dataName = "techniques"
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      serviceV14.getTechniquesWithData().toLiftResponseList(params, schema)
    }

  }

  object GetMethods extends LiftApiModule0 {

    val schema            = API.GetMethods
    val restExtractor     = restExtractorService
    implicit val dataName = "methods"

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val response = for {
        methods <- techniqueReader.getMethodsMetadata
        sorted   = methods.toList.sortBy(_._1.value)
      } yield {
        JObject(sorted.map(m => JField(m._1.value, techniqueSerializer.serializeMethodMetadata(m._2))))
      }
      resp(response.toBox, req, "Could not get generic methods metadata")("getMethods")
    }

  }

  object UpdateMethods extends LiftApiModule0 {

    val schema            = API.UpdateMethods
    val restExtractor     = restExtractorService
    implicit val dataName = "methods"

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val response = for {
        _       <- techniqueReader.updateMethodsMetadataFile
        methods <- techniqueReader.getMethodsMetadata
      } yield {
        JObject(methods.toList.map(m => JField(m._1.value, techniqueSerializer.serializeMethodMetadata(m._2))))
      }
      resp(response.toBox, req, "Could not get generic methods metadata")("getMethods")
    }

  }

  object UpdateTechniques extends LiftApiModule0 {

    val schema            = API.UpdateTechniques
    val restExtractor     = restExtractorService
    implicit val dataName = "techniques"

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val modId    = ModificationId(uuidGen.newUuid)
      val response = for {
        res                          <- techniqueReader.readTechniquesMetadataFile
        (techniques, methods, errors) = res
        _                            <- if (errors.isEmpty) ().succeed
                                        else {
                                          ApiLoggerPure.error(
                                            s"An error occurred while reading techniques when updating them: ${errors.map(_.msg).mkString("\n ->", "\n ->", "")}"
                                          )
                                        }
        _                            <- ZIO.foreach(techniques)(t => techniqueWriter.writeTechnique(t, methods, modId, authzToken.actor))
        json                         <- ZIO.foreach(techniques)(_.toJsonAST.toIO)
      } yield {
        json
      }
      response.toLiftResponseList(params, schema)

    }

  }

  object GetAllTechniqueCategories extends LiftApiModule0 {

    val schema            = API.GetAllTechniqueCategories
    val restExtractor     = restExtractorService
    implicit val dataName = "techniqueCategories"

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val response = {
        val categories = techniqueRepository.getAllCategories
        def serializeTechniqueCategory(t: TechniqueCategory): JObject = {
          val subs = t.subCategoryIds.flatMap(categories.get).map(serializeTechniqueCategory).toList
          val name = t match {
            case t: RootTechniqueCategory => "/"
            case _ => t.name
          }
          JObject(
            JField("name", JString(name)),
            JField("path", JString(t.id.getPathFromRoot.tail.map(_.value).mkString("/"))),
            JField("id", JString(t.id.name.value)),
            JField("subCategories", JArray(subs))
          )
        }
        serializeTechniqueCategory(techniqueRepository.getTechniqueLibrary)
      }

      resp(Full(response), req, "Could not get generic methods metadata")("getMethods")

    }

  }

  object CreateTechnique extends LiftApiModule0 {

    def moveRessources(technique: EditorTechnique, internalId: String) = {
      val workspacePath = s"workspace/${internalId}/${technique.version.value}/resources"
      val finalPath     = s"techniques/${technique.category}/${technique.id.value}/${technique.version.value}/resources"

      val workspaceDir = File(s"${configRepoPath}/${workspacePath}")
      val finalDir     = File(s"${configRepoPath}/${finalPath}")

      IOResult.attempt("Error when moving resource file from workspace to final destination")(if (workspaceDir.exists) {
        finalDir.createDirectoryIfNotExists(true)
        workspaceDir.moveTo(finalDir)(File.CopyOptions.apply(true))
        workspaceDir.parent.parent.delete()
        "ok"
      } else {
        "ok"
      })
    }

    // Comparison on technique name and ID are not case sensitive
    private def isTechniqueNameExist(techniqueName: String) = {
      val techniques = techniqueRepository.getAll()
      techniques.values.map(_.name.toLowerCase).toList.contains(techniqueName.toLowerCase)
    }
    private def isTechniqueIdExist(bundleName: BundleName)  = {
      val techniques = techniqueRepository.getAll()
      techniques.keySet.map(_.name.value.toLowerCase).contains(bundleName.value.toLowerCase)
    }

    val schema        = API.CreateTechnique
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val modId = ModificationId(uuidGen.newUuid) // copied from `Req.forcedBodyAsJson`

      def r = """; *charset=(.*)""".r

      def r2 = """[^=]*$""".r

      def charset: String = req.contentType.flatMap(ct => r.findFirstIn(ct).flatMap(r2.findFirstIn)).getOrElse("UTF-8")

      // end copy
      val response = {
        for {
          technique     <-
            req.body match {
              case eb: EmptyBox => Unexpected((eb ?~! "error when accessing request body").messageChain).fail
              case Full(bytes) => new String(bytes, charset).fromJson[EditorTechnique].toIO
            }
          methodMap     <- techniqueReader.getMethodsMetadata
          isNameTaken    = isTechniqueNameExist(technique.name)
          isIdTaken      = isTechniqueIdExist(technique.id)
          _             <- (isNameTaken, isIdTaken) match {
                             case (true, true)   =>
                               Inconsistency(
                                 s"Technique name and ID must be unique. Name '${technique.name}' and ID '${technique.id.value}' already used, they are case insensitive"
                               ).fail
                             case (true, false)  =>
                               Inconsistency(
                                 s"Technique name must be unique. Name '${technique.name}' already used, it is case insensitive "
                               ).fail
                             case (false, true)  =>
                               Inconsistency(
                                 s"Technique ID must be unique. ID '${technique.id.value}' already used, it is case insensitive"
                               ).fail
                             case (false, false) => ().succeed
                           }

          // If no internalId (used to manage temporary folder for resources), ignore resources, this can happen when importing techniques through the api
          resoucesMoved <- technique.internalId.map(internalId => moveRessources(technique, internalId)).getOrElse("Ok".succeed)
          updatedTech   <- techniqueWriter.writeTechniqueAndUpdateLib(technique, methodMap, modId, authzToken.actor)
          json          <- updatedTech.toJsonAST.toIO
        } yield {
          json
        }
      }
      response.toLiftResponseOne(params, schema, _ => None)
    }
  }

  object ListTechniques extends LiftApiModule0 {
    val schema        = API.ListTechniques
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      response(
        restExtractor,
        "techniques",
        None
      )(
        apiV6.listTechniques,
        req,
        s"Could not find list of techniques"
      )("listTechniques")
    }
  }

  object ListTechniquesDirectives extends LiftApiModuleString {
    val schema        = API.ListTechniquesDirectives
    val restExtractor = restExtractorService
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        name:       String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      val techniqueName = TechniqueName(name)
      response(
        restExtractor,
        "directives",
        Some(name)
      )(
        apiV6.listDirectives(techniqueName, None),
        req,
        s"Could not find list of directives based on '${techniqueName.value}' Technique"
      )("listTechniquesDirectives")
    }
  }

  object ListTechniqueDirectives extends LiftApiModuleString2 {
    val schema        = API.ListTechniqueDirectives
    val restExtractor = restExtractorService
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        nv:         (String, String),
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {

      val (techniqueName, version) = (TechniqueName(nv._1), nv._2)
      val directives               = TechniqueVersion.parse(version) match {
        case Right(techniqueVersion) =>
          apiV6.listDirectives(techniqueName, Some(techniqueVersion :: Nil))
        case Left(err)               =>
          Failure(
            s"Could not find list of directives based on '${techniqueName.value}' Technique, because we could not parse '${version}' as a valid technique version"
          )
      }
      response(
        restExtractor,
        "directives",
        Some(s"${techniqueName.value}/${version}")
      )(
        directives,
        req,
        s"Could not find list of directives based on version '${version}' of '${techniqueName.value}' Technique"
      )("listTechniqueDirectives")
    }
  }

  object ListTechniquesV14 extends LiftApiModule0 {
    val schema = API.ListTechniques
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      serviceV14.listTechniques.toLiftResponseList(params, schema)
    }
  }

  object ListTechniquesDirectivesV14 extends LiftApiModuleString {
    val schema = API.ListTechniquesDirectives
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        name:       String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      val techniqueName = TechniqueName(name)
      serviceV14.listDirectives(techniqueName, None).toLiftResponseList(params, schema)
    }
  }

  object ListTechniqueDirectivesV14 extends LiftApiModuleString2 {
    val schema = API.ListTechniqueDirectives
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        nv:         (String, String),
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      val (techniqueName, version) = (TechniqueName(nv._1), nv._2)

      val directives = TechniqueVersion.parse(version) match {
        case Right(techniqueVersion) =>
          serviceV14.listDirectives(techniqueName, Some(techniqueVersion :: Nil))
        case Left(error)             =>
          Inconsistency(
            s"Could not find list of directives based on '${techniqueName.value}' technique, because we could not parse '${version}' as a valid technique version"
          ).fail
      }
      directives.toLiftResponseList(params, schema)
    }
  }

  object GetTechniqueDetailsAllVersion extends LiftApiModuleString {
    val schema = API.GetTechniqueAllVersion
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        name:       String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      val techniqueName = TechniqueName(name)
      serviceV14.getTechniqueWithData(techniqueName, None).toLiftResponseList(params, schema)
    }
  }

  object GetTechnique extends LiftApiModuleString2 {
    val schema = API.GetTechnique
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        nv:         (String, String),
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      val (techniqueName, version) = (TechniqueName(nv._1), nv._2)

      val directives = TechniqueVersion.parse(version) match {
        case Right(techniqueVersion) =>
          serviceV14.getTechniqueWithData(techniqueName, Some(techniqueVersion))
        case Left(error)             =>
          Inconsistency(
            s"Could not find technique '${techniqueName.value}' details, because we could not parse '${version}' as a valid technique version"
          ).fail
      }
      directives.toLiftResponseList(params, schema)
    }
  }

  object TechniqueRevisions extends LiftApiModuleString2 {
    val schema = API.TechniqueRevisions
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        nv:         (String, String),
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      val (techniqueName, version) = (TechniqueName(nv._1), nv._2)

      val revisions = ParseVersion.parse(version) match {
        case Right(v)    =>
          serviceV14.techniqueRevisions(techniqueName, v)
        case Left(error) =>
          Inconsistency(
            s"Could not find list of directives based on '${techniqueName.value}' technique, because we could not parse '${version}' as a valid technique version: ${error}"
          ).fail
      }
      revisions.toLiftResponseList(params, schema)
    }
  }
}

class TechniqueAPIService6(
    readDirective:      RoDirectiveRepository,
    restDataSerializer: RestDataSerializer
) extends Loggable {

  def serialize(technique: Technique, directive: Directive) = restDataSerializer.serializeDirective(technique, directive, None)

  def listTechniques: Box[JValue] = {
    (for {
      lib             <- readDirective.getFullDirectiveLibrary()
      activeTechniques = lib.allActiveTechniques.values.toSeq
      serialized       = activeTechniques.map(restDataSerializer.serializeTechnique)
    } yield {
      serialized
    }).toBox.map(v => JArray(v.toList))
  }

  def listDirectives(techniqueName: TechniqueName, wantedVersions: Option[List[TechniqueVersion]]): Box[JValue] = {
    def serializeDirectives(
        directives:     Seq[Directive],
        techniques:     SortedMap[TechniqueVersion, Technique],
        wantedVersions: Option[List[TechniqueVersion]]
    ) = {
      val filter = (d: Directive) => {
        wantedVersions match {
          case None           => true
          case Some(versions) => versions.contains(d.techniqueVersion)
        }
      }

      ZIO.foreach(directives.filter(filter)) { directive =>
        techniques.get(directive.techniqueVersion) match {
          case None            =>
            Inconsistency(
              s"Version '${directive.techniqueVersion.serialize}' of Technique '${techniqueName.value}' does not exist, but is used by Directive '${directive.id.uid.value}'"
            ).fail
          case Some(technique) =>
            serialize(technique, directive).succeed
        }
      }
    }

    def checkWantedVersions(
        techniques:     SortedMap[TechniqueVersion, Technique],
        wantedVersions: Option[List[TechniqueVersion]]
    ) = {
      wantedVersions match {
        case Some(versions) =>
          ZIO.foreach(versions) { version =>
            ZIO.when(!techniques.keySet.contains(version)) {
              Inconsistency(s"Version '${version.serialize}' of Technique '${techniqueName.value}' does not exist").fail
            }
          }
        case None           => ZIO.unit
      }
    }

    (for {
      lib                  <- readDirective.getFullDirectiveLibrary()
      activeTech           <- lib.allActiveTechniques.values
                                .find(_.techniqueName == techniqueName)
                                .notOptional(
                                  s"Technique '${techniqueName.value}' does not exist"
                                )

      // Check if version we want exists in technique library, We don't need the result
      _                    <- checkWantedVersions(activeTech.techniques, wantedVersions)
      serializedDirectives <- serializeDirectives(activeTech.directives, activeTech.techniques, wantedVersions)
    } yield {
      serializedDirectives.toList
    }).toBox.map(v => JArray(v.toList))
  }

}

class TechniqueAPIService14(
    readDirective:       RoDirectiveRepository,
    techniqueRevisions:  TechniqueRevisionRepository,
    techniqueReader:     TechniqueReader,
    techniqueSerializer: TechniqueSerializer,
    restDataSerializer:  RestDataSerializer
) {

  def listTechniques: IOResult[Seq[JRActiveTechnique]] = {
    for {
      lib             <- readDirective.getFullDirectiveLibrary()
      activeTechniques = lib.allActiveTechniques.values.toSeq
      serialized       = activeTechniques.map(JRActiveTechnique.fromTechnique)
    } yield {
      serialized
    }
  }

  def listDirectives(techniqueName: TechniqueName, wantedVersions: Option[List[TechniqueVersion]]): IOResult[Seq[JRDirective]] = {
    def serializeDirectives(
        directives:     Seq[Directive],
        techniques:     SortedMap[TechniqueVersion, Technique],
        wantedVersions: Option[List[TechniqueVersion]]
    ): IOResult[Seq[JRDirective]] = {
      val filter = (d: Directive) => {
        wantedVersions match {
          case None           => true
          case Some(versions) => versions.contains(d.techniqueVersion)
        }
      }

      ZIO.foreach(directives.filter(filter)) { directive =>
        techniques.get(directive.techniqueVersion) match {
          case None            =>
            Inconsistency(
              s"Version ${directive.techniqueVersion.debugString} of Technique '${techniqueName.value}' does not exist, but is used by Directive '${directive.id.uid.value}'"
            ).fail
          case Some(technique) =>
            JRDirective.fromDirective(technique, directive, None).succeed
        }
      }
    }

    def checkWantedVersions(
        techniques:     SortedMap[TechniqueVersion, Technique],
        wantedVersions: Option[List[TechniqueVersion]]
    ) = {
      wantedVersions match {
        case Some(versions) =>
          ZIO.foreach(versions) { version =>
            ZIO.when(!techniques.keySet.contains(version)) {
              Inconsistency(s"Version '${version.debugString}' of Technique '${techniqueName.value}' does not exist").fail
            }
          }
        case None           => ZIO.unit
      }
    }

    for {
      lib                  <- readDirective.getFullDirectiveLibrary()
      activeTech           <- lib.allActiveTechniques.values
                                .find(_.techniqueName == techniqueName)
                                .notOptional(s"Technique '${techniqueName.value}' does not exist")

      // Check if version we want exists in technique library, We don't need the result
      _                    <- checkWantedVersions(activeTech.techniques, wantedVersions)
      serializedDirectives <- serializeDirectives(activeTech.directives, activeTech.techniques, wantedVersions)
    } yield {
      serializedDirectives
    }
  }

  /*
   * List available revision for given directive
   */
  def techniqueRevisions(name: TechniqueName, version: Version): IOResult[List[JRRevisionInfo]] = {
    techniqueRevisions.getTechniqueRevision(name, version).map(_.map(JRRevisionInfo.fromRevisionInfo))
  }

  def getTechniqueWithData(techniqueName: TechniqueName, version: Option[TechniqueVersion]) = {
    for {
      lib                          <- readDirective.getFullDirectiveLibrary()
      activeTechnique               = lib.allActiveTechniques.values.find(_.techniqueName == techniqueName).toSeq
      methods                      <- techniqueReader.getMethodsMetadata
      x                            <- techniqueReader.readTechniquesMetadataFile
      (techniques, methods, errors) = x
      _                            <- if (errors.isEmpty) ().succeed
                                      else {
                                        ApiLoggerPure.error(
                                          s"An error occurred while reading techniques when getting them: ${errors.map(_.msg).mkString("\n ->", "\n ->", "")}"
                                        )
                                      }

      json <- ZIO.foreach(
                activeTechnique.flatMap(at => {
                  version match {
                    case None    => at.techniques
                    case Some(v) => at.techniques.get(v).toSeq.map((v, _))
                  }
                })
              ) {
                case (version, technique) =>
                  techniques.find(t =>
                    t.id.value == technique.id.name.value && t.version.value == version.version.toVersionString
                  ) match {
                    case Some(editorTechnique) =>
                      import techniqueSerializer._
                      import zio.json._
                      editorTechnique.toJsonAST.map(_.merge(Json(("source", Str("editor"))))).toIO
                    case None                  =>
                      restDataSerializer.serializeTechnique(technique).succeed
                  }
              }
    } yield {
      json
    }
  }

  def getTechniquesWithData(): IOResult[Seq[Json]] = {
    for {
      lib                         <- readDirective.getFullDirectiveLibrary()
      activeTechniques             = lib.allActiveTechniques.values.toSeq
      res                         <- techniqueReader.readTechniquesMetadataFile
      (techniques, method, errors) = res
      _                           <- if (errors.isEmpty) ().succeed
                                     else {
                                       ApiLoggerPure.error(
                                         s"An error occurred while reading techniques when getting them: ${errors.map(_.msg).mkString("\n ->", "\n ->", "")}"
                                       )
                                     }
      json                        <- {
        ZIO.foreach(activeTechniques.flatMap(_.techniques)) {
          case (version, technique) =>
            techniques.find(t =>
              t.id.value == technique.id.name.value && t.version.value == version.version.toVersionString
            ) match {
              case Some(editorTechnique) =>
                import techniqueSerializer._
                import zio.json._
                editorTechnique.toJsonAST.map(_.merge(Json(("source", Str("editor"))))).toIO
              case None                  =>
                restDataSerializer.serializeTechnique(technique).succeed
            }
        }
      }
    } yield {
      json
    }

  }

}
