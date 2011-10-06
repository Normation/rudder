-- Insert test data for the reports data
insert into RudderSysEvents (executionDate, uuid, policyInstanceId, eventType, policy, eventName, msg) values ('2010-05-13 21:51:52.272', 'test', 'test_policyInstance', 'Success', 'test', 'An event name', 'this is a test');
insert into RudderSysEvents (executionDate, uuid, policyInstanceId, eventType, policy, eventName, msg) values ('2010-05-13 21:52:52.272', 'test', 'test_policyInstance2', 'Success', 'test', 'An event name', 'this is a test');
insert into RudderSysEvents (executionDate, uuid, policyInstanceId, eventType, policy, eventName, msg) values ('2010-05-14 20:00:52.272', 'test', 'test_policyInstance', 'Error', 'test', 'An event name', 'this is a test');
insert into RudderSysEvents (executionDate, uuid, policyInstanceId, eventType, policy, eventName, msg) values ('2010-05-14 20:00:00.272', 'test', 'test_policyInstance', 'Warn', 'test', 'An event name', 'this is a test');
insert into RudderSysEvents (executionDate, uuid, policyInstanceId, eventType, policy, eventName, msg) values ('2010-05-15 21:52:52.272', 'test', 'test_policyInstance2', 'Inform', 'test', 'An event name', 'this is a test');
insert into RudderSysEvents (executionDate, uuid, policyInstanceId, eventType, policy, eventName, msg) values ('2010-05-15 21:52:52.272', 'test', 'test_policyInstance2', 'Success', 'test', 'An event name', 'this is a test');


insert into operationReportsInfo (versionId, policyInstanceId, cardinality, executionDateTime) values (1, 'test_policyInstance2', 1, '2010-05-13 21:52:00.000' );
insert into operationReportsInfo (versionId, policyInstanceId, cardinality, executionDateTime) values (1, 'test_policyInstance2', 1, '2010-05-15 21:52:00.000' );
insert into operationReportsInfo (versionId, policyInstanceId, cardinality, executionDateTime) values (1, 'test_policyInstance', 1, '2010-05-14 20:00:00.000' );
insert into operationReportsInfo (versionId, policyInstanceId, cardinality, executionDateTime) values (1, 'test_policyInstance', 1, '2010-05-13 20:00:00.000' );


insert into operationServerList (versionId, serveruuid) values (1, 'test');