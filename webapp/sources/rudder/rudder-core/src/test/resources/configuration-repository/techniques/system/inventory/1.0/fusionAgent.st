# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2021 Normation SAS

#
# This file launch a fusion-inventory agent
# inventory in local.
# If fusion agent is not installed,
# the installation is done
#
# Then the inventory file is improved with external information (UUID, cf-key, VMs)

# Compute the inventory time
bundle agent computeInventoryTime
{
  vars:
      "run_interval"             int   => "&AGENT_RUN_INTERVAL&";

  classes:
       "inventory_time" expression => "schedule_simple_rudder_run_inventory_repaired",
                             scope => "namespace";

  methods:
      # Inventory will be during the 6 hours after midnight, every day
      "splay_inventory" usebundle => _method_reporting_context("Compute inventory splay", "None");
      "splay_inventory" usebundle => schedule_simple("rudder_run_inventory", "${run_interval}", "0", "6", "0", "0", "0", "0", "0", "1", "catchup");
}

bundle agent doInventory
{
  vars:
    resend_inventory::
      "cant_send_inventory" string => "undefine",
                            classes => cancel_all_classes("cant_send_inventory");

  classes:
      "disable_inventory" expression => fileexists("${g.rudder_disable_inventory_file}");

    cant_send_inventory::
      "resend_inventory" expression => "any",
                         scope => "namespace";

  methods:
    ignore_disable_inventory|!disable_inventory::
      "any" usebundle => doInventory_always;
    !(ignore_disable_inventory|!disable_inventory)::
      "any" usebundle => rudder_common_report("Inventory", "log_warn", "&TRACKINGKEY&", "Inventory", "None", "The file ${g.rudder_disable_inventory_file} is present, so Rudder will not send an inventory. Use 'rudder agent inventory -f' to override this temporarily");
}
bundle agent doInventory_always
{

  vars:

    uuid_successfully_downloaded::
      "policy_server_uuid" string => readfile("${sys.workdir}/rudder-server-uuid.txt", 50);

  classes:
    # The force_inventory class may be specified by the user at runtime, or defined here
      "force_inventory" expression => fileexists("${g.rudder_base}/etc/force_inventory"),
                             scope => "namespace";

    uuid_successfully_downloaded::
      "uuid_valid" expression => regcmp("[a-z0-9-]+","${policy_server_uuid}");
      "uuid_validity_checked"
        expression => "any",
        comment    => "This dummy class is just used for ordering the report using !uuid_valid below";

    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  files:
    # Clean policy server uuid file defined in 2.11 or earlier version, as location in 3.0+ is ${sys.workdir}/rudder-server-uuid.txt
    "${g.rudder_var_tmp}/uuid.txt"
      delete => tidy;

  methods:
      # Compute the inventory time
      "any" usebundle => computeInventoryTime;

    force_inventory|((inventory_time|initial_promises).!inventory_sent)::
      "any" usebundle => download_server_uuid;

    uuid_valid.(force_inventory|((inventory_time|initial_promises).!inventory_sent))::
      "any" usebundle => fusionAgent;
      "any" usebundle => checkInventoryFile;

    uuid_valid.(force_inventory|((inventory_time|initial_promises).!inventory_sent)).inventory_valid::
      "any" usebundle => moveInventoryToFinalDestination;
      "any" usebundle => signInventories;
      "any" usebundle => compressInventories;

    uuid_valid.(force_inventory|resend_inventory|((inventory_time|initial_promises).!inventory_sent)).inventory_valid::
      "any" usebundle => sendInventory;
      "any" usebundle => cleanForceInventoryFlagFile;

    pass3.uuid_successfully_downloaded.uuid_validity_checked.!uuid_valid::
      "any" usebundle => rudder_common_report("Inventory", "result_error", "&TRACKINGKEY&", "Inventory", "None", "Could not retrieve a valid UUID from the policy server");

    # Send reports to confirm this technique success even if we don't need to send an inventory
    pass3.inventory_sent::
      "any" usebundle => rudder_common_report("Inventory", "log_info", "&TRACKINGKEY&", "Inventory", "None", "An inventory was already sent less than 8 hours ago");

    pass3.!inventory_time.!force_inventory::
      "any" usebundle => rudder_common_report("Inventory", "result_success", "&TRACKINGKEY&", "Inventory", "None", "Next inventory scheduled between 00:00 and 06:00");

}

