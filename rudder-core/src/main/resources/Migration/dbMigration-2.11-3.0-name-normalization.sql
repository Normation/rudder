/*
*************************************************************************************
* Copyright 2014 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/



ALTER SEQUENCE serial RENAME TO seq_reports_received_id;

ALTER TABLE RudderSysEvents 
  RENAME TO reports_received
, RENAME creation_datetime TO creation_datetime
, RENAME nodeid TO node_id
, RENAME directiveid TO directive_id
, RENAME ruleid TO rule_id
, RENAME component TO component_name
, RENAME keyvalue TO component_value
, RENAME executiontimestamp TO agent_run
, RENAME eventtype TO report_type
, RENAME msg TO message
, ADD received_datetime  timestamp with time zone NOT NULL
;

ALTER INDEX nodeid_idx                   RENAME TO idx_reports_received_node_id; 
ALTER INDEX executionTimeStamp_idx       RENAME TO idx_reports_received_agent_run; 
ALTER INDEX composite_node_execution_idx RENAME TO idx_reports_received_node_id_agent_run; 
ALTER INDEX component_idx                RENAME TO idx_reports_received_component_name; 
ALTER INDEX keyValue_idx                 RENAME TO idx_reports_received_component_value;
ALTER INDEX ruleId_idx                   RENAME TO idx_reports_received_rule_id;

ALTER TABLE ArchivedRudderSysEvents 
  RENAME TO reports_received_archive
, RENAME creation_datetime TO creation_datetime
, RENAME nodeid TO node_id
, RENAME directiveid TO directive_id
, RENAME ruleid TO rule_id
, RENAME component TO component_name
, RENAME keyvalue TO component_value
, RENAME executiontimestamp TO agent_run
, RENAME eventtype TO report_type
, RENAME msg TO message
, ADD received_datetime  timestamp with time zone NOT NULL
;

ALTER INDEX executionTimeStamp_archived_idx RENAME TO idx_reports_received_archive_agent_run;

ALTER TABLE ReportsExecution 
  RENAME TO agent_runs
, RENAME nodeId TO node_id
, RENAME date TO datetime 
;

ALTER INDEX reportsexecution_date_idx  RENAME TO idx_agent_runs_datetime;

ALTER TABLE StatusUpdate
  RENAME TO reports_received_processed
, RENAME lastid TO last_id
, RENAME "date" TO datetime
;

ALTER SEQUENCE ruleSerialId  RENAME TO seq_reports_expected_id;
ALTER SEQUENCE ruleVersionId RENAME TO seq_reports_expected_node_join_key; 

ALTER TABLE expectedReports
  RENAME TO reports_expected
, RENAME pkid TO id
, RENAME nodeJoinKey TO node_join_key
, RENAME ruleid TO rule_id
, RENAME directiveid TO directive_id
, RENAME component TO component_name
, RENAME componentsvalues TO component_values
, RENAME unexpandedcomponentsvalues TO component_values_raw
, RENAME begindate TO begin_datetime
, RENAME enddate TO end_datetime
;

ALTER INDEX expectedReports_versionId RENAME TO idx_reports_expected_node_join_key;
ALTER INDEX expectedReports_serialId RENAME TO idx_reports_expected_rule_id_serial;

ALTER TABLE expectedReportsNodes
  RENAME TO reports_expected_by_node
, RENAME nodejoinkey TO node_join_key
, RENAME nodeid TO node_id
;

ALTER INDEX expectedReportsNodes_versionId RENAME TO idx_reports_expected_by_node_node_join_key;

ALTER SEQUENCE eventLogIdSeq RENAME TO seq_rudder_event_logs_id;

ALTER TABLE EventLog
  RENAME TO rudder_event_logs
, RENAME creationdate TO creation_datetime
, RENAME causeid TO cause_id
, RENAME modificationid TO modification_id
, RENAME eventtype TO "type"
;

ALTER INDEX eventType_idx RENAME TO idx_rudder_event_log_type;
ALTER INDEX creationDate_idx RENAME TO idx_rudder_event_log_type;

ALTER SEQUENCE MigrationEventLogId RENAME TO seq_rudder_event_log_migration_id;

ALTER TABLE MigrationEventLog
  RENAME TO rudder_event_logs_migration
, RENAME detectiontime TO detection_datetime
, RENAME detectedfileformat TO detected_file_format
, RENAME migrationstarttime TO migration_start_datetime
, RENAME migrationendtime TO migration_end_datetime
, RENAME migrationfileformat TO migration_file_format
;

ALTER TABLE RudderProperties RENAME TO rudder_properties;


ALTER SEQUENCE ChangeRequestId RENAME TO seq_change_request_id;

ALTER TABLE ChangeRequest
  RENAME TO change_requests
, RENAME creationtime TO creation_datetime
, RENAME modificationid TO modification_id
;

ALTER TABLE Workflow
  RENAME TO change_requests_workflow
;

ALTER TABLE gitCommit
  RENAME TO change_request_commits
, RENAME gitcommit TO id
, RENAME modificationid TO modification_id
;

ALTER SEQUENCE NodesId RENAME TO seq_name_historization_nodes; 

ALTER TABLE Nodes
  RENAME TO name_historization_nodes
, RENAME nodeid TO node_id
, RENAME nodename TO node_name
, RENAME nodedescription TO node_description
, RENAME starttime TO start_datetime
, RENAME endtime TO end_datetime
;

ALTER INDEX nodes_id_start RENAME TO idx_name_historization_nodes_node_id_start_datetime;
ALTER INDEX nodes_end      RENAME TO idx_name_historization_nodes_end_datetime;

ALTER SEQUENCE GroupsId RENAME TO seq_name_historization_groups;

ALTER TABLE Groups
, RENAME TO name_historization_groups
, RENAME groupid TO group_id
, RENAME groupname TO group_name
, RENAME groupdescription TO group_description
, RENAME groupstatus TO group_status
, RENAME nodecount TO node_count
, RENAME starttime TO start_datetime
, RENAME endtime TO end_datetime
;        

ALTER INDEX groups_id_start RENAME TO idx_name_historization_groups_group_id_start_datetime;
ALTER INDEX groups_end RENAME TO idx_name_historization_groups_end_datetime;
        
ALTER TABLE GroupsNodesJoin
, RENAME TO name_historization_join_groups_nodes
, RENAME grouppkeyid TO name_historization_groups_id
, RENAME nodeid TO node_id
;

ALTER SEQUENCE DirectivesId RENAME TO seq_name_historization_directives;

ALTER TABLE Directives
, RENAME TO name_historization_directives
, RENAME directiveid TO directive_id
, RENAME directivename TO directive_name
, RENAME directivedescription TO directive_description
, RENAME techniquename TO technique_name
, RENAME techniqueversion TO technique_version
, RENAME techniquedescription TO technique_description
, RENAME techniquehumanname TO technique_human_name
, RENAME starttime TO start_datetime
, RENAME endtime TO end_datetime
;

ALTER INDEX directives_id_start RENAME TO idx_name_historization_directives_directive_id_start_datetime;
ALTER INDEX directives_end RENAME TO idx_name_historization_directives_end_datetime;

ALTER SEQUENCE RulesId RENAME TO  seq_name_historization_rules;

ALTER TABLE Rules
, RENAME TO name_historization_rules
, RENAME rulepkeyid TO id
, RENAME ruleid TO rule_id
, RENAME shortdescription TO short_description
, RENAME longdescription TO long_description
, RENAME isenabled TO is_enabled
, RENAME starttime TO start_datetime
, RENAME endtime TO end_datetime
;

ALTER INDEX rules_id_start RENAME TO idx_name_historization_rules_rule_id_start_datetime;
ALTER INDEX rules_end RENAME TO idx_name_historization_rules_end_datetime;


ALTER TABLE RulesGroupsJoin
, RENAME  TO name_historization_join_rules_groups
, RENAME rulepkeyid TO name_historization_rules_id
, RENAME groupid TO group_id
;

ALTER TABLE RulesDirectivesJoin
, RENAME TO name_historization_join_rules_directives
, RENAME rulepkeyid TO name_historization_rules_id
, RENAME directiveid TO directive_id
;


