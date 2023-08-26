/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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

package com.normation.rudder.ncf.migration

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/*
 * Test that the serialisation is correctly able to read ncf lib
 */
@RunWith(classOf[JUnitRunner])
class TestMigrateOldTechniques extends Specification {

  implicit class ForceGetEither[A, B](either: Either[A, B]) {
    def forceGet: B = {
      either match {
        case Left(err) => throw new IllegalArgumentException(s"Test in error: ${err}")
        case Right(v)  => v
      }
    }
  }

  s"We should be able to migrate a simple JSON techniques" >> {
    val json = {
      """
        |{
        |  "id":"test_import_export_archive",
        |  "version":"1.0",
        |  "category":"ncf_techniques",
        |  "description":"",
        |  "name":"test import/export archive",
        |  "calls":[
        |    {
        |      "method":"command_execution",
        |      "condition":"",
        |      "disableReporting":false,
        |      "component":"Command execution",
        |      "parameters":[
        |        {
        |          "name":"command",
        |          "value":"touch /tmp/toto"
        |        }
        |      ],
        |      "id":"483b4b60-f940-4b65-834a-4d8ddd085c34"
        |    }
        |  ],
        |  "parameter":[],
        |  "resources":[],
        |  "source":"editor"
        |}""".stripMargin
    }

    val yaml = {
      """id: test_import_export_archive
        |name: test import/export archive
        |version: '1.0'
        |items:
        |  - id: 483b4b60-f940-4b65-834a-4d8ddd085c34
        |    name: Command execution
        |    method: command_execution
        |    params:
        |      command: touch /tmp/toto
        |category: ncf_techniques
        |""".stripMargin
    }

    MigrateOldTechniquesService.toYaml(json).forceGet === yaml
  }

}
