package com.normation.rudder.web.rest

import net.liftweb.http.Req
import net.liftweb.common.Box
import net.liftweb.http.LiftResponse
import net.liftweb.http.rest.RestHelper
import net.liftweb.common.Full
import net.liftweb.common.EmptyBox


trait RestAPI  extends RestHelper{

  def kind : String
  def requestDispatch : PartialFunction[Req, () => Box[LiftResponse]]

}

case class APIDispatcher (
    apis : Map[ApiVersion,List[RestAPI]]
) extends RestHelper {
  apis.foreach{
      case (ApiVersion(version),apis) =>
        apis.foreach{
          api =>
            serve("api" / s"${version}" / s"${api.kind}" prefix api.requestDispatch)
        }
    }
    apis(apis.keySet.maxBy(_.value)).foreach{
      api =>
        serve("api" / "latest" / s"${api.kind}" prefix api.requestDispatch)
    }

    val res = apis.toList.flatMap{case (version,list) => list.map((version,_))}.groupBy(_._2.kind).mapValues(_.toMap)

    res.foreach{
      case (kind,apis) =>
        implicit val availableVersions = apis.keySet.toList.map(_.value)
        val requestDispatch : PartialFunction[Req, () => Box[LiftResponse]] = {
          case req =>
            println("oups")
            ApiVersion.fromRequest(req) match {
              case Full(apiVersion) => apis.get(apiVersion) match {
                case Some(api) => api.requestDispatch(req)
                case None => RestUtils.notValidVersionResponse("N/A")
              }
              case eb : EmptyBox =>  RestUtils.notValidVersionResponse("N/A")
              }
            }

        serve("api" / s"${kind}" prefix requestDispatch)
    }


}