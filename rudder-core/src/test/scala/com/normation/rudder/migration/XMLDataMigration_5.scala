/*
*************************************************************************************
* Copyright 2013 Normation SAS
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


object Migration_5_DATA_Rule {

  val rule_add_5 =

    <rule fileFormat="5" changeType="add">
      <id>e7c21276-d2b5-4fff-9924-96b67db9bd1c</id>
      <displayName>configuration</displayName>
      <serial>0</serial>
      <target>group:f4b27025-b5a9-46fe-8289-cf9d56e07a8a</target>
      <directiveIds>
        <id>2813aeb2-6930-11e1-b052-0024e8cdea1f</id>
        <id>2c1b0d34-6930-11e1-b901-0024e8cdea1f</id>
      </directiveIds>
      <shortDescription>configurationconfiguration</shortDescription>
      <longDescription></longDescription>
      <isEnabled>true</isEnabled>
      <isSystem>false</isSystem>
      <category>rootRuleCategory</category>
    </rule>


  val rule_modify_5 =
    <rule fileFormat="5" changeType="modify">
      <id>39720027-952c-4e28-b774-9d5ce63f7a1e</id>
      <displayName>Eutelsat CR Test</displayName>
      <name>
        <from>Eutelsat CR Test</from>
        <to>Users and Fstabs CR</to>
      </name>
      <shortDescription>
        <from>Test CR for Eutelsat</from>
        <to>Test CR</to>
      </shortDescription>
      <longDescription>
        <from></from>
        <to>Test application of two (single) directives, with two multivalued section.</to>
      </longDescription>
      <target>
        <from>
          <none></none>
        </from>
        <to>group:383d521c-e5a7-4dc2-b402-21d425eefd30</to>
      </target>
      <directiveIds>
        <from></from>
        <to>
          <id>0a50f415-a8da-42aa-9e86-eb045e289de3</id>
        </to>
      </directiveIds>
      <isEnabled>
        <from>false</from>
        <to>true</to>
      </isEnabled>
      <category>rootRuleCategory</category>
    </rule>

  val rule_delete_5 =
    <rule fileFormat="5" changeType="delete">
      <id>ad8c48f7-b278-4f0c-83d7-f9cb28e0d440</id>
      <displayName>zada on SLES10</displayName>
      <serial>2</serial>
      <target>group:9bf723d9-0838-4af8-82f7-37912a5093ca</target>
      <directiveIds>
        <id>3fa24049-e673-475d-90ec-e5f9b6b81e38</id>
      </directiveIds>
      <shortDescription></shortDescription>
      <longDescription></longDescription>
      <isEnabled>true</isEnabled>
      <isSystem>false</isSystem>
      <category>rootRuleCategory</category>
    </rule>
}

object Migration_5_DATA_ChangeRequest {
  val cr_rule_change_5 =
    <changeRequest fileFormat="5">
      <groups>
      </groups>
      <directives>
      </directives>
      <rules>
      <rule id="790a3f8a-72e9-47e8-8b61-ee89be2bcf69">
        <initialState>
          <rule fileFormat="5">
            <id>790a3f8a-72e9-47e8-8b61-ee89be2bcf69</id>
            <name>My Rule</name>
            <serial>42</serial>
            <target>group:f4b27025-b5a9-46fe-8289-cf9d56e07a8a</target>
            <directiveIds>
              <id>2813aeb2-6930-11e1-b052-0024e8cdea1f</id>
              <id>2c1b0d34-6930-11e1-b901-0024e8cdea1f</id>
            </directiveIds>
            <shortDescription>my short description</shortDescription>
            <longDescription></longDescription>
            <isEnabled>true</isEnabled>
            <isSystem>false</isSystem>
            <category>rootRuleCategory</category>
          </rule>
        </initialState>
        <firstChange>
          <change>
          <actor>jon.doe</actor>
          <date>2013-04-04T11:21:14.691+02:00</date>
          <reason/>
          <modifyTo>
            <rule fileFormat="5">
              <id>790a3f8a-72e9-47e8-8b61-ee89be2bcf69</id>
              <name>My little Rule</name>
              <serial>42</serial>
              <target>group:f4b27025-b5a9-46fe-8289-cf9d56e07a8a</target>
              <directiveIds>
                <id>2813aeb2-6930-11e1-b052-0024e8cdea1f</id>
                <id>2c1b0d34-6930-11e1-b901-0024e8cdea1f</id>
              </directiveIds>
              <shortDescription>my short description</shortDescription>
              <longDescription></longDescription>
              <isEnabled>true</isEnabled>
              <isSystem>false</isSystem>
              <category>rootRuleCategory</category>
            </rule>
          </modifyTo>
          </change>
        </firstChange>
        <nextChanges>
        </nextChanges>
      </rule>
      </rules>
      <globalParameters>
      </globalParameters>
      </changeRequest>
}