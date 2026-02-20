/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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

import better.files.Resource
import com.normation.JsonSpecMatcher
import com.normation.errors.IOResult
import com.normation.rudder.ncf.EditorTechnique
import com.normation.rudder.ncf.ParameterType.BasicParameterTypeService
import com.normation.rudder.ncf.ResourceFile
import com.normation.rudder.ncf.ResourceFileService
import com.normation.rudder.ncf.ResourceFileState
import com.normation.rudder.ncf.TechniqueSerializer
import com.normation.rudder.ncf.yaml.YamlTechniqueSerializer
import com.normation.zio.*
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import zio.json.*
import zio.json.ast.Json.*
import zio.syntax.*

@RunWith(classOf[JUnitRunner])
class EditorTechniqueSerialisationTest extends Specification with JsonSpecMatcher {

  private val resources = new ResourceFileService {
    override def getResources(technique: EditorTechnique): IOResult[List[ResourceFile]] = {
      if (technique.id.value == "a_simple_yaml_technique")
        List(ResourceFile("/some/path/from/ResourceFileService/something.txt", ResourceFileState.Untouched)).succeed
      else Nil.succeed
    }

    override def getResourcesFromDir(
        resourcesPath:    String,
        techniqueName:    String,
        techniqueVersion: String
    ): IOResult[List[ResourceFile]] = ???

    override def cloneResourcesFromTechnique(
        draftId:          String,
        techniqueId:      String,
        techniqueVersion: String,
        category:         String
    ): IOResult[Unit] = ???
  }

  private val yaml       = new YamlTechniqueSerializer(resources)
  private val serializer = new TechniqueSerializer(new BasicParameterTypeService())

  "Serialising an editor technique with resource" >> {

    val s = Resource.getAsString("configuration-repository/techniques/ncf_techniques/a_simple_yaml_technique/1.0/technique.yml")

    val t = yaml.yamlToEditorTechnique(s).runNow.toOption.get

    val json = serializer.serializeEditorTechnique(t, None).toOption.get

    json.toJson must equalsJsonSemantic("""{
                                          |  "id":"a_simple_yaml_technique",
                                          |  "version":"1.0",
                                          |  "name":"A simple yaml technique",
                                          |  "category":"ncf_techniques",
                                          |  "calls":[
                                          |    {
                                          |      "type": "call",
                                          |        "method":"file_content",
                                          |        "id":"bfe1978f-b0e7-4da3-9544-06d53eb985fa",
                                          |        "parameters":{
                                          |          "path":"/tmp/test-rudder.txt",
                                          |          "lines":"Hello World!",
                                          |          "enforce":"true"
                                          |        },
                                          |        "condition":"",
                                          |        "component":"",
                                          |        "disabledReporting":false
                                          |    }
                                          |  ],
                                          |  "description":"",
                                          |  "documentation":"",
                                          |  "parameters":[
                                          |
                                          |  ],
                                          |  "resources":[
                                          |    {
                                          |      "path":"/some/path/from/ResourceFileService/something.txt",
                                          |      "state":"untouched"
                                          |    }
                                          |  ],
                                          |  "tags":{
                                          |
                                          |  },
                                          |  "source":"editor"
                                          |}
                                          |""".stripMargin)

  }

}
