/*
*************************************************************************************
* Copyright 2014 Normation SAS
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

package com.normation.rudder.repository.jdbc

import scala.io.Source
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import scala.slick.driver.JdbcDriver.backend.Database
import scala.slick.jdbc.{GetResult, StaticQuery => Q}
import Q.interpolation
import com.normation.rudder.migration.DBCommon
import java.sql.Timestamp
import MyPostgresDriver.simple._

/**
 *
 * Test on database.
 *
 */
@RunWith(classOf[JUnitRunner])
class MigrationTo212Test extends DBCommon {


  //we don't want the default 2.12 tables to be created
  override def sqlInit : String = ""

  lazy val db = new SlickSchema(dataSource)
  import db._
  import db.slickDB._

  sequential

  "Creating the 2.11 database followed by migration" should {

    val sql = {
      val is = this.getClass().getClassLoader().getResourceAsStream("Migration/dbMigration-2.11-2.12-add-nodeconfigids-columns.sql")
      val sqlMigration = Source.fromInputStream(is).getLines.toSeq.mkString("\n")
      is.close()

      ((SQL_Rudder211.sql + "\n" + sqlMigration)
         . toLowerCase
         .replaceAll("create table", "create temp table")
         .replaceAll("create sequence", "create temp sequence")
         .replaceAll("alter database rudder", "alter database test")
      )
    }

    "no raise error" in {
      jdbcTemplate.execute(sql)
      withSession { implicit s =>
        //some select to check existence
        sql"select * from reportsexecution".as[(String,Timestamp,Boolean,String)].firstOption
        sql"select * from expectedReportsNodes".as[(Int,String)].firstOption
      }
      success
    }

    "allows to add an entry in reportsexecution with nodeConfiguration" in {
      withSession { implicit s =>
        val t= ("node_1", new Timestamp(System.currentTimeMillis), false, "node_config_1")

        sqlu"""insert into  reportsexecution (nodeid, date, complete, nodeconfigid) values (${t._1}, ${t._2}, ${t._3}, ${t._4});""".first

        sql"select * from reportsexecution".as[(String,Timestamp,Boolean,String)].first === t
      }
    }

    "allows to add entry in expectedreportsnodes with node configuration" in {

      val t= SlickExpectedReportsNodes(42, "node_1", List("node_config_1","node_config_2","node_config_3"))

      slickExec { implicit s =>

        expectedReportsNodesTable +=  t

        expectedReportsNodesTable.first === t
      }
    }

  }

}

object SQL_Rudder211 {

