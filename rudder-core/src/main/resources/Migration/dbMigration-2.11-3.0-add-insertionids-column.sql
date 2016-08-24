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


-- add the columns needed to store the report insertion id:
-- in reportExectution, "insertionId"
ALTER TABLE ReportsExecution ADD COLUMN insertionId bigint default 0;

-- Add the index on this entry
CREATE INDEX reportsexecution_insertionid_idx ON ReportsExecution (insertionId);

-- Do the migration script
-- Add the id from the ruddersysevents when available, but only for the last run of each node

UPDATE ReportsExecution SET insertionId = S.id 
FROM 
(
  SELECT T.nodeid, T.executiontimestamp, min(id) AS id FROM
    (
      SELECT DISTINCT nodeid, max(date) AS executiontimestamp FROM ReportsExecution GROUP BY nodeid
    ) AS T
  LEFT JOIN ruddersysevents AS R
  ON T.nodeid = R.nodeid AND T.executiontimestamp = R.executiontimestamp
  GROUP BY T.nodeid, T.executiontimestamp
) AS S WHERE ReportsExecution.date = S.executiontimestamp AND ReportsExecution.nodeid=S.nodeid;


