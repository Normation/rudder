/*
*************************************************************************************
* Copyright 2012-2013 Normation SAS
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

-- This script creates the tables to store agent execution time
-- and populate the tables with existing information
-- If the table ReportsExecution already exists and is populated, the script will complain
-- but not insert duplicate values


-- First, create the tables

create table StatusUpdate (
key text PRIMARY KEY,
lastId integer NOT NULL,
date timestamp with time zone NOT NULL
);

create table ReportsExecution (
nodeId text NOT NULL,
date timestamp with time zone NOT NULL,
complete boolean NOT NULL,
PRIMARY KEY(nodeId, date)
);

create index reportsexecution_date_idx on ReportsExecution (date);

-- Create the temporary table to fasten the computation of agents execution time
create temp table tempExecutionTime  (
nodeId text NOT NULL,
date timestamp with time zone NOT NULL,
PRIMARY KEY(nodeId, date)
);

create temp table tempCompleteExecutionTime (
nodeId text NOT NULL,
date timestamp with time zone NOT NULL,
complete boolean NOT NULL,
PRIMARY KEY(nodeId, date)
);

-- Create a temporary table to store the max Id and datetime of handled data
create temp table tempStatusUpdate (
lastId integer NOT NULL,
date timestamp with time zone NOT NULL
);

insert into tempStatusUpdate
  select id, executionTimeStamp from ruddersysevents order by id desc limit 1;

-- Store in the temporary table the executions times
insert into tempExecutionTime
  (select distinct nodeid, executiontimestamp from ruddersysevents where component = 'common');

-- Store in temporary table the complete executio times
insert into tempCompleteExecutionTime
  (select distinct nodeid, executiontimestamp, true as isComplete from
     ruddersysevents where ruleId like 'hasPolicyServer%' and component = 'common' and keyValue = 'EndRun');

-- Save the values

insert into ReportsExecution
  select T.nodeid, T.date, coalesce(C.complete, false) from tempExecutionTime as T left join tempCompleteExecutionTime as C on T.nodeid = C.nodeid and T.date = C.date;

-- Finally, store the max considered value
insert into StatusUpdate
  select 'executionStatus', lastId, date from tempStatusUpdate;


