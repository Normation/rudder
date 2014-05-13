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


-- Migration script to update type used for id in active reports table
-- Caution, can be quite slow on large database

BEGIN;

ALTER TABLE ruddersysevents RENAME TO old_ruddersysevents;

DROP INDEX nodeid_idx;
DROP INDEX executionTimeStamp_idx;
DROP INDEX composite_node_execution_idx;
DROP INDEX component_idx;
DROP INDEX keyValue_idx;
DROP INDEX ruleId_idx;

CREATE TABLE ruddersysevents (
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


CREATE INDEX nodeid_idx on ruddersysevents (nodeId);
CREATE INDEX executionTimeStamp_idx on ruddersysevents (executionTimeStamp);
CREATE INDEX composite_node_execution_idx on ruddersysevents (nodeId, executionTimeStamp);
CREATE INDEX component_idx on ruddersysevents (component);
CREATE INDEX keyValue_idx on ruddersysevents (keyValue);
CREATE INDEX ruleId_idx on ruddersysevents (ruleId);


COMMIT;

INSERT INTO ruddersysevents SELECT * FROM old_ruddersysevents;

DROP TABLE old_ruddersysevents;

