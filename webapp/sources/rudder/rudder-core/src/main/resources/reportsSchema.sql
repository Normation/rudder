/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

-- SQL schema for the reports data

-- set the report to warnings
SET client_min_messages='warning';


-- Enforce support for standard string (unescaped \)
alter database rudder set standard_conforming_strings=true;


/*
 *************************************************************************************
 * The following tables are used as input from agent
 * action.
 * We store execution reports, historize them, and
 * also store each run in a dedicated tables.
 *
 * These tables are (should) be independent from
 * rudder-the-webapp and should be directly
 * fed by syslog.
 *************************************************************************************
 */


-- create the table for the reports sent

create sequence serial start 101;

create TABLE Users (
  id             text PRIMARY KEY NOT NULL CHECK (id <> '')
, creationDate   timestamp with time zone NOT NULL
, status         text NOT NULL
, managedBy      text NOT NULL CHECK (managedBy <> '')
, name           text
, email          text
, lastLogin      timestamp with time zone
, statusHistory  jsonb
, otherInfo      jsonb -- general additional user info
);

create TABLE UserSessions (
  userId       text NOT NULL CHECK (userId <> '')
, sessionId    text NOT NULL CHECK (sessionId <> '')
, creationDate timestamp with time zone NOT NULL
, authMethod   text
, permissions  text[]
, authz        text[]
, tenants      text
, endDate      timestamp with time zone
, endCause     text
, PRIMARY KEY(userId, sessionId)
);


create TABLE RudderSysEvents (
  id                 bigint PRIMARY KEY default nextval('serial')
, executionDate      timestamp with time zone NOT NULL
, nodeId             text NOT NULL CHECK (nodeId <> '')
, directiveId        text NOT NULL CHECK (directiveId <> '')
, ruleId             text NOT NULL CHECK (ruleId <> '')
, reportId           text NOT NULL CHECK (reportId <> '')
, component          text NOT NULL CHECK (component <> '')
, keyValue           text
, executionTimeStamp timestamp with time zone NOT NULL
, eventType          text
, policy             text
, msg                text
);


create index executionTimeStamp_idx       on RudderSysEvents (executionTimeStamp);
create index composite_node_execution_idx on RudderSysEvents (nodeId, executionTimeStamp);
create index ruleId_idx                   on RudderSysEvents (ruleId);

create index endRun_control_idx on RudderSysEvents (id) WHERE eventType = 'control' and component = 'end';
create index changes_executionTimeStamp_idx on RudderSysEvents (executionTimeStamp) WHERE eventType = 'result_repaired';

/*
 * That table store the agent execution times for each nodes.
 * We keep the starting time of the given run and the fact
 * that the run completed (we got an "execution END" report)
 * or not.
 */
create TABLE ReportsExecution (
  nodeId       text NOT NULL
, date         timestamp with time zone NOT NULL
, nodeConfigId text
, insertionId  bigint
, insertiondate timestamp with time zone default now()
, compliancecomputationdate timestamp with time zone
, PRIMARY KEY(nodeId, date)
);

create index reportsexecution_date_idx on ReportsExecution (date);
create index reportsexecution_nodeid_nodeconfigid_idx on ReportsExecution (nodeId, nodeConfigId);
create index reportsexecution_uncomputedrun_idx on ReportsExecution (compliancecomputationdate) where compliancecomputationdate IS NULL;

alter table reportsexecution set (autovacuum_vacuum_scale_factor = 0.05);

/*
 *************************************************************************************
 * The following tables store what Rudder expects from agent.
 * The are used to store rules versions and corresponding expected datas.
 *************************************************************************************
 */


/*
 * We also have a table of the list of node with configId / generationDate
 * so what we can answer the question: what is the last config id for that node ?
 * The date helps now if we should have received report for that node.
 */
create TABLE nodes_info (
  node_id    text PRIMARY KEY CHECK (node_id <> '')
  -- configs ids are a dump of json: [{"configId":"xxxx", "dateTime": "iso-date-time"} ]
, config_ids text
);

alter table nodes_info set (autovacuum_vacuum_threshold = 0);


-- Create the table for the node configuration
create TABLE nodeConfigurations (
  nodeId            text NOT NULL CHECK (nodeId <> '')
, nodeConfigId      text NOT NULL CHECK (nodeConfigId <> '')
, beginDate         timestamp with time zone NOT NULL
, endDate           timestamp with time zone

-- here, I'm using text but with valid JSON in it, because for now we can't impose postgres > 9.2,
-- and interesting function are on 9.3/9.4.  we will be able to migrate with:
-- ALTER TABLE table1 ALTER COLUMN col1 TYPE JSON USING col1::JSON;
-- or if version == 9.2, we need to do a several steps script like:
-- https://github.com/airblade/paper_trail/issues/600#issuecomment-136279154
-- and then garbage collect space with:
-- vacuum full nodecompliance;

, configuration     text NOT NULL CHECK (configuration <> '' )

-- Primary key is a little complexe because each of nodeId, nodeConfigId, (nodeId, nodeConfigId)
-- can appears several times. We need to also add begin date (and that can broke on a server with
-- time coming back in the past)

, PRIMARY KEY (nodeId, nodeConfigId, beginDate)
);

create index nodeConfigurations_nodeId on nodeConfigurations (nodeId);
create index nodeConfigurations_nodeConfigId on nodeConfigurations (nodeConfigId);

alter table nodeconfigurations set (autovacuum_vacuum_threshold = 0);

