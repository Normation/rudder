-- SQL schema for the reports data

-- Create the sequences
Create SEQUENCE confSerialId START 2;
Create SEQUENCE operationSerialId START 2;

Create SEQUENCE confVersionId START 2;
Create SEQUENCE operationVersionId START 2;

-- Create the table for the configuration reports information
create table configurationReportsInfo (
	serialId integer PRIMARY KEY DEFAULT nextval('confSerialId'),
	versionId integer NOT NULL,
	configurationRuleId text NOT NULL CHECK (configurationRuleId <> ''),
	serial integer NOT NULL,
	policyInstanceId text NOT NULL CHECK (policyInstanceId <> ''),
	component text NOT NULL CHECK (component <> ''),
	cardinality integer NOT NULL,
	beginDate timestamp NOT NULL,
	endDate timestamp
);

create index configuration_versionId on configurationReportsInfo (versionId);

create table configurationServerList (
	versionId integer NOT NULL ,
	serverUuid varchar(50) NOT NULL  CHECK (serverUuid <> ''),
	primary key (versionId, serverUuid)
);

-- Create the table for the operation reports information

create table operationReportsInfo (
	serialId integer PRIMARY KEY DEFAULT nextval('confSerialId'),
	versionId integer NOT NULL,
	policyInstanceId text NOT NULL CHECK (policyInstanceId <> ''),
	cardinality integer NOT NULL,
	executionDateTime timestamp NOT NULL,
	reportDateTime timestamp
);

create index operation_versionId on operationReportsInfo (versionId);

create table operationServerList (
	versionId integer NOT NULL,
	serverUuid text NOT NULL  CHECK (serverUuid <> ''),
	primary key (versionId, serverUuid)
);


-- create the table for the reports sent

create sequence serial START 101;

CREATE TABLE RudderSysEvents (
id integer PRIMARY KEY default nextval('serial'),
executionDate timestamp NOT NULL, 
nodeId text NOT NULL CHECK (nodeId <> ''),
policyInstanceId text NOT NULL CHECK (policyInstanceId <> ''),
configurationRuleId text NOT NULL CHECK (configurationRuleId <> ''),
serial integer NOT NULL,
component text NOT NULL CHECK (component <> ''),
keyValue text,
executionTimeStamp timestamp NOT NULL,
eventType varchar(64),
policy text,
msg text
);


create index nodeid_idx on RudderSysEvents (nodeId);
create index date_idx on RudderSysEvents (executionDate);
create index policyInstanceId_idx on RudderSysEvents (policyInstanceId);
create index configurationRuleId_idx on RudderSysEvents (configurationRuleId);
create index configurationRuleId_node_idx on RudderSysEvents (configurationRuleId, nodeId);
create index configurationRuleId_serialed_idx on RudderSysEvents (configurationRuleId, serial);
create index composite_idx on RudderSysEvents (configurationRuleId, policyInstanceId, serial, executionTimeStamp);


-- Log event part

CREATE SEQUENCE eventLogIdSeq START 1;


CREATE TABLE EventLog (
    id integer PRIMARY KEY  DEFAULT nextval('eventLogIdSeq'),
    creationDate timestamp NOT NULL DEFAULT 'now',
    severity integer,
    causeId integer,
    principal varchar(64),
    eventType varchar(64),
    data xml
); 

create index eventType_idx on EventLog (eventType);
create index causeId_idx on EventLog (causeId);
-- look at http://www.slideshare.net/petereisentraut/postgresql-and-xml
--create index data_idx on EventLog (data); -- Would it works ?

