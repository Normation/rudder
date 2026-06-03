# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2021 Normation SAS

# Rsyslog Rudder configuration

# Disable Repeated message reduction or reports may be lost
$RepeatedMsgReduction off

# Provides TCP syslog reception
$ModLoad imtcp
$InputTCPServerRun &SYSLOGPORT&

# Provide udp for snare and compatibility with older syslog
$ModLoad imudp
$UDPServerRun &SYSLOGPORT&

# for Snare client
$EscapeControlCharactersOnReceive off

&if(STORE_ALL_CENTRALIZED_LOGS_IN_FILE)&
# Log everything
local6.* /var/log/rudder/reports/all.log
&endif&


# Direct to DB
$ActionQueueType Direct
$ActionQueueSaveOnShutdown on

# Filtering by content
# Process :

{{#classes.rudder_reporting_https}}
{{#classes.agent_capability_http_reporting}}
# If report protocol is HTTPS, we drop the local rsyslog message
if $fromhost-ip == "127.0.0.1" then {
    :programname, isequal, "cf-agent" ~
    :programname, isequal, "rudder" ~
}
{{/classes.agent_capability_http_reporting}}
{{/classes.rudder_reporting_https}}

# We first forward the data to the root server, then we drop it to prevent
# it from reaching local storage in .log files.
# The report format is @@Policy@@State@@RuleId@@DirectiveId@@VersionId@@Component@@Key@@ExecutionTimeStamp##NodeId@#HumanReadableMessage
#

# 1 - Send every matching report to the root server
:msg, ereregex, "(R: )?@@[ a-zA-Z0-9_\-]+?@@[a-zA-Z0-9_\-]{1,64}?@@[a-zA-Z0-9\-]+@@[a-zA-Z0-9\-]+?@@[0-9]+?@@.*?@@.*?@@[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}[+-][0-9]{1,2}:[0-9]{2}##[a-zA-Z0-9\-]+?@#.*" {{vars.rsyslog_system_settings.rsyslog_rule_prefix}}{{vars.server_info.policy_server}}:&SYSLOGPORT&

# 2 - Drop the remaining rudder logs to prevent local storage cluttering

## Syslog messages from "rudder"
:programname, contains, "rudder" ~
:programname, contains, "cf-agent" ~

## Syslog messages from "rudder" forwarded from AIX
:msg, ereregex, "from .*: rudder" ~
:msg, ereregex, "from .*: cf-agent" ~

