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
package com.normation.rudder.domain.policies

import net.liftweb.common.*
import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.json.*

@RunWith(classOf[JUnitRunner])
class TagsTest extends Specification with Loggable {
  def createTag(name: String): Tag = {
    import Tag.*
    Tag(name, name + "-value")
  }

  val tag1: Tag = createTag("tag1")
  val tag2: Tag = createTag("tag2")
  val tag3: Tag = createTag("tag3")

  val simpleTags: Tags = Tags(Set[Tag](tag1, tag2, tag3))
  val simpleSerialization =
    """[{"key":"tag1","value":"tag1-value"},{"key":"tag2","value":"tag2-value"},{"key":"tag3","value":"tag3-value"}]"""

  val invalidSerialization = """[{"key" :"tag1"},{"key" :"tag2", "value":"tag2-value"},{"key" :"tag3", "value":"tag3-value"}]"""

  val duplicatedSerialization =
    """[{"key" :"tag2", "value":"tag2-value"},{"key" :"tag2", "value":"tag2-value"},{"key" :"tag3", "value":"tag3-value"},{"key" : "tag1", "value": "tag1-value"}]"""

  "Serializing and unserializing" should {
    "serialize in correct JSON" in {
      simpleTags.toJson must equalTo(simpleSerialization)
    }

    "unserialize correct JSON" in {
      simpleSerialization.fromJson[Tags] must beRight(simpleTags)
    }

    "fail to unserialize incorrect JSON" in {
      invalidSerialization.fromJson[Tags] must beLeft
    }

    "unserialize duplicated entries in JSON into uniques" in {
      duplicatedSerialization.fromJson[Tags] must beRight(simpleTags)
    }
  }

}
