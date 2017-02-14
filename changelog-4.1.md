# Change logs for Rudder 4.1.\* versions

Rudder 4.1 is currently the beta version of Rudder.

This page provides a summary of changes for each version. Previous beta
and rc versions are listed below for convenience.

**Main new features in Rudder 4.1:**

  - Add a Relay API:  share files between nodes, launch run on remote run behind relay
  - Import automatically properties to Nodes using http data sources
  - Organize Rules and Directives with tags
  - Implement notifications for different server-side actions and events (hooks)
  - Upgrade CFEngine to 3.10 in Rudder agent 
  - Store node compliance in database
  - Small improvements on UI (properties display, titles, harmonize some form ...)

**Installing, upgrading and testing**

  - Install docs:
    https://www.rudder-project.org/doc-4.1/_install_rudder_server.html
  - Upgrade docs:
    https://www.rudder-project.org/doc-4.1/_upgrade_rudder.html
  - Download links: https://www.rudder-project.org/site/get-rudder/downloads/

We also recommend using the [Rudder
Vagrant](https://github.com/Normation/rudder-vagrant) config if you want
a quick and easy way to get an installation for testing.

**Operating systems supported**

This version provides packages for these operating systems:

  - Rudder server and Rudder relay: **Debian 7, Debian 8, RHEL/CentOS 6, RHEL/CentOS 7
    (64 bits), SLES 11, SLES 12, Ubuntu 14.04, Ubuntu 16.04**
  - Rudder agent: all of the above plus **Debian 5, Debian 6,
    RHEL/CentOS 3, RHEL/CentOS 5, CentOS 7 (32 bits), Fedora 18, SLES
    10, SLES 12, Ubuntu 10.04, Ubuntu 12.04, Ubuntu 12.10**
  - Rudder agent (binary packages available from
    ([Normation](http://www.normation.com)): **Windows Server 2008-2012,
    AIX 5-6-7**

**Note**: As of Rudder 4.1, the rudder-agent-thin and rudder-server-relay packges are
no longer available in Debian 5, Debian 6, Ubuntu 10.04, Ubuntu 12.10, Fedora 18, 
RHEL/CentOS 3 and RHEL/CentOS 5


## Rudder 4.1.0.beta3 (2017-02-14)

### Changes

#### Web - UI & UX

  - Add an option to not display rule status/recent changes in directives screen
    ([\#10157](https://www.rudder-project.org/redmine/issues/10157))
  - Node breakdown - show actual numbers
    ([\#7422](https://www.rudder-project.org/redmine/issues/7422))

#### Documentation

  - Document the relay API
    ([\#9997](https://www.rudder-project.org/redmine/issues/9997))

#### Initial promises & sys tech

  - Use rudder agent run as cfruncommand
    ([\#10081](https://www.rudder-project.org/redmine/issues/10081))

#### Architecture - Dependencies

  - Switch to Scala 2.12 / Lift 3.0 
    ([\#10127](https://www.rudder-project.org/redmine/issues/10127))

#### Architecture - Refactoring

  - Scala actors are deprecated in scala 2.11 and removed in 2.12: update inventory-endpoint
    ([\#10119](https://www.rudder-project.org/redmine/issues/10119))

#### Techniques

  - Deprecate old techniques versions
    ([\#10159](https://www.rudder-project.org/redmine/issues/10159))

### Bug fixes

#### Web - UI & UX

  - Fixed: Status dropdown's display is broken
    ([\#10177](https://www.rudder-project.org/redmine/issues/10177))
  - Fixed: Display of new "Display compliance and recent changes columns on rule summary" setting is broken
    ([\#10173](https://www.rudder-project.org/redmine/issues/10173))
  - Fixed: Included "time ended" in Status dropdown
    ([\#10133](https://www.rudder-project.org/redmine/issues/10133))
  - Fixed: On Group creation, the tooltip on the "Save" button doesn't appear when it is disabled
    ([\#10116](https://www.rudder-project.org/redmine/issues/10116))
  - Fixed: Broken text fields in directive form
    ([\#10164](https://www.rudder-project.org/redmine/issues/10164))

#### Web - Config management

  - Fixed: Deadlock with simultaneous generation and new reports
    ([\#10168](https://www.rudder-project.org/redmine/issues/10168))
  - Fixed: Incomplete logging in expected reports computation
    ([\#10143](https://www.rudder-project.org/redmine/issues/10143))
  - Fixed: Renable WriteSystemTechniquesTest
    ([\#10150](https://www.rudder-project.org/redmine/issues/10150))

#### Documentation

  - Fixed: Broken link in CFEngine doc
    ([\#10151](https://www.rudder-project.org/redmine/issues/10151))

#### Packaging

  - Fixed: the shared-files directory is owned by root
    ([\#10178](https://www.rudder-project.org/redmine/issues/10178))
  - Fixed: openjdk8 cannot be installed if there is a backport in the building os
    ([\#10163](https://www.rudder-project.org/redmine/issues/10163))
  - Fixed: rudder-techniques depends on perl(XML::TreePP)
    ([\#9845](https://www.rudder-project.org/redmine/issues/9845))
  - Fixed: Missing entry in rudder-web.properties after update to 4.1.0.b2
    ([\#10132](https://www.rudder-project.org/redmine/issues/10132))
  - Fixed: rudder-relay has bad "sed" line
    ([\#10131](https://www.rudder-project.org/redmine/issues/10131))

#### Initial promises & sys tech

  - Fixed: Download Shared from node and  to nodes fail because /var/rudder/share-files is non existent (on centos)
    ([\#10085](https://www.rudder-project.org/redmine/issues/10085))

#### API

  - Fixed: Allow relay-api to make asynchronous remote run call with output 
    ([\#10114](https://www.rudder-project.org/redmine/issues/10114))

#### Architecture - Dependencies

  - Fixed: Test broken with "FileNotFoundException /ldap/bootstrap.ldif"
    ([\#10147](https://www.rudder-project.org/redmine/issues/10147))
  - Fixed: warning: Class javax.annotation.Nonnull not found - continuing with a stub.
    ([\#10146](https://www.rudder-project.org/redmine/issues/10146))
  - Fixed: warning: Class javax.annotation.Nonnull not found - continuing with a stub.
    ([\#10146](https://www.rudder-project.org/redmine/issues/10146))
  - Fixed: Use correct repository definition in pom.xml
    ([\#10120](https://www.rudder-project.org/redmine/issues/10120))

#### Server components

  - Fixed: pass ttl through url parameters in sharedfiles api
    ([\#10138](https://www.rudder-project.org/redmine/issues/10138))


## Rudder 4.1.0~beta2 (2017-02-02)

### Changes

#### Web - UI & UX

  - Reorganize Rules page interface ([\#9960](http://www.rudder-project.org/redmine/issues/9960))

#### Web - Config management

  - Remove all datasource code from Rudder main and add needed hooks ([\#10050](http://www.rudder-project.org/redmine/issues/10050))

#### Agent

  - Extend rudder-sign to add new information ([\#9996](http://www.rudder-project.org/redmine/issues/9996))
  - Warn the user if rudder agent is not run as root ([\#9684](http://www.rudder-project.org/redmine/issues/9684))

#### Packaging

  - Permit skipping scala build within packages ([\#10055](http://www.rudder-project.org/redmine/issues/10055))
  - use suse_version instead of sles_version during build ([\#9919](http://www.rudder-project.org/redmine/issues/9919))
  - Automatically set year in Rudder interface at build time ([\#9891](http://www.rudder-project.org/redmine/issues/9891))

#### API

  - Deprecate API v5, v6 and v7, and remove API v2,3,4 ([\#9836](http://www.rudder-project.org/redmine/issues/9836))
  - Remote run API should use relay API ([\#9714](http://www.rudder-project.org/redmine/issues/9714))

#### Architecture - Dependencies

  -  Requires Java8 (jdk8) for Rudder 4.1 ([\#9917](http://www.rudder-project.org/redmine/issues/9917))

### Bug fixes

#### Packaging

  - Fixed: Wrong ncf version dependency in 4.1 ([\#10091](http://www.rudder-project.org/redmine/issues/10091))
  - Fixed: On CentOS relay API uses /etc/httpd/logs/wsgi.18610.0.1.sock ([\#10072](http://www.rudder-project.org/redmine/issues/10072))
  - Fixed: The user trying to open nodes list in relay-api is not rudder ([\#10068](http://www.rudder-project.org/redmine/issues/10068))
  - Fixed: Wrong permission for /etc/sudoers.d/rudder-relay file on Sles ([\#10065](http://www.rudder-project.org/redmine/issues/10065))
  - Fixed: Remove rudder-apache-common.conf in postinstall ([\#10041](http://www.rudder-project.org/redmine/issues/10041))
  - Fixed: Not having set %{real_name} does operate on /bin ([\#10003](http://www.rudder-project.org/redmine/issues/10003))
  - Fixed: Allow to restrict edits on sudoers during install ([\#10001](http://www.rudder-project.org/redmine/issues/10001))

#### Web - Config management

  - Fixed: When I save a Directive, after cliking "save", it's not possible to scroll anymore in the Directive tree ([\#10010](http://www.rudder-project.org/redmine/issues/10010))

#### Server components

  - Fixed: the relay api shoud read nodeslist on each call ([\#10111](http://www.rudder-project.org/redmine/issues/10111))

### Release notes

Special thanks go out to the following individuals who invested time,
patience, testing, patches or bug reports to make this version of Rudder
better:

  - Janos Mattyasovszky

This software is in beta status and contains several new features but we
have tested it and believe it to be free of any critical bugs.¬The use
on production systems is not encouraged at this time and is at your own
risk. However, we do encourage testing, and welcome all and any
feedback\!


## Rudder 4.1.0~beta1 (2017-01-17)

### Changes

#### Web - Config management

  - Add tags in Directive/Rules ([\#9733](http://www.rudder-project.org/redmine/issues/9733))
  - Import node properties from external data sources ([\#9698](http://www.rudder-project.org/redmine/issues/9698))

#### API

  - Add a Relay API:  share files between nodes, launch run on remote run behind relay ([\#9707](http://www.rudder-project.org/redmine/issues/9707))

#### Server components

  - Implement notifications for different server-side actions and events (hooks) ([\#8353](http://www.rudder-project.org/redmine/issues/8353))

#### Web - UI & UX

  - Improve Json display in the Nodes properties tab ([\#9984](http://www.rudder-project.org/redmine/issues/9984))

#### Packaging

  - ncf-api-venv user should not have access to a shell ([\#9993](http://www.rudder-project.org/redmine/issues/9993))
  - Build slapd with lmdb ([\#9839](http://www.rudder-project.org/redmine/issues/9839))
  - Upgrade CFEngine in Rudder agent to 3.10 ([\#9737](http://www.rudder-project.org/redmine/issues/9737))

#### System integration

  - Do not create a temporary cron a postinstall ([\#9860](http://www.rudder-project.org/redmine/issues/9860))

#### Architecture - Refactoring

  - Store node compliance in database ([\#9645](http://www.rudder-project.org/redmine/issues/9645))

#### Architecture - Internal libs

  - Change pom version on master to 4.1 ([\#9686](http://www.rudder-project.org/redmine/issues/9686))

#### Documentation

  - Prepare manual for 4.1 ([\#9887](http://www.rudder-project.org/redmine/issues/9887))
  - Change doc title for 4.1 ([\#9753](http://www.rudder-project.org/redmine/issues/9753))

### Bug fixes

#### Web - UI & UX

  - Fixed: Put in bold rule form's required fields label ([\#9949](http://www.rudder-project.org/redmine/issues/9949))
  - Fixed: Nothing happens when trying to save a Directive ([\#9948](http://www.rudder-project.org/redmine/issues/9948))
  - Fixed: Put in bold "Technique version" label ([\#9935](http://www.rudder-project.org/redmine/issues/9935))

#### Packaging

  - Fixed: build-caching doesn't work ([\#9921](http://www.rudder-project.org/redmine/issues/9921))
  - Fixed: virtualenv doesn't work in the build environment ([\#9824](http://www.rudder-project.org/redmine/issues/9824))

#### Initial promises & sys tech

  - Fixed: FusionInventory --scan-homedirs should not be on by default ([\#7421](http://www.rudder-project.org/redmine/issues/7421))

#### Documentation

  - Fixed: We should disable link tests for the manual on master  ([\#9803](http://www.rudder-project.org/redmine/issues/9803))

#### Web - Config management

  - Fixed: Rule details text can be misleading ([\#6143](http://www.rudder-project.org/redmine/issues/6143))

### Release notes

Special thanks go out to the following individuals who invested time,
patience, testing, patches or bug reports to make this version of Rudder
better:

  - Florial Heigl 
  - Janos Mattyasovszky

This software is in beta status and contains several new features but we
have tested it and believe it to be free of any critical bugs.¬The use
on production systems is not encouraged at this time and is at your own
risk. However, we do encourage testing, and welcome all and any
feedback\!

