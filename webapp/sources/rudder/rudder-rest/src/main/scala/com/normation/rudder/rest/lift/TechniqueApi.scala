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

import com.normation.rudder.apidata.RestDataSerializer
import com.normation.box._
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.errors._
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.xml.TechniqueRevisionRepository
import net.liftweb.common.Box
import net.liftweb.common.Failure
import net.liftweb.common.Loggable
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.json.JsonAST.JArray
import net.liftweb.json.JsonAST.JValue
import zio._
import zio.syntax._
import com.normation.rudder.rest.{TechniqueApi => API, _}
import com.normation.rudder.rest.RestUtils.response
import com.normation.rudder.apidata.JsonResponseObjects._
import com.normation.rudder.apidata.implicits._
import com.normation.rudder.rest.implicits._
import com.normation.utils.ParseVersion
import com.normation.utils.Version

import scala.collection.SortedMap



class TechniqueApi (
    restExtractorService: RestExtractorService
  , apiV6     : TechniqueAPIService6
  , serviceV14: TechniqueAPIService14
) extends LiftApiModuleProvider[API] {

  def schemas = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => e match {
      case API.ListTechniques           => ChooseApi0(ListTechniques          , ListTechniquesV14                )
      case API.ListTechniquesDirectives => ChooseApiN(ListTechniquesDirectives, ListTechniquesDirectivesV14)
      case API.ListTechniqueDirectives  => ChooseApiN(ListTechniqueDirectives , ListTechniqueDirectivesV14 )
      case API.TechniqueRevisions       => TechniqueRevisions
    }).toList
  }

  object ListTechniques extends LiftApiModule0 {
    val schema = API.ListTechniques
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      response(
        restExtractor
      , "techniques"
      , None
      )(
          apiV6.listTechniques
        , req
        , s"Could not find list of techniques"
       ) ("listTechniques")
    }
  }

  object ListTechniquesDirectives extends LiftApiModuleString {
    val schema = API.ListTechniquesDirectives
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, name: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val techniqueName = TechniqueName(name)
      response(
        restExtractor
      , "directives"
      , Some(name)
      )(
          apiV6.listDirectives(techniqueName, None)
        , req
        , s"Could not find list of directives based on '${techniqueName.value}' Technique"
       ) ("listTechniquesDirectives")
    }
  }

  object ListTechniqueDirectives extends LiftApiModuleString2 {
    val schema = API.ListTechniqueDirectives
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, nv: (String, String), req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {

      val (techniqueName, version) = (TechniqueName(nv._1), nv._2)
      val directives = TechniqueVersion.parse(version) match {
        case Right(techniqueVersion) =>
              apiV6.listDirectives(techniqueName, Some(techniqueVersion :: Nil))
        case Left(err) =>
          Failure(s"Could not find list of directives based on '${techniqueName.value}' Technique, because we could not parse '${version}' as a valid technique version")
      }
      response(
          restExtractor
        , "directives"
        , Some(s"${techniqueName.value}/${version}")
      ) ( directives
        , req
        , s"Could not find list of directives based on version '${version}' of '${techniqueName.value}' Technique"
      ) ("listTechniqueDirectives")
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
    def process(version: ApiVersion, path: ApiPath, name: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val techniqueName = TechniqueName(name)
      serviceV14.listDirectives(techniqueName, None).toLiftResponseList(params, schema)
    }
  }

  object ListTechniqueDirectivesV14 extends LiftApiModuleString2 {
    val schema = API.ListTechniqueDirectives
    def process(version: ApiVersion, path: ApiPath, nv: (String, String), req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val (techniqueName, version) = (TechniqueName(nv._1), nv._2)

      val directives = TechniqueVersion.parse(version) match {
        case Right(techniqueVersion) =>
          serviceV14.listDirectives(techniqueName, Some(techniqueVersion :: Nil))
        case Left(error) =>
          Inconsistency(s"Could not find list of directives based on '${techniqueName.value}' technique, because we could not parse '${version}' as a valid technique version").fail
      }
      directives.toLiftResponseList(params, schema)
    }
  }

  object TechniqueRevisions extends LiftApiModuleString2 {
    val schema = API.TechniqueRevisions
    def process(version: ApiVersion, path: ApiPath, nv: (String, String), req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val (techniqueName, version) = (TechniqueName(nv._1), nv._2)

      val revisions = ParseVersion.parse(version) match {
        case Right(v) =>
          serviceV14.techniqueRevisions(techniqueName, v)
        case Left(error) =>
          Inconsistency(s"Could not find list of directives based on '${techniqueName.value}' technique, because we could not parse '${version}' as a valid technique version: ${error}").fail
      }
      revisions.toLiftResponseList(params, schema)
    }
  }
}

