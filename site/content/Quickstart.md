Title:Quickstart


In which we apply our first policy written in ncf.


## Install ncf

You will need ncf and CFEngine3 to follow this quickstart.

Add CFEngine repository and install cfengine3:

    wget -qO- https://s3.amazonaws.com/cfengine.package-repos/pub/gpg.key | apt-key add -
    echo "deb http://cfengine.com/pub/apt/packages stable main" > /etc/apt/sources.list.d/cfengine-community.list
    apt-get update
    apt-get install cfengine-community

Add ncf repository and install ncf:

    apt-key adv --recv-keys --keyserver keyserver.ubuntu.com 474A19E8
    echo "deb http://www.rudder-project.org/apt-latest/ $(lsb_release -cs) main" > /etc/apt/sources.list.d/ncf.list
    apt-get update
    apt-get install ncf

Copy the ncf tree to your policy directory:

    cp -r /usr/share/ncf/tree/* /var/cfengine/inputs/ # this is the default directory for cf-agent
    # you may change the owner to make editing it easier

Use ncf
-------

You can find ncf documentation at http://www.ncf.io/

Most of your work should go to 50_techniques (generic system configuration methods) and 60_services (final configuration with parameters).

Let's create a technique to force bash timeout on servers:

* Create 50_techniques/shell_timeout/shell_timeout.cf with:

        # @name Force timeout in the shell
        # @description Force timeout in the shell using TMOUT (bash only)
        # @version 0.1

        bundle agent shell_timeout(timeout) {
          vars:
            "timeout_file" string => "/etc/profile";

          methods:
            # modify line if it exists
            "config"  usebundle  => file_replace_lines("${timeout_file}", "^export TMOUT=(?!500$).*", "export TMOUT=${shell_timeout.timeout}");
            # append it otherwise
            "config"  usebundle  => file_ensure_lines_present("${timeout_file}", "export TMOUT=${shell_timeout.timeout}");
        }

* Now we could call this technique from 60_services/baseline/stable/baseline.cf which would be sufficient
* But instead, create 60_services/servers/stable/servers.cf with:

        bundle agent servers(path)
        {
           methods:
              "any" usebundle => shell_timeout(500);
        }

* To activate this service in ncf, modify service_mapping.cf and add "/servers/stable" to the "base_services" slist

Test it, run:

    cf-agent --no-lock --inform
    grep TMOUT /etc/profile

Yep, it works !


Use ncf on a policy server:
---------------------------

If you want to use ncf with a CFEngine policy server, you only need to install NCF on the server.
However you still need CFEngine on the client to retrieve and use ncf policies. 

If you do not have a CFEngine server yet, install cfengine on both the client and the server as shown above.

Then on the server:

    /var/cfengine/bin/cf-agent --bootstrap <IP address of self>

And on the client:

    /var/cfengine/bin/cf-agent --bootstrap <IP address of server>

And you must use /var/cfengine/masterfiles instead of /var/cfengine/inputs everywhere in this documentation when installing ncf.
