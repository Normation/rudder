/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.web.snippet

import com.normation.rudder.services.system.StartStopOrchestrator
import com.normation.rudder.domain.system.{OrchestratorStatus,ButtonActivated,ButtonReleased}
import bootstrap.liftweb.LiftSpringApplicationContext.inject

//lift std import
import scala.xml._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.util._
import Helpers._
import net.liftweb.http.js._
import JsCmds._ // For implicits
import JE._
import net.liftweb.http.SHtml._
import com.normation.exceptions.TechnicalException


object EmergencyStop {
  def templatePath = List("templates-hidden", "emergency_stop")
  def template =  Templates(templatePath) match {
    case Empty | Failure(_,_,_) => 
      throw new TechnicalException("Template for 'emergency stop' not found. I was looking for %s.html".format(templatePath.mkString("/")))
    case Full(n) => n
  }
  
  def panelTemplate = chooseTemplate("emergency","panel",template)
}

import EmergencyStop._

class EmergencyStop {
  val orchestrator =  inject[StartStopOrchestrator]("startStopOrchestrator")
  
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
        bind("emergency",panelTemplate,
            "button" -> SHtml.submit("Confirm", stop),
            "body" -> <h2>This button can be used to force a shutdown of the whole Rudder infastructure. Please use with caution.</h2>,
            "img" -> <img src="/img/btnAlert.jpg"/>,
            "title" -> Text("Emergency system shutdown"))

      case ButtonActivated => //show the restart button
        bind("emergency",panelTemplate,
            "button" -> SHtml.submit("Start", start,
                      ("id", "emergencyStartButton"), 
                      ("class", "emergencyButton"),
                      ("title","Unlock and restart the orchestrator")),
            "body" -> <h2>Restart the Rudder Infrastructure.</h2>,
            "img" -> <img src="/img/btnAccept.jpg"/>,
            "title" -> Text("Unlock and restart the orchestrator"))
        
    }
  }

}
