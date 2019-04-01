create database rudder;
create user rudderreports with encrypted password 'PASSWORD';
\i webapp/sources/rudder/rudder-core/src/main/resources/reportsSchema.sql
\c rudder
grant select on table ruddersysevents to rudderreports;
grant insert on table ruddersysevents to rudderreports;
/* grant truncate on table ruddersysevents to rudderreports; */
grant usage on sequence serial to rudderreports;
