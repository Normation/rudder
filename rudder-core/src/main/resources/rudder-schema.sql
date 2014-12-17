/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

-- SQL schema for the reports data

-- set the report to warnings
SET client_min_messages='warning';


-- Enforce support for standart string (unescaped \)
ALTER database rudder SET standard_conforming_strings=true;


/*
 *************************************************************************************
 * The following tables are used as input from agent 
 * action. 
 * We store execution reports, historize them, and
 * also store each run in a dedicated tables. 
 * 
 * These tables are (should) be independant from
 * rudder-the-webapp and should be directly 
 * feeded by syslog. 
 *************************************************************************************
 */


-- create the table for the reports sent

CREATE SEQUENCE seq_reports_received_id START 101;

CREATE TABLE reports_received (
  id                 bigint PRIMARY KEY default nextval('seq_reports_received_id')
, node_id            text NOT NULL CHECK (node_id <> '')
, agent_run          timestamp with time zone NOT NULL
, rule_id            text NOT NULL CHECK (rule_id <> '')
, serial             integer NOT NULL
, directive_id       text NOT NULL CHECK (directive_id <> '')
, component_name     text NOT NULL CHECK (component_name <> '')
, component_value    text
, report_type        text
, message            text
-- I don't remember if it is the time when the log is sent by syslog
-- or when it is written by the agent. I thing it's the last one
, creation_datetime  timestamp with time zone NOT NULL
-- We also need to store the datetime on which we received the report
, received_datetime  timestamp with time zone NOT NULL
);


CREATE INDEX idx_reports_received_node_id           ON reports_received (node_id);
CREATE INDEX idx_reports_received_agent_run         ON reports_received (agent_run);
CREATE INDEX idx_reports_received_node_id_agent_run ON reports_received (node_id, agent_run);
CREATE INDEX idx_reports_received_component_name    ON reports_received (component_name);
CREATE INDEX idx_reports_received_component_value   ON reports_received (component_value);
CREATE INDEX idx_reports_received_rule_id           ON reports_received (rule_id);

CREATE INDEX changes_executionTimeStamp_idx ON RudderSysEvents (executionTimeStamp) WHERE eventType = 'result_repaired';


/*
 * The table used to store archived agent execution reports. 
 */
CREATE TABLE reports_received_archive (
  id                 bigint PRIMARY KEY
, node_id            text NOT NULL CHECK (node_id <> '')
, agent_run          timestamp with time zone NOT NULL
, rule_id            text NOT NULL CHECK (rule_id <> '')
, serial             integer NOT NULL
, directive_id       text NOT NULL CHECK (directive_id <> '')
, component_name     text NOT NULL CHECK (component_name <> '')
, component_value    text
, report_type        text
, message            text
, creation_datetime  timestamp with time zone NOT NULL
, received_datetime  timestamp with time zone NOT NULL
);

CREATE INDEX idx_reports_received_archive_agent_run ON reports_received_archive (agent_run);

/*
 * That table stores the agent run execution times for each nodes. 
 * We keep the starting time of the given run and the fact 
 * that the run completed (we got an "execution END" report) 
 * or not. 
 */
CREATE TABLE agent_runs (
  node_id            text NOT NULL
, datetime           timestamp with time zone NOT NULL
, complete           boolean NOT NULL
, nodeConfig_id      text
, insertion_id       bigint
, PRIMARY KEY(node_id, datetime)
);

CREATE INDEX idx_agent_runs_datetime ON agent_runs (datetime);
CREATE INDEX idx_agent_runs_insertionid ON ReportsExecution (insertion_id);


/*
 * Not sure about that one. I thing it's the table where we store
 * the last processed reports_received#id from witch we will
 * have to look to fill the agent_runs table. 
 */
CREATE TABLE reports_received_processed (
  key      text PRIMARY KEY
, last_id  bigint NOT NULL
, datetime timestamp with time zone NOT NULL
);

/* 
 *************************************************************************************
 * The following tables store what Rudder expects from agent. 
 * They are used to store rules versions and corresponding expected datas. 
 *************************************************************************************
 */

CREATE SEQUENCE seq_reports_expected_id            START 1;
CREATE SEQUENCE seq_reports_expected_node_join_key START 1;

