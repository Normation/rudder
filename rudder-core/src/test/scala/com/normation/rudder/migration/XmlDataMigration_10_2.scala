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

package com.normation.rudder.migration

import java.sql.Timestamp
import scala.xml.NodeSeq
import java.sql.PreparedStatement
import java.sql.Connection
import scala.xml.Elem

/*
 * Note: remember to use plain text here, as
 * a failure on that test must rise wondering about
 * a global value modification that was not taken
 * into account in a migration. 
 */
object TestLog {
  //get a default TimeStamp value for that run
  val defaultTimestamp = new Timestamp(System.currentTimeMillis)
}

case class TestLog(
    id       : Option[Long] = None
  , eventType: String
  , timestamp: Timestamp = TestLog.defaultTimestamp
  , principal: String = "TestUser"
  , cause    : Option[Int] = None
  , severity : Int = 100
  , data     : Elem
) {
  
  def insertSql(c: Connection) : Long = {
    //ignore cause id
    val (row, qmark) = cause match {
      case Some(id) => ("causeId", ", ?")
      case None => ("", "")
    }
    
    val INSERT_SQL = "insert into EventLog (creationDate, principal, eventType, severity, data%s) values (?, ?, ?, ?, ?)".format(row, qmark)
    val ps = c.prepareStatement(INSERT_SQL, Array("id"))
    ps.setTimestamp(1, timestamp)
    ps.setString(2, principal)
    ps.setString(3, eventType)
    ps.setInt(4, severity)
    val sqlXml = c.createSQLXML()
    sqlXml.setString(data.toString)
    ps.setSQLXML(5, sqlXml)
    cause.foreach { id => 
      ps.setInt(6, id)
    }
    ps.executeUpdate
    val rs = ps.getGeneratedKeys
    rs.next
    rs.getLong("id")
  }
}

object Migration_10_2_DATA_EventLogs {
  import com.normation.rudder.migration.{
      Migration_10_2_DATA_Rule      => RuleXml
    , Migration_10_2_DATA_Other     => OtherXml
    , Migration_10_2_DATA_Directive => DirectiveXml
    , Migration_10_2_DATA_Group     => GroupXml
  }
  
  val data_10 = Map(
      "rule_add"    -> TestLog(
            eventType = "ConfigurationRuleAdded"
          , data      = RuleXml.rule_add_10
          )
    , "rule_modify" -> TestLog(
            eventType = "ConfigurationRuleModified"
          , data      =  RuleXml.rule_modify_10
          )
    , "rule_delete" -> TestLog(
            eventType = "ConfigurationRuleDeleted"
          , data      = RuleXml.rule_delete_10
          )
    , "addPendingDeployment" -> TestLog(
            eventType = "StartDeployement"
          , data      = OtherXml.addPendingDeployment_10
          )
    , "node_accept" -> TestLog(
            eventType = "AcceptNode"
          , data      = OtherXml.node_accept_10
          )
    , "node_refuse" -> TestLog(
            eventType = "RefuseNode"
          , data      = OtherXml.node_refuse_10
          )
    , "directive_add" -> TestLog(
            eventType = "PolicyInstanceAdded"
          , data      = DirectiveXml.directive_add_10
          )
    , "directive_modify" -> TestLog(
            eventType = "PolicyInstanceModified"
          , data      = DirectiveXml.directive_modify_10
          )
    , "directive_delete" -> TestLog(
            eventType = "PolicyInstanceDeleted"
          , data      = DirectiveXml.directive_delete_10
          )
    , "nodeGroup_add" -> TestLog(
            eventType = "NodeGroupAdded"
          , data      = GroupXml.nodeGroup_add_10
          )
    , "nodeGroup_modify" -> TestLog(
            eventType = "NodeGroupModified"
          , data      = GroupXml.nodeGroup_modify_10
          )
    , "nodeGroup_delete" -> TestLog(
            eventType = "NodeGroupDeleted"
          , data      = GroupXml.nodeGroup_delete_10
          )
  )
  
