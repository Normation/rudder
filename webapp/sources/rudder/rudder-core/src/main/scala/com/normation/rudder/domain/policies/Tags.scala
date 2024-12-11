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

import com.normation.errors.PureResult
import com.normation.errors.Unexpected
import io.scalaland.chimney.*
import io.scalaland.chimney.syntax.*
import zio.json.*

/**
 * Tags that apply on Rules and Directives
 * We do not warranty unicity of tags name, only on tuple (name, value)
 */

object Tag {
  implicit def tagName(value:  String): TagName  = TagName(value)
  implicit def tagValue(value: String): TagValue = TagValue(value)
}

final case class TagName(val value: String)  extends AnyVal
final case class TagValue(val value: String) extends AnyVal
final case class Tag(name: TagName, value: TagValue)

/**
 * We can have multiple Tags with same name - unicity is really on tuple (name, value)
 */
final case class Tags(tags: Set[Tag]) extends AnyVal {
  def map[A](f: Tag => A): Set[A] = {
    tags.map(f)
  }
}

object Tags {
  private case class JsonTag(key: String, value: String)
  implicit private val transformTag:     Transformer[Tag, JsonTag] = { case Tag(name, value) => JsonTag(name.value, value.value) }
  implicit private val transformJsonTag: Transformer[JsonTag, Tag] = {
    case JsonTag(name, value) => Tag(TagName(name), TagValue(value))
  }

  implicit private val transformTags:        Transformer[Tags, List[JsonTag]] = {
    case Tags(tags) => tags.toList.sortBy(_.name.value).map(_.transformInto[JsonTag])
  }
  implicit private val transformListJsonTag: Transformer[List[JsonTag], Tags] = {
    case list => Tags(list.map(_.transformInto[Tag]).toSet)
  }

  implicit private val codecJsonTag: JsonCodec[JsonTag] = DeriveJsonCodec.gen
  implicit val encoderTags:          JsonEncoder[Tags]  = JsonEncoder.list[JsonTag].contramap(_.transformInto[List[JsonTag]])
  implicit val decoderTags:          JsonDecoder[Tags]  = JsonDecoder.list[JsonTag].map(_.transformInto[Tags])

  // get tags from a list of key/value embodied by a Map with one elements (but
  // also works with several elements in map)
  def fromMaps(tags: List[Map[String, String]]): Tags = {
    Tags(tags.flatMap(_.map { case (k, v) => Tag(TagName(k), TagValue(v)) }).toSet)
  }

  val empty: Tags = Tags(Set())

  // we have a lot of cases where we parse an `Option[String]` into a `PureResult[Tags]` with
  // default value `Tags.empty`. The string format is:
  // [{"key":"k1","value":"v1"},{"key":"k2","value":"v2"}]
  def parse(opt: Option[String]): PureResult[Tags] = {
    opt match {
      case Some(v) => v.fromJson[Tags].left.map(Unexpected.apply)
      case None    => Right(Tags.empty)
    }
  }
}
