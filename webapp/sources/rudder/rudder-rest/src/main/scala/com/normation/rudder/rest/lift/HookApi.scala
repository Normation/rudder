package com.normation.rudder.rest.lift

import better.files.File
import com.normation.errors.IOResult
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.apidata.JsonResponseObjects.JRHooks
import com.normation.rudder.apidata.implicits._
import com.normation.rudder.hooks.RunHooks
import com.normation.rudder.rest.{HookApi => API}
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.implicits._
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import zio._

class HookApi(
    apiVService: HookApiService
) extends LiftApiModuleProvider[API] {

  def schemas = API

  override def getLiftEndpoints(): List[LiftApiModule] = {

    API.endpoints.map(e => {
      e match {
        case API.GetHooks => GetHooks
      }
    })
  }

  object GetHooks extends LiftApiModule0 {
    val schema = API.GetHooks

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiVService.listHooks().toLiftResponseList(params, schema)
    }
  }
}

class HookApiService(
    hooksDirectory:   String,
    suffixIgnoreList: List[String]
) {

  def listHooks(): IOResult[List[JRHooks]] = {
    for {
      hooksDirectories <- IOResult.effect(File(hooksDirectory).list.filter(_.isDirectory).toList)
      hooks            <- ZIO.foreach(hooksDirectories)(d => RunHooks.getHooksPure(s"$hooksDirectory/${d.name}", suffixIgnoreList))
      res               = hooks.map(JRHooks.fromHook)
    } yield {
      res
    }
  }
}
