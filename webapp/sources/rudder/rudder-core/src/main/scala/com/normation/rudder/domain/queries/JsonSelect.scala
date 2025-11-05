/*
 *************************************************************************************
 * Copyright 2018 Normation SAS
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

package com.normation.rudder.domain.queries

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.DocumentContext
import com.jayway.jsonpath.JsonPath
import net.liftweb.common.Box
import net.liftweb.common.Empty
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.util.Helpers.tryo
import net.minidev.json.JSONArray
import net.minidev.json.JSONAware
import net.minidev.json.JSONValue
import scala.util.control.NonFatal

/*
 * This file contain the logic to query a String for a
 *  JSON path.
 */

/**
 * Service that allows to find sub-part of JSON matching a JSON
 * path as defined in: http://goessner.net/articles/JsonPath/
 */
object JsonSelect {

  /*
   * Configuration for json path:
   * - always return list,
   * - We don't want "SUPPRESS_EXCEPTIONS" because null are returned
   *   in place => better to Box it.
   * - We don't want ALWAYS_RETURN_LIST, because it blindly adds an array
   *   around the value, even if the value is already an array.
   */
  val config: Configuration = Configuration.builder.build()

  /*
   * Return the selection corresponding to path from the string.
   * Fails on bad json or bad path.
   *
   * Always return a list with normalized outputs regarding
   * arrays and string quotation, see JsonPathTest for details.
   *
   * The list may be empty if 0 node matches the results.
   */
  def fromPath(path: String, json: String): Box[List[String]] = {
    for {
      p <- compilePath(path)
      j <- parse(json)
      r <- select(p, j)
    } yield {
      r
    }
  }

  /*
   * Only check that the given JsonPath returns something
   * for the given JSON.
   */
  def exists(path: JsonPath, json: String): Box[Boolean] = {
    for {
      j <- parse(json)
      r <- select(path, j)
    } yield {
      r.nonEmpty
    }
  }

  ///                                                       ///
  /// implementation logic - protected visibility for tests ///
  ///                                                       ///

  protected[queries] def parse(json: String): Box[DocumentContext] = {
    tryo(JsonPath.using(config).parse(json))
  }

  /*
   * Some remarks:
   * - just a name "foo" is interpreted as "$.foo"
   * - If path is empty, replace it by "$" or the path compilation fails,
   *   an empty path means accepting the whole json
   */
  protected[queries] def compilePath(path: String): Box[JsonPath] = {
    val effectivePath = if (path.isEmpty()) "$" else path
    tryo(JsonPath.compile(effectivePath))
  }

  /*
   * not exposed to user due to risk to not use the correct config
   */
  protected[queries] def select(path: JsonPath, json: DocumentContext): Box[List[String]] = {

    // so, this lib seems to be a whole can of inconsistencies on String quoting.
    // we would like to NEVER have quoted string if they are not in a JSON object
    // but to have them quoted in json object.
    def toJsonString(a: Any): String = {
      a match {
        case s: String => s
        case x => JSONValue.toJSONString(x)
      }
    }

    // I didn't find any other way to do that:
    // - trying to parse as Any, then find back the correct JSON type
    //   lead to a mess of quoted strings
    // - just parsing as JSONAware fails on string, int, etc.

    import scala.jdk.CollectionConverters.*

    for {
      jsonValue <- try {
                     Full(json.read[JSONAware](path))
                   } catch {
                     case _: ClassCastException =>
                       try {
                         Full(json.read[Any](path).toString)
                       } catch {
                         case NonFatal(ex) =>
                           Failure(s"Error when trying to get path '${path.getPath}': ${ex.getMessage}", Full(ex), Empty)
                       }
                     case NonFatal(ex) =>
                       Failure(s"Error when trying to get path '${path.getPath}': ${ex.getMessage}", Full(ex), Empty)
                   }
    } yield {
      jsonValue match {
        case x: JSONArray => x.asScala.toList.map(toJsonString)
        case x => toJsonString(x) :: Nil
      }
    }
  }

}
