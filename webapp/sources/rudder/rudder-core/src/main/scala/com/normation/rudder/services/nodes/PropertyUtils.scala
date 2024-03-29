package com.normation.rudder.services.nodes

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