/*
 *************************************************************************************
 * The following table stores the last compliance computed for a node.
 * It must be autonomous and in particular it must remains meaningful even if the
 * corresponding expected reports or run are deleted.
 *************************************************************************************
 */

create TABLE NodeLastCompliance (
  nodeId              text NOT NULL CHECK (nodeId <> '') primary key

-- the time when the compliance was computed. Used to know if it's sill valid or should be cleaned/ignored
, computationDateTime timestamp with time zone NOT NULL

-- all information about the run and what lead to that compliance:
-- the run config version, the awaited config version, etc
-- plus the node compliance for that policy type in JSON (meaning / details linked to type)
, details             jsonb NOT NULL
);

-- with the trigger for autovacuum being: autovacuum_vacuum_threshold + autovacuum_vacuum_scale_factor × reltuples
-- (default is 50 + 0.2 * rows)
-- we can make it purely proportional with only the scale_factor as % of rows
-- so expect vacuum for 100 nodes every 5 updates with these:
alter table NodeLastCompliance set (autovacuum_vacuum_threshold = 0);
alter table NodeLastCompliance set (autovacuum_vacuum_scale_factor = 0.05);



/*
 *************************************************************************************
 * The following tables stores "event logs", i.e logs action about user and
 * system event that can leads to configuration changes and are needed to allows
 * audit track logs.
 *************************************************************************************
 */

create sequence eventLogIdSeq start 1;

create TABLE EventLog (
  id             integer PRIMARY KEY  DEFAULT nextval('eventLogIdSeq')
, creationDate   timestamp with time zone NOT NULL DEFAULT 'now'
, severity       integer NOT NULL DEFAULT 100
, causeId        integer
, modificationId text
, principal      text NOT NULL DEFAULT 'unknown'
, reason         text
, eventType      text NOT NULL DEFAULT ''
, data           xml NOT NULL DEFAULT ''
);

create index eventType_idx on EventLog (eventType);
create index creationDate_idx on EventLog (creationDate);
create index eventlog_fileFormat_idx on eventlog (((((xpath('/entry//@fileFormat',data))[1])::text)));


/*
 * That table is used when a migration between
 * event log format is needed.
 */
create sequence MigrationEventLogId start 1;
create TABLE MigrationEventLog (
  id                  integer PRIMARY KEY default(nextval('MigrationEventLogId'))
, detectionTime       timestamp with time zone NOT NULL
, detectedFileFormat  integer
, migrationStartTime  timestamp with time zone
, migrationEndTime    timestamp with time zone
, migrationFileFormat integer
, description         text
);


/*
 *************************************************************************************
 * A table used to store generic properties related to the database and that could not
 * go the the LDAP backend. Typically, that's property that must be updated during a
 * transaction or are really frequently written.
 *************************************************************************************
 */


create TABLE RudderProperties(
  name  text PRIMARY KEY
, value text
);

alter table rudderproperties set (autovacuum_vacuum_threshold = 0);

/*
 *************************************************************************************
 * The following tables are used to manage
 * validation workflows and change requests
 *************************************************************************************
 */

create TABLE gitCommit(
  gitcommit text PRIMARY KEY
, modificationid text
);

create sequence ChangeRequestId start 1;

create TABLE ChangeRequest(
  id        integer PRIMARY KEY default(nextval('ChangeRequestId'))
, name text CHECK (name <> '')
, description text
, creationTime timestamp with time zone
, content xml
, modificationId text
);

create TABLE Workflow(
  id integer references ChangeRequest(id)
, state text
);

create TABLE StatusUpdate (
  key    text PRIMARY KEY
, lastId bigint NOT NULL
, date   timestamp with time zone NOT NULL
);

alter table statusupdate set (autovacuum_vacuum_threshold = 0);

/*
 *************************************************************************************
 * end
 *************************************************************************************
 */

/*
 *************************************************************************************
 * table related to (patch) campaign
 *************************************************************************************
 */

create type campaignEventState as enum ('scheduled', 'pre-hooks', 'running', 'post-hooks', 'finished', 'skipped', 'deleted', 'failure');

CREATE TABLE CampaignEvents (
  campaignId   text
, eventId      text PRIMARY KEY
, name         text
, state        campaignEventState NOT NULL
, startDate    timestamp with time zone NOT NULL
, endDate      timestamp with time zone NOT NULL
, campaignType text
);


CREATE TABLE CampaignEventsStateHistory (
  eventId   text references CampaignEvents(eventId) ON DELETE CASCADE
, state     campaignEventState
, startDate timestamp with time zone NOT NULL
, endDate   timestamp with time zone
, data      jsonb
, PRIMARY KEY (eventId, state)
);

/*
 *************************************************************************************
 * Table for storing node facts info
 *************************************************************************************
 */

CREATE TABLE NodeFacts (
  nodeId            text PRIMARY KEY
, acceptRefuseEvent jsonb  -- { 'date': 'rfc3339 timestamp', 'actor': 'actor name', 'statue'; 'accepted or refused' }
, acceptRefuseFact  jsonb  -- the big node fact data structure
, deleteEvent       jsonb  -- { 'date': 'rfc3339 timestamp', 'actor': 'actor name' }
);


create type score as enum ('A', 'B', 'C', 'D', 'E', 'F', 'X');

Create table GlobalScore (
  nodeId  text primary key
, score   score  NOT NULL
, message text  NOT NULL
, details jsonb  NOT NULL
);

Create table ScoreDetails (
  nodeId  text NOT NULL
, scoreId text NOT NULL
, score   score NOT NULL
, message text NOT NULL
, details jsonb NOT NULL
, PRIMARY KEY (nodeId, scoreId)
);
