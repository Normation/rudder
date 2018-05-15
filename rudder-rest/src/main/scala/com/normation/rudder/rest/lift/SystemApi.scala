package com.normation.rudder.rest.lift
import com.normation.rudder.rest.{ApiPath, ApiVersion, AuthzToken, RestExtractorService, SystemApi => API}
import net.liftweb.http.{LiftResponse, Req}
import com.normation.rudder.rest.RestUtils.toJsonResponse
import net.liftweb.json.JsonDSL._


class SystemApi(restExtractorService: RestExtractorService) extends LiftApiModuleProvider[API] {

  def schemas = API

  override def getLiftEndpoints(): List[LiftApiModule] = {

    API.endpoints.map(e => e match {
      case API.ApiStatus => Status
    })

  }

  object Status extends LiftApiModule0 {
    val schema = API.ApiStatus
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val prettify = params.prettify
      implicit val action = "getStatus"
      toJsonResponse(None,("global" -> "ok"))
    }
  }
}
