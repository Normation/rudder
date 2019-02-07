#####################################################################################
# Copyright 2011 Normation SAS
#####################################################################################
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, Version 3.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.
#
#####################################################################################

#######################################################
#
# Server specific configuration
#
#######################################################

bundle server access_rules
{
&if(NOVA)&
  vars:
    enterprise::
      "query_types" slist => {"delta", "rebase", "full"};
&endif&

  # Access rules are only defined on a policy server. Standard nodes should not share any files.
  access:

    policy_server|role_rudder_relay_promises_only::
      "${def.dir_masterfiles}"
        handle  => "grant_access_policy",
        comment => "Grant access to the policy updates",
        maproot => {  @{def.acl}  },
        admit   => {  @{def.acl}  };

      "${g.rudder_tools}"
        maproot => {  @{def.acl}  },
        admit   => {  @{def.acl}  };

&if(MANAGED_NODES_NAME)&
      "${g.rudder_ncf_origin_common}"
        maproot => {  @{def.acl}  },
        admit   => {  @{def.acl}  };

      "${g.rudder_ncf_origin_local}"
        maproot => {  @{def.acl}  },
        admit   => {  @{def.acl}  };

      # Deny access to 50_techniques folder
      "${g.rudder_ncf_origin_local}/50_techniques"
        deny    => {  ".*"  };

      &if(SHARED_FILES_FOLDER)&
      "&SHARED_FILES_FOLDER&"
        comment => "Grant access to the share files",
        maproot => {  @{def.acl}  },
        admit   => {  @{def.acl}  };

      &endif&

  &if(NOVA)&
    role_rudder_cfengine_mission_portal::
      "${query_types}"
        handle             => "server_access_grant_${query_types}_for_hub",
        comment            => "Grant ${query_types} reporting query for the hub on the policy server",
        resource_type      => "query",
        report_data_select => rudder_data_select_policy_hub,
        admit              => { ${def.policy_server}, @{sys.ip_addresses} }; # an enterprise policy server needs to be able to contact itself

  &endif&

    any::
  &if(SKIPIDENTIFY)&
    &MANAGED_NODES_NAME, MANAGED_NODES_ID, MANAGED_NODES_KEY: {host, uuid, key |
      "/var/rudder/share/&uuid&/"
        maproot    => { string_downcase(escape("&host&"))  },
        admit      => { string_downcase(escape("&host&"))  },
        admit_keys => { "&key&" };

      "/var/rudder/shared-files/&uuid&/"
        maproot    => { string_downcase(escape("&host&"))  },
        admit      => { string_downcase(escape("&host&"))  },
        admit_keys => { "&key&" };

    } &
  &else&
    &MANAGED_NODES_NAME, MANAGED_NODES_ID, MANAGED_NODES_KEY : {host, uuid, key |
      "/var/rudder/share/&uuid&/"
        maproot    => { host2ip("&host&"), string_downcase(escape("&host&"))  },
        admit      => { host2ip("&host&"), string_downcase(escape("&host&"))  },
        admit_keys => { "&key&" };

      "/var/rudder/shared-files/&uuid&/"
        maproot    => { host2ip("&host&"), string_downcase(escape("&host&"))  },
        admit      => { host2ip("&host&"), string_downcase(escape("&host&"))  },
        admit_keys => { "&key&" };

    } &
  &endif&

&endif&

    # Allow server to remotely run the agent
    # Using ${sys.cf_agent} fails, as cf-serverd cannot canonize its path
    windows::
      "${sys.workdir}\bin\cf-agent.exe"
        admit   => { host2ip("${server_info.cfserved}"), string_downcase(escape("${server_info.cfserved}")) };

    !windows::
      "${g.rudder_command}" comment => "Grant access to rudder agent command for cfruncommand",
        admit => { host2ip("${server_info.cfserved}"), string_downcase(escape("${server_info.cfserved}")) };

&if(NOVA)&
    enterprise_edition::
      "${query_types}"
        handle             => "server_access_grant_${query_types}_for_hosts",
        comment            => "Grant ${query_types} reporting query for the hub on the hosts",
        resource_type      => "query",
        report_data_select => rudder_data_select_host,
        admit              => { ${def.policy_server}, @{sys.ip_addresses} };
&endif&

  roles:
      # Allow user root to set any class
      ".*"  authorize => { "root" };
}

