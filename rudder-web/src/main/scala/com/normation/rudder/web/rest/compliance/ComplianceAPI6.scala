/*
*************************************************************************************
* Copyright 2015 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
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


class ComplianceAPI6 (
    restExtractor    : RestExtractorService
  , complianceService: ComplianceAPIService
) extends ComplianceAPI with Loggable {



  import net.liftweb.json.JsonDSL._
  import JsonCompliance._

  val requestDispatch : PartialFunction[Req, () => Box[LiftResponse]] = {


    /**
     * Get compliance for all Rules, then by node/directive/components/values
     */
    case Get("rules" :: Nil, req) => {
      implicit val action = "getRulesCompliance"
      implicit val prettify = restExtractor.extractPrettify(req.params)

      complianceService.getRulesCompliance() match {
        case Full(rules) =>
          toJsonResponse(None, ( "rules" -> rules.map( _.toJson ) ) )

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

      complianceService.getRuleCompliance(RuleId(ruleId)) match {
        case Full(rule) =>
          toJsonResponse(None,( "rules" -> List(rule).map( _.toJson ) ) )

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

      complianceService.getNodesCompliance() match {
        case Full(nodes) =>
          toJsonResponse(None, ("nodes" -> nodes.map( _.toJson ) ) )

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

      complianceService.getNodeCompliance(NodeId(nodeId)) match {
        case Full(node) =>
          toJsonResponse(None, ("nodes" -> List(node).map( _.toJson) ) )

        case eb: EmptyBox =>
          val message = (eb ?~ (s"Could not get compliance for node '${nodeId}'")).messageChain
          toJsonError(None, JString(message))
      }
    }

  }
}
