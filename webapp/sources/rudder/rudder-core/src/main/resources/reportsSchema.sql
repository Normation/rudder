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

CREATE SEQUENCE serial START 101;

CREATE TABLE Users (
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

CREATE TABLE UserSessions (
  userId       text NOT NULL CHECK (userId <> '')
, sessionId    text NOT NULL CHECK (sessionId <> '')
, creationDate timestamp with time zone NOT NULL
, authMethod   text
, permissions  text[]
, endDate      timestamp with time zone
, endCause     text
, PRIMARY KEY(userId, sessionId)
);


CREATE TABLE RudderSysEvents (
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


CREATE INDEX executionTimeStamp_idx       ON RudderSysEvents (executionTimeStamp);
CREATE INDEX composite_node_execution_idx ON RudderSysEvents (nodeId, executionTimeStamp);
CREATE INDEX ruleId_idx                   ON RudderSysEvents (ruleId);

CREATE INDEX endRun_control_idx ON RudderSysEvents (id) WHERE eventType = 'control' and component = 'end';
CREATE INDEX changes_executionTimeStamp_idx ON RudderSysEvents (executionTimeStamp) WHERE eventType = 'result_repaired';

/*
 * The table used to store archived agent execution reports.
 */
CREATE TABLE ArchivedRudderSysEvents (
  id                 bigint PRIMARY KEY
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

CREATE INDEX executionTimeStamp_archived_idx ON ArchivedRudderSysEvents (executionTimeStamp);

ALTER TABLE archivedruddersysevents set (autovacuum_vacuum_scale_factor = 0.005);

/*
 * That table store the agent execution times for each nodes.
 * We keep the starting time of the given run and the fact
 * that the run completed (we got an "execution END" report)
 * or not.
 */
CREATE TABLE ReportsExecution (
  nodeId       text NOT NULL
, date         timestamp with time zone NOT NULL
, nodeConfigId text
, insertionId  bigint
, insertiondate timestamp default now()
, compliancecomputationdate timestamp
, PRIMARY KEY(nodeId, date)
);

CREATE INDEX reportsexecution_date_idx ON ReportsExecution (date);
CREATE INDEX reportsexecution_nodeid_nodeconfigid_idx ON ReportsExecution (nodeId, nodeConfigId);
CREATE INDEX reportsexecution_uncomputedrun_idx on ReportsExecution (compliancecomputationdate) where compliancecomputationdate IS NULL;

ALTER TABLE reportsexecution set (autovacuum_vacuum_scale_factor = 0.05);

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
CREATE TABLE nodes_info (
  node_id    text PRIMARY KEY CHECK (node_id <> '')
  -- configs ids are a dump of json: [{"configId":"xxxx", "dateTime": "iso-date-time"} ]
, config_ids text
);

ALTER TABLE nodes_info set (autovacuum_vacuum_threshold = 0);


-- Create the table for the node configuration
CREATE TABLE nodeConfigurations (
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

CREATE INDEX nodeConfigurations_nodeId ON nodeConfigurations (nodeId);
CREATE INDEX nodeConfigurations_nodeConfigId ON nodeConfigurations (nodeConfigId);

ALTER TABLE nodeconfigurations set (autovacuum_vacuum_threshold = 0);

-- Create the table for the archived node configurations
CREATE TABLE archivedNodeConfigurations (
  nodeId            text NOT NULL CHECK (nodeId <> '')
, nodeConfigId      text NOT NULL CHECK (nodeConfigId <> '')
, beginDate         timestamp with time zone NOT NULL
, endDate           timestamp with time zone
, configuration     text NOT NULL CHECK (configuration <> '' )
, PRIMARY KEY (nodeId, nodeConfigId, beginDate)
);

ALTER TABLE archivednodeconfigurations set (autovacuum_vacuum_threshold = 0);

/*
 *************************************************************************************
 * The following tables stores "node compliance", i.e all the interesting information
 * about what was the compliance of a node FOR A GIVEN RUN.
 * That table *only* store information for runs, and does not track (non exaustively):
 * - when the node expected configuration is updated - only a new run will check,
 * - node not sending runs - only the fact that we don't have data can be observed
 * - if a node is deleted
 *************************************************************************************
 */

-- Create the table for the node compliance
CREATE TABLE nodeCompliance (
  nodeId            text NOT NULL CHECK (nodeId <> '')
, runTimestamp      timestamp with time zone NOT NULL

-- endOfList is the date until which the compliance information
-- are relevant. After that date/time, the node must have sent
-- a more recent run, this one is not valide anymore.
, endOfLife         timestamp with time zone

-- all information about the run and what lead to that compliance:
-- the run config version, the awaited config version, etc
-- It's JSON (but in a string, cf explanation in nodeConfigurations table)
-- and has such, it must not be empty (at least '{}')

, runAnalysis       text NOT NULL CHECK (runAnalysis <> '' )

-- node compliance summary (i.e, no details by rule etc), in percent
-- that JSON, again

, summary           text NOT NULL CHECK (summary <> '' )

-- the actual compliance with all details
-- Again, JSON

, details  text NOT NULL CHECK (details <> '' )

-- Primary key is given by a run timestamp and the node id. We could
-- have duplicate if node clock change, but it would need to have
-- exact same timestamp down to the millis, quite improbable.

, PRIMARY KEY (nodeId, runTimestamp)
);

CREATE INDEX nodeCompliance_nodeId ON nodeCompliance (nodeId);
CREATE INDEX nodeCompliance_runTimestamp ON nodeCompliance (runTimestamp);
CREATE INDEX nodeCompliance_endOfLife ON nodeCompliance (endOfLife);

ALTER TABLE nodecompliance set (autovacuum_vacuum_threshold = 0);
ALTER TABLE nodecompliance set (autovacuum_vacuum_scale_factor = 0.1);

-- Create the table for the archived node compliance
CREATE TABLE archivedNodeCompliance (
  nodeId            text NOT NULL CHECK (nodeId <> '')
, runTimestamp      timestamp with time zone NOT NULL
, endOfLife         timestamp with time zone
, runAnalysis       text NOT NULL CHECK (runAnalysis <> '' )
, summary           text NOT NULL CHECK (summary <> '' )
, details  text NOT NULL CHECK (details <> '' )
, PRIMARY KEY (nodeId, runTimestamp)
);

ALTER TABLE archivednodecompliance set (autovacuum_vacuum_threshold = 0);
ALTER TABLE archivednodecompliance set (autovacuum_vacuum_scale_factor = 0.1);

-- Create a table of only (nodeid, ruleid, directiveid) -> complianceLevel
-- for all runs. That table is amendable to postgresql-side processing,
-- in particular to allow aggregation of compliance by rule / node / directive,
-- but with a much more reasonable space until all our supported server versions
-- have at least PostgreSQL 9.4.
CREATE TABLE nodecompliancelevels (
  nodeId             text NOT NULL CHECK (nodeId <> '')
, runTimestamp       timestamp with time zone NOT NULL
, ruleId             text NOT NULL CHECK (ruleId <> '')
, directiveId        text NOT NULL CHECK (directiveId <> '')
, pending            int DEFAULT 0
, success            int DEFAULT 0
, repaired           int DEFAULT 0
, error              int DEFAULT 0
, unexpected         int DEFAULT 0
, missing            int DEFAULT 0
, noAnswer           int DEFAULT 0
, notApplicable      int DEFAULT 0
, reportsDisabled    int DEFAULT 0
, compliant          int DEFAULT 0
, auditNotApplicable int DEFAULT 0
, nonCompliant       int DEFAULT 0
, auditError         int DEFAULT 0
, badPolicyMode      int DEFAULT 0
, PRIMARY KEY (nodeId, runTimestamp, ruleId, directiveId)
);

CREATE INDEX nodecompliancelevels_nodeId ON nodecompliancelevels (nodeId);
CREATE INDEX nodecompliancelevels_ruleId_idx ON nodecompliancelevels (ruleId);
CREATE INDEX nodecompliancelevels_directiveId_idx ON nodecompliancelevels (directiveId);
CREATE INDEX nodecompliancelevels_runTimestamp ON nodecompliancelevels (runTimestamp);

ALTER TABLE nodecompliancelevels set (autovacuum_vacuum_scale_factor = 0.05);



/*
 *************************************************************************************
 * The following tables stores "event logs", i.e logs action about user and
 * system event that can leads to configuration changes and are needed to allows
 * audit track logs.
 *************************************************************************************
 */

CREATE SEQUENCE eventLogIdSeq START 1;

CREATE TABLE EventLog (
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

CREATE INDEX eventType_idx ON EventLog (eventType);
CREATE INDEX creationDate_idx ON EventLog (creationDate);
CREATE INDEX eventlog_fileFormat_idx ON eventlog (((((xpath('/entry//@fileFormat',data))[1])::text)));


/*
 * That table is used when a migration between
 * event log format is needed.
 */
CREATE SEQUENCE MigrationEventLogId start 1;
CREATE TABLE MigrationEventLog (
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


CREATE TABLE RudderProperties(
  name  text PRIMARY KEY
, value text
);

ALTER TABLE rudderproperties set (autovacuum_vacuum_threshold = 0);

/*
 *************************************************************************************
 * The following tables are used to manage
 * validation workflows and change requests
 *************************************************************************************
 */

CREATE TABLE gitCommit(
  gitcommit text PRIMARY KEY
, modificationid text
);

CREATE SEQUENCE ChangeRequestId start 1;

CREATE TABLE ChangeRequest(
  id        integer PRIMARY KEY default(nextval('ChangeRequestId'))
, name text CHECK (name <> '')
, description text
, creationTime timestamp with time zone
, content xml
, modificationId text
);

CREATE TABLE Workflow(
  id integer references ChangeRequest(id)
, state text
);

CREATE TABLE StatusUpdate (
  key    text PRIMARY KEY
, lastId bigint NOT NULL
, date   timestamp with time zone NOT NULL
);

ALTER TABLE statusupdate set (autovacuum_vacuum_threshold = 0);

/*
 *************************************************************************************
 * end
 *************************************************************************************
 */


CREATE TABLE CampaignEvents (
  campaignId   text
, eventid      text PRIMARY KEY
, name         text
, state        jsonb
, startDate    timestamp with time zone NOT NULL
, endDate      timestamp with time zone NOT NULL
, campaignType text
);


CREATE INDEX event_state_index ON CampaignEvents ((state->>'value'));
