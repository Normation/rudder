/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.rudder.web.snippet

import com.normation.rudder.domain.system.{ButtonActivated,ButtonReleased}
import scala.xml._
import net.liftweb.http._
import net.liftweb.util._
import Helpers._

import EmergencyStop._
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.web.ChooseTemplate
import bootstrap.liftweb.StaticResourceRewrite


object EmergencyStop {

  def panelTemplate = ChooseTemplate(
      List("templates-hidden", "emergency_stop")
    , "emergency-panel"
  )
}

class EmergencyStop {
  private[this] val orchestrator =  RudderConfig.startStopOrchestrator

  def render(html:NodeSeq) : NodeSeq = {

    def stop() = {
      orchestrator.stop

      S.redirectTo(S.uri)
    }
    def start() = {
      orchestrator.start
      S.redirectTo(S.uri)
    }

    orchestrator.status match {
      case ButtonReleased => //show the emergency stop
        (
            "emergency-button" #> SHtml.submit("Confirm", stop)
          & "emergency-body"   #> <h2>This button can be used to force a shutdown of the whole Rudder infastructure. Please use with caution.</h2>
          & "emergency:img"    #> <img src={"/" + StaticResourceRewrite.prefix + "/images/btnAlert.jpg"}/>
          & "emergency-title"  #> Text("Emergency system shutdown")
        )(panelTemplate)

      case ButtonActivated => //show the restart button
        (
            "emergency-button" #> SHtml.submit("Start", start,
                      ("id", "emergencyStartButton"),
                      ("class", "emergencyButton"),
                      ("title","Unlock and restart the orchestrator"))
          & "emergency-body"   #> <h2>Restart the Rudder Infrastructure.</h2>
          & "emergency:img"    #> <img src={"/" + StaticResourceRewrite.prefix + "/images/btnAccept.jpg"}/>
          & "emergency-title"  #> Text("Unlock and restart the orchestrator")
        )(panelTemplate)

    }
  }

}
