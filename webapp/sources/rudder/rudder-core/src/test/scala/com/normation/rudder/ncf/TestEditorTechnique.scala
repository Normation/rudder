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
package com.normation.rudder.ncf

import better.files.File
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.Version
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestEditorTechnique extends Specification {
  import ParameterType.*

  "BasicParameterTypeService" should {
    val service: ParameterTypeService = new BasicParameterTypeService

    "translate" >> {
      "DSC HereString" in {
        val translate = service.translate(_, ParameterType.HereString, AgentType.Dsc)
        "simple chain seq" in {
          translate("foo") must beRight(
            """@'
              |foo
              |'@""".stripMargin
          )
        }
        "with any non rudder interpolation" in {
          translate("foo ${prefix.var[foo]} bar") must beRight(
            """(@'
              |foo 
              |'@ + ([Rudder.Datastate]::Render('{{' + @'
              |vars.prefix.var.foo
              |'@ + '}}')) + @'
              | bar
              |'@)""".stripMargin
          )
        }
        "with rudder node interpolation" in {
          translate("foo ${rudder.node.foo.bar.baz} bar") must beRight(
            """(@'
              |foo 
              |'@ + ([Rudder.Datastate]::Render('{{' + @'
              |vars.rudder.node.foo.bar.baz
              |'@ + '}}')) + @'
              | bar
              |'@)""".stripMargin
          )
        }
        "with rudder parameter interpolation" in {
          translate("foo ${rudder.parameters[foo]} bar") must beRight(
            """(@'
              |foo 
              |'@ + ([Rudder.Datastate]::Render('{{' + @'
              |vars.rudder.param.foo
              |'@ + '}}')) + @'
              | bar
              |'@)""".stripMargin
          )
        }
        "with property interpolation" in {
          translate("foo ${node.properties[foo]} bar") must beRight(
            """(@'
              |foo 
              |'@ + ([Rudder.Datastate]::Render('{{' + @'
              |vars.node.properties.foo
              |'@ + '}}')) + @'
              | bar
              |'@)""".stripMargin
          )
        }
        "with rudder engine interpolation" in {
          translate("foo ${data.test[foo]} bar") must beRight(
            """(@'
              |foo 
              |'@ + ([Rudder.Datastate]::Render('{{' + @'
              |vars.data.test.foo
              |'@ + '}}')) + @'
              | bar
              |'@)""".stripMargin
          )
        }

        "with nested property path" in {
          translate("foo ${node.properties[foo][bar]} bar") must beRight(
            """(@'
              |foo 
              |'@ + ([Rudder.Datastate]::Render('{{' + @'
              |vars.node.properties.foo.bar
              |'@ + '}}')) + @'
              | bar
              |'@)""".stripMargin
          )
        }

        "with non-rudder var" in {
          translate("foo ${prefix.var} bar") must beRight(
            """(@'
              |foo 
              |'@ + ([Rudder.Datastate]::Render('{{' + @'
              |vars.prefix.var
              |'@ + '}}')) + @'
              | bar
              |'@)""".stripMargin
          )
        }

        "with non-rudder var multiple accessors and spaces" in {
          translate("foo ${prefix.var[foo bar][baz]} bar") must beRight(
            """(@'
              |foo 
              |'@ + ([Rudder.Datastate]::Render('{{' + @'
              |vars.prefix.var.foo bar.baz
              |'@ + '}}')) + @'
              | bar
              |'@)""".stripMargin
          )
        }

        "with node properties accessors containing dot by failing" in {
          translate("foo ${node.properties[ok.ko]} bar") must beLeft
        }

        "with non-rudder var interpolation accessor with allowed non-alphanum characters" in {
          translate("foo ${prefix.var[tëst😍 Ã¶emo😄ji-parameter]} bar") must beRight(
            """(@'
              |foo 
              |'@ + ([Rudder.Datastate]::Render('{{' + @'
              |vars.prefix.var.tëst😍 Ã¶emo😄ji-parameter
              |'@ + '}}')) + @'
              | bar
              |'@)""".stripMargin
          )
        }
      }
    }
  }

  "EditorTechnique" should {
    val category         = "ncf_techniques"
    val techniqueId      = "technique_id"
    val techniqueVersion = "1.0"

    "check technique ID consistency" in {
      // technique base dir must be in known editor technique path (techniques / cat / id / version)
      val base      = File(s"/base/techniques/${category}/${techniqueId}/${techniqueVersion}/")
      val technique = EditorTechnique(
        BundleName(techniqueId),
        Version(techniqueVersion),
        "Test technique in base-dir",
        category,
        List.empty,
        "",
        "",
        List.empty,
        List.empty,
        Map.empty,
        None
      )
      EditorTechnique.checkTechniqueIdConsistency(base, technique) must beRight(())
    }

    "invalidate technique outside dir" in {
      val base      = File(s"/base/techniques/wrong_path")
      val technique = EditorTechnique(
        BundleName(techniqueId),
        Version(techniqueVersion),
        "Test technique in base-dir",
        category,
        List.empty,
        "",
        "",
        List.empty,
        List.empty,
        Map.empty,
        None
      )
      EditorTechnique.checkTechniqueIdConsistency(base, technique) must beLeft
    }
  }
}
