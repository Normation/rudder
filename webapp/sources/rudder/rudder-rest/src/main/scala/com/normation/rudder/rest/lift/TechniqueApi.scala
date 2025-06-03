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
import com.normation.cfclerk.domain.{BundleName as _, *}
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.errors.*
import com.normation.eventlog.ModificationId
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.apidata.JsonResponseObjects.*
import com.normation.rudder.apidata.implicits.*
import com.normation.rudder.config.ReasonBehavior
import com.normation.rudder.config.UserPropertyService
import com.normation.rudder.domain.logger.ApiLoggerPure
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.ncf.*
import com.normation.rudder.ncf.yaml.YamlTechniqueSerializer
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.xml.TechniqueRevisionRepository
import com.normation.rudder.rest.{TechniqueApi as API, *}
import com.normation.rudder.rest.implicits.*
import com.normation.rudder.rest.lift.TechniqueApi.QueryFormat
import com.normation.utils.FileUtils
import com.normation.utils.ParseVersion
import com.normation.utils.StringUuidGenerator
import com.normation.utils.Version
import net.liftweb.common.*
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import scala.collection.SortedMap
import zio.*
import zio.json.ast.*
import zio.json.yaml.*
import zio.syntax.*

object TechniqueApi {
  sealed trait QueryFormat
  object QueryFormat {
    def parse(s: String): QueryFormat = {
      s.toLowerCase match {
        case "yaml" => Yaml
        case "json" => Json
        case _      => Json // default to json for now
      }
    }
    case object Json extends QueryFormat
    case object Yaml extends QueryFormat
  }
}

