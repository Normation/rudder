/*
*************************************************************************************
* Copyright 2015 Normation SAS
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
package com.normation.rudder.services.policies.write

import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import com.normation.rudder.services.policies.write.BuildBundleSequence._
import com.normation.cfclerk.domain.Bundle
import com.normation.rudder.domain.policies.PolicyMode

@RunWith(classOf[JUnitRunner])
class PrepareTemplateVariableTest extends Specification {

  val bundles = Seq(
      ("Global configuration for all nodes/20. Install jdk version 1.0"                   , Bundle("Install_jdk_rudder_reporting"))
    , ("Global configuration for all nodes/RUG / YaST package manager configuration (ZMD)", Bundle("check_zmd_settings"))
    , ("""Nodes only/Name resolution version "3.0" and counting"""                        , Bundle("check_dns_configuration"))
    , (raw"""Nodes only/Package \"management\" for Debian"""                              , Bundle("check_apt_package_installation"))
    , (raw"""Nodes only/Package \\"management\\" for Debian - again"""                    , Bundle("check_apt_package_installation2"))
  ).map { case(x,y) => TechniqueBundles(Promiser(x), Nil, y::Nil, Nil, false, false, PolicyMode.Enforce) }

  // Ok, now I can test
  "Preparing the string for writting usebundle of directives" should {

    "correctly write nothing at all when the list of bundle is emtpy" in {
      formatMethodsUsebundle(Seq()) === ""
    }

    "write exactly - including escaped quotes" in {

      //spaces inserted at the begining of promises in rudder_directives.cf are due to string template, not the formated string - strange

      formatMethodsUsebundle(bundles) ===
raw""""Global configuration for all nodes/20. Install jdk version 1.0"                    usebundle => Install_jdk_rudder_reporting;
     |"Global configuration for all nodes/RUG / YaST package manager configuration (ZMD)" usebundle => check_zmd_settings;
     |"Nodes only/Name resolution version \"3.0\" and counting"                           usebundle => check_dns_configuration;
     |"Nodes only/Package \\\"management\\\" for Debian"                                  usebundle => check_apt_package_installation;
     |"Nodes only/Package \\\\\"management\\\\\" for Debian - again"                      usebundle => check_apt_package_installation2;""".stripMargin
    }

  }
}
