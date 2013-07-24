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

object Migration_3_DATA_EventLogs {
  import com.normation.rudder.migration.{
      Migration_3_DATA_Rule      => RuleXml
    , Migration_3_DATA_Other     => OtherXml
    , Migration_3_DATA_Directive => DirectiveXml
    , Migration_3_DATA_Group     => GroupXml
  }

  def e(xml:Elem) = <entry>{xml}</entry>

  val data_3 = Map(
      "rule_add"    -> MigrationTestLog(
            eventType = "RuleAdded"
          , data      = e(RuleXml.rule_add_3)
          )
    , "rule_modify" -> MigrationTestLog(
            eventType = "RuleModified"
          , data      =  e(RuleXml.rule_modify_3)
          )
    , "rule_delete" -> MigrationTestLog(
            eventType = "RuleDeleted"
          , data      = e(RuleXml.rule_delete_3)
          )
    , "addPendingDeployment" -> MigrationTestLog(
            eventType = "AutomaticStartDeployement"
          , data      = e(OtherXml.addPendingDeployment_3)
          )
    , "node_accept" -> MigrationTestLog(
            eventType = "AcceptNode"
          , data      = e(OtherXml.node_accept_3)
          )
    , "node_refuse" -> MigrationTestLog(
            eventType = "RefuseNode"
          , data      = e(OtherXml.node_refuse_3)
          )
    , "directive_add" -> MigrationTestLog(
            eventType = "DirectiveAdded"
          , data      = e(DirectiveXml.directive_add_3)
          )
    , "directive_modify" -> MigrationTestLog(
            eventType = "DirectiveModified"
          , data      = e(DirectiveXml.directive_modify_3)
          )
    , "directive_delete" -> MigrationTestLog(
            eventType = "DirectiveDeleted"
          , data      = e(DirectiveXml.directive_delete_3)
          )
    , "nodeGroup_add" -> MigrationTestLog(
            eventType = "NodeGroupAdded"
          , data      = e(GroupXml.nodeGroup_add_3)
          )
    , "nodeGroup_modify" -> MigrationTestLog(
            eventType = "NodeGroupModified"
          , data      = e(GroupXml.nodeGroup_modify_3)
          )
    , "nodeGroup_delete" -> MigrationTestLog(
            eventType = "NodeGroupDeleted"
          , data      = e(GroupXml.nodeGroup_delete_3)
          )
  )

}





object Migration_3_DATA_Other {

  val addPendingDeployment_3 =
    <addPendingDeployement alreadyPending="false" fileFormat="3"></addPendingDeployement>


  val node_accept_3 =
    <node action="accept" fileFormat="3">
      <id>248c8e3d-1bf6-4bc1-9398-f8890b015a50</id>
      <inventoryVersion>2011-10-13T11:43:52.907+02:00</inventoryVersion>
      <hostname>centos-5-32</hostname>
      <fullOsName>Centos</fullOsName>
      <actorIp>127.0.0.1</actorIp>
    </node>


  val node_refuse_3 =
    <node fileFormat="3" action="accept">
      <id>248c8e3d-1bf6-4bc1-9398-f8890b015a50</id>
      <inventoryVersion>2011-10-13T11:43:52.907+02:00</inventoryVersion>
      <hostname>centos-5-32</hostname>
      <fullOsName>Centos</fullOsName>
      <actorIp>127.0.0.1</actorIp>
    </node>

}

object Migration_3_DATA_Rule {


    val rule_add_3 =
    <rule fileFormat="3" changeType="add">
      <id>e7c21276-d2b5-4fff-9924-96b67db9bd1c</id>
      <displayName>configuration</displayName>
      <serial>0</serial>
      <targets>
        <target>group:f4b27025-b5a9-46fe-8289-cf9d56e07a8a</target>
      </targets>
      <directiveIds>
        <id>2813aeb2-6930-11e1-b052-0024e8cdea1f</id>
        <id>2c1b0d34-6930-11e1-b901-0024e8cdea1f</id>
      </directiveIds>
      <shortDescription>configurationconfiguration</shortDescription>
      <longDescription></longDescription>
      <isEnabled>true</isEnabled>
      <isSystem>false</isSystem>
    </rule>

