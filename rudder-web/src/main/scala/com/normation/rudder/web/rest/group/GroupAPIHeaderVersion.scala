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
import net.liftweb.json.JString

class GroupAPIHeaderVersion (
    readGroup             : RoNodeGroupRepository
  , restExtractor        : RestExtractorService
  , apiV1_0              : GroupApiService1_0
) extends RestHelper with GroupAPI with Loggable{


  val requestDispatch : PartialFunction[Req, () => Box[LiftResponse]] = {

    case Get(Nil, req) => {
      req.header("X-API-VERSION") match {
        case Full("1.0") => apiV1_0.listGroups(req)
        case _ => notValidVersionResponse("listGroups")
      }
    }

    case Put(Nil, req) => {
      req.header("X-API-VERSION") match {
        case Full("1.0") =>
          val restGroup = restExtractor.extractGroup(req.params)
          apiV1_0.createGroup(restGroup, req)
        case _ => notValidVersionResponse("createGroup")
      }
    }

    case Get(id :: Nil, req) => {
      req.header("X-API-VERSION") match {
        case Full("1.0") => apiV1_0.groupDetails(id, req)
        case _ => notValidVersionResponse("groupDetails")
      }
    }

    case Delete(id :: Nil, req) => {
      req.header("X-API-VERSION") match {
        case Full("1.0") => apiV1_0.deleteGroup(id,req)
        case _ => notValidVersionResponse("deleteGroup")
      }
    }

    case Post(id:: Nil, req) => {
      req.header("X-API-VERSION") match {
        case Full("1.0") =>
          val restGroup = restExtractor.extractGroup(req.params)
          apiV1_0.updateGroup(id,req,restGroup)
        case _ => notValidVersionResponse("updateGroup")
      }
    }

    case id :: Nil JsonPost body -> req => {
      req.header("X-API-VERSION") match {
        case Full("1.0") =>
      req.json match {
        case Full(arg) =>
          val restGroup = restExtractor.extractGroupFromJSON(arg)
          apiV1_0.updateGroup(id,req,restGroup)
        case eb:EmptyBox=>    toJsonError(None, JString("No Json data sent"))("updateGroup",true)
      }
        case _ => notValidVersionResponse("listGroups")
      }

    }

  }
  serve( "api" / "groups" prefix requestDispatch)

}