CREATE TABLE reports_expected (
  id                   integer PRIMARY KEY DEFAULT nextval('seq_reports_expected_id')
, node_join_key        integer NOT NULL
, rule_id              text NOT NULL CHECK (rule_id <> '')
, serial               integer NOT NULL
, directive_id         text NOT NULL CHECK (directive_id <> '')
, component_name       text NOT NULL CHECK (component <> '')

-- cardinality is no more used, I propose to remove it 
, cardinality          integer NOT NULL

-- the type should be text[] (an array of text), but 
-- the migration would be not trivial. 
, component_values     text NOT NULL -- this is the serialisation of the expected values 
, component_values_raw text -- this is the serialisatin of the unexpanded expected values. It may be null for pre-2.6 entries
, begin_datetime       timestamp with time zone NOT NULL
, end_datetimee        timestamp with time zone
);

CREATE INDEX idx_reports_expected_node_join_key  ON reports_expected (node_join_key);
CREATE INDEX idx_reports_expected_rule_id_serial ON reports_expected (rule_id, serial);

CREATE TABLE reports_expected_by_node (
  node_join_key integer NOT NULL 
, node_id       text    NOT NULL CHECK (node_id <> '')
  /*
   * NodeConfigIds is an array of string  used for node 
   * config version id. It can be null or empty to accomodate 
   * pre-2.12 behaviour. 
   * 
   * The most recent version is put in first place of the
   * array, so that in
   * [v5, v4, v3, v2]
   * v5 is the newest. Space will be trim in the id. 
   * 
   */
, nodeConfigIds text[]
  /*
   * Denote that the expected report is actually not expected, 
   * because the directive is derived from an Unique Technique
   * and an other more priorised one was chosen. 
   */
, overriden text
, PRIMARY KEY (node_join_key, node_id)
);

CREATE INDEX idx_reports_expected_by_node_node_join_key ON reports_expected_by_node (node_join_key);


/*
 * We also have a table of the list of node with configId / generationDate
 * so what we can answer the question: what is the last config id for that node ?
 * The date helps now if we should have received report for that node. 
 */
CREATE TABLE nodes_info (
  node_id    text PRIMARY KEY CHECK (node_id <> '')
  -- configs ids are a dump of json: [{"configId":"xxxx", "dateTime": "iso-date-time"} ]
, config_ids text
);



/* 
 *************************************************************************************
 * The following tables stores "event logs", i.e logs action about user and 
 * system event that can leads to configuration changes and are needed to allows
 * audit track logs. 
 *************************************************************************************
 */

CREATE SEQUENCE seq_rudder_event_logs_id START 1;

-- event_log is not the best for this one
CREATE TABLE rudder_event_logs (
  id                 integer PRIMARY KEY  DEFAULT nextval('seq_rudder_event_logs_id')
, creation_datetime  timestamp with time zone NOT NULL DEFAULT 'now'
, severity           integer
, cause_id           integer
, modification_id    text
, principal          text
, reason             text
, type               text
, data               xml
); 

CREATE INDEX idx_rudder_event_log_type ON rudder_event_log (type);
CREATE INDEX idx_rudder_event_log_type ON rudder_event_log (creation_datetime);

/*
 * That table is used when a migration between 
 * event log format is needed. 
 */
CREATE SEQUENCE seq_rudder_event_log_migration_id start 1;

CREATE TABLE rudder_event_logs_migration (
  id                    integer PRIMARY KEY default(nextval('seq_rudder_event_log_migration_id'))
, detection_datetime    timestamp with time zone NOT NULL
, detected_file_format  integer
, migration_start_time  timestamp with time zone
, migration_end_time    timestamp with time zone 
, migration_file_format integer
, description           text
);


/*
 *************************************************************************************
 * A table used to store generic properties related to the database and that could not
 * go the the LDAP backend. Typically, that's property that must be updated during a 
 * transaction or are really frequently written. 
 *************************************************************************************
 */


CREATE TABLE rudder_properties (
  name  text PRIMARY KEY
, value text
);


/*
 *************************************************************************************
 * The following tables are used to manage
 * validation workflows and change requests 
 *************************************************************************************
 */