  val rule_modify_3 =
    <rule fileFormat="3" changeType="modify">
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
      <targets>
          <from>
          </from>
          <to>
            <target>
                   group:383d521c-e5a7-4dc2-b402-21d425eefd30
            </target>
          </to>
      </targets>
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
    </rule>


  val rule_delete_3 =
    <rule fileFormat="3" changeType="delete">
      <id>ad8c48f7-b278-4f0c-83d7-f9cb28e0d440</id>
      <displayName>zada on SLES10</displayName>
      <serial>2</serial>
      <targets>
        <target>group:9bf723d9-0838-4af8-82f7-37912a5093ca</target>
      </targets>
      <directiveIds>
        <id>3fa24049-e673-475d-90ec-e5f9b6b81e38</id>
      </directiveIds>
      <shortDescription></shortDescription>
      <longDescription></longDescription>
      <isEnabled>true</isEnabled>
      <isSystem>false</isSystem>
    </rule>
}

object Migration_3_DATA_Directive {



    val directive_add_3 =
    <directive fileFormat="3" changeType="add">
      <id>2fd5dd7e-c83b-4610-96ad-02002024c2f1</id>
      <displayName>Name resolution 1</displayName>
      <techniqueName>dnsConfiguration</techniqueName>
      <techniqueVersion>1.0</techniqueVersion>
      <section name="sections">
        <section name="Nameserver settings">
          <var name="DNS_RESOLVERS_EDIT">false</var>
          <section name="DNS resolvers">
            <var name="DNS_RESOLVERS">192.168.1.1</var>
          </section>
          <section name="DNS resolvers">
            <var name="DNS_RESOLVERS">192.168.1.2</var>
          </section>
        </section>
        <section name="Search suffix settings">
          <var name="DNS_SEARCHLIST_EDIT">false</var>
          <section name="DNS search list">
            <var name="DNS_SEARCHLIST">example1.com</var>
          </section>
          <section name="DNS search list">
            <var name="DNS_SEARCHLIST">example2.com</var>
          </section>
          <section name="DNS search list">
            <var name="DNS_SEARCHLIST">example3.com</var>
          </section>
        </section>
      </section>
      <shortDescription></shortDescription>
      <longDescription></longDescription>
      <priority>5</priority>
      <isEnabled>true</isEnabled>
      <isSystem>false</isSystem>
    </directive>


  val directive_modify_3 =
    <directive fileFormat="3" changeType="modify">
      <id>70785952-d3b9-4d8e-9df4-1606af6d1ba3</id>
      <techniqueName>createFilesFromList</techniqueName>
      <displayName>creatFileTestPI</displayName>
      <parameters>
        <from>
          <section name="sections">
            <section name="File">
              <var name="CREATEFILESFROMLIST_FILE">/tmp/anotherFile</var>
            </section>
            <section name="File">
              <var name="CREATEFILESFROMLIST_FILE">/tmp/anotherFile2</var>
            </section>
          </section>
        </from>
        <to>
          <section name="sections">
            <section name="File">
              <var name="CREATEFILESFROMLIST_FILE">/tmp/anotherFile</var>
            </section>
            <section name="File">
              <var name="CREATEFILESFROMLIST_FILE">/tmp/anotherFile2</var>
            </section>
            <section name="File">
              <var name="CREATEFILESFROMLIST_FILE">/tmp/anotherFile3</var>
            </section>
          </section>
        </to>
      </parameters>
    </directive>

  val directive_delete_3 =
    <directive fileFormat="3" changeType="delete">
      <id>2a79eabf-9987-450c-88bf-3c86d4759eb7</id>
      <displayName>Edit crontabs to use "yada"</displayName>
      <techniqueName>checkGenericFileContent</techniqueName>
      <techniqueVersion>2.0</techniqueVersion>
      <section name="sections">
        <section name="File to manage">
          <section name="File">
            <var name="GENERIC_FILE_CONTENT_PATH">/var/spool/cron/tabs/root</var>
            <var name="GENERIC_FILE_CONTENT_PAYLOAD">* * * * * /home/wimics/yada</var>
            <var name="GENERIC_FILE_CONTENT_ENFORCE">false</var>
          </section>
          <section name="Permission adjustment">
            <var name="GENERIC_FILE_CONTENT_PERMISSION_ADJUSTMENT">true</var>
            <var name="GENERIC_FILE_CONTENT_OWNER">root</var>
            <var name="GENERIC_FILE_CONTENT_GROUP">root</var>
            <var name="GENERIC_FILE_CONTENT_PERM">644</var>
          </section>
          <section name="Post-modification hook">
            <var name="GENERIC_FILE_CONTENT_POST_HOOK_RUN">false</var>
            <var name="GENERIC_FILE_CONTENT_POST_HOOK_COMMAND"></var>
          </section>
        </section>
      </section>
      <shortDescription></shortDescription>
      <longDescription></longDescription>
      <priority>5</priority>
      <isEnabled>true</isEnabled>
      <isSystem>false</isSystem>
    </directive>
}

