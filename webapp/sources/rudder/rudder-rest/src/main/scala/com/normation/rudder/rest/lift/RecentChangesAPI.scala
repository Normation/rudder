/*
 *************************************************************************************
 * Copyright 2021 Normation SAS
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

package com.normation.rudder.rest.lift

import com.normation.box.IOToBox
import com.normation.errors.BoxToIO
import com.normation.errors.Inconsistency
import com.normation.errors.IOResult
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.reports.ResultRepairedReport
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.ChangesApi
import com.normation.rudder.rest.ChangesApi as API
import com.normation.rudder.rest.RestUtils.*
import com.normation.rudder.services.reports.NodeChangesService
import com.normation.utils.DateFormaterService
import net.liftweb.common.*
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import org.joda.time.DateTime
import org.joda.time.Interval
import org.json4s.JsonAST.JValue
import org.json4s.JsonDSL.*
import zio.syntax.ToZio

class RecentChangesAPI(
    nodeChangesService: NodeChangesService
) extends LiftApiModuleProvider[API] {

  def serialize(changesByRules: Map[RuleId, Map[Interval, Int]]): JValue = {
    changesByRules.map {
      case (ruleId, changes) =>
        val serializedChanges = {
          changes.toList.sortBy(_._1.getStart).map {
            case (interval, number) =>
              (("start"    -> DateFormaterService.serialize(interval.getStart))
              ~ ("end"     -> DateFormaterService.serialize(interval.getEnd))
              ~ ("changes" -> number))
          }
        }
        (ruleId.serialize, serializedChanges)
    }
  }
  def serialize(report: ResultRepairedReport):                    JValue = {
    (("executionDate"       -> DateFormaterService.serialize(report.executionDate))
    ~ ("nodeId"             -> report.nodeId.value)
    ~ ("directiveId"        -> report.directiveId.serialize)
    ~ ("component"          -> report.component)
    ~ ("value"              -> report.keyValue)
    ~ ("message"            -> report.message)
    ~ ("executionTimestamp" -> DateFormaterService.serialize(report.executionTimestamp)))
  }

  def schemas: API.type = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map {
      case API.GetRecentChanges       => GetRecentChanges
      case API.GetRuleRepairedReports => GetRuleRepairedReports
    }
  }

  object GetRecentChanges extends LiftApiModule0 {
    val schema: ChangesApi.GetRecentChanges.type = API.GetRecentChanges

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val prettify: Boolean = params.prettify
      implicit val action:   String  = "getRulesChanges"
      nodeChangesService.countChangesByRuleByInterval() match {
        case Full((_, changes)) =>
          val json = serialize(changes)
          toJsonResponse(None, json)
        case eb: EmptyBox =>
          val msg = (eb ?~! s"Could not get recent changes for all Rules").messageChain
          toJsonError(None, msg)
      }
    }
  }

  object GetRuleRepairedReports extends LiftApiModule {
    val schema: ChangesApi.GetRuleRepairedReports.type = API.GetRuleRepairedReports

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        ruleId:     String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val action:   String  = "getRuleChange"
      implicit val prettify: Boolean = false

      (for {
        startDate <- req.params.get("start") match {
                       case Some(start :: Nil) =>
                         IOResult.attempt(DateTime.parse(start))
                       case _                  =>
                         Inconsistency("No start date defined").fail
                     }
        endDate   <- req.params.get("end") match {
                       case Some(end :: Nil) =>
                         IOResult.attempt(DateTime.parse(end))
                       case _                =>
                         Inconsistency("No end date defined").fail
                     }
        reports   <-
          nodeChangesService.getChangesForInterval(RuleId(RuleUid(ruleId)), new Interval(startDate, endDate), Some(10000)).toIO
      } yield {
        reports
      }).toBox match {
        case Full(reports) =>
          val json = reports.map(serialize)
          toJsonResponse(None, json)
        case eb: EmptyBox =>
          val msg = (eb ?~! s"Could not get repaired report for Rule '$ruleId'").messageChain
          toJsonError(None, msg)
      }

    }
  }
}