CREATE SEQUENCE seq_change_request_id start 1;

CREATE TABLE change_requests (
  id                 integer PRIMARY KEY default(nextval('seq_change_request_id'))
, name               text CHECK (name <> '')
, description        text
, creation_datetime  timestamp with time zone
, content            xml
, modification_id    text
);

CREATE TABLE change_requests_workflow(
  id    integer references change_requests(id)
, state text
);

CREATE TABLE change_request_commits(
  id              text PRIMARY KEY
, modification_id text
);

/* 
 *************************************************************************************
 * The following tables stores names about object to be able to historize them
 * and present them back to the user in a meaningful way. 
 *************************************************************************************
 */


CREATE SEQUENCE seq_name_historization_nodes_id START 101;

CREATE TABLE name_historization_nodes (
  id               integer PRIMARY KEY default nextval('seq_name_historization_nodes_id')
, node_id          text NOT NULL CHECK (node_id <> '')
, node_name        text
, node_description text
, start_datetime   timestamp with time zone default now()
, end_datetime     timestamp with time zone
);

CREATE INDEX idx_name_historization_nodes_node_id_start_datetime ON name_historization_nodes (node_id, start_datetime);
CREATE INDEX idx_name_historization_nodes_end_datetime           ON name_historization_nodes (end_datetime);

CREATE SEQUENCE seq_name_historization_groups_id START 101;

CREATE TABLE name_historization_groups (
  id                integer PRIMARY KEY default nextval('seq_name_historization_groups_id')
, group_id          text NOT NULL CHECK (group_id <> '')
, group_name        text
, group_description text
, group_status      integer default 2
, node_count        integer
, start_datetime    timestamp with time zone default now()
, end_datetime      timestamp with time zone
);

CREATE INDEX idx_name_historization_groups_group_id_start_datetime ON name_historization_groups (group_id, start_datetime);
CREATE INDEX idx_name_historization_groups_end_datetime            ON name_historization_groups (end_datetime);

CREATE TABLE name_historization_join_groups_nodes (
  name_historization_groups_id integer
, node_id                      text NOT NULL CHECK (nodeid <> '')
, PRIMARY KEY(name_historization_groups_id, node_id)
);

CREATE SEQUENCE seq_name_historization_directives START 101;

CREATE TABLE name_historization_directives (
  id                    integer PRIMARY KEY default nextval('seq_name_historization_directives')
, directive_id          text NOT NULL CHECK (directive_id <> '')
, directive_name        text
, directive_description text
, priority              integer NOT NULL
, technique_name        text
, technique_version     text
, technique_description text
, technique_human_name  text
, start_datetime        timestamp with time zone default now()
, end_datetime          timestamp with time zone
);

CREATE INDEX idx_name_historization_directives_directive_id_start_datetime ON name_historization_directives (group_id, start_datetime);
CREATE INDEX idx_name_historization_directives_end_datetime                ON name_historization_directives (end_datetime);

CREATE SEQUENCE seq_name_historization_rules START 101;

CREATE TABLE name_historization_rules (
  id                integer PRIMARY KEY default nextval('seq_name_historization_rules')
, rule_id           text NOT NULL CHECK (rule_id <> '')
, serial            integer NOT NULL
, name              text
, short_description text
, long_description  text
, is_enabled        boolean
, start_datetime   timestamp with time zone default now()
, end_datetime     timestamp with time zone
);

CREATE INDEX idx_name_historization_rules_rule_id_start_datetime ON Rules (rule_id, start_datetime);
CREATE INDEX idx_name_historization_rules_end_datetime           ON Rules (end_datetime);

CREATE TABLE name_historization_join_rules_groups (
  name_historization_rules_id integer 
, group_id                    text NOT NULL CHECK (group_id <> '')
, PRIMARY KEY(name_historization_rules_id, group_id)
);

CREATE TABLE name_historization_join_rules_directives (
  name_historization_rules_id integer 
, directive_id                text NOT NULL CHECK (directive_id <> '')
, PRIMARY KEY(name_historization_rules_id, directive_id)
);




/*
 *************************************************************************************
 * end
 *************************************************************************************
 */