bundle agent download_server_uuid
{
  classes:
      "have_client" expression => fileexists("/opt/rudder/bin/rudder-client");

  methods:
    could_not_download_uuid::
      "any" usebundle => rudder_common_report("Inventory", "result_error", "&TRACKINGKEY&", "Inventory", "None", "Could not retrieve the UUID of the policy server. Please check that the defined Policy Server exists, and that this Node IP address is in the Allowed Networks of its policy server.");

  commands:
    !have_client::
      # remove this when 6.2 agent are not supported anymore
      "${g.rudder_curl} --tlsv1.2 --location ${g.rudder_verify_certs_option} --silent --fail --proxy '' --output \"${sys.workdir}/rudder-server-uuid.txt\" https://${server_info.policy_server}/uuid"
        comment => "Getting the uuid from the server",
        classes => if_else("uuid_successfully_downloaded","could_not_download_uuid");

    have_client::
      "/opt/rudder/bin/rudder-client -e /uuid -- --output \"${sys.workdir}/rudder-server-uuid.txt\""
        comment => "Getting the uuid from the server",
        classes => if_else("uuid_successfully_downloaded","could_not_download_uuid");

}

bundle agent fusionAgent
{
  vars:
    any::
      "inventory_name"           string => "${sys.uqhost}-${g.uuid}.ocs";
      "inventory_path"           string => "${g.rudder_var_tmp}/inventory/${inventory_name}";

  classes:
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  files:
      "${g.rudder_var_tmp}/inventory/."
        create => "true",
        comment => "Creating inventory directory",
        classes => if_ok("inventoryfoldercreated");

      "${g.rudder_var_reports}/."
        create => "true";

  methods:
    pass3.run_inventory::
      "any" usebundle => rudder_common_report("Inventory", "log_debug", "&TRACKINGKEY&", "Inventory", "None", "Running inventory");

    pass3.inventory_failed::
      "any" usebundle => rudder_common_report("Inventory", "result_error", "&TRACKINGKEY&", "Inventory", "None", "Could not execute the inventory");

    pass3.!rudder_inventory_not_running::
      "any" usebundle => rudder_common_report("Inventory", "result_na", "&TRACKINGKEY&", "Inventory", "None", "Skipped the inventory as another is running");

  processes:
      "${g.rudder_base}/bin/run-inventory .*"
        restart_class => "rudder_inventory_not_running";

  commands:
    inventoryfoldercreated.rudder_inventory_not_running::
      "${g.rudder_base}/bin/run-inventory --local=${inventory_path}"
        classes => cf2_if_else("run_inventory", "inventory_failed"),
        comment => "Generating inventory, in the temporary folder";
}

# Check that the inventory file is valid
bundle agent checkInventoryFile
{
  vars:
    has_rudder_perl::
      "perl_command" string => "/opt/rudder/bin/perl -I /opt/rudder/lib/perl5";
    !has_rudder_perl::
      "perl_command" string => "/usr/bin/perl -I /opt/rudder/lib/perl5";

  classes:
    rudder_inventory_not_running::
      "inventory_file_exist" expression => fileexists("${fusionAgent.inventory_path}");
      "has_rudder_perl" expression => fileexists("/opt/rudder/bin/perl");
    pass1.inventory_file_exist::
      "inventory_valid" expression => returnszero("${perl_command} ${this.promise_dirname}/test-inventory.pl ${fusionAgent.inventory_path}", "noshell"),
                        scope => "namespace";

    any::
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  methods:
    pass2.inventory_file_exist.!inventory_valid::
      "any" usebundle => rudder_common_report("Inventory", "result_error", "&TRACKINGKEY&", "Inventory", "None", "Generated inventory has been detected as invalid");

  reports:
    pass2.inventory_file_exist.!inventory_valid::
      # Print information to the user in addition to the reporting
      "********************************************************************************
* WARNING Generated inventory is not valid, as it is missing mandatory fields. *
* Not sending it to the Rudder Server                                          *
* You can get a full error message by running: ${perl_command} ${this.promise_dirname}/test-inventory.pl ${fusionAgent.inventory_path} 
********************************************************************************";
}

# Move the inventory file in the shared directory
bundle agent moveInventoryToFinalDestination
{
  files:
      "${g.rudder_inventories}/${fusionAgent.inventory_name}"
        copy_from    => copy("${fusionAgent.inventory_path}"),
        comment      => "Moving inventory files to the final location";
}

# sign inventories
bundle agent signInventories
{
  vars:
      # Keep "/opt/rudder/bin/signature.sh" as a fallback until we don't support agents < 4.0 anymore
      "sign_script" string => ifelse(fileexists("${g.rudder_base}/bin/rudder-sign"), "${g.rudder_base}/bin/rudder-sign", "${g.rudder_base}/bin/signature.sh");

  classes:
      "sign_script_exists" expression => fileexists("${sign_script}");

  files:
    sign_script_exists::
      "${g.rudder_inventories}/${fusionAgent.inventory_name}"
        transformer  => "${sign_script} \"${this.promiser}\"",
        comment      => "Signing inventory files";
}

