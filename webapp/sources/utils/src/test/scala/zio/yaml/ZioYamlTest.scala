/*
 *************************************************************************************
 * Copyright 2019 Normation SAS
 *************************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *************************************************************************************
 */

/*
 * This class provides common usage for Zio
 */

package zio.yaml

import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.JUnitRunner
import zio.json.*
import zio.json.yaml.*

// test data
final case class Param(id: String, mandatory: Boolean)
final case class Container(id: String, version: Int, params: Seq[Param])

@RunWith(classOf[JUnitRunner])
class ZioYamlTest extends Specification {

  implicit val codecParam:           JsonCodec[Param]     = DeriveJsonCodec.gen
  implicit val codecSimpleContainer: JsonCodec[Container] = DeriveJsonCodec.gen

  val c: Container = Container("foo", 42, List(Param("p1", true)))

  "demonstrating correct encoding with our ZIO Yaml" >> {

    c.toYaml() must beEqualTo(
      Right(
        """id: foo
          |version: 42
          |params:
          |  - id: p1
          |    mandatory: true
          |""".stripMargin
      )
    )

  }
}
