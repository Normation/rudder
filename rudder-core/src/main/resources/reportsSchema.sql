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

CREATE SEQUENCE serial START 101;

CREATE TABLE RudderSysEvents (
  id                 bigint PRIMARY KEY default nextval('serial')
, executionDate      timestamp with time zone NOT NULL
, nodeId             text NOT NULL CHECK (nodeId <> '')
, directiveId        text NOT NULL CHECK (directiveId <> '')
, ruleId             text NOT NULL CHECK (ruleId <> '')
, serial             integer NOT NULL
, component          text NOT NULL CHECK (component <> '')
, keyValue           text
, executionTimeStamp timestamp with time zone NOT NULL
, eventType          varchar(64)
, policy             text
, msg                text
);


CREATE INDEX nodeid_idx                   ON RudderSysEvents (nodeId);
CREATE INDEX executionTimeStamp_idx       ON RudderSysEvents (executionTimeStamp);
CREATE INDEX composite_node_execution_idx ON RudderSysEvents (nodeId, executionTimeStamp);
CREATE INDEX component_idx                ON RudderSysEvents (component);
CREATE INDEX keyValue_idx                 ON RudderSysEvents (keyValue);
CREATE INDEX ruleId_idx                   ON RudderSysEvents (ruleId);

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
, serial             integer NOT NULL
, component          text NOT NULL CHECK (component <> '')
, keyValue           text
, executionTimeStamp timestamp with time zone NOT NULL
, eventType          varchar(64)
, policy             text
, msg                text
);

CREATE INDEX executionTimeStamp_archived_idx ON ArchivedRudderSysEvents (executionTimeStamp);

/*
 * That table store the agent execution times for each nodes. 
 * We keep the starting time of the given run and the fact 
 * that the run completed (we got an "execution END" report) 
 * or not. 
 */
CREATE TABLE ReportsExecution (
  nodeId       text NOT NULL
, date         timestamp with time zone NOT NULL
, complete     boolean NOT NULL
, nodeConfigId text
, PRIMARY KEY(nodeId, date)
);

CREATE INDEX reportsexecution_date_idx ON ReportsExecution (date);

/* 
 *************************************************************************************
 * The following tables store what Rudder expects from agent. 
 * The are used to store rules versions and corresponding expected datas. 
 *************************************************************************************
 */

-- Create the sequences
CREATE SEQUENCE ruleSerialId START 1;

-- that sequence is used for nodeJoinKey value
CREATE SEQUENCE ruleVersionId START 1;


-- Create the table for the reports information
CREATE TABLE expectedReports (
  pkId                       integer PRIMARY KEY DEFAULT nextval('ruleSerialId')
, nodeJoinKey                integer NOT NULL
, ruleId                     text NOT NULL CHECK (ruleId <> '')
, serial                     integer NOT NULL
, directiveId                text NOT NULL CHECK (directiveId <> '')
, component                  text NOT NULL CHECK (component <> '')
, cardinality                integer NOT NULL
, componentsValues           text NOT NULL -- this is the serialisation of the expected values 
, unexpandedComponentsValues text -- this is the serialisatin of the unexpanded expected values. It may be null for pre-2.6 entries
, beginDate                  timestamp with time zone NOT NULL
, endDate                    timestamp with time zone
);

CREATE INDEX expectedReports_versionId ON expectedReports (nodeJoinKey);
CREATE INDEX expectedReports_serialId ON expectedReports (ruleId, serial);

CREATE TABLE expectedReportsNodes (
  nodeJoinKey   integer NOT NULL 
, nodeId        varchar(50) NOT NULL CHECK (nodeId <> '')
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
, PRIMARY KEY (nodeJoinKey, nodeId)
);
CREATE INDEX expectedReportsNodes_versionId ON expectedReportsNodes (nodeJoinKey);


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

CREATE SEQUENCE eventLogIdSeq START 1;

CREATE TABLE EventLog (
  id             integer PRIMARY KEY  DEFAULT nextval('eventLogIdSeq')
, creationDate   timestamp with time zone NOT NULL DEFAULT 'now'
, severity       integer
, causeId        integer
, modificationId text
, principal      text
, reason         text
, eventType      varchar(64)
, data           xml
); 

CREATE INDEX eventType_idx ON EventLog (eventType);
CREATE INDEX creationDate_idx ON EventLog (creationDate);

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


/* 
 *************************************************************************************
 * The following tables stores names about object to be able to historize them
 * and present them back to the user in a meaningful way. 
 *************************************************************************************
 */

CREATE SEQUENCE GroupsId START 101;

CREATE TABLE Groups (
  id               integer PRIMARY KEY default nextval('GroupsId')
, groupId          text NOT NULL CHECK (groupId <> '')
, groupName        text
, groupDescription text
, nodeCount        int
, groupStatus      int default 2
, startTime        timestamp with time zone default now()
, endTime          timestamp with time zone
);

CREATE TABLE GroupsNodesJoin (
  groupPkeyId integer -- really the id of the table Groups
, nodeId      text NOT NULL CHECK (nodeid <> '')
, PRIMARY KEY(groupPkeyId, nodeId)
);

CREATE INDEX groups_id_start ON Groups (groupId, startTime);
CREATE INDEX groups_end ON Groups (endTime);

CREATE SEQUENCE directivesId START 101;

CREATE TABLE Directives (
  id                   integer PRIMARY KEY default nextval('directivesId')
, directiveId          text NOT NULL CHECK (directiveId <> '')
, directiveName        text
, directiveDescription text
, priority             integer NOT NULL
, techniqueName        text
, techniqueVersion     text
, techniqueDescription text
, techniqueHumanName   text
, startTime            timestamp with time zone NOT NULL
, endTime              timestamp with time zone
);

CREATE INDEX directive_id_start ON Directives (directiveId, startTime);
CREATE INDEX directive_end ON Directives (endTime);

CREATE SEQUENCE rulesId START 101;

CREATE TABLE Rules (
  rulePkeyId       integer PRIMARY KEY default nextval('rulesId')
, ruleId           text NOT NULL CHECK (ruleId <> '')
, serial           integer NOT NULL
, name             text
, shortdescription text
, longdescription  text
, isEnabled        boolean
, startTime        timestamp with time zone NOT NULL
, endTime          timestamp with time zone
);

CREATE TABLE RulesGroupJoin (
  rulePkeyId integer -- really the id of the table Rules
, groupId    text NOT NULL CHECK (groupId <> '')
, PRIMARY KEY(rulePkeyId, groupId)
);

CREATE TABLE RulesDirectivesJoin (
  rulePkeyId integer -- really the id of the table Rules
, directiveId text NOT NULL CHECK (directiveId <> '')
, PRIMARY KEY(rulePkeyId, directiveId)
);

CREATE INDEX rule_id_start ON Rules (ruleId, startTime);
CREATE INDEX rule_end      ON Rules (endTime);

CREATE SEQUENCE NodesId START 101;

CREATE TABLE Nodes (
  id              integer PRIMARY KEY default nextval('NodesId')
, nodeId          text NOT NULL CHECK (nodeId <> '')
, nodeName        text
, nodeDescription text
, startTime       timestamp with time zone default now()
, endTime         timestamp with time zone
);

CREATE INDEX nodes_id_start ON Nodes (nodeId, startTime);
CREATE INDEX nodes_end      ON Nodes (endTime);

/*
 *************************************************************************************
 * end
 *************************************************************************************
 */
