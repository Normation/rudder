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

package com.normation.rudder.web.rest.node

import net.liftweb.common.Box
import net.liftweb.common.Loggable
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.http.rest.RestHelper
import com.normation.rudder.web.rest.RestAPI
import net.liftweb.json.JsonAST.JValue
import com.normation.rudder.web.rest.RestExtractorService
import com.normation.rudder.web.rest.RestUtils
import com.normation.rudder.web.rest.ApiVersion
import com.normation.rudder.appconfig._
import net.liftweb.common.Empty
import com.normation.eventlog.EventActor
import net.liftweb.common.Full
import net.liftweb.common.Failure
import net.liftweb.json.JsonAST.JString
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.utils.StringUuidGenerator
import net.liftweb.json.JsonAST.JBool

trait SettingsApi extends RestAPI {
  val kind = "settings"
}

class SettingsAPI8(
    restExtractor : RestExtractorService
  , configService : ReadConfigService with UpdateConfigService
) extends SettingsApi {

  def response ( function : Box[JValue], req : Req, errorMessage : String, id : Option[String])(implicit action : String) : LiftResponse = {
    RestUtils.response(restExtractor, kind, id)(function, req, errorMessage)
  }
  override def requestDispatch(apiVersion : ApiVersion): PartialFunction[Req, () => Box[LiftResponse]] = {
    case Get("global_policy_mode" :: Nil, req) => {
      import net.liftweb.json.JsonDSL._
      val mode : Box[JValue] = configService.rudder_policy_mode_name().map(("global_policy_mode" -> _.name))
      RestUtils.response(restExtractor, "settings", Some("global_policy_mode"))(mode, req, "Could not get global policy mode")("getSetting")
    }
    case Post("global_policy_mode" :: Nil, req) => {
      import net.liftweb.json.JsonDSL._
      val newValue : Box[JValue] = for {
        requestValue <- req.json match {
          case Full(json) => json \ "value" match {
            case JString(value) => Full(value)
            case x => Failure("Invalid value "+x)
          }
          case _ => req.params.get("value") match {
            case Some(value :: Nil) => Full(value)
            case _ => Failure("No value defined in request")
          }
        }
        newMode <- PolicyMode.parse(requestValue)
        actor = RestUtils.getActor(req)
        saved <- configService.set_rudder_policy_mode_name(newMode, actor, None)
      } yield {
         import net.liftweb.json.JsonDSL._
        ("global_policy_mode" -> newMode.name)
      }
      RestUtils.response(restExtractor, "settings", Some("global_policy_mode"))(newValue, req, "Could not get global policy mode")("setSetting")
    }

    case Get("global_policy_mode_overridable" :: Nil, req) => {
      import net.liftweb.json.JsonDSL._
      val mode : Box[JValue] = configService.rudder_policy_overridable().map(("global_policy_mode_overridable" -> _))
      RestUtils.response(restExtractor, "settings", Some("global_policy_mode_overridable"))(mode, req, "Could not get global policy mode")("getSetting")
    }
    case Post("global_policy_mode_overridable" :: Nil, req) => {
      import net.liftweb.json.JsonDSL._
      val newValue : Box[JValue] = for {
        requestValue <- req.json match {
          case Full(json) => json \ "value" match {
            case JBool(value) => Full(value)
            case x => Failure("Invalid value "+x)
          }
          case _ => req.params.get("value") match {
            case Some(value :: Nil) => Full(value.toBoolean)
            case _ => Failure("No value defined in request")
          }
        }
        actor = RestUtils.getActor(req)
        saved <- configService.set_rudder_policy_overridable(requestValue, actor, None)
      } yield {
         import net.liftweb.json.JsonDSL._
        ("global_policy_mode_overridable" -> requestValue)
      }
      RestUtils.response(restExtractor, "settings", Some("global_policy_mode_overridable"))(newValue, req, "Could not get global policy mode")("setSetting")
    }
  }
}
