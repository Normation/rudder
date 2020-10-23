create database rudder;
create user rudderreports with encrypted password 'PASSWORD';
\c rudder
\i webapp/sources/rudder/rudder-core/src/main/resources/reportsSchema.sql

grant usage on sequence serial to rudderreports;
grant select on table ruddersysevents to rudderreports;
grant insert on table ruddersysevents to rudderreports;

grant select on table reportsexecution to rudderreports;
grant insert on table reportsexecution to rudderreports;

/* only for test databases */

grant delete on table ruddersysevents to rudderreports;
grant truncate on table ruddersysevents to rudderreports;

grant delete on table reportsexecution to rudderreports;
grant truncate on table reportsexecution to rudderreports;

