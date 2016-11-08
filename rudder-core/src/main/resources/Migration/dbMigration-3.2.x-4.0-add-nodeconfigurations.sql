/*
*************************************************************************************
* Copyright 2016 Normation SAS
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

-- Create the table for the node configuration
CREATE TABLE nodeConfigurations (
  nodeId            text NOT NULL CHECK (nodeId <> '')  
, nodeConfigId      text NOT NULL CHECK (nodeConfigId <> '')
, beginDate         timestamp with time zone NOT NULL
, endDate           timestamp with time zone

-- here, I'm using text but with valid JSON in it, because for now we can't impose postgres > 9.2, 
-- and interesting function are on 9.3/9.4.  we will be able to migrate with:
-- ALTER TABLE table1 ALTER COLUMN col1 TYPE JSON USING col1::JSON;

, configuration     text NOT NULL CHECK (configuration <> '' )

-- Primary key is a little complexe because each of nodeId, nodeConfigId, (nodeId, nodeConfigId)
-- can appears several times. We need to also add begin date (and that can broke on a server with
-- time coming back in the past)

, PRIMARY KEY (nodeId, nodeConfigId, beginDate)
);

CREATE INDEX nodeConfigurations_nodeId ON nodeConfigurations (nodeId);
CREATE INDEX nodeConfigurations_nodeConfigId ON nodeConfigurations (nodeConfigId);

-- Add a new index on reportsExecution to allows an outer join between them and nodeConfigurations.
CREATE INDEX reportsexecution_nodeid_nodeconfigid_idx ON ReportsExecution (nodeId, nodeConfigId);
