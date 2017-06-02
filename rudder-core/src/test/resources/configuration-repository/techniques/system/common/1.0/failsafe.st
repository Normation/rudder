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

#
# Failsafe file
#

body common control
{
        bundlesequence     => { "check_uuid", "init_files", "update_action" };

        inputs             => { "common/1.0/update.cf" };
        output_prefix      => "rudder";

        protocol_version   => "2";
}

body agent control {
        skipidentify => "&SKIPIDENTIFY&";
}

bundle common g
{
  vars:
    android::
      "rudder_base"               string => "/data/rudder";
      "rudder_var"                string => "/data/rudder";
      "rudder_tools_files_android" slist => { "cpuid-android-V1.0.sh" };

    !windows.!android::
      "rudder_base"              string => "/opt/rudder";
      "rudder_tools_files_unix"   slist => { "send-clean.sh",
                                             "cpuid-linux-V1.0.sh",
                                             "vmware_info.sh",
                                             "check-rsyslog-version",
                                             "apache-vhost.tpl",
                                           };


    !windows.!android::
      "rudder_var"               string => "/var/rudder";

    !windows::
      "rudder_bin"               string => "${rudder_base}/bin";
      "rudder_sbin"              string => "${rudder_base}/sbin";
      "rudder_base_sbin"         string => "${rudder_base}/sbin"; #folder where tools are installed
      "rudder_tools"             string => "${rudder_var}/tools";
      "rudder_ncf"               string => "${rudder_var}/ncf";
      "uuid_file"                string => "${rudder_base}/etc/uuid.hive";
      "rudder_disable_server_file" string => "${rudder_base}/etc/disable-policy-distribution";

&if(NOVA)&
    windows::
      "rudder_base"              string => "${sys.winprogdir}\Rudder";
      "rudder_var"               string => "${sys.winprogdir}\Rudder\var";
      "rudder_bin"               string => "${rudder_base}\bin";
      "rudder_sbin"              string => "${rudder_base}\sbin";
      "rudder_tools"             string => "${rudder_sbin}";
      "rudder_ncf"               string => "${rudder_var}\ncf";
      "uuid_file"                string => "${rudder_base}\etc\uuid.hive";
      "rudder_disable_server_file" string => "${rudder_base}\etc\disable-policy-distribution";

      "rudder_tools_files_windows" slist => {
                                              # Rudder utilities
                                              "uuid.vbs",
                                              "userlist.bat",
                                              "getDate.bat",
                                              "registrydns.bat",
                                              "cpuid-windows-v1.0.vbs",

                                              # GNU iconv
                                              "iconv.exe",
                                              "iconv.dll",

                                              # cURL
                                              "curl/curl.exe",
                                              "curl/curl-ca-bundle.crt",

                                              # OpenSSL
                                              "openssl/openssl.exe",
                                              "openssl/libeay32.dll",
                                              "openssl/ssleay32.dll",

                                              # Fusion inventory
                                              "fusionagent.exe",

                                              # NXlog
                                              "nxlog-ce-2.8.1248.msi",
                                              "nxlog.conf"
                                            };
&endif&

    SuSE::
      "rudder_tools_files_suse"    slist => { "openvpn-2.2.1-1.x86_64.rpm",
                                              "openvpn-2.2.1-1.i686.rpm",
                                              "zypper-repo.tpl",
                                              "check-zypper-version",
                                              "checkzmd.pl",
                                            };
    any::
      "rudder_tools_files"         slist => { @{rudder_tools_files_windows},
                                              @{rudder_tools_files_android},
                                              @{rudder_tools_files_unix},
                                              @{rudder_tools_files_suse},
                                              "checkroute.pl",
                                            }, policy => "ifdefined";

      "uuid"                     string => readfile("${g.uuid_file}", 60);
      "excludedreps"             slist  => { "\.X11", ".*kde.*", "\.svn", "perl" };
      "ncf_local_excludedreps"    slist => { "50_techniques", @{g.excludedreps}};
      "rudder_tools_origin"      string => "/var/rudder/tools";
      "rudder_ncf_origin_common" string => "/usr/share/ncf/tree";
      "rudder_ncf_origin_local"  string => "&CONFIGURATION_REPOSITORY_FOLDER&/ncf";

      "rudder_ncf_hash_file"          string => "ncf_hash_file";
      "rudder_ncf_origin_common_hash" string => "${rudder_ncf_origin_common}/${rudder_ncf_hash_file}";
      "rudder_ncf_origin_local_hash"  string => "${rudder_ncf_origin_local}/${rudder_ncf_hash_file}";

      "rudder_tools_updated_origin" string => "${rudder_tools_origin}/rudder_tools_updated";
      "rudder_tools_updated"        string => "${rudder_tools}/rudder_tools_updated";


      "rudder_tools_updated_filename" string => "rudder_tools_updated";
      "rudder_tools_updated_origin" string => "${rudder_tools_origin}/${rudder_tools_updated_filename}";
      "rudder_tools_updated"        string => "${rudder_tools}/${rudder_tools_updated_filename}";

      # The time at which the execution started
&if(NOVA)&
    windows.!cygwin::
      "execRun"                  string => execresult("c:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe -Command \"'{0:yyyy-MM-dd HH:mm:sszzz}' -f (get-date)\"", "noshell");
&endif&

    android::
      "execRun"                  string => execresult("/system/xbin/date \"+%Y-%m-%d %T+02:00\"", "noshell");
    (!windows|cygwin).!android::
      # We would like to use date's "--rfc-3339=second" option here, but it is not available on older OSes (RHEL 3/4, AIX 5...)
      "execRun"                  string => execresult("/bin/date -u \"+%Y-%m-%d %T+00:00\"", "noshell");

# definition of the node roles
  &NODEROLE&
}

