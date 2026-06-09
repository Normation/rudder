# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2021 Normation SAS

# This file launch a fusion-inventory agent inventory
# Then the inventory file is signed, then sent

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
      "splay_inventory" usebundle => rudder_reporting_context_id("inventory-all@@inventory-all@@00", "Inventory");
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

    # Clean all remaining inventories files
    any::
      "any" usebundle => cleanInventoryFiles;
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

    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  methods:
      # Compute the inventory time
      "any" usebundle => computeInventoryTime;

    force_inventory|((inventory_time|initial_promises).!inventory_sent)::
      "any" usebundle => download_server_uuid;

    uuid_valid.(force_inventory|((inventory_time|initial_promises).!inventory_sent))::
      "any" usebundle => fusionAgent;
      "any" usebundle => checkInventoryFile;

    uuid_valid.(force_inventory|((inventory_time|initial_promises).!inventory_sent)).inventory_valid::
      "any" usebundle => signInventories;
      "any" usebundle => compressInventories;

    uuid_valid.(force_inventory|resend_inventory|((inventory_time|initial_promises).!inventory_sent)).inventory_valid::
      "any" usebundle => sendInventory;
      "any" usebundle => cleanForceInventoryFlagFile;

    pass3.uuid_successfully_downloaded.!uuid_valid::
      "any" usebundle => rudder_common_report("Inventory", "result_error", "&TRACKINGKEY&", "Inventory", "None", "Could not retrieve a valid UUID from the policy server");

    # Send reports to confirm this technique success even if we don't need to send an inventory
    pass3.inventory_sent::
      "any" usebundle => rudder_common_report("Inventory", "log_info", "&TRACKINGKEY&", "Inventory", "None", "An inventory was already sent less than 8 hours ago");

    pass3.!inventory_time.!force_inventory::
      "any" usebundle => rudder_common_report("Inventory", "result_success", "&TRACKINGKEY&", "Inventory", "None", "Next inventory scheduled between 00:00 and 06:00");

}

body contain download_uuid_timeout
{
  exec_timeout => "10";
}

bundle agent download_server_uuid
{
  methods:
    could_not_download_uuid::
      "any" usebundle => rudder_common_report("Inventory", "result_error", "&TRACKINGKEY&", "Inventory", "None", "Could not retrieve the UUID of the policy server. Please check that the defined Policy Server exists, and that this Node IP address is in the Allowed Networks of its policy server.");

  commands:
      "/opt/rudder/bin/rudder-client -e /uuid -- --output \"${sys.workdir}/rudder-server-uuid.txt\""
        comment => "Getting the uuid from the server",
        contain => download_uuid_timeout,
        classes => if_else("uuid_successfully_downloaded","could_not_download_uuid");

}

bundle agent fusionAgent
{
  vars:
    !pass1::
      # Add a unique value to allow matching inventory and signature files
      "date"                     string => execresult("LANG=C /bin/date --utc --iso-8601=seconds", "useshell");
      "inventory_name"           string => "${g.uuid}_${date}.ocs";
      # Required when used in a regex
      "inventory_name_esc"       string => escape("${inventory_name}");
    verbose_inventory|verbose_mode|inform_mode::
      "inventory_opts"           string => "--debug --debug ";
    !(verbose_inventory|verbose_mode|inform_mode)::
      "inventory_opts"           string => "";

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
      "${g.rudder_base}/bin/run-inventory --local=${g.rudder_inventories}/${fusionAgent.inventory_name} ${inventory_opts}"
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
      "inventory_file_exist" expression => fileexists("${g.rudder_inventories}/${fusionAgent.inventory_name}");
      "has_rudder_perl" expression => fileexists("/opt/rudder/bin/perl");
    pass1.inventory_file_exist::
      "inventory_valid" expression => returnszero("${perl_command} ${this.promise_dirname}/test-inventory.pl ${g.rudder_inventories}/${fusionAgent.inventory_name}", "noshell"),
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
* You can get a full error message by running: ${perl_command} ${this.promise_dirname}/test-inventory.pl ${g.rudder_inventories}/${fusionAgent.inventory_name}
********************************************************************************";
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

  files:
    !root_server::
      "${g.rudder_inventories}/${fusionAgent.inventory_name_esc}.*"
        transformer => "/opt/rudder/bin/rudder-client -e ${endpoint} -- --upload-file ${this.promiser}",
        classes      => persistent_class("inventory_sent", "cant_send_inventory", "${min_resend_delay}"),
        comment      => "Sending the inventory to the server";

    root_server::
      # minimal change to copy inventories to accepted node updates on root server
      "${g.rudder_inventories}/${fusionAgent.inventory_name_esc}\..*"
        transformer => "${g.rudder_cp} ${this.promiser} ${g.rudder_inventories}/accepted-nodes-updates",
        classes      => persistent_class("inventory_sent", "cant_send_inventory", "${min_resend_delay}"),
        comment      => "Copying the inventory on root server";

    inventory_sent.!cant_send_inventory::
      "${g.rudder_var_tmp}/inventory_sent"
        create       => "true",
        touch        => "true",
        comment      => "Create local info about successful upload";

  methods:
    pass3.inventory_sent::
      "any" usebundle => rudder_common_report("Inventory", "result_success", "&TRACKINGKEY&", "Inventory", "None", "The inventory has been successfully sent");

    pass3.cant_send_inventory::
      "any" usebundle => rudder_common_report("Inventory", "result_error", "&TRACKINGKEY&", "Inventory", "None", "Could not send the inventory");
}

bundle agent cleanInventoryFiles
{
  files:
      "${g.rudder_inventories}"
        transformer  => "${g.rudder_rm} -f ${this.promiser}",
        depth_search => recurse_visible(1),
        file_select  => name_age("@{g.signed_inventory_file_types}", 0),
        classes      => if_else("inventory_file_deleted", "cant_delete_inventory_file"),
        comment      => "Cleaning up inventory files already sent to the server";

  methods:
    cant_delete_inventory_file::
      "any" usebundle => rudder_common_report("Inventory", "log_warn", "&TRACKINGKEY&", "Inventory", "None", "Could not delete inventory file after sending to server");
}

bundle agent cleanForceInventoryFlagFile
{

  files:
      "${g.rudder_base}/etc/force_inventory"
        delete     => tidy,
        ifvarclass => "inventory_sent"; # if the force inventory file was present, and we successfully sent an inventory, clean up the flag file

}

