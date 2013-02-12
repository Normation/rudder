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

package com.normation.rudder.repository.ldap

import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._


/**
 * A test to check that the Normation OID is not defined in
 * rudder.schema.
 *
 * We let-it in that file to be able the generate derived
 * schema (for example for UnboundID DS) from it
 */
@RunWith(classOf[JUnitRunner])
class CheckNormationOidTest extends Specification {

  val regex = """.*objectIdentifier NormationOID 1.3.6.1.4.1.35061.*""".r

  val rudderSchemaFile = this.getClass.getClassLoader.getResource("ldap/rudder.schema").getPath

  "Normation OID" should {

    val oidLine = scala.io.Source.fromFile(rudderSchemaFile).getLines.find( l => regex.pattern.matcher(l).matches )

    "exists in rudder.schema" in {
      oidLine.isDefined === true
    }

    "be commented out in rudder.schema" in {
      oidLine.map( _.startsWith("#")).getOrElse(false) === true
    }
  }

}