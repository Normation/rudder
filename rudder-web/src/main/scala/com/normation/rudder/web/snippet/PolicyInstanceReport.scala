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

import bootstrap.liftweb.LiftSpringApplicationContext.inject
import com.normation.rudder.services.reports.ReportingService

import scala.xml._
import net.liftweb.common._
import net.liftweb.http._
import js._
import JsCmds._
import JE._
import net.liftweb.util.Helpers._
import com.normation.cfclerk.domain.CFCPolicyInstanceId

class PolicyInstanceReport {
  val reportingService = inject[ReportingService]

  def render(in:NodeSeq):NodeSeq = {
    (for {
      piUuid <- S.attr("piUuid")
      img <- Full("report-none.png")
      /*{ reportingService.findLastReportsByPolicyInstance(CFCPolicyInstanceId(piUuid)) match {
        case None                                                                                     => Full("report-none.png")
        case Some(execution) if (execution.getErrorServer.size>0)                                     => Full("report-fail.png")
        case Some(execution) if (execution.getServerWithNoReports.size > 0)                           => Full("report-none.png")
        case Some(execution) if (execution.getWarnServer.size>0)                                      => Full("report-warn.png")
        case Some(execution) if (execution.getSuccessServer.size == execution.allExpectedServer.size) => Full("report-ok.png")
        case _ => Empty
      } }*/
    } yield {
      <span class="policyReports">
        <a href={S.attr("reportLink").map(x => "%s/%s".format(x,piUuid)).openOr("#")}>
          <img src={"/images/" + img}/>
        </a>
      </span>
      
    }) openOr <p class="error">Error when trying to retrieve report</p>
  }    

}