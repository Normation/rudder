/*
*************************************************************************************
* Copyright 2019 Normation SAS
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


ALTER TABLE ruddersysevents set (autovacuum_vacuum_scale_factor = 0.005);
ALTER TABLE archivedruddersysevents set (autovacuum_vacuum_scale_factor = 0.005);
ALTER TABLE reportsexecution set (autovacuum_vacuum_scale_factor = 0.05);
ALTER TABLE nodes_info set (autovacuum_vacuum_threshold = 0);

ALTER TABLE nodeconfigurations set (autovacuum_vacuum_threshold = 0);
ALTER TABLE archivednodeconfigurations set (autovacuum_vacuum_threshold = 0);
ALTER TABLE nodecompliance set (autovacuum_vacuum_threshold = 0);
ALTER TABLE nodecompliance set (autovacuum_vacuum_scale_factor = 0.1);
ALTER TABLE archivednodecompliance set (autovacuum_vacuum_threshold = 0);
ALTER TABLE archivednodecompliance set (autovacuum_vacuum_scale_factor = 0.1);
ALTER TABLE nodecompliancelevels set (autovacuum_vacuum_scale_factor = 0.05);
ALTER TABLE rudderproperties set (autovacuum_vacuum_threshold = 0);
ALTER TABLE statusupdate set (autovacuum_vacuum_threshold = 0);
ALTER TABLE groups set (autovacuum_vacuum_scale_factor = 0.05);
ALTER TABLE directives set (autovacuum_vacuum_scale_factor = 0.05);
ALTER TABLE rules set (autovacuum_vacuum_scale_factor = 0.05);
ALTER TABLE nodes set (autovacuum_vacuum_scale_factor = 0.05);