class TechniqueApi(
    service:             TechniqueAPIService14,
    techniqueWriter:     TechniqueWriter,
    techniqueReader:     EditorTechniqueReader,
    techniqueRepository: TechniqueRepository,
    techniqueSerializer: TechniqueSerializer,
    uuidGen:             StringUuidGenerator,
    userPropertyService: UserPropertyService,
    resourceFileService: ResourceFileService,
    configRepoPath:      String
) extends LiftApiModuleProvider[API] {

  import com.normation.rudder.rest.lift.TechniqueApi.*
  import zio.json.*
  import zio.json.yaml.*

  implicit def reasonBehavior: ReasonBehavior = userPropertyService.reasonsFieldBehavior

  def schemas: ApiModuleProvider[API] = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map {
      case API.GetTechniques             => GetTechniques
      case API.ListTechniques            => ListTechniques
      case API.ListTechniquesDirectives  => ListTechniquesDirectives
      case API.ListTechniqueDirectives   => ListTechniqueDirectives
      case API.TechniqueRevisions        => TechniqueRevisions
      case API.UpdateTechnique           => UpdateTechnique
      case API.CreateTechnique           => CreateTechnique
      case API.GetResources              => new GetResources[API.GetResources.type](newTechnique = false, schema = API.GetResources)
      case API.GetNewResources           =>
        new GetResources[API.GetNewResources.type](newTechnique = true, schema = API.GetNewResources)
      case API.DeleteTechnique           => DeleteTechnique
      case API.GetMethods                => GetMethods
      case API.UpdateMethods             => UpdateMethods
      case API.UpdateTechniques          => UpdateTechniques
      case API.GetAllTechniqueCategories => GetAllTechniqueCategories
      case API.GetTechniqueAllVersion    => GetTechniqueDetailsAllVersion
      case API.GetTechnique              => GetTechnique
      case API.CheckTechnique            => CheckTechnique
      case API.CopyResourcesWhenCloning  => CopyResourcesWhenCloning
    }
  }

  class GetResources[T <: TwoParam](newTechnique: Boolean, val schema: T) extends LiftApiModule {

    def process(
        version:       ApiVersion,
        path:          ApiPath,
        techniqueInfo: (String, String),
        req:           Req,
        params:        DefaultParams,
        authzToken:    AuthzToken
    ): LiftResponse = {

      import zio.syntax.*

      def serializeResourceWithState(resource: ResourceFile): Json.Obj = {
        Json.Obj(("path", Json.Str(resource.path)), ("state", Json.Str(resource.state.value)))
      }

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
         }).map(_.map(serializeResourceWithState))
      }

      resources.toLiftResponseList(params, schema)
    }
  }

  object CopyResourcesWhenCloning extends LiftApiModule {
    val schema: API.CopyResourcesWhenCloning.type = API.CopyResourcesWhenCloning

    private def extractString(key: String)(req: Req): PureResult[Option[String]] = {
      req.params.get(key) match {
        case None              => Right(None)
        case Some(head :: Nil) => Right(Some(head))
        case Some(list)        =>
          Left(Inconsistency(s"${list.size} values defined for '${key}' parameter, only one needs to be defined"))
      }
    }

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        draftInfo:  (String, String),
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      (for {
        techniqueId <- extractString("techniqueId")(req).toIO.notOptional("technique id parameter is missing")
        category    <- extractString("category")(req).toIO.notOptional("category parameter is missing")
        _           <- resourceFileService.cloneResourcesFromTechnique(draftInfo._1, techniqueId, draftInfo._2, category)
      } yield {
        "ok"
      }).toLiftResponseOne(params, schema, _ => Some(draftInfo._1))
    }
  }

  object DeleteTechnique extends LiftApiModule {
    val schema: TwoParam = API.DeleteTechnique

    private def extractBoolean(key: String)(req: Req): PureResult[Option[Boolean]] = {
      req.params.get(key) match {
        case None              => Right(None)
        case Some(head :: Nil) =>
          try {
            Right(Some(head.toBoolean))
          } catch {
            case e: Throwable =>
              Left(
                Inconsistency(
                  s"Parsing request parameter '${key}' as a boolean failed, current value is '${head}'. Error message is: '${e.getMessage}'."
                )
              )
          }
        case Some(list)        => Left(Inconsistency(s"${list.size} values defined for 'id' parameter, only one needs to be defined"))
      }
    }

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
          force <- extractBoolean("force")(req).map(_.getOrElse(false)).toIO
          _     <- techniqueWriter.deleteTechnique(techniqueInfo._1, techniqueInfo._2, force, modId, authzToken.qc)
        } yield {
          Json.Obj(("id", Json.Str(techniqueInfo._1)), ("version" -> Json.Str(techniqueInfo._2)))
        }
      }

      content.toLiftResponseOne(params, schema, _ => Some(techniqueInfo._1))
    }
  }

  object UpdateTechnique extends LiftApiModuleString2 {
    val schema: TwoParam = API.UpdateTechnique

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        nv:         (String, String),
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      val modId = ModificationId(uuidGen.newUuid)
      import techniqueSerializer.*

      def charset: String = RestUtils.getCharset(req)
      // end copy
      val response = {
        for {
          technique        <-
            req.body match {
              case eb: EmptyBox => Unexpected((eb ?~! "error when accessing request body").messageChain).fail
              case Full(bytes) => new String(bytes, charset).fromJson[EditorTechnique].toIO
            }
          _                <- techniqueReader.getMethodsMetadata
          updatedTechnique <- techniqueWriter.writeTechniqueAndUpdateLib(technique, modId, authzToken.qc.actor)
          json             <- service.getTechniqueJson(updatedTechnique)
        } yield {
          json
        }
      }
      response.toLiftResponseOne(params, schema, _ => None)
    }
  }

  object GetTechniques extends LiftApiModule0 {
    val schema: API.GetTechniques.type = API.GetTechniques

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      service.getTechniquesWithData().toLiftResponseList(params, schema)
    }

  }

  object GetMethods extends LiftApiModule0 {
    val schema: API.GetMethods.type = API.GetMethods

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val response = for {
        methods <- techniqueReader.getMethodsMetadata
        sorted   = methods.toList.sortBy(_._1.value)
      } yield {
        // ported from pre-9.0, this is very strange and not normalized
        Json.Obj(("methods", Json.Obj(sorted.map(m => (m._1.value, techniqueSerializer.serializeMethodMetadata(m._2)))*)))
      }

      response.toLiftResponseOne(params, schema, None)
    }
  }

  object UpdateMethods extends LiftApiModule0 {
    val schema: API.UpdateMethods.type = API.UpdateMethods

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val response = for {
        _       <- techniqueReader.updateMethodsMetadataFile
        methods <- techniqueReader.getMethodsMetadata
      } yield {
        Json.Obj(methods.toList.map(m => (m._1.value, techniqueSerializer.serializeMethodMetadata(m._2)))*)
      }
      response.toLiftResponseOne(params, schema, None)
    }
  }

  object UpdateTechniques extends LiftApiModule0 {
    val schema: API.UpdateTechniques.type = API.UpdateTechniques
    import techniqueSerializer.*

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val modId    = ModificationId(uuidGen.newUuid)
      val response = for {
        res                    <- techniqueReader.readTechniquesMetadataFile
        (techniques, _, errors) = res
        _                      <- if (errors.isEmpty) ().succeed
                                  else {
                                    ApiLoggerPure.error(
                                      s"An error occurred while reading techniques when updating them: ${errors.map(_.msg).mkString("\n ->", "\n ->", "")}"
                                    )
                                  }
        res                    <- techniqueWriter.writeTechniques(techniques, modId, authzToken.qc.actor)
        json                   <- ZIO.foreach(res)(_.toJsonAST.toIO)
      } yield {
        json
      }
      response.toLiftResponseList(params, schema)

    }

  }

  object GetAllTechniqueCategories extends LiftApiModule0 {

    val schema: API.GetAllTechniqueCategories.type = API.GetAllTechniqueCategories

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val response = {
        val categories = techniqueRepository.getAllCategories
        def serializeTechniqueCategory(t: TechniqueCategory): Json.Obj = {
          val subs = Chunk.fromIterable(t.subCategoryIds.flatMap(categories.get).map(serializeTechniqueCategory))
          val name = t match {
            case t: RootTechniqueCategory => "/"
            case _ => t.name
          }
          Json.Obj(
            ("name", Json.Str(name)),
            ("path", Json.Str(t.id.getPathFromRoot.tail.map(_.value).mkString("/"))),
            ("id", Json.Str(t.id.name.value)),
            ("subCategories", Json.Arr(subs))
          )
        }
        Json.Obj(("techniqueCategories", serializeTechniqueCategory(techniqueRepository.getTechniqueLibrary)))
      }

      response.succeed.toLiftResponseOne(params, schema, None)
    }
  }

  object CreateTechnique extends LiftApiModule0 {

    import techniqueSerializer.*

    private def moveRessources(technique: EditorTechnique, internalId: String): IOResult[Unit] = {

      val base = File(configRepoPath)

      for {
        workspaceDir <-
          FileUtils.checkSanitizedIsIn(base, base / "workspace" / internalId / technique.version.value / "resources")
        finalDir     <- FileUtils.checkSanitizedIsIn(
                          base,
                          base / "techniques" / technique.category / technique.id.value / technique.version.value / "resources"
                        )
        _            <- ZIO
                          .whenZIO(IOResult.attempt(workspaceDir.exists)) {
                            IOResult.attempt("Error when moving resource file from workspace to final destination") {
                              finalDir.createDirectoryIfNotExists(createParents = true)
                              workspaceDir.moveTo(finalDir)(File.CopyOptions.apply(true))
                              workspaceDir.parent.parent.delete()
                            }
                          }
      } yield ()
    }

    // Comparison on technique name and ID are not case-sensitive
    private def isTechniqueNameExist(techniqueName: String) = {
      val techniques = techniqueRepository.getAll()
      techniques.values.map(_.name.toLowerCase).toList.contains(techniqueName.toLowerCase)
    }
    private def isTechniqueIdExist(bundleName: BundleName)  = {
      val techniques = techniqueRepository.getAll()
      techniques.keySet.map(_.name.value.toLowerCase).contains(bundleName.value.toLowerCase)
    }

    val schema: API.CreateTechnique.type = API.CreateTechnique

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val modId = ModificationId(uuidGen.newUuid) // copied from `Req.forcedBodyAsJson`

      def charset: String = RestUtils.getCharset(req)

      // end copy
      val response = {
        for {
          technique   <-
            req.body match {
              case eb: EmptyBox => Unexpected((eb ?~! "error when accessing request body").messageChain).fail
              case Full(bytes) => new String(bytes, charset).fromJson[EditorTechnique].toIO
            }
          isNameTaken  = isTechniqueNameExist(technique.name)
          isIdTaken    = isTechniqueIdExist(technique.id)
          _           <- (isNameTaken, isIdTaken) match {
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
          _           <- technique.internalId.map(internalId => moveRessources(technique, internalId)).getOrElse("Ok".succeed)
          updatedTech <- techniqueWriter.writeTechniqueAndUpdateLib(technique, modId, authzToken.qc.actor)
          json        <- service.getTechniqueJson(updatedTech)
        } yield {
          json
        }
      }
      response.toLiftResponseOne(params, schema, _ => None)
    }
  }

  object CheckTechnique extends LiftApiModule0 {
    val schema: API.CheckTechnique.type = API.CheckTechnique

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      def charset: String = RestUtils.getCharset(req)

      val input  = req.params.get("input").flatMap(_.map(QueryFormat.parse).headOption).getOrElse(QueryFormat.Json)
      val output = req.params.get("output").flatMap(_.map(QueryFormat.parse).headOption).getOrElse(QueryFormat.Json)
      val response: IOResult[Json] = {
        for {
          content   <-
            req.body match {
              case eb: EmptyBox => Unexpected((eb ?~! "error when accessing request body").messageChain).fail
              case Full(bytes) => new String(bytes, charset).succeed
            }
          technique <- {
            input match {
              case QueryFormat.Yaml =>
                import com.normation.rudder.ncf.yaml.YamlTechniqueSerializer.*
                content.fromYaml[EditorTechnique].toIO
              case QueryFormat.Json =>
                import techniqueSerializer.*
                content.fromJson[EditorTechnique].toIO
            }
          }
          response  <- {
            output match {
              case QueryFormat.Yaml =>
                import com.normation.rudder.ncf.yaml.YamlTechniqueSerializer.*
                technique.toYaml().map(yaml => Json(("output", Json.Str(yaml)))).toIO
              case QueryFormat.Json =>
                import techniqueSerializer.*
                technique.toJsonAST.toIO
            }
          }
        } yield {
          response
        }
      }

      response.toLiftResponseOne(params, schema, _ => None)
    }
  }

  object ListTechniques extends LiftApiModule0 {
    val schema: API.ListTechniques.type = API.ListTechniques

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      service.listTechniques.toLiftResponseList(params, schema)
    }
  }

  object ListTechniquesDirectives extends LiftApiModuleString {
    val schema: API.ListTechniquesDirectives.type = API.ListTechniquesDirectives

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        name:       String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      val techniqueName = TechniqueName(name)
      service.listDirectives(techniqueName, None).toLiftResponseList(params, schema)
    }
  }

  object ListTechniqueDirectives extends LiftApiModuleString2 {
    val schema: API.ListTechniqueDirectives.type = API.ListTechniqueDirectives

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
          service.listDirectives(techniqueName, Some(techniqueVersion :: Nil))
        case Left(error)             =>
          Inconsistency(
            s"Could not find list of directives based on '${techniqueName.value}' technique, because we could not parse '${version}' as a valid technique version"
          ).fail
      }
      directives.toLiftResponseList(params, schema)
    }
  }

  object GetTechniqueDetailsAllVersion extends LiftApiModuleString {
    val schema: API.GetTechniqueAllVersion.type = API.GetTechniqueAllVersion

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        name:       String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      val techniqueName = TechniqueName(name)
      service.getTechniqueWithData(techniqueName, None, QueryFormat.Json).toLiftResponseList(params, schema)
    }
  }

  object GetTechnique extends LiftApiModuleString2 {
    val schema: API.GetTechnique.type = API.GetTechnique

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        nv:         (String, String),
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      val (techniqueName, version) = (TechniqueName(nv._1), nv._2)

      val format: QueryFormat =
        req.params.get("format").flatMap(_.map(QueryFormat.parse).headOption).getOrElse(QueryFormat.Json)
      val json = TechniqueVersion.parse(version) match {
        case Right(techniqueVersion) =>
          service.getTechniqueWithData(techniqueName, Some(techniqueVersion), format)
        case Left(error)             =>
          Inconsistency(
            s"Could not find technique '${techniqueName.value}' details, because we could not parse '${version}' as a valid technique version"
          ).fail
      }
      json.toLiftResponseList(params, schema)
    }
  }

  object TechniqueRevisions extends LiftApiModuleString2 {
    val schema: API.TechniqueRevisions.type = API.TechniqueRevisions

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
          service.techniqueRevisions(techniqueName, v)
        case Left(error) =>
          Inconsistency(
            s"Could not find list of directives based on '${techniqueName.value}' technique, because we could not parse '${version}' as a valid technique version: ${error}"
          ).fail
      }
      revisions.toLiftResponseList(params, schema)
    }
  }
}

