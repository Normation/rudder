/*
*************************************************************************************
* Copyright 2016 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.rudder.web.rest.datasource

import com.normation.rudder.web.rest.RestExtractorService
import com.normation.rudder.web.rest.RestUtils._
import net.liftweb.common._
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.json.JsonDSL._
import com.normation.rudder.web.rest.ApiVersion
import net.liftweb.json.JValue
import com.normation.rudder.web.rest.RestUtils
import com.normation.rudder.datasources._
import com.normation.utils.StringUuidGenerator

class DataSourceApi9 (
    restExtractor : RestExtractorService
  , apiService    : DataSourceApiService
  , uuidGen       : StringUuidGenerator
) extends DataSourceApi with Loggable {

  def response ( function : Box[JValue], req : Req, errorMessage : String, id : Option[String])(implicit action : String) : LiftResponse = {
    RestUtils.response(restExtractor, kind, id)(function, req, errorMessage)
  }

  type ActionType = RestUtils.ActionType
  def actionResponse ( function : Box[ActionType], req : Req, errorMessage : String, id : Option[String])(implicit action : String) : LiftResponse = {
    RestUtils.actionResponse(restExtractor, kind, uuidGen, id)(function, req, errorMessage)
  }

  import net.liftweb.json.JsonDSL._
  def requestDispatch(apiVersion: ApiVersion) : PartialFunction[Req, () => Box[LiftResponse]] = {

    case Get(Nil, req) => {
      response(apiService.getSources(), req, "Could not get data sources", None)("getAllDataSources")
    }

    case Get(sourceId :: Nil, req) => {
      response(apiService.getSource(DataSourceId(sourceId)), req, "Could not get data sources", None)("getDataSource")
    }

    case Delete(sourceId :: Nil, req) => {
      response(apiService.deleteSource(DataSourceId(sourceId)), req, "Could not delete data sources", None)("getDataSource")
    }

    case Put(Nil, req) => {
        response(apiService.createSource(req), req, "Could not create data source", None)("createDataSource")
    }

    case Post(sourceId :: Nil, req) => {
      response(apiService.updateSource(DataSourceId(sourceId),req), req, "Could not update data source", None)("updateDataSource")
    }

  }
}
