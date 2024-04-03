package com.normation.rudder.services.nodes
/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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

import com.normation.errors.IOResult
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.ncf.BundleName
import com.normation.rudder.ncf.EditorTechniqueReader
import com.normation.rudder.ncf.MethodBlock
import com.normation.rudder.ncf.MethodCall
import com.normation.rudder.ncf.MethodElem
import com.normation.rudder.repository.RoDirectiveRepository

class PropertyUsageService(readDirective: RoDirectiveRepository, techniqueReader: EditorTechniqueReader) {

  def findPropertyInDirective(propertyName: String): IOResult[List[(DirectiveId, String)]] = {
    for {
      fullLibrary <- readDirective.getFullDirectiveLibrary()
      atDirectives = fullLibrary.allDirectives.values.filter(!_._2.isSystem).map(_._2)
    } yield {
      atDirectives.filter { d =>
        val directivesParamValues = d.parameters.values.flatten.filter(p => p.contains(s"$${node.properties[$propertyName]"))
        directivesParamValues.nonEmpty
      }.map(d => (d.id, d.name)).toList
    }
  }

  def findPropertyInTechnique(propertyName: String): IOResult[List[(BundleName, String)]] = {
    def getParam(methods: MethodElem): List[String] = {
      methods match {
        case b: MethodBlock => b.calls.flatten(getParam)
        case c: MethodCall  => c.parameters.values.toList
      }
    }

    for {
      res               <- techniqueReader.readTechniquesMetadataFile
      (techniques, _, _) = res
    } yield {
      techniques.filter { t =>
        // I'm only checking in the value of method parameters here
        val methodParamValues = t.calls.flatMap(getParam).filter(p => p.contains(s"$${node.properties[$propertyName]"))
        methodParamValues.nonEmpty
      }.map(t => (t.id, t.name))
    }
  }
}
