bundle server access_rules
{
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

    any::
    &MANAGED_NODES_NAME, MANAGED_NODES_ID : {host, uuid |
      "/var/rudder/share/&uuid&/"
        maproot => { host2ip("&host&"), string_downcase(escape("&host&"))  },
        admit   => { host2ip("&host&"), string_downcase(escape("&host&"))  };

     } &

&endif&
    policy_server_set::
      # Allow server to remotely run the agent
      "${sys.workdir}/bin/cf-agent"
        admit   => { host2ip("${server_info.cfserved}"), string_downcase(escape("${server_info.cfserved}")) };

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

   # Use localhost as default policy_server if no policy_server.dat exists
    policy_server_set::
      "policy_server"
        string  => readfile("${policy_server_file}", 2048),
        comment => "IP address or hostname to locate your policy host.";
    !policy_server_set::
      "policy_server"
        string  => "rudder",
        comment => "IP address by default without ${def.policy_server_file} file";

    any::

      "dir_masterfiles" string => translatepath("${sys.workdir}/masterfiles");

   # List here the IP masks that we grant access to on the server

&if(ALLOWED_NETWORKS)&
    policy_server::
      "acl" slist => {
      "127.0.0.0/8" , "::1",
      host2ip("${def.policy_server}"), # the policy server can connect to a relay
      &ALLOWED_NETWORKS:{net|"&net&",}&
    };
&endif&


    !policy_server.policy_server_set::
      "acl" slist => {
      "${def.policy_server}", host2ip("${def.policy_server}")
    };

  classes:
      "policy_server_set" expression => fileexists("${def.policy_server_file}");
}


body server control
{
        trustkeysfrom     => {
          @{def.acl} ,
          &if(MANAGED_NODES_NAME)&
          &MANAGED_NODES_NAME: {
          host2ip("&it&"), "&it&"};separator=","&
          &endif&
        }; #trustkey allows the exchange of keys

        allowconnects     => {
          @{def.acl} ,
          &if(MANAGED_NODES_NAME)&
          &MANAGED_NODES_NAME: {
          host2ip("&it&"), "&it&"};separator=","&
          &endif&
        };

        allowallconnects  => {
          @{def.acl} ,
          &if(MANAGED_NODES_NAME)&
          &MANAGED_NODES_NAME: {
          host2ip("&it&"), "&it&"};separator=","&
          &endif&
        };
        maxconnections    => "1000";
        logallconnections => "true";

        cfruncommand      => "${sys.workdir}/bin/cf-agent -f failsafe.cf \&\& ${sys.workdir}/bin/cf-agent";
        allowusers        => {
          "&POLICYSERVER_ADMIN&",
          &MANAGED_NODES_ADMIN : {admin |"&admin&" };separator=","&
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
  # When using community, we must use the port &COMMUNITYPORT& to connect to nodes
        hosts => {
          &MANAGED_NODES_NAME: {
          "&it&:&COMMUNITYPORT&",}&
        };

        max_children => "25";

    community_edition.!debug_port::
        port => "&COMMUNITYPORT&";
    community_edition.debug_port::
        port => "5310";
}
&endif&
