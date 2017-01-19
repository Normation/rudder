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

package com.normation.rudder.datasources

import org.joda.time.DateTime
import net.liftweb.common._
import net.liftweb.util.ControlHelpers.tryo
import scala.concurrent.duration.FiniteDuration
import com.normation.inventory.domain.NodeId
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit
import com.normation.rudder.repository.json.JsonExctractorUtils
import com.normation.rudder.repository.json.JsonExctractorUtils
import scalaz.Monad
import scalaz.Id

final object DataSource {
  val defaultDuration = FiniteDuration(5,"minutes")
}

sealed trait DataSourceType {
  def name : String
}

/*
 * For an HTTP datasource, how to contact the
 * foreign server?
 */
sealed trait HttpMethod { def name: String }
final object HttpMethod {

  final case object GET  extends HttpMethod { override val name = "GET"  }
  final case object POST extends HttpMethod { override val name = "POST" }

  def values = ca.mrvisser.sealerate.values[HttpMethod]
}

final object DataSourceType {

  final object HTTP {
    val name = "http"
  }

  final case class HTTP (
      url            : String
    , headers        : Map[String,String]
    , httpMethod     : HttpMethod
    , params         : Map[String, String] // query params for GET, form params for POST
    , sslCheck       : Boolean
    , path           : String
    , requestMode    : HttpRequestMode
    , requestTimeOut : FiniteDuration
  ) extends DataSourceType {
    val name = HTTP.name
  }
}

sealed trait HttpRequestMode {
  def name : String
}

final object HttpRequestMode {
  final case object OneRequestByNode extends HttpRequestMode {
    val name = "byNode"
  }

  final object OneRequestAllNodes {
    val name = "allNodes"
  }

  final case class OneRequestAllNodes(
      matchingPath  : String
    , nodeAttribute : String
  ) extends HttpRequestMode {
    val name = HttpRequestMode.OneRequestAllNodes.name
  }
}

final case class DataSourceName(value : String) extends AnyVal
final case class DataSourceId  (value : String) extends AnyVal

sealed trait DataSourceSchedule {
  def duration : FiniteDuration
}

final object DataSourceSchedule {
  final case class NoSchedule(
    savedDuration : FiniteDuration
  ) extends DataSourceSchedule {
    val duration = savedDuration
  }

  final case class Scheduled(
    duration : FiniteDuration
  ) extends DataSourceSchedule
}

final case class DataSourceRunParameters (
    schedule     : DataSourceSchedule
  , onGeneration : Boolean
  , onNewNode    : Boolean
)

final case class DataSourceStatus (
    lastRunDate : Option[DateTime]
  , nodesStatus : Map[NodeId, DataSourceUpdateStatus]
)

sealed trait DataSourceUpdateStatus{
  def state       : String
  def lastRunDate : DateTime
}

final case object DataSourceUpdateStatus {

  final case class Success(
    lastRunDate : DateTime
  ) extends DataSourceUpdateStatus {
    val state = "success"
  }

  final case class Failure(
      lastRunDate     : DateTime
    , message         : String
    , lastSuccessDate : Option[String]
  ) extends DataSourceUpdateStatus {
    val state = "failure"
  }
}

final case class DataSource (
    id            : DataSourceId
  , name          : DataSourceName
  , sourceType    : DataSourceType
  , runParam      : DataSourceRunParameters
  , description   : String
  , enabled       : Boolean
  , updateTimeOut : FiniteDuration
) {
  val scope = "all"
}
