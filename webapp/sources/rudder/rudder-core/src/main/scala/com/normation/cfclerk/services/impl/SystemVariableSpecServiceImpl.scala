/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.cfclerk.services.impl

import com.normation.cfclerk.domain.BasicStringVType
import com.normation.cfclerk.domain.BooleanVType
import com.normation.cfclerk.domain.IntegerVType
import com.normation.cfclerk.domain.RegexConstraint
import com.normation.cfclerk.domain.Constraint
import com.normation.cfclerk.domain.SystemVariableSpec
import com.normation.cfclerk.services.MissingSystemVariable
import com.normation.cfclerk.services.SystemVariableSpecService
import com.normation.rudder.reports.ComplianceModeName

class SystemVariableSpecServiceImpl extends SystemVariableSpecService {

  private[this] val varSpecs : Seq[SystemVariableSpec] = Seq(
      SystemVariableSpec(
                             "ALLOWCONNECT" , "List of ip allowed to connect to the node (policyserver + children if any)"
                                            , multivalued = true
                                          )
    , SystemVariableSpec(
                           "CLIENTSFOLDERS" , "List of agent to contact via runagent"
                                            , multivalued = true
                                            , constraint = Constraint(mayBeEmpty=true)
                                          )
    , SystemVariableSpec(
                             "CMDBENDPOINT" , "The cmdb endpoint"
                                            , multivalued  = false
                        )
    , SystemVariableSpec(
                            "COMMUNITYPORT" , "The port used by the community edition"
                                            , multivalued  = false
                        )
    , SystemVariableSpec(        "NODEROLE" , "List of nodeConfiguration roles")
    , SystemVariableSpec(    "TOOLS_FOLDER" , "Tools folder")
    , SystemVariableSpec(
                                  "DAVUSER" , "Username for webdav user"
                                            , multivalued = false
                        )
    , SystemVariableSpec(
                             "DAVPASSWORD"  , "Password for webdav user"
                                            , multivalued = false
                        )
     , SystemVariableSpec(
                   "RUDDER_REPORTS_DB_NAME" , "Name of the Rudder database (rudder by default)"
                                            , multivalued = false
                                          )
    , SystemVariableSpec(
                   "RUDDER_REPORTS_DB_USER" , "Login of the Rudder database user (rudder by default)"
                                            , multivalued = false
                                          )
    , SystemVariableSpec(       "INPUTLIST" , "Input list")
    , SystemVariableSpec(      "BUNDLELIST" , "Bundle list")
    , SystemVariableSpec(
                                    "NOVA" , "The Cfengine Nova agent"
                                           , constraint = Constraint(mayBeEmpty=true)
                        )
    , SystemVariableSpec(      "COMMUNITY" , "The Cfengine Community agent"
                                           , constraint = Constraint(mayBeEmpty=true)
                        )
    , SystemVariableSpec(
                     "SHARED_FILES_FOLDER" , "The path to the shared files folder"
                                           , constraint = Constraint(mayBeEmpty=true)
                        )
    , SystemVariableSpec(
                              "SYSLOGPORT" , "Port to use for rsyslog (used by reports)"
                                           , multivalued = false
                                           , constraint = Constraint(typeName = IntegerVType())
                        )
    , SystemVariableSpec(
         "CONFIGURATION_REPOSITORY_FOLDER" , "The path to the configuration repository folder"
                                           , constraint = Constraint(mayBeEmpty=true)
                        )
    , SystemVariableSpec(
         "RELAY_SYNC_METHOD"               , "Synchronization method for relay; can be classic, rsync or manual "
                                           , multivalued = false
                        )
    , SystemVariableSpec(
         "RELAY_SYNC_PROMISES"             , "Synchronize promises on relay with Rsync in rsync mode"
                                           ,  constraint = Constraint(typeName = BooleanVType, default=Some("true"))
                        )
    , SystemVariableSpec(
         "RELAY_SYNC_SHAREDFILES"          , "Synchronize sharedfiles on relay with Rsync in rsync mode"
                                           , constraint = Constraint(typeName = BooleanVType, default=Some("true"))
                        )

      //
      // The following variables contain information about all the node *directly*
      // managed by a policy server (i.e: we don't have children of relays here)
      //
    , SystemVariableSpec(
                      "MANAGED_NODES_NAME" , "Hostname of nodes managed by the policy server"
                                           , constraint = Constraint(mayBeEmpty=true)
                        )
    , SystemVariableSpec(
                        "MANAGED_NODES_ID" , "UUID of nodes managed by the policy server"
                                           , constraint = Constraint(mayBeEmpty=true)
                        )
    , SystemVariableSpec(
                        "MANAGED_NODES_IP" , "IP of nodes managed by the policy server. A node may have several IPs - they are all here."
                                           , constraint = Constraint(mayBeEmpty=true)
                        )
    , SystemVariableSpec(
                     "MANAGED_NODES_ADMIN" , "Administrator login of nodes managed by the policy server"
                                           , constraint = Constraint(mayBeEmpty=true)
                        )
    , SystemVariableSpec(
                       "MANAGED_NODES_KEY" , "CFEngine KEY (modulus with transformation) of nodes managed by the policy server"
                                           , constraint = Constraint(mayBeEmpty=true)
                        )
      // end
      //
      // The following variables contains information about all the nodes managed by
      // by a policy server AND all of its grand children (i.e also information about
      // node managed by relays connected to that policy server)
      //
    , SystemVariableSpec(
                          "SUB_NODES_NAME" , "Hostname of nodes managed by the policy server AND relays under it"
                                           , constraint = Constraint(mayBeEmpty=true)
                        )
    , SystemVariableSpec(
                            "SUB_NODES_ID" , "UUID of nodes managed by the policy server AND relays under if"
                                           , constraint = Constraint(mayBeEmpty=true)
                        )
    , SystemVariableSpec(
                       "SUB_NODES_KEYHASH" , "Crypto key hash (standard one, not CFEngine format) of node private key"
                                           , constraint = Constraint(mayBeEmpty=true)
                        )
    , SystemVariableSpec(
                        "SUB_NODES_SERVER" , "Policy server to which the node is connected"
                                           , constraint = Constraint(mayBeEmpty=true)
                        )
      // end
    , SystemVariableSpec(
                 "MANAGED_NODES_CERT_UUID" , "UUID of node with certificate"
                                           , constraint = Constraint(mayBeEmpty=true)
                        )
    , SystemVariableSpec(
                   "MANAGED_NODES_CERT_CN" , "CN of certificate of the node"
                                           , constraint = Constraint(mayBeEmpty=true)
                        )
    , SystemVariableSpec(
                  "MANAGED_NODES_CERT_PEM" , "PEM encoded certificate of the node"
                                           , constraint = Constraint(mayBeEmpty=true)
                        )
    , SystemVariableSpec(
                   "MANAGED_NODES_CERT_DN" , "DN of certificate of the node"
                                           , constraint = Constraint(mayBeEmpty=true)
                        )
    , SystemVariableSpec(
                     "AUTHORIZED_NETWORKS" , "Networks authorized to connect to the policy server"
                                           , constraint = Constraint(mayBeEmpty=true)
                        )
      // this variable may be empty, has it is not filled by rudder, but by cf-clerk
    , SystemVariableSpec(
                     "GENERATIONTIMESTAMP" , "Timestamp of the promises generation"
                                           , multivalued = false
                                           , constraint = Constraint(mayBeEmpty=true)
                        )
    , SystemVariableSpec(
                           "DENYBADCLOCKS" , "Should CFEngine server accept connection from agent with a desynchronized clock?"
                                           , multivalued = false
                                           , constraint = Constraint(typeName = BooleanVType, default=Some("true"))

                        )
    , SystemVariableSpec(
                            "SKIPIDENTIFY" , "Should CFEngine server skip the forward DNS lookup for node identification?"
                                           , multivalued = false
                                           , constraint = Constraint(typeName = BooleanVType, default=Some("false"))

                        )
    , SystemVariableSpec(
                      "AGENT_RUN_INTERVAL" , "Run interval (in minutes) at which the agent runs"
                                           , multivalued = false
                                           , constraint = Constraint(typeName = IntegerVType())
                        )
    , SystemVariableSpec(
                     "AGENT_RUN_SPLAYTIME" , "Splaytime (in minutes) for the agent execution"
                                           , multivalued = false
                                           , constraint = Constraint(typeName = IntegerVType())
                        )

    , SystemVariableSpec(
                      "AGENT_RUN_SCHEDULE" , "Schedule for the executor daemon"
                                           , multivalued = false
                        )
    , SystemVariableSpec(
                      "MODIFIED_FILES_TTL" , "Number of days to retain modified files"
                                           , multivalued = false
                                           , constraint = Constraint(typeName = IntegerVType())
                        )
    , SystemVariableSpec(
                    "CFENGINE_OUTPUTS_TTL" , "Number of days to retain CFEngine outputs files"
                                           , multivalued = false
                                           , constraint = Constraint(typeName = IntegerVType())
                        )
    , SystemVariableSpec(
      "STORE_ALL_CENTRALIZED_LOGS_IN_FILE" , "Keep all centralized "
                                           , multivalued = false
                                           , constraint = Constraint(typeName = BooleanVType, default=Some("true"))
                        )
    , SystemVariableSpec(
                     "RUDDER_SERVER_ROLES" , "Mapping of all role <-> hostnames"
                                           , multivalued = false
                                           , constraint = Constraint(mayBeEmpty=true)
                        )
    , SystemVariableSpec(
                      "RUDDER_REPORT_MODE" , "Defines if Rudder should send compliance reports or only change (error, repair) one. (default full-compliance)"
                                           , multivalued = false
                                           , constraint = Constraint(
                                                 typeName = BasicStringVType(
                                                   regex = Some(RegexConstraint(
                                                       pattern = {
                                                         val allModes = ComplianceModeName.allModes.map(_.name).mkString("(", "|", ")")
                                                         allModes
                                                       }
                                                     , errorMsg = {
                                                         val allModes = ComplianceModeName.allModes.map(_.name).mkString("'", "' or '", "'")
                                                         s"Forbiden value, only ${allModes} are authorized"
                                                       }
                                                   ))
                                                 )
                                             , default=Some("full-compliance")
                                           )
                        )
    , SystemVariableSpec(
               "RUDDER_HEARTBEAT_INTERVAL" , "Interval between two heartbeat sending in changes-only mode (in number of runs)"
                                           , multivalued = false
                                           , constraint = Constraint(typeName = IntegerVType())
                        )
    , SystemVariableSpec(
                   "RUDDER_NODE_CONFIG_ID" , "Store the node configuration version (actually an identifier) of a node"
                                           , multivalued = false
                        )
    , SystemVariableSpec(
                            "SEND_METRICS" , "Should the server agent send metrics to Rudder development team"
                                           , multivalued = false
                        )
    , SystemVariableSpec(
                  "RUDDER_SYSLOG_PROTOCOL" , "Which protocol should syslog use"
                                           , multivalued = false
                        )
    , SystemVariableSpec(
       "RUDDER_SYSTEM_DIRECTIVES_SEQUENCE" , "The sequence of bundle to use as method call in bundle rudder_system_directives, in a formatted string"
                                           , multivalued = false
                        )
    , SystemVariableSpec(
         "RUDDER_SYSTEM_DIRECTIVES_INPUTS" , "The list of inputs specific to bundles RUDDER_SYSTEM_DIRECTIVES_SEQUENCE, in a formatted string"
                                           , multivalued = false
                        )
    , SystemVariableSpec(
              "RUDDER_DIRECTIVES_SEQUENCE" , "The sequence of bundle to use as method call in bundle rudder_directives, in a formatted string"
                                           , multivalued = false
                        )
    , SystemVariableSpec(
                "RUDDER_DIRECTIVES_INPUTS" , "The list of inputs specific to bundles RUDDER_DIRECTIVES_SEQUENCE, in a formatted string"
                                           , multivalued = false
                        )
      // we have 2 systems variables for groups:
      // - one to define all the classes related to groups
      // - one to define the variable holding all groups
    , SystemVariableSpec(
              "RUDDER_NODE_GROUPS_CLASSES" , "The classes definition for groups, both group_UUID and group_normalized(NAME), in a formatted string"
                                           , multivalued = false
                        )
    , SystemVariableSpec(
                 "RUDDER_NODE_GROUPS_VARS" , "The array of group_UUID => group_NAME for the node, in a formatted string"
                                           , multivalued = false
                        )
    , SystemVariableSpec(
                              "AGENT_TYPE" , "The normalised name of the agent type (cfengine-community, dsc, etc)"
                                           , multivalued = false
                        )
  )

  private[this] val varSpecsMap = varSpecs.map(x => (x.name -> x)).toMap

  override def get(varName : String): Either[MissingSystemVariable, SystemVariableSpec] = varSpecsMap.get(varName).toRight(MissingSystemVariable(varName))
  override def getAll() : Seq[SystemVariableSpec] = varSpecs
}
