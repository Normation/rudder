/*
*************************************************************************************
* Copyright 2016 Normation SAS
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

package com.normation.rudder.web.rest.technique

import scala.annotation.migration
import scala.collection.SortedMap

import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.web.rest.RestDataSerializer
import com.normation.utils.Control.boxSequence
import com.normation.utils.Control.sequence

import net.liftweb.common.Box
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.json.JsonAST.JValue
import net.liftweb.json.JsonDSL.seq2jvalue

case class TechniqueAPIService6 (
    readDirective        : RoDirectiveRepository
  , restDataSerializer   : RestDataSerializer
  , techniqueRepository  : TechniqueRepository
  ) extends Loggable {

  def serialize(technique : Technique, directive:Directive) = restDataSerializer.serializeDirective(technique, directive, None)

  def listTechniques : Box[JValue] = {
    for {
      lib <- readDirective.getFullDirectiveLibrary()
      activeTechniques = lib.allActiveTechniques.values.toSeq

      serialized = activeTechniques.map(restDataSerializer.serializeTechnique)
    } yield {
      serialized
    }
  }

  def listDirectives (techniqueName : TechniqueName, wantedVersions: Option[List[TechniqueVersion]])  : Box[JValue] = {
    def serializeDirectives (directives : Seq[Directive], techniques : SortedMap[TechniqueVersion, Technique], wantedVersions: Option[List[TechniqueVersion]] ) = {
     for {
       directive <- directives
       if ( wantedVersions match {
         case None => true
         case Some(versions) => versions.contains(directive.techniqueVersion)
       })
     } yield {
      techniques.get(directive.techniqueVersion) match {
        case None => Failure(s"Version ${directive.techniqueVersion} of Technique '${techniqueName.value}' does not exist, but is used by Directive '${directive.id.value}'")
        case Some(technique) => Full(serialize(technique,directive))
      }
     }
    }

    def checkWantedVersions( techniques : SortedMap[TechniqueVersion, Technique], wantedVersions: Option[List[TechniqueVersion]]) = {
      wantedVersions match {
        case Some(versions) =>
          sequence(versions) { version =>
            if  (techniques.keySet.contains(version)) {
              Full("ok")
            } else {
              Failure(s"Version '${version}' of Technique '${techniqueName.value}' does not exist" )
            }
          }
        case None => Full("ok")
      }
    }

    for {
      lib        <- readDirective.getFullDirectiveLibrary()
      activeTech <- Box(lib.allActiveTechniques.values.find { _.techniqueName == techniqueName }) ?~! s"Technique '${techniqueName.value}' does not exist"

      // Check if version we want exists in technique library, We don't need the result
      _                    <- checkWantedVersions(activeTech.techniques, wantedVersions)
      serializedDirectives <- boxSequence(serializeDirectives(activeTech.directives, activeTech.techniques, wantedVersions))
    } yield {
      serializedDirectives.toList
    }
  }

}