object Migration_3_DATA_Group {


     val nodeGroup_add_3 =
    <nodeGroup fileFormat="3" changeType="add">
      <id>a73220c8-c3e1-40f1-803b-55d21bc817ec</id>
      <displayName>CentOS</displayName>
      <description>CentOS Group</description>
      <query></query>
      <isDynamic>true</isDynamic>
      <nodeIds></nodeIds>
      <isEnabled>true</isEnabled>
      <isSystem>false</isSystem>
    </nodeGroup>


  val nodeGroup_modify_3 =
    <nodeGroup fileFormat="3" changeType="modify">
      <id>hasPolicyServer-root</id>
      <displayName>Root server group</displayName>
      <nodeIds>
        <from>
          <id>root</id>
          <id>248c8e3d-1bf6-4bc1-9398-f8890b015a50</id>
        </from>
        <to>
          <id>root</id>
          <id>248c8e3d-1bf6-4bc1-9398-f8890b015a50</id>
          <id>06da3556-5204-4bd7-b3b0-fa5e7bcfbbea</id>
        </to>
      </nodeIds>
    </nodeGroup>


  val nodeGroup_delete_3 =
    <nodeGroup fileFormat="3" changeType="delete">
      <id>4e0e8d5e-c87a-445c-ac81-a0e7a2b9e5e6</id>
      <displayName>All debian</displayName>
      <description></description>
      <query>
        {{"select":"node","composition":"And","where":[{{"objectType":"node","attribute":"osName","comparator":"eq","value":"Debian"}}]}}
      </query>
      <isDynamic>true</isDynamic>
      <nodeIds>
        <id>b9a71482-5030-4699-984d-b03d28bbbf36</id>
        <id>0876521e-3c81-4775-85c7-5dd7f9d5d3da</id>
      </nodeIds>
      <isEnabled>true</isEnabled>
      <isSystem>false</isSystem>
    </nodeGroup>
}




object Migration_3_DATA_ChangeRequest {
  val cr_directive_change_3 =
    <changeRequest fileFormat="3">
      <groups>
      </groups>
      <directives>
        <directive id="790a3f8a-72e9-47e8-8b61-ee89be2bcf68">
            <initialState>
              <directive fileFormat="3"><id>790a3f8a-72e9-47e8-8b61-ee89be2bcf68</id><displayName>aaaaaaaaaaaaaaaaaaa</displayName><techniqueName>aptPackageInstallation</techniqueName><techniqueVersion>1.0</techniqueVersion><section name="sections"><section name="Debian/Ubuntu packages"><var name="APT_PACKAGE_DEBACTION">add</var><var name="APT_PACKAGE_DEBLIST">f</var></section></section><shortDescription/><longDescription/><priority>5</priority><isEnabled>true</isEnabled><isSystem>false</isSystem></directive>
            </initialState>
              <firstChange>
                <change>
                  <actor>jon.doe</actor>
                  <date>2013-04-04T11:21:14.691+02:00</date>
                  <reason/>
                  <modifyTo><directive fileFormat="3"><id>790a3f8a-72e9-47e8-8b61-ee89be2bcf68</id><displayName>aaaaaaaaaaaaaaaaaaa</displayName><techniqueName>aptPackageInstallation</techniqueName><techniqueVersion>1.0</techniqueVersion><section name="sections"><section name="Debian/Ubuntu packages"><var name="APT_PACKAGE_DEBACTION">add</var><var name="APT_PACKAGE_DEBLIST">f</var></section></section><shortDescription/><longDescription/><priority>5</priority><isEnabled>true</isEnabled><isSystem>false</isSystem></directive></modifyTo>
                </change>
              </firstChange>
            <nextChanges>
            </nextChanges>
          </directive>
      </directives>
      <rules>
      </rules>
    </changeRequest>
}