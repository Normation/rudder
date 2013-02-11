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

import com.normation.rudder.web.services._
import com.normation.inventory.ldap.core._
import com.normation.inventory.ldap.core.LDAPConstants._

import com.unboundid.ldap.sdk.{SearchScope,SearchResult}

import scala.collection.mutable.Buffer
import scala.collection.JavaConversions._

import org.slf4j.{Logger,LoggerFactory}

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

object Util {
  val logger = LoggerFactory.getLogger(classOf[Util])
}

class Util {
  /*
   * Draws a form with an input field, a submit button
   * and a 'clear' link
   * The clear link targets redirectRoot
   * When submitted, the form redirect to
   * redirectRoot/input_value if input_value is
   * not empty
   * More over, the initial value of the field is taken
   * from the request, in the variable reqvarName
   * (or use "" if no such variable is set in request)
   */
  def redirectInput(xhtml:NodeSeq) : NodeSeq = {

    //default redirect root
    var redirectRoot = "/"
    var reqvarName = "filter"

    xhtml.filter(e => e.prefix =="param") foreach { e =>
      e.label match {
        case "root" => redirectRoot = e.text
        case "reqvar" => reqvarName = e.text
        case x =>  Util.logger.warn("Given parameter '{}' of snippet Util.redirectInput is not recognized (knows 'root' and 'reqvar'). Check Spelling.",x) //nothing
      }
    }


    var filter = S.param(reqvarName).getOrElse("")

    def processFilter () {
      if(filter == "") S.redirectTo(redirectRoot)
      else S.redirectTo(redirectRoot+ "/" + filter)
    }

    <xml:group>{SHtml.text(filter, f => filter = f, "maxlength" -> "40"
      )} {SHtml.submit("Filter", processFilter
      )} <a href={redirectRoot}>Clear</a></xml:group>
  }

}