bundle common def
{
  vars:
      "policy_server_file"
        string  => translatepath("${sys.workdir}/policy_server.dat"),
        comment => "Path to file containing address to policy server";

      # Use "rudder" as default policy_server if no policy_server.dat exists
      "policy_server"
        string  => ifelse(fileexists("${policy_server_file}"),
                          readfile("${policy_server_file}", 2048),
                          "rudder"),
        comment => "IP address or hostname to locate your policy host.";

    !windows::
      "cf_runagent_shell" 
        string  => "/bin/sh",
        comment => "Define path to shell used by cf-runagent";

    any::

      "dir_masterfiles" string => translatepath("${sys.workdir}/masterfiles");

   # List here the IP masks that we grant access to on the server

    policy_server::
      "acl" slist => {
      "127.0.0.0/8" , "::1",
&if(AUTHORIZED_NETWORKS)&
      host2ip("${def.policy_server}"), # the policy server can connect to a relay
      &AUTHORIZED_NETWORKS:{net|"&net&",}&
&endif&

    };

    !policy_server::
      "acl" slist => {
      "${def.policy_server}", host2ip("${def.policy_server}")
      };

}


body server control
{
&if(SKIPIDENTIFY)&
        trustkeysfrom     => {
          @{def.acl} ,
          &if(MANAGED_NODES_NAME)&
          &MANAGED_NODES_NAME: {
          "&it&"};separator=", "&
          &endif&

        }; #trustkey allows the exchange of keys

        allowconnects     => {
          @{def.acl} ,
          &if(MANAGED_NODES_NAME)&
          &MANAGED_NODES_NAME: {
          "&it&"};separator=", "&
          &endif&

        };

        allowallconnects  => {
          @{def.acl} ,
          &if(MANAGED_NODES_NAME)&
          &MANAGED_NODES_NAME: {
          "&it&"};separator=", "&
          &endif&

        };

&else&
        trustkeysfrom     => {
          @{def.acl} ,
          &if(MANAGED_NODES_NAME)&
          &MANAGED_NODES_NAME: {
          host2ip("&it&"), "&it&"};separator=", "&
          &endif&

        }; #trustkey allows the exchange of keys

        allowconnects     => {
          @{def.acl} ,
          &if(MANAGED_NODES_NAME)&
          &MANAGED_NODES_NAME: {
          host2ip("&it&"), "&it&"};separator=", "&
          &endif&

        };


        allowallconnects  => {
          @{def.acl} ,
          &if(MANAGED_NODES_NAME)&
          &MANAGED_NODES_NAME: {
          host2ip("&it&"), "&it&"};separator=", "&
          &endif&

        };

&endif&

        maxconnections    => "1000";
        logallconnections => "true";

        allowlegacyconnects => {
            host2ip("${def.policy_server}"), # the policy server can connect
            &if(AUTHORIZED_NETWORKS)&
            &AUTHORIZED_NETWORKS:{net|"&net&",}&
            &endif&

        };

      windows::
        cfruncommand => "${sys.cf_agent} -I -D cfruncommand -f \"failsafe.cf\"  \&
                        ${sys.cf_agent} -I -D cfruncommand";

      !windows::
        # last single quote in cfruncommand is left open, so that
        # arguments (like -K and --remote-bundles) are properly appended.
        cfruncommand => "${g.rudder_command} agent run -uR";

        allowusers        => {
          "&POLICYSERVER_ADMIN&",
          &MANAGED_NODES_ADMIN : {admin | "&admin&" };separator=", "&
        };

        denybadclocks => "&DENYBADCLOCKS&";

    community_edition.!debug_port::
        port => "&COMMUNITYPORT&";
    community_edition.debug_port::
        port => "5310";

}

#######################################################

&if(MANAGED_NODES_NAME)&
body runagent control
{
  &if(NOVA)&
  # If using enterprise, the nodes to connect to are using default port
        hosts => {
          &MANAGED_NODES_NAME: {
          "&it&",}&
        };
  &else&
  # When using community, we must use the port &COMMUNITYPORT& to connect to nodes
        hosts => {
          &MANAGED_NODES_NAME: {
          "&it&:&COMMUNITYPORT&",}&
        };

  &endif&

        max_children => "25";

    community_edition.!debug_port::
        port => "&COMMUNITYPORT&";
    debug_port::
        port => "5310";
}
&endif&

&if(NOVA)&
body report_data_select rudder_data_select_host
# @brief Data to collect from remote hosts by default
#
# By convention variables and classes known to be internal, (having no
# reporting value) should be prefixed with an underscore. By default the policy
# framework explicitly excludes these variables and classes from collection.
{
    metatags_include       => { "inventory", "report" };
    metatags_exclude       => { "noreport" };
    monitoring_include     => { ".*" };
    promise_handle_exclude => { "noreport_.*" };
}

body report_data_select rudder_data_select_policy_hub
# @brief Data to collect from policy servers by default
#
# By convention variables and classes known to be internal, (having no
# reporting value) should be prefixed with an underscore. By default the policy
# framework explicitly excludes these variables and classes from collection.
{
    metatags_include       => { "inventory", "report" };
    metatags_exclude       => { "noreport" };
    monitoring_include     => { ".*" };
    promise_handle_exclude => { "noreport_.*" };
}
&endif&
