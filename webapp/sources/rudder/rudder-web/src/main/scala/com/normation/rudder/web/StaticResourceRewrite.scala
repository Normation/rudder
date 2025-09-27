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

package com.normation.rudder.web

import java.net.URLConnection
import net.liftweb.http.LiftRules
import net.liftweb.http.S
import net.liftweb.http.StreamingResponse
import net.liftweb.http.rest.RestHelper
import org.joda.time.DateTime

////////// rewrites rules to remove the version from resources urls //////////
//////////
class StaticResourceRewrite(rudderFullVersion: String) extends RestHelper {
  // prefix added to signal that the resource is cached
  val prefix:       String = s"cache-${rudderFullVersion}"
  // full URI to resources root, with context path
  val resourceRoot: String = s"${S.contextPath}/${prefix}"

  private def headers(others: List[(String, String)]): List[(String, String)] = {
    ("Cache-Control", "max-age=31556926, public") ::
    ("Pragma", "") ::
    ("Expires", DateTime.now.plusMonths(6).toString("EEE, d MMM yyyy HH':'mm':'ss 'GMT'")) ::
    others
  }

  /**
   * Useful for getting a cached static resource such as a javascript module for import, images, etc.
   * @param path The path for the resource, it must contain a leading '/'
   */
  def resourceUrl(path: String, contextPath: String = S.contextPath): String = {
    s"${contextPath}${prependResourceVersion(path)}"
  }

  def prependResourceVersion(path: String): String = s"/${prefix}${path}"

  serve {
    case Get(prefix_ :: resource :: tail, req) if StaticResourceRewrite.resources.contains(resource) =>
      val resourcePath = req.uri.replaceFirst(prefix_ + "/", "")
      () => {
        for {
          url <- LiftRules.getResource(resourcePath)
        } yield {
          val contentType = URLConnection.guessContentTypeFromName(url.getFile) match {
            // if we don't know the content type, skip the header: most of the time,
            // browsers can live without it, but can't with a bad value in it
            case null                       => Nil
            case x if x.contains("unknown") => Nil
            case x                          => ("Content-Type", x) :: Nil
          }
          val conn        = url.openConnection // will be local, so ~ efficient
          val size        = conn.getContentLength.toLong
          val in          = conn.getInputStream
          StreamingResponse(in, () => in.close(), size = size, headers(contentType), cookies = Nil, code = 200)
        }
      }
  }
}

object StaticResourceRewrite {
  // the resource directory we want to serve that way
  private val resources: Set[String] = Set("javascript", "style", "images", "toserve")
}
