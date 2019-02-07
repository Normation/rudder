/*
*************************************************************************************
* Copyright 2018 Normation SAS
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

CREATE TABLE nodecompliancelevels (
  nodeId             text NOT NULL CHECK (nodeId <> '')
, runTimestamp       timestamp with time zone NOT NULL
, ruleId             text NOT NULL CHECK (nodeId <> '')
, directiveId        text NOT NULL CHECK (nodeId <> '')
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
CREATE INDEX nodecompliancelevels_ruleId ON nodecompliancelevels (nodeId);
CREATE INDEX nodecompliancelevels_directiveId ON nodecompliancelevels (nodeId);
CREATE INDEX nodecompliancelevels_runTimestamp ON nodecompliancelevels (runTimestamp);
