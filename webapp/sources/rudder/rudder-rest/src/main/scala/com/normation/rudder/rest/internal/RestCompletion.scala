/*
 *************************************************************************************
 * Copyright 2017 Normation SAS
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

package com.normation.rudder.rest.internal

import com.normation.box.*
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.rest.OldInternalApiAuthz
import com.normation.rudder.rest.RudderJsonResponse
import com.normation.rudder.rest.RudderJsonResponse.ResponseSchema
import com.normation.rudder.tenants.QueryContext
import com.normation.rudder.users.UserService
import net.liftweb.common.*
import net.liftweb.http.rest.RestHelper
import zio.json.*

class RestCompletion(
    completion:  RestCompletionService,
    userService: UserService
) extends RestHelper with Loggable {

  private case class JCompletion(value: String) derives JsonEncoder

  given prettify: Boolean = false

  serve {
    case Get("secure" :: "api" :: "completion" :: "tags" :: (kind @ ("rule" | "directive")) :: "key" :: token :: Nil, req) => {

      val schema = ResponseSchema("completeTagsKey", None)
      given action: String = schema.action

      OldInternalApiAuthz.withReadConfig(userService.getCurrentUser) {
        val fetchTags = if (kind == "directive") {
          completion.findDirectiveTagNames(token)(using userService.getCurrentQC)
        } else {
          // rule
          completion.findRuleTagNames(token)(using userService.getCurrentQC)
        }

        fetchTags match {
          case eb: EmptyBox =>
            val e = eb ?~! s"Error when looking for object containing ${token}"
            RudderJsonResponse.internalError(None, schema, e.messageChain)

          case Full(results) =>
            RudderJsonResponse.successList(schema, results.map(JCompletion.apply))
        }

      }
    }
    case Get("secure" :: "api" :: "completion" :: "tags" :: (kind @ ("rule" | "directive")) :: "value" :: token :: Nil, req) => {
      val schema = ResponseSchema("completeTagsValue", None)
      given action: String = schema.action

      OldInternalApiAuthz.withReadConfig(userService.getCurrentUser) {

        val fetchTags = if (kind == "directive") {
          completion.findDirectiveTagValues(token, None)(using
            userService.getCurrentQC
          )
        } else {
          // rule
          completion.findRuleTagValues(token, None)(using userService.getCurrentUser.map(_.qc).getOrElse(QueryContext.systemQC))
        }

        fetchTags match {
          case eb: EmptyBox =>
            val e = eb ?~! s"Error when looking for object containing ${token}"
            RudderJsonResponse.internalError(None, schema, e.messageChain)

          case Full(results) =>
            RudderJsonResponse.successList(schema, results.map(JCompletion.apply))
        }
      }

    }
    case Get(
          "secure" :: "api" :: "completion" :: "tags" :: (kind @ ("rule" | "directive")) :: "value" :: key :: token :: Nil,
          req
        ) => {

      val schema = ResponseSchema("completeTags", None)

      val fetchTags = if (kind == "directive") {
        completion.findDirectiveTagValues(token, Some(key))(using
          userService.getCurrentQC
        )
      } else {
        // rule
        completion.findRuleTagValues(token, Some(key))(using
          userService.getCurrentUser.map(_.qc).getOrElse(QueryContext.systemQC)
        )
      }

      fetchTags match {
        case eb: EmptyBox =>
          val e = eb ?~! s"Error when looking for object containing ${token}"
          RudderJsonResponse.internalError(None, schema, e.messageChain)

        case Full(results) =>
          RudderJsonResponse.successList(schema, results.map(JCompletion.apply))
      }

    }

  }

}

class RestCompletionService(
    readDirective: RoDirectiveRepository,
    readRule:      RoRuleRepository
) {
  def findDirectiveTagNames(matching: String)(using qc: QueryContext): Box[List[String]] = {
    for {
      lib <- readDirective.getFullDirectiveLibrary().toBox
    } yield {
      (for {
        tag <- lib.allDirectives.flatMap(_._2._2.tags.tags).toList
        name = tag.name.value
        if name.startsWith(matching)
      } yield {
        name
      }).sorted.distinct
    }
  }

  def findDirectiveTagValues(matching: String, tagName: Option[String])(using qc: QueryContext): Box[List[String]] = {
    for {
      lib <- readDirective.getFullDirectiveLibrary().toBox
    } yield {
      (for {
        tag  <- lib.allDirectives.flatMap(_._2._2.tags.tags).toList
        if tagName.map(_ == tag.name.value).getOrElse(true)
        value = tag.value.value
        if value.startsWith(matching)
      } yield {
        value
      }).sorted.distinct
    }
  }

  def findRuleTagNames(matching: String)(using qc: QueryContext): Box[List[String]] = {
    for {
      rules <- readRule.getAll(false).toBox
    } yield {
      (for {
        tag <- rules.flatMap(_.tags.tags).toList
        if tag.name.value.contains(matching)
      } yield {
        tag.name.value
      }).sorted.distinct
    }
  }

  def findRuleTagValues(matching: String, tagName: Option[String])(using qc: QueryContext): Box[List[String]] = {
    for {
      rules <- readRule.getAll(false).toBox
    } yield {
      (for {
        tag  <- rules.flatMap(_.tags.tags).toList
        if tagName.map(_ == tag.name.value).getOrElse(true)
        value = tag.value.value
        if value.startsWith(matching)
      } yield {
        value
      }).sorted.distinct
    }
  }
}
