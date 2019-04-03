-- pgbench -Urudderreports -h127.0.0.1 -T10 rudder -f bench.sql
BEGIN;
INSERT INTO "ruddersysevents" ("executiondate", "ruleid", "directiveid", "component", "keyvalue", "eventtype", "msg", "policy", "nodeid", "executiontimestamp", "serial") VALUES ('2018-08-24 15:55:01+00', 'server-roles', 'server-roles-directive', 'Check SQL in rudder-webapp.properties', 'None', 'result_success', 'Web interface configuration files are OK (checked SQL password)', 'server-roles', 'root', '2018-08-24 15:55:01+00', '0');
END;
