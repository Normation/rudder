package com.normation.rudder.web.rest

import com.normation.rudder.services.QuicksearchService
import com.normation.rudder.services.QuicksearchService._
import com.normation.rudder.web.rest.RestUtils._

import net.liftweb.common._
import net.liftweb.http.JsonResponse
import net.liftweb.http.js._
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsExp

import net.liftweb.http.rest.RestHelper
import net.liftweb.json.JArray
import net.liftweb.json.JsonAST._
import net.liftweb.json.JsonDSL._

class RestQuicksearch (
  quicksearch: QuicksearchService
) extends RestHelper with Loggable {


  serve {
    case Get("secure" :: "api" :: "quicksearch" :: token :: Nil, req) => {

      val error: JsExp = "error"
      def toJs(results: List[QuicksearchResult]): JValue = JArray(results.map(_.toJson))

      quicksearch.search(token) match {
        case eb: EmptyBox  => JsonResponse(error               , Nil, Nil, RestError.code)
        case Full(results) => toJsonResponse(None, toJs(results.toList))("quicksearch", false)
      }

    }

  }

  private implicit class JsonSearchResult(r: QuicksearchResult) {
    import com.normation.inventory.domain.NodeId
    import com.normation.rudder.domain.policies.DirectiveId
    import com.normation.rudder.domain.policies.RuleId
    import com.normation.rudder.domain.nodes.NodeGroupId
    import com.normation.rudder.domain.parameters.ParameterName
    import com.normation.rudder.web.model.JsInitContextLinkUtil._
    import net.liftweb.http.S

    def toJson(): JObject = {
      def enc(s: String) = S.encodeURL(s).encJs

      val url = r.id match {
        case QRNodeId(v)      => nodeLink(NodeId(v))
        case QRRuleId(v)      => ruleLink(RuleId(v))
        case QRDirectiveId(v) => directiveLink(DirectiveId(v))
        case QRGroupId(v)     => groupLink(NodeGroupId(v))
        case QRParameterId(v) => globalParameterLink(ParameterName(v))
      }

      (
          ( "name" -> r.name        )
        ~ ( "type" -> r.id.tpe      )
        ~ ( "id"   -> r.id.value    )
        ~ ( "desc" -> r.description )
        ~ ( "url"  -> url           )
      )
    }
  }


}



