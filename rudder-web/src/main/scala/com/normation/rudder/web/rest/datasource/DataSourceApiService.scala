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

import com.normation.rudder.datasources._
import net.liftweb.json.JsonAST.JValue
import com.normation.rudder.web.rest.RestDataSerializer
import net.liftweb.common._
import com.normation.rudder.web.rest.RestUtils
import com.normation.rudder.web.rest.RestExtractorService
import scala.concurrent.duration.Duration
import net.liftweb.http.Req
import scala.concurrent.duration.FiniteDuration

class DataSourceApiService(
    dataSourceRepo     : DataSourceRepository
  , restDataSerializer : RestDataSerializer
  , restExctactor      : RestExtractorService
) extends Loggable {
  import net.liftweb.json.JsonDSL._

  type ActionType = RestUtils.ActionType
  def getSources() : Box[JValue] = {
    for {
      sources <- dataSourceRepo.getAll
      data = sources.values.map(restDataSerializer.serializeDataSource(_))
    } yield {
      data
    }
  }

  def getSource(id : DataSourceId) : Box[JValue] = {
    for {
      optSource <- dataSourceRepo.get(id)
      source <- Box(optSource) ?~! s"Data source ${id} does not exist."
    } yield {
      restDataSerializer.serializeDataSource(source) :: Nil
    }
  }

  def deleteSource(id : DataSourceId) : Box[JValue] = {
    for {
      source <- dataSourceRepo.delete(id)
    } yield {
      (( "id" -> id.value) ~ ("message" -> s"Data source ${id.value} deleted")) :: Nil
    }
  }

  def createSource(request : Req) : Box[JValue] = {

    val defaultDuration = DataSource.defaultDuration
    val baseSourceType = DataSourceType.HTTP("", Map(), HttpMethod.GET, Map(), false,"", HttpRequestMode.OneRequestByNode, defaultDuration)
    val baseRunParam  = DataSourceRunParameters.apply(DataSourceSchedule.NoSchedule(defaultDuration), false,false)
    for {
      sourceId <- restExctactor.extractId(request){ a => val id = DataSourceId(a); Full(id)}.flatMap( Box(_) ?~! "You need to define datasource id to create it via api")
      base = DataSource.apply(sourceId, DataSourceName(""), baseSourceType, baseRunParam, "", false, defaultDuration)
      source <- restExctactor.extractReqDataSource(request, base)
      _ <- dataSourceRepo.save(source)
      data = restDataSerializer.serializeDataSource(source)
    } yield {
      data :: Nil
    }
  }

  def updateSource(sourceId : DataSourceId, request: Req ) : Box[JValue] = {
    for {
      base <- dataSourceRepo.get(sourceId).flatMap { Box(_) ?~! s"Cannot update data source '${sourceId.value}', because it does not exist" }
      updated <- restExctactor.extractReqDataSource(request, base)
      _ <- dataSourceRepo.save(updated)
      data = restDataSerializer.serializeDataSource(updated)
    } yield {
      data :: Nil
    }
  }
}
