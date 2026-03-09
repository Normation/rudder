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

package com.normation.rudder.rest.internal

import com.normation.box.*
import com.normation.rudder.AuthorizationType
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.rest.{QuickSearchApi as API, *}
import com.normation.rudder.rest.RestUtils.*
import com.normation.rudder.rest.lift.DefaultParams
import com.normation.rudder.rest.lift.LiftApiModule
import com.normation.rudder.rest.lift.LiftApiModule0
import com.normation.rudder.rest.lift.LiftApiModuleProvider
import com.normation.rudder.services.quicksearch.FullQuickSearchService
import com.normation.rudder.services.quicksearch.QSObject
import com.normation.rudder.services.quicksearch.QuickSearchResult
import com.normation.rudder.users.AuthenticatedUser
import com.normation.rudder.web.model.LinkUtil
import net.liftweb.common.*
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.json.JArray
import net.liftweb.json.JsonAST.*
import net.liftweb.json.JsonDSL.*

/**
 * A class for the Quicksearch rest endpoint.
 *
 * Internal Endpoint: /secure/api/quicksearch/${user_query}
 * Where user_query is a parsable query:
 * - it can be simple string, in that case the string is search exactly as entered
 * - it always a partial (substring) search that is done
 * - the string can contains some key:value parameters. All parameter are taken as AND
 * - possible parameters are:
 *   - restriction on type.
 *     - Possible values defined in QSObject: node, group, directive, parameter, rule
 *     - if not specified, each values are looked for
 *     - ex: type:directive
 *     - ex: type:rule,node
 *     - ex: type:rule type:node
 *   - restriction on attribute
 *     - possible values defined in QSAttribute: nodeId, hostname, etc.
 *     - ex: attribute:hostname,nodeId,ipAddresses
 *
 */
