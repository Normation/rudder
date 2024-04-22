package com.normation.rudder.rest.internal

import com.normation.errors.IOResult
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.apidata.JsonResponseObjects.*
import com.normation.rudder.apidata.implicits.*
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.rest.ApiModuleProvider
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.GroupInternalApi as API
import com.normation.rudder.rest.implicits.*
import com.normation.rudder.rest.lift.*
import io.scalaland.chimney.syntax.*
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req

class GroupsInternalApi(
    groupsInternalApiService: GroupInternalApiService
) extends LiftApiModuleProvider[API] {

  def schemas: ApiModuleProvider[API] = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => {
      e match {
        case API.GetGroupCategoryTree => GetGroupCategoryTree
      }
    })
  }

  object GetGroupCategoryTree extends LiftApiModule0 {
    val schema: API.GetGroupCategoryTree.type = API.GetGroupCategoryTree

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      groupsInternalApiService.getGroupCategoryTree().toLiftResponseOne(params, schema, _ => None)
    }
  }

}

class GroupInternalApiService(
    readGroup: RoNodeGroupRepository
) {
  def getGroupCategoryTree(): IOResult[JRGroupCategoryInfo] = {
    readGroup.getFullGroupLibrary().map(_.transformInto[JRGroupCategoryInfo])
  }
}
