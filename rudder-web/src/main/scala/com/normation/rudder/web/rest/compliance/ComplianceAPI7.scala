/*
*************************************************************************************
* Copyright 2015 Normation SAS
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

package com.normation.rudder.web.rest.compliance

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports._
import com.normation.rudder.web.rest.RestExtractorService
import com.normation.rudder.web.rest.RestUtils._
import net.liftweb.common._
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.json.JString
import net.liftweb.json.JsonDSL._
import com.normation.rudder.web.rest.ApiVersion


class ComplianceAPI7 (
    restExtractor    : RestExtractorService
  , complianceService: ComplianceAPIService
  , v6compatibility  : Boolean = false // if true, enforce v6 compatible outputs
) extends ComplianceAPI with Loggable {

  import net.liftweb.json.JsonDSL._
  import JsonCompliance._

  def requestDispatch(apiVersion: ApiVersion) : PartialFunction[Req, () => Box[LiftResponse]] = {


    /**
     * Get compliance for all Rules, then by node/directive/components/values
     */
    case Get("rules" :: Nil, req) => {
      implicit val action = "getRulesCompliance"
      implicit val prettify = restExtractor.extractPrettify(req.params)

      (for {
        level <- restExtractor.extractComplianceLevel(req.params)
        rules <- complianceService.getRulesCompliance()
      } yield {
        if(v6compatibility) {
          rules.map( _.toJsonV6 )
        } else {
          rules.map( _.toJson(level.getOrElse(10) ) ) //by default, all details are displayed
        }
      }) match {
        case Full(rules) =>
          toJsonResponse(None, ( "rules" -> rules ) )

        case eb: EmptyBox =>
          val message = (eb ?~ (s"Could not get compliance for all rules")).messageChain
          toJsonError(None, JString(message))
      }
    }


    /**
     * Get compliance for the given Rule, then by node/directive/components/values
     */
    case Get("rules" :: ruleId :: Nil, req) => {
      implicit val action = "getRuleCompliance"
      implicit val prettify = restExtractor.extractPrettify(req.params)

      (for {
        level <- restExtractor.extractComplianceLevel(req.params)
        rule  <- complianceService.getRuleCompliance(RuleId(ruleId))
      } yield {
        if(v6compatibility) {
          rule.toJsonV6
        } else {
          rule.toJson(level.getOrElse(10) ) //by default, all details are displayed
        }
      }) match {
        case Full(rule) =>
          toJsonResponse(None,( "rules" -> List(rule) ) )

        case eb: EmptyBox =>
          val message = (eb ?~ (s"Could not get compliance for rule '${ruleId}'")).messageChain
          toJsonError(None, JString(message))
      }
    }

    /**
     * Get compliance for all Nodes, then by rules/directive/components/values
     */
    case Get("nodes" :: Nil, req) => {
      implicit val action = "getNodesCompliance"
      implicit val prettify = restExtractor.extractPrettify(req.params)

      (for {
        level <- restExtractor.extractComplianceLevel(req.params)
        nodes <- complianceService.getNodesCompliance()
      } yield {
        if(v6compatibility) {
          nodes.map( _.toJsonV6 )
        } else {
          nodes.map( _.toJson(level.getOrElse(10)) )
        }
      })match {
        case Full(nodes) =>
          toJsonResponse(None, ("nodes" -> nodes ) )

        case eb: EmptyBox =>
          val message = (eb ?~ ("Could not get compliances for nodes")).messageChain
          toJsonError(None, JString(message))
      }
    }

    /**
     * Get compliance for the given Node, then by rules/directive/components/values
     */
    case Get("nodes" :: nodeId :: Nil, req) => {
      implicit val action = "getNodeCompliance"
      implicit val prettify = restExtractor.extractPrettify(req.params)

      (for {
        level <- restExtractor.extractComplianceLevel(req.params)
        node  <- complianceService.getNodeCompliance(NodeId(nodeId))
      } yield {
        if(v6compatibility) {
          node.toJsonV6
        } else {
          node.toJson(level.getOrElse(10))
        }
      })match {
        case Full(node) =>
          toJsonResponse(None, ("nodes" -> List(node) ))

        case eb: EmptyBox =>
          val message = (eb ?~ (s"Could not get compliance for node '${nodeId}'")).messageChain
          toJsonError(None, JString(message))
      }
    }

  }
}
