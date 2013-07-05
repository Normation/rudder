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
import com.normation.rudder.web.rest.rule.RuleAPI

class RuleAPI1_0 (
    readRule             : RoRuleRepository
  , restExtractor        : RestExtractorService
  , apiV1_0              : RuleApiService1_0
) extends RestHelper with RuleAPI with Loggable{


  val requestDispatch : PartialFunction[Req, () => Box[LiftResponse]] = {

    case Get(Nil, req) => apiV1_0.listRules(req)

    case Put(Nil, req) => {
      val restRule = restExtractor.extractRule(req.params)
      apiV1_0.createRule(restRule, req)
    }

    case Get(id :: Nil, req) => apiV1_0.ruleDetails(id, req)

    case Delete(id :: Nil, req) =>  apiV1_0.deleteRule(id,req)

    case Post(id:: Nil, req) => {
      val restRule = restExtractor.extractRule(req.params)
      apiV1_0.updateRule(id,req,restRule)
    }

    case id :: Nil JsonPost body -> req => {
      req.json match {
        case Full(arg) =>
          val restRule = restExtractor.extractRuleFromJSON(arg)
          apiV1_0.updateRule(id,req,restRule)
        case eb:EmptyBox=>    toJsonResponse(id, "no args arg", RestError)("Empty",true)
      }
    }

    case content => println(content)
         toJsonResponse("nothing", "rien", RestError)("error",true)

  }
  serve( "api" / "1.0" / "rules" prefix requestDispatch)

  serve ( "api" / "rules" prefix {

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
        case _ => notValidVersionResponse("listRules")
      }
    }

    case Post(id:: Nil, req) => {
      req.header("X-API-VERSION") match {
        case Full("1.0") =>
          val restRule = restExtractor.extractRule(req.params)
          apiV1_0.updateRule(id,req,restRule)
        case _ => notValidVersionResponse("listRules")
      }
    }

    case id :: Nil JsonPost body -> req => {
      req.header("X-API-VERSION") match {
        case Full("1.0") =>
      req.json match {
        case Full(arg) =>
          val restRule = restExtractor.extractRuleFromJSON(arg)
          apiV1_0.updateRule(id,req,restRule)
        case eb:EmptyBox=>    toJsonResponse(id, "no args arg", RestError)("Empty",true)
      }
        case _ => notValidVersionResponse("listRules")
      }

    }

    case content => println(content)
         toJsonResponse("nothing", "rien", RestError)("error",true)

  })

  def notValidVersionResponse(action:String) = {
    toJsonResponse("badversion", "version x does not exists", RestError)(action,true)
   }

}
