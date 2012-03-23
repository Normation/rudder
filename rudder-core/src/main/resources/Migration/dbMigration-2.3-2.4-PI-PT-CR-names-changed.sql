/*
*************************************************************************************
* Copyright 2012 Normation SAS
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


-- Migration script to the new names used in Rudder 2.4

ALTER TABLE expectedReports RENAME COLUMN configurationRuleId to ruleId;
ALTER TABLE expectedReports RENAME COLUMN policyInstanceId to directiveId;


ALTER TABLE RudderSysEvents RENAME COLUMN policyInstanceId to directiveId;
ALTER TABLE RudderSysEvents RENAME COLUMN configurationRuleId to ruleId;


ALTER TABLE PolicyInstances RENAME COLUMN policyInstanceId to directiveId;
ALTER TABLE PolicyInstances RENAME COLUMN policyInstanceName to directiveName;
ALTER TABLE PolicyInstances RENAME COLUMN policyInstanceDescription to directiveDescription;
ALTER TABLE PolicyInstances RENAME COLUMN policyPackageName to techniqueName;
ALTER TABLE PolicyInstances RENAME COLUMN policyPackageVersion to techniqueVersion;
ALTER TABLE PolicyInstances RENAME COLUMN policyPackageDescription to techniqueDescription;
ALTER TABLE PolicyInstances RENAME COLUMN policyTemplateHumanName to techniqueHumanName;

ALTER TABLE PolicyInstances RENAME to Directives;



ALTER TABLE ConfigurationRules RENAME COLUMN id to rulePkeyId;
ALTER TABLE ConfigurationRules RENAME COLUMN configurationRuleId to ruleId;
ALTER TABLE ConfigurationRules RENAME COLUMN isactivated to isenabled;


ALTER TABLE ConfigurationRulesGroups RENAME COLUMN CrId to rulePkeyId;

ALTER TABLE ConfigurationRulesPolicyInstance RENAME COLUMN CrId to rulePkeyId;
ALTER TABLE ConfigurationRulesPolicyInstance RENAME COLUMN policyInstanceId to directiveId;


ALTER TABLE ConfigurationRules RENAME to Rules;
ALTER TABLE ConfigurationRulesGroups RENAME to RulesGroupJoin;
ALTER TABLE ConfigurationRulesPolicyInstance RENAME to RulesDirectivesJoin;

ALTER TABLE confSerialId RENAME TO ruleSerialId;
ALTER TABLE confVersionId RENAME TO ruleVersionId;

ALTER TABLE PolicyInstancesId RENAME TO directivesId;
ALTER TABLE ConfigurationRulesId RENAME TO rulesId;

ALTER INDEX pi_id_start rename to directive_id_start;
ALTER INDEX pi_end rename to directive_end;

ALTER INDEX cr_id_start rename to rule_id_start;
ALTER INDEX cr_end rename to rule_end;


DROP INDEX reports_idx ;
DROP INDEX configurationRuleId_node_idx;

CREATE INDEX executionTimeStamp_idx on RudderSysEvents (executionTimeStamp);
CREATE INDEX component_idx on RudderSysEvents (component);
CREATE INDEX keyValue_idx on RudderSysEvents (keyValue);
CREATE INDEX ruleId_idx on RudderSysEvents (ruleId);

