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
set client_min_messages='warning';


-- Create the sequences
Create SEQUENCE confSerialId START 1;

Create SEQUENCE confVersionId START 1;

-- Create the table for the configuration reports information
create table expectedReports (
	pkId integer PRIMARY KEY DEFAULT nextval('confSerialId'),
	nodeJoinKey integer NOT NULL,
	configurationRuleId text NOT NULL CHECK (configurationRuleId <> ''),
	serial integer NOT NULL,
	policyInstanceId text NOT NULL CHECK (policyInstanceId <> ''),
	component text NOT NULL CHECK (component <> ''),
	cardinality integer NOT NULL,
	componentsValues text NOT NULL, -- this is the serialisation of the expected values 
	beginDate timestamp with time zone NOT NULL,
	endDate timestamp with time zone
);

create index expectedReports_versionId on expectedReports (nodeJoinKey);
create index expectedReports_serialId on expectedReports (configurationRuleId, serial);

create table expectedReportsNodes (
	nodeJoinKey integer NOT NULL ,
	nodeId varchar(50) NOT NULL  CHECK (nodeId <> ''),
	primary key (nodeJoinKey, nodeId)
);

create index expectedReportsNodes_versionId on expectedReportsNodes (nodeJoinKey);


-- create the table for the reports sent

create sequence serial START 101;

CREATE TABLE RudderSysEvents (
id integer PRIMARY KEY default nextval('serial'),
executionDate timestamp with time zone NOT NULL, 
nodeId text NOT NULL CHECK (nodeId <> ''),
policyInstanceId text NOT NULL CHECK (policyInstanceId <> ''),
configurationRuleId text NOT NULL CHECK (configurationRuleId <> ''),
serial integer NOT NULL,
component text NOT NULL CHECK (component <> ''),
keyValue text,
executionTimeStamp timestamp with time zone NOT NULL,
eventType varchar(64),
policy text,
msg text
);


create index nodeid_idx on RudderSysEvents (nodeId);
create index reports_idx on RudderSysEvents (executionTimeStamp, nodeId);
create index configurationRuleId_node_idx on RudderSysEvents (configurationRuleId, nodeId, serial, executionTimeStamp);



CREATE SEQUENCE eventLogIdSeq START 1;


CREATE TABLE EventLog (
    id integer PRIMARY KEY  DEFAULT nextval('eventLogIdSeq'),
    creationDate timestamp with time zone NOT NULL DEFAULT 'now',
    severity integer,
    causeId integer,
    principal varchar(64),
    eventType varchar(64),
    data xml
); 

create index eventType_idx on EventLog (eventType);
create index creationDate_idx on EventLog (creationDate);



create sequence GroupsId START 101;


CREATE TABLE Groups (
id integer PRIMARY KEY default nextval('GroupsId'),
groupId text NOT NULL CHECK (groupId <> ''),
groupName text,
groupDescription text,
nodeCount int,
groupStatus int default 2,
startTime timestamp with time zone default now(),
endTime timestamp with time zone
);



create index groups_id_start on Groups (groupId, startTime);
create index groups_end on Groups (endTime);


create sequence PolicyInstancesId START 101;


CREATE TABLE PolicyInstances (
id integer PRIMARY KEY default nextval('PolicyInstancesId'),
policyInstanceId text NOT NULL CHECK (policyInstanceId <> ''),
policyInstanceName text,
policyInstanceDescription text,
priority integer NOT NULL,
policyPackageName text,
policyPackageVersion text,
policyPackageDescription text,
policyTemplateHumanName text,
startTime timestamp with time zone NOT NULL,
endTime timestamp with time zone
);


create index pi_id_start on PolicyInstances (policyInstanceId, startTime);
create index pi_end on PolicyInstances (endTime);

create sequence ConfigurationRulesId START 101;


CREATE TABLE ConfigurationRules (
id integer PRIMARY KEY default nextval('ConfigurationRulesId'),
configurationRuleId text NOT NULL CHECK (configurationRuleId <> ''),
serial integer NOT NULL,
name text,
shortdescription text,
longdescription text,
isActivated boolean,
startTime timestamp with time zone NOT NULL,
endTime timestamp with time zone
);

CREATE TABLE ConfigurationRulesGroups (
CrId integer, -- really the id of the table ConfigurationRules
groupId text NOT NULL CHECK (groupId <> ''),
PRIMARY KEY(CrId, groupId)
);

CREATE TABLE ConfigurationRulesPolicyInstance (
CrId integer, -- really the id of the table ConfigurationRules
policyInstanceId text NOT NULL CHECK (policyInstanceId <> ''),
PRIMARY KEY(CrId, policyInstanceId)
);


create index cr_id_start on ConfigurationRules (configurationRuleId, startTime);
create index cr_end on ConfigurationRules (endTime);




create sequence NodesId START 101;


CREATE TABLE Nodes (
id integer PRIMARY KEY default nextval('NodesId'),
nodeId text NOT NULL CHECK (nodeId <> ''),
nodeName text,
nodeDescription text,
startTime timestamp with time zone default now(),
endTime timestamp with time zone
);



create index nodes_id_start on Nodes (nodeId, startTime);
create index nodes_end on Nodes (endTime);


