package com.normation.rudder.web.rest.rule

import net.liftweb.http.rest.RestHelper
import net.liftweb.common._
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req

class LatestNodeAPI (
    latestApi : NodeAPI
) extends RestHelper with Loggable {
    serve( "api" / "lastest" / "nodes" prefix latestApi.requestDispatch)

}

trait NodeAPI {
  val requestDispatch : PartialFunction[Req, () => Box[LiftResponse]]
}