  val data_2 = Map(
      "rule_add"    -> TestLog(
            eventType = "RuleAdded"
          , data      = RuleXml.rule_add_2
          )
    , "rule_modify" -> TestLog(
            eventType = "RuleModified"
          , data      =  RuleXml.rule_modify_2
          )
    , "rule_delete" -> TestLog(
            eventType = "RuleDeleted"
          , data      = RuleXml.rule_delete_2
          )
    , "addPendingDeployment" -> TestLog(
            eventType = "AutomaticStartDeployement"
          , data      = OtherXml.addPendingDeployment_2
          )
    , "node_accept" -> TestLog(
            eventType = "AcceptNode"
          , data      = OtherXml.node_accept_2
          )
    , "node_refuse" -> TestLog(
            eventType = "RefuseNode"
          , data      = OtherXml.node_refuse_2
          )
    , "directive_add" -> TestLog(
            eventType = "DirectiveAdded"
          , data      = DirectiveXml.directive_add_2
          )
    , "directive_modify" -> TestLog(
            eventType = "DirectiveModified"
          , data      = DirectiveXml.directive_modify_2
          )
    , "directive_delete" -> TestLog(
            eventType = "DirectiveDeleted"
          , data      = DirectiveXml.directive_delete_2
          )
    , "nodeGroup_add" -> TestLog(
            eventType = "NodeGroupAdded"
          , data      = GroupXml.nodeGroup_add_2
          )
    , "nodeGroup_modify" -> TestLog(
            eventType = "NodeGroupModified"
          , data      = GroupXml.nodeGroup_modify_2
          )
    , "nodeGroup_delete" -> TestLog(
            eventType = "NodeGroupDeleted"
          , data      = GroupXml.nodeGroup_delete_2
          )
  )
  
}



object Migration_10_2_DATA_Other {

  val addPendingDeployment_10 =
    <entry><addPending alreadyPending="false"></addPending></entry>


  val addPendingDeployment_2 =
    <entry><addPendingDeployement alreadyPending="false" fileFormat="2"></addPendingDeployement></entry>


  val node_accept_10 =
    <entry><node action="accept" fileFormat="1.0">
      <id>248c8e3d-1bf6-4bc1-9398-f8890b015a50</id>
      <inventoryVersion>2011-10-13T11:43:52.907+02:00</inventoryVersion>
      <hostname>centos-5-32</hostname>
      <fullOsName>Centos</fullOsName>
      <actorIp>127.0.0.1</actorIp>
    </node></entry>

  val node_accept_2 =
    <entry><node action="accept" fileFormat="2">
      <id>248c8e3d-1bf6-4bc1-9398-f8890b015a50</id>
      <inventoryVersion>2011-10-13T11:43:52.907+02:00</inventoryVersion>
      <hostname>centos-5-32</hostname>
      <fullOsName>Centos</fullOsName>
      <actorIp>127.0.0.1</actorIp>
    </node></entry>
    
  val node_refuse_10 = 
    <entry><node fileFormat="1.0" action="accept">
      <id>248c8e3d-1bf6-4bc1-9398-f8890b015a50</id>
      <inventoryVersion>2011-10-13T11:43:52.907+02:00</inventoryVersion>
      <hostname>centos-5-32</hostname>
      <fullOsName>Centos</fullOsName>
      <actorIp>127.0.0.1</actorIp>
    </node></entry>
    
  val node_refuse_2 = 
    <entry><node fileFormat="2" action="accept">
      <id>248c8e3d-1bf6-4bc1-9398-f8890b015a50</id>
      <inventoryVersion>2011-10-13T11:43:52.907+02:00</inventoryVersion>
      <hostname>centos-5-32</hostname>
      <fullOsName>Centos</fullOsName>
      <actorIp>127.0.0.1</actorIp>
    </node></entry>
}

object Migration_10_2_DATA_Rule {
  val rule_add_10 = 
    <entry><configurationRule fileFormat="1.0" changeType="add">
      <id>e7c21276-d2b5-4fff-9924-96b67db9bd1c</id>
      <displayName>configuration</displayName>
      <serial>0</serial>
      <target>group:f4b27025-b5a9-46fe-8289-cf9d56e07a8a</target>
      <policyInstanceIds>
        <id>2813aeb2-6930-11e1-b052-0024e8cdea1f</id>
        <id>2c1b0d34-6930-11e1-b901-0024e8cdea1f</id>
      </policyInstanceIds>
      <shortDescription>configurationconfiguration</shortDescription>
      <longDescription></longDescription>
      <isActivated>true</isActivated>
      <isSystem>false</isSystem>
    </configurationRule></entry>
  
  val rule_add_2 =
    <entry><rule fileFormat="2" changeType="add">
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
    </rule></entry>

  val rule_modify_10 =
    <entry><configurationRule fileFormat="1.0" changeType="modify">
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
        <to>Test application of two (single) policy instances, with two multivalued section.</to>
      </longDescription>
      <target>
        <from>
          <none></none>
        </from>
        <to>group:383d521c-e5a7-4dc2-b402-21d425eefd30</to>
      </target>
      <policyInstanceIds>
        <from></from>
        <to>
          <id>0a50f415-a8da-42aa-9e86-eb045e289de3</id>
        </to>
      </policyInstanceIds>
      <isActivated>
        <from>false</from>
        <to>true</to>
      </isActivated>
    </configurationRule>   </entry> 

