/*
*************************************************************************************
* Copyright 2012 Normation SAS
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

package com.normation.rudder.migration

import scala.xml.Elem

object Migration_4_DATA_ChangeRequest {
  val cr_directive_change_4 =
    <changeRequest fileFormat="4">
      <groups>
      </groups>
      <directives>
        <directive id="790a3f8a-72e9-47e8-8b61-ee89be2bcf68">
            <initialState>
              <directive fileFormat="4"><id>790a3f8a-72e9-47e8-8b61-ee89be2bcf68</id><displayName>aaaaaaaaaaaaaaaaaaa</displayName><techniqueName>aptPackageInstallation</techniqueName><techniqueVersion>1.0</techniqueVersion><section name="sections"><section name="Debian/Ubuntu packages"><var name="APT_PACKAGE_DEBACTION">add</var><var name="APT_PACKAGE_DEBLIST">f</var></section></section><shortDescription/><longDescription/><priority>5</priority><isEnabled>true</isEnabled><isSystem>false</isSystem></directive>
            </initialState>
              <firstChange>
                <change>
                  <actor>jon.doe</actor>
                  <date>2013-04-04T11:21:14.691+02:00</date>
                  <reason/>
                  <modifyTo><directive fileFormat="4"><id>790a3f8a-72e9-47e8-8b61-ee89be2bcf68</id><displayName>aaaaaaaaaaaaaaaaaaa</displayName><techniqueName>aptPackageInstallation</techniqueName><techniqueVersion>1.0</techniqueVersion><section name="sections"><section name="Debian/Ubuntu packages"><var name="APT_PACKAGE_DEBACTION">add</var><var name="APT_PACKAGE_DEBLIST">f</var></section></section><shortDescription/><longDescription/><priority>5</priority><isEnabled>true</isEnabled><isSystem>false</isSystem></directive></modifyTo>
                </change>
              </firstChange>
            <nextChanges>
            </nextChanges>
          </directive>
      </directives>
      <rules>
      </rules>
      <globalParameters>
      </globalParameters>
      </changeRequest>
}