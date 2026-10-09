# Testing postgres repositories

* Status: 
* Deciders:
* Date: 2026-04-24

## Context

The webapp depends on some infrastructure like ldap server and postgres server.

Writing and running tests for parts of applications with such dependencies has long been an annoying problem to solve 
because people use different architectures and OSes.

However, this code is has important to test as any other and solution has emerged during the last decade or so.

## Problem statement

For now, postgres related code is tested only when passing specific properties to the JVM and requires a postgres
server to run before running the test, which make these tests second class citizens in the codebase.

We would like these tests to be first citizens.

* We would like all tests to be run without having specific configuration and knowledge.
* Running a database server can be slow, and we don't want the testsuite to become slow.
* The testsuite should be easy to run on developer computers and on the CI runners

## Solutions

### Using test managed docker containers

Using testcontainers (or similar strategy) allows to specify the required dependency in the test code and make the
tests runnable given that developers and the CI runners have a working Docker daemon.

To mitigate the performance penalty of running a server, the testsuite should ensure a single server is started for all
tests, using some kind of isolation (schema is known to work).

Pro:
* It's known to work on Linux and MacOS, it may also work on Windows
* It's quite fast as long as you run a single server
* It allows to test the code against several server version
* You can run tests from your IDE or command line without any specific configuration
* It opens the door to testing others infrastructure dependencies

Con:
* Requires docker-in-docker usually in CI which can be painful to set up
* Docker can fill filesystems and sometimes break APIs
* Docker can be slow on MacOS
* Tuning postgres can be painful because containers only expose so many configuration parameters

### Using embedded Postgres

Using https://github.com/zonkyio/embedded-postgres it's possible to run a postgres directly on the computer running
the tests. However, it downloads binary from maven and have to mess with architecture.

To mitigate the performance penalty of running a server, the testsuite should ensure a single server is started for all
tests, using some kind of isolation (schema is known to work).

Pro:
* It's quite fast as long as you run a single server
* You can run tests from your IDE or command line without any specific configuration
* It doesn't require a Docker daemon

Con:
* Limited architecture support
* That's kind of a hack to deliver binary from Jar files
* can fill /tmp
* Much less popular than testcontainers means less support

### Decision

### Consequences
