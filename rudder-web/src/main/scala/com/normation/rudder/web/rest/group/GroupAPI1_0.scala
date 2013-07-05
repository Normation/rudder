package com.normation.rudder.web.rest.group

import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.web.services.rest.RestExtractorService
import com.normation.rudder.web.rest.group.service.GroupApiService1_0
import com.normation.rudder.web.rest.RestUtils._
import net.liftweb.http.Req
import net.liftweb.http.rest.RestHelper
import net.liftweb.common._
import net.liftweb.http.LiftResponse
import com.normation.rudder.web.rest.RestError

class GroupAPI1_0 (
    readGroup     : RoNodeGroupRepository
  , restExtractor : RestExtractorService
  , apiV1_0       : GroupApiService1_0
) extends RestHelper with GroupAPI with Loggable{


  val requestDispatch : PartialFunction[Req, () => Box[LiftResponse]] = {

    case Get(Nil, req) => apiV1_0.listGroups(req)

    case Put(Nil, req) => {
      val restGroup = restExtractor.extractGroup(req.params)
      apiV1_0.createGroup(restGroup, req)
    }

    case Get(id :: Nil, req) => apiV1_0.groupDetails(id, req)

    case Delete(id :: Nil, req) =>  apiV1_0.deleteGroup(id,req)

    case Post(id:: Nil, req) => {
      val restGroup = restExtractor.extractGroup(req.params)
      apiV1_0.updateGroup(id,req,restGroup)
    }

    case Post("reload" :: id:: Nil, req) => {
      apiV1_0.groupReload(id, req)
    }

/*    case id :: Nil JsonPost body -> req => {
      req.json match {
        case Full(arg) =>
          val restGroup = restExtractor.extractGroupFromJSON(arg)
          apiV1_0.updateGroup(id,req,restGroup)
        case eb:EmptyBox=>    toJsonResponse(id, "no args arg", RestError)("Empty",true)
      }
    }*/

    case content => println(content)
         toJsonResponse("nothing", "rien", RestError)("error",true)

  }
  serve( "api" / "1.0" / "groups" prefix requestDispatch)


}
