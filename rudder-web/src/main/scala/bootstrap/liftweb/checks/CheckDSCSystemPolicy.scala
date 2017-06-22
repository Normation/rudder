/*
*************************************************************************************
* Copyright 2017 Normation SAS
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

package bootstrap.liftweb.checks

import com.normation.ldap.ldif.LDIFNoopChangeRecord
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.LDAPEntry
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.ldap.sdk.TRUE
import com.normation.utils.Control._
import com.unboundid.ldap.sdk.Attribute
import com.unboundid.ldap.sdk.DN
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import bootstrap.liftweb.BootstrapChecks

/**
 * The DSC plugin install:
 * - a new system group for all node based on DSC agent,
 * - a system directive common to all DSC agent based on system technique "dsc-agent"
 * - a rule binding the two last things.
 */
class CheckDSCSystemPolicy(
    ldap          : LDAPConnectionProvider[RwLDAPConnection]
) extends BootstrapChecks {
  import CheckCfengineSystemRuleTargets.compare

  override val description = "Check that system group / directive / rules for DSC are present"

  override def checks() : Unit = {

    val dscGroup = LDAPEntry(new DN("nodeGroupId=all-nodes-with-dsc-agent,groupCategoryId=SystemGroups,groupCategoryId=GroupRoot,ou=Rudder,cn=rudder-configuration"),
        new Attribute("objectClass", "nodeGroup", "top")
      , new Attribute("cn", "All nodes with a Rudder Windows DSC agent")
      , new Attribute("isDynamic", TRUE.toLDAPString)
      , new Attribute("nodeGroupId", "all-nodes-with-dsc-agent")
      , new Attribute("description", "All nodes with a Rudder Windows DSC agent")
      , new Attribute("isEnabled", TRUE.toLDAPString)
      , new Attribute("isSystem", TRUE.toLDAPString)
      , new Attribute("jsonNodeGroupQuery", """{"select":"nodeAndPolicyServer","composition":"And","where":[
                                              |{"objectType":"node","attribute":"agentName","comparator":"eq","value":"dsc"}
                                              |]}""".stripMargin.replaceAll("""\n""", "")) //needed to have consistant value regarding spacestored in LDAP
    )

    val dscCommonTechnique = LDAPEntry(new DN("activeTechniqueId=dsc-common,techniqueCategoryId=Rudder Internal,techniqueCategoryId=Active Techniques,ou=Rudder,cn=rudder-configuration"),
        new Attribute("objectClass", "activeTechnique", "top")
      , new Attribute("acceptationTimestamp", """{"1.0":"20170101010100.000Z"}""")
      , new Attribute("activeTechniqueId", "dsc-common")
      , new Attribute("techniqueId", "dsc-common")
      , new Attribute("isEnabled", TRUE.toLDAPString)
      , new Attribute("isSystem", TRUE.toLDAPString)
    )

    val dscCommonDirective = LDAPEntry(new DN("directiveId=dsc-common-all,activeTechniqueId=dsc-common,techniqueCategoryId=Rudder Internal,techniqueCategoryId=Active Techniques,ou=Rudder,cn=rudder-configuration"),
        new Attribute("objectClass", "directive", "top")
      , new Attribute("directiveId", "dsc-common-all")
      , new Attribute("techniqueVersion", "1.0")
      , new Attribute("cn", "DSC Based agent configuration")
      , new Attribute("description", "System configuration for base agent")
      , new Attribute("directivePriority", "0")
      , new Attribute("isEnabled", TRUE.toLDAPString)
      , new Attribute("isSystem", TRUE.toLDAPString)
      , new Attribute("serializedTags", "[]")
    )

    val dscCommonRule = LDAPEntry(new DN("ruleId=dsc-agent-all,ou=Rules,ou=Rudder,cn=rudder-configuration"),
        new Attribute("objectClass", "rule", "top")
      , new Attribute("ruleId", "dsc-agent-all")
      , new Attribute("serial", "0")
      , new Attribute("cn", "Rudder system policy: base configuration for DSC based agent")
      , new Attribute("description", "Rudder system policy: base configuration for DSC based agent")
      , new Attribute("longDescription", "This rule manage based configuration for DSC agent")
      , new Attribute("ruleTarget", "group:dsc-based-agent")
      , new Attribute("directiveId", "dsc-common-all")
      , new Attribute("isEnabled", TRUE.toLDAPString)
      , new Attribute("isSystem", TRUE.toLDAPString)
    )

    val all = dscGroup :: dscCommonTechnique :: dscCommonDirective :: dscCommonRule :: Nil

    /*
     * check that each entry exists and is up to date)
     */
    ldap.map { con =>
      sequence(all) { wanted =>
        con.get(wanted.dn) match {
          case Full(entry) =>
            compare(con, logger, wanted, entry)
          case eb: EmptyBox =>
            // it can be an error or just a missing (bad semantic from the lib here)
            // so try to add it
              con.save(wanted, true).map { mod =>
                logger.info(s"Updating system configuration for DSC based agent stored in entry '${wanted.dn.toString}': ${mod}")
              }
        }
      }
    }
  }
}