  val rule_modify_2 =
    <entry><rule fileFormat="2" changeType="modify">
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
        <to>Test application of two (single) policy instances, with two multivalued section.</to>
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
    </rule>    </entry>
  
  val rule_delete_10 =
    <entry><configurationRule fileFormat="1.0" changeType="delete">
      <id>ad8c48f7-b278-4f0c-83d7-f9cb28e0d440</id>
      <displayName>zada on SLES10</displayName>
      <serial>2</serial>
      <target>group:9bf723d9-0838-4af8-82f7-37912a5093ca</target>
      <policyInstanceIds>
        <id>3fa24049-e673-475d-90ec-e5f9b6b81e38</id>
      </policyInstanceIds>
      <shortDescription></shortDescription>
      <longDescription></longDescription>
      <isActivated>true</isActivated>
      <isSystem>false</isSystem>
    </configurationRule></entry>

  val rule_delete_2 =
    <entry><rule fileFormat="2" changeType="delete">
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
    </rule></entry>
}

object Migration_10_2_DATA_Directive {

  val directive_add_10 =
    <entry><policyInstance fileFormat="1.0" changeType="add">
      <id>2fd5dd7e-c83b-4610-96ad-02002024c2f1</id>
      <displayName>Name resolution 1</displayName>
      <policyTemplateName>dnsConfiguration</policyTemplateName>
      <policyTemplateVersion>1.0</policyTemplateVersion>
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
      <isActivated>true</isActivated>
      <isSystem>false</isSystem>
    </policyInstance>  </entry>  
    
  val directive_add_2 = 
    <entry><directive fileFormat="2" changeType="add">
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
    </directive></entry>

  val directive_modify_10 =
    <entry><policyInstance fileFormat="1.0" changeType="modify">
      <id>70785952-d3b9-4d8e-9df4-1606af6d1ba3</id>
      <policyTemplateName>createFilesFromList</policyTemplateName>
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
    </policyInstance></entry>

  val directive_modify_2 =
    <entry><directive fileFormat="2" changeType="modify">
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
    </directive></entry>

  val directive_delete_10 =
    <entry><policyInstance fileFormat="1.0" changeType="delete">
      <id>2a79eabf-9987-450c-88bf-3c86d4759eb7</id>
      <displayName>Edit crontabs to use "yada"</displayName>
      <policyTemplateName>checkGenericFileContent</policyTemplateName>
      <policyTemplateVersion>2.0</policyTemplateVersion>
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
      <isActivated>true</isActivated>
      <isSystem>false</isSystem>
    </policyInstance></entry>
    
  val directive_delete_2 =
    <entry><directive fileFormat="2" changeType="delete">
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
    </directive></entry>
}

object Migration_10_2_DATA_Group {

  val nodeGroup_add_10 =
    <entry><nodeGroup fileFormat="1.0" changeType="add">
      <id>a73220c8-c3e1-40f1-803b-55d21bc817ec</id>
      <displayName>CentOS</displayName>
      <description>CentOS Group</description>
      <query></query>
      <isDynamic>true</isDynamic>
      <nodeIds></nodeIds>
      <isActivated>true</isActivated>
      <isSystem>false</isSystem>
    </nodeGroup></entry>

  val nodeGroup_add_2 =
    <entry><nodeGroup fileFormat="2" changeType="add">
      <id>a73220c8-c3e1-40f1-803b-55d21bc817ec</id>
      <displayName>CentOS</displayName>
      <description>CentOS Group</description>
      <query></query>
      <isDynamic>true</isDynamic>
      <nodeIds></nodeIds>
      <isEnabled>true</isEnabled>
      <isSystem>false</isSystem>
    </nodeGroup></entry>

  val nodeGroup_modify_10 =
    <entry><nodeGroup fileFormat="1.0" changeType="modify">
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
    </nodeGroup></entry>

  val nodeGroup_modify_2 =
    <entry><nodeGroup fileFormat="2" changeType="modify">
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
    </nodeGroup></entry>


  val nodeGroup_delete_10 =
    <entry><nodeGroup fileFormat="1.0" changeType="delete">
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
      <isActivated>true</isActivated>
      <isSystem>false</isSystem>
    </nodeGroup></entry>
  
  val nodeGroup_delete_2 =
    <entry><nodeGroup fileFormat="2" changeType="delete">
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
    </nodeGroup></entry>

}