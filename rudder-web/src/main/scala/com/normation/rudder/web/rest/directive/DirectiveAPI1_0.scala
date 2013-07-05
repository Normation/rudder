package com.normation.rudder.web.rest.directive

import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.web.services.rest.RestExtractorService
import com.normation.rudder.web.rest.RestUtils._
import net.liftweb.http.Req
import net.liftweb.http.rest.RestHelper
import net.liftweb.common._
import net.liftweb.http.LiftResponse
import com.normation.rudder.web.rest.RestError
import com.normation.rudder.web.rest.directive.service.DirectiveAPIService1_0

class DirectiveAPI1_0 (
    readDirective : RoDirectiveRepository
  , restExtractor : RestExtractorService
  , apiV1_0       : DirectiveAPIService1_0
) extends RestHelper with DirectiveAPI with Loggable{


  val requestDispatch : PartialFunction[Req, () => Box[LiftResponse]] = {

    case Get(Nil, req) => apiV1_0.listDirectives(req)

    case Put(Nil, req) => {
      val restDirective = restExtractor.extractDirective(req.params)
      apiV1_0.createDirective(restDirective, req)
    }

    case Get(id :: Nil, req) => apiV1_0.directiveDetails(id, req)

    case Delete(id :: Nil, req) =>  apiV1_0.deleteDirective(id,req)

    case Post(id:: Nil, req) => {
      val restDirective = restExtractor.extractDirective(req.params)
      apiV1_0.updateDirective(id,req,restDirective)
    }

/*    case id :: Nil JsonPost body -> req => {
      req.json match {
        case Full(arg) =>
          val restDirective = restExtractor.extractDirectiveFromJSON(arg)
          apiV1_0.updateDirective(id,req,restDirective)
        case eb:EmptyBox=>    toJsonResponse(id, "no args arg", RestError)("Empty",true)
      }
    }
*/
    case content => println(content)
         toJsonResponse("nothing", "rien", RestError)("error",true)

  }
  serve( "api" / "1.0" / "directives" prefix requestDispatch)

}
