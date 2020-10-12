package com.normation.rudder.rest.lift

import com.normation.box._
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.ApiVersion
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.RestDataSerializer
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest.RestUtils
import com.normation.rudder.rest.{HealthcheckApi => API}
import com.normation.rudder.services.healthcheck.HealthcheckService
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.json.JArray
import net.liftweb.json.JsonDSL._

class HealthcheckApi (
    restExtractorService: RestExtractorService
  , serializer          : RestDataSerializer
  , healthcheckService  : HealthcheckService
) extends LiftApiModuleProvider[API] {

  def schemas = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => e match {
      case API.GetHealthcheckResult   => GetHealthcheck
    }).toList
  }

  // Run all checks to return the result
  object GetHealthcheck extends LiftApiModule0 {
    val schema        = API.GetHealthcheckResult
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val action   = schema.name
      implicit val prettify = params.prettify
      val result = for {
        checks <- healthcheckService.runAll.toBox
      } yield {
        checks.map(serializer.serializeHealthcheckResult)
      }
      result match {
        case Full(jsons) =>
          RestUtils.toJsonResponse(None, JArray(jsons))
        case eb: EmptyBox =>
          val message = (eb ?~ (s"Error when trying to run healthcheck")).msg
          RestUtils.toJsonError(None, message)("getHealthcheck",true)
      }
    }

  }
}