class TechniqueAPIService6 (
    readDirective        : RoDirectiveRepository
  , restDataSerializer   : RestDataSerializer
  , techniqueRepository  : TechniqueRepository
  ) extends Loggable {

  def serialize(technique : Technique, directive:Directive) = restDataSerializer.serializeDirective(technique, directive, None)

  def listTechniques : Box[JValue] = {
    (for {
      lib              <- readDirective.getFullDirectiveLibrary()
      activeTechniques =  lib.allActiveTechniques.values.toSeq
      serialized       =  activeTechniques.map(restDataSerializer.serializeTechnique)
    } yield {
      serialized
    }).toBox.map(v => JArray(v.toList))
  }

  def listDirectives (techniqueName : TechniqueName, wantedVersions: Option[List[TechniqueVersion]])  : Box[JValue] = {
    def serializeDirectives (directives : Seq[Directive], techniques : SortedMap[TechniqueVersion, Technique], wantedVersions: Option[List[TechniqueVersion]] ) = {
      val filter = (d: Directive) => wantedVersions match {
        case None => true
        case Some(versions) => versions.contains(d.techniqueVersion)
      }

      ZIO.foreach(directives.filter(filter)) { directive =>
        techniques.get(directive.techniqueVersion) match {
          case None            =>
            Inconsistency(s"Version '${directive.techniqueVersion.serialize}' of Technique '${techniqueName.value}' does not exist, but is used by Directive '${directive.id.uid.value}'").fail
          case Some(technique) =>
            serialize(technique,directive).succeed
        }
     }
    }

    def checkWantedVersions( techniques : SortedMap[TechniqueVersion, Technique], wantedVersions: Option[List[TechniqueVersion]]) = {
      wantedVersions match {
        case Some(versions) =>
          ZIO.foreach(versions) { version =>
            ZIO.when(!techniques.keySet.contains(version)) {
              Inconsistency(s"Version '${version.serialize}' of Technique '${techniqueName.value}' does not exist").fail
            }
          }
        case None => UIO.unit
      }
    }

    (for {
      lib        <- readDirective.getFullDirectiveLibrary()
      activeTech <- lib.allActiveTechniques.values.find { _.techniqueName == techniqueName }.notOptional(s"Technique '${techniqueName.value}' does not exist")

      // Check if version we want exists in technique library, We don't need the result
      _                    <- checkWantedVersions(activeTech.techniques, wantedVersions)
      serializedDirectives <- serializeDirectives(activeTech.directives, activeTech.techniques, wantedVersions)
    } yield {
      serializedDirectives.toList
    }).toBox.map(v => JArray(v.toList))
  }

}

class TechniqueAPIService14 (
    readDirective      : RoDirectiveRepository
  , techniqueRepository: TechniqueRepository
  , techniqueRevisions : TechniqueRevisionRepository
  ) {

  def listTechniques: IOResult[Seq[JRActiveTechnique]] = {
    for {
      lib              <- readDirective.getFullDirectiveLibrary()
      activeTechniques =  lib.allActiveTechniques.values.toSeq
      serialized       =  activeTechniques.map(JRActiveTechnique.fromTechnique)
    } yield {
      serialized
    }
  }

  def listDirectives (techniqueName : TechniqueName, wantedVersions: Option[List[TechniqueVersion]]): IOResult[Seq[JRDirective]] = {
    def serializeDirectives (directives : Seq[Directive], techniques : SortedMap[TechniqueVersion, Technique], wantedVersions: Option[List[TechniqueVersion]] ): IOResult[Seq[JRDirective]] = {
      val filter = (d: Directive) => wantedVersions match {
        case None => true
        case Some(versions) => versions.contains(d.techniqueVersion)
      }

      ZIO.foreach(directives.filter(filter)) { directive =>
        techniques.get(directive.techniqueVersion) match {
          case None            =>
            Inconsistency(s"Version ${directive.techniqueVersion.debugString} of Technique '${techniqueName.value}' does not exist, but is used by Directive '${directive.id.uid.value}'").fail
          case Some(technique) =>
            JRDirective.fromDirective(technique, directive, None).succeed
        }
      }
    }

    def checkWantedVersions( techniques : SortedMap[TechniqueVersion, Technique], wantedVersions: Option[List[TechniqueVersion]]) = {
      wantedVersions match {
        case Some(versions) =>
          ZIO.foreach(versions) { version =>
            ZIO.when(!techniques.keySet.contains(version)) {
              Inconsistency(s"Version '${version.debugString}' of Technique '${techniqueName.value}' does not exist").fail
            }
          }
        case None => UIO.unit
      }
    }

    for {
      lib        <- readDirective.getFullDirectiveLibrary()
      activeTech <- lib.allActiveTechniques.values.find { _.techniqueName == techniqueName }.notOptional(s"Technique '${techniqueName.value}' does not exist")

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
}
