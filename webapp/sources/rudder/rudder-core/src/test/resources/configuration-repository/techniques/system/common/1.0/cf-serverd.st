# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2021 Normation SAS

#######################################################
#
# Server specific configuration
#
#######################################################

bundle server access_rules
{
  access:
    # Access rules are only defined on a policy server. Standard nodes should not share any files.
    policy_server::
      "${def.dir_masterfiles}"
        handle    => "grant_access_policy",
        comment   => "Grant access to the policy updates",
        maproot => {  @{def.acl}  },
        admit_ips => {  @{def.acl}  };

      "${g.rudder_ncf_origin_common}"
        maproot => {  @{def.acl}  },
        admit_ips => {  @{def.acl}  };

      "${g.rudder_ncf_origin_local}"
        maproot => {  @{def.acl}  },
        admit_ips => {  @{def.acl}  };

      # Deny access to 50_techniques folder
      "${g.rudder_ncf_origin_local}/50_techniques"
        deny_ips  => {  "0.0.0.0/0"  };

&if(MANAGED_NODES_NAME)&

      &if(SHARED_FILES_FOLDER)&
      "&SHARED_FILES_FOLDER&"
        comment   => "Grant access to the share files",
        maproot => {  @{def.acl}  },
        admit_ips => {  @{def.acl}  };

      &endif&

    any::
    &MANAGED_NODES_NAME, MANAGED_NODES_ID, MANAGED_NODES_KEY : {host, uuid, key |
      "/var/rudder/share/&uuid&/"
        maproot => {  @{def.acl}  },
        admit_keys => { "&key&" };

      "/var/rudder/shared-files/&uuid&/"
        maproot => {  @{def.acl}  },
        admit_keys => { "&key&" };

    } &

&endif&

    # Allow server to remotely run the agent
    # Using ${sys.cf_agent} fails, as cf-serverd cannot canonize its path
    !root_server::
      "${g.rudder_command}" comment => "Grant access to rudder agent command for cfruncommand",
        admit_keys => { "&POLICY_SERVER_KEY&" };
    root_server::
      "${g.rudder_command}" comment => "Grant access to rudder agent command for cfruncommand, root is its own policy server",
        admit_keys => { "&POLICY_SERVER_KEY&" };

  roles:
      # Allow user root to set any class
      ".*"  authorize => { "root" };
}

bundle common def
{
  vars:
      "cf_runagent_shell" 
        string  => "/bin/sh",
        comment => "Define path to shell used by cf-runagent";

      "dir_masterfiles" string => translatepath("${sys.workdir}/masterfiles");

   # List here the IP masks that we grant access to on the server

    policy_server.(agent|server)::
      "acl" slist => {
        "127.0.0.0/8" , "::1", @{sys.ip_addresses},
&if(ALLOWED_NETWORKS)&
        host2ip("${server_info.policy_server}"), # the policy server can connect to a relay
        &ALLOWED_NETWORKS:{net|"&net&",}&
&endif&

    };

    !policy_server.(agent|server)::
      "acl" slist => {
        host2ip("${server_info.policy_server}")
      };


    ipv6_enabled::
      "bindtointerface" string => "${node.properties[rudder][server][cf_serverd_bind_address]}";

    !ipv6_enabled::
      "bindtointerface" string => "",
                            if => strcmp("${node.properties[rudder][server][cf_serverd_bind_address]}", "::");

      "bindtointerface" string => "${node.properties[rudder][server][cf_serverd_bind_address]}",
                        unless => strcmp("${node.properties[rudder][server][cf_serverd_bind_address]}", "::");


  classes:
      "ipv6_enabled" expression => fileexists("/proc/net/if_inet6"),
                          scope => "namespace";

}


body server control
{
      policy_server::
        trustkeysfrom     => {
          @{def.acl} ,
        };
      # our policy server should already be trusted after bootstraping
      !policy_server::
        trustkeysfrom     => {};

      any::
        allowconnects     => {
          @{def.acl} ,
        };

        allowallconnects  => {
          @{def.acl} ,
        };

        # force connections via tls1.2 or higher
        allowtlsversion => "1.2";

        maxconnections    => "10000";
        logallconnections => "true";

        # Disallow old protocol
        allowlegacyconnects => {};

        # last single quote in cfruncommand is left open, so that
        # arguments (like -K and --remote-bundles) are properly appended.
        cfruncommand => "${g.rudder_command} agent run -uR";

        allowusers        => {
          "&POLICYSERVER_ADMIN&",
          &MANAGED_NODES_ADMIN : {admin | "&admin&" };separator=", "&
        };

        denybadclocks => "&DENYBADCLOCKS&";

    !debug_port::
        port => "&COMMUNITYPORT&";
    debug_port::
        port => "5310";

    any::
        bindtointerface => "${def.bindtointerface}";


}

#######################################################

&if(MANAGED_NODES_NAME)&
body runagent control
{
        hosts => {
          &MANAGED_NODES_NAME: {
          "&it&:&COMMUNITYPORT&",}&
        };

        max_children => "25";

    !debug_port::
        port => "&COMMUNITYPORT&";
    debug_port::
        port => "5310";
}
&endif&

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