class TechniqueAPIService14(
    readDirective:       RoDirectiveRepository,
    techniqueRevisions:  TechniqueRevisionRepository,
    techniqueReader:     EditorTechniqueReader,
    techniqueSerializer: TechniqueSerializer,
    techniqueCompiler:   TechniqueCompiler
) {

  private def serializeTechnique(technique: Technique): Json = {
    zio.json.ast.Json(
      ("name"    -> Json.Str(technique.name)),
      ("id"      -> Json.Str(technique.id.name.value)),
      ("version" -> Json.Str(technique.id.version.serialize)),
      ("source"  -> Json.Str("built-in"))
    )
  }

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

  def getTechniqueJson(editorTechnique: EditorTechnique): IOResult[Json] = {
    import zio.json.*
    import com.normation.rudder.ncf.TechniqueCompilationIO.codecTechniqueCompilationOutput

    techniqueCompiler
      .getCompilationOutput(editorTechnique)
      .flatMap {
        case None    => None.succeed
        case Some(x) => x.toJsonAST.toIO.map(Some.apply)
      }
      .catchAll(err =>
        (Some(Json.Str(s"Compilation error with technique ${editorTechnique.id.value}: ${err.fullMsg}")): Option[Json]).succeed
      )
      .flatMap(output => techniqueSerializer.serializeEditorTechnique(editorTechnique, output).toIO)
  }

  def getTechniqueWithData(
      techniqueName: TechniqueName,
      version:       Option[TechniqueVersion],
      format:        TechniqueApi.QueryFormat
  ): ZIO[Any, RudderError, Seq[Json]] = {
    for {
      lib                    <- readDirective.getFullDirectiveLibrary()
      activeTechnique         = lib.allActiveTechniques.values.find(_.techniqueName == techniqueName).toSeq
      x                      <- techniqueReader.readTechniquesMetadataFile
      (techniques, _, errors) = x
      _                      <- if (errors.isEmpty) ().succeed
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
                      format match {
                        case QueryFormat.Yaml =>
                          import YamlTechniqueSerializer.*
                          editorTechnique.toYaml().map(s => Json(("content", Json.Str(s)))).toIO
                        case QueryFormat.Json =>
                          getTechniqueJson(editorTechnique)
                      }
                    case None                  =>
                      serializeTechnique(technique).succeed
                  }
              }
    } yield {
      json
    }
  }

  def getTechniquesWithData(): IOResult[Seq[Json]] = {
    for {
      lib                    <- readDirective.getFullDirectiveLibrary()
      activeTechniques        = lib.allActiveTechniques.values.toSeq
      res                    <- techniqueReader.readTechniquesMetadataFile
      (techniques, _, errors) = res
      _                      <- if (errors.isEmpty) ().succeed
                                else {
                                  ApiLoggerPure.error(
                                    s"An error occurred while reading techniques when getting them: ${errors.map(_.msg).mkString("\n ->", "\n ->", "")}"
                                  )
                                }
      json                   <- {
        ZIO.foreach(activeTechniques.flatMap(_.techniques)) {
          case (version, technique) =>
            techniques.find(t =>
              t.id.value == technique.id.name.value && t.version.value == version.version.toVersionString
            ) match {
              case Some(editorTechnique) =>
                getTechniqueJson(editorTechnique)
              case None                  =>
                serializeTechnique(technique).succeed
            }
        }
      }
    } yield {
      json
    }
  }

}