  val sql = """
-- SQL schema for the reports data

-- set the report to warnings
set client_min_messages='warning';


-- Enforce support for standart string (unescaped \)
ALTER database rudder SET standard_conforming_strings=true;

-- Create the sequences
Create SEQUENCE ruleSerialId START 1;

Create SEQUENCE ruleVersionId START 1;

-- Create the table for the reports information
create table expectedReports (
     pkId integer PRIMARY KEY DEFAULT nextval('ruleSerialId'),
     nodeJoinKey integer NOT NULL,
     ruleId text NOT NULL CHECK (ruleId <> ''),
     serial integer NOT NULL,
     directiveId text NOT NULL CHECK (directiveId <> ''),
     component text NOT NULL CHECK (component <> ''),
     cardinality integer NOT NULL,
     componentsValues text NOT NULL, -- this is the serialisation of the expected values
     unexpandedComponentsValues text, -- this is the serialisatin of the unexpanded expected values. It may be null for pre-2.6 entries
     beginDate timestamp with time zone NOT NULL,
     endDate timestamp with time zone
);

create index expectedReports_versionId on expectedReports (nodeJoinKey);
create index expectedReports_serialId on expectedReports (ruleId, serial);

create table expectedReportsNodes (
     nodeJoinKey integer NOT NULL ,
     nodeId varchar(50) NOT NULL  CHECK (nodeId <> ''),
     primary key (nodeJoinKey, nodeId)
);

create index expectedReportsNodes_versionId on expectedReportsNodes (nodeJoinKey);


-- create the table for the reports sent

create sequence serial START 101;

CREATE TABLE RudderSysEvents (
id bigint PRIMARY KEY default nextval('serial'),
executionDate timestamp with time zone NOT NULL,
nodeId text NOT NULL CHECK (nodeId <> ''),
directiveId text NOT NULL CHECK (directiveId <> ''),
ruleId text NOT NULL CHECK (ruleId <> ''),
serial integer NOT NULL,
component text NOT NULL CHECK (component <> ''),
keyValue text,
executionTimeStamp timestamp with time zone NOT NULL,
eventType varchar(64),
policy text,
msg text
);


create index nodeid_idx on RudderSysEvents (nodeId);
CREATE INDEX executionTimeStamp_idx on RudderSysEvents (executionTimeStamp);
CREATE INDEX composite_node_execution_idx on RudderSysEvents (nodeId, executionTimeStamp);
CREATE INDEX component_idx on RudderSysEvents (component);
CREATE INDEX keyValue_idx on RudderSysEvents (keyValue);
CREATE INDEX ruleId_idx on RudderSysEvents (ruleId);


CREATE TABLE ArchivedRudderSysEvents (
id bigint PRIMARY KEY,
executionDate timestamp with time zone NOT NULL,
nodeId text NOT NULL CHECK (nodeId <> ''),
directiveId text NOT NULL CHECK (directiveId <> ''),
ruleId text NOT NULL CHECK (ruleId <> ''),
serial integer NOT NULL,
component text NOT NULL CHECK (component <> ''),
keyValue text,
executionTimeStamp timestamp with time zone NOT NULL,
eventType varchar(64),
policy text,
msg text
);

create index executionTimeStamp_archived_idx on ArchivedRudderSysEvents (executionTimeStamp);


CREATE SEQUENCE eventLogIdSeq START 1;


CREATE TABLE EventLog (
    id integer PRIMARY KEY  DEFAULT nextval('eventLogIdSeq'),
    creationDate timestamp with time zone NOT NULL DEFAULT 'now',
    severity integer,
    causeId integer,
    modificationId text,
    principal text,
    reason text,
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


CREATE TABLE GroupsNodesJoin (
groupPkeyId integer, -- really the id of the table Groups
nodeId text NOT NULL CHECK (nodeid <> ''),
PRIMARY KEY(groupPkeyId, nodeId)
);


create index groups_id_start on Groups (groupId, startTime);
create index groups_end on Groups (endTime);


create sequence directivesId START 101;


CREATE TABLE Directives (
id integer PRIMARY KEY default nextval('directivesId'),
directiveId text NOT NULL CHECK (directiveId <> ''),
directiveName text,
directiveDescription text,
priority integer NOT NULL,
techniqueName text,
techniqueVersion text,
techniqueDescription text,
techniqueHumanName text,
startTime timestamp with time zone NOT NULL,
endTime timestamp with time zone
);


create index directive_id_start on Directives (directiveId, startTime);
create index directive_end on Directives (endTime);

create sequence rulesId START 101;


CREATE TABLE Rules (
rulePkeyId integer PRIMARY KEY default nextval('rulesId'),
ruleId text NOT NULL CHECK (ruleId <> ''),
serial integer NOT NULL,
name text,
shortdescription text,
longdescription text,
isEnabled boolean,
startTime timestamp with time zone NOT NULL,
endTime timestamp with time zone
);

CREATE TABLE RulesGroupJoin (
rulePkeyId integer, -- really the id of the table Rules
groupId text NOT NULL CHECK (groupId <> ''),
PRIMARY KEY(rulePkeyId, groupId)
);

CREATE TABLE RulesDirectivesJoin (
rulePkeyId integer, -- really the id of the table Rules
directiveId text NOT NULL CHECK (directiveId <> ''),
PRIMARY KEY(rulePkeyId, directiveId)
);


create index rule_id_start on Rules (ruleId, startTime);
create index rule_end on Rules (endTime);




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

create sequence MigrationEventLogId start 1;

CREATE TABLE MigrationEventLog(
  id                  integer PRIMARY KEY default(nextval('MigrationEventLogId'))
, detectionTime       timestamp with time zone NOT NULL
, detectedFileFormat  integer
, migrationStartTime  timestamp with time zone
, migrationEndTime    timestamp with time zone
, migrationFileFormat integer
, description         text
);

CREATE TABLE RudderProperties(
  name text PRIMARY KEY
, value text
);

CREATE TABLE gitCommit(
  gitcommit text PRIMARY KEY
, modificationid text
);


create sequence ChangeRequestId start 1;

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

create table StatusUpdate (
key text PRIMARY KEY,
lastId bigint NOT NULL,
date timestamp with time zone NOT NULL
);

create table ReportsExecution (
nodeId text NOT NULL,
date timestamp with time zone NOT NULL,
complete boolean NOT NULL,
PRIMARY KEY(nodeId, date)
);

create index reportsexecution_date_idx on ReportsExecution (date);

"""
}
