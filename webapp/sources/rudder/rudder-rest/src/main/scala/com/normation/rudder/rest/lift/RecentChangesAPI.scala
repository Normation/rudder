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

import com.normation.errors.*
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.apidata.JsonResponseObjects.JRRecentChanges
import com.normation.rudder.apidata.JsonResponseObjects.JRResultRepairedReport
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.ChangesApi
import com.normation.rudder.rest.ChangesApi as API
import com.normation.rudder.rest.RudderJsonResponse.syntax.*
import com.normation.rudder.services.reports.NodeChangesService
import io.scalaland.chimney.syntax.*
import net.liftweb.common.*
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import org.joda.time.DateTime
import org.joda.time.Interval
import zio.syntax.ToZio

class RecentChangesAPI(
    nodeChangesService: NodeChangesService
) extends LiftApiModuleProvider[API] {

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
      nodeChangesService
        .countChangesByRuleByInterval()
        .toIO
        .map((_, c) => c.transformInto[Map[RuleId, List[JRRecentChanges]]].map((k, v) => (k.serialize, v)))
        .chainError(s"Could not get recent changes for all rules")
        .toLiftResponseOne(params, schema, None)
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
        reports.map(_.transformInto[JRResultRepairedReport])
      })
        .chainError(s"Could not get repaired report for Rule '${ruleId}'")
        .toLiftResponseList(params, schema)
    }
  }
}