class QuicksearchApi(
    quicksearch: FullQuickSearchService,
    linkUtil:    LinkUtil
) extends LiftApiModuleProvider[API] {

  def schemas: ApiModuleProvider[API] = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map { case API.CompleteTagsValue => CompleteTagsValue }
  }

  // serve: PartialFunction[Req, () => Box[LiftResponse]]
  // So we just have to return a
  /*
   * Since that API doesn't follow the standard Public API schema, we will need to build
   * by hand some part, on a model similar to `RudderJsonResponse#toLiftResponseList)
   */

  object CompleteTagsValue extends LiftApiModule0 {
    final private val MAX_RES_BY_KIND = 10
    val schema: API.CompleteTagsValue.type = API.CompleteTagsValue

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {

      implicit val prettify: Boolean = params.prettify
      implicit val action:   String  = schema.name

      val token = req.params.get("value") match {
        case Some(value :: Nil) => value
        case None               => ""
        // Should not happen, but for now make one token from it, maybe we should only take head ?
        case Some(values)       => values.mkString("")
      }
      val limit = req.params.get("limit").flatMap(_.headOption).flatMap(_.toIntOption)
      quicksearch.search(token, limit)(using authzToken.qc).toBox match {
        case eb: EmptyBox =>
          val e = eb ?~! s"Error when looking for object containing '${token}'"
          toJsonError(None, e.messageChain)

        case Full(results) =>
          toJsonResponse(None, prepare(results, limit.getOrElse(MAX_RES_BY_KIND), authzToken.user))
      }
    }
  }

  private def filter(results: Set[QuickSearchResult], u: AuthenticatedUser) = {
    import com.normation.rudder.services.quicksearch.QuickSearchResultId.*

    val nodeOK       = u.checkRights(AuthorizationType.Node.Read)
    val groupOK      = u.checkRights(AuthorizationType.Group.Read)
    val ruleOK       = u.checkRights(AuthorizationType.Configuration.Read) || u.checkRights(AuthorizationType.Rule.Read)
    val directiveOK  = u.checkRights(AuthorizationType.Configuration.Read) || u.checkRights(AuthorizationType.Directive.Read)
    val techniqueOK  = u.checkRights(AuthorizationType.Technique.Read)
    val parametersOK =
      u.checkRights(AuthorizationType.Configuration.Read) || u.checkRights(AuthorizationType.Parameter.Read)

    results.filter {
      _.id match {
        case _: QRNodeId      => nodeOK
        case _: QRGroupId     => groupOK
        case _: QRRuleId      => ruleOK
        case _: QRParameterId => parametersOK
        case _: QRDirectiveId => directiveOK
        case _: QRTechniqueId => techniqueOK
      }

    }
  }

  /**
   * A function that will prepare results to be transfered to the browser:
   * - split them by kind, so that we can add a summary by number for each
   * - in each kind, sort by natural order on names,
   * - for each kind, limit the number of element sent to the browser.
   *   The user will be able to make more precises search if needed, and we avoid
   *   crunching the browser with thousands of answers
   */

  private def prepare(results: Set[QuickSearchResult], maxByKind: Int, user: AuthenticatedUser): JValue = {
    val filteredResult = filter(results, user)
    // group by kind, and build the summary for each
    val map            = filteredResult.groupBy(_.id.tpe).map {
      case (tpe, set) =>
        // distinct by id, sorted by name
        val unique   = set.map(x => (x.id, x)).toMap.values.toSeq.sortBy(_.name)
        // take the nth first
        val returned = unique.take(maxByKind)

        val summary = ResultTypeSummary(tpe.name, unique.size, returned.size)

        (tpe, (summary, returned))
    }

    // now, transformed to the wanted results: an array.
    // hard coded order for elements:
    // - nodes
    // - groups
    // - directives
    // - parameters
    // - rules
    import com.normation.rudder.services.quicksearch.QSObject.sortQSObject
    val jsonList = QSObject.all.toList.sortWith(sortQSObject).flatMap { tpe =>
      val (summary, res) = map.getOrElse(tpe, (ResultTypeSummary(tpe.name, 0, 0), Seq()))
      if (res.isEmpty) {
        None
      } else {
        Some(
          ("header"  -> summary.toJson)
          ~ ("items" -> JArray(res.toList.map(_.toJson)))
        )
      }
    }
    JArray(jsonList)
  }

  // private case class cannot be final because of scalac bug
  private case class ResultTypeSummary(
      tpe:            String,
      originalNumber: Int,
      returnedNumber: Int
  )

  implicit private class JsonResultTypeSummary(t: ResultTypeSummary) {

    val desc: String = if (t.originalNumber <= t.returnedNumber) {
      s"${t.originalNumber} found"
    } else { // we elided some results
      s"${t.originalNumber} found, only displaying the first ${t.returnedNumber}. Please refine your query."
    }

    def toJson: JObject = {
      (
        ("type"      -> t.tpe.capitalize)
        ~ ("summary" -> desc)
        ~ ("numbers" -> t.originalNumber)
      )
    }
  }

  implicit private class JsonSearchResult(r: QuickSearchResult) {
    import com.normation.inventory.domain.NodeId
    import com.normation.rudder.domain.nodes.NodeGroupId
    import com.normation.rudder.domain.policies.DirectiveUid
    import com.normation.rudder.domain.policies.RuleId
    import com.normation.rudder.services.quicksearch.QuickSearchResultId.*

    def toJson: JObject = {
      import linkUtil.*
      val url = r.id match {
        case QRNodeId(value)      => nodeLink(NodeId(value))
        case QRRuleId(value)      => ruleLink(RuleId(RuleUid(value)))
        case QRDirectiveId(value) => directiveLink(DirectiveUid(value))
        case QRTechniqueId(value) => techniqueLink(value)
        case QRGroupId(value)     => groupLink(NodeGroupId(NodeGroupUid(value)))
        case QRParameterId(value) => globalParameterLink(value)
      }

      // limit description length to avoid having a whole file printed
      val v = {
        val max = 100
        if (r.value.size > max + 3) r.value.take(max) + "..."
        else r.value
      }

      val desc = s"${r.attribute.map(_.display + ": ").getOrElse("")}${v}"

      (
        ("name"    -> r.name)
        ~ ("type"  -> r.id.tpe.name)
        ~ ("id"    -> r.id.value)
        // matched value
        ~ ("value" -> r.value)
        ~ ("desc"  -> desc)
        ~ ("url"   -> url)
      )
    }
  }

}