# compress inventories if possible
bundle agent compressInventories
{
  files:
    gzip_installed::
      "${g.rudder_inventories}/${fusionAgent.inventory_name}"
        transformer  => "${g.gzip} -fq ${this.promiser}",
        comment      => "gzip inventory files";
}

# Send the file to the promises server
bundle agent sendInventory
{

  vars:
    initial_promises::
      "download_endpoint"         string => "https://${server_info.policy_server}/inventories/";
      "endpoint"                  string => "/inventories/";
      "min_resend_delay"          string => "15";
    !initial_promises::
      "download_endpoint"         string => "https://${server_info.policy_server}/inventory-updates/";
      "endpoint"                  string => "/inventory-updates/";
      "min_resend_delay"          string => "480";

  classes:
    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";
      "have_client" expression => fileexists("/opt/rudder/bin/rudder-client");

  files:
    !root_server.!have_client::
      # remove this when 6.2 agent are not supported anymore
      "${g.rudder_inventories}/${fusionAgent.inventory_name}.*"
        transformer => "${g.rudder_curl} --tlsv1.2 --location ${g.rudder_verify_certs_option} --fail --silent --proxy '' --user ${g.davuser}:${g.davpw} --upload-file ${this.promiser} ${download_endpoint}",
        classes      => persistent_class("inventory_sent", "cant_send_inventory", "${min_resend_delay}"),
        comment      => "Sending the inventory to the server";

    !root_server.have_client::
      "${g.rudder_inventories}/${fusionAgent.inventory_name}.*"
        transformer => "/opt/rudder/bin/rudder-client -e ${endpoint} -- --upload-file ${this.promiser}",
        classes      => persistent_class("inventory_sent", "cant_send_inventory", "${min_resend_delay}"),
        comment      => "Sending the inventory to the server";

    root_server::
      # minimal change to copy inventories to accepted node updates on root server
      "${g.rudder_inventories}/${fusionAgent.inventory_name}\..*"
        transformer => "${g.rudder_cp} ${this.promiser} ${g.rudder_inventories}/accepted-nodes-updates",
        classes      => persistent_class("inventory_sent", "cant_send_inventory", "${min_resend_delay}"),
        comment      => "Copying the inventory on root server";


  # Once we've successfully sent all inventories, remove them
    inventory_sent.!cant_send_inventory::
      "${g.rudder_inventories}/${fusionAgent.inventory_name}.*"
        transformer  => "${g.rudder_rm} -f ${this.promiser}",
        classes      => if_else("inventory_file_deleted", "cant_delete_inventory_file"),
        comment      => "Cleaning up inventory files already sent to the server";

      "${g.rudder_var_tmp}/inventory_sent"
        create       => "true",
        touch        => "true",
        comment      => "Create local info about successful upload";

      "${g.rudder_var_tmp}/inventory"
        transformer  => "${g.rudder_rm} -f ${this.promiser}",
        depth_search => recurse_visible(1),
        file_select  => fusion_by_name_old("@{g.signed_inventory_file_types}", 2),
        classes      => if_else("inventory_file_deleted", "cant_delete_inventory_file"),
        comment      => "Cleaning up inventory files already sent to the server";

  methods:
    pass3.inventory_sent::
      "any" usebundle => rudder_common_report("Inventory", "result_success", "&TRACKINGKEY&", "Inventory", "None", "The inventory has been successfully sent");

    pass3.cant_send_inventory::
      "any" usebundle => rudder_common_report("Inventory", "result_error", "&TRACKINGKEY&", "Inventory", "None", "Could not send the inventory");

    pass3.cant_delete_inventory_file::
      "any" usebundle => rudder_common_report("Inventory", "log_warn", "&TRACKINGKEY&", "Inventory", "None", "Could not delete inventory file after sending to server");

}
body file_select fusion_by_name_old(names,days)
{
      leaf_name   => { @(names) };
      mtime       => irange(0,ago(0,0,"${days}",0,0,0));
      file_result => "mtime.leaf_name";
}

bundle agent cleanForceInventoryFlagFile
{

  files:
      "${g.rudder_base}/etc/force_inventory"
        delete     => tidy,
        ifvarclass => "inventory_sent"; # if the force inventory file was present, and we successfully sent an inventory, clean up the flag file

}

