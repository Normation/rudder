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

package com.normation.templates.cli

import com.normation.templates.STVariable

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class JsonVariablesTest extends Specification {



  "A json file for variable" should {
    "correctly be parsed with different case of values" in {
      val json = """
         {
             "key1": true
           , "key2": "some value"
           , "key3": "42"
           , "key4": [ "some", "more", "values", true, false ]
           , "key5": { "value": "k5", "system": true, "optional": false }
           , "key6": { "value": [ "a1", "a2", "a3" ], "system": false, "optional": true }
           , "key7": ""
           , "key8": { "value": [] }
         }
        """
      val variables = Seq(
          STVariable("key1", true,  Seq(true), false)
        , STVariable("key2", true,  Seq("some value"), false)
        , STVariable("key3", true,  Seq("42"), false)
        , STVariable("key4", true,  Seq("some", "more", "values", true, false), false)
        , STVariable("key5", false, Seq("k5"), true)
        , STVariable("key6", true,  Seq("a1", "a2", "a3"), false)
        , STVariable("key7", true,  Seq(""), false)
        , STVariable("key8", true,  Seq(), false)
      )

      ParseVariables.fromString(json).openOrThrowException("Failed test!") must containTheSameElementsAs(variables)
    }
  }
}
