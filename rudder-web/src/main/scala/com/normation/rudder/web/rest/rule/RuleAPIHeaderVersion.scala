package com.normation.rudder.web.rest.rule

import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.web.services.rest.RestExtractorService
import com.normation.rudder.web.rest.rule.service.RuleApiService1_0
import com.normation.rudder.web.rest.RestUtils._
import net.liftweb.http.Req
import net.liftweb.http.rest.RestHelper
import net.liftweb.common._
import net.liftweb.http.LiftResponse
import com.normation.rudder.web.rest.RestError
import net.liftweb.json.JString

class RuleAPIHeaderVersion (
    readRule             : RoRuleRepository
  , restExtractor        : RestExtractorService
  , apiV1_0              : RuleApiService1_0
) extends RestHelper with RuleAPI with Loggable{


  val requestDispatch : PartialFunction[Req, () => Box[LiftResponse]] = {

    case Get(Nil, req) => {
      req.header("X-API-VERSION") match {
        case Full("1.0") => apiV1_0.listRules(req)
        case _ => notValidVersionResponse("listRules")
      }
    }

    case Put(Nil, req) => {
      req.header("X-API-VERSION") match {
        case Full("1.0") =>
          val restRule = restExtractor.extractRule(req.params)
          apiV1_0.createRule(restRule, req)
        case _ => notValidVersionResponse("createRule")
      }
    }

    case Get(id :: Nil, req) => {
      req.header("X-API-VERSION") match {
        case Full("1.0") => apiV1_0.ruleDetails(id, req)
        case _ => notValidVersionResponse("listRules")
      }
    }

    case Delete(id :: Nil, req) => {
      req.header("X-API-VERSION") match {
        case Full("1.0") => apiV1_0.deleteRule(id,req)
        case _ => notValidVersionResponse("deleteRule")
      }
    }

    case Post(id:: Nil, req) => {
      req.header("X-API-VERSION") match {
        case Full("1.0") =>
          val restRule = restExtractor.extractRule(req.params)
          apiV1_0.updateRule(id,req,restRule)
        case _ => notValidVersionResponse("updateRule")
      }
    }

    case id :: Nil JsonPost body -> req => {
      req.header("X-API-VERSION") match {
        case Full("1.0") =>
      req.json match {
        case Full(arg) =>
          val restRule = restExtractor.extractRuleFromJSON(arg)
          apiV1_0.updateRule(id,req,restRule)
        case eb:EmptyBox=>    toJsonError(Some(id), JString("no Json Data sent"))("updateRule",true)
      }
        case _ => notValidVersionResponse("updateRule")
      }

    }
  }
  serve( "api" / "rules" prefix requestDispatch)

}