bundle common rudder_roles
{
  classes:
      # Policy Server is a machine which delivers promises
      "policy_server"       expression => strcmp("root","${g.uuid}");
      # Root Server is the top policy server machine
      "root_server"         expression => strcmp("root","${g.uuid}");

      # We are in the failsafe phase
      "failsafe" expression => "any";
}

############################################

#generate a key if not present
bundle agent init_files
{
  vars:
      "components"            slist  => { "cf-agent", "cf-serverd", "cf-execd", "cf-monitord", "cf-promises", "cf-runagent", "cf-key" };

    nova_edition::
      "cfengine_install_path" string => "/usr/local";
    community_edition::
      "cfengine_install_path" string => "${g.rudder_base}";

  classes:
      "missing_key" not => fileexists("${sys.workdir}/ppkeys/localhost.priv");

  files:
    community_edition.!windows::
      "${sys.workdir}/bin/${components}"
        perms     => u_p("755"),
        copy_from => cp("${cfengine_install_path}/bin/${components}","localhost"),
        action    => immediate;


  commands:
    cygwin.missing_key::
      "${sys.workdir}/bin/cf-key.exe";

&if(NOVA)&
    windows.missing_key.!cygwin::
      "\"${sys.workdir}\bin\cf-key\"";
&endif&

    !windows.!cygwin.missing_key::
      "${sys.workdir}/bin/cf-key";
}


body depth_search recurse(d)
{
        depth => "${d}";
}

#perms validation
body perms u_p(p)
{
        mode  => "${p}";
}

#server may be a list
body copy_from cp(from,server)
{
        servers     => { "${server}" };
        source      => "${from}";
        compare     => "digest";
    community_edition::
        portnumber => "&COMMUNITYPORT&";

}

body action immediate
{
        ifelapsed => "0";
}

body depth_search recurse_ignore(d,list)
{
        depth => "${d}";
        exclude_dirs => { @{list} };
}

body delete tidy
{
        dirlinks => "delete";
        rmdirs   => "true";
}

body file_select exclude(name)
{
        leaf_name  => { "${name}"};
        file_result => "!leaf_name";
}

body file_select by_fullname(names)
# @brief Select files that match `names`
# @param names A regular expression
{
      path_name  => { @(names)};
      file_result => "path_name";
}


# Define a class no matter what the outcome of the promise is
body classes always(x)
{
  promise_repaired => { "${x}" };
  promise_kept => { "${x}" };
  repair_failed => { "${x}" };
  repair_denied => { "${x}" };
  repair_timeout => { "${x}" };
}
