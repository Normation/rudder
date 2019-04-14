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

import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.ApiVersion
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.RestDataSerializer
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest.RestUtils.response
import com.normation.rudder.rest.{TechniqueApi => API}
import com.normation.utils.Control.boxSequence
import com.normation.utils.Control.sequence
import net.liftweb.common.Box
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.json.JsonAST.JValue

import scala.collection.SortedMap
import scala.util.Success
import scala.util.Try
import scala.util.{Failure => TryFailure}
import com.normation.box._
import com.normation.errors._
import net.liftweb.json.JsonAST.JArray
import scalaz.zio._
import scalaz.zio.syntax._

class TechniqueApi (
    restExtractorService: RestExtractorService
  , apiV6               : TechniqueAPIService6
) extends LiftApiModuleProvider[API] {

  def schemas = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => e match {
      case API.ListTechniques           => ListTechniques
      case API.ListTechniquesDirectives => ListTechniquesDirectives
      case API.ListTechniqueDirectives  => ListTechniqueDirectives
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

  object ListTechniquesDirectives extends LiftApiModule {
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
        , s"Could not find list of directives based on '${techniqueName}' Technique"
       ) ("listTechniquesDirectives")
    }
  }

  object ListTechniqueDirectives extends LiftApiModule {
    val schema = API.ListTechniqueDirectives
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, nv: (String, String), req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {

      val (techniqueName, version) = (TechniqueName(nv._1), nv._2)
      val directives = Try(TechniqueVersion(version)) match {
        case Success(techniqueVersion) =>
              apiV6.listDirectives(techniqueName, Some(techniqueVersion :: Nil))
        case TryFailure(exception) =>
          Failure(s"Could not find list of directives based on '${techniqueName}' Technique, because we could not parse '${version}' as a valid technique version")
      }
      response(
          restExtractor
        , "directives"
        , Some(s"${techniqueName.value}/${version}")
      ) ( directives
        , req
        , s"Could not find list of directives based on version '${version}' of '${techniqueName}' Technique"
      ) ("listTechniqueDirectives")
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
            Unconsistancy(s"Version ${directive.techniqueVersion} of Technique '${techniqueName.value}' does not exist, but is used by Directive '${directive.id.value}'").fail
          case Some(technique) =>
            serialize(technique,directive).succeed
        }
     }
    }

    def checkWantedVersions( techniques : SortedMap[TechniqueVersion, Technique], wantedVersions: Option[List[TechniqueVersion]]) = {
      wantedVersions match {
        case Some(versions) =>
          ZIO.foreach(versions) { version =>
            if  (techniques.keySet.contains(version)) {
              UIO.unit
            } else {
              Unconsistancy(s"Version '${version}' of Technique '${techniqueName.value}' does not exist").fail
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
