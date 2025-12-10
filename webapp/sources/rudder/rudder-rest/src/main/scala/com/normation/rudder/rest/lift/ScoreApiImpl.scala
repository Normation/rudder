package com.normation.rudder.rest.lift

import com.normation.rudder.api.ApiVersion
import com.normation.rudder.rest.ApiModuleProvider
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.ScoreApi
import com.normation.rudder.rest.ScoreApi as API
import com.normation.rudder.score.ScoreService
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req

class ScoreApiImpl(scoreService: ScoreService) extends LiftApiModuleProvider[API] {

  def schemas: ApiModuleProvider[API] = API

  override def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map { case ScoreApi.GetScoreList => GetScoreList }
  }

  object GetScoreList extends LiftApiModule0 {
    val schema: ScoreApi.GetScoreList.type = API.GetScoreList

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {

      import com.normation.rudder.rest.syntax.*
      val res = for {
        availablesScores <- scoreService.getAvailableScore()
      } yield {
        availablesScores.map(s => Map(("id", s._1), ("name", s._2)))
      }
      res.toLiftResponseList(params, schema)
    }
  }

}
